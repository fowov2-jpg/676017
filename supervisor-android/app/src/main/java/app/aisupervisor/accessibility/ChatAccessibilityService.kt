package app.aisupervisor.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.aisupervisor.data.SupervisorDb
import app.aisupervisor.model.MonitorStatus
import java.security.MessageDigest

class ChatAccessibilityService : AccessibilityService() {
    private val state by lazy { getSharedPreferences("supervisor_state", MODE_PRIVATE) }
    private val db by lazy { SupervisorDb(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        state.edit().putBoolean("accessibility_connected", true).apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != CHATGPT_PACKAGE) return
        val root = rootInActiveWindow ?: return
        val visible = collectVisibleText(root)
        val signature = sha256(visible)
        val previous = state.getString("chat_signature", null)
        val now = System.currentTimeMillis()
        state.edit()
            .putBoolean("chatgpt_visible", true)
            .putLong("chat_last_event_ms", now)
            .apply()

        if (visible.isNotBlank() && previous != signature) {
            state.edit()
                .putString("chat_signature", signature)
                .putLong("chat_last_change_ms", now)
                .apply()
        }

        detectConnectionProblem(visible)?.let { problem ->
            val projectId = state.getLong("active_project_id", -1L)
            if (projectId > 0) {
                db.addEventIfChanged(
                    projectId = projectId,
                    source = "ChatGPT",
                    status = MonitorStatus.STALLED,
                    title = "ChatGPT требует вмешательства",
                    detail = problem,
                    fingerprint = "chat-error:${sha256(problem)}"
                )
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        state.edit().putBoolean("accessibility_connected", false).putBoolean("chatgpt_visible", false).apply()
        super.onDestroy()
    }

    private fun tryPing(message: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != CHATGPT_PACKAGE) return false
        val editable = findFirst(root) { it.isEditable && it.isEnabled } ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        if (!editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false

        if (Build.VERSION.SDK_INT >= 30) {
            val sent = editable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (sent) return true
        }

        val sendButton = findFirst(root) { node ->
            if (!node.isClickable || !node.isEnabled) return@findFirst false
            val label = "${node.text.orEmpty()} ${node.contentDescription.orEmpty()}".lowercase()
            label.contains("send") || label.contains("отправ") || label.contains("submit")
        }
        return sendButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun collectVisibleText(root: AccessibilityNodeInfo): String {
        val parts = ArrayList<String>(256)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 700) {
            val node = stack.removeLast()
            visited++
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(parts::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(parts::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let(stack::add)
        }
        return parts.joinToString("\n").takeLast(12_000)
    }

    private fun detectConnectionProblem(text: String): String? {
        val lower = text.lowercase()
        val patterns = listOf(
            "потеряно соединение",
            "пропало соединение",
            "ошибка сети",
            "повторить попытку",
            "требуется дополнительная проверка",
            "connection lost",
            "network error",
            "try again",
            "additional verification",
            "verify you are human"
        )
        val hit = patterns.firstOrNull { lower.contains(it) } ?: return null
        val lines = text.lineSequence().filter { it.lowercase().contains(hit) }.take(3).joinToString(" · ")
        return lines.ifBlank { hit }
    }

    private fun findFirst(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 700) {
            val node = stack.removeLast()
            visited++
            if (predicate(node)) return node
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::add)
        }
        return null
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        @Volatile private var instance: ChatAccessibilityService? = null

        fun requestPing(message: String): Boolean = runCatching { instance?.tryPing(message) == true }.getOrDefault(false)
        fun isConnected(): Boolean = instance != null
    }
}
