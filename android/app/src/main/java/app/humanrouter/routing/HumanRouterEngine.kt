package app.humanrouter.routing

import android.content.Context
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
    private val railWaypointIndex: RailWaypointIndex? by lazy {
        RailWaypointIndex.openOrNull(runtimeRoot)
    }

    sealed interface PlanResult {
        data class Success(
            val routes: List<RankedRoute>,
            val serviceDate: LocalDate,
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
        LastPlanStore.seed = null
        val runtimeSurface = File(runtimeRoot, "surface")
        if (!File(runtimeSurface, "manifest.json").exists()) {
            return PlanResult.RuntimeMissing("Транспортные данные ещё не установлены")
        }

        return runCatching {
            SurfaceScheduleRepository(context).use { repository ->
                val serviceDate = repository.serviceDate
                    .takeIf { it.isNotBlank() }
                    ?.let(LocalDate::parse)
                    ?: return@use PlanResult.Failure("В runtime отсутствует service_date")

                val departureDate = Instant.ofEpochSecond(departureEpochSec)
                    .atZone(zoneId)
                    .toLocalDate()
                val serviceMidnight = serviceDate.atStartOfDay(zoneId).toEpochSecond()
                val serviceSeconds = (departureEpochSec - serviceMidnight).toInt()

                if (serviceSeconds !in 0..MAX_SERVICE_SECONDS) {
                    return@use PlanResult.ScheduleUnavailable(serviceDate, departureDate)
                }

                val surfaceRouter = SurfaceCsaRouter(repository, preferences, walkGraph)
                val offsets = if (alternatives) ALTERNATIVE_DEPARTURE_OFFSETS else intArrayOf(0)
                val candidates = LinkedHashMap<String, RouteCandidate>()
                var exactWalking = false

                // Exact timetable surface routes and the direct-walk fallback embedded in CSA.
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
                    val normalized = surface.fastest.copy(
                        requestedDepartureEpochSec = departureEpochSec
                    )
                    candidates.putIfAbsent(normalized.id, normalized)
                }

                // Direct METRO/MCC option.
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

                // Real mixed surface<->rail beams. Surface stages use the actual BUS/TRAM timetable;
                // each rail or second surface stage starts at the previous stage's real arrival time.
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
                        candidates.putIfAbsent(candidate.id, candidate)
                    }
                }

                if (candidates.isEmpty()) {
                    return@use PlanResult.Failure("Маршрутный движок не вернул ни одного варианта")
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
                    return@use PlanResult.Failure("После фильтрации не осталось допустимых маршрутов")
                }

                LastPlanStore.seed = ActivePlanSeed(
                    destination = destination,
                    baselineArrivalEpochSec = ordered.first().route.arrivalEpochSec,
                    routeId = ordered.first().route.id
                )

                PlanResult.Success(
                    routes = ordered,
                    serviceDate = serviceDate,
                    exactWalkingGraph = exactWalking
                )
            }
        }.getOrElse { error ->
            LastPlanStore.seed = null
            PlanResult.Failure(error.message ?: error.javaClass.simpleName)
        }
    }

    companion object {
        private const val MAX_SERVICE_SECONDS = 30 * 60 * 60
        private const val MAX_VISIBLE_OPTIONS = 4
        private val ALTERNATIVE_DEPARTURE_OFFSETS = intArrayOf(0, 120, 300, 600)
    }
}
