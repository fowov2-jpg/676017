package app.humanrouter

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Animated startup presentation that never turns the first install into a blocking downloader.
 *
 * The full-screen layer is only a short visual hand-off. If the Moscow runtime is not installed
 * yet, the app opens the map automatically while WorkManager keeps downloading in the background;
 * MainActivity's compact runtime panel remains responsible for ongoing progress and retry UI.
 */
internal object StartupExperienceV2 {
    private val controllers = WeakHashMap<MainActivity, Controller>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (BuildConfig.DEBUG && activity.intent.hasExtra("qa_screen")) return
        if (controllers.containsKey(activity)) return
        val root = activity.findViewById<ViewGroup?>(R.id.root) ?: return
        controllers[activity] = Controller(activity, root)
    }

    @Synchronized
    fun destroy(activity: MainActivity) {
        controllers.remove(activity)?.destroy()
    }

    private class Controller(
        private val activity: MainActivity,
        private val root: ViewGroup
    ) {
        private val density = activity.resources.displayMetrics.density
        private val worker = Executors.newSingleThreadExecutor()
        private val startedAt = SystemClock.uptimeMillis()

        private var destroyed = false
        private var finishing = false
        private var localRuntimeReady = false
        private var progressAnimator: ObjectAnimator? = null
        private var orbitAnimator: ObjectAnimator? = null
        private var pulseAnimator: ValueAnimator? = null

        private val overlay = FrameLayout(activity).apply {
            setBackgroundColor(BACKGROUND)
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Подготовка приложения. Нажмите, чтобы открыть карту сразу"
            elevation = dp(90).toFloat()
            setOnClickListener {
                if (!localRuntimeReady) {
                    handoffToInteractive("Транспортные данные продолжат загружаться в фоне")
                }
            }
        }

        private val art = ImageView(activity).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "ВремяХодом"
        }

        private val orbit = StartupOrbitView(activity)

        private val title = TextView(activity).apply {
            text = "ВремяХодом запускается"
            gravity = Gravity.CENTER
            textSize = 22f
            setTypeface(typeface, Typeface.NORMAL)
            setTextColor(Color.WHITE)
            includeFontPadding = false
        }

        private val status = TextView(activity).apply {
            text = "Проверяем локальные ресурсы…"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.rgb(190, 208, 233))
            includeFontPadding = false
        }

        private val details = TextView(activity).apply {
            gravity = Gravity.CENTER
            textSize = 11.5f
            setTextColor(Color.rgb(118, 145, 181))
            includeFontPadding = false
        }

        private val hint = TextView(activity).apply {
            text = "Карта и поиск откроются сразу · ресурсы догрузятся в фоне"
            gravity = Gravity.CENTER
            textSize = 11.5f
            setTextColor(Color.rgb(102, 180, 236))
            includeFontPadding = false
            visibility = View.INVISIBLE
        }

        private val percent = TextView(activity).apply {
            text = "0%"
            gravity = Gravity.END
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(151, 205, 255))
            includeFontPadding = false
        }

        private val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = 0
            progressTintList = ColorStateList.valueOf(Color.rgb(24, 164, 255))
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(20, 44, 77))
        }

        init {
            buildUi()
            loadArtwork()
            root.addView(
                overlay,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            configureBarsForStartup()
            startMotion()
            observeRuntimeWork()
            scanLocalRuntime()

            // First install must never wait for the large runtime package. The user gets the map
            // immediately; the existing compact runtime panel keeps showing true background progress.
            overlay.postDelayed({
                if (!destroyed && !finishing && !localRuntimeReady) {
                    handoffToInteractive("Транспортные данные догружаются в фоне")
                }
            }, FIRST_INSTALL_MAX_BLOCK_MS)
        }

        fun destroy() {
            destroyed = true
            stopMotion()
            progressAnimator?.cancel()
            worker.shutdownNow()
            if (overlay.parent === root) root.removeView(overlay)
        }

        private fun buildUi() {
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(28), dp(18), dp(28), dp(18))
            }
            content.addView(Space(activity), LinearLayout.LayoutParams(1, 0, 0.64f))

            val artStage = FrameLayout(activity).apply {
                addView(
                    art,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                addView(
                    orbit,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
            }
            content.addView(
                artStage,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(322)).apply {
                    leftMargin = dp(4)
                    rightMargin = dp(4)
                }
            )
            content.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5)
            })
            content.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
            content.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply {
                topMargin = dp(20)
            })
            content.addView(percent, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
            content.addView(details, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            })
            content.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
            content.addView(Space(activity), LinearLayout.LayoutParams(1, 0, 1f))

            overlay.addView(
                content,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }

        private fun loadArtwork() {
            runCatching {
                val encoded = activity.resources.openRawResource(R.raw.startup_art)
                    .bufferedReader()
                    .use { it.readText() }
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()?.let(art::setImageBitmap)
                ?: art.setImageResource(R.mipmap.ic_vremyahodom_logo)
        }

        private fun startMotion() {
            art.alpha = 0.86f
            art.scaleX = 0.975f
            art.scaleY = 0.975f

            pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 820L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { animator ->
                    val t = animator.animatedFraction
                    art.alpha = 0.86f + (0.14f * t)
                    art.scaleX = 0.975f + (0.035f * t)
                    art.scaleY = 0.975f + (0.035f * t)
                    art.translationY = -dp(3) * t
                }
                start()
            }

            orbitAnimator = ObjectAnimator.ofFloat(orbit, View.ROTATION, 0f, 360f).apply {
                duration = 1_900L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }

        private fun stopMotion() {
            orbitAnimator?.cancel()
            pulseAnimator?.cancel()
            art.animate().cancel()
        }

        private fun observeRuntimeWork() {
            WorkManager.getInstance(activity)
                .getWorkInfosForUniqueWorkLiveData(RuntimeDownloadWorker.UNIQUE_WORK)
                .observe(activity) { infos ->
                    if (destroyed || finishing) return@observe
                    val info = infos.lastOrNull() ?: return@observe
                    when (info.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                            if (!localRuntimeReady) {
                                hint.visibility = View.VISIBLE
                                showState(
                                    8,
                                    "Ожидаем сеть для транспортных данных…",
                                    "Картой и поиском уже можно пользоваться"
                                )
                            }
                        }
                        WorkInfo.State.RUNNING -> {
                            if (localRuntimeReady) return@observe
                            hint.visibility = View.VISIBLE
                            val realPercent = info.progress
                                .getInt(RuntimeDownloadWorker.KEY_PERCENT, 0)
                                .coerceIn(0, 100)
                            val downloaded = info.progress.getLong(RuntimeDownloadWorker.KEY_DOWNLOADED, 0L)
                            val total = info.progress.getLong(RuntimeDownloadWorker.KEY_TOTAL, 0L)
                            val message = info.progress.getString(RuntimeDownloadWorker.KEY_MESSAGE)
                                ?.takeIf(String::isNotBlank)
                                ?: "Загружаем транспортные данные…"
                            val mapped = 10 + (realPercent * 74 / 100)
                            val byteText = if (total > 0L) {
                                "${formatBytes(downloaded)} из ${formatBytes(total)} · $realPercent% ресурсов"
                            } else {
                                "$realPercent% транспортных данных"
                            }
                            showState(mapped, message, byteText)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            showState(86, "Проверяем загруженные ресурсы…", "Загрузка завершена")
                            scanLocalRuntime()
                        }
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                            hint.visibility = View.VISIBLE
                            showState(
                                max(progress.progress, 12),
                                "Карта доступна без ожидания",
                                "Транспортные данные продолжим загружать при подключении"
                            )
                        }
                    }
                }
        }

        private fun scanLocalRuntime() {
            worker.execute {
                val runtimeRoot = File(activity.filesDir, "runtime")
                val manifestFile = File(runtimeRoot, "manifest.json")
                if (!manifestFile.isFile) {
                    activity.runOnUiThread {
                        if (destroyed || finishing) return@runOnUiThread
                        localRuntimeReady = false
                        hint.visibility = View.VISIBLE
                        showState(
                            6,
                            "Запускаем карту…",
                            "Транспортный пакет установится в фоне"
                        )
                    }
                    return@execute
                }

                val ready = runCatching {
                    val manifest = JSONObject(manifestFile.readText())
                    val packs = manifest.optJSONArray("packs")
                    val required = buildList<String> {
                        if (packs != null) {
                            for (i in 0 until packs.length()) {
                                val pack = packs.optJSONObject(i) ?: continue
                                if (!pack.optBoolean("required", true)) continue
                                pack.optString("install_as")
                                    .takeIf(String::isNotBlank)
                                    ?.let(::add)
                            }
                        }
                    }
                    var valid = 0
                    val total = max(1, required.size)
                    required.forEachIndexed { index, path ->
                        if (destroyed) return@runCatching false
                        val file = File(runtimeRoot, path)
                        if (file.isFile && file.length() > 0L) valid++
                        val scanPercent = 18 + ((index + 1) * 54 / total)
                        activity.runOnUiThread {
                            if (!destroyed && !finishing) {
                                showState(
                                    scanPercent,
                                    "Проверяем локальные ресурсы…",
                                    "Проверено ${index + 1} из $total"
                                )
                            }
                        }
                    }
                    required.isNotEmpty() && valid == required.size &&
                        File(runtimeRoot, "surface/manifest.json").isFile &&
                        File(runtimeRoot, "rail/graph.json").isFile
                }.getOrElse { false }

                activity.runOnUiThread {
                    if (destroyed || finishing) return@runOnUiThread
                    localRuntimeReady = ready
                    if (ready) {
                        hint.visibility = View.INVISIBLE
                        showState(88, "Транспортные данные готовы", "Запускаем карту и маршрутизатор")
                        finishReadyStartup()
                    } else {
                        hint.visibility = View.VISIBLE
                    }
                }
            }
        }

        private fun finishReadyStartup() {
            if (destroyed || finishing || !localRuntimeReady) return
            val elapsed = SystemClock.uptimeMillis() - startedAt
            val delay = (READY_MIN_VISIBLE_MS - elapsed).coerceAtLeast(READY_FINISH_MIN_MS)
            progressAnimator?.cancel()
            progressAnimator = ObjectAnimator.ofInt(progress, "progress", progress.progress, 100).apply {
                duration = delay
                interpolator = LinearInterpolator()
                addUpdateListener { animator -> percent.text = "${animator.animatedValue as Int}%" }
                start()
            }
            overlay.postDelayed({
                if (!destroyed && !finishing) {
                    status.text = "Готово"
                    details.text = ""
                    percent.text = "100%"
                    removeOverlay()
                }
            }, delay)
        }

        private fun handoffToInteractive(detail: String) {
            if (destroyed || finishing) return
            finishing = true
            progressAnimator?.cancel()
            hint.visibility = View.VISIBLE
            title.text = "Карта уже готова"
            status.text = "Можно пользоваться приложением"
            details.text = detail
            overlay.animate()
                .alpha(0f)
                .translationY(-dp(12).toFloat())
                .setStartDelay(HANDOFF_HOLD_MS)
                .setDuration(FADE_OUT_MS)
                .withEndAction { removeOverlay(alreadyFinishing = true) }
                .start()
        }

        private fun removeOverlay(alreadyFinishing: Boolean = false) {
            if (destroyed) return
            if (!alreadyFinishing) finishing = true
            stopMotion()
            if (overlay.parent === root) root.removeView(overlay)
            restoreBars()
        }

        private fun showState(target: Int, message: String, detail: String) {
            if (destroyed || finishing) return
            val bounded = target.coerceIn(0, 100)
            if (status.text?.toString() != message) {
                status.animate().cancel()
                status.alpha = 0.72f
                status.text = message
                status.animate().alpha(1f).setDuration(150L).start()
            }
            details.text = detail
            percent.text = "$bounded%"
            progressAnimator?.cancel()
            progressAnimator = ObjectAnimator.ofInt(progress, "progress", progress.progress, bounded).apply {
                duration = 240L
                start()
            }
        }

        private fun configureBarsForStartup() {
            activity.window.statusBarColor = BACKGROUND
            activity.window.navigationBarColor = BACKGROUND
            WindowCompat.getInsetsController(activity.window, overlay).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }

        private fun restoreBars() {
            activity.window.statusBarColor = ContextCompat.getColor(activity, R.color.vh_background)
            activity.window.navigationBarColor = ContextCompat.getColor(activity, R.color.vh_surface_solid)
            val dark = AppPreferences.isDarkTheme(activity)
            WindowCompat.getInsetsController(activity.window, root).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }

        private fun formatBytes(value: Long): String = when {
            value >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f МБ", value / (1024.0 * 1024.0))
            value >= 1024L -> String.format(java.util.Locale.US, "%.0f КБ", value / 1024.0)
            else -> "$value Б"
        }

        private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    }

    private class StartupOrbitView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 1.5f * density
            color = Color.argb(60, 74, 174, 255)
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 3f * density
            color = Color.rgb(42, 179, 255)
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(214, 246, 255)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val size = min(w, h)
            val radius = size * 0.40f
            val cx = w / 2f
            val cy = h / 2f
            val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(oval, 0f, 360f, false, track)
            canvas.drawArc(oval, -35f, 82f, false, glow)
            canvas.drawArc(oval, 145f, 46f, false, glow)
            canvas.drawCircle(cx, cy - radius, 4.3f * density, dot)
            canvas.drawCircle(cx + radius * 0.94f, cy + radius * 0.34f, 2.7f * density, dot)
        }
    }

    private const val FIRST_INSTALL_MAX_BLOCK_MS = 1_250L
    private const val READY_MIN_VISIBLE_MS = 1_650L
    private const val READY_FINISH_MIN_MS = 260L
    private const val HANDOFF_HOLD_MS = 170L
    private const val FADE_OUT_MS = 280L
    private val BACKGROUND = Color.rgb(2, 8, 25)
}
