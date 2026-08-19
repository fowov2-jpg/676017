package app.aisupervisor.model

enum class MonitorStatus(val rank: Int) {
    IDLE(0),
    DONE(1),
    RUNNING(2),
    WAITING(3),
    SUSPICIOUS(4),
    STALLED(5),
    RECOVERING(6),
    FAILED(7),
    HUNG(8);

    companion object {
        fun from(value: String?): MonitorStatus = entries.firstOrNull { it.name == value } ?: IDLE
    }
}

data class Project(
    val id: Long,
    val name: String,
    val repo: String,
    val branch: String,
    val enabled: Boolean,
    val chatPinger: Boolean,
    val warningSeconds: Int,
    val stalledSeconds: Int,
    val hardSeconds: Int,
    val createdAt: Long
)

data class Integration(
    val id: Long,
    val projectId: Long,
    val type: String,
    val label: String,
    val endpoint: String,
    val enabled: Boolean,
    val configJson: String
)

data class TimelineEvent(
    val id: Long,
    val projectId: Long,
    val timestamp: Long,
    val source: String,
    val status: MonitorStatus,
    val title: String,
    val detail: String,
    val fingerprint: String
)

data class ProbeResult(
    val source: String,
    val status: MonitorStatus,
    val title: String,
    val detail: String,
    val fingerprint: String,
    val countsAsProgress: Boolean = true
)
