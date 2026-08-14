package app.humanrouter.routing

import kotlin.math.max

object RouteRanker {
    fun rank(
        candidates: List<RouteCandidate>,
        objective: RouteObjective,
        preferences: RoutePreferences = RoutePreferences()
    ): List<RankedRoute> {
        return candidates
            .asSequence()
            .filter { it.walkMeters <= preferences.maxWalkMeters || it.legs.all { leg -> leg.mode == TransportMode.WALK } }
            .map { score(it, objective, preferences) }
            .sortedBy { it.score }
            .toList()
    }

    fun score(
        route: RouteCandidate,
        objective: RouteObjective,
        preferences: RoutePreferences = RoutePreferences()
    ): RankedRoute {
        val transferRisk = transferRisk(route)
        val expected = route.arrivalEpochSec
        val reliable = expected + route.uncertaintySeconds + transferRisk.expectedMissPenaltySeconds

        var score = when (objective) {
            RouteObjective.FASTEST -> expected.toDouble() + transferRisk.expectedMissPenaltySeconds * 0.30
            RouteObjective.RELIABLE -> reliable.toDouble()
            RouteObjective.LESS_WALKING -> expected + route.walkMeters * 0.35 + route.transferCount * 45.0
            RouteObjective.FEWER_TRANSFERS -> expected + route.transferCount * 300.0 + route.walkMeters * 0.08
        }

        if (preferences.preferLessWalking) score += route.walkMeters * 0.18
        if (preferences.preferFewerTransfers) score += route.transferCount * 150.0

        return RankedRoute(
            route = route,
            objective = objective,
            expectedArrivalEpochSec = expected,
            reliableArrivalEpochSec = reliable,
            transferSuccessProbability = transferRisk.successProbability,
            score = score
        )
    }

    private data class TransferRisk(
        val successProbability: Double,
        val expectedMissPenaltySeconds: Int
    )

    private fun transferRisk(route: RouteCandidate): TransferRisk {
        val transit = route.legs.filter { it.mode != TransportMode.WALK }
        if (transit.size <= 1) return TransferRisk(1.0, 0)

        var probability = 1.0
        var expectedPenalty = 0.0

        for (index in 1 until transit.size) {
            val previous = transit[index - 1]
            val next = transit[index]
            val required = requiredTransferBufferSeconds(previous.mode, next.mode)
            val available = max(0, next.transferBufferSeconds)
            val bufferFactor = (available.toDouble() / required.toDouble()).coerceIn(0.05, 1.0)
            val confidence = ((previous.realtimeConfidence + next.realtimeConfidence) / 2.0).coerceIn(0.05, 1.0)
            val success = (bufferFactor * confidence).coerceIn(0.02, 0.995)
            probability *= success
            expectedPenalty += (1.0 - success) * typicalNextDepartureSeconds(next.mode)
        }

        return TransferRisk(
            successProbability = probability.coerceIn(0.0, 1.0),
            expectedMissPenaltySeconds = expectedPenalty.toInt()
        )
    }

    private fun requiredTransferBufferSeconds(from: TransportMode, to: TransportMode): Int {
        val fromRail = from in setOf(TransportMode.METRO, TransportMode.MCC, TransportMode.MCD, TransportMode.TRAIN)
        val toRail = to in setOf(TransportMode.METRO, TransportMode.MCC, TransportMode.MCD, TransportMode.TRAIN)
        return when {
            fromRail && toRail -> 150
            fromRail || toRail -> 180
            else -> 90
        }
    }

    private fun typicalNextDepartureSeconds(mode: TransportMode): Int = when (mode) {
        TransportMode.METRO -> 180
        TransportMode.BUS, TransportMode.TRAM -> 600
        TransportMode.MCC, TransportMode.MCD -> 600
        TransportMode.TRAIN -> 1_200
        TransportMode.WALK -> 0
    }
}
