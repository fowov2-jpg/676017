package app.humanrouter

import app.humanrouter.transit.NoRealtimeTransitSource
import app.humanrouter.transit.RealtimeTransitAvailability
import app.humanrouter.transit.RealtimeTransitRegistry
import app.humanrouter.transit.RealtimeTransitSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnePassRemediationTest {
    @After
    fun resetRealtimeRegistry() {
        RealtimeTransitRegistry.replaceForProcess(NoRealtimeTransitSource)
    }

    @Test
    fun journeySceneHasRequiredTwoSecondStopAndOrderedStages() {
        assertEquals(JourneySceneTimeline.Stage.RUN_TO_STOP, JourneySceneTimeline.frameAt(0).stage)
        assertEquals(JourneySceneTimeline.Stage.WAIT_AT_STOP, JourneySceneTimeline.frameAt(2_600).stage)
        assertEquals(JourneySceneTimeline.Stage.WAIT_AT_STOP, JourneySceneTimeline.frameAt(4_599).stage)
        assertEquals(JourneySceneTimeline.Stage.BUS_TO_METRO, JourneySceneTimeline.frameAt(4_600).stage)
        assertEquals(JourneySceneTimeline.Stage.DESCEND_TO_PLATFORM, JourneySceneTimeline.frameAt(7_500).stage)
        assertEquals(JourneySceneTimeline.Stage.TRAIN_DEPARTS, JourneySceneTimeline.frameAt(9_000).stage)
        assertEquals(JourneySceneTimeline.Stage.RUN_TO_STOP, JourneySceneTimeline.frameAt(12_000).stage)
    }

    @Test
    fun realtimeIsNeverClaimedWhenSourceIsUnavailable() {
        assertTrue(RealtimeTransitRegistry.current().availability is RealtimeTransitAvailability.Unavailable)
        assertTrue(RealtimeTransitRegistry.userMessage().contains("не подключены", ignoreCase = true))
    }

    @Test
    fun realtimeLabelChangesOnlyWhenExplicitSourceIsInstalled() {
        RealtimeTransitRegistry.replaceForProcess(object : RealtimeTransitSource {
            override val availability = RealtimeTransitAvailability.Available("verified-test-source")
        })
        assertEquals("Realtime: verified-test-source", RealtimeTransitRegistry.userMessage())
    }
}
