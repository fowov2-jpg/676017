package app.humanrouter

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.WeakHashMap

/**
 * Keeps the V2 journey strip authoritative while legacy route visuals are still present.
 *
 * Changes are event-driven and converge after a finite layout pass: the guard only mutates when
 * an actual legacy artefact is present, so Espresso and the main thread can return to idle.
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

        private val rootLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            if (needsStructuralChange()) enforceSoon()
        }

        private val panelLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!destroyed && hasHiddenLegacyActiveStatus()) enforceSoon()
        }

        private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (!destroyed && hasHiddenLegacyActiveStatus()) {
                // ReferenceProductUiV2 hides the old title late in its composition pass. Clear the
                // stale semantic text synchronously before the frame becomes observable so the
                // invisible legacy label cannot duplicate the authoritative V2 "В пути" title.
                suppressHiddenLegacyActiveStatus()
            }
            true
        }

        init {
            root.viewTreeObserver.addOnGlobalLayoutListener(rootLayoutListener)
            root.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            panel.addOnLayoutChangeListener(panelLayoutListener)
            enforceSoon()
        }

        fun destroy() {
            destroyed = true
            if (root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnGlobalLayoutListener(rootLayoutListener)
                root.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            }
            panel.removeOnLayoutChangeListener(panelLayoutListener)
        }

        fun enforceSoon() {
            if (posted || destroyed) return
            posted = true
            root.post {
                posted = false
                if (!destroyed && !activity.isFinishing && !activity.isDestroyed) enforceNow()
            }
        }

        private fun needsStructuralChange(): Boolean {
            if (hasHiddenLegacyActiveStatus()) return true
            return descendants(panel).filterIsInstance<HorizontalScrollView>().any { scroll ->
                when (scroll.contentDescription?.toString().orEmpty()) {
                    V2_DESCRIPTION -> scroll.parent === panel ||
                        scroll.visibility != View.VISIBLE ||
                        hasDuplicateSecondary(scroll)
                    LEGACY_DESCRIPTION -> scroll.visibility != View.GONE
                    else -> false
                }
            }
        }

        private fun enforceNow() {
            suppressHiddenLegacyActiveStatus()
            val v2 = descendants(panel).filterIsInstance<HorizontalScrollView>()
                .firstOrNull { it.contentDescription?.toString() == V2_DESCRIPTION }
            if (v2 != null) {
                isolateV2(v2)
                if (v2.visibility != View.VISIBLE) v2.visibility = View.VISIBLE
                suppressDuplicateSecondaryLabels(v2)
            }
            descendants(panel).filterIsInstance<HorizontalScrollView>().forEach { scroll ->
                if (scroll.contentDescription?.toString() == LEGACY_DESCRIPTION && scroll.visibility != View.GONE) {
                    scroll.visibility = View.GONE
                }
            }
        }

        private fun hasHiddenLegacyActiveStatus(): Boolean = descendants(panel)
            .filterIsInstance<TextView>()
            .any { view -> view.visibility != View.VISIBLE && view.text?.toString() == ACTIVE_STATUS_TEXT }

        private fun suppressHiddenLegacyActiveStatus() {
            descendants(panel)
                .filterIsInstance<TextView>()
                .filter { view -> view.visibility != View.VISIBLE && view.text?.toString() == ACTIVE_STATUS_TEXT }
                .forEach { view ->
                    view.text = ""
                    view.contentDescription = null
                }
        }

        private fun isolateV2(scroll: HorizontalScrollView) {
            if (scroll.parent !== panel) return
            val index = panel.indexOfChild(scroll)
            if (index < 0) return

            val transition = panel.layoutTransition
            panel.layoutTransition = null
            try {
                panel.removeViewAt(index)
                if (scroll.parent != null) return

                val wrapper = FrameLayout(activity).apply {
                    tag = V2_WRAPPER_TAG
                    clipChildren = false
                    clipToPadding = false
                }
                panel.addView(
                    wrapper,
                    index.coerceAtMost(panel.childCount),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                wrapper.addView(
                    scroll,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            } finally {
                panel.layoutTransition = transition
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
    private const val ACTIVE_STATUS_TEXT = "В пути"
    private const val V2_WRAPPER_TAG = "vh-transit-strip-v2-wrapper"
}
