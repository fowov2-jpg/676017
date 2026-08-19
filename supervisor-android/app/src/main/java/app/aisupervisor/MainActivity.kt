package app.aisupervisor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aisupervisor.accessibility.ChatAccessibilityService
import app.aisupervisor.data.SecretStore
import app.aisupervisor.data.SupervisorDb
import app.aisupervisor.model.Integration
import app.aisupervisor.model.MonitorStatus
import app.aisupervisor.model.Project
import app.aisupervisor.model.TimelineEvent
import app.aisupervisor.monitor.SupervisorService
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        setContent { MaterialTheme { SupervisorApp() } }
    }
}

private enum class Tab(val title: String, val icon: String) {
    OVERVIEW("Обзор", "●"), PROJECTS("Проекты", "▦"), EVENTS("События", "≡"), SETTINGS("Настройки", "⚙")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupervisorApp() {
    val context = LocalContext.current
    val db = remember { SupervisorDb(context) }
    val secrets = remember { SecretStore(context) }
    val prefs = remember { context.getSharedPreferences("supervisor_state", Context.MODE_PRIVATE) }
    val seed = remember { db.ensureSeedProject() }
    var activeId by remember { mutableStateOf(prefs.getLong("active_project_id", seed).takeIf { db.getProject(it) != null } ?: seed) }
    var tab by remember { mutableStateOf(Tab.OVERVIEW) }
    var projects by remember { mutableStateOf(db.listProjects()) }
    var events by remember { mutableStateOf(db.listEvents(activeId)) }
    var integrations by remember { mutableStateOf(db.listIntegrations(activeId)) }
    var monitorOn by remember { mutableStateOf(prefs.getBoolean("monitor_enabled", false)) }
    var chatOn by remember { mutableStateOf(ChatAccessibilityService.isConnected()) }
    var lastPoll by remember { mutableStateOf(prefs.getLong("last_poll_ms", 0L)) }
    var addProject by remember { mutableStateOf(false) }
    var editProject by remember { mutableStateOf<Project?>(null) }
    var addApi by remember { mutableStateOf(false) }
    var githubToken by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<TimelineEvent?>(null) }

    LaunchedEffect(activeId) {
        prefs.edit().putLong("active_project_id", activeId).apply()
        while (true) {
            projects = db.listProjects(); events = db.listEvents(activeId); integrations = db.listIntegrations(activeId)
            monitorOn = prefs.getBoolean("monitor_enabled", false)
            chatOn = ChatAccessibilityService.isConnected() || prefs.getBoolean("accessibility_connected", false)
            lastPoll = prefs.getLong("last_poll_ms", 0L)
            delay(1500)
        }
    }
    val active = projects.firstOrNull { it.id == activeId }

    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("AI Supervisor", fontWeight = FontWeight.SemiBold); active?.let { Text(it.name, style = MaterialTheme.typography.labelMedium) } } }) },
        bottomBar = { NavigationBar { Tab.entries.forEach { item -> NavigationBarItem(tab == item, { tab = item }, { Text(item.icon) }, label = { Text(item.title) }) } } }
    ) { pad ->
        when (tab) {
            Tab.OVERVIEW -> Overview(pad, active, events, integrations, monitorOn, chatOn, lastPoll,
                toggle = {
                    if (monitorOn) SupervisorService.stop(context) else SupervisorService.start(context)
                    monitorOn = !monitorOn; prefs.edit().putBoolean("monitor_enabled", monitorOn).apply()
                },
                ping = { context.startService(Intent(context, SupervisorService::class.java).setAction(SupervisorService.ACTION_PING_NOW)) },
                open = { details = it }, settings = { tab = Tab.SETTINGS })
            Tab.PROJECTS -> Projects(pad, projects, activeId, add = { addProject = true }, select = { activeId = it.id; tab = Tab.OVERVIEW }, edit = { editProject = it },
                enabled = { p, v -> db.updateProject(p.copy(enabled = v)); projects = db.listProjects() },
                pinger = { p, v -> db.updateProject(p.copy(chatPinger = v)); projects = db.listProjects() })
            Tab.EVENTS -> Events(pad, active, events) { details = it }
            Tab.SETTINGS -> Settings(pad, active, integrations, chatOn,
                accessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                token = { githubToken = true }, edit = { active?.let { editProject = it } }, addApi = { addApi = true },
                toggleApi = { i, v -> db.updateIntegration(i.copy(enabled = v)); integrations = db.listIntegrations(activeId) },
                deleteApi = { i -> secrets.remove("integration_${i.id}_token"); db.deleteIntegration(i.id); integrations = db.listIntegrations(activeId) })
        }
    }

    if (addProject) ProjectDialog(null, { addProject = false }) { name, repo, branch, _, _, _ ->
        activeId = db.addProject(name, repo, branch); projects = db.listProjects(); addProject = false; tab = Tab.OVERVIEW
    }
    editProject?.let { p -> ProjectDialog(p, { editProject = null }, allowDelete = projects.size > 1,
        delete = {
            db.listIntegrations(p.id).forEach { secrets.remove("integration_${it.id}_token") }; db.deleteProject(p.id)
            activeId = db.listProjects().firstOrNull()?.id ?: db.ensureSeedProject(); projects = db.listProjects(); editProject = null
        }) { name, repo, branch, w, s, h -> db.updateProject(p.copy(name = name, repo = repo, branch = branch, warningSeconds = w, stalledSeconds = s, hardSeconds = h)); projects = db.listProjects(); editProject = null }
    }
    if (addApi) ApiDialog({ addApi = false }) { type, label, endpoint, token ->
        val id = db.addIntegration(activeId, type, label, endpoint); secrets.put("integration_${id}_token", token); integrations = db.listIntegrations(activeId); addApi = false
    }
    if (githubToken) SecretDialog({ githubToken = false }) { secrets.put("github_token", it); githubToken = false }
    details?.let { EventDialog(it) { details = null } }
}

