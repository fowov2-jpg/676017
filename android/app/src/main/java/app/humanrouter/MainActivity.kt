package app.humanrouter

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
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
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var loadingPanel: LinearLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var searchPanel: LinearLayout
    private lateinit var retryButton: Button
    private lateinit var routeButton: Button
    private lateinit var settingsButton: TextView
    private lateinit var searchHandle: TextView
    private lateinit var navHandle: TextView
    private lateinit var leftEdgeZone: View

    private var runtimeReady = false
    private var globalDownX = 0f
    private var globalDownY = 0f

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
        searchPanel = findViewById(R.id.searchPanel)
        retryButton = findViewById(R.id.retryButton)
        routeButton = findViewById(R.id.routeButton)
        settingsButton = findViewById(R.id.settingsButton)
        searchHandle = findViewById(R.id.searchHandle)
        navHandle = findViewById(R.id.navHandle)
        leftEdgeZone = findViewById(R.id.leftEdgeZone)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(55.751244, 37.618423))
                .zoom(10.5)
                .build()
        }

        routeButton.setOnClickListener {
            closeDrawer(searchPanel)
            status.text = "Выберите точки «Откуда» и «Куда»"
            loadingPanel.visibility = View.VISIBLE
        }
        retryButton.setOnClickListener { startRuntimeInstall() }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        searchHandle.setOnClickListener { toggleSearchDrawer() }
        navHandle.setOnClickListener { toggleNavDrawer() }
        attachLeftEdgeSwipe()

        startRuntimeInstall()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                globalDownX = event.rawX
                globalDownY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - globalDownX
                val dy = event.rawY - globalDownY
                if (dx < -80f && abs(dx) > abs(dy)) {
                    val target = when {
                        searchPanel.visibility == View.VISIBLE && pointInside(searchPanel, globalDownX, globalDownY) -> searchPanel
                        bottomNav.visibility == View.VISIBLE && pointInside(bottomNav, globalDownX, globalDownY) -> bottomNav
                        else -> null
                    }
                    if (target != null) {
                        closeDrawer(target)
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun pointInside(view: View, rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return rawX >= location[0] && rawX <= location[0] + view.width &&
            rawY >= location[1] && rawY <= location[1] + view.height
    }

    private fun toggleSearchDrawer() {
        if (searchPanel.visibility == View.VISIBLE) closeDrawer(searchPanel) else openSearchDrawer()
    }

    private fun toggleNavDrawer() {
        if (!runtimeReady) return
        if (bottomNav.visibility == View.VISIBLE) closeDrawer(bottomNav) else openNavDrawer()
    }

    private fun openSearchDrawer() {
        closeDrawer(bottomNav)
        searchPanel.visibility = View.VISIBLE
        searchPanel.post {
            searchPanel.translationX = -searchPanel.width.toFloat() - 24f
            searchPanel.animate().translationX(0f).setDuration(190).start()
        }
    }

    private fun openNavDrawer() {
        if (!runtimeReady) return
        closeDrawer(searchPanel)
        bottomNav.visibility = View.VISIBLE
        bottomNav.post {
            bottomNav.translationX = -bottomNav.width.toFloat() - 24f
            bottomNav.animate().translationX(0f).setDuration(190).start()
        }
    }

    private fun closeDrawer(view: View) {
        if (view.visibility != View.VISIBLE) return
        val width = if (view.width > 0) view.width.toFloat() else 700f
        view.animate()
            .translationX(-width - 24f)
            .setDuration(170)
            .withEndAction {
                view.visibility = View.INVISIBLE
                view.translationX = 0f
            }
            .start()
    }

    private fun attachLeftEdgeSwipe() {
        var downX = 0f
        var downY = 0f
        leftEdgeZone.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dx > 70f && abs(dx) > abs(dy)) {
                        val split = resources.displayMetrics.heightPixels * 0.90f
                        if (downY >= split && runtimeReady) openNavDrawer() else openSearchDrawer()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun startRuntimeInstall() {
        runtimeReady = false
        retryButton.visibility = View.GONE
        loadingPanel.visibility = View.VISIBLE
        navHandle.visibility = View.GONE
        closeDrawer(bottomNav)
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
                            runtimeReady = true
                            loadingPanel.visibility = View.GONE
                            navHandle.visibility = View.VISIBLE
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
