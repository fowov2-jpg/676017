package app.humanrouter.routing

import java.io.File
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class RuntimeWalkGraph private constructor(
    private val root: File,
    private val preferences: RoutePreferences
) {
    data class WalkCost(val seconds: Int, val meters: Int)

    private data class Snap(val node: Int, val meters: Int, val seconds: Int)
    private data class QueueNode(val node: Int, val seconds: Int, val meters: Int)

    private val latE7 = NpyArrays.int32(File(root, "lat_e7.npy"))
    private val lonE7 = NpyArrays.int32(File(root, "lon_e7.npy"))
    private val offsets = NpyArrays.uint64(File(root, "offsets.npy"))
    private val targets = NpyArrays.uint32(File(root, "targets.npy"))
    private val edgeSeconds = NpyArrays.uint16(File(root, "seconds.npy"))
    private val edgeMeters = NpyArrays.uint16(File(root, "meters.npy"))
    private val revOffsets = NpyArrays.uint64(File(root, "rev_offsets.npy"))
    private val revTargets = NpyArrays.uint32(File(root, "rev_targets.npy"))
    private val revSeconds = NpyArrays.uint16(File(root, "rev_seconds.npy"))
    private val revMeters = NpyArrays.uint16(File(root, "rev_meters.npy"))
    private val stopIndex = StopWalkIndex.load(File(root.parentFile, "surface/stop_walk_nodes.npz"))

    private val distSeconds = IntArray(latE7.size)
    private val distMeters = IntArray(latE7.size)
    private val generation = IntArray(latE7.size)
    private var currentGeneration = 0
    private val snapCache = LinkedHashMap<Long, Snap>(32, 0.75f, true)

    init {
        require(latE7.size == lonE7.size)
        require(offsets.size == latE7.size + 1)
        require(revOffsets.size == latE7.size + 1)
        require(targets.size == edgeSeconds.size && targets.size == edgeMeters.size)
        require(revTargets.size == revSeconds.size && revTargets.size == revMeters.size)
    }

    @Synchronized
    fun stopCostsFrom(point: GeoPoint, maxSeconds: Int, maxMeters: Int): Map<Int, WalkCost> {
        val snap = snap(point)
        return dijkstraStops(
            start = snap,
            maxSeconds = maxSeconds,
            maxMeters = maxMeters,
            reverse = false
        )
    }

    @Synchronized
    fun stopCostsTo(point: GeoPoint, maxSeconds: Int, maxMeters: Int): Map<Int, WalkCost> {
        val snap = snap(point)
        return dijkstraStops(
            start = snap,
            maxSeconds = maxSeconds,
            maxMeters = maxMeters,
            reverse = true
        )
    }

    @Synchronized
    fun shortestWalk(
        from: GeoPoint,
        to: GeoPoint,
        maxSeconds: Int,
        maxMeters: Int
    ): WalkCost? {
        val start = snap(from)
        val target = snap(to)
        beginSearch()
        val queue = PriorityQueue(compareBy<QueueNode> { it.seconds }.thenBy { it.meters })
        setDistance(start.node, start.seconds, start.meters)
        queue += QueueNode(start.node, start.seconds, start.meters)

        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (!isCurrent(current)) continue
            if (current.seconds > maxSeconds || current.meters > maxMeters) continue
            if (current.node == target.node) {
                val seconds = current.seconds + target.seconds
                val meters = current.meters + target.meters
                return if (seconds <= maxSeconds && meters <= maxMeters) WalkCost(seconds, meters) else null
            }
            relax(current, queue, reverse = false, maxSeconds = maxSeconds, maxMeters = maxMeters)
        }
        return null
    }

    private fun dijkstraStops(
        start: Snap,
        maxSeconds: Int,
        maxMeters: Int,
        reverse: Boolean
    ): Map<Int, WalkCost> {
        beginSearch()
        val result = HashMap<Int, WalkCost>(128)
        val queue = PriorityQueue(compareBy<QueueNode> { it.seconds }.thenBy { it.meters })
        setDistance(start.node, start.seconds, start.meters)
        queue += QueueNode(start.node, start.seconds, start.meters)

        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (!isCurrent(current)) continue
            if (current.seconds > maxSeconds || current.meters > maxMeters) continue

            stopIndex.byWalkNode[current.node]?.forEach { stop ->
                val snapMeters = stop.snapMeters
                val totalMeters = current.meters + snapMeters
                val totalSeconds = current.seconds + walkingSeconds(snapMeters)
                if (totalMeters <= maxMeters && totalSeconds <= maxSeconds) {
                    val existing = result[stop.stopId]
                    if (existing == null || totalSeconds < existing.seconds ||
                        (totalSeconds == existing.seconds && totalMeters < existing.meters)
                    ) {
                        result[stop.stopId] = WalkCost(totalSeconds, totalMeters)
                    }
                }
            }
            relax(current, queue, reverse, maxSeconds, maxMeters)
        }
        return result
    }

    private fun relax(
        current: QueueNode,
        queue: PriorityQueue<QueueNode>,
        reverse: Boolean,
        maxSeconds: Int,
        maxMeters: Int
    ) {
        val graphOffsets = if (reverse) revOffsets else offsets
        val graphTargets = if (reverse) revTargets else targets
        val graphSeconds = if (reverse) revSeconds else edgeSeconds
        val graphMeters = if (reverse) revMeters else edgeMeters
        val from = graphOffsets[current.node].toInt()
        val until = graphOffsets[current.node + 1].toInt()
        for (edge in from until until) {
            val target = graphTargets[edge]
            val seconds = current.seconds + graphSeconds[edge]
            val meters = current.meters + graphMeters[edge]
            if (seconds > maxSeconds || meters > maxMeters) continue
            val oldSeconds = distanceSeconds(target)
            val oldMeters = distanceMeters(target)
            if (seconds < oldSeconds || (seconds == oldSeconds && meters < oldMeters)) {
                setDistance(target, seconds, meters)
                queue += QueueNode(target, seconds, meters)
            }
        }
    }

    private fun beginSearch() {
        currentGeneration++
        if (currentGeneration == 0) {
            generation.fill(0)
            currentGeneration = 1
        }
    }

    private fun distanceSeconds(node: Int): Int =
        if (generation[node] == currentGeneration) distSeconds[node] else INF

    private fun distanceMeters(node: Int): Int =
        if (generation[node] == currentGeneration) distMeters[node] else INF

    private fun setDistance(node: Int, seconds: Int, meters: Int) {
        generation[node] = currentGeneration
        distSeconds[node] = seconds
        distMeters[node] = meters
    }

    private fun isCurrent(item: QueueNode): Boolean =
        generation[item.node] == currentGeneration &&
            distSeconds[item.node] == item.seconds &&
            distMeters[item.node] == item.meters

    private fun snap(point: GeoPoint): Snap {
        val key = snapKey(point)
        snapCache[key]?.let { return it }
        val targetLat = (point.lat * 10_000_000.0).roundToInt()
        val targetLon = (point.lon * 10_000_000.0).roundToInt()
        val lonScale = cos(point.lat * PI / 180.0)
        var bestNode = 0
        var bestSquared = Double.POSITIVE_INFINITY
        for (node in 0 until latE7.size) {
            val dLat = (latE7[node] - targetLat).toDouble()
            val dLon = (lonE7[node] - targetLon).toDouble() * lonScale
            val squared = dLat * dLat + dLon * dLon
            if (squared < bestSquared) {
                bestSquared = squared
                bestNode = node
            }
        }
        val meters = (sqrt(bestSquared) * METERS_PER_E7).roundToInt().coerceAtLeast(0)
        val snap = Snap(bestNode, meters, walkingSeconds(meters))
        snapCache[key] = snap
        if (snapCache.size > 64) snapCache.remove(snapCache.entries.first().key)
        return snap
    }

    private fun snapKey(point: GeoPoint): Long {
        val lat = (point.lat * 100_000.0).roundToInt()
        val lon = (point.lon * 100_000.0).roundToInt()
        return (lat.toLong() shl 32) xor (lon.toLong() and 0xffffffffL)
    }

    private fun walkingSeconds(meters: Int): Int =
        if (meters <= 0) 0 else (meters / preferences.walkingSpeedMetersPerSecond).roundToInt().coerceAtLeast(1)

    companion object {
        private const val INF = Int.MAX_VALUE / 4
        private const val METERS_PER_E7 = 0.011132

        fun openOrNull(runtimeRoot: File, preferences: RoutePreferences): RuntimeWalkGraph? {
            val root = File(runtimeRoot, "walk_graph")
            val required = listOf(
                "lat_e7.npy", "lon_e7.npy", "offsets.npy", "targets.npy", "seconds.npy", "meters.npy",
                "rev_offsets.npy", "rev_targets.npy", "rev_seconds.npy", "rev_meters.npy"
            )
            if (required.any { !File(root, it).exists() } || !File(runtimeRoot, "surface/stop_walk_nodes.npz").exists()) {
                return null
            }
            return runCatching { RuntimeWalkGraph(root, preferences) }.getOrNull()
        }
    }
}
