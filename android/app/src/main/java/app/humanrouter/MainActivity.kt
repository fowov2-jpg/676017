package app.humanrouter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var root: View
    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var loadingPanel: LinearLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var searchPanel: LinearLayout
    private lateinit var retryButton: Button
    private lateinit var routeButton: Button
    private lateinit var settingsNavButton: TextView
    private lateinit var searchHandle: ImageButton
    private lateinit var navHandle: ImageButton
    private lateinit var leftEdgeZone: View

    private var runtimeReady = false
    private var globalDownX = 0f
    private var globalDownY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        mapView = findViewById(R.id.mapView)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)
        loadingPanel = findViewById(R.id.loadingPanel)
        bottomNav = findViewById(R.id.bottomNav)
        searchPanel = findViewById(R.id.searchPanel)
        retryButton = findViewById(R.id.retryButton)
        routeButton = findViewById(R.id.routeButton)
        settingsNavButton = findViewById(R.id.settingsNavButton)
        searchHandle = findViewById(R.id.searchHandle)
        navHandle = findViewById(R.id.navHandle)
        leftEdgeZone = findViewById(R.id.leftEdgeZone)

        applySystemInsets()

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri("asset://map_style.json"))
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(55.751244, 37.618423))
                .zoom(11.0)
                .build()
        }
        mapView.addOnDidFailLoadingMapListener { error ->
            Toast.makeText(this, "Не удалось загрузить карту: $error", Toast.LENGTH_SHORT).show()
        }

        routeButton.setOnClickListener {
            closeDrawer(searchPanel)
            if (!runtimeReady) {
                Toast.makeText(this, "Данные Москвы ещё загружаются", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Выберите точки «Откуда» и «Куда»", Toast.LENGTH_SHORT).show()
            }
        }
        retryButton.setOnClickListener { enqueueRuntimeDownload(replace = true) }
        settingsNavButton.setOnClickListener {
            closeDrawer(bottomNav)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        searchHandle.setOnClickListener { toggleSearchDrawer() }
        navHandle.setOnClickListener { toggleNavDrawer() }
        attachLeftEdgeSwipe()

        requestNotificationPermissionIfNeeded()
        observeRuntimeDownload()
        enqueueRuntimeDownload(replace = false)
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                4107
            )
        }
    }

    private fun enqueueRuntimeDownload(replace: Boolean) {
        runtimeReady = false
        retryButton.visibility = View.GONE
        loadingPanel.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Проверяем данные…"
        progressText.text = "0%"

        val request = OneTimeWorkRequestBuilder<RuntimeDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            RuntimeDownloadWorker.UNIQUE_WORK,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun observeRuntimeDownload() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(RuntimeDownloadWorker.UNIQUE_WORK)
            .observe(this) { infos ->
                val info = infos.lastOrNull() ?: return@observe
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        loadingPanel.visibility = View.VISIBLE
                        status.text = "Ожидаем сеть…"
                    }
                    WorkInfo.State.RUNNING -> {
                        loadingPanel.visibility = View.VISIBLE
                        retryButton.visibility = View.GONE
                        val p = info.progress
                        val percent = p.getInt(RuntimeDownloadWorker.KEY_PERCENT, 0)
                        progress.progress = percent
                        progressText.text = "$percent%"
                        status.text = p.getString(RuntimeDownloadWorker.KEY_MESSAGE) ?: "Загружаем данные…"
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        runtimeReady = true
                        progress.progress = 100
                        progressText.text = "100%"
                        loadingPanel.visibility = View.GONE
                    }
                    WorkInfo.State.FAILED -> {
                        runtimeReady = false
                        loadingPanel.visibility = View.VISIBLE
                        status.text = "Ошибка данных: ${info.outputData.getString(RuntimeDownloadWorker.KEY_ERROR) ?: "неизвестно"}"
                        retryButton.visibility = View.VISIBLE
                    }
                    WorkInfo.State.CANCELLED -> {
                        runtimeReady = false
                        loadingPanel.visibility = View.VISIBLE
                        status.text = "Загрузка остановлена"
                        retryButton.visibility = View.VISIBLE
                    }
                }
            }
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
        if (bottomNav.visibility == View.VISIBLE) closeDrawer(bottomNav) else openNavDrawer()
    }

    private fun openSearchDrawer() {
        closeDrawer(bottomNav)
        searchPanel.visibility = View.VISIBLE
        searchPanel.post {
            searchPanel.translationX = -searchPanel.width.toFloat() - 24f
            searchPanel.animate().translationX(0f).setDuration(180).start()
        }
    }

    private fun openNavDrawer() {
        closeDrawer(searchPanel)
        bottomNav.visibility = View.VISIBLE
        bottomNav.post {
            bottomNav.translationX = -bottomNav.width.toFloat() - 24f
            bottomNav.animate().translationX(0f).setDuration(180).start()
        }
    }

    private fun closeDrawer(view: View) {
        if (view.visibility != View.VISIBLE) return
        val width = if (view.width > 0) view.width.toFloat() else 700f
        view.animate()
            .translationX(-width - 24f)
            .setDuration(160)
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
                        val split = resources.displayMetrics.heightPixels * 0.82f
                        if (downY >= split) openNavDrawer() else openSearchDrawer()
                    }
                    true
                }
                else -> true
            }
        }
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
}
