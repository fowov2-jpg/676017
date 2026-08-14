package app.humanrouter

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)
        mapView = findViewById(R.id.mapView)
        status = findViewById(R.id.status)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(55.751244, 37.618423))
                .zoom(10.5)
                .build()
        }

        Thread {
            runCatching {
                RuntimeInstaller.install(this) { text -> runOnUiThread { status.text = text } }
            }.onFailure { error ->
                runOnUiThread { status.text = "Runtime error: ${error.message}" }
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
