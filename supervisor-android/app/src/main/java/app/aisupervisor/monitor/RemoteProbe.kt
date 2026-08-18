package app.aisupervisor.monitor

import app.aisupervisor.data.SecretStore
import app.aisupervisor.model.Integration
import app.aisupervisor.model.MonitorStatus
import app.aisupervisor.model.ProbeResult
import app.aisupervisor.net.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class RemoteProbe(private val secrets: SecretStore) {
    fun poll(integration: Integration): ProbeResult = runCatching {
        val token = secrets.get("integration_${integration.id}_token")
        val response = HttpClient.get(integration.endpoint, bearerToken = token)
        if (response.code !in 200..299) {
            return ProbeResult(
                source = integration.label,
                status = MonitorStatus.FAILED,
                title = "${integration.label}: HTTP ${response.code}",
                detail = response.body.take(1000),
                fingerprint = "${integration.id}:http:${response.code}:${hash(response.body.take(500))}",
                countsAsProgress = false
            )
        }

        val parsed = summarize(integration.type, response.body)
        ProbeResult(
            source = integration.label,
            status = parsed.first,
            title = parsed.second,
            detail = parsed.third,
            fingerprint = "${integration.id}:${parsed.first}:${hash(parsed.second + parsed.third)}",
            countsAsProgress = parsed.first in setOf(MonitorStatus.RUNNING, MonitorStatus.DONE, MonitorStatus.RECOVERING)
        )
    }.getOrElse { throwable ->
        ProbeResult(
            source = integration.label,
            status = MonitorStatus.FAILED,
            title = "${integration.label}: ошибка соединения",
            detail = throwable.message.orEmpty().take(1000),
            fingerprint = "${integration.id}:error:${hash(throwable.message.orEmpty())}",
            countsAsProgress = false
        )
    }

    private fun summarize(type: String, body: String): Triple<MonitorStatus, String, String> = when (type.uppercase()) {
        "VERCEL" -> summarizeVercel(body)
        "SENTRY" -> summarizeSentry(body)
        "CLOUDFLARE" -> summarizeCloudflare(body)
        "POSTHOG" -> summarizePostHog(body)
        "SUPABASE" -> summarizeSupabase(body)
        else -> Triple(MonitorStatus.DONE, "HTTP endpoint отвечает", body.take(700))
    }

    private fun summarizeVercel(body: String): Triple<MonitorStatus, String, String> = runCatching {
        val root = JSONObject(body)
        val deployments = root.optJSONArray("deployments") ?: JSONArray()
        if (deployments.length() == 0) return Triple(MonitorStatus.IDLE, "Vercel: deploys не найдены", "")
        val item = deployments.getJSONObject(0)
        val state = item.optString("readyState", item.optString("state", "UNKNOWN"))
        val name = item.optString("name", "deployment")
        val url = item.optString("url")
        val status = when (state.uppercase()) {
            "READY" -> MonitorStatus.DONE
            "BUILDING", "INITIALIZING", "QUEUED" -> MonitorStatus.RUNNING
            "CANCELED", "CANCELLED" -> MonitorStatus.WAITING
            "ERROR" -> MonitorStatus.FAILED
            else -> MonitorStatus.WAITING
        }
        Triple(status, "Vercel: $name · $state", url)
    }.getOrElse { Triple(MonitorStatus.DONE, "Vercel endpoint отвечает", body.take(700)) }

    private fun summarizeSentry(body: String): Triple<MonitorStatus, String, String> = runCatching {
        val issues = JSONArray(body)
        val count = issues.length()
        if (count == 0) Triple(MonitorStatus.DONE, "Sentry: unresolved ошибок нет", "")
        else {
            val first = issues.optJSONObject(0)
            val title = first?.optString("title").orEmpty()
            Triple(MonitorStatus.SUSPICIOUS, "Sentry: $count unresolved", title)
        }
    }.getOrElse { Triple(MonitorStatus.DONE, "Sentry endpoint отвечает", body.take(700)) }

    private fun summarizeCloudflare(body: String): Triple<MonitorStatus, String, String> = runCatching {
        val root = JSONObject(body)
        val success = root.optBoolean("success", true)
        val errors = root.optJSONArray("errors")
        if (success) Triple(MonitorStatus.DONE, "Cloudflare API: OK", summarizeJsonResult(root.opt("result")))
        else Triple(MonitorStatus.FAILED, "Cloudflare API: error", errors?.toString().orEmpty())
    }.getOrElse { Triple(MonitorStatus.DONE, "Cloudflare endpoint отвечает", body.take(700)) }

    private fun summarizePostHog(body: String): Triple<MonitorStatus, String, String> = runCatching {
        val root = JSONObject(body)
        val count = root.optInt("count", root.optJSONArray("results")?.length() ?: -1)
        Triple(MonitorStatus.DONE, "PostHog API: OK", if (count >= 0) "results=$count" else body.take(500))
    }.getOrElse { Triple(MonitorStatus.DONE, "PostHog endpoint отвечает", body.take(700)) }

    private fun summarizeSupabase(body: String): Triple<MonitorStatus, String, String> = runCatching {
        val root = JSONObject(body)
        val statusText = root.optString("status", root.optString("healthy", "OK"))
        Triple(MonitorStatus.DONE, "Supabase: $statusText", body.take(500))
    }.getOrElse { Triple(MonitorStatus.DONE, "Supabase endpoint отвечает", body.take(700)) }

    private fun summarizeJsonResult(value: Any?): String = when (value) {
        is JSONArray -> "result count=${value.length()}"
        is JSONObject -> value.toString().take(500)
        null -> ""
        else -> value.toString().take(500)
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
