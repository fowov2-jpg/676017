package app.humanrouter

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.WeakHashMap

/**
 * Keeps the keyboard lifecycle consistent with the expanded search surface.
 *
 * EditText.showSoftInput() is asynchronous. If the user closes search immediately after it opens,
 * the queued IME request can arrive after MainActivity has already hidden the search and called
 * hideSoftInputFromWindow(), leaving an invisible field focused and the Activity without stable
 * window focus. Observe the real search visibility transition and enforce the collapsed state after
 * the queued IME transaction has had a chance to run.
 */
internal object SearchImeLifecycleGuard {
    private val guards = WeakHashMap<MainActivity, Guard>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (guards.containsKey(activity)) return
        guards[activity] = Guard(activity)
    }

    @Synchronized
    fun destroy(activity: MainActivity) {
        guards.remove(activity)?.destroy()
    }

    private class Guard(private val activity: MainActivity) {
        private val handler = Handler(Looper.getMainLooper())
        private val root = activity.findViewById<View>(R.id.root)
        private val expandedSearch = activity.findViewById<View>(R.id.expandedSearchContent)
        private val fromField = activity.findViewById<EditText>(R.id.fromField)
        private val toField = activity.findViewById<EditText>(R.id.toField)
        private val inputMethodManager = activity.getSystemService(InputMethodManager::class.java)
        private var wasExpanded = expandedSearch.visibility == View.VISIBLE

        private val ensureCollapsedImeState = Runnable {
            if (expandedSearch.visibility != View.VISIBLE) hideImeAndClearSearchFocus()
        }

        private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val expandedNow = expandedSearch.visibility == View.VISIBLE
            if (wasExpanded && !expandedNow) {
                // Hide now, then once more after any showSoftInput() posted by the opening frame.
                hideImeAndClearSearchFocus()
                handler.removeCallbacks(ensureCollapsedImeState)
                handler.postDelayed(ensureCollapsedImeState, IME_SETTLE_GUARD_MS)
            }
            wasExpanded = expandedNow
        }

        init {
            root.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        }

        fun destroy() {
            handler.removeCallbacks(ensureCollapsedImeState)
            if (root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
            }
        }

        private fun hideImeAndClearSearchFocus() {
            fromField.clearFocus()
            toField.clearFocus()
            WindowCompat.getInsetsController(activity.window, root).hide(WindowInsetsCompat.Type.ime())
            root.windowToken?.let { token -> inputMethodManager.hideSoftInputFromWindow(token, 0) }
        }
    }

    private const val IME_SETTLE_GUARD_MS = 180L
}
