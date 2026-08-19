package app.aisupervisor.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.aisupervisor.MainActivity
import app.aisupervisor.R
import app.aisupervisor.accessibility.ChatAccessibilityService
import app.aisupervisor.data.SecretStore
import app.aisupervisor.data.SupervisorDb
import app.aisupervisor.model.MonitorStatus
import app.aisupervisor.model.ProbeResult
import app.aisupervisor.model.Project
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class SupervisorService : Service() {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var db: SupervisorDb
    private lateinit var secrets: SecretStore
    private lateinit var state: android.content.SharedPreferences
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        db = SupervisorDb(this)
        secrets = SecretStore(this)
        state = getSharedPreferences("supervisor_state", MODE_PRIVATE)
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                state.edit().putBoolean("monitor_enabled", false).apply()
                stopLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PING_NOW -> {
                enterForeground("Ручной пинок…")
                executor.execute { pingActiveProject(manual = true) }
                ensureLoop()
            }
            else -> {
                state.edit().putBoolean("monitor_enabled", true).apply()
                enterForeground("Запуск мониторинга…")
                ensureLoop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLoop()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureLoop() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            while (running.get()) {
                runCatching { pollCycle() }.onFailure { throwable ->
                    updateForeground("Ошибка цикла: ${throwable.message.orEmpty().take(80)}")
                }
                var seconds = 0
                while (running.get() && seconds < POLL_SECONDS) {
                    Thread.sleep(1_000)
                    seconds++
                }
            }
        }
    }

    private fun stopLoop() {
        running.set(false)
    }

    private fun pollCycle() {
        val projects = db.listEnabledProjects()
        if (projects.isEmpty()) {
            updateForeground("Нет активных проектов")
            return
        }

        val github = GitHubProbe(this, secrets)
        val remote = RemoteProbe(secrets)
        var worst = MonitorStatus.IDLE
        var activeOperations = 0

        projects.forEach { project ->
            val now = System.currentTimeMillis()
            val progressKey = "project_last_progress_${project.id}"
            if (state.getLong(progressKey, 0L) == 0L) state.edit().putLong(progressKey, now).apply()

            val results = mutableListOf<ProbeResult>()
            results += github.poll(project)
            db.listIntegrations(project.id).filter { it.enabled }.forEach { integration ->
                results += remote.poll(integration)
            }

            results.forEach { result ->
                val inserted = db.addEventIfChanged(
                    projectId = project.id,
                    source = result.source,
                    status = result.status,
                    title = result.title,
                    detail = result.detail,
                    fingerprint = result.fingerprint
                )
                if (inserted && result.countsAsProgress) {
                    state.edit().putLong(progressKey, now).apply()
                }
                if (result.status.rank > worst.rank) worst = result.status
                if (result.status in ACTIVE_STATUSES) activeOperations++
                if (inserted && result.status.rank >= MonitorStatus.FAILED.rank) {
                    notifyAlert(project, result.status, result.title, result.detail)
                }
            }

            evaluateSessionHealth(project, results)
        }

        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        updateForeground("${projects.size} проект(ов) · активных операций $activeOperations · $stamp · ${worst.name}")
        state.edit().putLong("last_poll_ms", System.currentTimeMillis()).apply()
    }

    private fun evaluateSessionHealth(project: Project, probeResults: List<ProbeResult>) {
        val activeProjectId = state.getLong("active_project_id", -1L)
        if (activeProjectId != project.id) return

        val now = System.currentTimeMillis()
        val chatLastEvent = state.getLong("chat_last_event_ms", 0L)
        val chatLastChange = state.getLong("chat_last_change_ms", 0L)
        val projectProgress = state.getLong("project_last_progress_${project.id}", now)
        val externalActivity = probeResults.any { it.status in ACTIVE_STATUSES }
        val chatRecentlyObserved = chatLastEvent > 0 && now - chatLastEvent <= max(15 * 60_000L, project.hardSeconds * 2_000L)
        if (!externalActivity && !chatRecentlyObserved) return

        val effectiveProgress = max(projectProgress, chatLastChange)
        val ageSec = ((now - effectiveProgress) / 1_000L).coerceAtLeast(0L)
        val status = when {
            ageSec >= project.hardSeconds -> MonitorStatus.HUNG
            ageSec >= project.stalledSeconds -> MonitorStatus.STALLED
            ageSec >= project.warningSeconds -> MonitorStatus.SUSPICIOUS
            else -> return
        }

        val title = when (status) {
            MonitorStatus.HUNG -> "Агент завис или потерял связь"
            MonitorStatus.STALLED -> "Нет наблюдаемого прогресса"
            else -> "Работа подозрительно долго без изменений"
        }
        val detail = "Проект: ${project.name}\nНет наблюдаемого прогресса ${formatAge(ageSec)}. " +
            "Последняя активность учитывает ChatGPT, GitHub/CI и подключённые сервисы."
        val fingerprint = "session-health:${status.name}:${effectiveProgress / 60_000L}"
        val inserted = db.addEventIfChanged(project.id, "Supervisor", status, title, detail, fingerprint)
        if (inserted) notifyAlert(project, status, title, detail)

        if (project.chatPinger && status.rank >= MonitorStatus.STALLED.rank) {
            val lastPing = state.getLong("last_ping_${project.id}", 0L)
            val minInterval = max(project.stalledSeconds * 1_000L, 5 * 60_000L)
            if (now - lastPing >= minInterval) {
                val sent = ChatAccessibilityService.requestPing(buildPingMessage(project, ageSec))
                state.edit().putLong("last_ping_${project.id}", now).apply()
                db.addEventIfChanged(
                    project.id,
                    "Chat pinger",
                    if (sent) MonitorStatus.RECOVERING else MonitorStatus.WAITING,
                    if (sent) "Пинатель отправил сообщение" else "Пинатель не смог отправить сообщение",
                    if (sent) "ChatGPT был открыт; запрос на продолжение отправлен." else "Открой ChatGPT: Accessibility не видит активное поле ввода.",
                    "ping:${now / minInterval}:$sent"
                )
                if (!sent) notifyAlert(project, MonitorStatus.WAITING, "Нужно открыть ChatGPT", "Автопинок подготовлен, но ChatGPT сейчас недоступен AccessibilityService.")
            }
        }
    }

    private fun pingActiveProject(manual: Boolean) {
        val projectId = state.getLong("active_project_id", -1L)
        val project = db.getProject(projectId) ?: return
        val sent = ChatAccessibilityService.requestPing(buildPingMessage(project, 0L))
        db.addEventIfChanged(
            project.id,
            "Chat pinger",
            if (sent) MonitorStatus.RECOVERING else MonitorStatus.WAITING,
            if (sent) "${if (manual) "Ручной" else "Автоматический"} пинок отправлен" else "Не удалось пнуть ChatGPT",
            if (sent) "Запрос на продолжение отправлен в открытый чат." else "ChatGPT должен быть открыт, а AccessibilityService — включён.",
            "manual-ping:${System.currentTimeMillis() / 10_000L}:$sent"
        )
        if (!sent) notifyAlert(project, MonitorStatus.WAITING, "Пинок не отправлен", "Открой ChatGPT и повтори.")
    }

    private fun buildPingMessage(project: Project, ageSec: Long): String = buildString {
        append("Продолжай работу по активному проекту «${project.name}» (${project.repo}, ветка ${project.branch}) с последнего незавершённого шага. ")
        if (ageSec > 0) append("Наблюдаемого прогресса нет ${formatAge(ageSec)}. ")
        append("Проверь, не завис ли инструмент, сайт, тест, CI или дополнительная проверка. Не повторяй уже завершённые действия. Сначала коротко сообщи текущий статус и продолжай.")
    }

    private fun createChannels() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "AI Supervisor", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постоянный статус минутного мониторинга"
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "AI Supervisor alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Зависания, ошибки CI и проблемы внешних сервисов"
            }
        )
    }

    private fun buildForegroundNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, SupervisorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pingIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, SupervisorService::class.java).setAction(ACTION_PING_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_watchdog)
            .setContentTitle("AI Supervisor работает")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(R.drawable.ic_stat_watchdog, "Пнуть", pingIntent).build())
            .addAction(Notification.Action.Builder(R.drawable.ic_stat_watchdog, "Стоп", stopIntent).build())
            .build()
    }

    private fun enterForeground(text: String) {
        val notification = buildForegroundNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForeground(text: String) {
        notificationManager.notify(NOTIFICATION_ID, buildForegroundNotification(text))
    }

    private fun notifyAlert(project: Project, status: MonitorStatus, title: String, detail: String) {
        val openIntent = PendingIntent.getActivity(
            this,
            (project.id % Int.MAX_VALUE).toInt(),
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_watchdog)
            .setContentTitle("${status.name} · ${project.name}")
            .setContentText(title)
            .setStyle(Notification.BigTextStyle().bigText("$title\n$detail"))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify((10_000 + project.id % 5_000).toInt(), notification)
    }

    private fun formatAge(seconds: Long): String = when {
        seconds < 60 -> "${seconds}с"
        seconds < 3600 -> "${seconds / 60}м ${seconds % 60}с"
        else -> "${seconds / 3600}ч ${(seconds % 3600) / 60}м"
    }

    companion object {
        const val ACTION_START = "app.aisupervisor.START"
        const val ACTION_STOP = "app.aisupervisor.STOP"
        const val ACTION_PING_NOW = "app.aisupervisor.PING_NOW"
        private const val POLL_SECONDS = 60
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_MONITOR = "supervisor_monitor"
        private const val CHANNEL_ALERTS = "supervisor_alerts"
        private val ACTIVE_STATUSES = setOf(
            MonitorStatus.RUNNING,
            MonitorStatus.WAITING,
            MonitorStatus.SUSPICIOUS,
            MonitorStatus.STALLED,
            MonitorStatus.HUNG,
            MonitorStatus.RECOVERING
        )

        fun start(context: Context) {
            val intent = Intent(context, SupervisorService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SupervisorService::class.java).setAction(ACTION_STOP))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val state = context.getSharedPreferences("supervisor_state", Context.MODE_PRIVATE)
        if (!state.getBoolean("monitor_enabled", false)) return
        runCatching { SupervisorService.start(context) }
    }
}
