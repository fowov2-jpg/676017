package app.humanrouter

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.TransportMode
import app.humanrouter.search.SearchPlace
import app.humanrouter.transit.NearbyTransitPlace
import app.humanrouter.transit.TransitDirectionOption
import app.humanrouter.transit.TransitStopDirectionRepository
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Owns typed transport symbols and the compact stop/station action sheet.
 *
 * The legacy blue nearby circles remain as a fallback source for old UI code but are made
 * transparent once this controller attaches. Marker data comes from MainActivity's real
 * NearbyRepository result; no vehicle positions or stop metadata are fabricated in production.
 */
internal object TransitStopMapController {
    private val controllers = WeakHashMap<MainActivity, Controller>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (controllers.containsKey(activity)) return
        controllers[activity] = Controller(activity)
    }

    @Synchronized
    fun destroy(activity: MainActivity) {
        controllers.remove(activity)?.destroy()
    }

    /** Deterministic instrumentation hook; production interaction still comes from map taps. */
    internal fun openForQa(activity: MainActivity, placeId: String): Boolean =
        controllers[activity]?.openById(placeId) == true

    private class Controller(private val activity: MainActivity) {
        private val handler = Handler(Looper.getMainLooper())
        private val io = Executors.newSingleThreadExecutor()
        private val directionRepository = TransitStopDirectionRepository(activity)
        private val root = activity.findViewById<FrameLayout>(R.id.root)
        private val nearbyPanel = activity.findViewById<View>(R.id.nearbyPanel)
        private val bottomNav = activity.findViewById<View>(R.id.bottomNav)
        private val preferences = AppPreferences.prefs(activity)
        private val zoneId = ZoneId.of("Europe/Moscow")
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        private var map: MapLibreMap? = null
        private var source: GeoJsonSource? = null
        private var attachedStyle: Style? = null
        private var stopSheet: View? = null
        private var selectedStop: NearbyTransitPlace? = null
        private var nearbyWasVisible = false
        private var destroyed = false

        private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AppPreferences.KEY_SHOW_STOPS) handler.post(::renderMarkers)
        }

        private val mapClickListener = object : MapLibreMap.OnMapClickListener {
            override fun onMapClick(point: LatLng): Boolean {
                val activeMap = map ?: return false
                val screenPoint = activeMap.projection.toScreenLocation(point)
                val feature = activeMap.queryRenderedFeatures(screenPoint, arrayOf(MARKER_LAYER_ID)).firstOrNull()
                val id = feature?.getStringProperty(PROPERTY_STOP_ID)
                if (id.isNullOrBlank()) {
                    closeSheet()
                    return false
                }
                return openById(id)
            }
        }

        private val cameraIdleListener = MapLibreMap.OnCameraIdleListener {
            handler.removeCallbacks(refreshAfterNearbyRunnable)
            handler.postDelayed(refreshAfterNearbyRunnable, 760L)
        }

        private val refreshAfterNearbyRunnable = Runnable { renderMarkers() }

        init {
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
            attachSoon(0L)
            attachSoon(350L)
            attachSoon(1_100L)
        }

        fun destroy() {
            destroyed = true
            handler.removeCallbacksAndMessages(null)
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            map?.removeOnMapClickListener(mapClickListener)
            map?.removeOnCameraIdleListener(cameraIdleListener)
            closeSheet(restoreNearby = false)
            io.shutdownNow()
            map = null
            source = null
            attachedStyle = null
        }

        fun openById(placeId: String): Boolean {
            val place = nearbyPlaces().firstOrNull { it.id == placeId } ?: return false
            showStopSheet(place)
            return true
        }

        private fun attachSoon(delayMs: Long) {
            handler.postDelayed({
                if (destroyed) return@postDelayed
                val readyMap = readField<MapLibreMap>(activity, "map") ?: return@postDelayed
                val style = readyMap.style ?: return@postDelayed
                if (map !== readyMap) {
                    map?.removeOnMapClickListener(mapClickListener)
                    map?.removeOnCameraIdleListener(cameraIdleListener)
                    map = readyMap
                    readyMap.addOnMapClickListener(mapClickListener)
                    readyMap.addOnCameraIdleListener(cameraIdleListener)
                }
                ensureStyle(style)
                renderMarkers()
            }, delayMs)
        }

        private fun ensureStyle(style: Style) {
            if (attachedStyle === style && source != null) {
                hideLegacyDots(style)
                return
            }
            attachedStyle = style
            source = null

            markerModes.forEach { mode ->
                val imageId = imageId(mode)
                if (style.getImage(imageId) == null) style.addImage(imageId, markerBitmap(mode))
            }

            val existingSource = style.getSourceAs<GeoJsonSource>(MARKER_SOURCE_ID)
            source = existingSource ?: GeoJsonSource(MARKER_SOURCE_ID, emptyFeatures()).also(style::addSource)
            if (style.getLayer(MARKER_LAYER_ID) == null) {
                style.addLayer(
                    SymbolLayer(MARKER_LAYER_ID, MARKER_SOURCE_ID).withProperties(
                        PropertyFactory.iconImage(Expression.image(Expression.get(PROPERTY_ICON))),
                        PropertyFactory.iconAllowOverlap(false),
                        PropertyFactory.iconIgnorePlacement(false),
                        PropertyFactory.iconPadding(3f),
                        PropertyFactory.iconSize(
                            Expression.interpolate(
                                Expression.linear(),
                                Expression.zoom(),
                                Expression.stop(10, 0.64f),
                                Expression.stop(13, 0.82f),
                                Expression.stop(16, 1.0f)
                            )
                        )
                    ).also { it.minZoom = 10.2f }
                )
            }
            hideLegacyDots(style)
        }

        private fun hideLegacyDots(style: Style) {
            style.getLayer(LEGACY_NEARBY_LAYER_ID)?.setProperties(PropertyFactory.circleOpacity(0f))
        }

        private fun renderMarkers() {
            if (destroyed) return
            val activeMap = map ?: readField<MapLibreMap>(activity, "map") ?: return
            val style = activeMap.style ?: return
            if (attachedStyle !== style || source == null) ensureStyle(style)
            hideLegacyDots(style)
            val visible = preferences.getBoolean(AppPreferences.KEY_SHOW_STOPS, true)
            val places = if (visible) nearbyPlaces() else emptyList()
            val features = places.map { place ->
                Feature.fromGeometry(Point.fromLngLat(place.point.lon, place.point.lat)).apply {
                    addStringProperty(PROPERTY_STOP_ID, place.id)
                    addStringProperty(PROPERTY_STOP_NAME, place.name)
                    addStringProperty(PROPERTY_ICON, imageId(preferredMarkerMode(place.modes)))
                }
            }
            source?.setGeoJson(FeatureCollection.fromFeatures(features.toTypedArray()))
        }

        private fun showStopSheet(place: NearbyTransitPlace) {
            selectedStop = place
            closeSheet(restoreNearby = false)
            nearbyWasVisible = nearbyPanel.visibility == View.VISIBLE
            nearbyPanel.visibility = View.GONE

            val sheet = LinearLayout(activity).apply {
                tag = STOP_SHEET_TAG
                orientation = LinearLayout.VERTICAL
                elevation = dp(24).toFloat()
                setPadding(dp(18), dp(9), dp(18), dp(16))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(28).toFloat()
                    setColor(color(R.color.vh_surface_solid))
                    setStroke(dp(1), color(R.color.vh_border))
                }
            }
            sheet.addView(View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(2).toFloat()
                    setColor(color(R.color.vh_border))
                }
            }, LinearLayout.LayoutParams(dp(42), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(9)
            })

            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    tag = STOP_TITLE_TAG
                    text = place.name
                    textSize = 19f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.vh_text_primary))
                    maxLines = 2
                })
                addView(TextView(activity).apply {
                    text = "${modeSummary(place)} · ${formatDistance(place.distanceMeters)}"
                    textSize = 12f
                    setTextColor(color(R.color.vh_text_tertiary))
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(TextView(activity).apply {
                text = "×"
                textSize = 27f
                gravity = Gravity.CENTER
                contentDescription = "Закрыть карточку остановки"
                setTextColor(color(R.color.vh_text_secondary))
                setOnClickListener { closeSheet() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            sheet.addView(header)

            val platforms = platformGroup(place)
            if (platforms.size > 1) {
                sheet.addView(TextView(activity).apply {
                    text = "Площадки"
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.vh_text_tertiary))
                    setPadding(0, dp(5), 0, dp(4))
                })
                val platformRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    tag = PLATFORM_ROW_TAG
                }
                platforms.take(3).forEachIndexed { index, candidate ->
                    platformRow.addView(Button(activity).apply {
                        isAllCaps = false
                        text = if (candidate.id == place.id) "✓ ${index + 1}" else "Площадка ${index + 1}"
                        textSize = 12f
                        minHeight = 0
                        minimumHeight = dp(40)
                        background = ContextCompat.getDrawable(activity, R.drawable.bg_chip)
                        setOnClickListener { showStopSheet(candidate) }
                    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                        if (index > 0) leftMargin = dp(7)
                    })
                }
                sheet.addView(platformRow)
            }

            val directionsTitle = TextView(activity).apply {
                text = "Маршруты и направления"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.vh_text_tertiary))
                setPadding(0, dp(10), 0, dp(4))
            }
            sheet.addView(directionsTitle)
            val directions = LinearLayout(activity).apply {
                tag = DIRECTIONS_TAG
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = "Загружаем направления…"
                    textSize = 13f
                    setTextColor(color(R.color.vh_text_secondary))
                    setPadding(0, dp(5), 0, dp(7))
                })
            }
            sheet.addView(directions)

            val actions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, 0)
            }
            actions.addView(Button(activity).apply {
                tag = FROM_HERE_TAG
                text = "Отсюда"
                isAllCaps = false
                textSize = 15f
                minimumHeight = dp(50)
                background = ContextCompat.getDrawable(activity, R.drawable.bg_chip)
                setTextColor(color(R.color.vh_primary))
                setOnClickListener { selectAsOrigin(requireNotNull(selectedStop)) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) })
            actions.addView(Button(activity).apply {
                tag = TO_HERE_TAG
                text = "Сюда"
                isAllCaps = false
                textSize = 15f
                minimumHeight = dp(50)
                background = ContextCompat.getDrawable(activity, R.drawable.bg_primary)
                setTextColor(Color.WHITE)
                setOnClickListener { selectAsDestination(requireNotNull(selectedStop)) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(6) })
            sheet.addView(actions)

            val bottomClearance = if (bottomNav.visibility == View.VISIBLE) dp(86) else dp(18)
            root.addView(sheet, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(14)
                rightMargin = dp(14)
                bottomMargin = bottomClearance
            })
            stopSheet = sheet

            loadDirections(place, directions)
        }

        private fun loadDirections(place: NearbyTransitPlace, container: LinearLayout) {
            val stopId = place.id
            io.execute {
                val loaded = if (BuildConfig.DEBUG && stopId.startsWith("qa:")) {
                    qaDirections(place)
                } else {
                    directionRepository.directionsFor(place, Instant.now().epochSecond, 8)
                }
                activity.runOnUiThread {
                    if (destroyed || selectedStop?.id != stopId || stopSheet?.parent == null) return@runOnUiThread
                    renderDirections(container, place, loaded)
                }
            }
        }

        private fun renderDirections(
            container: LinearLayout,
            place: NearbyTransitPlace,
            directions: List<TransitDirectionOption>
        ) {
            container.removeAllViews()
            if (directions.isEmpty()) {
                container.addView(TextView(activity).apply {
                    text = buildString {
                        if (place.routeLabels.isNotEmpty()) append(place.routeLabels.take(6).joinToString(" · ")).append("\n")
                        append("Направления недоступны в установленной версии данных")
                    }
                    textSize = 13f
                    setTextColor(color(R.color.vh_text_secondary))
                    setPadding(0, dp(4), 0, dp(6))
                })
                return
            }
            directions.take(6).forEach { option ->
                container.addView(directionRow(option))
            }
        }

        private fun directionRow(option: TransitDirectionOption): View =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
                val badge = TextView(activity).apply {
                    text = option.routeLabel
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setTextColor(modeColor(option.mode))
                    background = ContextCompat.getDrawable(activity, R.drawable.bg_chip)
                    val icon = ContextCompat.getDrawable(activity, modeDrawable(option.mode))
                    setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
                    compoundDrawablePadding = dp(4)
                    setPadding(dp(8), 0, dp(8), 0)
                }
                addView(badge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, 0, 0)
                    addView(TextView(activity).apply {
                        text = buildString {
                            append("→ ").append(option.headsign)
                            option.radialHint?.let { append(" · ").append(it) }
                        }
                        textSize = 13f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(color(R.color.vh_text_primary))
                        maxLines = 1
                    })
                    addView(TextView(activity).apply {
                        text = option.nextDepartureEpochSec?.let { "Ближайшее: ${formatTime(it)}" }
                            ?: "Направление по установленным данным"
                        textSize = 11f
                        setTextColor(color(R.color.vh_text_tertiary))
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }

        private fun selectAsOrigin(place: NearbyTransitPlace) {
            val searchPlace = SearchPlace(place.name, stopSubtitle(place), place.point)
            setEndpoint("selectedFrom", R.id.fromField, searchPlace)
            closeSheet()
            invokeByName(activity, "expandSearch", true)
        }

        private fun selectAsDestination(place: NearbyTransitPlace) {
            val searchPlace = SearchPlace(place.name, stopSubtitle(place), place.point)
            setEndpoint("selectedTo", R.id.toField, searchPlace)
            activity.findViewById<TextView>(R.id.compactSearchButton).text = place.name
            closeSheet()
            activity.findViewById<Button>(R.id.routeButton).performClick()
        }

        private fun setEndpoint(fieldName: String, fieldId: Int, place: SearchPlace) {
            val field = activity.findViewById<EditText>(fieldId)
            val label = listOf(place.title, place.subtitle).filter(String::isNotBlank).distinct().joinToString(", ")
            runCatching {
                val method = activity.javaClass.declaredMethods.firstOrNull {
                    it.name == "setFieldText" && it.parameterCount == 2
                }
                method?.isAccessible = true
                method?.invoke(activity, field, label)
            }.onFailure { field.setText(label) }
            writeField(activity, fieldName, place)
        }

        private fun closeSheet(restoreNearby: Boolean = true) {
            val sheet = stopSheet
            if (sheet != null) root.removeView(sheet)
            stopSheet = null
            selectedStop = null
            if (restoreNearby && nearbyWasVisible) nearbyPanel.visibility = View.VISIBLE
            nearbyWasVisible = false
        }

        private fun platformGroup(place: NearbyTransitPlace): List<NearbyTransitPlace> {
            val normalized = normalize(place.name)
            val group = nearbyPlaces().filter { candidate ->
                normalize(candidate.name) == normalized &&
                    haversineMeters(candidate.point, place.point) <= PLATFORM_GROUP_METERS
            }
            return if (group.size > 1) group.sortedBy { it.distanceMeters } else listOf(place)
        }

        private fun qaDirections(place: NearbyTransitPlace): List<TransitDirectionOption> = when (place.id) {
            "qa:bus" -> listOf(
                TransitDirectionOption(TransportMode.BUS, "м2", "Лубянка", "в центр", Instant.now().epochSecond + 3 * 60),
                TransitDirectionOption(TransportMode.BUS, "м2", "Фили", "из центра", Instant.now().epochSecond + 7 * 60),
                TransitDirectionOption(TransportMode.BUS, "м3", "Серебряный бор", "из центра", Instant.now().epochSecond + 11 * 60)
            )
            "qa:metro" -> listOf(
                TransitDirectionOption(TransportMode.METRO, "1", "Бульвар Рокоссовского", null, null),
                TransitDirectionOption(TransportMode.METRO, "1", "Коммунарка", null, null)
            )
            "qa:tram" -> listOf(
                TransitDirectionOption(TransportMode.TRAM, "А", "Новоконная площадь", "из центра", Instant.now().epochSecond + 5 * 60)
            )
            "qa:d3" -> listOf(
                TransitDirectionOption(TransportMode.MCD, "D3", "Зеленоград-Крюково", "из центра", Instant.now().epochSecond + 8 * 60),
                TransitDirectionOption(TransportMode.MCD, "D3", "Ипподром", "в центр", Instant.now().epochSecond + 14 * 60)
            )
            else -> emptyList()
        }

        private fun nearbyPlaces(): List<NearbyTransitPlace> =
            readField<List<NearbyTransitPlace>>(activity, "lastNearby").orEmpty()

        private fun stopSubtitle(place: NearbyTransitPlace): String = buildString {
            append(modeSummary(place))
            append(if (place.modes.any { it == TransportMode.BUS || it == TransportMode.TRAM }) " · остановка" else " · станция")
        }

        private fun modeSummary(place: NearbyTransitPlace): String = place.modes
            .sortedBy(TransportMode::ordinal)
            .joinToString(" · ") { modeLabel(it) }

        private fun preferredMarkerMode(modes: Set<TransportMode>): TransportMode = when {
            TransportMode.METRO in modes -> TransportMode.METRO
            TransportMode.MCC in modes -> TransportMode.MCC
            TransportMode.MCD in modes -> TransportMode.MCD
            TransportMode.TRAM in modes -> TransportMode.TRAM
            TransportMode.BUS in modes -> TransportMode.BUS
            TransportMode.TRAIN in modes -> TransportMode.TRAIN
            else -> TransportMode.BUS
        }

        private fun markerBitmap(mode: TransportMode): Bitmap {
            val size = dp(46)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val center = size / 2f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.WHITE
            canvas.drawCircle(center, center, center - dp(1), paint)
            paint.color = modeColor(mode)
            canvas.drawCircle(center, center, center - dp(3), paint)

            when (mode) {
                TransportMode.MCC -> drawMarkerText(canvas, "МЦК", size, 9f)
                TransportMode.MCD -> drawMarkerText(canvas, "D", size, 19f)
                else -> {
                    val drawable = ContextCompat.getDrawable(activity, modeDrawable(mode))?.mutate() ?: return bitmap
                    DrawableCompat.setTint(drawable, Color.WHITE)
                    val inset = dp(11)
                    drawable.setBounds(inset, inset, size - inset, size - inset)
                    drawable.draw(canvas)
                }
            }
            return bitmap
        }

        private fun drawMarkerText(canvas: Canvas, text: String, size: Int, sp: Float) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                textSize = sp * activity.resources.displayMetrics.scaledDensity
            }
            val y = size / 2f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(text, size / 2f, y, paint)
        }

        private fun modeDrawable(mode: TransportMode): Int = when (mode) {
            TransportMode.BUS -> R.drawable.ic_bus
            TransportMode.TRAM -> R.drawable.ic_tram
            TransportMode.METRO -> R.drawable.ic_metro
            TransportMode.MCC, TransportMode.MCD, TransportMode.TRAIN -> R.drawable.ic_transport
            TransportMode.WALK -> R.drawable.ic_routes
        }

        private fun modeLabel(mode: TransportMode): String = when (mode) {
            TransportMode.BUS -> "Автобус"
            TransportMode.TRAM -> "Трамвай"
            TransportMode.METRO -> "Метро"
            TransportMode.MCC -> "МЦК"
            TransportMode.MCD -> "МЦД"
            TransportMode.TRAIN -> "Поезд"
            TransportMode.WALK -> "Пешком"
        }

        private fun modeColor(mode: TransportMode): Int = color(
            when (mode) {
                TransportMode.BUS -> R.color.vh_bus
                TransportMode.TRAM -> R.color.vh_tram
                TransportMode.METRO -> R.color.vh_metro
                TransportMode.MCC -> R.color.vh_mcc
                TransportMode.MCD -> R.color.vh_mcd
                TransportMode.TRAIN -> R.color.vh_train
                TransportMode.WALK -> R.color.vh_text_secondary
            }
        )

        private fun imageId(mode: TransportMode): String = "vh-stop-${mode.name.lowercase(Locale.ROOT)}"

        private fun formatTime(epochSec: Long): String = Instant.ofEpochSecond(epochSec)
            .atZone(zoneId)
            .format(timeFormatter)

        private fun formatDistance(meters: Int): String = if (meters < 1_000) {
            "$meters м"
        } else {
            String.format(Locale("ru"), "%.1f км", meters / 1_000.0)
        }

        private fun color(resource: Int): Int = ContextCompat.getColor(activity, resource)
        private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

        private fun emptyFeatures(): FeatureCollection = FeatureCollection.fromFeatures(emptyArray<Feature>())

        private fun normalize(value: String): String = value
            .lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .replace(Regex("[^а-яa-z0-9]+"), "")

        private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
            val p1 = a.lat * PI / 180.0
            val p2 = b.lat * PI / 180.0
            val dLat = (b.lat - a.lat) * PI / 180.0
            val dLon = (b.lon - a.lon) * PI / 180.0
            val q = sin(dLat / 2) * sin(dLat / 2) + cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
            return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(q)))
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> readField(target: Any, name: String): T? = runCatching {
            val field = target.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.get(target) as? T
        }.getOrNull()

        private fun writeField(target: Any, name: String, value: Any?) {
            runCatching {
                val field = target.javaClass.getDeclaredField(name)
                field.isAccessible = true
                field.set(target, value)
            }
        }

        private fun invokeByName(target: Any, name: String, vararg args: Any?) {
            runCatching {
                val method = target.javaClass.declaredMethods.firstOrNull {
                    it.name == name && it.parameterCount == args.size
                } ?: return
                method.isAccessible = true
                method.invoke(target, *args)
            }
        }

        companion object {
            private const val MARKER_SOURCE_ID = "vh-transit-symbol-source"
            private const val MARKER_LAYER_ID = "vh-transit-symbol-layer"
            private const val LEGACY_NEARBY_LAYER_ID = "vh-nearby-layer"
            private const val PROPERTY_STOP_ID = "vh_stop_id"
            private const val PROPERTY_STOP_NAME = "vh_stop_name"
            private const val PROPERTY_ICON = "vh_stop_icon"
            private const val STOP_SHEET_TAG = "vh_transit_stop_sheet"
            private const val STOP_TITLE_TAG = "vh_transit_stop_title"
            private const val PLATFORM_ROW_TAG = "vh_transit_platforms"
            private const val DIRECTIONS_TAG = "vh_transit_directions"
            private const val FROM_HERE_TAG = "vh_transit_from_here"
            private const val TO_HERE_TAG = "vh_transit_to_here"
            private const val PLATFORM_GROUP_METERS = 180.0
            private const val EARTH_RADIUS_METERS = 6_371_000.0
            private val markerModes = listOf(
                TransportMode.BUS,
                TransportMode.TRAM,
                TransportMode.METRO,
                TransportMode.MCC,
                TransportMode.MCD,
                TransportMode.TRAIN
            )
        }
    }
}
