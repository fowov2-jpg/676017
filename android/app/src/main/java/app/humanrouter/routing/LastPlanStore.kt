package app.humanrouter.routing

internal data class ActivePlanSeed(
    val destination: GeoPoint,
    val baselineArrivalEpochSec: Long,
    val routeId: String,
    val route: RouteCandidate? = null
)

internal object LastPlanStore {
    @Volatile
    var seed: ActivePlanSeed? = null

    fun select(route: RouteCandidate, destination: GeoPoint) {
        seed = ActivePlanSeed(
            destination = destination,
            baselineArrivalEpochSec = route.arrivalEpochSec,
            routeId = route.id,
            route = route
        )
    }
}
