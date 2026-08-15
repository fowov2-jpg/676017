package app.humanrouter.routing

internal enum class RouteDisplayKind {
    WALK,
    TRANSIT,
    TRANSFER
}

/**
 * A user-facing route step. Router legs intentionally stay lossless; this model hides technical
 * joins (for example two adjacent walks or a zero-metre interchange) from the interface.
 */
internal data class RouteDisplayStep(
    val kind: RouteDisplayKind,
    val mode: TransportMode?,
    val from: RoutePlace,
    val to: RoutePlace,
    val departureEpochSec: Long,
    val arrivalEpochSec: Long,
    val lineId: String? = null,
    val lineName: String? = null,
    val walkMeters: Int = 0,
    val stopCount: Int = 0,
    val sourceLegCount: Int = 1
) {
    val durationSeconds: Int
        get() = (arrivalEpochSec - departureEpochSec).toInt().coerceAtLeast(0)
}

internal object RoutePresentation {
    fun steps(
        route: RouteCandidate,
        originTitle: String? = null,
        destinationTitle: String? = null
    ): List<RouteDisplayStep> {
        val grouped = ArrayList<RouteDisplayStep>(route.legs.size)
        var index = 0
        while (index < route.legs.size) {
            val first = route.legs[index]
            if (first.mode == TransportMode.WALK) {
                var end = index
                var metres = 0
                while (end < route.legs.size && route.legs[end].mode == TransportMode.WALK) {
                    metres += route.legs[end].walkMeters
                    end += 1
                }
                val last = route.legs[end - 1]
                val betweenTransit = index > 0 && end < route.legs.size &&
                    route.legs[index - 1].mode != TransportMode.WALK &&
                    route.legs[end].mode != TransportMode.WALK
                val samePlace = normalizedPlaceName(first.from.name) == normalizedPlaceName(last.to.name)
                val technicalEndpoint = !betweenTransit && metres == 0 && samePlace
                if (!technicalEndpoint) {
                    grouped += RouteDisplayStep(
                        kind = if (betweenTransit) RouteDisplayKind.TRANSFER else RouteDisplayKind.WALK,
                        mode = null,
                        from = first.from,
                        to = last.to,
                        departureEpochSec = first.departureEpochSec,
                        arrivalEpochSec = last.arrivalEpochSec,
                        walkMeters = metres,
                        sourceLegCount = end - index
                    )
                }
                index = end
                continue
            }

            var end = index + 1
            var stops = first.stopCount
            while (end < route.legs.size && canMergeTransit(first, route.legs[end])) {
                stops += route.legs[end].stopCount
                end += 1
            }
            val last = route.legs[end - 1]
            grouped += RouteDisplayStep(
                kind = RouteDisplayKind.TRANSIT,
                mode = first.mode,
                from = first.from,
                to = last.to,
                departureEpochSec = first.departureEpochSec,
                arrivalEpochSec = last.arrivalEpochSec,
                lineId = first.lineId,
                lineName = first.lineName,
                stopCount = stops,
                sourceLegCount = end - index
            )
            index = end
        }

        if (grouped.isEmpty()) return emptyList()
        originTitle?.trim()?.takeIf(String::isNotBlank)?.let { title ->
            val first = grouped.first()
            grouped[0] = first.copy(from = first.from.copy(name = title))
        }
        destinationTitle?.trim()?.takeIf(String::isNotBlank)?.let { title ->
            val lastIndex = grouped.lastIndex
            val last = grouped[lastIndex]
            grouped[lastIndex] = last.copy(to = last.to.copy(name = title))
        }
        return grouped
    }

    private fun canMergeTransit(first: RouteLeg, next: RouteLeg): Boolean {
        if (next.mode == TransportMode.WALK || first.mode != next.mode) return false
        val firstLine = first.lineId?.takeIf(String::isNotBlank) ?: first.lineName.orEmpty()
        val nextLine = next.lineId?.takeIf(String::isNotBlank) ?: next.lineName.orEmpty()
        return firstLine.isNotBlank() && firstLine == nextLine
    }

    internal fun normalizedPlaceName(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^\u0430-\u044fa-z0-9]+"), "")
}
