package app.aisupervisor.monitor

import android.content.Context
import android.util.Base64
import app.aisupervisor.data.SecretStore
import app.aisupervisor.model.MonitorStatus
import app.aisupervisor.model.ProbeResult
import app.aisupervisor.model.Project
import app.aisupervisor.net.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant

class GitHubProbe(private val context: Context, private val secrets: SecretStore) {
    private val prefs = context.getSharedPreferences("supervisor_state", Context.MODE_PRIVATE)

    fun poll(project: Project): List<ProbeResult> {
        val repo = project.repo.trim().removePrefix("https://github.com/").removeSuffix(".git").trim('/')
        if (repo.count { it == '/' } != 1) {
            return listOf(
                ProbeResult(
                    source = "GitHub",
                    status = MonitorStatus.FAILED,
                    title = "Некорректный репозиторий",
                    detail = "Ожидается owner/repository, получено: ${project.repo}",
                    fingerprint = "repo-invalid:${project.repo}",
                    countsAsProgress = false
                )
            )
        }
        val token = secrets.get("github_token")
        val results = mutableListOf<ProbeResult>()
        results += pollCommit(repo, project.branch, token)
        results += pollActions(repo, project, token)
        maybeInventory(repo, project, token)?.let(results::add)
        return results
    }

    private fun pollCommit(repo: String, branch: String, token: String?): ProbeResult = runCatching {
        val url = "https://api.github.com/repos/$repo/commits?sha=${encode(branch)}&per_page=1"
        val response = githubGet(url, token)
        check(response.code in 200..299) { "GitHub HTTP ${response.code}: ${response.body.take(300)}" }
        val array = JSONArray(response.body)
        check(array.length() > 0) { "В ветке нет коммитов" }
        val item = array.getJSONObject(0)
        val sha = item.getString("sha")
        val commit = item.getJSONObject("commit")
        val message = commit.optString("message").lineSequence().firstOrNull().orEmpty()
        val date = commit.optJSONObject("committer")?.optString("date").orEmpty()
        ProbeResult(
            source = "GitHub commit",
            status = MonitorStatus.DONE,
            title = "Последний commit ${sha.take(7)}",
            detail = listOf(message, date, "branch=$branch").filter { it.isNotBlank() }.joinToString(" · "),
            fingerprint = "commit:$sha",
            countsAsProgress = true
        )
    }.getOrElse { errorResult("GitHub commit", it) }

    private fun pollActions(repo: String, project: Project, token: String?): ProbeResult = runCatching {
        val url = "https://api.github.com/repos/$repo/actions/runs?branch=${encode(project.branch)}&per_page=5"
        val response = githubGet(url, token)
        check(response.code in 200..299) { "GitHub HTTP ${response.code}: ${response.body.take(300)}" }
        val root = JSONObject(response.body)
        val runs = root.optJSONArray("workflow_runs") ?: JSONArray()
        if (runs.length() == 0) {
            return ProbeResult(
                source = "GitHub Actions",
                status = MonitorStatus.IDLE,
                title = "Workflow runs пока нет",
                detail = "Ветка ${project.branch}",
                fingerprint = "actions:none:${project.branch}",
                countsAsProgress = false
            )
        }

        val run = runs.getJSONObject(0)
        val runId = run.getLong("id")
        val name = run.optString("name", "workflow")
        val statusText = run.optString("status")
        val conclusion = run.optString("conclusion")
        val headSha = run.optString("head_sha")
        val html = run.optString("html_url")

        if (statusText == "completed") {
            val status = when (conclusion) {
                "success", "neutral", "skipped" -> MonitorStatus.DONE
                "cancelled" -> MonitorStatus.WAITING
                else -> MonitorStatus.FAILED
            }
            return ProbeResult(
                source = "GitHub Actions",
                status = status,
                title = "$name · ${conclusion.ifBlank { "completed" }}",
                detail = "run=$runId · commit=${headSha.take(7)}${if (html.isNotBlank()) "\n$html" else ""}",
                fingerprint = "run:$runId:$statusText:$conclusion",
                countsAsProgress = true
            )
        }

        val jobInfo = pollCurrentJob(repo, runId, token)
        val startedAt = jobInfo.startedAt.ifBlank { run.optString("run_started_at") }
        val ageSec = secondsSince(startedAt)
        val monitorStatus = when {
            ageSec >= project.hardSeconds -> MonitorStatus.HUNG
            ageSec >= project.stalledSeconds -> MonitorStatus.STALLED
            ageSec >= project.warningSeconds -> MonitorStatus.SUSPICIOUS
            statusText == "queued" || statusText == "waiting" -> MonitorStatus.WAITING
            else -> MonitorStatus.RUNNING
        }
        val current = listOf(jobInfo.jobName, jobInfo.stepName).filter { it.isNotBlank() }.joinToString(" → ")
        val title = when (monitorStatus) {
            MonitorStatus.HUNG -> "CI вероятно завис: $current"
            MonitorStatus.STALLED -> "CI без прогресса: $current"
            MonitorStatus.SUSPICIOUS -> "CI работает дольше обычного: $current"
            MonitorStatus.WAITING -> "$name ожидает runner"
            else -> "$name выполняется"
        }
        ProbeResult(
            source = "GitHub Actions",
            status = monitorStatus,
            title = title,
            detail = "run=$runId · ${formatAge(ageSec)} · commit=${headSha.take(7)}${if (html.isNotBlank()) "\n$html" else ""}",
            fingerprint = "run:$runId:$statusText:${jobInfo.jobName}:${jobInfo.stepName}:${monitorStatus.name}",
            countsAsProgress = jobInfo.stepChanged
        )
    }.getOrElse { errorResult("GitHub Actions", it) }

