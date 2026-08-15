package app.humanrouter

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.WeakHashMap

/**
 * Keeps the V2 journey strip authoritative while the legacy visual decorator is still present.
 *
 * The legacy decorator was written before V2 and hides sibling HorizontalScrollViews in active-trip
 * mode. Until that decorator is removed completely, this guard resolves the race after layout:
 * legacy strips are hidden and the V2 strip is made visible again. It also removes redundant
 * secondary labels such as "м2 / м2" or "Метро 6 / 6" so route tokens stay compact and legible.
 */
internal object TransitJourneyVisibilityGuard {
    private val controllers = WeakHashMap<MainActivity, Controller>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (controllers.containsKey(activity)) return
        controllers[activity] = Controller(activity)
    }

    @Synchronized
    fun enforce(activity: MainActivity) {
        controllers[activity]?.enforceSoon()
    }

    @Synchronized
    fun destroy(activity: MainActivity) {
        controllers.remove(activity)?.destroy()
    }

    private class Controller(private val activity: MainActivity) {
        private val root = activity.findViewById<ViewGroup>(R.id.root)
        private val panel = activity.findViewById<LinearLayout>(R.id.routeResultsPanel)
        private var posted = false
        private var destroyed = false

        private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            if (needsChange()) enforceSoon()
        }

        init {
            root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            enforceSoon()
        }

        fun destroy() {
            destroyed = true
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        }

        fun enforceSoon() {
            if (posted || destroyed) return
            posted = true
            root.post {
                posted = false
                if (!destroyed && !activity.isFinishing && !activity.isDestroyed) enforceNow()
            }
        }

        private fun needsChange(): Boolean {
            var needs = false
            descendants(panel).filterIsInstance<HorizontalScrollView>().forEach { scroll ->
                when (scroll.contentDescription?.toString().orEmpty()) {
                    V2_DESCRIPTION -> {
                        if (scroll.visibility != View.VISIBLE || hasDuplicateSecondary(scroll)) needs = true
                    }
                    LEGACY_DESCRIPTION -> if (scroll.visibility != View.GONE) needs = true
                }
            }
            return needs
        }

        private fun enforceNow() {
            descendants(panel).filterIsInstance<HorizontalScrollView>().forEach { scroll ->
                when (scroll.contentDescription?.toString().orEmpty()) {
                    V2_DESCRIPTION -> {
                        if (scroll.visibility != View.VISIBLE) scroll.visibility = View.VISIBLE
                        suppressDuplicateSecondaryLabels(scroll)
                    }
                    LEGACY_DESCRIPTION -> if (scroll.visibility != View.GONE) scroll.visibility = View.GONE
                }
            }
        }

        private fun hasDuplicateSecondary(root: View): Boolean =
            textColumns(root).any { column -> duplicatePair(column) != null }

        private fun suppressDuplicateSecondaryLabels(root: View) {
            textColumns(root).forEach { column ->
                duplicatePair(column)?.second?.apply {
                    text = ""
                    visibility = View.GONE
                }
            }
        }

        private fun textColumns(root: View): Sequence<LinearLayout> = descendants(root)
            .filterIsInstance<LinearLayout>()
            .filter { column ->
                column.childCount >= 2 &&
                    column.getChildAt(0) is TextView &&
                    column.getChildAt(1) is TextView
            }

        private fun duplicatePair(column: LinearLayout): Pair<TextView, TextView>? {
            val primary = column.getChildAt(0) as? TextView ?: return null
            val secondary = column.getChildAt(1) as? TextView ?: return null
            if (secondary.visibility == View.GONE || secondary.text.isNullOrBlank()) return null
            val first = normalize(primary.text?.toString().orEmpty())
            val second = normalize(secondary.text?.toString().orEmpty())
            if (first.isBlank() || second.isBlank()) return null
            val redundant = first == second ||
                (second.length <= 4 && first.endsWith(second)) ||
                (first.length <= 4 && second.endsWith(first))
            return if (redundant) primary to secondary else null
        }

        private fun normalize(value: String): String = value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^а-яa-z0-9]+"), "")

        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
            }
        }
    }

    internal const val V2_DESCRIPTION = "Этапы маршрута с линиями и переходами"
    private const val LEGACY_DESCRIPTION = "Схема транспорта маршрута"
}
