package app.humanrouter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

internal object JourneySceneTimeline {
    const val LOOP_MS = 12_000L

    enum class Stage(val label: String) {
        RUN_TO_STOP("Идём к остановке"),
        WAIT_AT_STOP("Ждём транспорт · около 2 сек"),
        BUS_TO_METRO("Едем до метро"),
        DESCEND_TO_PLATFORM("Спускаемся на платформу"),
        TRAIN_DEPARTS("Продолжаем путь на поезде")
    }

    data class Frame(val stage: Stage, val progress: Float)

    fun frameAt(elapsedMs: Long): Frame {
        val t = ((elapsedMs % LOOP_MS) + LOOP_MS) % LOOP_MS
        return when (t) {
            in 0L until 2_600L -> Frame(Stage.RUN_TO_STOP, t / 2_600f)
            in 2_600L until 4_600L -> Frame(Stage.WAIT_AT_STOP, (t - 2_600L) / 2_000f)
            in 4_600L until 7_500L -> Frame(Stage.BUS_TO_METRO, (t - 4_600L) / 2_900f)
            in 7_500L until 9_000L -> Frame(Stage.DESCEND_TO_PLATFORM, (t - 7_500L) / 1_500f)
            else -> Frame(Stage.TRAIN_DEPARTS, (t - 9_000L) / 3_000f)
        }
    }
}

/**
 * A continuous journey scene used while route data is prepared.
 *
 * Unlike the old frame swap, this is one coherent animation: the passenger approaches a stop,
 * waits for roughly two seconds, boards a bus, rides to metro, descends to the platform and leaves
 * on a train. Everything is drawn as vector geometry so it stays crisp on every density.
 */
