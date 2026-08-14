package app.humanrouter

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest

object RuntimeInstaller {
    fun install(context: Context, onProgress: (String) -> Unit) {
        val base = BuildConfig.RUNTIME_BASE_URL
        val manifestText = URL(base + "manifest.json").readText()
        val manifest = JSONObject(manifestText)
        val packs = manifest.getJSONArray("packs")
        val root = File(context.filesDir, "runtime").apply { mkdirs() }

        for (i in 0 until packs.length()) {
            val pack = packs.getJSONObject(i)
            val name = pack.getString("file")
            val expectedSha = pack.getString("sha256").lowercase()
            val expectedBytes = pack.getLong("bytes")
            val out = File(root, name)
            onProgress("${i + 1}/${packs.length()} $name")

            if (!out.exists() || out.length() != expectedBytes || sha256(out) != expectedSha) {
                URL(base + name).openStream().use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            check(out.length() == expectedBytes) { "Size mismatch: $name" }
            check(sha256(out) == expectedSha) { "SHA-256 mismatch: $name" }
        }
        File(root, "manifest.json").writeText(manifestText)
        onProgress("Runtime ${manifest.getString("version")} готов")
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
