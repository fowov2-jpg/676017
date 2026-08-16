package app.humanrouter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import android.widget.TextView
import app.humanrouter.routing.LastPlanStore
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.RouteDisplayKind
import app.humanrouter.routing.RouteDisplayStep
import app.humanrouter.routing.RoutePresentation
import app.humanrouter.routing.RouteTransferKind
import app.humanrouter.routing.TransportMode
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.min

/**
 * Second-generation Moscow route strip/timeline polish.
 *
 * It intentionally replaces the first visual strip instead of stacking another decoration. Tokens
 * never truncate a line number: metro/MCC use coloured numbered circles, surface routes keep their
 * actual route ref, walking uses a clean red pedestrian pictogram, and transfers use distinct
 * crossing/stairs/interchange symbols. Unknown transfer geometry remains a neutral interchange —
 * the UI never guesses "underground" or "overground" without route data saying so.
 */
internal object TransitJourneyUiV2 {
    private val controllers = WeakHashMap<MainActivity, Controller>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (controllers.containsKey(activity)) return
        controllers[activity] = Controller(activity)
    }

    @Synchronized
    fun refresh(activity: MainActivity) {
        controllers[activity]?.refresh(true)
    }

    @Synchronized
    fun destroy(activity: MainActivity) {
        controllers.remove(activity)?.destroy()
    }

    private class Controller(private val activity: MainActivity) {
        private val density = activity.resources.displayMetrics.density
        private val root = activity.findViewById<ViewGroup>(R.id.root)
        private val panel = activity.findViewById<LinearLayout>(R.id.routeResultsPanel)
        private var lastSignature = ""
        private var scheduled = false
        private var destroyed = false

        private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { schedule() }
        private val liveListener: (TripLiveSnapshot) -> Unit = { activity.runOnUiThread { refresh(true) } }

        init {
            root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            TripLiveState.addListener(liveListener)
            root.post { refresh(true) }
        }

        fun destroy() {
            destroyed = true
            TripLiveState.removeListener(liveListener)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        }

        private fun schedule() {
            if (scheduled || destroyed) return
            scheduled = true
            root.post {
                scheduled = false
                refresh(false)
            }
        }

        fun refresh(force: Boolean) {
            if (destroyed || activity.isDestroyed || activity.isFinishing) return
            val route = currentRoute() ?: return
            val steps = RoutePresentation.steps(route)
            if (steps.isEmpty()) return
            val signature = route.id + ":" + panel.childCount + ":" + steps.joinToString("|") {
                "${it.kind}:${it.mode}:${it.lineName}:${it.walkMeters}:${it.from.name}:${it.to.name}"
            }.hashCode()
            if (!force && signature == lastSignature) return
            lastSignature = signature

            hideLegacyStrips()
            installStrip(route, steps)
            decorateTimeline(steps)
            decorateCurrentCard(steps)
        }

        private fun currentRoute(): RouteCandidate? =
            TripLiveState.current()?.route
                ?: ActiveTripStore.load(activity)?.route
                ?: LastPlanStore.seed?.route

        private fun hideLegacyStrips() {
            descendants(panel).filterIsInstance<HorizontalScrollView>().forEach { scroll ->
                val description = scroll.contentDescription?.toString().orEmpty()
                if (description == "Схема транспорта маршрута") scroll.visibility = View.GONE
            }
        }

        private fun installStrip(route: RouteCandidate, steps: List<RouteDisplayStep>) {
            val existing = panel.findViewWithTag<View>(TAG_V2_STRIP)
            if (existing?.getTag(TAG_ROUTE_ID_KEY) == route.id) return
            if (existing != null) (existing.parent as? ViewGroup)?.removeView(existing)

            val scroll = HorizontalScrollView(activity).apply {
                tag = TAG_V2_STRIP
                setTag(TAG_ROUTE_ID_KEY, route.id)
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_NEVER
                contentDescription = "Этапы маршрута с линиями и переходами"
                setPadding(0, dp(5), 0, dp(6))

                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                steps.forEachIndexed { index, step ->
                    if (index > 0) row.addView(connector(step))
                    row.addView(token(step))
                }
                addView(row)
            }
            val index = min(2, panel.childCount)
            panel.addView(scroll, index)
            scroll.alpha = 0f
            scroll.translationX = dp(12).toFloat()
            scroll.animate().alpha(1f).translationX(0f).setDuration(170L).start()
        }

        private fun connector(next: RouteDisplayStep): View = TextView(activity).apply {
            text = if (next.kind == RouteDisplayKind.TRANSFER) "→" else "›"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(116, 130, 151))
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(44))
        }

        private fun token(step: RouteDisplayStep): View {
            val visual = TransitVisualCatalog.forStep(step)
            val accent = transferColor(step) ?: visual.color
            val holder = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(46)
                setPadding(dp(7), dp(4), dp(10), dp(4))
                background = rounded(
                    blend(Color.WHITE, accent, 0.08f),
                    blend(Color.WHITE, accent, 0.48f),
                    dp(1),
                    dp(18).toFloat()
                )
            }

            val glyph = JourneyGlyphView(activity, step, accent, visual.foreground)
            holder.addView(glyph, LinearLayout.LayoutParams(dp(34), dp(34)))

            val textColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(7), 0, 0, 0)
            }
            textColumn.addView(TextView(activity).apply {
                text = primaryLabel(step, visual)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(37, 49, 69))
                includeFontPadding = false
                maxLines = 1
            })
            secondaryLabel(step, visual)?.let { secondary ->
                textColumn.addView(TextView(activity).apply {
                    text = secondary
                    textSize = 10.5f
                    setTextColor(accent)
                    includeFontPadding = false
                    maxLines = 1
                })
            }
            holder.addView(textColumn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return holder
        }

        private fun primaryLabel(step: RouteDisplayStep, visual: TransitVisualCatalog.Visual): String = when (step.kind) {
            RouteDisplayKind.WALK -> if (step.walkMeters > 0) "Пешком ${step.walkMeters} м" else "Пешком"
            RouteDisplayKind.TRANSFER -> step.instruction ?: if (step.walkMeters > 0) "Переход ${step.walkMeters} м" else "Пересадка"
            RouteDisplayKind.TRANSIT -> when (step.mode) {
                TransportMode.METRO -> "Метро ${visual.badge}".trim()
                TransportMode.MCC -> "МЦК 14"
                TransportMode.MCD -> visual.badge.ifBlank { "МЦД" }
                TransportMode.BUS -> visual.badge.ifBlank { "Автобус" }
                TransportMode.TRAM -> visual.badge.ifBlank { "Трамвай" }
                TransportMode.TRAIN -> visual.badge.ifBlank { "Поезд" }
                else -> visual.label
            }
        }

        private fun secondaryLabel(step: RouteDisplayStep, visual: TransitVisualCatalog.Visual): String? {
            if (step.kind == RouteDisplayKind.TRANSFER) {
                return when (step.transferKind) {
                    RouteTransferKind.GROUND -> "наземный переход"
                    RouteTransferKind.UNDERGROUND -> "подземный переход"
                    RouteTransferKind.OVERGROUND -> "надземный переход"
                    RouteTransferKind.METRO_EXIT -> "выход к следующему этапу"
                    RouteTransferKind.INTERCHANGE, null -> null
                }
            }
            if (step.kind != RouteDisplayKind.TRANSIT) return null
            val lineName = step.lineName?.trim().orEmpty()
            if (lineName.isBlank()) return visual.label.takeUnless { primaryLabel(step, visual).contains(it) }
            val compact = lineName.substringAfter(" · ", lineName).trim()
            return compact.take(34).takeIf(String::isNotBlank)
        }

        private fun decorateCurrentCard(steps: List<RouteDisplayStep>) {
            val card = directLinearChildren(panel).firstOrNull { containsText(it, "Текущий этап") } ?: return
            val title = allTextViews(card).firstOrNull { view ->
                val text = view.text?.toString().orEmpty()
                text.isNotBlank() && !text.startsWith("Текущий этап") &&
                    !text.startsWith("До ") && !text.startsWith("Затем") && !text.startsWith("Этап ")
            } ?: return
            val step = steps.firstOrNull { matchesTitle(it, title.text?.toString().orEmpty()) } ?: steps.firstOrNull() ?: return
            title.text = timelineTitle(step)
            title.setTextColor(transferColor(step) ?: TransitVisualCatalog.forStep(step).color)
        }

        private fun decorateTimeline(steps: List<RouteDisplayStep>) {
            val rows = directLinearChildren(panel).filter {
                it.contentDescription?.toString()?.startsWith("Этап маршрута:") == true
            }
            rows.zip(steps).forEach { (row, step) ->
                val accent = transferColor(step) ?: TransitVisualCatalog.forStep(step).color
                val rail = row.getChildAt(0) as? FrameLayout
                rail?.getChildAt(1)?.background = oval(accent, Color.WHITE, dp(2))
                val card = row.getChildAt(1) as? LinearLayout ?: return@forEach
                card.background = rounded(
                    blend(Color.WHITE, accent, 0.035f),
                    blend(Color.WHITE, accent, 0.72f),
                    dp(1),
                    dp(14).toFloat()
                )
                val titleRow = card.getChildAt(0) as? LinearLayout
                val title = titleRow?.getChildAt(1) as? TextView
                title?.apply {
                    text = timelineTitle(step)
                    setTextColor(accent)
                }
            }
        }

        private fun timelineTitle(step: RouteDisplayStep): String = when (step.kind) {
            RouteDisplayKind.WALK, RouteDisplayKind.TRANSFER -> step.instruction ?: "Пешком ${step.walkMeters} м"
            RouteDisplayKind.TRANSIT -> {
                val visual = TransitVisualCatalog.forStep(step)
                val prefix = when (step.mode) {
                    TransportMode.METRO -> "Метро"
                    TransportMode.MCC -> "МЦК"
                    TransportMode.MCD -> "МЦД"
                    TransportMode.BUS -> "Автобус"
                    TransportMode.TRAM -> "Трамвай"
                    TransportMode.TRAIN -> "Поезд"
                    else -> visual.label
                }
                "$prefix ${visual.badge}".trim()
            }
        }

        private fun transferColor(step: RouteDisplayStep): Int? = when (step.transferKind) {
            RouteTransferKind.GROUND -> Color.rgb(22, 102, 199)
            RouteTransferKind.UNDERGROUND -> Color.rgb(255, 193, 7)
            RouteTransferKind.OVERGROUND -> Color.rgb(28, 100, 194)
            RouteTransferKind.METRO_EXIT -> Color.rgb(22, 102, 199)
            RouteTransferKind.INTERCHANGE -> Color.rgb(83, 97, 122)
            null -> null
        }

        private fun matchesTitle(step: RouteDisplayStep, title: String): Boolean {
            val normalized = normalize(title)
            if (step.instruction?.let(::normalize)?.let(normalized::contains) == true) return true
            val visual = TransitVisualCatalog.forStep(step)
            return visual.badge.isNotBlank() && normalized.contains(normalize(visual.badge))
        }

        private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^а-яa-z0-9]+"), "")

        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }

        private fun allTextViews(view: View): List<TextView> = descendants(view).filterIsInstance<TextView>().toList()

        private fun directLinearChildren(group: ViewGroup): List<LinearLayout> =
            (0 until group.childCount).mapNotNull { group.getChildAt(it) as? LinearLayout }

        private fun containsText(view: View, needle: String): Boolean =
            allTextViews(view).any { it.text?.toString()?.contains(needle, ignoreCase = true) == true }

        private fun rounded(color: Int, stroke: Int, strokeWidth: Int, radius: Float) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
            setStroke(strokeWidth, stroke)
        }

        private fun oval(color: Int, stroke: Int, strokeWidth: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(strokeWidth, stroke)
        }

        private fun blend(base: Int, accent: Int, amount: Float): Int {
            fun c(a: Int, b: Int): Int = (a + (b - a) * amount).toInt().coerceIn(0, 255)
            return Color.rgb(c(Color.red(base), Color.red(accent)), c(Color.green(base), Color.green(accent)), c(Color.blue(base), Color.blue(accent)))
        }

        private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    }

    /** Crisp route pictograms modelled after the supplied Moscow transport reference. */
    private class JourneyGlyphView(
        context: android.content.Context,
        private val step: RouteDisplayStep,
        private val accent: Int,
        private val foreground: Int
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            when (step.kind) {
                RouteDisplayKind.WALK -> drawWalker(canvas, w, h)
                RouteDisplayKind.TRANSFER -> drawTransfer(canvas, w, h)
                RouteDisplayKind.TRANSIT -> drawTransit(canvas, w, h)
            }
        }

        private fun drawWalker(canvas: Canvas, w: Float, h: Float) {
            paint.color = accent
            paint.style = Paint.Style.FILL
            canvas.drawCircle(w * 0.54f, h * 0.20f, w * 0.085f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w * 0.095f
            canvas.drawLine(w * 0.50f, h * 0.33f, w * 0.45f, h * 0.58f, paint)
            canvas.drawLine(w * 0.48f, h * 0.39f, w * 0.29f, h * 0.51f, paint)
            canvas.drawLine(w * 0.48f, h * 0.40f, w * 0.67f, h * 0.49f, paint)
            canvas.drawLine(w * 0.45f, h * 0.58f, w * 0.26f, h * 0.82f, paint)
            canvas.drawLine(w * 0.45f, h * 0.58f, w * 0.65f, h * 0.80f, paint)
        }

        private fun drawTransfer(canvas: Canvas, w: Float, h: Float) {
            paint.color = accent
            when (step.transferKind) {
                RouteTransferKind.GROUND -> {
                    paint.style = Paint.Style.FILL
                    for (i in 0..3) {
                        val left = w * (0.12f + i * 0.20f)
                        canvas.drawRect(left, h * 0.60f, left + w * 0.12f, h * 0.72f, paint)
                    }
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = w * 0.07f
                    canvas.drawCircle(w * 0.52f, h * 0.23f, w * 0.06f, paint)
                    canvas.drawLine(w * 0.50f, h * 0.31f, w * 0.46f, h * 0.50f, paint)
                    canvas.drawLine(w * 0.46f, h * 0.50f, w * 0.34f, h * 0.61f, paint)
                    canvas.drawLine(w * 0.46f, h * 0.50f, w * 0.59f, h * 0.59f, paint)
                }
                RouteTransferKind.UNDERGROUND, RouteTransferKind.OVERGROUND -> drawStairs(canvas, w, h)
                RouteTransferKind.METRO_EXIT -> drawExitArrow(canvas, w, h)
                RouteTransferKind.INTERCHANGE, null -> drawSwap(canvas, w, h)
            }
        }

        private fun drawStairs(canvas: Canvas, w: Float, h: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w * 0.075f
            val down = step.transferKind == RouteTransferKind.UNDERGROUND
            val path = Path()
            if (down) {
                path.moveTo(w * 0.18f, h * 0.30f)
                path.lineTo(w * 0.38f, h * 0.30f)
                path.lineTo(w * 0.38f, h * 0.47f)
                path.lineTo(w * 0.58f, h * 0.47f)
                path.lineTo(w * 0.58f, h * 0.64f)
                path.lineTo(w * 0.79f, h * 0.64f)
            } else {
                path.moveTo(w * 0.18f, h * 0.68f)
                path.lineTo(w * 0.38f, h * 0.68f)
                path.lineTo(w * 0.38f, h * 0.51f)
                path.lineTo(w * 0.58f, h * 0.51f)
                path.lineTo(w * 0.58f, h * 0.34f)
                path.lineTo(w * 0.79f, h * 0.34f)
            }
            canvas.drawPath(path, paint)
        }

        private fun drawExitArrow(canvas: Canvas, w: Float, h: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w * 0.08f
            canvas.drawRect(w * 0.18f, h * 0.20f, w * 0.52f, h * 0.80f, paint)
            canvas.drawLine(w * 0.40f, h * 0.50f, w * 0.82f, h * 0.50f, paint)
            canvas.drawLine(w * 0.68f, h * 0.36f, w * 0.82f, h * 0.50f, paint)
            canvas.drawLine(w * 0.68f, h * 0.64f, w * 0.82f, h * 0.50f, paint)
        }

        private fun drawSwap(canvas: Canvas, w: Float, h: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w * 0.075f
            canvas.drawLine(w * 0.20f, h * 0.39f, w * 0.77f, h * 0.39f, paint)
            canvas.drawLine(w * 0.65f, h * 0.27f, w * 0.78f, h * 0.39f, paint)
            canvas.drawLine(w * 0.65f, h * 0.51f, w * 0.78f, h * 0.39f, paint)
            canvas.drawLine(w * 0.80f, h * 0.64f, w * 0.23f, h * 0.64f, paint)
            canvas.drawLine(w * 0.35f, h * 0.52f, w * 0.22f, h * 0.64f, paint)
            canvas.drawLine(w * 0.35f, h * 0.76f, w * 0.22f, h * 0.64f, paint)
        }

        private fun drawTransit(canvas: Canvas, w: Float, h: Float) {
            val visual = TransitVisualCatalog.forStep(step)
            paint.style = Paint.Style.FILL
            paint.color = accent
            canvas.drawCircle(w * 0.5f, h * 0.5f, min(w, h) * 0.47f, paint)
            paint.color = if (step.mode == TransportMode.METRO || step.mode == TransportMode.MCC) visual.foreground else foreground
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = when {
                visual.badge.length <= 2 -> w * 0.43f
                visual.badge.length <= 4 -> w * 0.28f
                else -> w * 0.20f
            }
            val baseline = h * 0.5f - (paint.ascent() + paint.descent()) / 2f
            val value = visual.badge.take(6).ifBlank {
                when (step.mode) {
                    TransportMode.BUS -> "A"
                    TransportMode.TRAM -> "T"
                    TransportMode.TRAIN -> "РЖД"
                    TransportMode.MCD -> "D"
                    else -> "M"
                }
            }
            canvas.drawText(value, w * 0.5f, baseline, paint)
        }
    }

    private const val TAG_V2_STRIP = "vh-transit-strip-v2"
    private const val TAG_ROUTE_ID_KEY = 0x7f0f21d2
}