@Composable
private fun Overview(pad: PaddingValues, project: Project?, events: List<TimelineEvent>, integrations: List<Integration>, monitor: Boolean, chat: Boolean, lastPoll: Long, toggle: () -> Unit, ping: () -> Unit, open: (TimelineEvent) -> Unit, settings: () -> Unit) {
    val normal = events.filterNot { it.source.equals("Project inventory", true) }
    val latest = normal.firstOrNull() ?: events.firstOrNull()
    val problem = normal.firstOrNull { it.status.rank >= MonitorStatus.SUSPICIOUS.rank }
    val status = problem?.status ?: latest?.status ?: if (monitor) MonitorStatus.RUNNING else MonitorStatus.IDLE
    LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(headline(status), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(explain(status), style = MaterialTheme.typography.bodyMedium)
                project?.let { Text(it.name, fontWeight = FontWeight.SemiBold); Text("${it.repo} · ${it.branch}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { AssistChip({}, { Text(if (monitor) "Монитор ON" else "Монитор OFF") }); AssistChip({}, { Text(if (chat) "Chat ON" else "Chat OFF") }) }
                if (lastPoll > 0) Text("Последняя проверка ${time(lastPoll)}", style = MaterialTheme.typography.bodySmall)
                Button(toggle, Modifier.fillMaxWidth()) { Text(if (monitor) "Остановить мониторинг" else "Запустить мониторинг") }
                OutlinedButton(ping, Modifier.fillMaxWidth()) { Text("Пнуть ChatGPT сейчас") }
            } }
        }
        item { Title("Сейчас") }
        if (normal.isEmpty()) item { Empty("Нет рабочих событий. Запусти мониторинг.") }
        else items(normal.take(3), key = { it.id }) { EventCompact(it) { open(it) } }
        item { Title("Подключения"); Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("GitHub · встроен", fontWeight = FontWeight.Medium)
            Text("Дополнительные API: ${integrations.count { it.enabled }}", style = MaterialTheme.typography.bodySmall)
            Text(if (chat) "ChatGPT watcher подключён" else "ChatGPT watcher выключен", style = MaterialTheme.typography.bodySmall)
            TextButton(settings) { Text("Настроить сервисы") }
        } } }
    }
}

