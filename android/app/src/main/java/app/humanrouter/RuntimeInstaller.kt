package app.humanrouter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

object RuntimeInstaller {
    data class Progress(
        val percent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val message: String,
        val done: Boolean = false
    )

    fun install(
        context: Context,
        shouldStop: () -> Boolean = { false },
        onProgress: (Progress) -> Unit
    ) {
        if (shouldStop()) return
        val base = BuildConfig.RUNTIME_BASE_URL
        val root = File(context.filesDir, "runtime").apply { mkdirs() }
        val localManifestFile = File(root, "manifest.json")
        val manifestText = readText(base + "manifest.json")
        if (shouldStop()) return
        val manifest = JSONObject(manifestText)
        val packs = manifest.getJSONArray("packs")
        val totalBytes = manifest.getLong("total_download_bytes")

        // Normal launches and periodic checks only need one small manifest request when the rolling
        // runtime has not changed. The previous manifest is written only after a fully verified
        // installation, so equality means the local pack set completed successfully before.
        val previousManifestText = runCatching {
            if (localManifestFile.exists()) localManifestFile.readText() else null
        }.getOrNull()
        if (previousManifestText == manifestText) {
            onProgress(Progress(100, totalBytes, totalBytes, "Данные актуальны", done = true))
            return
        }

        val previousManifest = previousManifestText
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val previousByFile = indexPacks(previousManifest?.optJSONArray("packs"), "file")
        val previousByInstallPath = indexPacks(previousManifest?.optJSONArray("packs"), "install_as")

        var completedBytes = 0L
        for (i in 0 until packs.length()) {
            if (shouldStop()) return
            val pack = packs.getJSONObject(i)
            val name = pack.getString("file")
            val installAs = pack.getString("install_as")
            val compression = pack.getString("compression")
            val expectedSha = pack.getString("sha256_compressed").lowercase()
            val expectedBytes = pack.getLong("compressed_bytes")
            val expectedRawBytes = pack.getLong("raw_bytes")
            val expectedRawSha = pack.getString("sha256_raw").lowercase()
            val cached = File(root, "downloads/$name")
            cached.parentFile?.mkdirs()

            val previousDownload = previousByFile[name]
            val cachedKnownGood = cached.exists() &&
                cached.length() == expectedBytes &&
                previousDownload != null &&
                previousDownload.optLong("compressed_bytes", -1L) == expectedBytes &&
                previousDownload.optString("sha256_compressed").equals(expectedSha, ignoreCase = true)

            if (!cachedKnownGood &&
                (!cached.exists() || cached.length() != expectedBytes || sha256(cached) != expectedSha)
            ) {
                val tmp = File(cached.absolutePath + ".part")
                var fileBytes = 0L
                val connection = openConnection(base + name)
                try {
                    connection.inputStream.buffered().use { input ->
                        tmp.outputStream().buffered().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                if (shouldStop()) return
                                val n = input.read(buffer)
                                if (n <= 0) break
                                output.write(buffer, 0, n)
                                fileBytes += n
                                val now = completedBytes + fileBytes
                                val percent = ((now * 100L) / totalBytes).toInt().coerceIn(0, 99)
                                onProgress(
                                    Progress(
                                        percent,
                                        now,
                                        totalBytes,
                                        "${i + 1}/${packs.length()} · $name"
                                    )
                                )
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                check(tmp.length() == expectedBytes) { "Size mismatch: $name" }
                check(sha256(tmp) == expectedSha) { "SHA-256 mismatch: $name" }
                if (cached.exists()) cached.delete()
                check(tmp.renameTo(cached)) { "Cannot save $name" }
            }

            if (shouldStop()) return
            completedBytes += expectedBytes
            val installed = File(root, installAs)
            installed.parentFile?.mkdirs()
            val previousInstalled = previousByInstallPath[installAs]
            val installedKnownGood = installed.exists() &&
                installed.length() == expectedRawBytes &&
                previousInstalled != null &&
                previousInstalled.optLong("raw_bytes", -1L) == expectedRawBytes &&
                previousInstalled.optString("sha256_raw").equals(expectedRawSha, ignoreCase = true)
            val installNeeded = !installedKnownGood &&
                (!installed.exists() || installed.length() != expectedRawBytes || sha256(installed) != expectedRawSha)

            if (installNeeded) {
                val tmpInstall = File(installed.absolutePath + ".part")
                when (compression) {
                    "gzip" -> GZIPInputStream(cached.inputStream()).use { input ->
                        tmpInstall.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                    }
                    "none" -> cached.inputStream().use { input ->
                        tmpInstall.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                    }
                    else -> error("Unsupported compression: $compression")
                }
                check(tmpInstall.length() == expectedRawBytes) { "Installed size mismatch: $installAs" }
                check(sha256(tmpInstall) == expectedRawSha) { "Installed SHA-256 mismatch: $installAs" }
                if (installed.exists()) installed.delete()
                check(tmpInstall.renameTo(installed)) { "Cannot install $installAs" }
            }

            val percent = ((completedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
            onProgress(
                Progress(
                    percent,
                    completedBytes,
                    totalBytes,
                    "Готово ${i + 1} из ${packs.length()}"
                )
            )
        }

        if (shouldStop()) return
        localManifestFile.writeText(manifestText)
        cleanupObsoleteFiles(root, packs)
        onProgress(Progress(100, totalBytes, totalBytes, "Данные Москвы готовы", done = true))
    }

    private fun indexPacks(packs: JSONArray?, key: String): Map<String, JSONObject> {
        if (packs == null) return emptyMap()
        val result = HashMap<String, JSONObject>(packs.length() * 2)
        for (i in 0 until packs.length()) {
            val pack = packs.optJSONObject(i) ?: continue
            val value = pack.optString(key)
            if (value.isNotBlank()) result[value] = pack
        }
        return result
    }

    private fun cleanupObsoleteFiles(root: File, packs: JSONArray) {
        val allowed = HashSet<String>(packs.length() * 2 + 1)
        allowed += "manifest.json"
        for (i in 0 until packs.length()) {
            val pack = packs.getJSONObject(i)
            allowed += pack.getString("install_as").replace('\\', '/')
            allowed += "downloads/${pack.getString("file").replace('\\', '/')}"
        }

        root.walkBottomUp().forEach { file ->
            if (file == root) return@forEach
            if (file.isFile) {
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (relative !in allowed) file.delete()
            } else if (file.isDirectory && file.list()?.isEmpty() == true) {
                file.delete()
            }
        }
    }

    private fun readText(url: String): String {
        val connection = openConnection(url)
        return try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 45_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "HumanRouter/0.1 Android")
        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IOException("HTTP $code: $url")
        }
        return connection
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
