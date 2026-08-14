package app.humanrouter.routing

internal data class ActivePlanSeed(
    val destination: GeoPoint,
    val baselineArrivalEpochSec: Long,
    val routeId: String
)

internal object LastPlanStore {
    @Volatile
    var seed: ActivePlanSeed? = null
}
