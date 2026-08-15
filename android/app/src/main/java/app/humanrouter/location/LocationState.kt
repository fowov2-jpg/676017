package app.humanrouter.location

import app.humanrouter.routing.GeoPoint

internal sealed interface LocationState {
    data object Unknown : LocationState
    data object Requesting : LocationState
    data class Available(
        val point: GeoPoint,
        val capturedAtMillis: Long,
        val isLastKnown: Boolean
    ) : LocationState
    data class PermissionDenied(val permanently: Boolean) : LocationState
    data object ProviderDisabled : LocationState
    data object Timeout : LocationState
    data class Error(val userMessage: String) : LocationState
}

/** Small, deterministic state holder kept separate from Android permission and provider APIs. */
internal class LocationStateMachine(
    initial: LocationState = LocationState.Unknown
) {
    var state: LocationState = initial
        private set

    fun requestStarted() {
        state = LocationState.Requesting
    }

    fun locationAvailable(point: GeoPoint, capturedAtMillis: Long, isLastKnown: Boolean) {
        state = LocationState.Available(point, capturedAtMillis, isLastKnown)
    }

    fun permissionDenied(permanently: Boolean) {
        state = LocationState.PermissionDenied(permanently)
    }

    fun providerDisabled() {
        state = LocationState.ProviderDisabled
    }

    fun timeout() {
        if (state is LocationState.Requesting) state = LocationState.Timeout
    }

    fun error(message: String) {
        state = LocationState.Error(message)
    }
}
