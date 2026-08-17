package app.humanrouter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.RouteCandidate
import java.time.Instant
import java.util.WeakHashMap

/**
 * Foreground passenger-GPS bridge for the active trip.
 *
 * It never asks for permission itself: trip start remains the permission owner. When permission is
 * already granted, this bridge sends real device samples through the same TripProgressState used by
 * deterministic GPS replay instrumentation. That makes transfer UI behavior testable without a
 * parallel QA-only state machine.
 */
internal object PassengerGpsProgressCoordinator {
    private val controllers = WeakHashMap<MainActivity, Controller>()

    @Synchronized
    fun resume(activity: MainActivity) {
        controllers.getOrPut(activity) { Controller(activity) }.resume()
    }

    @Synchronized
    fun pause(activity: MainActivity) {
        controllers[activity]?.pause()
    }

    @Synchronized
    fun destroy(activity: MainActivity) {
        controllers.remove(activity)?.destroy()
    }

    private class Controller(private val activity: MainActivity) : LocationListener {
        private val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        private var resumed = false
        private var listening = false
        private val liveListener: (TripLiveSnapshot) -> Unit = {
            if (resumed) activity.runOnUiThread(::startTrackingIfNeeded)
        }

        init {
            TripLiveState.addListener(liveListener)
        }

        fun resume() {
            resumed = true
            startTrackingIfNeeded()
        }

        fun pause() {
            resumed = false
            stopTracking()
        }

        fun destroy() {
            pause()
            TripLiveState.removeListener(liveListener)
        }

        private fun startTrackingIfNeeded() {
            if (!resumed || listening || currentRoute() == null || !hasPermission()) return
            val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
            providers.mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.maxByOrNull(Location::getTime)?.let(::publish)

            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter(providers::contains)
                .forEach { provider ->
                    runCatching {
                        manager.requestLocationUpdates(
                            provider,
                            MIN_TIME_MS,
                            MIN_DISTANCE_M,
                            this,
                            Looper.getMainLooper()
                        )
                    }
                }
            listening = providers.any { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
        }

        private fun stopTracking() {
            if (!listening) return
            if (hasPermission()) runCatching { manager.removeUpdates(this) }
            listening = false
        }

        override fun onLocationChanged(location: Location) = publish(location)
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        private fun publish(location: Location) {
            val route = currentRoute() ?: return
            if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
            val epoch = (location.time / 1000L).takeIf { it > 0L } ?: Instant.now().epochSecond
            TripProgressState.publishLocation(
                route = route,
                point = GeoPoint(location.latitude, location.longitude),
                epochSec = epoch,
                accuracyMeters = location.accuracy.takeIf { it.isFinite() && it > 0f } ?: 25f
            )
        }

        private fun currentRoute(): RouteCandidate? =
            TripLiveState.current()?.route ?: ActiveTripStore.load(activity)?.route

        private fun hasPermission(): Boolean =
            ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private const val MIN_TIME_MS = 2_000L
    private const val MIN_DISTANCE_M = 4f
}
