package app.humanrouter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

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
 * One continuous journey scene shown while route data is prepared.
 *
 * The timeline stays deliberately simple and deterministic, but the passenger and transport are
 * now the real illustrated journey assets shipped with the app instead of placeholder Canvas
 * people/rectangles. The stop is visibly empty after boarding, matching the product sequence.
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

    private val person = asset(R.drawable.journey_person)
    private val occupiedStop = asset(R.drawable.journey_stop)
    private val bus = asset(R.drawable.journey_bus)
    private val metroTrain = asset(R.drawable.journey_metro)
    private val train = asset(R.drawable.journey_train)

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
        val groundY = h * 0.64f
        val railY = h * 0.88f
        val stopX = w * 0.31f
        val metroX = w * 0.73f

        drawBackground(canvas, w, h, groundY, railY)
        drawMetroEntrance(canvas, metroX, groundY)
        drawStairs(canvas, metroX, groundY + dp(4f), railY - dp(7f))

        when (frame.stage) {
            JourneySceneTimeline.Stage.RUN_TO_STOP -> {
                drawEmptyStop(canvas, stopX, groundY)
                val p = ease(frame.progress)
                val x = lerp(w * 0.05f, stopX - dp(16f), p)
                val bounce = if (frame.progress in 0.04f..0.96f) {
                    kotlin.math.sin(frame.progress * Math.PI * 8.0).toFloat() * dp(1.4f)
                } else 0f
                drawAsset(canvas, person, x, groundY + dp(5f) + bounce, dp(49f))
            }

            JourneySceneTimeline.Stage.WAIT_AT_STOP -> {
                // This artwork contains the waiting passenger. It replaces the moving person only
                // after arrival, so there is never a second static person waiting at the stop.
                drawAsset(canvas, occupiedStop, stopX, groundY + dp(12f), dp(62f))
                val approach = ((frame.progress - 0.32f) / 0.68f).coerceIn(0f, 1f)
                if (approach > 0f) {
                    val x = lerp(w + dp(48f), stopX + dp(38f), ease(approach))
                    drawAsset(canvas, bus, x, groundY + dp(15f), dp(79f))
                }
            }

            JourneySceneTimeline.Stage.BUS_TO_METRO -> {
                // Boarding is complete: the shelter is intentionally empty from this point on.
                drawEmptyStop(canvas, stopX, groundY)
                val x = lerp(stopX + dp(38f), metroX - dp(23f), ease(frame.progress))
                drawAsset(canvas, bus, x, groundY + dp(15f), dp(79f))
            }

            JourneySceneTimeline.Stage.DESCEND_TO_PLATFORM -> {
                drawEmptyStop(canvas, stopX, groundY)
                val busAlpha = ((1f - frame.progress * 1.5f).coerceIn(0f, 1f) * 255).roundToInt()
                if (busAlpha > 0) {
                    drawAsset(canvas, bus, metroX - dp(23f), groundY + dp(15f), dp(79f), busAlpha)
                }

                drawAsset(canvas, metroTrain, metroX + dp(17f), h + dp(12f), dp(84f), 190)
                val p = ease(frame.progress)
                val x = lerp(metroX - dp(7f), metroX + dp(19f), p)
                val y = lerp(groundY + dp(5f), railY + dp(9f), p)
                val size = lerp(dp(47f), dp(38f), p)
                drawAsset(canvas, person, x, y, size)
            }

            JourneySceneTimeline.Stage.TRAIN_DEPARTS -> {
                drawEmptyStop(canvas, stopX, groundY)
                val p = ease(frame.progress)
                val x = lerp(metroX + dp(10f), w + dp(62f), p)
                drawAsset(canvas, train, x, h + dp(10f), dp(96f))
            }
        }
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float, groundY: Float, railY: Float) {
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            h,
            Color.rgb(250, 252, 255),
            Color.rgb(231, 238, 247),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(RectF(0f, 0f, w, h), dp(16f), dp(16f), paint)
        paint.shader = null

        paint.color = Color.rgb(211, 220, 231)
        canvas.drawRoundRect(
            RectF(dp(8f), groundY + dp(8f), w - dp(8f), groundY + dp(10f)),
            dp(1f),
            dp(1f),
            paint
        )

        paint.color = Color.rgb(193, 204, 219)
        canvas.drawRect(dp(5f), railY, w - dp(5f), railY + dp(1.5f), paint)
        canvas.drawRect(dp(5f), railY + dp(6f), w - dp(5f), railY + dp(7.5f), paint)
    }

    private fun drawEmptyStop(canvas: Canvas, x: Float, groundY: Float) {
        val left = x - dp(17f)
        val right = x + dp(17f)
        val roofY = groundY - dp(26f)

        paint.color = Color.argb(210, 235, 242, 249)
        canvas.drawRoundRect(
            RectF(left, roofY, right, groundY + dp(3f)),
            dp(4f),
            dp(4f),
            paint
        )
        stroke.color = Color.rgb(111, 129, 153)
        stroke.strokeWidth = dp(1.6f)
        canvas.drawLine(left, roofY, right, roofY, stroke)
        canvas.drawLine(left + dp(2f), roofY, left + dp(2f), groundY + dp(5f), stroke)
        canvas.drawLine(right - dp(2f), roofY, right - dp(2f), groundY + dp(5f), stroke)

        paint.color = Color.rgb(115, 134, 157)
        canvas.drawRoundRect(
            RectF(x - dp(10f), groundY - dp(7f), x + dp(7f), groundY - dp(4f)),
            dp(1.5f),
            dp(1.5f),
            paint
        )

        val poleX = right + dp(7f)
        stroke.color = Color.rgb(70, 88, 111)
        stroke.strokeWidth = dp(1.8f)
        canvas.drawLine(poleX, roofY - dp(1f), poleX, groundY + dp(5f), stroke)
        paint.color = Color.WHITE
        canvas.drawCircle(poleX, roofY, dp(6f), paint)
        stroke.color = Color.rgb(40, 123, 255)
        stroke.strokeWidth = dp(2f)
        canvas.drawCircle(poleX, roofY, dp(5.5f), stroke)
    }

    private fun drawMetroEntrance(canvas: Canvas, x: Float, groundY: Float) {
        paint.color = Color.WHITE
        canvas.drawRoundRect(
            RectF(x - dp(14f), groundY - dp(31f), x + dp(14f), groundY + dp(2f)),
            dp(6f),
            dp(6f),
            paint
        )
        stroke.color = Color.rgb(221, 39, 59)
        stroke.strokeWidth = dp(1.8f)
        canvas.drawRoundRect(
            RectF(x - dp(14f), groundY - dp(31f), x + dp(14f), groundY + dp(2f)),
            dp(6f),
            dp(6f),
            stroke
        )
        paint.color = Color.rgb(221, 39, 59)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(13f)
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText("M", x, groundY - dp(11f), paint)
        paint.typeface = android.graphics.Typeface.DEFAULT
    }

    private fun drawStairs(canvas: Canvas, x: Float, topY: Float, bottomY: Float) {
        stroke.color = Color.rgb(149, 164, 183)
        stroke.strokeWidth = dp(1.2f)
        val left = x + dp(9f)
        val right = x + dp(29f)
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

    private fun drawAsset(
        canvas: Canvas,
        drawable: Drawable,
        centerX: Float,
        bottomY: Float,
        size: Float,
        alpha: Int = 255
    ) {
        val half = size / 2f
        val left = centerX - half
        val top = bottomY - size
        val oldAlpha = drawable.alpha
        drawable.alpha = alpha.coerceIn(0, 255)
        drawable.setBounds(
            left.roundToInt(),
            top.roundToInt(),
            (left + size).roundToInt(),
            bottomY.roundToInt()
        )
        drawable.draw(canvas)
        drawable.alpha = oldAlpha
    }

    private fun asset(id: Int): Drawable =
        requireNotNull(ContextCompat.getDrawable(context, id)) { "Missing journey drawable $id" }.mutate()

    private fun ease(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    private fun lerp(a: Float, b: Float, p: Float): Float = a + (b - a) * p.coerceIn(0f, 1f)
    private fun dp(value: Float): Float = value * density
}
