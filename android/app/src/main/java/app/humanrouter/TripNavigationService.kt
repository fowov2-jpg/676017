package app.humanrouter

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.HumanRouterEngine
import app.humanrouter.routing.LastPlanStore
import app.humanrouter.routing.RankedRoute
import app.humanrouter.routing.ReplanPolicy
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.RouteObjective
import app.humanrouter.routing.RoutePreferences
import app.humanrouter.routing.RouteRanker
import app.humanrouter.routing.TransportMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

class TripNavigationService : Service(), LocationListener {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val planning = AtomicBoolean(false)
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private var routingPreferences = RoutePreferences()
    private var engine: HumanRouterEngine? = null
    private val zoneId = ZoneId.of("Europe/Moscow")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    @Volatile private var lastLocation: Location? = null
    private var destination: GeoPoint? = null
    private var baselineArrivalEpochSec: Long = 0L
    private var currentRouteId: String = ""
    private var routeSummary: String = ""
    private var nextStop: String = ""
    private var stopsRemaining: Int = 0
    private var lastSuggestedRouteId: String? = null
    private var lastSuggestionEpochSec: Long = 0L

    private val replanRunnable = object : Runnable {
        override fun run() {
            replanNow()
            handler.postDelayed(this, REPLAN_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        loadState()
        ActiveTripStore.load(this)?.route?.let { route ->
            TripLiveState.publish(route, approximate(route), "Восстановлена активная поездка")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopNavigation()
                return START_NOT_STICKY
            }
            ACTION_ACCEPT_REPLAN -> {
                acceptPendingReplan(intent.getStringExtra(EXTRA_ROUTE_ID))
                return if (destination != null) START_STICKY else START_NOT_STICKY
            }
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_DEST_LAT, Double.NaN)
                val lon = intent.getDoubleExtra(EXTRA_DEST_LON, Double.NaN)
                if (lat.isFinite() && lon.isFinite()) destination = GeoPoint(lat, lon)
                baselineArrivalEpochSec = intent.getLongExtra(EXTRA_BASELINE_ARRIVAL, baselineArrivalEpochSec)
                currentRouteId = intent.getStringExtra(EXTRA_ROUTE_ID).orEmpty()
                routeSummary = intent.getStringExtra(EXTRA_ROUTE_SUMMARY).orEmpty()
                nextStop = intent.getStringExtra(EXTRA_NEXT_STOP).orEmpty()
                stopsRemaining = intent.getIntExtra(EXTRA_STOPS_REMAINING, 0).coerceAtLeast(0)
                ActiveTripStore.load(this)?.route?.let { route ->
                    currentRouteId = route.id
                    baselineArrivalEpochSec = route.arrivalEpochSec
                    destination = route.legs.last().to.point
                    TripLiveState.publish(route, approximate(route), "Поездка запущена")
                }
                persistState()
            }
        }

        if (destination == null || baselineArrivalEpochSec <= 0L) {
            ActiveTripStore.clear(this)
            PendingReplanStore.clear(this)
            TripLiveState.clear()
            stopSelf()
            return START_NOT_STICKY
        }