@Composable
private fun Projects(pad: PaddingValues, projects: List<Project>, activeId: Long, add: () -> Unit, select: (Project) -> Unit, edit: (Project) -> Unit, enabled: (Project, Boolean) -> Unit, pinger: (Project, Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("Проекты"); Text("Контексты полностью разделены.", style = MaterialTheme.typography.bodySmall); Button(add, Modifier.fillMaxWidth()) { Text("+ Добавить проект") } }
        items(projects, key = { it.id }) { p -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.name, fontWeight = FontWeight.Bold); Text("${p.repo} · ${p.branch}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.id == activeId) AssistChip({}, { Text("Активный") })
            SwitchRow("Мониторить", p.enabled) { enabled(p, it) }; SwitchRow("Автопин ChatGPT", p.chatPinger) { pinger(p, it) }
            Text("${p.warningSeconds}с → ${p.stalledSeconds}с → ${p.hardSeconds}с", style = MaterialTheme.typography.bodySmall)
            if (p.id != activeId) Button({ select(p) }, Modifier.fillMaxWidth()) { Text("Сделать активным") }
            OutlinedButton({ edit(p) }, Modifier.fillMaxWidth()) { Text("Настроить") }
        } } }
    }
}

@Composable
private fun Events(pad: PaddingValues, project: Project?, events: List<TimelineEvent>, open: (TimelineEvent) -> Unit) {
    var filter by remember { mutableStateOf("Все") }
    val shown = when (filter) {
        "Проблемы" -> events.filter { it.status.rank >= MonitorStatus.SUSPICIOUS.rank }
        "GitHub" -> events.filter { it.source.contains("GitHub", true) || it.source.contains("inventory", true) }
        "ChatGPT" -> events.filter { it.source.contains("Chat", true) || it.source.contains("Supervisor", true) }
        else -> events
    }
    LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Title("События"); project?.let { Text(it.name, style = MaterialTheme.typography.bodySmall) }; Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Все", "Проблемы", "GitHub", "ChatGPT").forEach { f -> FilterChip(filter == f, { filter = f }, { Text(f) }) }
        } }
        if (shown.isEmpty()) item { Empty("Событий по этому фильтру нет.") }
        else items(shown, key = { it.id }) { e -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${glyph(e.status)} ${e.title}", fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${e.source} · ${dateTime(e.timestamp)}", style = MaterialTheme.typography.bodySmall)
            preview(e)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            TextButton({ open(e) }) { Text("Подробнее") }
        } } }
    }
}

