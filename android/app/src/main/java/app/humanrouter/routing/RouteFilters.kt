package app.humanrouter.routing

internal enum class RouteFilter(val label: String) {
    FASTEST("Быстрее"),
    LESS_WALKING("Меньше пешком"),
    NO_TRANSFERS("Без пересадок"),
    METRO("Метро"),
    SURFACE("Наземный транспорт")
}

internal object RouteFilters {
    private val metroModes = setOf(
        TransportMode.METRO,
        TransportMode.MCC
    )

    fun apply(routes: List<RankedRoute>, filter: RouteFilter): List<RankedRoute> = when (filter) {
        RouteFilter.FASTEST -> routes.sortedBy { it.expectedArrivalEpochSec }
        RouteFilter.LESS_WALKING -> routes.sortedWith(
            compareBy<RankedRoute> { it.route.walkMeters }.thenBy { it.expectedArrivalEpochSec }
        )
        RouteFilter.NO_TRANSFERS -> routes.filter { it.route.transferCount == 0 }
            .sortedBy { it.expectedArrivalEpochSec }
        RouteFilter.METRO -> routes.filter { ranked ->
            ranked.route.legs.any { it.mode in metroModes }
        }.sortedBy { it.expectedArrivalEpochSec }
        RouteFilter.SURFACE -> routes.filter { ranked ->
            ranked.route.legs.any { it.mode == TransportMode.BUS || it.mode == TransportMode.TRAM }
        }.sortedBy { it.expectedArrivalEpochSec }
    }
}