    private fun pollCurrentJob(repo: String, runId: Long, token: String?): JobInfo {
        val response = githubGet("https://api.github.com/repos/$repo/actions/runs/$runId/jobs?per_page=100", token)
        if (response.code !in 200..299) return JobInfo("", "", "", false)
        val jobs = JSONObject(response.body).optJSONArray("jobs") ?: return JobInfo("", "", "", false)
        var candidate: JSONObject? = null
        for (i in 0 until jobs.length()) {
            val job = jobs.optJSONObject(i) ?: continue
            if (job.optString("status") == "in_progress" || job.optString("status") == "queued") {
                candidate = job
                break
            }
        }
        candidate ?: return JobInfo("", "", "", false)
        val jobName = candidate.optString("name")
        val steps = candidate.optJSONArray("steps") ?: JSONArray()
        var stepName = ""
        var startedAt = candidate.optString("started_at")
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("status") == "in_progress") {
                stepName = step.optString("name")
                startedAt = step.optString("started_at", startedAt)
                break
            }
        }
        val signature = "$runId|$jobName|$stepName"
        val key = "github_step_signature_$repo"
        val previous = prefs.getString(key, null)
        if (previous != signature) prefs.edit().putString(key, signature).apply()
        return JobInfo(jobName, stepName, startedAt, previous != null && previous != signature)
    }

    private fun maybeInventory(repo: String, project: Project, token: String?): ProbeResult? {
        val now = System.currentTimeMillis()
        val lastKey = "inventory_last_poll_${project.id}"
        val last = prefs.getLong(lastKey, 0L)
        if (last > 0 && now - last < 15 * 60_000L) return null
        prefs.edit().putLong(lastKey, now).apply()

        return runCatching {
            val treeUrl = "https://api.github.com/repos/$repo/git/trees/${encode(project.branch)}?recursive=1"
            val treeResponse = githubGet(treeUrl, token)
            check(treeResponse.code in 200..299) { "GitHub tree HTTP ${treeResponse.code}" }
            val tree = JSONObject(treeResponse.body).optJSONArray("tree") ?: JSONArray()
            val allPaths = mutableListOf<String>()
            val candidates = mutableListOf<Pair<String, String>>()
            for (i in 0 until tree.length()) {
                val item = tree.optJSONObject(i) ?: continue
                if (item.optString("type") != "blob") continue
                val path = item.optString("path")
                allPaths += path
                if (PluginInventory.isInventoryPath(path) && candidates.size < 32) {
                    candidates += path to item.optString("sha")
                }
            }

            val contents = linkedMapOf<String, String>()
            candidates.forEach { (path, sha) ->
                if (sha.isBlank()) return@forEach
                val blob = githubGet("https://api.github.com/repos/$repo/git/blobs/$sha", token)
                if (blob.code !in 200..299) return@forEach
                val obj = JSONObject(blob.body)
                if (obj.optString("encoding") != "base64") return@forEach
                val decoded = runCatching {
                    String(Base64.decode(obj.optString("content"), Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull() ?: return@forEach
                if (decoded.length <= 500_000) contents[path] = decoded
            }

            val snapshot = PluginInventory.snapshot(contents, allPaths)
            val previousKey = "inventory_fingerprint_${project.id}"
            val previous = prefs.getString(previousKey, null)
            prefs.edit().putString(previousKey, snapshot.fingerprint).apply()
            val first = previous == null
            ProbeResult(
                source = "Project inventory",
                status = MonitorStatus.DONE,
                title = if (first) "Инвентаризация проекта готова" else "Зависимости/плагины изменились",
                detail = snapshot.summary(),
                fingerprint = "inventory:${snapshot.fingerprint}",
                countsAsProgress = !first && previous != snapshot.fingerprint
            )
        }.getOrElse { errorResult("Project inventory", it) }
    }

    private fun githubGet(url: String, token: String?) = HttpClient.get(
        url = url,
        bearerToken = token,
        extraHeaders = mapOf("X-GitHub-Api-Version" to "2022-11-28", "Accept" to "application/vnd.github+json")
    )

    private fun errorResult(source: String, throwable: Throwable) = ProbeResult(
        source = source,
        status = MonitorStatus.FAILED,
        title = "$source: ошибка проверки",
        detail = throwable.message.orEmpty().take(1000),
        fingerprint = "error:${sha256(throwable.message.orEmpty())}",
        countsAsProgress = false
    )

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun secondsSince(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching { ((System.currentTimeMillis() - Instant.parse(value).toEpochMilli()) / 1000L).coerceAtLeast(0) }.getOrDefault(0L)
    }

    private fun formatAge(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private data class JobInfo(
        val jobName: String,
        val stepName: String,
        val startedAt: String,
        val stepChanged: Boolean
    )
}
