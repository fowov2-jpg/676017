package app.humanrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class RuntimeManifestSpecTest {
    @Test
    fun parsesValidatedPackAndPreservesInstallPath() {
        val manifest = RuntimeManifestSpec.parse(manifestJson("surface/data.sqlite"))
        assertEquals("test-runtime", manifest.version)
        assertEquals(3L, manifest.totalDownloadBytes)
        assertEquals("surface/data.sqlite", manifest.packs.single().installAs)
        assertEquals("gzip", manifest.packs.single().compression)
    }

    @Test
    fun rejectsPathTraversal() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeManifestSpec.parse(manifestJson("../outside.sqlite"))
        }
    }

    @Test
    fun computesKnownSha256() {
        val file = File("build/tmp/RuntimeManifestSpecTest/sha.txt").apply {
            parentFile?.mkdirs()
        }
        try {
            file.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                RuntimeInstaller.sha256(file)
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun interruptedCommitRestoresOldFilesAndRemovesNewFiles() {
        val filesRoot = File("build/tmp/RuntimeManifestSpecTest/recovery").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val runtime = File(filesRoot, "runtime")
            val oldTarget = File(runtime, "surface/data.sqlite").apply {
                parentFile?.mkdirs()
                writeText("new-partial")
            }
            val newTarget = File(runtime, "rail/new.json").apply {
                parentFile?.mkdirs()
                writeText("new-file")
            }
            File(filesRoot, "runtime-rollback/surface/data.sqlite").apply {
                parentFile?.mkdirs()
                writeText("old-complete")
            }
            File(filesRoot, "runtime-transaction.json").writeText(
                """
                {"schema":1,"changes":[
                  {"path":"surface/data.sqlite","had_target":true},
                  {"path":"rail/new.json","had_target":false}
                ]}
                """.trimIndent()
            )

            RuntimeInstaller.recoverInterruptedInstall(filesRoot)

            assertEquals("old-complete", oldTarget.readText())
            assertFalse(newTarget.exists())
            assertFalse(RuntimeInstaller.transactionInProgress(filesRoot))
            assertFalse(File(filesRoot, "runtime-rollback").exists())
        } finally {
            filesRoot.deleteRecursively()
        }
    }

    @Test
    fun malformedRecoveryJournalForcesACompleteVerifiedReinstall() {
        val filesRoot = File("build/tmp/RuntimeManifestSpecTest/malformed-recovery").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            File(filesRoot, "runtime/surface/data.sqlite").apply {
                parentFile?.mkdirs()
                writeText("possibly-partial")
            }
            File(filesRoot, "runtime-rollback/surface/data.sqlite").apply {
                parentFile?.mkdirs()
                writeText("unknown-state")
            }
            File(filesRoot, "runtime-transaction.json").writeText("{not-json")

            RuntimeInstaller.recoverInterruptedInstall(filesRoot)

            assertFalse(File(filesRoot, "runtime").exists())
            assertFalse(RuntimeInstaller.transactionInProgress(filesRoot))
            assertFalse(File(filesRoot, "runtime-rollback").exists())
        } finally {
            filesRoot.deleteRecursively()
        }
    }

    private fun manifestJson(installAs: String): String = """
        {
          "version": "test-runtime",
          "total_download_bytes": 3,
          "packs": [
            {
              "file": "pack.gz",
              "install_as": "$installAs",
              "compression": "gzip",
              "sha256_compressed": "${"a".repeat(64)}",
              "compressed_bytes": 3,
              "sha256_raw": "${"b".repeat(64)}",
              "raw_bytes": 7,
              "required": true
            }
          ]
        }
    """.trimIndent()
}
