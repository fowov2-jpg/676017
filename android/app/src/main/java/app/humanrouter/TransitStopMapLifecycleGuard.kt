package app.humanrouter

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import app.humanrouter.transit.NearbyTransitPlace
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.util.WeakHashMap

/**
 * Bridges the Activity lifecycle and MapLibre's asynchronous style lifecycle for typed stop markers.
 *
 * TransitStopMapControllerV3 is installed when MainActivity resumes, while MapLibre may still be
 * creating the map/style. Its original short startup retries can all finish before a slower device
 * exposes a Style, leaving the typed stop/station layer unbound until some unrelated camera event.
 * This guard watches the real Style and requests a clean controller rebind only when the typed
 * source/layer is actually missing.
 *
 * A rebind destroys and recreates the marker controller, so it must never run while the passenger
 * has an open stop/station sheet: doing that used to remove the sheet underneath the user's finger.
 * In that case the rebind is deferred until the sheet closes, preserving the visible interaction
 * while still repairing a late or replaced MapLibre style immediately afterwards.
 */
internal object TransitStopMapLifecycleGuard {
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
        private val root = activity.findViewById<FrameLayout>(R.id.root)
        private var destroyed = false
        private var observedStyle: Any? = null
        private var reboundAfterNearbyData = false
        private var pendingRebind = false

        private val poll = object : Runnable {
            override fun run() {
                if (destroyed || activity.isFinishing || activity.isDestroyed) return

                val currentMap = readField<MapLibreMap>(activity, "map")
                val currentStyle = currentMap?.style
                if (currentStyle == null) {
                    handler.postDelayed(this, MAP_READY_POLL_MS)
                    return
                }

                if (currentStyle !== observedStyle) {
                    observedStyle = currentStyle
                    reboundAfterNearbyData = false
                    // A new Style generation needs intervention only if our source/layer is absent.
                    // Avoid destroying a healthy controller just because MapLibre delivered another
                    // lifecycle callback for the same visible map state.
                    pendingRebind = !hasTypedLayer(currentStyle)
                }

                val nearby = readField<List<NearbyTransitPlace>>(activity, "lastNearby").orEmpty()
                val markerCount = TransitStopMapControllerV3.markerCountForQa(activity)
                if (nearby.isNotEmpty() && markerCount == 0 && !reboundAfterNearbyData) {
                    pendingRebind = true
                }

                if (pendingRebind) {
                    if (stopSheetOpen()) {
                        // Preserve the user's current stop interaction. Re-check shortly and repair
                        // the map after the sheet is closed instead of deleting visible UI state.
                        handler.postDelayed(this, SHEET_OPEN_POLL_MS)
                        return
                    }
                    pendingRebind = false
                    reboundAfterNearbyData = nearby.isNotEmpty()
                    rebindController()
                    handler.postDelayed(this, DATA_SETTLE_POLL_MS)
                    return
                }

                // Keep a cheap style-generation watch while the Activity lives. This covers an
                // in-place style reload without continuously rebuilding an already healthy layer.
                handler.postDelayed(this, STYLE_WATCH_MS)
            }
        }

        init {
            handler.post(poll)
        }

        fun destroy() {
            destroyed = true
            handler.removeCallbacks(poll)
        }

        private fun stopSheetOpen(): Boolean =
            root.findViewWithTag<View>(STOP_SHEET_TAG) != null

        private fun hasTypedLayer(style: Style): Boolean =
            style.getSource(TYPED_SOURCE_ID) != null && style.getLayer(TYPED_LAYER_ID) != null

        private fun rebindController() {
            if (destroyed) return
            TransitStopMapControllerV3.destroy(activity)
            TransitStopMapControllerV3.install(activity)
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> readField(target: Any, name: String): T? = runCatching {
            var type: Class<*>? = target.javaClass
            while (type != null) {
                val field = runCatching { type.getDeclaredField(name) }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    return@runCatching field.get(target) as? T
                }
                type = type.superclass
            }
            null
        }.getOrNull()
    }

    private const val TYPED_SOURCE_ID = "vh-transit-symbol-source"
    private const val TYPED_LAYER_ID = "vh-transit-symbol-layer"
    private const val STOP_SHEET_TAG = "vh_transit_stop_sheet"
    private const val MAP_READY_POLL_MS = 120L
    private const val DATA_SETTLE_POLL_MS = 260L
    private const val SHEET_OPEN_POLL_MS = 120L
    private const val STYLE_WATCH_MS = 1_000L
}
