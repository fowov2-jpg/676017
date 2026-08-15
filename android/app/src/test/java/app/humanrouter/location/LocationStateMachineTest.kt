package app.humanrouter.location

import app.humanrouter.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationStateMachineTest {
    @Test
    fun timeoutOnlyReplacesAnActiveRequest() {
        val machine = LocationStateMachine()
        machine.timeout()
        assertTrue(machine.state is LocationState.Unknown)

        machine.requestStarted()
        machine.timeout()
        assertTrue(machine.state is LocationState.Timeout)
    }

    @Test
    fun lastKnownLocationIsRepresentedAsAUsableFallback() {
        val point = GeoPoint(55.751, 37.618)
        val machine = LocationStateMachine()
        machine.locationAvailable(point, capturedAtMillis = 123L, isLastKnown = true)

        val state = machine.state as LocationState.Available
        assertEquals(point, state.point)
        assertTrue(state.isLastKnown)
    }

    @Test
    fun permissionDenialKeepsPermanentStateExplicit() {
        val machine = LocationStateMachine()
        machine.permissionDenied(permanently = false)
        assertFalse((machine.state as LocationState.PermissionDenied).permanently)
        machine.permissionDenied(permanently = true)
        assertTrue((machine.state as LocationState.PermissionDenied).permanently)
    }
}
