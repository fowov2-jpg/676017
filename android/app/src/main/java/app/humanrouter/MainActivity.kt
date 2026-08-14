package app.humanrouter

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var loadingPanel: LinearLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var retryButton: Button
    private lateinit var routeButton: Button
    private lateinit var settingsButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)
        loadingPanel = findViewById(R.id.loadingPanel)
        bottomNav = findViewById(R.id.bottomNav)
        retryButton = findViewById(R.id.retryButton)
        routeButton = findViewById(R.id.routeButton)
        settingsButton = findViewById(R.id.settingsButton)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(55.751244, 37.618423))
                .zoom(10.5)
                .build()
        }

        routeButton.setOnClickListener {
            status.text = "Выберите точки «Откуда» и «Куда»"
            loadingPanel.visibility = View.VISIBLE
        }
        retryButton.setOnClickListener { startRuntimeInstall() }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        startRuntimeInstall()
    }

    private fun startRuntimeInstall() {
        retryButton.visibility = View.GONE
        loadingPanel.visibility = View.VISIBLE
        bottomNav.visibility = View.GONE
        progress.progress = 0
        status.text = "Проверяем данные…"
        progressText.text = "0%"

        Thread {
            runCatching {
                RuntimeInstaller.install(this) { p ->
                    runOnUiThread {
                        progress.progress = p.percent
                        status.text = p.message
                        progressText.text = "${p.percent}%"
                        if (p.done) {
                            loadingPanel.visibility = View.GONE
                            bottomNav.visibility = View.VISIBLE
                        }
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    status.text = "Ошибка данных: ${error.message ?: "неизвестно"}"
                    retryButton.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
}
