package app.humanrouter

import android.os.Handler
import android.os.Looper
import app.humanrouter.transit.NearbyTransitPlace
import org.maplibre.android.maps.MapLibreMap
import java.util.WeakHashMap

/**
 * Bridges the Activity lifecycle and MapLibre's asynchronous style lifecycle for typed stop markers.
 *
 * TransitStopMapControllerV3 is installed when MainActivity resumes, while MapLibre may still be
 * creating the map/style. Its original short startup retries can all finish before a slower device
 * exposes a Style, leaving the typed stop/station layer unbound until some unrelated camera event.
 * This guard waits for the real Style object and performs one clean controller rebind for each style
 * generation. If nearby data arrives just after the style, it performs one additional data-aware
 * rebind so the first visible map state cannot miss its transport markers.
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
        private var destroyed = false
        private var observedStyle: Any? = null
        private var reboundAfterNearbyData = false

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
                    rebindController()
                    handler.postDelayed(this, DATA_SETTLE_POLL_MS)
                    return
                }

                val nearby = readField<List<NearbyTransitPlace>>(activity, "lastNearby").orEmpty()
                val markerCount = TransitStopMapControllerV3.markerCountForQa(activity)
                if (nearby.isNotEmpty() && markerCount == 0 && !reboundAfterNearbyData) {
                    reboundAfterNearbyData = true
                    rebindController()
                }

                // Keep a very cheap style-generation watch while the Activity lives. This also
                // covers a future in-place style reload without continuously rebuilding the layer.
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

    private const val MAP_READY_POLL_MS = 120L
    private const val DATA_SETTLE_POLL_MS = 260L
    private const val STYLE_WATCH_MS = 1_000L
}
