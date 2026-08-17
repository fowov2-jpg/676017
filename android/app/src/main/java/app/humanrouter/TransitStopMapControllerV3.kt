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
import app.humanrouter.transit.TransitMapMarkerRepository
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Typed transport markers plus the compact stop/station action sheet.
 *
 * Marker density is independent from the short "Рядом" list: the map gets a spatially bounded set
 * of local runtime places around the current camera, while the home card can stay concise. Marker
 * lookup and direction lookup use separate single-thread workers, so panning never waits for a
 * timetable query. No live vehicle positions are fabricated here.
 */
internal object TransitStopMapControllerV3 {
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

    internal fun openForQa(activity: MainActivity, placeId: String): Boolean =
        controllers[activity]?.openById(placeId) == true

    internal fun markerCountForQa(activity: MainActivity): Int =
        controllers[activity]?.markerCount() ?: 0

    private class Controller(private val activity: MainActivity) {
        private val handler = Handler(Looper.getMainLooper())
        private val markerIo = Executors.newSingleThreadExecutor()
        private val directionIo = Executors.newSingleThreadExecutor()
        private val markerRepository = TransitMapMarkerRepository(activity)
        private val directionRepository = TransitStopDirectionRepository(activity)
        private val root = activity.findViewById<FrameLayout>(R.id.root)
        private val nearbyPanel = activity.findViewById<View>(R.id.nearbyPanel)
        private val bottomNav = activity.findViewById<View>(R.id.bottomNav)
        private val prefs = AppPreferences.prefs(activity)
        private val zoneId = ZoneId.of("Europe/Moscow")
        private val clock = DateTimeFormatter.ofPattern("HH:mm")
        private val markerSerial = AtomicInteger()

        private var map: MapLibreMap? = null
        private var source: GeoJsonSource? = null
        private var style: Style? = null
        private var sheet: View? = null
        private var selected: NearbyTransitPlace? = null
        private var restoreNearby = false
        private var destroyed = false
        @Volatile private var markerPlaces: List<NearbyTransitPlace> = emptyList()

