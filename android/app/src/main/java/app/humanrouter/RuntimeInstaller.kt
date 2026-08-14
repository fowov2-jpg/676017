package app.humanrouter

import android.content.Context
import org.json.JSONObject
import java.io.File
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

    fun install(context: Context, onProgress: (Progress) -> Unit) {
        val base = BuildConfig.RUNTIME_BASE_URL
        val manifestText = URL(base + "manifest.json").readText()
        val manifest = JSONObject(manifestText)
        val packs = manifest.getJSONArray("packs")
        val totalBytes = manifest.getLong("total_download_bytes")
        val root = File(context.filesDir, "runtime").apply { mkdirs() }
        var completedBytes = 0L

        for (i in 0 until packs.length()) {
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

            if (!cached.exists() || cached.length() != expectedBytes || sha256(cached) != expectedSha) {
                val tmp = File(cached.absolutePath + ".part")
                var fileBytes = 0L
                URL(base + name).openStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            fileBytes += n
                            val now = completedBytes + fileBytes
                            val percent = ((now * 100L) / totalBytes).toInt().coerceIn(0, 99)
                            onProgress(Progress(percent, now, totalBytes, "${i + 1}/${packs.length()} · $name"))
                        }
                    }
                }
                check(tmp.length() == expectedBytes) { "Size mismatch: $name" }
                check(sha256(tmp) == expectedSha) { "SHA-256 mismatch: $name" }
                if (cached.exists()) cached.delete()
                check(tmp.renameTo(cached)) { "Cannot save $name" }
            }

            completedBytes += expectedBytes
            val installed = File(root, installAs)
            installed.parentFile?.mkdirs()
            val installNeeded = !installed.exists() || installed.length() != expectedRawBytes || sha256(installed) != expectedRawSha
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
            onProgress(Progress(percent, completedBytes, totalBytes, "Готово ${i + 1} из ${packs.length()}"))
        }

        File(root, "manifest.json").writeText(manifestText)
        onProgress(Progress(100, totalBytes, totalBytes, "Данные Москвы готовы", done = true))
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