        val initialStatus = buildString {
            if (nextStop.isNotBlank()) append("Следующая: ").append(nextStop)
            if (stopsRemaining > 0) {
                if (isNotEmpty()) append(" · ")
                append(stopsRemaining).append(' ').append(stopWord(stopsRemaining))
            }
            if (isEmpty()) append(routeSummary.ifBlank { "Запускаем навигацию…" })
        }
        startForeground(NOTIFICATION_ID_ACTIVE, buildActiveNotification(initialStatus))
        startLocationTracking()
        handler.removeCallbacks(replanRunnable)
        handler.post(replanRunnable)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        val previous = lastLocation
        if (previous == null || location.accuracy <= previous.accuracy + 80f || location.time > previous.time + 60_000L) {
            lastLocation = location
        }
    }

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        if (!hasLocationPermission()) {
            updateForegroundNotification("Нужен доступ к геопозиции")
            return
        }
        val providers = runCatching { locationManager.getProviders(true) }.getOrDefault(emptyList())
        providers.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull(Location::getTime)?.let { lastLocation = it }

        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { providers.contains(it) }
            .forEach { provider ->
                runCatching {
                    locationManager.requestLocationUpdates(
                        provider,
                        LOCATION_MIN_TIME_MS,
                        LOCATION_MIN_DISTANCE_METERS,
                        this,
                        Looper.getMainLooper()
                    )
                }
            }
    }

    private fun currentEngine(): HumanRouterEngine {
        val updatedPreferences = AppPreferences.routePreferences(this)
        val existing = engine
        if (existing == null || updatedPreferences != routingPreferences) {
            routingPreferences = updatedPreferences
            engine = HumanRouterEngine(this, updatedPreferences)
        }
        return checkNotNull(engine)
    }

    private fun replanNow() {
        val location = lastLocation ?: run {
            updateForegroundNotification("Ждём точную геопозицию…")
            return
        }
        val target = destination ?: return
        if (!planning.compareAndSet(false, true)) return

        executor.execute {
            try {
                val result = currentEngine().planFastest(
                    origin = GeoPoint(location.latitude, location.longitude),
                    destination = target,
                    departureEpochSec = Instant.now().epochSecond
                )
                if (result is HumanRouterEngine.PlanResult.Success) {
                    val alternative = result.fastest
                    val route = alternative.route
                    val summary = summarize(route)
                    val active = ActiveTripStore.load(this)?.route
                    if (active != null && route.id == currentRouteId) {
                        replaceActiveRoute(route, "ETA обновлён")
                    }
                    val displayRoute = ActiveTripStore.load(this)?.route ?: active ?: route
                    val arrival = displayRoute.arrivalEpochSec
                    val etaText = (if (approximate(displayRoute)) "≈ " else "") +
                        Instant.ofEpochSecond(arrival).atZone(zoneId).format(timeFormatter)
                    val remainingMin = maxOf(
                        1,
                        ceil((arrival - Instant.now().epochSecond).coerceAtLeast(60L) / 60.0).toInt()
                    )
                    handler.post {
                        updateForegroundNotification("$remainingMin мин · прибытие $etaText · ${summarize(displayRoute)}")
                        maybeSuggestReplan(alternative, summary)
                    }
                }
            } finally {
                planning.set(false)
            }
        }
    }

    private fun maybeSuggestReplan(alternative: RankedRoute, summary: String) {
        val activeRoute = ActiveTripStore.load(this)?.route ?: return
        if (activeRoute.id == alternative.route.id) return
        val current = RouteRanker.score(activeRoute, RouteObjective.FASTEST, routingPreferences)
        val lastSuggestion = lastSuggestionEpochSec.takeIf { it > 0L }
        val decision = ReplanPolicy.evaluate(
            nowEpochSec = Instant.now().epochSecond,
            current = current,
            alternative = alternative,
            lastSuggestionEpochSec = lastSuggestion,
            preferences = routingPreferences
        )
        if (!decision.shouldSuggest) return

        val route = alternative.route
        PendingReplanStore.save(this, route)
        lastSuggestedRouteId = route.id
        lastSuggestionEpochSec = Instant.now().epochSecond
        val minutes = maxOf(1, ceil(decision.savingSeconds / 60.0).toInt())
        val acceptIntent = Intent(this, TripNavigationService::class.java).apply {
            action = ACTION_ACCEPT_REPLAN
            putExtra(EXTRA_ROUTE_ID, route.id)
        }
        val acceptPending = PendingIntent.getService(
            this,
            2202,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPending = PendingIntent.getActivity(
            this,
            2203,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(decision.reason.ifBlank { "Есть маршрут быстрее на $minutes мин" })
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$summary\nЭкономия примерно $minutes мин."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addAction(0, "Принять", acceptPending)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID_SUGGESTION, notification)
    }

    private fun acceptPendingReplan(requestedRouteId: String?) {
        val route = PendingReplanStore.load(this)
        if (route == null || (!requestedRouteId.isNullOrBlank() && route.id != requestedRouteId)) {
            updateForegroundNotification("Новый маршрут уже недоступен · продолжаем текущий")
            PendingReplanStore.clear(this)
            return
        }
        val existingSnapshot = ActiveTripStore.load(this)
        destination = route.legs.last().to.point
        currentRouteId = route.id
        baselineArrivalEpochSec = route.arrivalEpochSec
        routeSummary = summarize(route)
        val firstTransit = route.legs.firstOrNull { it.mode != TransportMode.WALK }
        nextStop = firstTransit?.to?.name.orEmpty()
        stopsRemaining = firstTransit?.stopCount ?: 0
        if (existingSnapshot != null) {
            ActiveTripStore.save(this, existingSnapshot.copy(route = route))
        }
        LastPlanStore.select(route, checkNotNull(destination))
        TripLiveState.publish(route, approximate(route), "Маршрут обновлён")
        PendingReplanStore.clear(this)
        lastSuggestedRouteId = null
        persistState()
        updateForegroundNotification("Маршрут обновлён · ${remainingText(route)}")
    }

    private fun replaceActiveRoute(route: RouteCandidate, status: String) {
        val snapshot = ActiveTripStore.load(this) ?: return
        ActiveTripStore.save(this, snapshot.copy(route = route))
        destination = route.legs.last().to.point
        currentRouteId = route.id
        baselineArrivalEpochSec = route.arrivalEpochSec
        LastPlanStore.select(route, checkNotNull(destination))
        TripLiveState.publish(route, approximate(route), status)
        persistState()
    }

    private fun remainingText(route: RouteCandidate): String {
        val minutes = maxOf(1, ceil((route.arrivalEpochSec - Instant.now().epochSecond).coerceAtLeast(60L) / 60.0).toInt())
        val eta = Instant.ofEpochSecond(route.arrivalEpochSec).atZone(zoneId).format(timeFormatter)
        return "${if (approximate(route)) "≈ " else ""}$minutes мин · до $eta"
    }

    private fun summarize(route: RouteCandidate): String = route.legs.joinToString(" → ") { leg ->
        when (leg.mode) {
            TransportMode.WALK -> "пешком"
            TransportMode.BUS -> "автобус ${leg.lineName ?: leg.lineId.orEmpty()}"
            TransportMode.TRAM -> "трамвай ${leg.lineName ?: leg.lineId.orEmpty()}"
            TransportMode.METRO -> "метро ${leg.lineName.orEmpty()}".trim()
            TransportMode.MCC -> "МЦК"
            TransportMode.MCD -> "МЦД ${leg.lineName.orEmpty()}".trim()
            TransportMode.TRAIN -> "поезд ${leg.lineName.orEmpty()}".trim()
        }
    }

    private fun approximate(route: RouteCandidate): Boolean = route.legs.any { leg ->
        leg.realtimeConfidence < 0.85 || leg.mode in setOf(
            TransportMode.BUS,
            TransportMode.TRAM,
            TransportMode.METRO,
            TransportMode.MCC
        )
    }

    private fun buildActiveNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("ВремяХодом · маршрут активен")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                2200,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            0,
            "Завершить",
            PendingIntent.getService(
                this,
                2201,
                Intent(this, TripNavigationService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun updateForegroundNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID_ACTIVE, buildActiveNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ВремяХодом · навигация",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Активный маршрут и предложения более быстрого пути"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun persistState() {
        val target = destination ?: return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_DEST_LAT, target.lat.toString())
            .putString(KEY_DEST_LON, target.lon.toString())
            .putLong(KEY_BASELINE_ARRIVAL, baselineArrivalEpochSec)
            .putString(KEY_ROUTE_ID, currentRouteId)
            .putString(KEY_ROUTE_SUMMARY, routeSummary)
            .putString(KEY_NEXT_STOP, nextStop)
            .putInt(KEY_STOPS_REMAINING, stopsRemaining)
            .apply()
    }

    private fun loadState() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return
        val lat = prefs.getString(KEY_DEST_LAT, null)?.toDoubleOrNull()
        val lon = prefs.getString(KEY_DEST_LON, null)?.toDoubleOrNull()
        if (lat != null && lon != null) destination = GeoPoint(lat, lon)
        baselineArrivalEpochSec = prefs.getLong(KEY_BASELINE_ARRIVAL, 0L)
        currentRouteId = prefs.getString(KEY_ROUTE_ID, "").orEmpty()
        routeSummary = prefs.getString(KEY_ROUTE_SUMMARY, "").orEmpty()
        nextStop = prefs.getString(KEY_NEXT_STOP, "").orEmpty()
        stopsRemaining = prefs.getInt(KEY_STOPS_REMAINING, 0).coerceAtLeast(0)
    }

    private fun stopWord(value: Int): String {
        val mod100 = value % 100
        val mod10 = value % 10
        return when {
            mod100 in 11..14 -> "остановок"
            mod10 == 1 -> "остановка"
            mod10 in 2..4 -> "остановки"
            else -> "остановок"
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopNavigation() {
        handler.removeCallbacks(replanRunnable)
        if (hasLocationPermission()) runCatching { locationManager.removeUpdates(this) }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
        ActiveTripStore.clear(this)
        PendingReplanStore.clear(this)
        TripLiveState.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(replanRunnable)
        if (hasLocationPermission()) runCatching { locationManager.removeUpdates(this) }
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.humanrouter.action.START_TRIP"
        const val ACTION_STOP = "app.humanrouter.action.STOP_TRIP"
        const val ACTION_ACCEPT_REPLAN = "app.humanrouter.action.ACCEPT_REPLAN"
        const val EXTRA_DEST_LAT = "dest_lat"
        const val EXTRA_DEST_LON = "dest_lon"
        const val EXTRA_BASELINE_ARRIVAL = "baseline_arrival"
        const val EXTRA_ROUTE_ID = "route_id"
        const val EXTRA_ROUTE_SUMMARY = "route_summary"
        const val EXTRA_NEXT_STOP = "next_stop"
        const val EXTRA_STOPS_REMAINING = "stops_remaining"

        private const val CHANNEL_ID = "trip_navigation"
        private const val NOTIFICATION_ID_ACTIVE = 2200
        private const val NOTIFICATION_ID_SUGGESTION = 2201
        private const val REPLAN_INTERVAL_MS = 60_000L
        private const val LOCATION_MIN_TIME_MS = 20_000L
        private const val LOCATION_MIN_DISTANCE_METERS = 15f
        private const val PREFS = "active_trip"
        private const val KEY_ACTIVE = "active"
        private const val KEY_DEST_LAT = "dest_lat"
        private const val KEY_DEST_LON = "dest_lon"
        private const val KEY_BASELINE_ARRIVAL = "baseline_arrival"
        private const val KEY_ROUTE_ID = "route_id"
        private const val KEY_ROUTE_SUMMARY = "route_summary"
        private const val KEY_NEXT_STOP = "next_stop"
        private const val KEY_STOPS_REMAINING = "stops_remaining"
    }
}
