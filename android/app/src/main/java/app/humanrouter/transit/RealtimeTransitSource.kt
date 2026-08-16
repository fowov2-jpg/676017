package app.humanrouter.transit

/**
 * Contract for a future authenticated/official realtime source.
 *
 * The app deliberately does not infer vehicle positions from timetable data. Until a source is
 * configured, UI must say that departures are scheduled/modelled rather than displaying fake live.
 */
internal interface RealtimeTransitSource {
    val availability: RealtimeTransitAvailability
}

internal sealed interface RealtimeTransitAvailability {
    data class Available(val sourceName: String) : RealtimeTransitAvailability
    data class Unavailable(val reason: String) : RealtimeTransitAvailability
}

internal object NoRealtimeTransitSource : RealtimeTransitSource {
    override val availability: RealtimeTransitAvailability = RealtimeTransitAvailability.Unavailable(
        "Live-позиции транспорта не подключены; показываем расписание и GPS пользователя."
    )
}

internal object RealtimeTransitRegistry {
    @Volatile
    private var source: RealtimeTransitSource = NoRealtimeTransitSource

    fun current(): RealtimeTransitSource = source

    fun replaceForProcess(newSource: RealtimeTransitSource) {
        source = newSource
    }

    fun userMessage(): String = when (val state = source.availability) {
        is RealtimeTransitAvailability.Available -> "Realtime: ${state.sourceName}"
        is RealtimeTransitAvailability.Unavailable -> state.reason
    }
}
