package app.humanrouter

import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.HumanRouterEngine
import app.humanrouter.search.FastAddressResolver
import app.humanrouter.search.SearchPlace
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fast path for the primary A -> B route action.
 *
 * The legacy MainActivity path resolves A and B sequentially and then asks the engine for every
 * alternative before showing anything. On a slow geocoder this can take many seconds. This
 * controller resolves both endpoints concurrently, renders planFastest first, and only then
 * refreshes the list with alternatives in the background.
 */
internal object FastRoutePlanner {
    private const val GEOCODE_BUDGET_MS = 1_550L
    private val io = Executors.newFixedThreadPool(4)
    private val installed = WeakHashMap<MainActivity, Boolean>()
    private val requestSerial = AtomicInteger()

    @Synchronized
    fun install(activity: MainActivity) {
        polishButtons(activity)
        if (installed.put(activity, true) == true) return
        activity.findViewById<Button>(R.id.routeButton).setOnClickListener {
            plan(activity)
        }
    }

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
            val origin = runCatching { originFuture.get(GEOCODE_BUDGET_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            val destination = runCatching { destinationFuture.get(GEOCODE_BUDGET_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            if (request != requestSerial.get()) return@execute

            if (origin == null || destination == null) {
                originFuture.cancel(true)
                destinationFuture.cancel(true)
                activity.runOnUiThread {
                    if (request != requestSerial.get()) return@runOnUiThread
                    invokeByName(activity, "setPlanBusy", false)
                    val missing = if (origin == null) "точку отправления" else "место назначения"
                    invokeByName(activity, "showSuggestionMessage", "Не удалось быстро найти $missing. Уточните улицу и номер дома.")
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
            val fastResult = engine.planFastest(origin.point, destination.point, departure)
            if (request != requestSerial.get()) return@execute

            val fastSucceeded = fastResult is HumanRouterEngine.PlanResult.Success
            activity.runOnUiThread {
                if (request != requestSerial.get()) return@runOnUiThread
                applyResolvedEndpoint(activity, "selectedFrom", fromField, origin)
                applyResolvedEndpoint(activity, "selectedTo", toField, destination)
                activity.findViewById<TextView>(R.id.compactSearchButton).text = destination.title
                invokeByName(activity, "collapseSearch")
                invokeByName(activity, "setPlanBusy", false)
                invokeRenderPlanResult(activity, fastResult)
            }

            // The first usable route is already on screen. Enrich with alternatives afterwards,
            // without making the user wait for multiple departure offsets and multimodal scans.
            if (fastSucceeded && request == requestSerial.get()) {
                val options = runCatching {
                    engine.planOptions(origin.point, destination.point, departure)
                }.getOrNull()
                if (options is HumanRouterEngine.PlanResult.Success && request == requestSerial.get()) {
                    activity.runOnUiThread {
                        if (request != requestSerial.get()) return@runOnUiThread
                        invokeRenderPlanResult(activity, options)
                    }
                }
            } else if (!fastSucceeded && request == requestSerial.get()) {
                // Fast path may be too restrictive for a rare multimodal case. Try the broad search
                // once before leaving the user with a failure.
                val options = runCatching {
                    engine.planOptions(origin.point, destination.point, departure)
                }.getOrNull()
                if (options is HumanRouterEngine.PlanResult.Success && request == requestSerial.get()) {
                    activity.runOnUiThread { invokeRenderPlanResult(activity, options) }
                }
            }

            android.util.Log.i(
                "VremyaHodomRoute",
                "A-B first result in ${SystemClock.elapsedRealtime() - started} ms; fast=$fastSucceeded"
            )
        }
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
        // setFieldText suppresses MainActivity's watcher so it does not immediately clear the
        // selected SearchPlace while we fill the canonical label.
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
