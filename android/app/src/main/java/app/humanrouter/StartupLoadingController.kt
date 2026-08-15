package app.humanrouter

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Full-screen startup surface shown after the mandatory Android system splash.
 *
 * Unlike the platform splash icon, this screen reflects actual application work:
 * local runtime files are checked one by one and RuntimeDownloadWorker progress is
 * mirrored directly (including downloaded/total byte counts). It disappears as soon
 * as usable transport data and the first UI layout are ready.
 */
internal object StartupLoadingController {
    private val controllers = WeakHashMap<MainActivity, Controller>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (!BuildConfig.DEBUG.not() && activity.intent.hasExtra("qa_screen")) return
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
        private var localRuntimeReady = false
        private var finishing = false
        private var progressAnimator: ObjectAnimator? = null

        private val overlay = FrameLayout(activity).apply {
            setBackgroundColor(BACKGROUND)
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Подготовка приложения"
            elevation = dp(80).toFloat()
        }
        private val art = ImageView(activity).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "ВремяХодом"
        }
        private val title = TextView(activity).apply {
            text = "Приложение готовится к запуску"
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
            setTextColor(Color.rgb(181, 199, 224))
            includeFontPadding = false
        }
        private val details = TextView(activity).apply {
            gravity = Gravity.CENTER
            textSize = 11.5f
            setTextColor(Color.rgb(115, 139, 173))
            includeFontPadding = false
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
        private val retry = Button(activity).apply {
            text = "Повторить загрузку"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            minHeight = dp(48)
            minimumHeight = dp(48)
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.rgb(19, 126, 232))
            }
            setOnClickListener {
                visibility = View.GONE
                showState(8, "Повторяем загрузку транспортных данных…", "Ожидаем соединение")
                enqueueRuntimeDownload()
            }
        }

        init {
            buildUi()
            loadArtwork()
            root.addView(
                overlay,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            configureBarsForStartup()
            animateArtwork()
            observeRuntimeWork()
            scanLocalRuntime()
        }

        fun destroy() {
            destroyed = true
            progressAnimator?.cancel()
            art.animate().cancel()
            worker.shutdownNow()
            if (overlay.parent === root) root.removeView(overlay)
        }

        private fun buildUi() {
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(28), dp(18), dp(28), dp(18))
            }
            content.addView(Space(activity), LinearLayout.LayoutParams(1, 0, 0.72f))
            content.addView(
                art,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(330)).apply {
                    leftMargin = dp(4)
                    rightMargin = dp(4)
                }
            )
            content.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            content.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(13)
            })
            content.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply {
                topMargin = dp(22)
            })
            content.addView(percent, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(7)
            })
            content.addView(details, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
            content.addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(18)
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

        private fun animateArtwork() {
            art.alpha = 0.88f
            art.scaleX = 0.985f
            art.scaleY = 0.985f
            art.animate()
                .alpha(1f)
                .scaleX(1.015f)
                .scaleY(1.015f)
                .setDuration(1_200L)
                .withEndAction {
                    if (!destroyed && !finishing) {
                        art.animate()
                            .alpha(0.91f)
                            .scaleX(0.99f)
                            .scaleY(0.99f)
                            .setDuration(1_200L)
                            .withEndAction { if (!destroyed && !finishing) animateArtwork() }
                            .start()
                    }
                }
                .start()
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
                                showState(9, "Ожидаем сеть для транспортных данных…", "Данные будут загружены автоматически")
                            }
                        }
                        WorkInfo.State.RUNNING -> {
                            val realPercent = info.progress.getInt(RuntimeDownloadWorker.KEY_PERCENT, 0).coerceIn(0, 100)
                            val downloaded = info.progress.getLong(RuntimeDownloadWorker.KEY_DOWNLOADED, 0L)
                            val total = info.progress.getLong(RuntimeDownloadWorker.KEY_TOTAL, 0L)
                            val message = info.progress.getString(RuntimeDownloadWorker.KEY_MESSAGE)
                                ?.takeIf(String::isNotBlank)
                                ?: "Загружаем транспортные данные…"
                            val mapped = 12 + (realPercent * 70 / 100)
                            val byteText = if (total > 0L) {
                                "${formatBytes(downloaded)} из ${formatBytes(total)} · $realPercent% ресурсов"
                            } else {
                                "$realPercent% транспортных данных"
                            }
                            showState(mapped, message, byteText)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            showState(84, "Проверяем загруженные ресурсы…", "Загрузка завершена")
                            scanLocalRuntime()
                        }
                        WorkInfo.State.FAILED -> {
                            scanLocalRuntime(onUnavailable = {
                                val error = info.outputData.getString(RuntimeDownloadWorker.KEY_ERROR)
                                    ?.takeIf(String::isNotBlank)
                                    ?: "Не удалось подготовить транспортные данные"
                                showFailure(error)
                            })
                        }
                        WorkInfo.State.CANCELLED -> {
                            scanLocalRuntime(onUnavailable = { showFailure("Загрузка транспортных данных остановлена") })
                        }
                    }
                }
        }

        private fun scanLocalRuntime(onUnavailable: (() -> Unit)? = null) {
            worker.execute {
                val runtimeRoot = File(activity.filesDir, "runtime")
                val manifestFile = File(runtimeRoot, "manifest.json")
                if (!manifestFile.isFile) {
                    activity.runOnUiThread {
                        if (onUnavailable != null) onUnavailable() else showState(
                            6,
                            "Готовим транспортные данные Москвы…",
                            "Локальный пакет ещё не установлен"
                        )
                    }
                    return@execute
                }

                val result = runCatching {
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
                        val scanPercent = 18 + ((index + 1) * 52 / total)
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
                    localRuntimeReady = result
                    if (result) {
                        showState(78, "Транспортные данные готовы", "Локальные ресурсы проверены")
                        finishWhenUiReady()
                    } else if (onUnavailable != null) {
                        onUnavailable()
                    }
                }
            }
        }

        private fun finishWhenUiReady() {
            if (!localRuntimeReady || finishing || destroyed) return
            showState(88, "Подготавливаем карту и интерфейс…", "Транспортный движок готов")
            root.post {
                if (destroyed || finishing) return@post
                val map = activity.findViewById<View?>(R.id.mapView)
                if (root.width > 0 && root.height > 0 && map?.width ?: 0 > 0) {
                    showState(97, "Запускаем навигатор…", "Интерфейс готов")
                    finishOverlay()
                } else {
                    root.postDelayed({ finishWhenUiReady() }, 80L)
                }
            }
        }

        private fun finishOverlay() {
            if (finishing || destroyed) return
            finishing = true
            showState(100, "Готово", "")
            val elapsed = SystemClock.uptimeMillis() - startedAt
            val delay = (MIN_VISIBLE_MS - elapsed).coerceAtLeast(120L)
            overlay.postDelayed({
                if (destroyed) return@postDelayed
                overlay.animate()
                    .alpha(0f)
                    .setDuration(220L)
                    .withEndAction {
                        if (overlay.parent === root) root.removeView(overlay)
                        restoreBars()
                    }
                    .start()
            }, delay)
        }

        private fun showFailure(message: String) {
            if (localRuntimeReady || destroyed || finishing) {
                if (localRuntimeReady) finishWhenUiReady()
                return
            }
            progressAnimator?.cancel()
            status.text = "Не удалось подготовить ресурсы"
            details.text = message
            percent.text = ""
            retry.visibility = View.VISIBLE
        }

        private fun showState(target: Int, message: String, detail: String) {
            if (destroyed || finishing && target < 100) return
            val bounded = target.coerceIn(0, 100)
            status.text = message
            details.text = detail
            percent.text = "$bounded%"
            retry.visibility = View.GONE
            progressAnimator?.cancel()
            progressAnimator = ObjectAnimator.ofInt(progress, "progress", progress.progress, bounded).apply {
                duration = 220L
                start()
            }
        }

        private fun enqueueRuntimeDownload() {
            val request = OneTimeWorkRequestBuilder<RuntimeDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(activity).enqueueUniqueWork(
                RuntimeDownloadWorker.UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
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

    private const val MIN_VISIBLE_MS = 520L
    private val BACKGROUND = Color.rgb(2, 8, 25)
}
