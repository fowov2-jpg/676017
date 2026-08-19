package app.aisupervisor.net

import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val code: Int, val body: String, val headers: Map<String, List<String>>)

object HttpClient {
    fun get(
        url: String,
        bearerToken: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 15_000
    ): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AI-Supervisor-Android/0.1")
            bearerToken?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            extraHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val headers = connection.headerFields.entries
                .filter { it.key != null && it.value != null }
                .associate { it.key!! to it.value!! }
            HttpResponse(code, body, headers)
        } finally {
            connection.disconnect()
        }
    }
}
