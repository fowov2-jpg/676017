package app.humanrouter.routing

import android.content.Context
import app.humanrouter.RuntimeInstaller
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal class HumanRouterEngine(
    private val context: Context,
    private val preferences: RoutePreferences = RoutePreferences(),
    private val zoneId: ZoneId = ZoneId.of("Europe/Moscow")
) {
    private val runtimeRoot = File(context.filesDir, "runtime")
    private val walkGraph: RuntimeWalkGraph? by lazy {
        RuntimeWalkGraph.openOrNull(runtimeRoot, preferences)
    }
    private val railRouter: RailGraphRouter? by lazy {
        RailGraphRouter.openOrNull(runtimeRoot, preferences, walkGraph)
    }
    private val railTimetableRouter: RailTimetableRouter? by lazy {
        RailTimetableRouter.openOrNull(context, runtimeRoot, preferences, walkGraph, zoneId)
    }
    private val railWaypointIndex: RailWaypointIndex? by lazy {
        RailWaypointIndex.openOrNull(runtimeRoot)
    }
    private val railExternalTransfers: RailExternalTransferComposer? by lazy {
        RailExternalTransferComposer.openOrNull(runtimeRoot, railRouter, walkGraph, preferences)
    }

    sealed interface PlanResult {
        data class Success(
            val routes: List<RankedRoute>,
            val serviceDate: LocalDate?,
            val railTimetableEffectiveFrom: LocalDate?,
            val exactWalkingGraph: Boolean
        ) : PlanResult {
            init { require(routes.isNotEmpty()) }
            val fastest: RankedRoute get() = routes.first()
        }

        data class RuntimeMissing(val reason: String) : PlanResult
        data class ScheduleUnavailable(val serviceDate: LocalDate?, val requestedDate: LocalDate) : PlanResult
        data class Failure(val reason: String) : PlanResult
    }

    fun planFastest(
        origin: GeoPoint,
        destination: GeoPoint,
        departureEpochSec: Long
    ): PlanResult = planInternal(origin, destination, departureEpochSec, alternatives = false)

    fun planOptions(
        origin: GeoPoint,
        destination: GeoPoint,
        departureEpochSec: Long
    ): PlanResult = planInternal(origin, destination, departureEpochSec, alternatives = true)

    private fun planInternal(
        origin: GeoPoint,
        destination: GeoPoint,
        departureEpochSec: Long,
        alternatives: Boolean
    ): PlanResult {
        if (RuntimeInstaller.transactionInProgress(context.filesDir)) {
            return PlanResult.RuntimeMissing("Обновление транспортных данных завершается")
        }
        LastPlanStore.seed = null
        val runtimeSurface = File(runtimeRoot, "surface")
        return runCatching {
            val candidates = LinkedHashMap<String, RouteCandidate>()
            var exactWalking = false
            directWalk(origin, destination, departureEpochSec)?.let { walk ->
                candidates[walk.id] = walk
                exactWalking = true
            }

            var serviceDate: LocalDate? = null
            val requestedDate = Instant.ofEpochSecond(departureEpochSec)
                .atZone(zoneId)
                .toLocalDate()
            var scheduleUsable = false
            val surfaceManifest = File(runtimeSurface, "manifest.json")
            if (surfaceManifest.exists()) {
                SurfaceScheduleRepository(context).use { repository ->
                    serviceDate = repository.serviceDate
                        .takeIf(String::isNotBlank)
                        ?.let(LocalDate::parse)
                    val date = serviceDate
                    if (date != null) {
                        val scheduleDecision = SurfaceSchedulePolicy.evaluate(date, requestedDate)
                        val serviceMidnight = requestedDate.atStartOfDay(zoneId).toEpochSecond()
                        val serviceSeconds = (departureEpochSec - serviceMidnight).toInt()
                        if (scheduleDecision.usable && serviceSeconds in 0..MAX_SERVICE_SECONDS) {
                            val staleSchedule = scheduleDecision.stale
                            scheduleUsable = true
                            val surfaceRouter = SurfaceCsaRouter(repository, preferences, walkGraph)
                            val offsets = if (alternatives) ALTERNATIVE_DEPARTURE_OFFSETS else intArrayOf(0)

                            for (offset in offsets) {
                                val shifted = serviceSeconds + offset
                                if (shifted !in 0..MAX_SERVICE_SECONDS) continue
                                val surface = surfaceRouter.findFastest(
                                    origin = origin,
                                    destination = destination,
                                    departureServiceSec = shifted,
                                    serviceMidnightEpochSec = serviceMidnight
                                )
                                exactWalking = exactWalking || surface.usedOsmWalkingGraph
                                var normalized = (surface.fastest ?: continue).copy(
                                    requestedDepartureEpochSec = departureEpochSec
                                )
                                if (staleSchedule) normalized = markStaleSurfaceTiming(normalized)
                                candidates.putIfAbsent(normalized.id, normalized)
                            }

                            val rail = railRouter
                            val index = railWaypointIndex
                            if (rail != null && index != null) {
                                val mixed = MultimodalComposer(
                                    surface = surfaceRouter,
                                    rail = rail,
                                    railIndex = index,
                                    serviceMidnightEpochSec = serviceMidnight
                                ).findCandidates(
                                    origin = origin,
                                    destination = destination,
                                    departureEpochSec = departureEpochSec,
                                    broadSearch = alternatives
                                )
                                for (candidate in mixed) {
                                    val normalized = if (staleSchedule) markStaleSurfaceTiming(candidate) else candidate
                                    candidates.putIfAbsent(normalized.id, normalized)
                                }
                            }
                        }
                    }
                }
            }

            railTimetableRouter?.findCandidates(
                origin = origin,
                destination = destination,
                departureEpochSec = departureEpochSec,
                broadSearch = alternatives
            )?.forEach { candidate ->
                candidates.putIfAbsent(candidate.id, candidate)
                exactWalking = exactWalking || walkGraph != null
            }

            val rail = railRouter
            if (rail != null) {
                rail.findFastest(
                    origin = origin,
                    destination = destination,
                    departureEpochSec = departureEpochSec
                )?.let { candidate ->
                    candidates.putIfAbsent(candidate.id, candidate)
                    exactWalking = exactWalking || walkGraph != null
                }
            }

            railExternalTransfers?.findCandidates(
                origin = origin,
                destination = destination,
                departureEpochSec = departureEpochSec,
                broadSearch = alternatives
            )?.forEach { candidate ->
                candidates.putIfAbsent(candidate.id, candidate)
                exactWalking = exactWalking || walkGraph != null
            }

            if (candidates.isEmpty()) {
                return@runCatching when {
                    surfaceManifest.exists() && !scheduleUsable ->
                        PlanResult.ScheduleUnavailable(serviceDate, requestedDate)
                    !surfaceManifest.exists() && rail == null && walkGraph == null ->
                        PlanResult.RuntimeMissing("Транспортные данные ещё не установлены")
                    else -> PlanResult.Failure("Для выбранных точек маршрут не найден")
                }
            }

            val all = candidates.values.toList()
            val selected = LinkedHashMap<String, RankedRoute>()
            val objectives = listOf(
                RouteObjective.FASTEST,
                RouteObjective.RELIABLE,
                RouteObjective.LESS_WALKING,
                RouteObjective.FEWER_TRANSFERS
            )

            for (objective in objectives) {
                val ranked = RouteRanker.rank(all, objective, preferences).firstOrNull() ?: continue
                selected.putIfAbsent(ranked.route.id, ranked)
            }
            for (ranked in RouteRanker.rank(all, RouteObjective.FASTEST, preferences)) {
                if (selected.size >= MAX_VISIBLE_OPTIONS) break
                selected.putIfAbsent(ranked.route.id, ranked)
            }

            val ordered = selected.values
                .sortedWith(
                    compareBy<RankedRoute> { it.expectedArrivalEpochSec }
                        .thenByDescending { it.transferSuccessProbability }
                )
                .take(MAX_VISIBLE_OPTIONS)

            if (ordered.isEmpty()) {
                return@runCatching PlanResult.Failure("После фильтрации не осталось допустимых маршрутов")
            }

            LastPlanStore.select(ordered.first().route, destination)

            PlanResult.Success(
                routes = ordered,
                serviceDate = serviceDate,
                railTimetableEffectiveFrom = railTimetableRouter?.effectiveFrom,
                exactWalkingGraph = exactWalking
            )
        }.getOrElse { error ->
            LastPlanStore.seed = null
            PlanResult.Failure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun directWalk(
        origin: GeoPoint,
        destination: GeoPoint,
        departureEpochSec: Long
    ): RouteCandidate? {
        val cost = walkGraph?.shortestWalk(
            from = origin,
            to = destination,
            maxSeconds = DIRECT_WALK_MAX_SECONDS,
            maxMeters = DIRECT_WALK_MAX_METERS
        ) ?: return null
        val leg = RouteLeg(
            mode = TransportMode.WALK,
            from = RoutePlace("origin", "Откуда", origin),
            to = RoutePlace("destination", "Куда", destination),
            departureEpochSec = departureEpochSec,
            arrivalEpochSec = departureEpochSec + cost.seconds,
            walkMeters = cost.meters,
            uncertaintySeconds = 30,
            realtimeConfidence = 0.95
        )
        return RouteCandidate(
            id = "walk-${origin.hashCode().toUInt().toString(16)}-${destination.hashCode().toUInt().toString(16)}",
            requestedDepartureEpochSec = departureEpochSec,
            legs = listOf(leg)
        )
    }

    private fun markStaleSurfaceTiming(route: RouteCandidate): RouteCandidate = route.copy(
        legs = route.legs.map { leg ->
            if (leg.mode == TransportMode.BUS || leg.mode == TransportMode.TRAM) {
                leg.copy(
                    uncertaintySeconds = leg.uncertaintySeconds + STALE_SURFACE_UNCERTAINTY_SECONDS,
                    realtimeConfidence = minOf(leg.realtimeConfidence, STALE_SURFACE_CONFIDENCE)
                )
            } else {
                leg
            }
        }
    )

    companion object {
        private const val MAX_SERVICE_SECONDS = 30 * 60 * 60
        private const val MAX_VISIBLE_OPTIONS = 4
        private const val DIRECT_WALK_MAX_METERS = 20_000
        private const val DIRECT_WALK_MAX_SECONDS = 5 * 60 * 60
        private const val STALE_SURFACE_UNCERTAINTY_SECONDS = 20 * 60
        private const val STALE_SURFACE_CONFIDENCE = 0.15
        private val ALTERNATIVE_DEPARTURE_OFFSETS = intArrayOf(0, 120, 300, 600)
    }
}