internal class JourneySceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var startedAt = 0L
    private var running = false
    private var lastStage: JourneySceneTimeline.Stage? = null

    var onStageChanged: ((JourneySceneTimeline.Stage) -> Unit)? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startedAt = SystemClock.uptimeMillis()
        running = true
        invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val frame = JourneySceneTimeline.frameAt(SystemClock.uptimeMillis() - startedAt)
        if (frame.stage != lastStage) {
            lastStage = frame.stage
            onStageChanged?.invoke(frame.stage)
        }

        drawScene(canvas, frame)
        if (running) postInvalidateOnAnimation()
    }

    private fun drawScene(canvas: Canvas, frame: JourneySceneTimeline.Frame) {
        val w = width.toFloat()
        val h = height.toFloat()
        val groundY = h * 0.48f
        val railY = h * 0.82f
        val stopX = w * 0.29f
        val metroX = w * 0.73f

        paint.color = Color.rgb(238, 243, 248)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), dp(16f), dp(16f), paint)

        paint.color = Color.rgb(216, 224, 234)
        canvas.drawRect(0f, groundY + dp(7f), w, groundY + dp(9f), paint)
        paint.color = Color.rgb(205, 214, 225)
        canvas.drawRect(0f, railY, w, railY + dp(2f), paint)
        canvas.drawRect(0f, railY + dp(7f), w, railY + dp(9f), paint)

        drawStop(canvas, stopX, groundY)
        drawMetroEntrance(canvas, metroX, groundY)
        drawStairs(canvas, metroX, groundY + dp(8f), railY - dp(10f))

        when (frame.stage) {
            JourneySceneTimeline.Stage.RUN_TO_STOP -> {
                val x = lerp(w * 0.08f, stopX - dp(12f), ease(frame.progress))
                drawPerson(canvas, x, groundY - dp(2f), walkingPhase = frame.progress * 6f)
            }
            JourneySceneTimeline.Stage.WAIT_AT_STOP -> {
                drawPerson(canvas, stopX - dp(12f), groundY - dp(2f), walkingPhase = 0f)
                val approach = ((frame.progress - 0.38f) / 0.62f).coerceIn(0f, 1f)
                val busX = lerp(w + dp(48f), stopX + dp(28f), ease(approach))
                drawBus(canvas, busX, groundY, passengerVisible = approach > 0.93f)
            }
            JourneySceneTimeline.Stage.BUS_TO_METRO -> {
                val busX = lerp(stopX + dp(28f), metroX - dp(16f), ease(frame.progress))
                drawBus(canvas, busX, groundY, passengerVisible = true)
            }
            JourneySceneTimeline.Stage.DESCEND_TO_PLATFORM -> {
                drawBus(canvas, metroX - dp(16f), groundY, passengerVisible = false)
                val p = ease(frame.progress)
                val x = lerp(metroX - dp(6f), metroX + dp(22f), p)
                val y = lerp(groundY - dp(2f), railY - dp(12f), p)
                drawPerson(canvas, x, y, walkingPhase = frame.progress * 5f, scale = 0.86f)
            }
            JourneySceneTimeline.Stage.TRAIN_DEPARTS -> {
                val trainX = lerp(-w * 0.28f, w * 1.23f, ease(frame.progress))
                drawTrain(canvas, trainX, railY - dp(2f), passengerVisible = frame.progress > 0.12f)
                if (frame.progress < 0.12f) {
                    drawPerson(canvas, metroX + dp(22f), railY - dp(12f), walkingPhase = 0f, scale = 0.86f)
                }
            }
        }
    }

    private fun drawStop(canvas: Canvas, x: Float, groundY: Float) {
        stroke.color = Color.rgb(68, 80, 99)
        stroke.strokeWidth = dp(2f)
        canvas.drawLine(x, groundY - dp(31f), x, groundY + dp(7f), stroke)
        paint.color = Color.WHITE
        canvas.drawCircle(x, groundY - dp(31f), dp(9f), paint)
        stroke.color = Color.rgb(40, 123, 255)
        stroke.strokeWidth = dp(2.4f)
        canvas.drawCircle(x, groundY - dp(31f), dp(8f), stroke)
        paint.color = Color.rgb(40, 123, 255)
        canvas.drawCircle(x, groundY - dp(31f), dp(2.8f), paint)
    }

    private fun drawMetroEntrance(canvas: Canvas, x: Float, groundY: Float) {
        paint.color = Color.WHITE
        canvas.drawRoundRect(
            RectF(x - dp(16f), groundY - dp(33f), x + dp(16f), groundY + dp(4f)),
            dp(6f), dp(6f), paint
        )
        stroke.color = Color.rgb(220, 43, 61)
        stroke.strokeWidth = dp(2f)
        canvas.drawRoundRect(
            RectF(x - dp(16f), groundY - dp(33f), x + dp(16f), groundY + dp(4f)),
            dp(6f), dp(6f), stroke
        )
        paint.color = Color.rgb(220, 43, 61)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(15f)
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText("M", x, groundY - dp(11f), paint)
        paint.typeface = android.graphics.Typeface.DEFAULT
    }

    private fun drawStairs(canvas: Canvas, x: Float, topY: Float, bottomY: Float) {
        stroke.color = Color.rgb(151, 163, 180)
        stroke.strokeWidth = dp(1.5f)
        val left = x + dp(10f)
        val right = x + dp(31f)
        val steps = 5
        for (i in 0 until steps) {
            val p0 = i / steps.toFloat()
            val p1 = (i + 1) / steps.toFloat()
            val y0 = lerp(topY, bottomY, p0)
            val y1 = lerp(topY, bottomY, p1)
            val sx = lerp(left, right, p0)
            val ex = lerp(left, right, p1)
            canvas.drawLine(sx, y0, ex, y0, stroke)
            canvas.drawLine(ex, y0, ex, y1, stroke)
        }
    }

    private fun drawPerson(
        canvas: Canvas,
        x: Float,
        footY: Float,
        walkingPhase: Float,
        scale: Float = 1f
    ) {
        val s = scale
        val sway = sin(walkingPhase * PI).toFloat() * dp(3.2f) * s
        paint.color = Color.rgb(31, 41, 55)
        canvas.drawCircle(x, footY - dp(22f) * s, dp(4.2f) * s, paint)
        stroke.color = Color.rgb(31, 41, 55)
        stroke.strokeWidth = dp(2.5f) * s
        canvas.drawLine(x, footY - dp(17f) * s, x, footY - dp(7f) * s, stroke)
        canvas.drawLine(x, footY - dp(14f) * s, x - sway, footY - dp(8f) * s, stroke)
        canvas.drawLine(x, footY - dp(14f) * s, x + sway, footY - dp(9f) * s, stroke)
        canvas.drawLine(x, footY - dp(7f) * s, x - sway, footY, stroke)
        canvas.drawLine(x, footY - dp(7f) * s, x + sway, footY, stroke)
    }

    private fun drawBus(canvas: Canvas, centerX: Float, groundY: Float, passengerVisible: Boolean) {
        val left = centerX - dp(28f)
        val top = groundY - dp(28f)
        val right = centerX + dp(28f)
        val bottom = groundY
        paint.color = Color.rgb(40, 123, 255)
        canvas.drawRoundRect(RectF(left, top, right, bottom), dp(7f), dp(7f), paint)
        paint.color = Color.rgb(225, 240, 255)
        canvas.drawRoundRect(RectF(left + dp(7f), top + dp(5f), right - dp(7f), top + dp(15f)), dp(3f), dp(3f), paint)
        paint.color = Color.WHITE
        canvas.drawRect(right - dp(12f), top + dp(17f), right - dp(5f), bottom - dp(4f), paint)
        paint.color = Color.rgb(43, 51, 64)
        canvas.drawCircle(left + dp(12f), bottom + dp(1f), dp(4.5f), paint)
        canvas.drawCircle(right - dp(12f), bottom + dp(1f), dp(4.5f), paint)
        if (passengerVisible) {
            paint.color = Color.rgb(31, 41, 55)
            canvas.drawCircle(centerX - dp(5f), top + dp(10f), dp(2.3f), paint)
        }
    }

    private fun drawTrain(canvas: Canvas, centerX: Float, railY: Float, passengerVisible: Boolean) {
        val left = centerX - dp(43f)
        val top = railY - dp(30f)
        val right = centerX + dp(43f)
        val bottom = railY - dp(3f)
        paint.color = Color.rgb(216, 33, 53)
        canvas.drawRoundRect(RectF(left, top, right, bottom), dp(9f), dp(9f), paint)
        paint.color = Color.rgb(240, 247, 252)
        val windowTop = top + dp(5f)
        for (i in 0..3) {
            val wx = left + dp(8f) + i * dp(18f)
            canvas.drawRoundRect(RectF(wx, windowTop, wx + dp(12f), windowTop + dp(10f)), dp(2f), dp(2f), paint)
        }
        paint.color = Color.rgb(47, 55, 69)
        canvas.drawCircle(left + dp(15f), bottom + dp(1f), dp(3.8f), paint)
        canvas.drawCircle(right - dp(15f), bottom + dp(1f), dp(3.8f), paint)
        if (passengerVisible) {
            paint.color = Color.rgb(31, 41, 55)
            canvas.drawCircle(left + dp(32f), windowTop + dp(5f), dp(2f), paint)
        }
    }

    private fun ease(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    private fun lerp(a: Float, b: Float, p: Float): Float = a + (b - a) * p.coerceIn(0f, 1f)
    private fun dp(value: Float): Float = value * density
}
