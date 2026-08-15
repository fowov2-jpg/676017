package app.humanrouter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.humanrouter.routing.LastPlanStore
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.RouteDisplayKind
import app.humanrouter.routing.RouteDisplayStep
import app.humanrouter.routing.RouteLeg
import app.humanrouter.routing.RoutePresentation
import app.humanrouter.routing.TransportMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.min

/**
 * Visual language for Moscow public transport.
 *
 * The palette and badge semantics intentionally mirror the familiar Moscow scheme used by riders:
 * numbered metro circles, D1-D5 badges, blue bus badges, orange tram badges, red rail badges and a
 * visually distinct transfer token. The routing model stays untouched; this layer only translates
 * real route data into consistent map/UI presentation.
 */
internal object TransitVisualCatalog {
    internal enum class Glyph {
        WALK,
        BUS,
        TRAM,
        TRAIN,
        METRO,
        MCD,
        TRANSFER
    }

    internal data class Visual(
        val color: Int,
        val foreground: Int,
        val badge: String,
        val label: String,
        val glyph: Glyph,
        val circularBadge: Boolean = false
    )

    fun forLeg(leg: RouteLeg): Visual = transitVisual(leg.mode, leg.lineName, leg.lineId)

    fun forStep(step: RouteDisplayStep): Visual = when (step.kind) {
        RouteDisplayKind.WALK -> Visual(
            color = WALK_RED,
            foreground = Color.WHITE,
            badge = "",
            label = "Пешком",
            glyph = Glyph.WALK
        )
        RouteDisplayKind.TRANSFER -> Visual(
            color = TRANSFER_SLATE,
            foreground = Color.WHITE,
            badge = "",
            label = "Пересадка",
            glyph = Glyph.TRANSFER
        )
        RouteDisplayKind.TRANSIT -> transitVisual(
            requireNotNull(step.mode),
            step.lineName,
            step.lineId
        )
    }

    fun colorHex(mode: TransportMode, lineName: String? = null, lineId: String? = null): String =
        String.format(Locale.US, "#%06X", 0xFFFFFF and transitVisual(mode, lineName, lineId).color)

