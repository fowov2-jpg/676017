package app.aisupervisor.monitor

import org.json.JSONObject
import java.security.MessageDigest

data class InventoryItem(val kind: String, val name: String, val version: String, val path: String)

data class InventorySnapshot(
    val items: List<InventoryItem>,
    val detectedServices: Set<String>,
    val fingerprint: String
) {
    fun summary(maxItems: Int = 40): String {
        val lines = items.sortedWith(compareBy<InventoryItem> { it.kind }.thenBy { it.name })
            .take(maxItems)
            .map { item -> "${item.kind}: ${item.name}${item.version.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()} · ${item.path}" }
            .toMutableList()
        if (items.size > maxItems) lines += "… ещё ${items.size - maxItems}"
        if (detectedServices.isNotEmpty()) lines += "Сервисы: ${detectedServices.sorted().joinToString()}"
        return lines.joinToString("\n")
    }
}

object PluginInventory {
    private val gradlePlugin = Regex("""id\([\"']([^\"']+)[\"']\)(?:\s+version\s+[\"']([^\"']+)[\"'])?""")
    private val gradleDependency = Regex(
        """(?:implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|debugImplementation|kapt|ksp)\s*\(\s*[\"']([^:\"']+):([^:\"']+):([^\"']+)[\"']\s*\)"""
    )
    private val actionUse = Regex("""(?m)^\s*-?\s*uses:\s*([^\s@]+)@([^\s#]+)""")
    private val dockerFrom = Regex("""(?im)^\s*FROM\s+([^\s]+)""")

    fun isInventoryPath(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name in setOf(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "libs.versions.toml", "package.json", "requirements.txt", "pyproject.toml",
            "Pipfile", "poetry.lock", "go.mod", "Cargo.toml", "pom.xml", "composer.json",
            "Dockerfile", "wrangler.toml", "wrangler.json", "wrangler.jsonc", "vercel.json",
            "sentry.properties", "supabase.toml"
        ) || path.startsWith(".github/workflows/") && (path.endsWith(".yml") || path.endsWith(".yaml"))
            || Regex("requirements[-_.].*\\.txt").matches(name)
            || path.startsWith("supabase/") && name == "config.toml"
    }

    fun parse(path: String, text: String): List<InventoryItem> = when {
        path.endsWith("build.gradle") || path.endsWith("build.gradle.kts") ||
            path.endsWith("settings.gradle") || path.endsWith("settings.gradle.kts") -> parseGradle(path, text)
        path.endsWith(".yml") || path.endsWith(".yaml") -> parseActions(path, text)
        path.endsWith("package.json") -> parsePackageJson(path, text)
        path.substringAfterLast('/').startsWith("requirements") && path.endsWith(".txt") -> parseRequirements(path, text)
        path.endsWith("Dockerfile") -> parseDocker(path, text)
        else -> emptyList()
    }

    fun snapshot(fileContents: Map<String, String>, allPaths: Collection<String>): InventorySnapshot {
        val items = fileContents.entries.flatMap { (path, text) -> parse(path, text) }
            .distinctBy { "${it.kind}|${it.name}|${it.version}|${it.path}" }
        val services = detectServices(allPaths, fileContents)
        val canonical = buildString {
            items.sortedBy { "${it.kind}|${it.name}|${it.version}|${it.path}" }.forEach {
                append(it.kind).append('|').append(it.name).append('|').append(it.version).append('|').append(it.path).append('\n')
            }
            append("services=").append(services.sorted().joinToString(","))
        }
        return InventorySnapshot(items, services, sha256(canonical))
    }

    private fun parseGradle(path: String, text: String): List<InventoryItem> = buildList {
        gradlePlugin.findAll(text).forEach { match ->
            add(InventoryItem("gradle-plugin", match.groupValues[1], match.groupValues.getOrElse(2) { "" }, path))
        }
        gradleDependency.findAll(text).forEach { match ->
            add(InventoryItem("gradle-dependency", "${match.groupValues[1]}:${match.groupValues[2]}", match.groupValues[3], path))
        }
    }

    private fun parseActions(path: String, text: String): List<InventoryItem> = actionUse.findAll(text).map {
        InventoryItem("github-action", it.groupValues[1], it.groupValues[2], path)
    }.toList()

    private fun parsePackageJson(path: String, text: String): List<InventoryItem> = runCatching {
        val root = JSONObject(text)
        buildList {
            listOf("dependencies", "devDependencies", "peerDependencies").forEach { section ->
                val obj = root.optJSONObject(section) ?: return@forEach
                obj.keys().forEach { name -> add(InventoryItem("npm-$section", name, obj.optString(name), path)) }
            }
        }
    }.getOrDefault(emptyList())

    private fun parseRequirements(path: String, text: String): List<InventoryItem> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith('#') && !it.startsWith('-') }
        .map { line ->
            val parts = line.split("==", ">=", "~=", "<=", "!=", limit = 2)
            InventoryItem("python-dependency", parts[0].trim(), parts.getOrElse(1) { "" }.trim(), path)
        }
        .toList()

    private fun parseDocker(path: String, text: String): List<InventoryItem> = dockerFrom.findAll(text).map {
        val image = it.groupValues[1]
        val name = image.substringBefore('@').substringBeforeLast(':', image)
        val version = image.substringAfterLast(':', "").takeIf { ':' in image.substringAfterLast('/') }.orEmpty()
        InventoryItem("docker-image", name, version, path)
    }.toList()

    private fun detectServices(paths: Collection<String>, contents: Map<String, String>): Set<String> = buildSet {
        val lowerPaths = paths.map { it.lowercase() }
        if (lowerPaths.any { it.endsWith("vercel.json") || it.contains("/.vercel/") }) add("Vercel")
        if (lowerPaths.any { it.endsWith("wrangler.toml") || it.endsWith("wrangler.json") || it.endsWith("wrangler.jsonc") }) add("Cloudflare")
        if (lowerPaths.any { it.startsWith("supabase/") || it.contains("/supabase/") }) add("Supabase")
        if (lowerPaths.any { it.endsWith("sentry.properties") }) add("Sentry")
        val joined = contents.values.joinToString("\n").lowercase()
        if ("posthog" in joined) add("PostHog")
        if ("sentry" in joined) add("Sentry")
        if ("supabase" in joined) add("Supabase")
        if ("cloudflare" in joined || "wrangler" in joined) add("Cloudflare")
        if ("vercel" in joined) add("Vercel")
        if (lowerPaths.any { it.startsWith(".github/workflows/") }) add("GitHub Actions")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
