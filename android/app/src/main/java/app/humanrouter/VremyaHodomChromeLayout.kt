package app.humanrouter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

/**
 * Lightweight UI chrome kept separate from the routing activity. It owns only
 * map-screen conveniences and presentation preferences; routing/runtime logic
 * remains in MainActivity/HumanRouterEngine.
 */
class VremyaHodomChromeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val prefs by lazy {
        context.getSharedPreferences("vremyahodom_ui", Context.MODE_PRIVATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { wireChrome() }
    }

    private fun wireChrome() {
        findViewById<TextView?>(R.id.homeQuickButton)?.setOnClickListener {
            Toast.makeText(context, "Дом можно будет сохранить после выбора адреса", Toast.LENGTH_SHORT).show()
            findViewById<EditText?>(R.id.toField)?.requestFocus()
        }
        findViewById<TextView?>(R.id.workQuickButton)?.setOnClickListener {
            Toast.makeText(context, "Работу можно будет сохранить после выбора адреса", Toast.LENGTH_SHORT).show()
            findViewById<EditText?>(R.id.toField)?.requestFocus()
        }
        findViewById<TextView?>(R.id.nearbyQuickButton)?.setOnClickListener {
            showNearby(true)
        }
        findViewById<View?>(R.id.locationButton)?.setOnClickListener {
            centerOnLastLocation()
        }
        findViewById<TextView?>(R.id.transportNavButton)?.setOnClickListener {
            showNearby(true)
        }
        findViewById<TextView?>(R.id.favoritesNavButton)?.setOnClickListener {
            Toast.makeText(context, "Избранное появится здесь после сохранения маршрутов", Toast.LENGTH_SHORT).show()
        }

        bindPreferenceSwitch(R.id.showStopsSwitch, "show_stops", true)
        bindPreferenceSwitch(R.id.showTransportSwitch, "show_transport", true)
        bindPreferenceSwitch(R.id.darkThemeSwitch, "dark_theme", false)
        bindPreferenceSwitch(R.id.lessWalkingSwitch, "less_walking", false)
        bindPreferenceSwitch(R.id.avoidTransfersSwitch, "avoid_transfers", false)
    }

    private fun showNearby(show: Boolean) {
        val panel = findViewById<View?>(R.id.nearbyPanel) ?: return
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            panel.alpha = 0f
            panel.translationY = dp(28).toFloat()
            panel.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
    }

    private fun bindPreferenceSwitch(id: Int, key: String, defaultValue: Boolean) {
        val switch = findViewById<SwitchCompat?>(id) ?: return
        switch.isChecked = prefs.getBoolean(key, defaultValue)
        switch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
    }

    private fun centerOnLastLocation() {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            Toast.makeText(context, "Разрешите геопозицию, чтобы показать вас на карте", Toast.LENGTH_SHORT).show()
            return
        }
        val manager = context.getSystemService(LocationManager::class.java)
        val location = manager.getProviders(true)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull(Location::getTime)
        if (location == null) {
            Toast.makeText(context, "Уточняем геопозицию…", Toast.LENGTH_SHORT).show()
            return
        }
        findViewById<MapView?>(R.id.mapView)?.getMapAsync { map ->
            map.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(location.latitude, location.longitude))
                        .zoom(15.2)
                        .build()
                ),
                420
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
