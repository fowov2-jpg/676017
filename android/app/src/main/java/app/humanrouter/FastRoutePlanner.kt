package app.humanrouter

import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import app.humanrouter.routing.FastMeetRouter
import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.HumanRouterEngine
import app.humanrouter.search.FastAddressResolver
import app.humanrouter.search.SearchPlace
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-latency A -> B controller.
 *
 * Typed endpoints are resolved under one shared geocoding deadline. The first route is produced by
 * FastMeetRouter (bidirectional METRO/MCC + concurrent short-horizon BUS/TRAM) and painted before
 * the expensive exact multimodal alternatives are calculated in the background.
 */
internal object FastRoutePlanner {
    internal const val GEOCODE_BUDGET_MS = 780L
    internal const val PREVIEW_BUDGET_MS = 820L
    internal const val FIRST_RESULT_TARGET_MS = 2_000L
    private val io = Executors.newFixedThreadPool(4)
    private val installed = WeakHashMap<MainActivity, Boolean>()
    private val requestSerial = AtomicInteger()

    @Synchronized
    fun install(activity: MainActivity) {
        polishButtons(activity)
        if (installed.put(activity, true) == true) return

        val routeButton = activity.findViewById<Button>(R.id.routeButton)
        val toField = activity.findViewById<EditText>(R.id.toField)
        routeButton.setOnClickListener { plan(activity) }
        toField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                plan(activity)
                true
            } else {
                false
            }
        }

        // FastMeetRouter.prewarm() is non-blocking: it only schedules the rail/surface preload on
        // the router's own workers. Call it immediately while installing the active lifecycle so
        // cold-index parsing starts as early as possible. The previous extra io.execute hop could
        // delay the preload behind unrelated geocoding/refinement work after Activity recreation.
        runCatching {
            FastMeetRouter
                .get(activity.applicationContext, AppPreferences.routePreferences(activity))
                .prewarm()
        }
    }

    @Synchronized
    internal fun isInstalled(activity: MainActivity): Boolean = installed[activity] == true

    private fun plan(activity: MainActivity) {
        val fromField = activity.findViewById<EditText>(R.id.fromField)
        val toField = activity.findViewById<EditText>(R.id.toField)
        val fromText = fromField.text?.toString()?.trim().orEmpty()
        val toText = toField.text?.toString()?.trim().orEmpty()
        val selectedFrom = readField<SearchPlace>(activity, "selectedFrom")
        val selectedTo = readField<SearchPlace>(activity, "selectedTo")
        val currentLocation = readField<GeoPoint>(activity, "currentLocation")

        if (selectedTo == null && toText.length < 2) {
            invokeLegacyPlan(activity)
            return
        }
        if (selectedFrom == null && isCurrentLocationText(fromText) && currentLocation == null) {
            // Permission / GPS acquisition remains owned by MainActivity.
            invokeLegacyPlan(activity)
            return
        }

        val request = requestSerial.incrementAndGet()
        invokeByName(activity, "setPlanBusy", true)
        invokeByName(activity, "hideKeyboard")
        invokeByName(activity, "hideSuggestions")
        val started = SystemClock.elapsedRealtime()

        val originFuture = io.submit<SearchPlace?> {
            selectedFrom ?: when {
                isCurrentLocationText(fromText) -> currentLocation?.let {
                    SearchPlace("Моё местоположение", "GPS", it)
                }
                else -> resolveTyped(activity, fromText, currentLocation)
            }
        }
        val destinationFuture = io.submit<SearchPlace?> {
            selectedTo ?: resolveTyped(activity, toText, currentLocation)
        }

        io.execute {
            // One absolute deadline for both endpoints. The old code could effectively spend the
            // timeout twice when one endpoint was slow.
            val geocodeDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GEOCODE_BUDGET_MS)
            val origin = getBeforeDeadline(originFuture, geocodeDeadline)
            val destination = getBeforeDeadline(destinationFuture, geocodeDeadline)
            if (request != requestSerial.get()) return@execute

            if (origin == null || destination == null) {
                originFuture.cancel(true)
                destinationFuture.cancel(true)
                activity.runOnUiThread {
                    if (request != requestSerial.get()) return@runOnUiThread
                    invokeByName(activity, "setPlanBusy", false)
                    val missing = if (origin == null) "точку отправления" else "место назначения"
                    invokeByName(
                        activity,
                        "showSuggestionMessage",
                        "Не удалось быстро найти $missing. Выберите подсказку или уточните улицу и дом."
                    )
                    Toast.makeText(activity, "Адрес не найден. Проверьте написание.", Toast.LENGTH_SHORT).show()
                }
                return@execute
            }

            val engine = engine(activity)
            if (engine == null) {
                activity.runOnUiThread {
                    invokeByName(activity, "setPlanBusy", false)
                    Toast.makeText(activity, "Маршрутизатор ещё не готов", Toast.LENGTH_SHORT).show()
                }
                return@execute
            }

            val departure = java.time.Instant.now().epochSecond
            val routePreferences = AppPreferences.routePreferences(activity)
            val preview = runCatching {
                FastMeetRouter
                    .get(activity.applicationContext, routePreferences)
                    .planPreview(
                        origin = origin.point,
                        destination = destination.point,
                        departureEpochSec = departure,
                        budgetMs = PREVIEW_BUDGET_MS
                    )
            }.getOrElse {
                HumanRouterEngine.PlanResult.Failure(it.message ?: "Быстрый поиск недоступен")
            }
            if (request != requestSerial.get()) return@execute

            val previewSucceeded = preview is HumanRouterEngine.PlanResult.Success
            val firstElapsed = SystemClock.elapsedRealtime() - started
            if (previewSucceeded) {
                activity.runOnUiThread {
                    if (request != requestSerial.get()) return@runOnUiThread
                    applyResolvedEndpoint(activity, "selectedFrom", fromField, origin)
                    applyResolvedEndpoint(activity, "selectedTo", toField, destination)
                    activity.findViewById<TextView>(R.id.compactSearchButton).text = destination.title
                    invokeByName(activity, "collapseSearch")
                    invokeByName(activity, "setPlanBusy", false)
                    invokeRenderPlanResult(activity, preview)
                }
            }

            android.util.Log.i(
                "VremyaHodomRoute",
                "first-preview=${firstElapsed}ms target=${FIRST_RESULT_TARGET_MS}ms success=$previewSucceeded"
            )

            // Full exact multimodal calculation is refinement, not a blocker for first paint.
            // If the preview could not cover a rare trip, fall back to the existing exact fastest
            // route before running all alternatives.
            if (!previewSucceeded && request == requestSerial.get()) {
                val exactFast = runCatching {
                    engine.planFastest(origin.point, destination.point, departure)
                }.getOrNull()
                if (exactFast is HumanRouterEngine.PlanResult.Success && request == requestSerial.get()) {
                    activity.runOnUiThread {
                        if (request != requestSerial.get()) return@runOnUiThread
                        applyResolvedEndpoint(activity, "selectedFrom", fromField, origin)
                        applyResolvedEndpoint(activity, "selectedTo", toField, destination)
                        activity.findViewById<TextView>(R.id.compactSearchButton).text = destination.title
                        invokeByName(activity, "collapseSearch")
                        invokeByName(activity, "setPlanBusy", false)
                        invokeRenderPlanResult(activity, exactFast)
                    }
                }
            }

            if (request != requestSerial.get()) return@execute
            val options = runCatching {
                engine.planOptions(origin.point, destination.point, departure)
            }.getOrNull()
            if (options is HumanRouterEngine.PlanResult.Success && request == requestSerial.get()) {
                activity.runOnUiThread {
                    if (request != requestSerial.get()) return@runOnUiThread
                    invokeByName(activity, "setPlanBusy", false)
                    invokeRenderPlanResult(activity, options)
                }
            } else if (!previewSucceeded && request == requestSerial.get()) {
                activity.runOnUiThread {
                    if (request != requestSerial.get()) return@runOnUiThread
                    invokeByName(activity, "setPlanBusy", false)
                    invokeRenderPlanResult(
                        activity,
                        options ?: HumanRouterEngine.PlanResult.Failure("Для выбранных точек маршрут не найден")
                    )
                }
            }
        }
    }

    private fun <T> getBeforeDeadline(future: Future<T>, deadlineNanos: Long): T? {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0L) return null
        return runCatching { future.get(remaining, TimeUnit.NANOSECONDS) }.getOrNull()
    }

    private fun resolveTyped(activity: MainActivity, text: String, focus: GeoPoint?): SearchPlace? {
        if (text.length < 2) return null
        return FastAddressResolver.search(
            context = activity,
            query = text,
            focus = focus,
            limit = 5,
            budgetMs = GEOCODE_BUDGET_MS
        ).firstOrNull()
    }

    private fun engine(activity: MainActivity): HumanRouterEngine? = runCatching {
        val method = activity.javaClass.declaredMethods.firstOrNull {
            it.name == "engineForCurrentPreferences" && it.parameterCount == 0
        } ?: return@runCatching null
        method.isAccessible = true
        method.invoke(activity) as? HumanRouterEngine
    }.getOrNull()

    private fun applyResolvedEndpoint(
        activity: MainActivity,
        fieldName: String,
        field: EditText,
        place: SearchPlace
    ) {
        val label = if (place.subtitle.isBlank()) place.title else "${place.title}, ${place.subtitle}"
        val method = activity.javaClass.declaredMethods.firstOrNull {
            it.name == "setFieldText" && it.parameterCount == 2
        }
        runCatching {
            method?.isAccessible = true
            method?.invoke(activity, field, label)
        }.onFailure { field.setText(label) }
        writeField(activity, fieldName, place)
    }

    private fun invokeRenderPlanResult(activity: MainActivity, result: HumanRouterEngine.PlanResult) {
        runCatching {
            val method = activity.javaClass.declaredMethods.firstOrNull {
                it.name == "renderPlanResult" && it.parameterCount == 1
            } ?: return
            method.isAccessible = true
            method.invoke(activity, result)
        }
    }

    private fun invokeLegacyPlan(activity: MainActivity) {
        runCatching {
            val method = activity.javaClass.declaredMethods.firstOrNull {
                it.name == "planRouteNow" && it.parameterCount == 0
            } ?: return
            method.isAccessible = true
            method.invoke(activity)
        }
    }

    private fun invokeByName(activity: MainActivity, name: String, vararg args: Any?) {
        runCatching {
            val method: Method = activity.javaClass.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == args.size
            } ?: return
            method.isAccessible = true
            method.invoke(activity, *args)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(activity: MainActivity, name: String): T? = runCatching {
        val field = activity.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.get(activity) as? T
    }.getOrNull()

    private fun writeField(activity: MainActivity, name: String, value: Any?) {
        runCatching {
            val field = activity.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.set(activity, value)
        }
    }

    private fun isCurrentLocationText(text: String): Boolean =
        text.isBlank() ||
            text.equals("Моё местоположение", ignoreCase = true) ||
            text.equals("Мое местоположение", ignoreCase = true) ||
            text.equals("Текущее местоположение", ignoreCase = true)

    private fun polishButtons(activity: MainActivity) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val buttonIds = intArrayOf(
            R.id.routeButton,
            R.id.retryButton,
            R.id.locationPrimaryAction,
            R.id.locationSecondaryAction,
            R.id.routePrimaryAction,
            R.id.checkDataButton
        )
        buttonIds.forEach { id ->
            activity.findViewById<Button?>(id)?.apply {
                isAllCaps = false
                minimumHeight = dp(48)
                minHeight = dp(48)
                setPadding(dp(14), paddingTop, dp(14), paddingBottom)
            }
        }
        activity.findViewById<Button>(R.id.routeButton).apply {
            textSize = 16f
            minimumHeight = dp(50)
        }
        intArrayOf(R.id.clearFromButton, R.id.clearToButton, R.id.closeSearchButton).forEach { id ->
            activity.findViewById<TextView>(id).apply {
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                alpha = 0.92f
            }
        }
    }
}