    fun metroLineNumber(label: String?): Int? {
        val raw = label.orEmpty().trim()
        if (raw.isBlank()) return null
        val normalized = normalize(raw)
        METRO_NAMES.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (needle, _) -> normalized.contains(needle) }
            ?.let { return it.value }
        val compact = raw.uppercase(Locale.ROOT)
            .replace('М', 'M')
            .replace("№", "")
            .replace(" ", "")
        Regex("(?:^|[^0-9])M?(1[0-6]|[1-9])(?:[^0-9]|$)")
            .find(compact)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }
        return null
    }

    fun mcdLineNumber(label: String?): Int? = Regex("D([1-5])", RegexOption.IGNORE_CASE)
        .find(label.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    fun badgeFor(mode: TransportMode, lineName: String? = null, lineId: String? = null): String =
        transitVisual(mode, lineName, lineId).badge

    private fun transitVisual(mode: TransportMode, lineName: String?, lineId: String?): Visual {
        val rawLine = lineName?.trim().takeUnless { it.isNullOrBlank() }
            ?: lineId?.substringAfterLast(':')?.trim().orEmpty()
        return when (mode) {
            TransportMode.WALK -> Visual(WALK_RED, Color.WHITE, "", "Пешком", Glyph.WALK)
            TransportMode.BUS -> Visual(
                BUS_BLUE,
                Color.WHITE,
                surfaceBadge(rawLine),
                "Автобус",
                Glyph.BUS
            )
            TransportMode.TRAM -> Visual(
                TRAM_ORANGE,
                Color.WHITE,
                surfaceBadge(rawLine),
                "Трамвай",
                Glyph.TRAM
            )
            TransportMode.TRAIN -> {
                val aero = normalize(rawLine).contains("аэроэкспресс") || normalize(rawLine).contains("aeroexpress")
                Visual(
                    TRAIN_RED,
                    Color.WHITE,
                    if (aero) "Аэроэкспресс" else surfaceBadge(rawLine),
                    if (aero) "Аэроэкспресс" else "Поезд",
                    Glyph.TRAIN
                )
            }
            TransportMode.METRO -> {
                val number = metroLineNumber(rawLine) ?: metroLineNumber(lineId)
                val color = METRO_COLORS[number] ?: METRO_FALLBACK
                Visual(
                    color,
                    contrastColor(color),
                    number?.toString() ?: rawLine.take(4).ifBlank { "М" },
                    "Метро",
                    Glyph.METRO,
                    circularBadge = true
                )
            }
            TransportMode.MCC -> Visual(
                METRO_COLORS.getValue(14),
                Color.WHITE,
                "14",
                "МЦК",
                Glyph.METRO,
                circularBadge = true
            )
            TransportMode.MCD -> {
                val number = mcdLineNumber(rawLine) ?: mcdLineNumber(lineId)
                val color = MCD_COLORS[number] ?: MCD_FALLBACK
                Visual(
                    color,
                    contrastColor(color),
                    number?.let { "D$it" } ?: rawLine.substringBefore('·').trim().take(5).ifBlank { "D" },
                    "МЦД",
                    Glyph.MCD
                )
            }
        }
    }

    private fun surfaceBadge(raw: String): String {
        val clean = raw.substringBefore('·').trim()
            .replace(Regex("^(автобус|трамвай|поезд|маршрут)\\s+", RegexOption.IGNORE_CASE), "")
        return clean.take(12)
    }

    private fun contrastColor(color: Int): Int {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return if (luminance > 0.66) Color.rgb(24, 34, 51) else Color.WHITE
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(Regex("[^а-яa-z0-9]+"), "")

    private val METRO_NAMES = linkedMapOf(
        "сокольническ" to 1,
        "sokolnich" to 1,
        "замоскворецк" to 2,
        "zamoskvoretsk" to 2,
        "арбатскопокровск" to 3,
        "arbatskopokrovsk" to 3,
        "филевск" to 4,
        "filyov" to 4,
        "кольцев" to 5,
        "koltsev" to 5,
        "калужскорижск" to 6,
        "kaluzhskorizh" to 6,
        "таганскокраснопресненск" to 7,
        "taganskokrasnopresn" to 7,
        "калининск" to 8,
        "солнцевск" to 8,
        "kalininsk" to 8,
        "solntsevsk" to 8,
        "серпуховскотимирязевск" to 9,
        "serpukhovskotimiryaz" to 9,
        "люблинскодмитровск" to 10,
        "lyublinskodmitrovsk" to 10,
        "большаякольцевая" to 11,
        "bolshayakoltsevaya" to 11,
        "бутовск" to 12,
        "butovsk" to 12,
        "московскоeцентральноекольцо" to 14,
        "московскоецентральноекольцо" to 14,
        "mcc" to 14,
        "некрасовск" to 15,
        "nekrasovsk" to 15,
        "троицк" to 16,
        "troitsk" to 16
    )

    private val METRO_COLORS = mapOf(
        1 to Color.rgb(232, 29, 37),
        2 to Color.rgb(52, 168, 83),
        3 to Color.rgb(22, 102, 199),
        4 to Color.rgb(52, 177, 225),
        5 to Color.rgb(137, 78, 24),
        6 to Color.rgb(255, 139, 0),
        7 to Color.rgb(152, 50, 166),
        8 to Color.rgb(255, 204, 23),
        9 to Color.rgb(159, 166, 174),
        10 to Color.rgb(118, 190, 96),
        11 to Color.rgb(82, 188, 210),
        12 to Color.rgb(82, 169, 87),
        13 to Color.rgb(226, 102, 96),
        14 to Color.rgb(224, 92, 89),
        15 to Color.rgb(126, 96, 194),
        16 to Color.rgb(58, 174, 160)
    )

    private val MCD_COLORS = mapOf(
        1 to Color.rgb(250, 197, 26),
        2 to Color.rgb(223, 82, 129),
        3 to Color.rgb(126, 190, 45),
        4 to Color.rgb(32, 190, 174),
        5 to Color.rgb(244, 132, 31)
    )

    private val WALK_RED = Color.rgb(231, 30, 40)
    private val BUS_BLUE = Color.rgb(28, 100, 194)
    private val TRAM_ORANGE = Color.rgb(255, 139, 0)
    private val TRAIN_RED = Color.rgb(221, 31, 47)
    private val TRANSFER_SLATE = Color.rgb(83, 97, 122)
    private val METRO_FALLBACK = Color.rgb(40, 123, 255)
    private val MCD_FALLBACK = Color.rgb(43, 174, 164)
}

/** Small vector-like transport pictogram drawn directly on Canvas to stay crisp at any density. */
internal class TransitGlyphView(
    context: Context,
    private val glyph: TransitVisualCatalog.Glyph,
    private val fillColor: Int,
    private val foregroundColor: Int = Color.WHITE
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) * 0.48f
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        canvas.drawCircle(cx, cy, radius, paint)
        paint.color = foregroundColor
        when (glyph) {
            TransitVisualCatalog.Glyph.WALK -> drawWalk(canvas, w, h)
            TransitVisualCatalog.Glyph.BUS -> drawBus(canvas, w, h, tram = false)
            TransitVisualCatalog.Glyph.TRAM -> drawBus(canvas, w, h, tram = true)
            TransitVisualCatalog.Glyph.TRAIN -> drawTrain(canvas, w, h)
            TransitVisualCatalog.Glyph.METRO -> drawLetter(canvas, "M", w, h)
            TransitVisualCatalog.Glyph.MCD -> drawLetter(canvas, "D", w, h)
            TransitVisualCatalog.Glyph.TRANSFER -> drawTransfer(canvas, w, h)
        }
    }

    private fun drawWalk(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        canvas.drawCircle(w * 0.52f, h * 0.28f, w * 0.075f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.075f
        canvas.drawLine(w * 0.50f, h * 0.38f, w * 0.46f, h * 0.62f, paint)
        canvas.drawLine(w * 0.48f, h * 0.46f, w * 0.32f, h * 0.54f, paint)
        canvas.drawLine(w * 0.48f, h * 0.46f, w * 0.65f, h * 0.52f, paint)
        canvas.drawLine(w * 0.46f, h * 0.61f, w * 0.31f, h * 0.78f, paint)
        canvas.drawLine(w * 0.46f, h * 0.61f, w * 0.62f, h * 0.78f, paint)
    }

    private fun drawBus(canvas: Canvas, w: Float, h: Float, tram: Boolean) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.065f
        val body = RectF(w * 0.27f, h * 0.30f, w * 0.73f, h * 0.72f)
        canvas.drawRoundRect(body, w * 0.08f, w * 0.08f, paint)
        canvas.drawLine(w * 0.33f, h * 0.44f, w * 0.67f, h * 0.44f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(w * 0.36f, h * 0.76f, w * 0.055f, paint)
        canvas.drawCircle(w * 0.64f, h * 0.76f, w * 0.055f, paint)
        if (tram) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w * 0.05f
            canvas.drawLine(w * 0.43f, h * 0.29f, w * 0.52f, h * 0.18f, paint)
            canvas.drawLine(w * 0.52f, h * 0.18f, w * 0.61f, h * 0.29f, paint)
        }
    }

    private fun drawTrain(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.065f
        val body = RectF(w * 0.28f, h * 0.25f, w * 0.72f, h * 0.71f)
        canvas.drawRoundRect(body, w * 0.13f, w * 0.13f, paint)
        canvas.drawLine(w * 0.36f, h * 0.43f, w * 0.64f, h * 0.43f, paint)
        canvas.drawLine(w * 0.38f, h * 0.72f, w * 0.28f, h * 0.83f, paint)
        canvas.drawLine(w * 0.62f, h * 0.72f, w * 0.72f, h * 0.83f, paint)
        canvas.drawLine(w * 0.32f, h * 0.82f, w * 0.68f, h * 0.82f, paint)
    }

    private fun drawTransfer(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.065f
        canvas.drawLine(w * 0.28f, h * 0.42f, w * 0.68f, h * 0.42f, paint)
        canvas.drawLine(w * 0.61f, h * 0.34f, w * 0.69f, h * 0.42f, paint)
        canvas.drawLine(w * 0.61f, h * 0.50f, w * 0.69f, h * 0.42f, paint)
        canvas.drawLine(w * 0.72f, h * 0.62f, w * 0.32f, h * 0.62f, paint)
        canvas.drawLine(w * 0.39f, h * 0.54f, w * 0.31f, h * 0.62f, paint)
        canvas.drawLine(w * 0.39f, h * 0.70f, w * 0.31f, h * 0.62f, paint)
    }

    private fun drawLetter(canvas: Canvas, value: String, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = w * 0.46f
        val baseline = h * 0.5f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(value, w * 0.5f, baseline, paint)
    }
}

