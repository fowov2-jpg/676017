package app.humanrouter.routing

import java.io.File
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Runtime pedestrian graph.
 *
 * Point-to-point walks use an exact A* search over the directed OSM-derived graph. The heuristic is
 * deliberately conservative (2.4 m/s straight-line lower bound), so it changes search order, not
 * route correctness. Endpoints that are implausibly far from the pedestrian graph are rejected
 * instead of drawing a straight line across a river, railway yard or fenced area.
 */
internal class RuntimeWalkGraph private constructor(
    private val root: File,
    private val preferences: RoutePreferences
) {
    data class WalkCost(
        val seconds: Int,
        val meters: Int,
        val geometry: List<GeoPoint> = emptyList()
    )

    private data class Snap(val node: Int, val meters: Int, val seconds: Int)
    private data class QueueNode(
        val node: Int,
        val seconds: Int,
        val meters: Int,
        val prioritySeconds: Int = seconds
    )

    private data class WalkCacheKey(
        val from: Long,
        val to: Long,
        val maxSeconds: Int,
        val maxMeters: Int
    )

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

    // Spatial lookup already shipped in the runtime. We derive our own stable cell keys from
    // representative nodes instead of depending on the producer's private key packing.
    private val gridOffsets = NpyArrays.uint64(File(root, "grid_offsets.npy"))
    private val gridNodes = NpyArrays.uint32(File(root, "grid_nodes.npy"))
    private val gridCellKeys: LongArray
    private val gridCellIndices: IntArray

    private val stopIndex = StopWalkIndex.load(File(root.parentFile, "surface/stop_walk_nodes.npz"))

    private val distSeconds = IntArray(latE7.size)
    private val distMeters = IntArray(latE7.size)
    private val generation = IntArray(latE7.size)
    private val parentNode = IntArray(latE7.size)
    private val parentGeneration = IntArray(latE7.size)
    private var currentGeneration = 0
    private val snapCache = LinkedHashMap<Long, Snap>(32, 0.75f, true)
    private val walkCache = LinkedHashMap<WalkCacheKey, WalkCost?>(64, 0.75f, true)

    init {
        require(latE7.size == lonE7.size)
        require(offsets.size == latE7.size + 1)
        require(revOffsets.size == latE7.size + 1)
        require(targets.size == edgeSeconds.size && targets.size == edgeMeters.size)
        require(revTargets.size == revSeconds.size && revTargets.size == revMeters.size)
        require(gridOffsets.size >= 2)
        require(gridNodes.size == latE7.size)

        val cellCount = gridOffsets.size - 1
        gridCellKeys = LongArray(cellCount)
        gridCellIndices = IntArray(cellCount)
        var usable = 0
        for (cell in 0 until cellCount) {
            val from = gridOffsets[cell].toInt()
            val until = gridOffsets[cell + 1].toInt()
            if (from >= until) continue
            val node = gridNodes[from]
            gridCellKeys[usable] = packCell(
                Math.floorDiv(latE7[node], GRID_CELL_E7),
                Math.floorDiv(lonE7[node], GRID_CELL_E7)
            )
            gridCellIndices[usable] = cell
            usable++
        }
        if (usable != cellCount) {
            error("Walk grid contains empty cells")
        }
        sortGridLookup(0, gridCellKeys.lastIndex)
    }

    @Synchronized
    fun stopCostsFrom(
        point: GeoPoint,
        maxSeconds: Int,
        maxMeters: Int,
        limit: Int = Int.MAX_VALUE
    ): Map<Int, WalkCost> {
        val snap = snap(point)
        return dijkstraStops(
            start = snap,
            maxSeconds = maxSeconds,
            maxMeters = maxMeters,
            reverse = false,
            limit = limit,
            excludedStopId = null
        )
    }

    @Synchronized
    fun stopCostsTo(
        point: GeoPoint,
        maxSeconds: Int,
        maxMeters: Int,
        limit: Int = Int.MAX_VALUE
    ): Map<Int, WalkCost> {
        val snap = snap(point)
        return dijkstraStops(
            start = snap,
            maxSeconds = maxSeconds,
            maxMeters = maxMeters,
            reverse = true,
            limit = limit,
            excludedStopId = null
        )
    }

    @Synchronized
    fun stopCostsFromStop(
        stopId: Int,
        maxSeconds: Int,
        maxMeters: Int,
        limit: Int
    ): Map<Int, WalkCost> {
        if (limit <= 0) return emptyMap()
        val stop = stopIndex.byStopId[stopId] ?: return emptyMap()
        return dijkstraStops(
            start = Snap(
                node = stop.walkNode,
                meters = stop.snapMeters,
                seconds = walkingSeconds(stop.snapMeters)
            ),
            maxSeconds = maxSeconds,
            maxMeters = maxMeters,
            reverse = false,
            limit = limit,
            excludedStopId = stopId
        )
    }

    @Synchronized
    fun shortestWalk(
        from: GeoPoint,
        to: GeoPoint,
        maxSeconds: Int,
        maxMeters: Int
    ): WalkCost? {
        if (maxSeconds <= 0 || maxMeters <= 0) return null
        val cacheKey = WalkCacheKey(snapKey(from), snapKey(to), maxSeconds, maxMeters)
        if (walkCache.containsKey(cacheKey)) return walkCache[cacheKey]

        val start = snap(from)
        val target = snap(to)
        if (start.meters > MAX_ENDPOINT_SNAP_METERS || target.meters > MAX_ENDPOINT_SNAP_METERS) {
            putWalkCache(cacheKey, null)
            return null
        }

        if (start.node == target.node) {
            val meters = start.meters + target.meters
            val seconds = start.seconds + target.seconds
            val result = if (meters <= maxMeters && seconds <= maxSeconds) {
                WalkCost(seconds, meters, listOf(from, nodePoint(start.node), to).distinct())
            } else null
            putWalkCache(cacheKey, result)
            return result
        }

        beginSearch()
        val queue = PriorityQueue(
            compareBy<QueueNode> { it.prioritySeconds }
                .thenBy { it.seconds }
                .thenBy { it.meters }
        )
        setDistance(start.node, start.seconds, start.meters)
        setParent(start.node, -1)
        queue += QueueNode(
            node = start.node,
            seconds = start.seconds,
            meters = start.meters,
            prioritySeconds = start.seconds + heuristicSeconds(start.node, target.node)
        )

        var result: WalkCost? = null
        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (!isCurrent(current)) continue
            if (current.seconds > maxSeconds || current.meters > maxMeters) continue
            if (current.node == target.node) {
                val seconds = current.seconds + target.seconds
                val meters = current.meters + target.meters
                if (seconds <= maxSeconds && meters <= maxMeters) {
                    result = WalkCost(
                        seconds = seconds,
                        meters = meters,
                        geometry = reconstructGeometry(start.node, target.node, from, to)
                    )
                }
                break
            }
            relaxAStar(
                current = current,
                queue = queue,
                targetNode = target.node,
                maxSeconds = maxSeconds,
                maxMeters = maxMeters
            )
        }
        putWalkCache(cacheKey, result)
        return result
    }

    private fun putWalkCache(key: WalkCacheKey, value: WalkCost?) {
        walkCache[key] = value
        if (walkCache.size > WALK_CACHE_SIZE) {
            walkCache.remove(walkCache.entries.first().key)
        }
    }

    private fun relaxAStar(
        current: QueueNode,
        queue: PriorityQueue<QueueNode>,
        targetNode: Int,
        maxSeconds: Int,
        maxMeters: Int
    ) {
        val from = offsets[current.node].toInt()
        val until = offsets[current.node + 1].toInt()
        for (edge in from until until) {
            val target = targets[edge]
            val seconds = current.seconds + edgeSeconds[edge]
            val meters = current.meters + edgeMeters[edge]
            if (seconds > maxSeconds || meters > maxMeters) continue

            val oldSeconds = distanceSeconds(target)
            val oldMeters = distanceMeters(target)
            if (seconds < oldSeconds || (seconds == oldSeconds && meters < oldMeters)) {
                setDistance(target, seconds, meters)
                setParent(target, current.node)
                val estimate = heuristicSeconds(target, targetNode)
                if (seconds + estimate <= maxSeconds) {
                    queue += QueueNode(target, seconds, meters, seconds + estimate)
                }
            }
        }
    }

    /** Straight-line time at 2.4 m/s is a lower bound for a pedestrian graph edge path. */
    private fun heuristicSeconds(fromNode: Int, targetNode: Int): Int {
        val lat = (latE7[fromNode] + latE7[targetNode]) * 0.5 / 10_000_000.0
        val lonScale = cos(lat * PI / 180.0).coerceAtLeast(0.01)
        val dLat = (latE7[fromNode] - latE7[targetNode]).toDouble()
        val dLon = (lonE7[fromNode] - lonE7[targetNode]).toDouble() * lonScale
        val meters = sqrt(dLat * dLat + dLon * dLon) * METERS_PER_E7
        return floor(meters / MAX_HEURISTIC_SPEED_MPS).toInt().coerceAtLeast(0)
    }

    private fun dijkstraStops(
        start: Snap,
        maxSeconds: Int,
        maxMeters: Int,
        reverse: Boolean,
        limit: Int,
        excludedStopId: Int?
    ): Map<Int, WalkCost> {
        if (limit <= 0) return emptyMap()

        beginSearch()
        val initialCapacity = if (limit == Int.MAX_VALUE) 128 else (limit * 2).coerceAtLeast(16)
        val result = HashMap<Int, WalkCost>(initialCapacity)
        val queue = PriorityQueue(compareBy<QueueNode> { it.seconds }.thenBy { it.meters })
        setDistance(start.node, start.seconds, start.meters)
        queue += QueueNode(start.node, start.seconds, start.meters)
        var cutoffSeconds = maxSeconds

        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (!isCurrent(current)) continue
            if (current.seconds > maxSeconds || current.meters > maxMeters) continue
            if (result.size >= limit && current.seconds > cutoffSeconds) break

            stopIndex.byWalkNode[current.node]?.forEach { stop ->
                if (stop.stopId == excludedStopId) return@forEach

                val totalMeters = current.meters + stop.snapMeters
                val totalSeconds = current.seconds + walkingSeconds(stop.snapMeters)
                if (totalMeters > maxMeters || totalSeconds > maxSeconds) return@forEach

                val candidate = WalkCost(totalSeconds, totalMeters)
                val existing = result[stop.stopId]
                if (existing == null || isBetter(candidate, existing)) {
                    result[stop.stopId] = candidate
                    if (limit != Int.MAX_VALUE && result.size > limit) {
                        removeWorst(result)
                    }
                    if (result.size >= limit && limit != Int.MAX_VALUE) {
                        cutoffSeconds = result.values.maxOf { it.seconds }
                    }
                }
            }

            val effectiveMaxSeconds = if (result.size >= limit && limit != Int.MAX_VALUE) {
                min(maxSeconds, cutoffSeconds)
            } else {
                maxSeconds
            }
            relax(
                current,
                queue,
                reverse = reverse,
                maxSeconds = effectiveMaxSeconds,
                maxMeters = maxMeters
            )
        }
        return result
    }

    private fun isBetter(candidate: WalkCost, existing: WalkCost): Boolean =
        candidate.seconds < existing.seconds ||
            (candidate.seconds == existing.seconds && candidate.meters < existing.meters)

    private fun removeWorst(result: MutableMap<Int, WalkCost>) {
        var worstId: Int? = null
        var worst: WalkCost? = null
        for ((id, cost) in result) {
            val currentWorst = worst
            if (currentWorst == null ||
                cost.seconds > currentWorst.seconds ||
                (cost.seconds == currentWorst.seconds && cost.meters > currentWorst.meters)
            ) {
                worstId = id
                worst = cost
            }
        }
        worstId?.let(result::remove)
    }

    private fun relax(
        current: QueueNode,
        queue: PriorityQueue<QueueNode>,
        reverse: Boolean,
        maxSeconds: Int,
        maxMeters: Int,
        trackParents: Boolean = false
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
                if (trackParents) setParent(target, current.node)
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

    private fun setParent(node: Int, parent: Int) {
        parentGeneration[node] = currentGeneration
        parentNode[node] = parent
    }

    private fun reconstructGeometry(
        startNode: Int,
        targetNode: Int,
        from: GeoPoint,
        to: GeoPoint
    ): List<GeoPoint> {
        val reversed = ArrayList<Int>()
        var cursor = targetNode
        while (true) {
            reversed += cursor
            if (cursor == startNode) break
            if (parentGeneration[cursor] != currentGeneration) return emptyList()
            cursor = parentNode[cursor]
            if (cursor < 0 || reversed.size > latE7.size) return emptyList()
        }
        reversed.reverse()

        val result = ArrayList<GeoPoint>(min(reversed.size + 2, MAX_GEOMETRY_POINTS + 2))
        fun append(point: GeoPoint) {
            if (result.lastOrNull() != point) result += point
        }
        append(from)
        val stride = ((reversed.size + MAX_GEOMETRY_POINTS - 1) / MAX_GEOMETRY_POINTS)
            .coerceAtLeast(1)
        var index = 0
        while (index < reversed.size) {
            append(nodePoint(reversed[index]))
            index += stride
        }
        append(nodePoint(reversed.last()))
        append(to)
        return result
    }

    private fun nodePoint(node: Int): GeoPoint = GeoPoint(
        lat = latE7[node] / 10_000_000.0,
        lon = lonE7[node] / 10_000_000.0
    )

    private fun isCurrent(item: QueueNode): Boolean =
        generation[item.node] == currentGeneration &&
            distSeconds[item.node] == item.seconds &&
            distMeters[item.node] == item.meters

    private fun snap(point: GeoPoint): Snap {
        val cacheKey = snapKey(point)
        snapCache[cacheKey]?.let { return it }

        val targetLat = (point.lat * 10_000_000.0).roundToInt()
        val targetLon = (point.lon * 10_000_000.0).roundToInt()
        val lonScale = cos(point.lat * PI / 180.0).coerceAtLeast(0.01)

        val node = nearestNodeFromGrid(targetLat, targetLon, lonScale)
        val dLat = (latE7[node] - targetLat).toDouble()
        val dLon = (lonE7[node] - targetLon).toDouble() * lonScale
        val meters = (sqrt(dLat * dLat + dLon * dLon) * METERS_PER_E7)
            .roundToInt()
            .coerceAtLeast(0)

        val snap = Snap(node, meters, walkingSeconds(meters))
        snapCache[cacheKey] = snap
        if (snapCache.size > SNAP_CACHE_SIZE) {
            snapCache.remove(snapCache.entries.first().key)
        }
        return snap
    }

    private fun nearestNodeFromGrid(targetLat: Int, targetLon: Int, lonScale: Double): Int {
        val baseLatCell = Math.floorDiv(targetLat, GRID_CELL_E7)
        val baseLonCell = Math.floorDiv(targetLon, GRID_CELL_E7)
        var bestNode = -1
        var bestSquared = Double.POSITIVE_INFINITY

        for (ring in 0..MAX_GRID_RING) {
            for (dx in -ring..ring) {
                for (dy in -ring..ring) {
                    if (ring > 0 && abs(dx) != ring && abs(dy) != ring) continue
                    val cell = findGridCell(baseLatCell + dx, baseLonCell + dy)
                    if (cell < 0) continue

                    val from = gridOffsets[cell].toInt()
                    val until = gridOffsets[cell + 1].toInt()
                    for (position in from until until) {
                        val node = gridNodes[position]
                        val dLat = (latE7[node] - targetLat).toDouble()
                        val dLon = (lonE7[node] - targetLon).toDouble() * lonScale
                        val squared = dLat * dLat + dLon * dLon
                        if (squared < bestSquared) {
                            bestSquared = squared
                            bestNode = node
                        }
                    }
                }
            }

            if (bestNode >= 0) {
                val minOutside = minimumDistanceOutsideRingE7(
                    targetLat = targetLat,
                    targetLon = targetLon,
                    baseLatCell = baseLatCell,
                    baseLonCell = baseLonCell,
                    ring = ring,
                    lonScale = lonScale
                )
                if (sqrt(bestSquared) <= minOutside) {
                    return bestNode
                }
            }
        }

        // Correctness fallback for a point well outside the indexed runtime boundary.
        return bruteForceNearestNode(targetLat, targetLon, lonScale)
    }

    private fun minimumDistanceOutsideRingE7(
        targetLat: Int,
        targetLon: Int,
        baseLatCell: Int,
        baseLonCell: Int,
        ring: Int,
        lonScale: Double
    ): Double {
        val lowLat = (baseLatCell - ring).toLong() * GRID_CELL_E7
        val highLat = (baseLatCell + ring + 1L) * GRID_CELL_E7
        val lowLon = (baseLonCell - ring).toLong() * GRID_CELL_E7
        val highLon = (baseLonCell + ring + 1L) * GRID_CELL_E7

        val latGap = min(
            abs(targetLat.toLong() - lowLat).toDouble(),
            abs(highLat - targetLat.toLong()).toDouble()
        )
        val lonGap = min(
            abs(targetLon.toLong() - lowLon).toDouble(),
            abs(highLon - targetLon.toLong()).toDouble()
        ) * lonScale
        return min(latGap, lonGap)
    }

    private fun bruteForceNearestNode(targetLat: Int, targetLon: Int, lonScale: Double): Int {
        var bestNode = 0
        var bestSquared = Double.POSITIVE_INFINITY
        for (node in latE7.indices) {
            val dLat = (latE7[node] - targetLat).toDouble()
            val dLon = (lonE7[node] - targetLon).toDouble() * lonScale
            val squared = dLat * dLat + dLon * dLon
            if (squared < bestSquared) {
                bestSquared = squared
                bestNode = node
            }
        }
        return bestNode
    }

    private fun findGridCell(latCell: Int, lonCell: Int): Int {
        val key = packCell(latCell, lonCell)
        var low = 0
        var high = gridCellKeys.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = gridCellKeys[mid]
            when {
                value < key -> low = mid + 1
                value > key -> high = mid - 1
                else -> return gridCellIndices[mid]
            }
        }
        return -1
    }

    private fun sortGridLookup(left: Int, right: Int) {
        if (left >= right) return
        var i = left
        var j = right
        val pivot = gridCellKeys[(left + right) ushr 1]

        while (i <= j) {
            while (gridCellKeys[i] < pivot) i++
            while (gridCellKeys[j] > pivot) j--
            if (i <= j) {
                val key = gridCellKeys[i]
                gridCellKeys[i] = gridCellKeys[j]
                gridCellKeys[j] = key

                val index = gridCellIndices[i]
                gridCellIndices[i] = gridCellIndices[j]
                gridCellIndices[j] = index
                i++
                j--
            }
        }
        if (left < j) sortGridLookup(left, j)
        if (i < right) sortGridLookup(i, right)
    }

    private fun packCell(latCell: Int, lonCell: Int): Long =
        (latCell.toLong() shl 32) xor (lonCell.toLong() and 0xffffffffL)

    private fun snapKey(point: GeoPoint): Long {
        val lat = (point.lat * 100_000.0).roundToInt()
        val lon = (point.lon * 100_000.0).roundToInt()
        return (lat.toLong() shl 32) xor (lon.toLong() and 0xffffffffL)
    }

    private fun walkingSeconds(meters: Int): Int =
        if (meters <= 0) {
            0
        } else {
            (meters / preferences.walkingSpeedMetersPerSecond).roundToInt().coerceAtLeast(1)
        }

    companion object {
        private const val INF = Int.MAX_VALUE / 4
        private const val METERS_PER_E7 = 0.011132
        private const val GRID_CELL_E7 = 20_000
        private const val MAX_GRID_RING = 8
        private const val SNAP_CACHE_SIZE = 96
        private const val WALK_CACHE_SIZE = 96
        private const val MAX_GEOMETRY_POINTS = 700
        private const val MAX_ENDPOINT_SNAP_METERS = 250
        private const val MAX_HEURISTIC_SPEED_MPS = 2.4

        fun openOrNull(runtimeRoot: File, preferences: RoutePreferences): RuntimeWalkGraph? {
            val root = File(runtimeRoot, "walk_graph")
            val required = listOf(
                "lat_e7.npy", "lon_e7.npy",
                "offsets.npy", "targets.npy", "seconds.npy", "meters.npy",
                "rev_offsets.npy", "rev_targets.npy", "rev_seconds.npy", "rev_meters.npy",
                "grid_offsets.npy", "grid_nodes.npy"
            )
            if (required.any { !File(root, it).exists() } ||
                !File(runtimeRoot, "surface/stop_walk_nodes.npz").exists()
            ) {
                return null
            }
            return runCatching { RuntimeWalkGraph(root, preferences) }.getOrNull()
        }
    }
}
