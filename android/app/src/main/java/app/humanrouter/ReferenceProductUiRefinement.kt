package app.humanrouter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.humanrouter.routing.LastPlanStore
import java.util.WeakHashMap

/** Small deterministic refinements for dynamically rebuilt MainActivity content. */
internal object ReferenceProductUiRefinement {
    private val installed = WeakHashMap<MainActivity, View.OnLayoutChangeListener>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (installed.containsKey(activity)) return
        val root = activity.findViewById<FrameLayout>(R.id.root)
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            refine(activity)
        }
        installed[activity] = listener
        root.addOnLayoutChangeListener(listener)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                installed.remove(activity)?.let(root::removeOnLayoutChangeListener)
                root.removeOnAttachStateChangeListener(this)
            }
        })
        root.post { refine(activity) }
    }

    private fun refine(activity: MainActivity) {
        removeLegacyNearbyDivider(activity)
        updateEndpointNames(activity)
        refineActiveTrip(activity)
    }

    private fun removeLegacyNearbyDivider(activity: MainActivity) {
        val list = activity.findViewById<LinearLayout>(R.id.nearbyList)
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index) as? ViewGroup ?: continue
            val bad = row.findViewWithTag<View>("reference_nearby_divider")
            if (bad != null && bad.parent === row) row.removeView(bad)
        }
    }

    private fun updateEndpointNames(activity: MainActivity) {
        val sheet = activity.findViewById<LinearLayout>(R.id.routeResultsContainer)
        val endpointCard = sheet.findViewWithTag<ViewGroup>("reference_route_endpoints") ?: return
        val labels = descendants(endpointCard).filterIsInstance<TextView>().toList()
        if (labels.size < 2) return
        val from = activity.findViewById<EditText>(R.id.fromField).text?.toString().orEmpty()
            .substringBefore(',').trim()
        val to = activity.findViewById<EditText>(R.id.toField).text?.toString().orEmpty()
            .substringBefore(',').trim()
        if (from.isNotBlank()) labels[0].text = from
        if (to.isNotBlank()) labels[1].text = to
    }

    private fun refineActiveTrip(activity: MainActivity) {
        val root = activity.findViewById<FrameLayout>(R.id.root)
        val top = root.findViewWithTag<ViewGroup>("reference_active_trip_top") ?: return

        // The reference top card is the single primary trip title. Hide the older duplicate title
        // that MainActivity keeps inside the detail sheet for backwards-compatible data rendering.
        val routePanel = activity.findViewById<LinearLayout>(R.id.routeResultsPanel)
        descendants(routePanel)
            .filterIsInstance<TextView>()
            .filter { it.text?.toString() == "В пути" }
            .forEach { it.visibility = View.GONE }

        val route = LastPlanStore.seed?.route ?: return
        val now = java.time.Instant.now().epochSecond
        val transit = route.legs.firstOrNull { it.mode != app.humanrouter.routing.TransportMode.WALK && now < it.arrivalEpochSec }
            ?: route.legs.firstOrNull { it.mode != app.humanrouter.routing.TransportMode.WALK }
            ?: return
        val line = transit.lineName?.takeIf(String::isNotBlank) ?: return
        descendants(top)
            .filterIsInstance<TextView>()
            .firstOrNull { it.text?.toString() == line }
            ?.apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_transport, 0, 0, 0)
                compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
                compoundDrawablePadding = dp(activity, 6)
            }
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
        }
    }

    private fun dp(activity: MainActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
