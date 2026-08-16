package app.humanrouter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

/** Presentation chrome for the map-first ВремяХодом shell. */
class VremyaHodomChromeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val prefs by lazy {
        context.getSharedPreferences("vremyahodom_ui", Context.MODE_PRIVATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Keep the legacy drawer-shaped bottom navigation out of the first frame.
        findViewById<View?>(R.id.bottomNav)?.visibility = View.GONE
        post {
            configureChromeInsets()
            wireChrome()
        }
    }

    private fun configureChromeInsets() {
        // MainActivity historically padded the whole root by systemBars().bottom. That
        // produced a second visual floor underneath app navigation. Keep only the status
        // bar safe area here; Material 3 NavigationBar consumes the bottom inset itself,
        // while the map/background can render edge-to-edge behind it.
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(0, statusBars.top, 0, 0)
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    private fun wireChrome() {
        val destination = findViewById<EditText?>(R.id.toField)

        // Bottom navigation is now a Material 3 Compose surface. The legacy LinearLayout
        // remains only as a compatibility target for MainActivity while the rest of the
        // screen is migrated incrementally.
        findViewById<View?>(R.id.bottomNav)?.visibility = View.GONE
        ensureModernBottomNavigation()

        findViewById<Button?>(R.id.routeButton)?.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val button = view as Button
            if (button.text.toString() == "Найти маршрут") button.text = "Найти"
        }

        findViewById<TextView?>(R.id.homeQuickButton)?.setOnClickListener {
            Toast.makeText(context, "Сначала сохраните адрес дома", Toast.LENGTH_SHORT).show()
            destination?.requestFocus()
        }
        findViewById<TextView?>(R.id.workQuickButton)?.setOnClickListener {
            Toast.makeText(context, "Сначала сохраните адрес работы", Toast.LENGTH_SHORT).show()
            destination?.requestFocus()
        }
        findViewById<TextView?>(R.id.nearbyQuickButton)?.setOnClickListener { showNearby(true) }
        findViewById<View?>(R.id.locationButton)?.setOnClickListener { centerOnLastLocation() }
        findViewById<TextView?>(R.id.closeSettingsButton)?.setOnClickListener { closeSettings() }

        findViewById<View?>(R.id.nearbyBusRow)?.setOnClickListener { focusNearby("автобус") }
        findViewById<View?>(R.id.nearbyTramRow)?.setOnClickListener { focusNearby("трамвай") }
        findViewById<View?>(R.id.nearbyMetroRow)?.setOnClickListener { focusNearby("метро") }

        bindPreferenceSwitch(R.id.showStopsSwitch, "show_stops", true)
        bindPreferenceSwitch(R.id.showTransportSwitch, "show_transport", true)
        bindPreferenceSwitch(R.id.darkThemeSwitch, "dark_theme", false)
        bindPreferenceSwitch(R.id.lessWalkingSwitch, "less_walking", false)
        bindPreferenceSwitch(R.id.avoidTransfersSwitch, "avoid_transfers", false)
    }

    private fun ensureModernBottomNavigation() {
        if (findViewWithTag<View?>(MODERN_BOTTOM_NAV_TAG) != null) return

        val nav = ModernBottomNavigationView(context).apply {
            tag = MODERN_BOTTOM_NAV_TAG
            elevation = dp(10).toFloat()
        }
        val params = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )

        // Keep settings above navigation just like the original XML z-order.
        val settings = findViewById<View?>(R.id.settingsPanel)
        val settingsIndex = settings?.let(::indexOfChild)?.takeIf { it >= 0 } ?: childCount
        addView(nav, settingsIndex, params)
    }

    private fun focusNearby(kind: String) {
        Toast.makeText(context, "Показываем $kind рядом", Toast.LENGTH_SHORT).show()
        findViewById<EditText?>(R.id.toField)?.requestFocus()
    }

    private fun showNearby(show: Boolean) {
        val panel = findViewById<View?>(R.id.nearbyPanel) ?: return
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            findViewById<View?>(R.id.routeResultsPanel)?.visibility = View.GONE
            panel.alpha = 0f
            panel.translationY = dp(20).toFloat()
            panel.animate().alpha(1f).translationY(0f).setDuration(170).start()
        }
    }

    private fun closeSettings() {
        val panel = findViewById<LinearLayout?>(R.id.settingsPanel) ?: return
        panel.animate().translationX(panel.width.toFloat()).alpha(0.8f).setDuration(160).withEndAction {
            panel.visibility = View.INVISIBLE
            panel.translationX = 0f
            panel.alpha = 1f
            findViewById<View?>(R.id.searchPanel)?.visibility = View.VISIBLE
        }.start()
    }

    private fun bindPreferenceSwitch(id: Int, key: String, defaultValue: Boolean) {
        val switch = findViewById<SwitchCompat?>(id) ?: return
        switch.isChecked = prefs.getBoolean(key, defaultValue)
        switch.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
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
            .maxByOrNull { it.time }
        if (location == null) {
            Toast.makeText(context, "Уточняем геопозицию…", Toast.LENGTH_SHORT).show()
            return
        }
        findViewById<MapView?>(R.id.mapView)?.getMapAsync { map ->
            map.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(location.latitude, location.longitude)).zoom(15.2).build()
                ),
                420
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MODERN_BOTTOM_NAV_TAG = "vremyahodom_modern_bottom_navigation"
    }
}
