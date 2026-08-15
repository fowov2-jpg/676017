package app.humanrouter

import org.json.JSONObject

internal data class RuntimePackSpec(
    val file: String,
    val installAs: String,
    val compression: String,
    val compressedSha256: String,
    val compressedBytes: Long,
    val rawSha256: String,
    val rawBytes: Long,
    val required: Boolean
)

internal data class RuntimeManifestSpec(
    val version: String,
    val totalDownloadBytes: Long,
    val packs: List<RuntimePackSpec>
) {
    companion object {
        private val shaPattern = Regex("[0-9a-f]{64}")

        fun parse(text: String): RuntimeManifestSpec {
            val root = JSONObject(text)
            val version = root.getString("version").trim()
            require(version.isNotBlank()) { "Runtime version is empty" }
            val total = root.getLong("total_download_bytes")
            require(total >= 0L) { "Invalid total_download_bytes" }
            val items = root.getJSONArray("packs")
            require(items.length() > 0) { "Runtime manifest has no packs" }
            val packs = ArrayList<RuntimePackSpec>(items.length())
            val installPaths = HashSet<String>()
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val file = safeRelativePath(item.getString("file"), "file")
                val installAs = safeRelativePath(item.getString("install_as"), "install_as")
                require(installPaths.add(installAs)) { "Duplicate install_as=$installAs" }
                val compression = item.getString("compression")
                require(compression == "gzip" || compression == "none") {
                    "Unsupported compression=$compression"
                }
                val compressedSha = item.getString("sha256_compressed").lowercase()
                val rawSha = item.getString("sha256_raw").lowercase()
                require(shaPattern.matches(compressedSha)) { "Invalid compressed SHA-256: $file" }
                require(shaPattern.matches(rawSha)) { "Invalid raw SHA-256: $installAs" }
                val compressedBytes = item.getLong("compressed_bytes")
                val rawBytes = item.getLong("raw_bytes")
                require(compressedBytes >= 0L && rawBytes >= 0L) { "Negative pack size: $file" }
                packs += RuntimePackSpec(
                    file = file,
                    installAs = installAs,
                    compression = compression,
                    compressedSha256 = compressedSha,
                    compressedBytes = compressedBytes,
                    rawSha256 = rawSha,
                    rawBytes = rawBytes,
                    required = item.optBoolean("required", true)
                )
            }
            require(total == packs.sumOf { it.compressedBytes }) {
                "total_download_bytes does not match pack sizes"
            }
            return RuntimeManifestSpec(version, total, packs)
        }

        private fun safeRelativePath(raw: String, field: String): String {
            val normalized = raw.trim().replace('\\', '/')
            require(normalized.isNotBlank()) { "$field is empty" }
            require(!normalized.startsWith('/')) { "$field must be relative" }
            require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                "Unsafe $field=$raw"
            }
            return normalized
        }
    }
}
