package app.humanrouter

import android.Manifest
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
import androidx.appcompat.widget.SwitchCompat
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
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File
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
    private lateinit var settingsPanel: LinearLayout
    private lateinit var retryButton: Button
    private lateinit var routeButton: Button
    private lateinit var checkDataButton: Button
    private lateinit var searchHandle: ImageButton
    private lateinit var navHandle: ImageButton
    private lateinit var settingsHandle: ImageButton
    private lateinit var leftEdgeZone: View
    private lateinit var rightEdgeZone: View
    private lateinit var rotateMapSwitch: SwitchCompat
    private lateinit var tiltMapSwitch: SwitchCompat
    private lateinit var runtimeVersionText: TextView

    private var map: MapLibreMap? = null
    private var runtimeReady = false
    private var globalDownX = 0f
    private var globalDownY = 0f

    private val preferences by lazy {
        getSharedPreferences("human_router_settings", MODE_PRIVATE)
    }

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
        settingsPanel = findViewById(R.id.settingsPanel)
        retryButton = findViewById(R.id.retryButton)
        routeButton = findViewById(R.id.routeButton)
        checkDataButton = findViewById(R.id.checkDataButton)
        searchHandle = findViewById(R.id.searchHandle)
        navHandle = findViewById(R.id.navHandle)
        settingsHandle = findViewById(R.id.settingsHandle)
        leftEdgeZone = findViewById(R.id.leftEdgeZone)
        rightEdgeZone = findViewById(R.id.rightEdgeZone)
        rotateMapSwitch = findViewById(R.id.rotateMapSwitch)
        tiltMapSwitch = findViewById(R.id.tiltMapSwitch)
        runtimeVersionText = findViewById(R.id.runtimeVersionText)

        applySystemInsets()
        configureSettings()

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { readyMap ->
            map = readyMap
            readyMap.setStyle(Style.Builder().fromUri("asset://map_style.json"))
            readyMap.cameraPosition = CameraPosition.Builder()
                .target(LatLng(55.751244, 37.618423))
                .zoom(11.0)
                .build()
            applyMapSettings()
        }
        mapView.addOnDidFailLoadingMapListener { error ->
            Toast.makeText(this, "Не удалось загрузить карту: $error", Toast.LENGTH_SHORT).show()
        }

        routeButton.setOnClickListener {
            closeLeftDrawer(searchPanel)
            if (!runtimeReady) {
                Toast.makeText(this, "Данные Москвы ещё загружаются", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Выберите точки «Откуда» и «Куда»", Toast.LENGTH_SHORT).show()
            }
        }
        retryButton.setOnClickListener { enqueueRuntimeDownload(replace = true) }
        checkDataButton.setOnClickListener {
            enqueueRuntimeDownload(replace = true)
            Toast.makeText(this, "Проверка данных запущена в фоне", Toast.LENGTH_SHORT).show()
        }

        searchHandle.setOnClickListener { toggleSearchDrawer() }
        navHandle.setOnClickListener { toggleNavDrawer() }
        settingsHandle.setOnClickListener { toggleSettingsDrawer() }
        attachLeftEdgeSwipe()
        attachRightEdgeSwipe()

        requestNotificationPermissionIfNeeded()
        observeRuntimeDownload()
        enqueueRuntimeDownload(replace = false)
        refreshRuntimeVersion()
    }

    private fun configureSettings() {
        rotateMapSwitch.isChecked = preferences.getBoolean(PREF_ROTATE, true)
        tiltMapSwitch.isChecked = preferences.getBoolean(PREF_TILT, false)

        rotateMapSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(PREF_ROTATE, checked).apply()
            applyMapSettings()
        }
        tiltMapSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(PREF_TILT, checked).apply()
            applyMapSettings()
        }
    }

    private fun applyMapSettings() {
        val ui = map?.uiSettings ?: return
        ui.setRotateGesturesEnabled(rotateMapSwitch.isChecked)
        ui.setTiltGesturesEnabled(tiltMapSwitch.isChecked)
    }

    private fun refreshRuntimeVersion() {
        val manifestFile = File(filesDir, "runtime/manifest.json")
        runtimeVersionText.text = runCatching {
            val version = JSONObject(manifestFile.readText()).getString("version")
            "Runtime: $version"
        }.getOrElse {
            "Runtime: ещё не установлен"
        }
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
                        refreshRuntimeVersion()
                    }
                    WorkInfo.State.FAILED -> {
                        runtimeReady = false
                        loadingPanel.visibility = View.VISIBLE
                        status.text = "Ошибка данных: ${info.outputData.getString(RuntimeDownloadWorker.KEY_ERROR) ?: "неизвестно"}"
                        retryButton.visibility = View.VISIBLE
                        refreshRuntimeVersion()
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
                if (abs(dx) > 80f && abs(dx) > abs(dy)) {
                    if (dx < 0f) {
                        val leftTarget = when {
                            searchPanel.visibility == View.VISIBLE && pointInside(searchPanel, globalDownX, globalDownY) -> searchPanel
                            bottomNav.visibility == View.VISIBLE && pointInside(bottomNav, globalDownX, globalDownY) -> bottomNav
                            else -> null
                        }
                        if (leftTarget != null) {
                            closeLeftDrawer(leftTarget)
                            return true
                        }
                    } else if (
                        settingsPanel.visibility == View.VISIBLE &&
                        pointInside(settingsPanel, globalDownX, globalDownY)
                    ) {
                        closeRightDrawer()
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
        if (searchPanel.visibility == View.VISIBLE) closeLeftDrawer(searchPanel) else openSearchDrawer()
    }

    private fun toggleNavDrawer() {
        if (bottomNav.visibility == View.VISIBLE) closeLeftDrawer(bottomNav) else openNavDrawer()
    }

    private fun toggleSettingsDrawer() {
        if (settingsPanel.visibility == View.VISIBLE) closeRightDrawer() else openSettingsDrawer()
    }

    private fun openSearchDrawer() {
        closeLeftDrawer(bottomNav)
        closeRightDrawer()
        searchPanel.visibility = View.VISIBLE
        searchPanel.post {
            searchPanel.translationX = -resources.displayMetrics.widthPixels.toFloat()
            searchPanel.animate().translationX(0f).setDuration(190).start()
        }
    }

    private fun openNavDrawer() {
        closeLeftDrawer(searchPanel)
        closeRightDrawer()
        bottomNav.visibility = View.VISIBLE
        bottomNav.post {
            bottomNav.translationX = -bottomNav.width.toFloat() - 24f
            bottomNav.animate().translationX(0f).setDuration(180).start()
        }
    }

    private fun openSettingsDrawer() {
        closeLeftDrawer(searchPanel)
        closeLeftDrawer(bottomNav)
        refreshRuntimeVersion()
        settingsPanel.visibility = View.VISIBLE
        settingsPanel.post {
            settingsPanel.translationX = resources.displayMetrics.widthPixels.toFloat()
            settingsPanel.animate().translationX(0f).setDuration(190).start()
        }
    }

    private fun closeLeftDrawer(view: View) {
        if (view.visibility != View.VISIBLE) return
        val distance = if (view === searchPanel) {
            -resources.displayMetrics.widthPixels.toFloat()
        } else {
            -(if (view.width > 0) view.width.toFloat() else 700f) - 24f
        }
        view.animate()
            .translationX(distance)
            .setDuration(160)
            .withEndAction {
                view.visibility = View.INVISIBLE
                view.translationX = 0f
            }
            .start()
    }

    private fun closeRightDrawer() {
        if (settingsPanel.visibility != View.VISIBLE) return
        settingsPanel.animate()
            .translationX(resources.displayMetrics.widthPixels.toFloat())
            .setDuration(160)
            .withEndAction {
                settingsPanel.visibility = View.INVISIBLE
                settingsPanel.translationX = 0f
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

    private fun attachRightEdgeSwipe() {
        var downX = 0f
        var downY = 0f
        rightEdgeZone.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dx < -70f && abs(dx) > abs(dy)) {
                        openSettingsDrawer()
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

    companion object {
        private const val PREF_ROTATE = "map_rotate"
        private const val PREF_TILT = "map_tilt"
    }
}
