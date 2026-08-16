package app.humanrouter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import app.humanrouter.routing.LastPlanStore
import app.humanrouter.routing.TransportMode
import java.time.Instant
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Event-driven final tuning for the approved reference proportions. */
internal object ReferenceVisualTuning {
    private data class Listeners(
        val route: View.OnLayoutChangeListener,
        val settings: View.OnLayoutChangeListener
    )

    private val installed = WeakHashMap<MainActivity, Listeners>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (installed.containsKey(activity)) return
        val routeSheet = activity.findViewById<View>(R.id.routeResultsContainer)
        val settingsScrim = activity.findViewById<View>(R.id.settingsScrim)

        val routeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            tuneRouteChrome(activity)
            tuneActiveTripBadge(activity)
        }
        val settingsListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            tuneSettingsSheet(activity)
        }
        installed[activity] = Listeners(routeListener, settingsListener)
        routeSheet.addOnLayoutChangeListener(routeListener)
        settingsScrim.addOnLayoutChangeListener(settingsListener)

        activity.window.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                installed.remove(activity)?.let { listeners ->
                    routeSheet.removeOnLayoutChangeListener(listeners.route)
                    settingsScrim.removeOnLayoutChangeListener(listeners.settings)
                }
                v.removeOnAttachStateChangeListener(this)
            }
        })

        tune(activity)
        activity.window.decorView.postDelayed({ tune(activity) }, 220L)
        activity.window.decorView.postDelayed({ tune(activity) }, 720L)
    }

    private fun tune(activity: MainActivity) {
        tuneSettingsSheet(activity)
        tuneRouteChrome(activity)
        tuneActiveTripBadge(activity)
    }

    private fun tuneSettingsSheet(activity: MainActivity) {
        val settingsPanel = activity.findViewById<ViewGroup>(R.id.settingsPanel)
        val scroll = settingsPanel.parent as? ScrollView ?: return
        val width = activity.resources.displayMetrics.widthPixels
        val targetWidth = (width * 0.42f).roundToInt()
        val params = scroll.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.width != targetWidth || params.gravity != Gravity.END) {
            params.width = targetWidth
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.END
            params.leftMargin = 0
            scroll.layoutParams = params
        }
        settingsPanel.setPadding(dp(activity, 18), settingsPanel.paddingTop, dp(activity, 16), settingsPanel.paddingBottom)
        intArrayOf(
            R.id.showStopsSwitch,
            R.id.showTransportSwitch,
            R.id.darkThemeSwitch,
            R.id.lessWalkingSwitch,
            R.id.avoidTransfersSwitch
        ).forEach { id ->
            activity.findViewById<SwitchCompat>(id).apply {
                textSize = 12.5f
                minimumHeight = dp(activity, 72)
            }
        }
    }

    private fun tuneRouteChrome(activity: MainActivity) {
        val sheet = activity.findViewById<View>(R.id.routeResultsContainer)
        val filters = activity.findViewById<HorizontalScrollView>(R.id.routeFiltersScroll)
        val primaryAction = activity.findViewById<Button>(R.id.routePrimaryAction)
        val activeTrip = primaryAction.visibility == View.VISIBLE &&
            primaryAction.text?.toString()?.contains("Завершить", ignoreCase = true) == true
        val routeOptions = sheet.visibility == View.VISIBLE && filters.visibility == View.VISIBLE && !activeTrip
        if (!routeOptions) return

        activity.findViewById<View>(R.id.searchPanel).visibility = View.GONE
        activity.findViewById<View>(R.id.quickActions).visibility = View.GONE
        activity.findViewById<View>(R.id.locationButton).visibility = View.GONE
        activity.findViewById<View>(R.id.settingsButton).visibility = View.GONE
        activity.findViewById<View>(R.id.bottomNav).visibility = View.GONE
    }

    private fun tuneActiveTripBadge(activity: MainActivity) {
        val root = activity.findViewById<FrameLayout>(R.id.root)
        val top = root.findViewWithTag<ViewGroup>("reference_active_trip_top") ?: return
        val route = LastPlanStore.seed?.route ?: return
        val now = Instant.now().epochSecond
        val leg = route.legs.firstOrNull { it.mode != TransportMode.WALK && now < it.arrivalEpochSec }
            ?: route.legs.firstOrNull { it.mode != TransportMode.WALK }
            ?: return
        val line = leg.lineName?.takeIf(String::isNotBlank) ?: return
        descendants(top)
            .filterIsInstance<TextView>()
            .firstOrNull { it.text?.toString() == line }
            ?.apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(iconForMode(leg.mode), 0, 0, 0)
                compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
                compoundDrawablePadding = dp(activity, 5)
            }
    }

    private fun iconForMode(mode: TransportMode): Int = when (mode) {
        TransportMode.BUS -> R.drawable.ic_bus
        TransportMode.TRAM -> R.drawable.ic_tram
        TransportMode.METRO, TransportMode.MCC, TransportMode.MCD -> R.drawable.ic_metro
        TransportMode.TRAIN -> R.drawable.ic_transport
        TransportMode.WALK -> R.drawable.ic_routes
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private fun dp(activity: MainActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
