package app.humanrouter.routing

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceSchedulePolicyTest {
    @Test
    fun currentAndShortlyStaleSchedulesAreDistinguished() {
        val date = LocalDate.of(2026, 8, 14)
        val current = SurfaceSchedulePolicy.evaluate(date, date)
        assertTrue(current.usable)
        assertFalse(current.stale)

        val oneDayOld = SurfaceSchedulePolicy.evaluate(date, date.plusDays(1))
        assertTrue(oneDayOld.usable)
        assertTrue(oneDayOld.stale)

        assertTrue(SurfaceSchedulePolicy.evaluate(date, date.plusDays(3)).usable)
        assertFalse(SurfaceSchedulePolicy.evaluate(date, date.plusDays(4)).usable)
    }
}