/**
 * Applies the reference visual system to the existing route screen without changing route choice.
 * The controller is intentionally additive: routing, ETA and accessibility semantics remain owned
 * by MainActivity, while this class supplies badges, exact line colours and transfer geometry.
 */
internal object TransitVisualPolish {
    private val controllers = WeakHashMap<MainActivity, Controller>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (controllers.containsKey(activity)) return
        controllers[activity] = Controller(activity)
    }

    @Synchronized
    fun refresh(activity: MainActivity) {
        controllers[activity]?.refresh(force = true)
    }

    @Synchronized
    fun destroy(activity: MainActivity) {
        controllers.remove(activity)?.destroy()
    }

    private class Controller(private val activity: MainActivity) {
        private val density = activity.resources.displayMetrics.density
        private val root = activity.findViewById<ViewGroup>(R.id.root)
        private val routePanel = activity.findViewById<LinearLayout>(R.id.routeResultsPanel)
        private val mapView = activity.findViewById<MapView>(R.id.mapView)
        private var lastSignature = ""
        private var scheduled = false
        private var destroyed = false

        private val liveListener: (TripLiveSnapshot) -> Unit = {
            activity.runOnUiThread { refresh(force = true) }
        }

        private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            scheduleRefresh()
        }

        init {
            root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            TripLiveState.addListener(liveListener)
            root.post { refresh(force = true) }
        }

        fun destroy() {
            destroyed = true
            TripLiveState.removeListener(liveListener)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        }

        fun refresh(force: Boolean) {
            if (destroyed || activity.isFinishing || activity.isDestroyed) return
            val route = currentRoute() ?: return
            val signature = buildSignature(route)
            if (!force && signature == lastSignature) return
            lastSignature = signature
            val steps = RoutePresentation.steps(route)
            if (steps.isEmpty()) return

            if (containsText(routePanel, "В пути")) {
                decorateActiveTrip(route, steps)
            } else {
                decorateSelectedRouteCard(route, steps)
            }
            recolorMatchingTexts(steps)
            applyMapStyle(route)
        }

        private fun scheduleRefresh() {
            if (scheduled || destroyed) return
            scheduled = true
            root.post {
                scheduled = false
                refresh(force = false)
            }
        }

        private fun currentRoute(): RouteCandidate? =
            TripLiveState.current()?.route
                ?: ActiveTripStore.load(activity)?.route
                ?: LastPlanStore.seed?.route

        private fun buildSignature(route: RouteCandidate): String {
            val textHash = allTextViews(routePanel)
                .take(36)
                .joinToString("|") { it.text?.toString().orEmpty() }
                .hashCode()
            return "${route.id}:${routePanel.childCount}:$textHash"
        }

        private fun decorateSelectedRouteCard(route: RouteCandidate, steps: List<RouteDisplayStep>) {
            val cards = (0 until routePanel.childCount)
                .map { routePanel.getChildAt(it) }
                .filterIsInstance<LinearLayout>()
                .filter { it.isClickable && it.orientation == LinearLayout.VERTICAL }
            val selected = cards.firstOrNull { it.elevation > 0f } ?: cards.firstOrNull() ?: return
            selected.findViewWithTag<View>(TAG_ROUTE_STRIP)?.let { old ->
                if (old.getTag(TAG_ROUTE_ID_KEY) == route.id) return@let
                (old.parent as? ViewGroup)?.removeView(old)
            }
            if (selected.findViewWithTag<View>(TAG_ROUTE_STRIP) == null) {
                val strip = buildRouteStrip(route, steps).apply {
                    tag = TAG_ROUTE_STRIP
                    setTag(TAG_ROUTE_ID_KEY, route.id)
                    alpha = 0f
                    translationX = dp(12).toFloat()
                }
                selected.addView(strip, min(2, selected.childCount))
                strip.animate().alpha(1f).translationX(0f).setDuration(180L).start()
            }

            for (index in 0 until selected.childCount) {
                val child = selected.getChildAt(index)
                if (child is TextView && child.tag != TAG_ROUTE_STRIP) {
                    val text = child.text?.toString().orEmpty()
                    if (text.contains("  ›  ")) child.visibility = View.GONE
                }
            }

            selected.background = roundedRect(
                color = blend(Color.WHITE, Color.rgb(40, 123, 255), 0.055f),
                strokeColor = Color.rgb(104, 166, 255),
                strokeWidth = dp(1),
                radius = dp(18).toFloat()
            )
        }

        private fun decorateActiveTrip(route: RouteCandidate, steps: List<RouteDisplayStep>) {
            var strip = routePanel.findViewWithTag<View>(TAG_ACTIVE_STRIP)
            if (strip?.getTag(TAG_ROUTE_ID_KEY) != route.id) {
                if (strip != null) (strip.parent as? ViewGroup)?.removeView(strip)
                strip = buildRouteStrip(route, steps).apply {
                    tag = TAG_ACTIVE_STRIP
                    setTag(TAG_ROUTE_ID_KEY, route.id)
                    alpha = 0f
                }
                routePanel.addView(strip, min(2, routePanel.childCount))
                strip.animate().alpha(1f).setDuration(180L).start()
            }

            for (index in 0 until routePanel.childCount) {
                val child = routePanel.getChildAt(index)
                if (child is HorizontalScrollView && child !== strip && child.tag != TAG_ACTIVE_STRIP) {
                    child.visibility = View.GONE
                }
            }

            decorateCurrentStep(steps)
            decorateTimeline(steps)
        }

        private fun decorateCurrentStep(steps: List<RouteDisplayStep>) {
            val card = directLinearChildren(routePanel).firstOrNull { containsText(it, "Текущий этап") } ?: return
            val title = allTextViews(card).firstOrNull { view ->
                view.text?.toString()?.let { text ->
                    text.isNotBlank() && !text.startsWith("Текущий этап") &&
                        !text.startsWith("До ") && !text.startsWith("Затем") && !text.startsWith("Этап ")
                } == true
            }
            val titleText = title?.text?.toString().orEmpty()
            val step = steps.firstOrNull { stepMatchesTitle(it, titleText) } ?: return
            val visual = TransitVisualCatalog.forStep(step)
            card.background = roundedRect(
                blend(Color.WHITE, visual.color, 0.075f),
                blend(Color.WHITE, visual.color, 0.42f),
                dp(1),
                dp(18).toFloat()
            )
            title?.setTextColor(visual.color)
            if (step.kind == RouteDisplayKind.TRANSFER && title != null) {
                title.text = title.text.toString().replace("Переход", "Пересадка")
            }
            progressBars(card).forEach { bar ->
                bar.progressTintList = android.content.res.ColorStateList.valueOf(visual.color)
            }
        }

        private fun decorateTimeline(steps: List<RouteDisplayStep>) {
            val rows = directLinearChildren(routePanel).filter {
                it.contentDescription?.toString()?.startsWith("Этап маршрута:") == true
            }
            rows.zip(steps).forEach { (row, step) ->
                val visual = TransitVisualCatalog.forStep(step)
                val rail = row.getChildAt(0) as? FrameLayout
                val dot = rail?.getChildAt(1)
                dot?.background = oval(visual.color, Color.WHITE, dp(2))

                val card = row.getChildAt(1) as? LinearLayout
                card?.background = roundedRect(
                    blend(Color.WHITE, visual.color, if (step.kind == RouteDisplayKind.TRANSIT) 0.045f else 0.025f),
                    blend(Color.WHITE, visual.color, 0.70f),
                    dp(1),
                    dp(14).toFloat()
                )
                val titleRow = card?.getChildAt(0) as? LinearLayout
                val title = titleRow?.getChildAt(1) as? TextView
                title?.setTextColor(visual.color)
                if (step.kind == RouteDisplayKind.TRANSFER && title != null) {
                    title.text = title.text.toString().replace("Переход", "Пересадка")
                }
            }
        }

        private fun buildRouteStrip(route: RouteCandidate, steps: List<RouteDisplayStep>): HorizontalScrollView =
            HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                contentDescription = "Схема транспорта маршрута"
                setPadding(0, dp(6), 0, dp(4))
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                steps.forEachIndexed { index, step ->
                    if (index > 0) row.addView(connector(step.kind == RouteDisplayKind.TRANSFER))
                    row.addView(stepToken(step))
                }
                addView(row)
            }

        private fun connector(transfer: Boolean): View = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            addView(TextView(activity).apply {
                text = if (transfer) "↔" else "›"
                textSize = if (transfer) 16f else 18f
                gravity = Gravity.CENTER
                setTextColor(if (transfer) Color.rgb(83, 97, 122) else Color.rgb(125, 139, 160))
            }, LinearLayout.LayoutParams(dp(22), dp(32)))
        }

        private fun stepToken(step: RouteDisplayStep): View {
            val visual = TransitVisualCatalog.forStep(step)
            return LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(38)
                setPadding(dp(5), dp(3), dp(7), dp(3))
                background = roundedRect(
                    blend(Color.WHITE, visual.color, 0.085f),
                    blend(Color.WHITE, visual.color, 0.55f),
                    dp(1),
                    dp(16).toFloat()
                )
                contentDescription = when (step.kind) {
                    RouteDisplayKind.WALK -> "Пешком"
                    RouteDisplayKind.TRANSFER -> "Пересадка"
                    RouteDisplayKind.TRANSIT ->
                        "Транспорт: ${visual.label.lowercase(Locale.ROOT)}; ${visual.badge}".trimEnd(';', ' ')
                }

                if (step.kind == RouteDisplayKind.TRANSIT && visual.circularBadge) {
                    addView(circleBadge(visual), LinearLayout.LayoutParams(dp(30), dp(30)))
                    addView(TextView(activity).apply {
                        text = visual.label
                        textSize = 11.5f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Color.rgb(42, 54, 73))
                        setPadding(dp(6), 0, 0, 0)
                    })
                } else {
                    addView(
                        TransitGlyphView(activity, visual.glyph, visual.color, visual.foreground),
                        LinearLayout.LayoutParams(dp(30), dp(30))
                    )
                    addView(TextView(activity).apply {
                        text = when (step.kind) {
                            RouteDisplayKind.WALK -> "Пешком"
                            RouteDisplayKind.TRANSFER -> "Пересадка"
                            RouteDisplayKind.TRANSIT -> visual.badge.ifBlank { visual.label }
                        }
                        textSize = 12f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Color.rgb(42, 54, 73))
                        setPadding(dp(6), 0, 0, 0)
                    })
                }
            }
        }

        private fun circleBadge(visual: TransitVisualCatalog.Visual): TextView = TextView(activity).apply {
            text = visual.badge
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(visual.foreground)
            background = oval(visual.color, Color.TRANSPARENT, 0)
            includeFontPadding = false
        }

        private fun recolorMatchingTexts(steps: List<RouteDisplayStep>) {
            val views = allTextViews(routePanel)
            steps.forEach { step ->
                val visual = TransitVisualCatalog.forStep(step)
                val rawLine = step.lineName?.takeIf(String::isNotBlank)
                    ?: step.lineId?.substringAfterLast(':')?.takeIf(String::isNotBlank)
                views.forEach { view ->
                    val text = view.text?.toString().orEmpty()
                    when {
                        step.kind == RouteDisplayKind.TRANSFER && text.startsWith("Переход") -> {
                            view.setTextColor(visual.color)
                            if (text.length <= 28) view.text = text.replace("Переход", "Пересадка")
                        }
                        step.kind == RouteDisplayKind.TRANSIT && rawLine != null &&
                            text.contains(rawLine, ignoreCase = true) -> {
                            view.setTextColor(visual.color)
                        }
                    }
                }
            }
        }

        private fun stepMatchesTitle(step: RouteDisplayStep, title: String): Boolean {
            if (title.isBlank()) return false
            return when (step.kind) {
                RouteDisplayKind.WALK -> title.contains("Пешком", ignoreCase = true)
                RouteDisplayKind.TRANSFER -> title.contains("Переход", ignoreCase = true) ||
                    title.contains("Пересад", ignoreCase = true)
                RouteDisplayKind.TRANSIT -> {
                    val line = step.lineName ?: step.lineId?.substringAfterLast(':').orEmpty()
                    title.contains(line, ignoreCase = true) ||
                        title.contains(TransitVisualCatalog.forStep(step).label, ignoreCase = true)
                }
            }
        }

        private fun applyMapStyle(route: RouteCandidate) {
            mapView.getMapAsync { map ->
                map.getStyle { style ->
                    val transit = ArrayList<Feature>()
                    val walk = ArrayList<Feature>()
                    val transfer = ArrayList<Feature>()
                    val transferPoints = ArrayList<Feature>()

                    route.legs.forEachIndexed { index, leg ->
                        val points = leg.mapPoints()
                        if (points.size < 2) return@forEachIndexed
                        val isTransfer = leg.mode == TransportMode.WALK && index > 0 && index < route.legs.lastIndex &&
                            route.legs[index - 1].mode != TransportMode.WALK &&
                            route.legs[index + 1].mode != TransportMode.WALK
                        when {
                            isTransfer -> {
                                transfer += lineFeature(leg, "#53617A")
                                val before = route.legs[index - 1]
                                val after = route.legs[index + 1]
                                transferPoints += pointFeature(leg.from.point.lon, leg.from.point.lat, TransitVisualCatalog.colorHex(before.mode, before.lineName, before.lineId))
                                transferPoints += pointFeature(leg.to.point.lon, leg.to.point.lat, TransitVisualCatalog.colorHex(after.mode, after.lineName, after.lineId))
                            }
                            leg.mode == TransportMode.WALK -> walk += lineFeature(leg, "#758196")
                            else -> transit += lineFeature(
                                leg,
                                TransitVisualCatalog.colorHex(leg.mode, leg.lineName, leg.lineId)
                            )
                        }
                    }

                    source(style, MAP_TRANSIT_SOURCE, transit)
                    source(style, MAP_WALK_SOURCE, walk)
                    source(style, MAP_TRANSFER_SOURCE, transfer)
                    source(style, MAP_TRANSFER_POINTS_SOURCE, transferPoints)

                    lineLayer(style, MAP_TRANSIT_HALO, MAP_TRANSIT_SOURCE, Color.WHITE.toHex(), 10f, 0.90f)
                    lineLayer(style, MAP_TRANSIT_LAYER, MAP_TRANSIT_SOURCE, null, 7f, 0.98f)
                    style.getLayerAs<LineLayer>(MAP_TRANSIT_LAYER)?.setProperties(
                        PropertyFactory.lineColor(Expression.get("segment_color")),
                        PropertyFactory.lineWidth(7f),
                        PropertyFactory.lineOpacity(0.98f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    )

                    lineLayer(style, MAP_WALK_LAYER, MAP_WALK_SOURCE, "#758196", 4f, 0.92f)
                    style.getLayerAs<LineLayer>(MAP_WALK_LAYER)?.setProperties(
                        PropertyFactory.lineDasharray(arrayOf(1.0f, 1.5f)),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    )

                    lineLayer(style, MAP_TRANSFER_HALO, MAP_TRANSFER_SOURCE, Color.WHITE.toHex(), 9f, 0.96f)
                    lineLayer(style, MAP_TRANSFER_LAYER, MAP_TRANSFER_SOURCE, "#53617A", 4f, 1f)
                    style.getLayerAs<LineLayer>(MAP_TRANSFER_LAYER)?.setProperties(
                        PropertyFactory.lineDasharray(arrayOf(0.65f, 1.05f)),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    )

                    val transferPointLayer = style.getLayerAs<CircleLayer>(MAP_TRANSFER_POINTS_LAYER)
                        ?: CircleLayer(MAP_TRANSFER_POINTS_LAYER, MAP_TRANSFER_POINTS_SOURCE).withProperties(
                            PropertyFactory.circleColor(Expression.get("transfer_color")),
                            PropertyFactory.circleRadius(5.5f),
                            PropertyFactory.circleStrokeColor(Color.WHITE.toHex()),
                            PropertyFactory.circleStrokeWidth(2.5f),
                            PropertyFactory.circleOpacity(1f)
                        ).also(style::addLayer)
                    transferPointLayer.setProperties(PropertyFactory.visibility(Property.VISIBLE))
                }
            }
        }

        private fun lineFeature(leg: RouteLeg, color: String): Feature {
            val geometry = LineString.fromLngLats(leg.mapPoints().map { Point.fromLngLat(it.lon, it.lat) })
            return Feature.fromGeometry(geometry).apply {
                addStringProperty("segment_color", color)
                addStringProperty("segment_mode", leg.mode.name)
                addStringProperty("segment_line", leg.lineName ?: leg.lineId.orEmpty())
            }
        }

        private fun pointFeature(lon: Double, lat: Double, color: String): Feature =
            Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                addStringProperty("transfer_color", color)
            }

        private fun source(style: org.maplibre.android.maps.Style, id: String, features: List<Feature>) {
            val collection = FeatureCollection.fromFeatures(features.toTypedArray())
            val existing = style.getSourceAs<GeoJsonSource>(id)
            if (existing != null) existing.setGeoJson(collection)
            else style.addSource(GeoJsonSource(id, collection))
        }

        private fun lineLayer(
            style: org.maplibre.android.maps.Style,
            id: String,
            sourceId: String,
            color: String?,
            width: Float,
            opacity: Float
        ) {
            val existing = style.getLayerAs<LineLayer>(id)
            if (existing != null) {
                val props = mutableListOf(
                    PropertyFactory.lineWidth(width),
                    PropertyFactory.lineOpacity(opacity),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.visibility(Property.VISIBLE)
                )
                if (color != null) props += PropertyFactory.lineColor(color)
                existing.setProperties(*props.toTypedArray())
                return
            }
            val layer = LineLayer(id, sourceId).withProperties(
                PropertyFactory.lineWidth(width),
                PropertyFactory.lineOpacity(opacity),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
            if (color != null) layer.setProperties(PropertyFactory.lineColor(color))
            style.addLayer(layer)
        }

        private fun directLinearChildren(group: ViewGroup): List<LinearLayout> =
            (0 until group.childCount).mapNotNull { group.getChildAt(it) as? LinearLayout }

        private fun containsText(view: View, needle: String): Boolean =
            allTextViews(view).any { it.text?.toString()?.contains(needle, ignoreCase = true) == true }

        private fun allTextViews(root: View): List<TextView> {
            val result = ArrayList<TextView>()
            fun visit(view: View) {
                if (view is TextView) result += view
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(root)
            return result
        }

        private fun progressBars(root: View): List<ProgressBar> {
            val result = ArrayList<ProgressBar>()
            fun visit(view: View) {
                if (view is ProgressBar) result += view
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(root)
            return result
        }

        private fun roundedRect(
            color: Int,
            strokeColor: Int,
            strokeWidth: Int,
            radius: Float
        ): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

        private fun oval(color: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

        private fun blend(base: Int, accent: Int, amount: Float): Int {
            val t = amount.coerceIn(0f, 1f)
            val r = (Color.red(base) * (1f - t) + Color.red(accent) * t).toInt()
            val g = (Color.green(base) * (1f - t) + Color.green(accent) * t).toInt()
            val b = (Color.blue(base) * (1f - t) + Color.blue(accent) * t).toInt()
            return Color.rgb(r, g, b)
        }

        private fun Int.toHex(): String = String.format(Locale.US, "#%06X", 0xFFFFFF and this)
        private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    }

    internal const val TAG_ROUTE_STRIP = "vh_transit_visual_route_strip"
    internal const val TAG_ACTIVE_STRIP = "vh_transit_visual_active_strip"
    private const val TAG_ROUTE_ID_KEY = 0x7f0f7001

    private const val MAP_TRANSIT_SOURCE = "vh-visual-transit-source"
    private const val MAP_TRANSIT_HALO = "vh-visual-transit-halo"
    private const val MAP_TRANSIT_LAYER = "vh-visual-transit-layer"
    private const val MAP_WALK_SOURCE = "vh-visual-walk-source"
    private const val MAP_WALK_LAYER = "vh-visual-walk-layer"
    private const val MAP_TRANSFER_SOURCE = "vh-visual-transfer-source"
    private const val MAP_TRANSFER_HALO = "vh-visual-transfer-halo"
    private const val MAP_TRANSFER_LAYER = "vh-visual-transfer-layer"
    private const val MAP_TRANSFER_POINTS_SOURCE = "vh-visual-transfer-points-source"
    private const val MAP_TRANSFER_POINTS_LAYER = "vh-visual-transfer-points-layer"
}
