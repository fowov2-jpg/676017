package app.aisupervisor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
        setContent {
            MaterialTheme {
                SupervisorScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupervisorScreen() {
    val context = LocalContext.current
    val db = remember { SupervisorDb(context) }
    val secrets = remember { SecretStore(context) }
    val state = remember { context.getSharedPreferences("supervisor_state", Context.MODE_PRIVATE) }
    val seedId = remember { db.ensureSeedProject() }
    var activeId by remember {
        val saved = state.getLong("active_project_id", -1L)
        mutableStateOf(if (db.getProject(saved) != null) saved else seedId)
    }
    var projects by remember { mutableStateOf(db.listProjects()) }
    var events by remember { mutableStateOf(db.listEvents(activeId)) }
    var integrations by remember { mutableStateOf(db.listIntegrations(activeId)) }
    var monitorEnabled by remember { mutableStateOf(state.getBoolean("monitor_enabled", false)) }
    var accessibilityConnected by remember { mutableStateOf(ChatAccessibilityService.isConnected()) }
    var showAddProject by remember { mutableStateOf(false) }
    var showProjectSettings by remember { mutableStateOf<Project?>(null) }
    var showAddIntegration by remember { mutableStateOf(false) }
    var showGitHubToken by remember { mutableStateOf(false) }

    LaunchedEffect(activeId) {
        state.edit().putLong("active_project_id", activeId).apply()
        while (true) {
            projects = db.listProjects()
            events = db.listEvents(activeId)
            integrations = db.listIntegrations(activeId)
            monitorEnabled = state.getBoolean("monitor_enabled", false)
            accessibilityConnected = ChatAccessibilityService.isConnected() || state.getBoolean("accessibility_connected", false)
            delay(2_000)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("AI Supervisor") }) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Контроль рабочей сессии", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = {},
                                label = { Text(if (monitorEnabled) "Монитор ON · 60 сек" else "Монитор OFF") }
                            )
                            AssistChip(
                                onClick = {},
                                label = { Text(if (accessibilityConnected) "Chat watcher ON" else "Chat watcher OFF") }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                if (monitorEnabled) SupervisorService.stop(context) else SupervisorService.start(context)
                                monitorEnabled = !monitorEnabled
                                state.edit().putBoolean("monitor_enabled", monitorEnabled).apply()
                            }) {
                                Text(if (monitorEnabled) "Остановить" else "Запустить")
                            }
                            OutlinedButton(onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Text("Accessibility") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showGitHubToken = true }) { Text("GitHub token") }
                            OutlinedButton(onClick = {
                                context.startService(Intent(context, SupervisorService::class.java).setAction(SupervisorService.ACTION_PING_NOW))
                            }) { Text("Пнуть сейчас") }
                        }
                        Text(
                            "Статусы считаются только по наблюдаемым событиям: ChatGPT UI, GitHub/CI и API подключённых сервисов. Скрытые рассуждения модели приложение не читает.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Проекты", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { showAddProject = true }) { Text("+ Добавить") }
                }
            }

            items(projects, key = { it.id }) { project ->
                ProjectCard(
                    project = project,
                    active = project.id == activeId,
                    onSelect = {
                        activeId = project.id
                        state.edit().putLong("active_project_id", project.id).apply()
                        events = db.listEvents(project.id)
                        integrations = db.listIntegrations(project.id)
                    },
                    onToggleEnabled = { enabled ->
                        db.updateProject(project.copy(enabled = enabled))
                        projects = db.listProjects()
                    },
                    onTogglePinger = { enabled ->
                        db.updateProject(project.copy(chatPinger = enabled))
                        projects = db.listProjects()
                    },
                    onSettings = { showProjectSettings = project }
                )
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Сервисы активного проекта", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { showAddIntegration = true }) { Text("+ API") }
                }
            }

            if (integrations.isEmpty()) {
                item {
                    Text("GitHub контролируется из карточки проекта. Vercel, Cloudflare, Supabase, Sentry, PostHog и любые HTTP API добавляются здесь.")
                }
            } else {
                items(integrations, key = { it.id }) { integration ->
                    IntegrationCard(
                        integration = integration,
                        onToggle = { enabled ->
                            db.updateIntegration(integration.copy(enabled = enabled))
                            integrations = db.listIntegrations(activeId)
                        },
                        onDelete = {
                            secrets.remove("integration_${integration.id}_token")
                            db.deleteIntegration(integration.id)
                            integrations = db.listIntegrations(activeId)
                        }
                    )
                }
            }

            item {
                Text("Хронология A → Я", style = MaterialTheme.typography.titleLarge)
            }

            if (events.isEmpty()) {
                item { Text("Событий пока нет. Запусти монитор — первая проверка начнётся сразу.") }
            } else {
                items(events, key = { it.id }) { event -> EventCard(event) }
            }
        }
    }

    if (showAddProject) {
        AddProjectDialog(
            onDismiss = { showAddProject = false },
            onAdd = { name, repo, branch ->
                val id = db.addProject(name, repo, branch)
                projects = db.listProjects()
                activeId = id
                state.edit().putLong("active_project_id", id).apply()
                showAddProject = false
            }
        )
    }

    showProjectSettings?.let { project ->
        ProjectSettingsDialog(
            project = project,
            allowDelete = projects.size > 1,
            onDismiss = { showProjectSettings = null },
            onSave = { updated ->
                db.updateProject(updated)
                projects = db.listProjects()
                showProjectSettings = null
            },
            onDelete = {
                db.listIntegrations(project.id).forEach { secrets.remove("integration_${it.id}_token") }
                db.deleteProject(project.id)
                val replacement = db.listProjects().firstOrNull()?.id ?: db.ensureSeedProject()
                activeId = replacement
                state.edit().putLong("active_project_id", replacement).apply()
                projects = db.listProjects()
                showProjectSettings = null
            }
        )
    }

    if (showAddIntegration) {
        AddIntegrationDialog(
            onDismiss = { showAddIntegration = false },
            onAdd = { type, label, endpoint, token ->
                val id = db.addIntegration(activeId, type, label, endpoint)
                if (token.isNotBlank()) secrets.put("integration_${id}_token", token)
                integrations = db.listIntegrations(activeId)
                showAddIntegration = false
            }
        )
    }

    if (showGitHubToken) {
        SecretDialog(
            title = "GitHub token",
            hint = "fine-grained PAT для private repos/Actions; для public можно оставить пустым",
            onDismiss = { showGitHubToken = false },
            onSave = { value ->
                secrets.put("github_token", value)
                showGitHubToken = false
            }
        )
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    active: Boolean,
    onSelect: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onTogglePinger: (Boolean) -> Unit,
    onSettings: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(project.name, fontWeight = FontWeight.Bold)
                    Text("${project.repo} · ${project.branch}", style = MaterialTheme.typography.bodySmall)
                }
                if (active) AssistChip(onClick = {}, label = { Text("ACTIVE") })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Мониторить проект")
                Switch(checked = project.enabled, onCheckedChange = onToggleEnabled)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Автопин ChatGPT")
                Switch(checked = project.chatPinger, onCheckedChange = onTogglePinger)
            }
            Text("Пороги: ${project.warningSeconds}с → ${project.stalledSeconds}с → ${project.hardSeconds}с", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!active) Button(onClick = onSelect) { Text("Переключиться") }
                OutlinedButton(onClick = onSettings) { Text("Настроить") }
            }
        }
    }
}

