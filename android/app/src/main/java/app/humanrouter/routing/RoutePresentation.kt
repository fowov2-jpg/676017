package app.humanrouter.routing

internal enum class RouteDisplayKind {
    WALK,
    TRANSIT,
    TRANSFER
}

internal enum class RouteTransferKind {
    GROUND,
    UNDERGROUND,
    OVERGROUND,
    INTERCHANGE,
    METRO_EXIT
}

/**
 * A user-facing route step. Router legs intentionally stay lossless; this model hides technical
 * joins (for example two adjacent walks or a zero-metre interchange) from the interface while
 * preserving a concrete instruction for Moscow exits and pedestrian transfers.
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
    val sourceLegCount: Int = 1,
    val transferKind: RouteTransferKind? = null,
    val instruction: String? = null
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
                    val exitPlace = sequenceOf(first.from, last.to)
                        .firstOrNull { it.id.startsWith("metro-exit:") || normalizedPlaceName(it.name).startsWith("выход") }
                    val transferKind = if (betweenTransit) {
                        classifyTransfer(first.from, last.to, exitPlace != null)
                    } else if (exitPlace != null) {
                        RouteTransferKind.METRO_EXIT
                    } else {
                        null
                    }
                    val kind = if (betweenTransit) RouteDisplayKind.TRANSFER else RouteDisplayKind.WALK
                    grouped += RouteDisplayStep(
                        kind = kind,
                        mode = null,
                        from = first.from,
                        to = last.to,
                        departureEpochSec = first.departureEpochSec,
                        arrivalEpochSec = last.arrivalEpochSec,
                        walkMeters = metres,
                        sourceLegCount = end - index,
                        transferKind = transferKind,
                        instruction = walkingInstruction(kind, metres, exitPlace, transferKind)
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
                sourceLegCount = end - index,
                instruction = transitInstruction(first.mode, first.lineName, first.lineId)
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

    private fun walkingInstruction(
        kind: RouteDisplayKind,
        metres: Int,
        exitPlace: RoutePlace?,
        transferKind: RouteTransferKind?
    ): String {
        if (exitPlace != null) {
            val exit = exitPlace.name.substringBefore(" · ").trim()
            return if (metres > 0) "$exit · пешком $metres м" else exit
        }
        if (kind == RouteDisplayKind.WALK) {
            return if (metres > 0) "Пешком $metres м" else "Пешком"
        }
        val prefix = when (transferKind) {
            RouteTransferKind.UNDERGROUND -> "Подземный переход"
            RouteTransferKind.OVERGROUND -> "Надземный переход"
            RouteTransferKind.GROUND -> "Наземный переход"
            RouteTransferKind.METRO_EXIT -> "Выход из метро"
            RouteTransferKind.INTERCHANGE, null -> "Пересадка"
        }
        return if (metres > 0) "$prefix $metres м" else prefix
    }

    private fun classifyTransfer(
        from: RoutePlace,
        to: RoutePlace,
        metroExit: Boolean
    ): RouteTransferKind {
        if (metroExit) return RouteTransferKind.METRO_EXIT
        val text = normalizedPlaceName("${from.name} ${to.name}")
        return when {
            "подзем" in text || "тоннел" in text -> RouteTransferKind.UNDERGROUND
            "надзем" in text || "эстакад" in text -> RouteTransferKind.OVERGROUND
            "назем" in text || "улиц" in text -> RouteTransferKind.GROUND
            else -> RouteTransferKind.INTERCHANGE
        }
    }

    private fun transitInstruction(mode: TransportMode, lineName: String?, lineId: String?): String {
        val line = lineName?.trim().takeUnless { it.isNullOrBlank() }
            ?: lineId?.substringAfterLast(':')?.trim().orEmpty()
        val modeName = when (mode) {
            TransportMode.BUS -> "Автобус"
            TransportMode.TRAM -> "Трамвай"
            TransportMode.METRO -> "Метро"
            TransportMode.MCC -> "МЦК"
            TransportMode.MCD -> "МЦД"
            TransportMode.TRAIN -> "Поезд"
            TransportMode.WALK -> "Пешком"
        }
        return listOf(modeName, line).filter(String::isNotBlank).joinToString(" ")
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