        private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AppPreferences.KEY_SHOW_STOPS) handler.post(::requestMarkerRefresh)
        }

        private val mapClickListener = object : MapLibreMap.OnMapClickListener {
            override fun onMapClick(point: LatLng): Boolean {
                val activeMap = map ?: return false
                val pixel = activeMap.projection.toScreenLocation(point)
                val feature = activeMap.queryRenderedFeatures(pixel, MARKER_LAYER_ID).firstOrNull()
                val property = feature?.properties()?.get(PROPERTY_STOP_ID)
                val stopId = property?.takeUnless { it.isJsonNull }?.asString
                if (stopId.isNullOrBlank()) {
                    closeSheet()
                    return false
                }
                return openById(stopId)
            }
        }

        private val cameraIdleListener = MapLibreMap.OnCameraIdleListener {
            handler.removeCallbacks(requestRefreshRunnable)
            handler.postDelayed(requestRefreshRunnable, CAMERA_IDLE_DEBOUNCE_MS)
        }

        private val requestRefreshRunnable = Runnable { requestMarkerRefresh() }

        init {
            prefs.registerOnSharedPreferenceChangeListener(prefListener)
            attach(0L)
            attach(350L)
            attach(1_150L)
        }

        fun markerCount(): Int = markerPlaces.size

        fun destroy() {
            destroyed = true
            markerSerial.incrementAndGet()
            handler.removeCallbacksAndMessages(null)
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            map?.removeOnMapClickListener(mapClickListener)
            map?.removeOnCameraIdleListener(cameraIdleListener)
            closeSheet(restore = false)
            markerIo.shutdownNow()
            directionIo.shutdownNow()
            map = null
            source = null
            style = null
            markerPlaces = emptyList()
        }

        fun openById(placeId: String): Boolean {
            val place = allKnownPlaces().firstOrNull { it.id == placeId } ?: return false
            showSheet(place)
            return true
        }

        private fun attach(delayMs: Long) {
            handler.postDelayed({
                if (destroyed) return@postDelayed
                val currentMap = readField<MapLibreMap>(activity, "map") ?: return@postDelayed
                val currentStyle = currentMap.style ?: return@postDelayed
                if (map !== currentMap) {
                    map?.removeOnMapClickListener(mapClickListener)
                    map?.removeOnCameraIdleListener(cameraIdleListener)
                    map = currentMap
                    currentMap.addOnMapClickListener(mapClickListener)
                    currentMap.addOnCameraIdleListener(cameraIdleListener)
                }
                ensureStyle(currentStyle)
                requestMarkerRefresh()
            }, delayMs)
        }

        private fun ensureStyle(currentStyle: Style) {
            if (style === currentStyle && source != null) {
                hideLegacyDots(currentStyle)
                return
            }
            style = currentStyle
            source = null

            MARKER_MODES.forEach { mode ->
                val id = imageId(mode)
                if (currentStyle.getImage(id) == null) currentStyle.addImage(id, markerBitmap(mode))
            }
            source = currentStyle.getSourceAs<GeoJsonSource>(MARKER_SOURCE_ID)
                ?: GeoJsonSource(MARKER_SOURCE_ID, emptyFeatures()).also(currentStyle::addSource)

            if (currentStyle.getLayer(MARKER_LAYER_ID) == null) {
                val layer = SymbolLayer(MARKER_LAYER_ID, MARKER_SOURCE_ID).withProperties(
                    PropertyFactory.iconImage(Expression.image(Expression.get(PROPERTY_ICON))),
                    PropertyFactory.iconAllowOverlap(false),
                    PropertyFactory.iconIgnorePlacement(false),
                    PropertyFactory.iconPadding(3f),
                    PropertyFactory.iconSize(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(10, 0.58f),
                            Expression.stop(12, 0.68f),
                            Expression.stop(14, 0.86f),
                            Expression.stop(16, 1.0f)
                        )
                    )
                )
                layer.minZoom = MIN_MARKER_ZOOM.toFloat()
                currentStyle.addLayer(layer)
            }
            hideLegacyDots(currentStyle)
        }

        private fun hideLegacyDots(currentStyle: Style) {
            currentStyle.getLayer(LEGACY_NEARBY_LAYER_ID)
                ?.setProperties(PropertyFactory.circleOpacity(0f))
        }

        private fun requestMarkerRefresh() {
            if (destroyed) return
            val activeMap = map ?: readField<MapLibreMap>(activity, "map") ?: return
            val currentStyle = activeMap.style ?: return
            if (style !== currentStyle || source == null) ensureStyle(currentStyle)
            hideLegacyDots(currentStyle)

            if (!prefs.getBoolean(AppPreferences.KEY_SHOW_STOPS, true)) {
                markerSerial.incrementAndGet()
                markerPlaces = emptyList()
                renderMarkerFeatures(emptyList())
                return
            }

            val qaPlaces = nearbyPlaces().filter { it.id.startsWith("qa:") }
            if (BuildConfig.DEBUG && qaPlaces.isNotEmpty()) {
                markerSerial.incrementAndGet()
                markerPlaces = qaPlaces
                renderMarkerFeatures(qaPlaces)
                return
            }

            val center = activeMap.cameraPosition.target ?: return
            val zoom = activeMap.cameraPosition.zoom
            val request = markerSerial.incrementAndGet()
            val radius = radiusForZoom(zoom)
            val limit = limitForZoom(zoom)
            markerIo.execute {
                val found = runCatching {
                    markerRepository.around(
                        center = GeoPoint(center.latitude, center.longitude),
                        radiusMeters = radius,
                        limit = limit
                    )
                }.getOrDefault(emptyList())
                activity.runOnUiThread {
                    if (destroyed || request != markerSerial.get()) return@runOnUiThread
                    val fallback = nearbyPlaces()
                    markerPlaces = if (found.isNotEmpty()) found else fallback
                    renderMarkerFeatures(markerPlaces)
                }
            }
        }

        private fun renderMarkerFeatures(places: List<NearbyTransitPlace>) {
            if (destroyed) return
            val currentStyle = map?.style ?: return
            if (style !== currentStyle || source == null) ensureStyle(currentStyle)
            val features = places.map { place ->
                Feature.fromGeometry(Point.fromLngLat(place.point.lon, place.point.lat)).apply {
                    addStringProperty(PROPERTY_STOP_ID, place.id)
                    addStringProperty(PROPERTY_ICON, imageId(markerMode(place.modes)))
                }
            }
            source?.setGeoJson(FeatureCollection.fromFeatures(features.toTypedArray()))
        }

        private fun radiusForZoom(zoom: Double): Int = when {
            zoom >= 16.0 -> 1_500
            zoom >= 14.5 -> 2_500
            zoom >= 13.0 -> 4_000
            zoom >= 11.5 -> 7_000
            else -> 11_000
        }

        private fun limitForZoom(zoom: Double): Int = when {
            zoom >= 15.5 -> 80
            zoom >= 13.0 -> 120
            else -> 160
        }

        private fun showSheet(place: NearbyTransitPlace) {
            val shouldRestoreNearby = restoreNearby || nearbyPanel.visibility == View.VISIBLE
            removeSheetOnly()
            selected = place
            restoreNearby = shouldRestoreNearby
            nearbyPanel.visibility = View.GONE

            val card = LinearLayout(activity).apply {
                tag = STOP_SHEET_TAG
                orientation = LinearLayout.VERTICAL
                elevation = dp(24).toFloat()
                setPadding(dp(18), dp(9), dp(18), dp(14))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(28).toFloat()
                    setColor(color(R.color.vh_surface_solid))
                    setStroke(dp(1), color(R.color.vh_border))
                }
            }
            card.addView(View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(2).toFloat()
                    setColor(color(R.color.vh_border))
                }
            }, LinearLayout.LayoutParams(dp(42), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(8)
            })
            card.addView(header(place))

            val platformCandidates = platforms(place)
            if (platformCandidates.size > 1) card.addView(platformChooser(place, platformCandidates))

            card.addView(TextView(activity).apply {
                text = "Маршруты и направления"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.vh_text_tertiary))
                setPadding(0, dp(8), 0, dp(3))
            })
            val directionContainer = LinearLayout(activity).apply {
                tag = DIRECTIONS_TAG
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = "Загружаем направления…"
                    textSize = 13f
                    setTextColor(color(R.color.vh_text_secondary))
                    setPadding(0, dp(4), 0, dp(5))
                })
            }
            card.addView(directionContainer)
            card.addView(actionRow())

            root.addView(card, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(14)
                rightMargin = dp(14)
                bottomMargin = if (bottomNav.visibility == View.VISIBLE) dp(86) else dp(18)
            })
            sheet = card
            loadDirections(place, directionContainer)
        }

        private fun header(place: NearbyTransitPlace): View = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(activity).apply {
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
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(activity).apply {
                text = "×"
                textSize = 27f
                gravity = Gravity.CENTER
                contentDescription = "Закрыть карточку остановки"
                setTextColor(color(R.color.vh_text_secondary))
                setOnClickListener { closeSheet() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }

        private fun platformChooser(
            current: NearbyTransitPlace,
            candidates: List<NearbyTransitPlace>
        ): View = LinearLayout(activity).apply {
            tag = PLATFORM_ROW_TAG
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "Выберите площадку"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.vh_text_tertiary))
                setPadding(0, dp(3), 0, dp(3))
            })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                candidates.take(3).forEachIndexed { index, candidate ->
                    addView(Button(activity).apply {
                        isAllCaps = false
                        text = if (candidate.id == current.id) "✓ ${index + 1}" else "Площадка ${index + 1}"
                        textSize = 11.5f
                        minHeight = 0
                        minimumHeight = dp(38)
                        background = ContextCompat.getDrawable(activity, R.drawable.bg_chip)
                        setOnClickListener { showSheet(candidate) }
                    }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                        if (index > 0) leftMargin = dp(6)
                    })
                }
            })
        }

        private fun actionRow(): View = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
            addView(Button(activity).apply {
                tag = FROM_HERE_TAG
                text = "Отсюда"
                isAllCaps = false
                textSize = 15f
                minimumHeight = dp(50)
                background = ContextCompat.getDrawable(activity, R.drawable.bg_chip)
                setTextColor(color(R.color.vh_primary))
                setOnClickListener { selected?.let(::selectAsOrigin) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) })
            addView(Button(activity).apply {
                tag = TO_HERE_TAG
                text = "Сюда"
                isAllCaps = false
                textSize = 15f
                minimumHeight = dp(50)
                background = ContextCompat.getDrawable(activity, R.drawable.bg_primary)
                setTextColor(Color.WHITE)
                setOnClickListener { selected?.let(::selectAsDestination) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(6) })
        }

        private fun loadDirections(place: NearbyTransitPlace, container: LinearLayout) {
            val placeId = place.id
            directionIo.execute {
                val options = if (BuildConfig.DEBUG && placeId.startsWith("qa:")) {
                    qaDirections(place)
                } else {
                    directionRepository.directionsFor(place, Instant.now().epochSecond, 8)
                }
                activity.runOnUiThread {
                    if (destroyed || selected?.id != placeId || sheet?.parent == null) return@runOnUiThread
                    renderDirections(container, place, options)
                }
            }
        }

        private fun renderDirections(
            container: LinearLayout,
            place: NearbyTransitPlace,
            options: List<TransitDirectionOption>
        ) {
            container.removeAllViews()
            if (options.isEmpty()) {
                container.addView(TextView(activity).apply {
                    text = buildString {
                        if (place.routeLabels.isNotEmpty()) {
                            append(place.routeLabels.take(6).joinToString(" · ")).append("\n")
                        }
                        append("Направление не указано в установленной версии данных")
                    }
                    textSize = 13f
                    setTextColor(color(R.color.vh_text_secondary))
                    setPadding(0, dp(4), 0, dp(5))
                })
                return
            }
            val visible = options.take(COMPACT_DIRECTION_ROWS)
            visible.forEach { container.addView(directionRow(it)) }
            if (options.size > visible.size) {
                container.addView(TextView(activity).apply {
                    text = "Ещё ${options.size - visible.size} направлений"
                    textSize = 11.5f
                    setTextColor(color(R.color.vh_text_tertiary))
                    setPadding(0, dp(2), 0, dp(2))
                })
            }
        }

        private fun directionRow(option: TransitDirectionOption): View = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(TextView(activity).apply {
                text = option.routeLabel
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(modeColor(option.mode))
                background = ContextCompat.getDrawable(activity, R.drawable.bg_chip)
                setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(activity, modeDrawable(option.mode)),
                    null,
                    null,
                    null
                )
                compoundDrawablePadding = dp(4)
                setPadding(dp(8), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))
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
            setEndpoint(
                fieldName = "selectedFrom",
                fieldId = R.id.fromField,
                place = SearchPlace(place.name, stopSubtitle(place), place.point)
            )
            closeSheet()
            invokeByName(activity, "expandSearch", true)
        }

        private fun selectAsDestination(place: NearbyTransitPlace) {
            setEndpoint(
                fieldName = "selectedTo",
                fieldId = R.id.toField,
                place = SearchPlace(place.name, stopSubtitle(place), place.point)
            )
            activity.findViewById<TextView>(R.id.compactSearchButton).text = place.name
            closeSheet()
            activity.findViewById<Button>(R.id.routeButton).performClick()
        }

        private fun setEndpoint(fieldName: String, fieldId: Int, place: SearchPlace) {
            val field = activity.findViewById<EditText>(fieldId)
            val label = listOf(place.title, place.subtitle)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(", ")
            runCatching {
                val method = activity.javaClass.declaredMethods.firstOrNull {
                    it.name == "setFieldText" && it.parameterCount == 2
                }
                method?.isAccessible = true
                method?.invoke(activity, field, label)
            }.onFailure { field.setText(label) }
            writeField(activity, fieldName, place)
        }

        private fun platforms(place: NearbyTransitPlace): List<NearbyTransitPlace> {
            val key = normalize(place.name)
            val grouped = allKnownPlaces().filter { candidate ->
                normalize(candidate.name) == key &&
                    haversineMeters(candidate.point, place.point) <= PLATFORM_GROUP_METERS
            }
            return if (grouped.size > 1) grouped.sortedBy { it.distanceMeters } else listOf(place)
        }

        private fun allKnownPlaces(): List<NearbyTransitPlace> {
            val merged = LinkedHashMap<String, NearbyTransitPlace>()
            markerPlaces.forEach { merged[it.id] = it }
            nearbyPlaces().forEach { merged.putIfAbsent(it.id, it) }
            return merged.values.toList()
        }

        private fun nearbyPlaces(): List<NearbyTransitPlace> =
            readField<List<NearbyTransitPlace>>(activity, "lastNearby").orEmpty()

        private fun removeSheetOnly() {
            sheet?.let { if (it.parent === root) root.removeView(it) }
            sheet = null
        }

        private fun closeSheet(restore: Boolean = true) {
            removeSheetOnly()
            selected = null
            if (restore && restoreNearby) nearbyPanel.visibility = View.VISIBLE
            restoreNearby = false
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

        private fun markerMode(modes: Set<TransportMode>): TransportMode = when {
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
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.WHITE
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - dp(1), paint)
            paint.color = modeColor(mode)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - dp(3), paint)

            when (mode) {
                TransportMode.MCC -> drawMarkerText(canvas, "МЦК", size, 8.5f)
                TransportMode.MCD -> drawMarkerText(canvas, "D", size, 19f)
                else -> ContextCompat.getDrawable(activity, modeDrawable(mode))?.mutate()?.let { drawable ->
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

        private fun stopSubtitle(place: NearbyTransitPlace): String =
            modeSummary(place) + if (place.modes.any { it == TransportMode.BUS || it == TransportMode.TRAM }) {
                " · остановка"
            } else {
                " · станция"
            }

        private fun modeSummary(place: NearbyTransitPlace): String = place.modes
            .sortedBy(TransportMode::ordinal)
            .joinToString(" · ", transform = ::modeLabel)

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

        private fun modeColor(mode: TransportMode): Int = color(when (mode) {
            TransportMode.BUS -> R.color.vh_bus
            TransportMode.TRAM -> R.color.vh_tram
            TransportMode.METRO -> R.color.vh_metro
            TransportMode.MCC -> R.color.vh_mcc
            TransportMode.MCD -> R.color.vh_mcd
            TransportMode.TRAIN -> R.color.vh_train
            TransportMode.WALK -> R.color.vh_text_secondary
        })

        private fun imageId(mode: TransportMode): String = "vh-stop-${mode.name.lowercase(Locale.ROOT)}"

        private fun formatTime(epochSec: Long): String = Instant.ofEpochSecond(epochSec)
            .atZone(zoneId)
            .format(clock)

        private fun formatDistance(meters: Int): String = if (meters < 1_000) {
            "$meters м"
        } else {
            String.format(Locale("ru"), "%.1f км", meters / 1_000.0)
        }

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

        private fun color(res: Int): Int = ContextCompat.getColor(activity, res)
        private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
        private fun emptyFeatures(): FeatureCollection = FeatureCollection.fromFeatures(emptyArray<Feature>())

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
    }

    private const val MARKER_SOURCE_ID = "vh-transit-symbol-source"
    private const val MARKER_LAYER_ID = "vh-transit-symbol-layer"
    private const val LEGACY_NEARBY_LAYER_ID = "vh-nearby-layer"
    private const val PROPERTY_STOP_ID = "vh_stop_id"
    private const val PROPERTY_ICON = "vh_stop_icon"
    private const val STOP_SHEET_TAG = "vh_transit_stop_sheet"
    private const val STOP_TITLE_TAG = "vh_transit_stop_title"
    private const val PLATFORM_ROW_TAG = "vh_transit_platforms"
    private const val DIRECTIONS_TAG = "vh_transit_directions"
    private const val FROM_HERE_TAG = "vh_transit_from_here"
    private const val TO_HERE_TAG = "vh_transit_to_here"
    private const val COMPACT_DIRECTION_ROWS = 3
    private const val PLATFORM_GROUP_METERS = 180.0
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val CAMERA_IDLE_DEBOUNCE_MS = 280L
    private const val MIN_MARKER_ZOOM = 10.2
    private val MARKER_MODES = listOf(
        TransportMode.BUS,
        TransportMode.TRAM,
        TransportMode.METRO,
        TransportMode.MCC,
        TransportMode.MCD,
        TransportMode.TRAIN
    )
}
