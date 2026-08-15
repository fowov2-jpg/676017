package app.humanrouter

import app.humanrouter.routing.RouteCandidate
import java.time.Instant
import java.util.concurrent.CopyOnWriteArraySet

internal data class TripLiveSnapshot(
    val route: RouteCandidate,
    val updatedEpochSec: Long,
    val approximate: Boolean,
    val status: String
)

/** In-process single source of truth for the active trip shared by service and Activity UI. */
internal object TripLiveState {
    private val listeners = CopyOnWriteArraySet<(TripLiveSnapshot) -> Unit>()

    @Volatile
    private var current: TripLiveSnapshot? = null

    fun current(): TripLiveSnapshot? = current

    fun publish(
        route: RouteCandidate,
        approximate: Boolean = route.legs.any { it.realtimeConfidence < 0.8 },
        status: String = ""
    ) {
        val snapshot = TripLiveSnapshot(
            route = route,
            updatedEpochSec = Instant.now().epochSecond,
            approximate = approximate,
            status = status
        )
        current = snapshot
        listeners.forEach { listener -> runCatching { listener(snapshot) } }
    }

    fun clear() {
        current = null
    }

    fun addListener(listener: (TripLiveSnapshot) -> Unit) {
        listeners += listener
        current?.let { snapshot -> runCatching { listener(snapshot) } }
    }

    fun removeListener(listener: (TripLiveSnapshot) -> Unit) {
        listeners -= listener
    }
}
