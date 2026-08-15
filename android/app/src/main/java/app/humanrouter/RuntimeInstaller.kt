package app.humanrouter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

    private data class ChangedFile(val target: File, val backup: File?)
    private data class PlannedChange(
        val relativePath: String,
        val target: File,
        val staged: File,
        val hadTarget: Boolean
    )
    private class InstallCancelled : RuntimeException()

    fun install(
        context: Context,
        shouldStop: () -> Boolean = { false },
        onProgress: (Progress) -> Unit
    ) {
        if (shouldStop()) return
        val base = BuildConfig.RUNTIME_BASE_URL
        check(base.startsWith("https://")) { "Runtime URL must use HTTPS" }
        val filesRoot = context.filesDir
        val root = File(filesRoot, "runtime").apply { mkdirs() }
        val rollbackRoot = File(filesRoot, "runtime-rollback")
        val stagingRoot = File(filesRoot, "runtime-staging")
        val transactionFile = File(filesRoot, TRANSACTION_FILE_NAME)
        recoverInterruptedInstall(filesRoot)
        stagingRoot.deleteRecursively()
        check(stagingRoot.isDirectory || stagingRoot.mkdirs()) { "Cannot create runtime staging directory" }
        val localManifest = File(root, "manifest.json")
        val manifestText = readText(base + "manifest.json")
        if (shouldStop()) return
        val manifest = RuntimeManifestSpec.parse(manifestText)

        val previousText = runCatching { localManifest.takeIf(File::exists)?.readText() }.getOrNull()
        if (previousText == manifestText && installedPacksAreValid(root, manifest.packs)) {
            onProgress(
                Progress(100, manifest.totalDownloadBytes, manifest.totalDownloadBytes, "Данные актуальны", done = true)
            )
            return
        }

        rollbackRoot.deleteRecursively()
        transactionFile.delete()
        val planned = ArrayList<PlannedChange>()
        val changed = ArrayList<ChangedFile>()
        var committed = false

        try {
            var completedBytes = 0L
            for ((index, pack) in manifest.packs.withIndex()) {
                checkNotStopped(shouldStop)
                val cached = resolveWithin(root, "downloads/${pack.file}")
                cached.parentFile?.mkdirs()
                if (!fileMatches(cached, pack.compressedBytes, pack.compressedSha256)) {
                    downloadPack(
                        url = base + pack.file,
                        destination = cached,
                        expectedBytes = pack.compressedBytes,
                        expectedSha = pack.compressedSha256,
                        completedBytes = completedBytes,
                        totalBytes = manifest.totalDownloadBytes,
                        packIndex = index,
                        packCount = manifest.packs.size,
                        shouldStop = shouldStop,
                        onProgress = onProgress
                    )
                }

                checkNotStopped(shouldStop)
                val installed = resolveWithin(root, pack.installAs)
                if (!fileMatches(installed, pack.rawBytes, pack.rawSha256)) {
                    val staged = resolveWithin(stagingRoot, pack.installAs)
                    staged.parentFile?.mkdirs()
                    staged.delete()
                    when (pack.compression) {
                        "gzip" -> GZIPInputStream(cached.inputStream()).use { input ->
                            staged.outputStream().buffered().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
                        }
                        "none" -> cached.inputStream().buffered().use { input ->
                            staged.outputStream().buffered().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
                        }
                    }
                    check(fileMatches(staged, pack.rawBytes, pack.rawSha256)) {
                        "Installed checksum mismatch: ${pack.installAs}"
                    }
                    planned += PlannedChange(
                        relativePath = pack.installAs,
                        target = installed,
                        staged = staged,
                        hadTarget = installed.exists()
                    )
                }

                completedBytes += pack.compressedBytes
                val percent = if (manifest.totalDownloadBytes == 0L) 100 else {
                    ((completedBytes * 100L) / manifest.totalDownloadBytes).toInt().coerceIn(0, 100)
                }
                onProgress(
                    Progress(
                        percent,
                        completedBytes,
                        manifest.totalDownloadBytes,
                        "Готово ${index + 1} из ${manifest.packs.size}"
                    )
                )
            }

            checkNotStopped(shouldStop)
            if (previousText != manifestText || !localManifest.exists()) {
                val stagedManifest = resolveWithin(stagingRoot, "manifest.json")
                writeDurably(stagedManifest, manifestText)
                RuntimeManifestSpec.parse(stagedManifest.readText())
                planned += PlannedChange(
                    relativePath = "manifest.json",
                    target = localManifest,
                    staged = stagedManifest,
                    hadTarget = localManifest.exists()
                )
            }

            if (planned.isNotEmpty()) {
                check(rollbackRoot.isDirectory || rollbackRoot.mkdirs()) {
                    "Cannot create runtime rollback directory"
                }
                writeTransactionJournal(transactionFile, planned)
                for (change in planned) {
                    replaceWithRollback(
                        change.target,
                        change.staged,
                        rollbackRoot,
                        change.relativePath,
                        changed
                    )
                }
                check(transactionFile.delete() || !transactionFile.exists()) {
                    "Cannot complete runtime transaction"
                }
            }
            committed = true
            runCatching { cleanupObsoleteFiles(root, manifest.packs) }
            rollbackRoot.deleteRecursively()
            onProgress(
                Progress(
                    100,
                    manifest.totalDownloadBytes,
                    manifest.totalDownloadBytes,
                    "Данные Москвы готовы",
                    done = true
                )
            )
        } catch (_: InstallCancelled) {
            if (!committed) {
                rollbackChangedFiles(changed, transactionFile)
            }
        } catch (error: Throwable) {
            if (!committed) {
                runCatching { rollbackChangedFiles(changed, transactionFile) }
                    .exceptionOrNull()
                    ?.let(error::addSuppressed)
            }
            throw error
        } finally {
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.forEach(File::delete)
            stagingRoot.deleteRecursively()
            if (!committed && !transactionFile.exists()) rollbackRoot.deleteRecursively()
        }
    }

    internal fun transactionInProgress(filesRoot: File): Boolean =
        File(filesRoot, TRANSACTION_FILE_NAME).exists()

    /** Restores the last complete runtime if the process stopped during the short commit window. */
    internal fun recoverInterruptedInstall(filesRoot: File) {
        val transactionFile = File(filesRoot, TRANSACTION_FILE_NAME)
        val transactionPart = File(transactionFile.absolutePath + ".part")
        val rollbackRoot = File(filesRoot, "runtime-rollback")
        val stagingRoot = File(filesRoot, "runtime-staging")
        if (!transactionFile.exists()) {
            transactionPart.delete()
            rollbackRoot.deleteRecursively()
            stagingRoot.deleteRecursively()
            return
        }

        val runtimeRoot = File(filesRoot, "runtime").apply { mkdirs() }
        val changes = runCatching {
            JSONObject(transactionFile.readText()).getJSONArray("changes")
        }.getOrElse {
            // A journal that cannot be trusted cannot prove which files were already replaced.
            // Discard the runtime so the next worker run performs a complete verified install.
            runtimeRoot.deleteRecursively()
            transactionFile.delete()
            transactionPart.delete()
            rollbackRoot.deleteRecursively()
            stagingRoot.deleteRecursively()
            return
        }
        for (index in changes.length() - 1 downTo 0) {
            val item = changes.getJSONObject(index)
            val relativePath = item.getString("path")
            val hadTarget = item.getBoolean("had_target")
            val target = resolveWithin(runtimeRoot, relativePath)
            val backup = resolveWithin(rollbackRoot, relativePath)
            if (backup.exists()) {
                target.delete()
                target.parentFile?.mkdirs()
                moveReplacing(backup, target)
            } else if (!hadTarget) {
                target.delete()
            }
        }
        check(transactionFile.delete() || !transactionFile.exists()) {
            "Cannot clear recovered runtime transaction"
        }
        transactionPart.delete()
        rollbackRoot.deleteRecursively()
        stagingRoot.deleteRecursively()
    }

    private fun writeTransactionJournal(file: File, changes: List<PlannedChange>) {
        val items = JSONArray()
        for (change in changes) {
            items.put(
                JSONObject()
                    .put("path", change.relativePath)
                    .put("had_target", change.hadTarget)
            )
        }
        val staged = File(file.absolutePath + ".part")
        writeDurably(staged, JSONObject().put("schema", 1).put("changes", items).toString())
        moveReplacing(staged, file)
    }

    private fun writeDurably(file: File, text: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { stream ->
            val writer = stream.writer(Charsets.UTF_8)
            writer.write(text)
            writer.flush()
            stream.fd.sync()
        }
    }

    private fun downloadPack(
        url: String,
        destination: File,
        expectedBytes: Long,
        expectedSha: String,
        completedBytes: Long,
        totalBytes: Long,
        packIndex: Int,
        packCount: Int,
        shouldStop: () -> Boolean,
        onProgress: (Progress) -> Unit
    ) {
        val staged = File(destination.absolutePath + ".part")
        staged.delete()
        var fileBytes = 0L
        val connection = openConnection(url)
        try {
            connection.inputStream.buffered().use { input ->
                staged.outputStream().buffered().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        checkNotStopped(shouldStop)
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        fileBytes += count
                        val now = completedBytes + fileBytes
                        val percent = if (totalBytes == 0L) 99 else {
                            ((now * 100L) / totalBytes).toInt().coerceIn(0, 99)
                        }
                        onProgress(
                            Progress(percent, now, totalBytes, "${packIndex + 1}/$packCount · ${destination.name}")
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        check(fileMatches(staged, expectedBytes, expectedSha)) { "Downloaded checksum mismatch: ${destination.name}" }
        moveReplacing(staged, destination)
    }

    private fun replaceWithRollback(
        target: File,
        staged: File,
        rollbackRoot: File,
        relativePath: String,
        changes: MutableList<ChangedFile>
    ) {
        val backup = if (target.exists()) {
            resolveWithin(rollbackRoot, relativePath).also {
                it.parentFile?.mkdirs()
                moveReplacing(target, it)
            }
        } else {
            null
        }
        changes += ChangedFile(target, backup)
        moveReplacing(staged, target)
    }

    private fun rollbackChangedFiles(changes: List<ChangedFile>, transactionFile: File) {
        for (change in changes.asReversed()) {
            check(change.target.delete() || !change.target.exists()) {
                "Cannot remove partially installed runtime file: ${change.target.name}"
            }
            change.backup?.takeIf(File::exists)?.let { backup ->
                change.target.parentFile?.mkdirs()
                moveReplacing(backup, change.target)
            }
        }
        check(transactionFile.delete() || !transactionFile.exists()) {
            "Cannot clear rolled-back runtime transaction"
        }
    }

    private fun installedPacksAreValid(root: File, packs: List<RuntimePackSpec>): Boolean =
        packs.filter { it.required }.all { pack ->
            fileMatches(resolveWithin(root, pack.installAs), pack.rawBytes, pack.rawSha256)
        }

    private fun fileMatches(file: File, bytes: Long, sha: String): Boolean =
        file.exists() && file.isFile && file.length() == bytes && sha256(file) == sha

    private fun cleanupObsoleteFiles(root: File, packs: List<RuntimePackSpec>) {
        val allowed = HashSet<String>(packs.size * 2 + 1)
        allowed += "manifest.json"
        for (pack in packs) {
            allowed += pack.installAs
            allowed += "downloads/${pack.file}"
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

    private fun resolveWithin(root: File, relativePath: String): File {
        val resolved = File(root, relativePath).canonicalFile
        val canonicalRoot = root.canonicalFile
        check(resolved.toPath().startsWith(canonicalRoot.toPath())) { "Unsafe runtime path: $relativePath" }
        return resolved
    }

    private fun moveReplacing(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun checkNotStopped(shouldStop: () -> Boolean) {
        if (shouldStop()) throw InstallCancelled()
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
        check(url.startsWith("https://")) { "Only HTTPS runtime URLs are allowed" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 45_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "VremyaHodom/0.1 Android")
        connection.connect()
        check(connection.url.protocol.equals("https", ignoreCase = true)) { "Runtime redirected outside HTTPS" }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IOException("HTTP $code")
        }
        return connection
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val COPY_BUFFER_BYTES = 256 * 1024
    private const val TRANSACTION_FILE_NAME = "runtime-transaction.json"
}