@Composable
private fun IntegrationCard(integration: Integration, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(integration.label, fontWeight = FontWeight.Bold)
                    Text(integration.type, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = integration.enabled, onCheckedChange = onToggle)
            }
            Text(integration.endpoint, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDelete) { Text("Удалить") }
        }
    }
}

@Composable
private fun EventCard(event: TimelineEvent) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${statusGlyph(event.status)} ${event.source}", fontWeight = FontWeight.Bold)
                Text(formatTime(event.timestamp), style = MaterialTheme.typography.bodySmall)
            }
            Text(event.title)
            if (event.detail.isNotBlank()) Text(event.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AddProjectDialog(onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый проект") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(repo, { repo = it }, label = { Text("GitHub owner/repo") }, singleLine = true)
                OutlinedTextField(branch, { branch = it }, label = { Text("Ветка") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && repo.count { it == '/' } == 1,
                onClick = { onAdd(name.trim(), repo.trim(), branch.trim().ifBlank { "main" }) }
            ) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun ProjectSettingsDialog(
    project: Project,
    allowDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (Project) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(project.name) }
    var repo by remember { mutableStateOf(project.repo) }
    var branch by remember { mutableStateOf(project.branch) }
    var warning by remember { mutableStateOf(project.warningSeconds.toString()) }
    var stalled by remember { mutableStateOf(project.stalledSeconds.toString()) }
    var hard by remember { mutableStateOf(project.hardSeconds.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки проекта") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(repo, { repo = it }, label = { Text("owner/repo") }, singleLine = true)
                OutlinedTextField(branch, { branch = it }, label = { Text("Ветка") }, singleLine = true)
                HorizontalDivider()
                Text("Таймауты в секундах")
                OutlinedTextField(warning, { warning = it.filter(Char::isDigit) }, label = { Text("Warning") }, singleLine = true)
                OutlinedTextField(stalled, { stalled = it.filter(Char::isDigit) }, label = { Text("Stalled / автопин") }, singleLine = true)
                OutlinedTextField(hard, { hard = it.filter(Char::isDigit) }, label = { Text("Hard / HUNG") }, singleLine = true)
                if (allowDelete) TextButton(onClick = onDelete) { Text("Удалить проект") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val w = warning.toIntOrNull()?.coerceAtLeast(60) ?: 180
                val s = stalled.toIntOrNull()?.coerceAtLeast(w + 60) ?: 300
                val h = hard.toIntOrNull()?.coerceAtLeast(s + 60) ?: 600
                onSave(project.copy(name = name.trim(), repo = repo.trim(), branch = branch.trim(), warningSeconds = w, stalledSeconds = s, hardSeconds = h))
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun AddIntegrationDialog(onDismiss: () -> Unit, onAdd: (String, String, String, String) -> Unit) {
    val types = listOf("VERCEL", "CLOUDFLARE", "SUPABASE", "SENTRY", "POSTHOG", "CUSTOM_HTTP")
    var type by remember { mutableStateOf(types.first()) }
    var expanded by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("Vercel") }
    var endpoint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подключить сервис") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { expanded = true }) { Text(type) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    types.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item) },
                            onClick = {
                                type = item
                                label = if (item == "CUSTOM_HTTP") "HTTP" else item.lowercase().replaceFirstChar { it.uppercase() }
                                expanded = false
                            }
                        )
                    }
                }
                OutlinedTextField(label, { label = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Полный HTTPS API endpoint") })
                OutlinedTextField(token, { token = it }, label = { Text("Bearer token (необязательно)") }, singleLine = true)
                Text(integrationHint(type), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && endpoint.startsWith("https://"),
                onClick = { onAdd(type, label.trim(), endpoint.trim(), token) }
            ) { Text("Подключить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun SecretDialog(title: String, hint: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value, { value = it }, label = { Text("Token") }, singleLine = true)
                Text(hint, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun integrationHint(type: String): String = when (type) {
    "VERCEL" -> "Например: https://api.vercel.com/v6/deployments?projectId=PROJECT_ID&limit=1"
    "CLOUDFLARE" -> "Cloudflare API endpoint проекта/Workers/Pages. Используется Authorization: Bearer TOKEN."
    "SUPABASE" -> "Supabase Management/health endpoint. Секрет хранится локально через Android Keystore."
    "SENTRY" -> "Например: https://sentry.io/api/0/projects/ORG/PROJECT/issues/?query=is:unresolved&sort=date"
    "POSTHOG" -> "Например: https://us.posthog.com/api/projects/PROJECT_ID/events/?limit=1"
    else -> "Любой HTTPS endpoint. HTTP 2xx считается доступным; JSON сохраняется в краткой форме."
}

private fun statusGlyph(status: MonitorStatus): String = when (status) {
    MonitorStatus.DONE -> "✅"
    MonitorStatus.RUNNING -> "🟢"
    MonitorStatus.WAITING -> "🟡"
    MonitorStatus.SUSPICIOUS -> "⚠️"
    MonitorStatus.STALLED -> "🟠"
    MonitorStatus.RECOVERING -> "🔄"
    MonitorStatus.FAILED, MonitorStatus.HUNG -> "🔴"
    MonitorStatus.IDLE -> "⚪"
}

private fun formatTime(timestamp: Long): String = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