@Composable
private fun Settings(pad: PaddingValues, project: Project?, integrations: List<Integration>, chat: Boolean, accessibility: () -> Unit, token: () -> Unit, edit: () -> Unit, addApi: () -> Unit, toggleApi: (Integration, Boolean) -> Unit, deleteApi: (Integration) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("Настройки") }
        item { Block("ChatGPT watcher") { Text(if (chat) "Accessibility подключён" else "Accessibility выключен", style = MaterialTheme.typography.bodySmall); OutlinedButton(accessibility, Modifier.fillMaxWidth()) { Text("Открыть Accessibility") } } }
        item { Block("GitHub") { Text("Токен нужен для private repos и расширенного Actions API.", style = MaterialTheme.typography.bodySmall); OutlinedButton(token, Modifier.fillMaxWidth()) { Text("GitHub token") } } }
        item { Block("Активный проект") { Text(project?.name ?: "Не выбран", fontWeight = FontWeight.Medium); project?.let { Text("${it.repo} · ${it.branch}", style = MaterialTheme.typography.bodySmall) }; OutlinedButton(edit, Modifier.fillMaxWidth(), enabled = project != null) { Text("Настроить проект") } } }
        item { Block("Сервисы проекта") { Text("Vercel, Cloudflare, Supabase, Sentry, PostHog и Custom HTTP.", style = MaterialTheme.typography.bodySmall); Button(addApi, Modifier.fillMaxWidth(), enabled = project != null) { Text("+ Добавить API") } } }
        if (integrations.isEmpty()) item { Empty("Дополнительных API пока нет.") }
        else items(integrations, key = { it.id }) { i -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(i.label.ifBlank { i.type }, fontWeight = FontWeight.Bold); Text(i.type, style = MaterialTheme.typography.labelMedium); Text(i.endpoint, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            SwitchRow("Включено", i.enabled) { toggleApi(i, it) }; TextButton({ deleteApi(i) }) { Text("Удалить") }
        } } }
        item { Text("Supervisor показывает только наблюдаемые события и не читает скрытые рассуждения модели.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
@Composable private fun Empty(text: String) = Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(16.dp)) }
@Composable private fun Block(title: String, body: @Composable () -> Unit) = Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { Text(title, fontWeight = FontWeight.Bold); body() } }
@Composable private fun SwitchRow(label: String, checked: Boolean, change: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Switch(checked, change) }
@Composable private fun EventCompact(e: TimelineEvent, open: () -> Unit) = Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("${glyph(e.status)} ${e.title}", fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${e.source} · ${time(e.timestamp)}", style = MaterialTheme.typography.bodySmall); preview(e)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }; TextButton(open) { Text("Подробнее") } } }

@Composable
private fun ProjectDialog(project: Project?, dismiss: () -> Unit, allowDelete: Boolean = false, delete: () -> Unit = {}, save: (String, String, String, Int, Int, Int) -> Unit) {
    var name by remember { mutableStateOf(project?.name.orEmpty()) }; var repo by remember { mutableStateOf(project?.repo.orEmpty()) }; var branch by remember { mutableStateOf(project?.branch ?: "main") }
    var w by remember { mutableStateOf((project?.warningSeconds ?: 180).toString()) }; var s by remember { mutableStateOf((project?.stalledSeconds ?: 300).toString()) }; var h by remember { mutableStateOf((project?.hardSeconds ?: 600).toString()) }
    AlertDialog(dismiss, title = { Text(if (project == null) "Новый проект" else "Настройки проекта") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth(), singleLine = true); OutlinedTextField(repo, { repo = it }, label = { Text("owner/repo") }, modifier = Modifier.fillMaxWidth(), singleLine = true); OutlinedTextField(branch, { branch = it }, label = { Text("Ветка") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (project != null) { HorizontalDivider(); Text("Таймауты, секунд"); OutlinedTextField(w, { w = it.filter(Char::isDigit) }, label = { Text("Предупреждение") }, modifier = Modifier.fillMaxWidth(), singleLine = true); OutlinedTextField(s, { s = it.filter(Char::isDigit) }, label = { Text("Зависание") }, modifier = Modifier.fillMaxWidth(), singleLine = true); OutlinedTextField(h, { h = it.filter(Char::isDigit) }, label = { Text("Hard timeout") }, modifier = Modifier.fillMaxWidth(), singleLine = true); if (allowDelete) TextButton(delete) { Text("Удалить проект") } }
    } }, confirmButton = { TextButton({ val ww = w.toIntOrNull()?.coerceAtLeast(60) ?: 180; val ss = s.toIntOrNull()?.coerceAtLeast(ww + 60) ?: ww + 120; val hh = h.toIntOrNull()?.coerceAtLeast(ss + 60) ?: ss + 300; save(name.trim(), repo.trim(), branch.trim().ifBlank { "main" }, ww, ss, hh) }, enabled = name.isNotBlank() && repo.count { it == '/' } == 1) { Text("Сохранить") } }, dismissButton = { TextButton(dismiss) { Text("Отмена") } })
}

@Composable
private fun ApiDialog(dismiss: () -> Unit, save: (String, String, String, String) -> Unit) {
    val types = listOf("VERCEL", "CLOUDFLARE", "SUPABASE", "SENTRY", "POSTHOG", "CUSTOM_HTTP"); var type by remember { mutableStateOf(types.first()) }; var menu by remember { mutableStateOf(false) }; var label by remember { mutableStateOf("") }; var endpoint by remember { mutableStateOf("") }; var token by remember { mutableStateOf("") }
    AlertDialog(dismiss, title = { Text("Добавить API") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ menu = true }, Modifier.fillMaxWidth()) { Text(type) }; DropdownMenu(menu, { menu = false }) { types.forEach { t -> DropdownMenuItem({ Text(t) }, { type = t; menu = false }) } }
        OutlinedTextField(label, { label = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS endpoint") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(token, { token = it }, label = { Text("Token / API key") }, modifier = Modifier.fillMaxWidth()); Text("Токен хранится в Android Keystore.", style = MaterialTheme.typography.bodySmall)
    } }, confirmButton = { TextButton({ save(type, label.trim(), endpoint.trim(), token.trim()) }, enabled = label.isNotBlank() && endpoint.startsWith("https://")) { Text("Добавить") } }, dismissButton = { TextButton(dismiss) { Text("Отмена") } })
}

