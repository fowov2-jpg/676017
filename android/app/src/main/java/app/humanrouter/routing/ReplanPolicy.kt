package app.humanrouter.routing

data class ReplanDecision(
    val shouldSuggest: Boolean,
    val savingSeconds: Int,
    val reason: String
)

object ReplanPolicy {
    fun evaluate(
        nowEpochSec: Long,
        current: RankedRoute,
        alternative: RankedRoute,
        lastSuggestionEpochSec: Long?,
        preferences: RoutePreferences = RoutePreferences()
    ): ReplanDecision {
        if (current.route.id == alternative.route.id) {
            return ReplanDecision(false, 0, "Маршрут не изменился")
        }

        if (lastSuggestionEpochSec != null &&
            nowEpochSec - lastSuggestionEpochSec < preferences.replanCooldownSeconds
        ) {
            return ReplanDecision(false, 0, "Слишком рано для повторного предложения")
        }

        val saving = (current.expectedArrivalEpochSec - alternative.expectedArrivalEpochSec)
            .toInt()

        if (saving < preferences.minimumSuggestedSavingSeconds) {
            return ReplanDecision(false, saving, "Экономия слишком мала")
        }

        val currentReliability = current.transferSuccessProbability
        val alternativeReliability = alternative.transferSuccessProbability

        val stronglyFaster = saving >= 600
        val reliabilityAcceptable = alternativeReliability >= 0.65 || alternativeReliability >= currentReliability - 0.08

        if (!stronglyFaster && !reliabilityAcceptable) {
            return ReplanDecision(false, saving, "Новый путь быстрее, но слишком рискованный")
        }

        val reason = when {
            alternative.route.legs.all { it.mode == TransportMode.WALK } ->
                "Пешком быстрее на ${saving / 60} мин"
            saving >= 600 ->
                "Новый маршрут экономит ${saving / 60} мин"
            alternative.route.transferCount < current.route.transferCount ->
                "Быстрее и меньше пересадок"
            else ->
                "Новый маршрут быстрее на ${saving / 60} мин"
        }

        return ReplanDecision(true, saving, reason)
    }
}
