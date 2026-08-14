package app.humanrouter.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRankerTest {
    private val a = RoutePlace("a", "A", GeoPoint(55.75, 37.61))
    private val b = RoutePlace("b", "B", GeoPoint(55.76, 37.62))
    private val c = RoutePlace("c", "C", GeoPoint(55.77, 37.63))

    @Test
    fun walkingWinsWhenItReallyArrivesEarlier() {
        val start = 1_000_000L
        val walk = RouteCandidate("walk", start, listOf(
            RouteLeg(TransportMode.WALK, a, c, start, start + 16 * 60, walkMeters = 1_250)
        ))
        val bus = RouteCandidate("bus", start, listOf(
            RouteLeg(TransportMode.WALK, a, b, start, start + 4 * 60, walkMeters = 280),
            RouteLeg(TransportMode.BUS, b, c, start + 8 * 60, start + 22 * 60, waitSeconds = 4 * 60, realtimeConfidence = 0.9)
        ))
        assertEquals("walk", RouteRanker.rank(listOf(bus, walk), RouteObjective.FASTEST).first().route.id)
    }

    @Test
    fun riskyTransferCanLoseToSlightlySlowerDirectTrip() {
        val start = 2_000_000L
        val risky = RouteCandidate("risky", start, listOf(
            RouteLeg(TransportMode.BUS, a, b, start, start + 10 * 60, realtimeConfidence = 0.8),
            RouteLeg(TransportMode.BUS, b, c, start + 10 * 60 + 20, start + 20 * 60, realtimeConfidence = 0.8, transferBufferSeconds = 20)
        ))
        val direct = RouteCandidate("direct", start, listOf(
            RouteLeg(TransportMode.BUS, a, c, start, start + 22 * 60, realtimeConfidence = 0.95)
        ))
        assertEquals("direct", RouteRanker.rank(listOf(risky, direct), RouteObjective.FASTEST).first().route.id)
    }

    @Test
    fun replanSuggestsWalkingWhenItSavesSeveralMinutes() {
        val start = 3_000_000L
        val current = RouteRanker.score(RouteCandidate("current", start, listOf(
            RouteLeg(TransportMode.BUS, a, c, start, start + 24 * 60, realtimeConfidence = 0.9)
        )), RouteObjective.FASTEST)
        val walk = RouteRanker.score(RouteCandidate("walk", start, listOf(
            RouteLeg(TransportMode.WALK, a, c, start, start + 17 * 60, walkMeters = 1_300)
        )), RouteObjective.FASTEST)
        val decision = ReplanPolicy.evaluate(start + 60, current, walk, null)
        assertTrue(decision.shouldSuggest)
        assertTrue(decision.reason.contains("Пешком быстрее"))
    }

    @Test
    fun replanIgnoresTinySaving() {
        val start = 4_000_000L
        val current = RouteRanker.score(RouteCandidate("current", start, listOf(
            RouteLeg(TransportMode.BUS, a, c, start, start + 20 * 60, realtimeConfidence = 0.9)
        )), RouteObjective.FASTEST)
        val alternative = RouteRanker.score(RouteCandidate("alternative", start, listOf(
            RouteLeg(TransportMode.TRAM, a, c, start, start + 18 * 60, realtimeConfidence = 0.9)
        )), RouteObjective.FASTEST)
        assertFalse(ReplanPolicy.evaluate(start + 60, current, alternative, null).shouldSuggest)
    }
}