@Composable private fun SecretDialog(dismiss: () -> Unit, save: (String) -> Unit) { var value by remember { mutableStateOf("") }; AlertDialog(dismiss, title = { Text("GitHub token") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Fine-grained PAT. Пустое значение удаляет сохранённый токен.", style = MaterialTheme.typography.bodySmall); OutlinedTextField(value, { value = it }, label = { Text("Секрет") }, modifier = Modifier.fillMaxWidth()) } }, confirmButton = { TextButton({ save(value.trim()) }) { Text("Сохранить") } }, dismissButton = { TextButton(dismiss) { Text("Отмена") } }) }
@Composable private fun EventDialog(e: TimelineEvent, dismiss: () -> Unit) = AlertDialog(dismiss, title = { Text("${glyph(e.status)} ${e.title}") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("${e.source} · ${dateTime(e.timestamp)}", style = MaterialTheme.typography.bodySmall); Text("Статус: ${e.status.name}", fontWeight = FontWeight.Medium); if (e.detail.isNotBlank()) Text(e.detail) } }, confirmButton = { TextButton(dismiss) { Text("Закрыть") } })

private fun preview(e: TimelineEvent): String? {
    if (e.detail.isBlank()) return null
    if (e.source.equals("Project inventory", true)) {
        val p = e.detail.split(';').map { it.trim() }; val actions = p.count { it.startsWith("github-action:", true) }; val deps = p.count { it.startsWith("android:", true) || it.startsWith("npm:", true) || it.startsWith("python:", true) || it.startsWith("docker:", true) }
        return "Инвентаризация: $actions GitHub Actions${if (deps > 0) " · $deps зависимостей" else ""}. Полный список — в подробностях."
    }
    return e.detail.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
}
private fun headline(s: MonitorStatus) = when (s) { MonitorStatus.HUNG -> "🔴 Агент завис"; MonitorStatus.FAILED -> "🔴 Ошибка"; MonitorStatus.STALLED -> "🟠 Нет прогресса"; MonitorStatus.SUSPICIOUS -> "🟡 Проверяю зависание"; MonitorStatus.RECOVERING -> "🔄 Восстановление"; MonitorStatus.WAITING -> "⏳ Ожидание"; MonitorStatus.RUNNING -> "🟢 Работа идёт"; MonitorStatus.DONE -> "✅ Готово"; MonitorStatus.IDLE -> "⚪ Монитор готов" }
private fun explain(s: MonitorStatus) = when (s) { MonitorStatus.HUNG -> "Прогресса нет дольше hard timeout."; MonitorStatus.FAILED -> "Один из сервисов сообщил об ошибке."; MonitorStatus.STALLED -> "Прогресс остановился дольше порога."; MonitorStatus.SUSPICIOUS -> "Операция идёт необычно долго без изменений."; MonitorStatus.RECOVERING -> "Отправлено восстановительное действие."; MonitorStatus.WAITING -> "Процесс ожидает внешний сервис или действие."; MonitorStatus.RUNNING -> "Есть свежая наблюдаемая активность."; MonitorStatus.DONE -> "Последняя операция завершилась успешно."; MonitorStatus.IDLE -> "Запусти минутный мониторинг." }
private fun glyph(s: MonitorStatus) = when (s) { MonitorStatus.HUNG, MonitorStatus.FAILED -> "🔴"; MonitorStatus.STALLED -> "🟠"; MonitorStatus.SUSPICIOUS, MonitorStatus.WAITING -> "🟡"; MonitorStatus.RECOVERING -> "🔄"; MonitorStatus.RUNNING -> "🟢"; MonitorStatus.DONE -> "✅"; MonitorStatus.IDLE -> "⚪" }
private fun time(ts: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
private fun dateTime(ts: Long) = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(ts))
