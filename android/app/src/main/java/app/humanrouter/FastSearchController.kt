package app.humanrouter

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import app.humanrouter.routing.GeoPoint
import app.humanrouter.search.FastAddressResolver
import app.humanrouter.search.SearchPlace
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Makes the live A/B fields use the same resilient low-latency address resolver as the route button.
 *
 * MainActivity's original watcher only sends digit-containing queries to Photon. That means a normal
 * Moscow input such as "Шумилова 13" can show an empty suggestion list whenever Photon does not
 * understand the abbreviated form, even though the Android device geocoder can resolve it. This
 * controller invalidates that legacy request and renders results from FastAddressResolver instead.
 */
internal object FastSearchController {
    private const val DEBOUNCE_MS = 90L
    private const val SEARCH_BUDGET_MS = 1_550L
    private val installed = WeakHashMap<MainActivity, Boolean>()
    private val serial = AtomicInteger()
    private val io = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())

    @Synchronized
    fun install(activity: MainActivity) {
        if (installed.put(activity, true) == true) return
        bind(activity, activity.findViewById(R.id.fromField), isOrigin = true)
        bind(activity, activity.findViewById(R.id.toField), isOrigin = false)
    }

    private fun bind(activity: MainActivity, field: EditText, isOrigin: Boolean) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                if (query.length < 3 || isCurrentLocationText(query)) return

                val selected = readField<SearchPlace>(activity, if (isOrigin) "selectedFrom" else "selectedTo")
                if (selected != null && query.contains(selected.title, ignoreCase = true)) return

                // MainActivity's original watcher runs first. Advance its token so its slower Photon-
                // only response cannot overwrite the combined fast result below.
                invalidateLegacySearch(activity)
                val token = serial.incrementAndGet()
                handler.postDelayed({
                    if (token != serial.get() || !field.hasFocus() || field.text.toString().trim() != query) {
                        return@postDelayed
                    }
                    val focus = readField<GeoPoint>(activity, "currentLocation")
                    io.execute {
                        val results = runCatching {
                            FastAddressResolver.search(
                                context = activity,
                                query = query,
                                focus = focus,
                                limit = 6,
                                budgetMs = SEARCH_BUDGET_MS
                            )
                        }.getOrDefault(emptyList())

                        activity.runOnUiThread {
                            if (token != serial.get() || !field.hasFocus() || field.text.toString().trim() != query) {
                                return@runOnUiThread
                            }
                            invokeRenderSuggestions(activity, field, isOrigin, results)
                            attachMapPreviewHooks(activity, isOrigin)
                        }
                    }
                }, DEBOUNCE_MS)
            }
        })
    }

    private fun invokeRenderSuggestions(
        activity: MainActivity,
        field: EditText,
        isOrigin: Boolean,
        results: List<SearchPlace>
    ) {
        runCatching {
            val method: Method = activity.javaClass.declaredMethods.firstOrNull {
                it.name == "renderSuggestions" && it.parameterCount == 3
            } ?: return
            method.isAccessible = true
            method.invoke(activity, field, isOrigin, results)
        }
    }

    private fun attachMapPreviewHooks(activity: MainActivity, isOrigin: Boolean) {
        val panel = activity.findViewById<LinearLayout>(R.id.suggestionsPanel)
        for (index in 0 until panel.childCount) {
            val child = panel.getChildAt(index)
            child.setOnTouchListener { _: View, event: MotionEvent ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    handler.postDelayed({ previewSelectedPoint(activity, isOrigin) }, 60L)
                }
                false
            }
        }
    }

    private fun previewSelectedPoint(activity: MainActivity, isOrigin: Boolean) {
        val place = readField<SearchPlace>(activity, if (isOrigin) "selectedFrom" else "selectedTo") ?: return
        val map = readField<MapLibreMap>(activity, "map")
        map?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(place.point.lat, place.point.lon), 16.4),
            280
        )

        val source = readField<GeoJsonSource>(
            activity,
            if (isOrigin) "routeOriginSource" else "routeDestinationSource"
        )
        source?.setGeoJson(
            FeatureCollection.fromFeatures(
                arrayOf(Feature.fromGeometry(Point.fromLngLat(place.point.lon, place.point.lat)))
            )
        )
    }

    private fun invalidateLegacySearch(activity: MainActivity) {
        runCatching {
            val field = activity.javaClass.getDeclaredField("searchSerial")
            field.isAccessible = true
            field.setInt(activity, field.getInt(activity) + 1)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(activity: MainActivity, name: String): T? = runCatching {
        val field = activity.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.get(activity) as? T
    }.getOrNull()

    private fun isCurrentLocationText(text: String): Boolean =
        text.equals("Моё местоположение", ignoreCase = true) ||
            text.equals("Мое местоположение", ignoreCase = true) ||
            text.equals("Текущее местоположение", ignoreCase = true)
}
