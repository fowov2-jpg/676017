package app.humanrouter

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
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
 * window focus. Observe the real search visibility transition and enforce the collapsed state over
 * several UI frames so late IME transactions cannot win, including landscape/extract-mode devices.
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
        private var remainingCollapseGuards = 0

        private val ensureCollapsedImeState = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) return
                if (expandedSearch.visibility == View.VISIBLE) {
                    remainingCollapseGuards = 0
                    return
                }

                hideImeAndReturnAppFocus()
                remainingCollapseGuards -= 1
                if (remainingCollapseGuards > 0) {
                    handler.postDelayed(this, IME_RECHECK_MS)
                }
            }
        }

        private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val expandedNow = expandedSearch.visibility == View.VISIBLE
            if (wasExpanded && !expandedNow) {
                beginCollapsedImeGuard()
            } else if (!wasExpanded && expandedNow) {
                handler.removeCallbacks(ensureCollapsedImeState)
                remainingCollapseGuards = 0
            }
            wasExpanded = expandedNow
        }

        init {
            // Landscape keyboards are allowed to use a fullscreen extract editor unless the app
            // explicitly opts out. Search is a map overlay and must remain in-place on every form
            // factor, so keep the normal keyboard without giving it a separate fullscreen window.
            fromField.imeOptions = fromField.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            toField.imeOptions = toField.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            root.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        }

        fun destroy() {
            handler.removeCallbacks(ensureCollapsedImeState)
            remainingCollapseGuards = 0
            if (root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
            }
        }

        private fun beginCollapsedImeGuard() {
            handler.removeCallbacks(ensureCollapsedImeState)
            remainingCollapseGuards = IME_RECHECK_COUNT
            // Enforce immediately, then over subsequent frames. A queued showSoftInput() from the
            // opening frame can otherwise run after the first hideSoftInputFromWindow() call.
            hideImeAndReturnAppFocus()
            handler.postDelayed(ensureCollapsedImeState, IME_RECHECK_MS)
        }

        private fun hideImeAndReturnAppFocus() {
            fromField.clearFocus()
            toField.clearFocus()
            WindowCompat.getInsetsController(activity.window, root).hide(WindowInsetsCompat.Type.ime())
            val token = activity.window.decorView.windowToken ?: root.windowToken
            token?.let { inputMethodManager.hideSoftInputFromWindow(it, 0) }

            // Clearing the EditText focus is not enough on some landscape IMEs: the input window can
            // stay the focused window even after it starts hiding. Give focus to an app-owned view so
            // keyboard dismissal deterministically returns interaction to MainActivity.
            root.isFocusableInTouchMode = true
            root.requestFocus()
            activity.window.decorView.isFocusableInTouchMode = true
            activity.window.decorView.requestFocus()
        }
    }

    private const val IME_RECHECK_COUNT = 8
    private const val IME_RECHECK_MS = 120L
}
