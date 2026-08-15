package app.humanrouter.routing

import android.content.Context
import app.humanrouter.RuntimeInstaller
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.PriorityQueue
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Low-latency first-route engine.
 *
 * The production HumanRouterEngine remains the authority and refines this preview in the
 * background. This router intentionally uses a much smaller search space for the first paint:
 *
 *  * METRO/MCC use a true bidirectional meet-in-the-middle search over the static rail graph;
 *  * BUS/TRAM use the timetable CSA with geometric (not OSM-Dijkstra) access/transfer links and a
 *    short horizon;
 *  * rail and surface searches run concurrently and the best completed candidate wins;
 *  * the expensive exact walking graph, MCD/train timetable and broad multimodal alternatives are
 *    left to HumanRouterEngine.planOptions after a preview is already visible.
 *
 * The preview is deliberately marked with larger uncertainty. It is a responsiveness mechanism,
 * not a replacement for the full route calculation.
 */
internal class FastMeetRouter private constructor(
    context: Context,
    private val preferences: RoutePreferences,
    private val runtimeToken: String
) {
    private val appContext = context.applicationContext
    private val runtimeRoot = File(appContext.filesDir, "runtime")
    private val zoneId = ZoneId.of("Europe/Moscow")
    private val workers = Executors.newFixedThreadPool(3)

    private val railIndex: FastRailMeetIndex? by lazy {
        FastRailMeetIndex.openOrNull(
            File(runtimeRoot, "rail/graph.json"),
            preferences
        )
    }

    @Volatile
    private var surfaceSession: SurfaceSession? = null

    fun prewarm() {
        workers.execute { runCatching { railIndex } }
        workers.execute { runCatching { getSurfaceSession() } }
    }

    fun planPreview(
        origin: GeoPoint,
        destination: GeoPoint,
        departureEpochSec: Long,
        budgetMs: Long = DEFAULT_PREVIEW_BUDGET_MS
    ): HumanRouterEngine.PlanResult {
        if (RuntimeInstaller.transactionInProgress(appContext.filesDir)) {
            return HumanRouterEngine.PlanResult.RuntimeMissing("Обновление транспортных данных завершается")
        }

        val candidates = ArrayList<RouteCandidate>(3)
        approximateDirectWalk(origin, destination, departureEpochSec)?.let(candidates::add)

        val completion = ExecutorCompletionService<PreviewCandidate?>(workers)
        val futures = ArrayList<Future<PreviewCandidate?>>(2)
        futures += completion.submit<PreviewCandidate?> {
            railIndex?.findFastest(origin, destination, departureEpochSec)?.let {
                PreviewCandidate(it, null)
            }
        }
        futures += completion.submit<PreviewCandidate?> {
            surfacePreview(origin, destination, departureEpochSec)
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
            budgetMs.coerceIn(MIN_PREVIEW_BUDGET_MS, MAX_PREVIEW_BUDGET_MS)
        )
        var serviceDate: LocalDate? = null
        var completed = 0
        while (completed < futures.size) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) break
            val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
            completed++
            val preview = runCatching { future.get() }.getOrNull() ?: continue
            preview.route?.let(candidates::add)
            if (serviceDate == null) serviceDate = preview.serviceDate
        }
        futures.forEach { if (!it.isDone) it.cancel(true) }

        val ranked = RouteRanker.rank(
            candidates = candidates.distinctBy { it.id },
            objective = RouteObjective.FASTEST,
            preferences = preferences
        ).firstOrNull()

        if (ranked == null) {
            return HumanRouterEngine.PlanResult.Failure("Быстрый маршрут не найден; выполняется расширенный поиск")
        }

        LastPlanStore.select(ranked.route, destination)
        return HumanRouterEngine.PlanResult.Success(
            routes = listOf(ranked),
            serviceDate = serviceDate,
            railTimetableEffectiveFrom = null,
            exactWalkingGraph = false
        )
    }

    private fun surfacePreview(
        origin: GeoPoint,
        destination: GeoPoint,
        departureEpochSec: Long
    ): PreviewCandidate? {
        val session = getSurfaceSession() ?: return null
        val requestedDate = Instant.ofEpochSecond(departureEpochSec).atZone(zoneId).toLocalDate()
        val serviceDate = runCatching { LocalDate.parse(session.repository.serviceDate) }.getOrNull()
            ?: return PreviewCandidate(null, null)

        val dayDistance = kotlin.math.abs(ChronoUnit.DAYS.between(serviceDate, requestedDate))
        if (dayDistance > MAX_SURFACE_DATE_DISTANCE_DAYS) {
            return PreviewCandidate(null, serviceDate)
        }

        val midnight = requestedDate.atStartOfDay(zoneId).toEpochSecond()
        val serviceSeconds = (departureEpochSec - midnight).toInt()
        if (serviceSeconds !in 0..MAX_SERVICE_SECONDS) return PreviewCandidate(null, serviceDate)

        val result = synchronized(session.router) {
            session.router.findFastest(
                origin = origin,
                destination = destination,
                departureServiceSec = serviceSeconds,
                serviceMidnightEpochSec = midnight,
                horizonSeconds = FAST_SURFACE_HORIZON_SECONDS
            )
        }
        return PreviewCandidate(
            route = result.fastest?.let(::markPreviewTiming),
            serviceDate = serviceDate
        )
    }

    @Synchronized
    private fun getSurfaceSession(): SurfaceSession? {
        val manifest = File(runtimeRoot, "surface/manifest.json")
        if (!manifest.exists()) return null
        val token = "${manifest.length()}:${manifest.lastModified()}"
        surfaceSession?.let { existing ->
            if (existing.token == token) return existing
            runCatching { existing.repository.close() }
            surfaceSession = null
        }
        return runCatching {
            val repository = SurfaceScheduleRepository(appContext)
            SurfaceSession(
                token = token,
                repository = repository,
                // A fast preview deliberately avoids OSM walking Dijkstra. The exact background
                // route restores pedestrian geometry and timing a moment later.
                router = SurfaceCsaRouter(repository, preferences, walkGraph = null)
            ).also { surfaceSession = it }
        }.getOrNull()
    }

    private fun approximateDirectWalk(
        origin: GeoPoint,
        destination: GeoPoint,
        departureEpochSec: Long
    ): RouteCandidate? {
        val geometricMeters = haversineMeters(origin, destination)
        if (geometricMeters > DIRECT_PREVIEW_LIMIT_METERS) return null
        val meters = ceil(geometricMeters * WALK_DETOUR_FACTOR).toInt().coerceAtLeast(1)
        val seconds = ceil(meters / preferences.walkingSpeedMetersPerSecond).toInt().coerceAtLeast(1)
        val leg = RouteLeg(
            mode = TransportMode.WALK,
            from = RoutePlace("origin", "Откуда", origin),
            to = RoutePlace("destination", "Куда", destination),
            departureEpochSec = departureEpochSec,
            arrivalEpochSec = departureEpochSec + seconds,
            walkMeters = meters,
            uncertaintySeconds = 120,
            realtimeConfidence = 0.68,
            geometry = listOf(origin, destination)
        )
        return RouteCandidate(
            id = "fast-walk-${origin.hashCode().toUInt().toString(16)}-${destination.hashCode().toUInt().toString(16)}",
            requestedDepartureEpochSec = departureEpochSec,
            legs = listOf(leg)
        )
    }

    private fun markPreviewTiming(route: RouteCandidate): RouteCandidate = route.copy(
        id = "fast-${route.id}",
        legs = route.legs.map { leg ->
            leg.copy(
                uncertaintySeconds = maxOf(leg.uncertaintySeconds, when (leg.mode) {
                    TransportMode.WALK -> 90
                    TransportMode.BUS, TransportMode.TRAM -> 240
                    else -> 120
                }),
                realtimeConfidence = min(leg.realtimeConfidence, 0.62)
            )
        }
    )

    private data class PreviewCandidate(
        val route: RouteCandidate?,
        val serviceDate: LocalDate?
    )

    private data class SurfaceSession(
        val token: String,
        val repository: SurfaceScheduleRepository,
        val router: SurfaceCsaRouter
    )

    companion object {
        private const val DEFAULT_PREVIEW_BUDGET_MS = 820L
        private const val MIN_PREVIEW_BUDGET_MS = 250L
        private const val MAX_PREVIEW_BUDGET_MS = 1_300L
        private const val FAST_SURFACE_HORIZON_SECONDS = 75 * 60
        private const val MAX_SURFACE_DATE_DISTANCE_DAYS = 3L
        private const val MAX_SERVICE_SECONDS = 36 * 60 * 60
        private const val DIRECT_PREVIEW_LIMIT_METERS = 2_400.0
        private const val WALK_DETOUR_FACTOR = 1.18
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        @Volatile
        private var cached: FastMeetRouter? = null
        private var cachedKey: String = ""

        @Synchronized
        fun get(context: Context, preferences: RoutePreferences): FastMeetRouter {
            val runtimeRoot = File(context.applicationContext.filesDir, "runtime")
            val manifest = File(runtimeRoot, "surface/manifest.json")
            val rail = File(runtimeRoot, "rail/graph.json")
            val runtimeToken = listOf(
                manifest.length(), manifest.lastModified(),
                rail.length(), rail.lastModified()
            ).joinToString(":")
            val key = listOf(
                runtimeToken,
                preferences.preferLessWalking,
                preferences.preferFewerTransfers,
                preferences.walkingSpeedMetersPerSecond,
                preferences.maxWalkMeters
            ).joinToString(":")
            val existing = cached
            if (existing != null && key == cachedKey) return existing
            return FastMeetRouter(context, preferences, runtimeToken).also {
                cached = it
                cachedKey = key
            }
        }

        private fun haversineMeters(from: GeoPoint, to: GeoPoint): Double {
            val p1 = from.lat * PI / 180.0
            val p2 = to.lat * PI / 180.0
            val dLat = (to.lat - from.lat) * PI / 180.0
            val dLon = (to.lon - from.lon) * PI / 180.0
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
            return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
        }
    }
}

