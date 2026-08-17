package app.humanrouter

import org.junit.Assert.assertTrue
import org.junit.Test

class FastRoutePlannerContractTest {
    @Test
    fun configuredFastPathFitsTwoSecondFirstResultContract() {
        val boundedFirstPass = FastRoutePlanner.GEOCODE_BUDGET_MS + FastRoutePlanner.PREVIEW_BUDGET_MS

        assertTrue(
            "geocoding + preview budgets exceed first-result target: $boundedFirstPass > ${FastRoutePlanner.FIRST_RESULT_TARGET_MS}",
            boundedFirstPass <= FastRoutePlanner.FIRST_RESULT_TARGET_MS
        )
        assertTrue(
            "first-result target must remain at or below two seconds",
            FastRoutePlanner.FIRST_RESULT_TARGET_MS <= 2_000L
        )
    }
}