/**
 * Small static METRO/MCC graph optimized for first paint. It expands both endpoints and terminates
 * as soon as the two settled frontiers cannot beat the best meeting point already found.
 */
private class FastRailMeetIndex private constructor(
    private val preferences: RoutePreferences,
    graph: JSONObject
) {
    private data class Node(
        val index: Int,
        val id: String,
        val name: String,
        val point: GeoPoint,
        val modes: MutableSet<TransportMode> = LinkedHashSet()
    )

    private data class Edge(
        val from: Int,
        val to: Int,
        val seconds: Int,
        val lineKey: String?,
        val lineName: String?,
        val mode: TransportMode,
        val transfer: Boolean = false
    )

    private data class QueueItem(val node: Int, val seconds: Int)
    private data class Access(val node: Int, val meters: Int, val walkSeconds: Int, val overheadSeconds: Int)
    private data class Egress(val node: Int, val meters: Int, val walkSeconds: Int, val exitSeconds: Int)

    private val nodes = ArrayList<Node>()
    private val indexByOsmId = HashMap<String, Int>()
    private val forward = ArrayList<MutableList<Edge>>()
    private val reverse = ArrayList<MutableList<Edge>>()
    private val groups = HashMap<String, MutableList<Int>>()

    init {
        load(graph)
        addTransferEdges()
    }

    fun findFastest(
        origin: GeoPoint,
        destination: GeoPoint,
        departureEpochSec: Long
    ): RouteCandidate? {
        if (nodes.isEmpty()) return null
        val access = nearest(origin, towardDestination = false)
        val egress = nearest(destination, towardDestination = true)
        if (access.isEmpty() || egress.isEmpty()) return null

        val n = nodes.size
        val inf = Int.MAX_VALUE / 4
        val distForward = IntArray(n) { inf }
        val distBackward = IntArray(n) { inf }
        val prevNode = IntArray(n) { -1 }
        val prevEdge = arrayOfNulls<Edge>(n)
        val nextNode = IntArray(n) { -1 }
        val nextEdge = arrayOfNulls<Edge>(n)
        val startAccess = arrayOfNulls<Access>(n)
        val endEgress = arrayOfNulls<Egress>(n)
        val settledForward = BooleanArray(n)
        val settledBackward = BooleanArray(n)
        val qForward = PriorityQueue(compareBy<QueueItem> { it.seconds })
        val qBackward = PriorityQueue(compareBy<QueueItem> { it.seconds })

        access.forEach { link ->
            val total = link.walkSeconds + link.overheadSeconds
            if (total < distForward[link.node]) {
                distForward[link.node] = total
                startAccess[link.node] = link
                qForward += QueueItem(link.node, total)
            }
        }
        egress.forEach { link ->
            val total = link.walkSeconds + link.exitSeconds
            if (total < distBackward[link.node]) {
                distBackward[link.node] = total
                endEgress[link.node] = link
                qBackward += QueueItem(link.node, total)
            }
        }

        var best = inf
        var meeting = -1

        fun updateMeeting(node: Int) {
            val a = distForward[node]
            val b = distBackward[node]
            if (a >= inf || b >= inf) return
            val total = a + b
            if (total < best) {
                best = total
                meeting = node
            }
        }

        while (qForward.isNotEmpty() && qBackward.isNotEmpty()) {
            while (qForward.isNotEmpty() && qForward.peek().seconds != distForward[qForward.peek().node]) qForward.remove()
            while (qBackward.isNotEmpty() && qBackward.peek().seconds != distBackward[qBackward.peek().node]) qBackward.remove()
            if (qForward.isEmpty() || qBackward.isEmpty()) break
            if (qForward.peek().seconds.toLong() + qBackward.peek().seconds.toLong() >= best.toLong()) break

            if (qForward.peek().seconds <= qBackward.peek().seconds) {
                val item = qForward.remove()
                if (settledForward[item.node]) continue
                settledForward[item.node] = true
                updateMeeting(item.node)
                for (edge in forward[item.node]) {
                    val candidate = item.seconds + edge.seconds
                    if (candidate < distForward[edge.to]) {
                        distForward[edge.to] = candidate
                        prevNode[edge.to] = item.node
                        prevEdge[edge.to] = edge
                        startAccess[edge.to] = startAccess[item.node]
                        qForward += QueueItem(edge.to, candidate)
                    }
                }
            } else {
                val item = qBackward.remove()
                if (settledBackward[item.node]) continue
                settledBackward[item.node] = true
                updateMeeting(item.node)
                for (edge in reverse[item.node]) {
                    val candidate = item.seconds + edge.seconds
                    if (candidate < distBackward[edge.from]) {
                        distBackward[edge.from] = candidate
                        nextNode[edge.from] = item.node
                        nextEdge[edge.from] = edge
                        endEgress[edge.from] = endEgress[item.node]
                        qBackward += QueueItem(edge.from, candidate)
                    }
                }
            }
        }

        if (meeting < 0) return null
        val accessUsed = startAccess[meeting] ?: return null
        val egressUsed = endEgress[meeting] ?: return null

        val firstHalf = ArrayList<Edge>()
        var cursor = meeting
        while (prevNode[cursor] >= 0) {
            val edge = prevEdge[cursor] ?: return null
            firstHalf += edge
            cursor = prevNode[cursor]
        }
        val startNode = cursor
        firstHalf.reverse()

        val secondHalf = ArrayList<Edge>()
        cursor = meeting
        while (nextNode[cursor] >= 0) {
            val edge = nextEdge[cursor] ?: return null
            secondHalf += edge
            cursor = nextNode[cursor]
        }
        val endNode = cursor
        val path = firstHalf + secondHalf
        if (path.none { !it.transfer }) return null

        return buildCandidate(
            origin = origin,
            destination = destination,
            departureEpochSec = departureEpochSec,
            startNode = startNode,
            endNode = endNode,
            access = accessUsed,
            egress = egressUsed,
            path = path
        )
    }

    private fun buildCandidate(
        origin: GeoPoint,
        destination: GeoPoint,
        departureEpochSec: Long,
        startNode: Int,
        endNode: Int,
        access: Access,
        egress: Egress,
        path: List<Edge>
    ): RouteCandidate {
        val legs = ArrayList<RouteLeg>()
        var time = departureEpochSec
        val originPlace = RoutePlace("origin", "Откуда", origin)
        val destinationPlace = RoutePlace("destination", "Куда", destination)

        val accessArrival = time + access.walkSeconds
        legs += RouteLeg(
            mode = TransportMode.WALK,
            from = originPlace,
            to = nodes[startNode].place(),
            departureEpochSec = time,
            arrivalEpochSec = accessArrival,
            walkMeters = access.meters,
            uncertaintySeconds = 90,
            realtimeConfidence = 0.65,
            geometry = listOf(origin, nodes[startNode].point)
        )
        time = accessArrival
        var firstRail = true
        var index = 0
        while (index < path.size) {
            val edge = path[index]
            if (edge.transfer) {
                legs += RouteLeg(
                    mode = TransportMode.WALK,
                    from = nodes[edge.from].place(),
                    to = nodes[edge.to].place(),
                    departureEpochSec = time,
                    arrivalEpochSec = time + edge.seconds,
                    walkMeters = 0,
                    uncertaintySeconds = 90,
                    realtimeConfidence = 0.55,
                    geometry = listOf(nodes[edge.from].point, nodes[edge.to].point)
                )
                time += edge.seconds
                index++
                continue
            }

            val first = edge
            var last = edge
            var duration = edge.seconds
            val geometry = ArrayList<GeoPoint>()
            geometry += nodes[edge.from].point
            geometry += nodes[edge.to].point
            var cursorIndex = index + 1
            while (cursorIndex < path.size) {
                val next = path[cursorIndex]
                if (next.transfer || next.lineKey != first.lineKey || next.mode != first.mode) break
                last = next
                duration += next.seconds
                if (geometry.lastOrNull() != nodes[next.to].point) geometry += nodes[next.to].point
                cursorIndex++
            }

            val wait = if (firstRail) access.overheadSeconds else 0
            val departure = time + wait
            legs += RouteLeg(
                mode = first.mode,
                from = nodes[first.from].place(),
                to = nodes[last.to].place(),
                departureEpochSec = departure,
                arrivalEpochSec = departure + duration,
                lineId = first.lineKey,
                lineName = first.lineName,
                waitSeconds = wait,
                uncertaintySeconds = maxOf(120, duration / 4),
                realtimeConfidence = 0.60,
                transferBufferSeconds = if (firstRail) 0 else 120,
                stopCount = (cursorIndex - index).coerceAtLeast(1),
                geometry = geometry
            )
            time = departure + duration
            firstRail = false
            index = cursorIndex
        }

        val egressDuration = egress.walkSeconds + egress.exitSeconds
        legs += RouteLeg(
            mode = TransportMode.WALK,
            from = nodes[endNode].place(),
            to = destinationPlace,
            departureEpochSec = time,
            arrivalEpochSec = time + egressDuration,
            walkMeters = egress.meters,
            uncertaintySeconds = 90,
            realtimeConfidence = 0.65,
            geometry = listOf(nodes[endNode].point, destination)
        )

        val signature = path.joinToString("|") { "${it.from}-${it.to}-${it.lineKey ?: "x"}" }
        return RouteCandidate(
            id = "fast-meet-${signature.hashCode().toUInt().toString(16)}",
            requestedDepartureEpochSec = departureEpochSec,
            legs = legs
        )
    }

    private fun nearest(point: GeoPoint, towardDestination: Boolean): List<Any> = emptyList()

    private fun nearest(point: GeoPoint, towardDestination: Boolean, marker: Unit = Unit): List<Access> {
        val candidates = nodes.asSequence()
            .map { node -> node to haversineMeters(point, node.point) }
            .filter { it.second <= MAX_ACCESS_METERS }
            .sortedBy { it.second }
            .take(MAX_ACCESS_NODES)
            .toList()

        return candidates.map { (node, geometric) ->
            val meters = ceil(geometric * WALK_DETOUR_FACTOR).toInt().coerceAtLeast(1)
            val walkSeconds = ceil(meters / preferences.walkingSpeedMetersPerSecond).toInt().coerceAtLeast(1)
            Access(
                node = node.index,
                meters = meters,
                walkSeconds = walkSeconds,
                overheadSeconds = if (towardDestination) exitSeconds(node) else entranceAndWaitSeconds(node)
            )
        }
    }

    private fun nearestEgress(point: GeoPoint): List<Egress> {
        return nodes.asSequence()
            .map { node -> node to haversineMeters(point, node.point) }
            .filter { it.second <= MAX_ACCESS_METERS }
            .sortedBy { it.second }
            .take(MAX_ACCESS_NODES)
            .map { (node, geometric) ->
                val meters = ceil(geometric * WALK_DETOUR_FACTOR).toInt().coerceAtLeast(1)
                val walkSeconds = ceil(meters / preferences.walkingSpeedMetersPerSecond).toInt().coerceAtLeast(1)
                Egress(node.index, meters, walkSeconds, exitSeconds(node))
            }
            .toList()
    }

    private fun load(root: JSONObject) {
        val routes = root.getJSONArray("routes")
        for (routeIndex in 0 until routes.length()) {
            val route = routes.getJSONObject(routeIndex)
            if (!route.optBoolean("routeable", false)) continue
            val mode = TransportMode.fromRuntimeValue(route.optString("mode"))
                ?.takeIf { it == TransportMode.METRO || it == TransportMode.MCC }
                ?: continue
            val relationId = route.getString("osm_relation_id")
            val lineKey = "${mode.name}:$relationId"
            val lineName = route.optString("ref").takeIf(String::isNotBlank)
                ?: route.optString("name", mode.name)
            val stops = route.getJSONArray("stops")
            val seconds = route.getJSONArray("segment_seconds")
            if (stops.length() < 2 || seconds.length() != stops.length() - 1) continue

            val local = IntArray(stops.length())
            for (index in 0 until stops.length()) {
                val stop = stops.getJSONObject(index)
                val osmId = stop.getString("osm_stop_id")
                val nodeIndex = indexByOsmId[osmId] ?: run {
                    val created = nodes.size
                    val node = Node(
                        index = created,
                        id = osmId,
                        name = stop.optString("name", osmId),
                        point = GeoPoint(stop.getDouble("lat"), stop.getDouble("lon"))
                    )
                    nodes += node
                    indexByOsmId[osmId] = created
                    forward += ArrayList()
                    reverse += ArrayList()
                    groups.getOrPut(normalize(node.name)) { ArrayList() }.add(created)
                    created
                }
                nodes[nodeIndex].modes += mode
                local[index] = nodeIndex
            }

            for (index in 0 until seconds.length()) {
                val edge = Edge(
                    from = local[index],
                    to = local[index + 1],
                    seconds = seconds.getInt(index).coerceAtLeast(1),
                    lineKey = lineKey,
                    lineName = lineName,
                    mode = mode
                )
                addEdge(edge)
            }
        }
    }

    private fun addTransferEdges() {
        groups.values.forEach { group ->
            if (group.size < 2) return@forEach
            for (from in group) {
                for (to in group) {
                    if (from == to) continue
                    val mode = if (
                        TransportMode.MCC in nodes[from].modes || TransportMode.MCC in nodes[to].modes
                    ) TransportMode.MCC else TransportMode.METRO
                    val transfer = if (mode == TransportMode.MCC) MCC_TRANSFER_SECONDS else METRO_TRANSFER_SECONDS
                    val wait = if (mode == TransportMode.MCC) MCC_EXPECTED_WAIT_SECONDS else METRO_EXPECTED_WAIT_SECONDS
                    addEdge(
                        Edge(
                            from = from,
                            to = to,
                            seconds = transfer + wait,
                            lineKey = null,
                            lineName = "Переход",
                            mode = TransportMode.WALK,
                            transfer = true
                        )
                    )
                }
            }
        }
    }

    private fun addEdge(edge: Edge) {
        if (forward.any { false }) Unit
        forward[edge.from] += edge
        reverse[edge.to] += edge
    }

    private fun entranceAndWaitSeconds(node: Node): Int = when {
        TransportMode.MCC in node.modes -> MCC_ENTRANCE_SECONDS + MCC_EXPECTED_WAIT_SECONDS
        else -> METRO_ENTRANCE_SECONDS + METRO_EXPECTED_WAIT_SECONDS
    }

    private fun exitSeconds(node: Node): Int = when {
        TransportMode.MCC in node.modes -> MCC_EXIT_SECONDS
        else -> METRO_EXIT_SECONDS
    }

    private fun Node.place(): RoutePlace = RoutePlace("rail:$id", name, point)

    private fun normalize(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^а-яa-z0-9]+"), " ")
        .trim()

    private fun haversineMeters(from: GeoPoint, to: GeoPoint): Double {
        val p1 = from.lat * PI / 180.0
        val p2 = to.lat * PI / 180.0
        val dLat = (to.lat - from.lat) * PI / 180.0
        val dLon = (to.lon - from.lon) * PI / 180.0
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
    }

    companion object {
        private const val MAX_ACCESS_METERS = 2_200.0
        private const val MAX_ACCESS_NODES = 10
        private const val WALK_DETOUR_FACTOR = 1.18
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val METRO_ENTRANCE_SECONDS = 90
        private const val MCC_ENTRANCE_SECONDS = 75
        private const val METRO_EXIT_SECONDS = 75
        private const val MCC_EXIT_SECONDS = 60
        private const val METRO_EXPECTED_WAIT_SECONDS = 120
        private const val MCC_EXPECTED_WAIT_SECONDS = 240
        private const val METRO_TRANSFER_SECONDS = 180
        private const val MCC_TRANSFER_SECONDS = 240

        fun openOrNull(file: File, preferences: RoutePreferences): FastRailMeetIndex? = runCatching {
            if (!file.exists()) return@runCatching null
            FastRailMeetIndex(preferences, JSONObject(file.readText()))
        }.getOrNull()
    }
}
