package app.humanrouter

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
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
import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.HumanRouterEngine
import app.humanrouter.routing.RouteLeg
import app.humanrouter.routing.RouteObjective
import app.humanrouter.routing.TransportMode
import app.humanrouter.search.PhotonGeocoder
import app.humanrouter.search.SearchPlace
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil

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
    private lateinit var suggestionsPanel: LinearLayout
    private lateinit var routeResultsPanel: LinearLayout
    private lateinit var retryButton: Button
    private lateinit var routeButton: Button
    private lateinit var checkDataButton: Button
    private lateinit var fromField: EditText
    private lateinit var toField: EditText
    private lateinit var searchHandle: ImageButton
    private lateinit var navHandle: ImageButton
    private lateinit var settingsHandle: ImageButton
    private lateinit var leftEdgeZone: View
    private lateinit var rightEdgeZone: View
    private lateinit var rotateMapSwitch: SwitchCompat
    private lateinit var tiltMapSwitch: SwitchCompat
    private lateinit var runtimeVersionText: TextView
    private lateinit var mapNavButton: TextView
    private lateinit var routesNavButton: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val zoneId = ZoneId.of("Europe/Moscow")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val engine by lazy { HumanRouterEngine(this) }
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }

    private var map: MapLibreMap? = null
    private var runtimeReady = false
    private var globalDownX = 0f
    private var globalDownY = 0f
    private var currentLocation: GeoPoint? = null
    private var selectedFrom: SearchPlace? = null
    private var selectedTo: SearchPlace? = null
    private var searchSerial = 0
    private var suppressSearchWatcher = false

    private val preferences by lazy {
        getSharedPreferences("human_router_settings", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        bindViews()
        applySystemInsets()
        configureSettings()
        configureMap(savedInstanceState)
        configureSearch()
        configureNavigation()

        retryButton.setOnClickListener { enqueueRuntimeDownload(replace = true) }
        checkDataButton.setOnClickListener {
            enqueueRuntimeDownload(replace = true)
            Toast.makeText(this, "Проверка данных запущена в фоне", Toast.LENGTH_SHORT).show()
        }
        routeButton.setOnClickListener { planRouteNow() }

        searchHandle.setOnClickListener { toggleSearchDrawer() }
        navHandle.setOnClickListener { toggleNavDrawer() }
        settingsHandle.setOnClickListener { toggleSettingsDrawer() }
        attachLeftEdgeSwipe()
        attachRightEdgeSwipe()

        requestNotificationPermissionIfNeeded()
        requestLocationPermissionIfNeeded()
        observeRuntimeDownload()
        enqueueRuntimeDownload(replace = false)
        refreshRuntimeVersion()
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        mapView = findViewById(R.id.mapView)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)
        loadingPanel = findViewById(R.id.loadingPanel)
        bottomNav = findViewById(R.id.bottomNav)
        searchPanel = findViewById(R.id.searchPanel)
        settingsPanel = findViewById(R.id.settingsPanel)
        suggestionsPanel = findViewById(R.id.suggestionsPanel)
        routeResultsPanel = findViewById(R.id.routeResultsPanel)
        retryButton = findViewById(R.id.retryButton)
        routeButton = findViewById(R.id.routeButton)
        checkDataButton = findViewById(R.id.checkDataButton)
        fromField = findViewById(R.id.fromField)
        toField = findViewById(R.id.toField)
        searchHandle = findViewById(R.id.searchHandle)
        navHandle = findViewById(R.id.navHandle)
        settingsHandle = findViewById(R.id.settingsHandle)
        leftEdgeZone = findViewById(R.id.leftEdgeZone)
        rightEdgeZone = findViewById(R.id.rightEdgeZone)
        rotateMapSwitch = findViewById(R.id.rotateMapSwitch)
        tiltMapSwitch = findViewById(R.id.tiltMapSwitch)
        runtimeVersionText = findViewById(R.id.runtimeVersionText)
        mapNavButton = findViewById(R.id.mapNavButton)
        routesNavButton = findViewById(R.id.routesNavButton)
    }

    private fun configureMap(savedInstanceState: Bundle?) {
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
    }

    private fun configureSearch() {
        bindSearchField(fromField, isOrigin = true)
        bindSearchField(toField, isOrigin = false)
        fromField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && currentLocation != null && fromField.text.isBlank()) {
                renderUseCurrentLocation()
            }
        }
    }

    private fun bindSearchField(field: EditText, isOrigin: Boolean) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressSearchWatcher) return
                if (isOrigin) selectedFrom = null else selectedTo = null
                val query = s?.toString()?.trim().orEmpty()
                if (query.length < 3 || query == CURRENT_LOCATION_LABEL) {
                    suggestionsPanel.removeAllViews()
                    if (isOrigin && field.hasFocus() && currentLocation != null) renderUseCurrentLocation()
                    return
                }
                val token = ++searchSerial
                mainHandler.postDelayed({
                    if (token != searchSerial || !field.hasFocus()) return@postDelayed
                    executor.execute {
                        val results = runCatching { PhotonGeocoder.search(query, currentLocation) }.getOrDefault(emptyList())
                        runOnUiThread {
                            if (token == searchSerial && field.hasFocus() && field.text.toString().trim() == query) {
                                renderSuggestions(field, isOrigin, results)
                            }
                        }
                    }
                }, 450)
            }
        })
    }

    private fun renderUseCurrentLocation() {
        suggestionsPanel.removeAllViews()
        val point = currentLocation ?: return
        val row = suggestionView(CURRENT_LOCATION_LABEL, "GPS")
        row.setOnClickListener {
            selectedFrom = SearchPlace(CURRENT_LOCATION_LABEL, "GPS", point)
            setFieldText(fromField, CURRENT_LOCATION_LABEL)
            suggestionsPanel.removeAllViews()
            hideKeyboard()
        }
        suggestionsPanel.addView(row)
    }

    private fun renderSuggestions(field: EditText, isOrigin: Boolean, results: List<SearchPlace>) {
        suggestionsPanel.removeAllViews()
        if (isOrigin && currentLocation != null) {
            val current = suggestionView(CURRENT_LOCATION_LABEL, "GPS")
            current.setOnClickListener {
                selectedFrom = SearchPlace(CURRENT_LOCATION_LABEL, "GPS", currentLocation!!)
                setFieldText(fromField, CURRENT_LOCATION_LABEL)
                suggestionsPanel.removeAllViews()
                hideKeyboard()
            }
            suggestionsPanel.addView(current)
        }
        for (place in results.take(6)) {
            val row = suggestionView(place.title, place.subtitle)
            row.setOnClickListener {
                if (isOrigin) selectedFrom = place else selectedTo = place
                setFieldText(field, listOf(place.title, place.subtitle).filter { it.isNotBlank() }.joinToString(", "))
                suggestionsPanel.removeAllViews()
                hideKeyboard()
            }
            suggestionsPanel.addView(row)
        }
    }

    private fun suggestionView(title: String, subtitle: String): TextView {
        return TextView(this).apply {
            text = if (subtitle.isBlank()) title else "$title\n$subtitle"
            textSize = 14f
            setTextColor(0xFF111827.toInt())
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
    }

    private fun setFieldText(field: EditText, value: String) {
        suppressSearchWatcher = true
        field.setText(value)
        field.setSelection(value.length)
        suppressSearchWatcher = false
    }

    private fun planRouteNow() {
        if (!runtimeReady) {
            Toast.makeText(this, "Данные Москвы ещё загружаются", Toast.LENGTH_SHORT).show()
            return
        }
        val fromText = fromField.text.toString().trim()
        val toText = toField.text.toString().trim()
        if (toText.length < 2 && selectedTo == null) {
            toField.requestFocus()
            Toast.makeText(this, "Укажите, куда едем", Toast.LENGTH_SHORT).show()
            return
        }

        hideKeyboard()
        routeButton.isEnabled = false
        routeButton.text = "Считаем…"
        suggestionsPanel.removeAllViews()

        executor.execute {
            val originPlace = resolveOrigin(fromText)
            val destinationPlace = resolveDestination(toText)
            if (originPlace == null || destinationPlace == null) {
                runOnUiThread {
                    routeButton.isEnabled = true
                    routeButton.text = "Найти маршрут"
                    Toast.makeText(
                        this,
                        if (originPlace == null) "Не удалось определить точку отправления" else "Не удалось найти место назначения",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@execute
            }

            val result = engine.planOptions(
                origin = originPlace.point,
                destination = destinationPlace.point,
                departureEpochSec = Instant.now().epochSecond
            )
            runOnUiThread {
                routeButton.isEnabled = true
                routeButton.text = "Найти маршрут"
                if (selectedFrom == null) selectedFrom = originPlace
                if (selectedTo == null) selectedTo = destinationPlace
                renderPlanResult(result, originPlace, destinationPlace)
            }
        }
    }

    private fun resolveOrigin(text: String): SearchPlace? {
        selectedFrom?.let { return it }
        if (text.isBlank() || text.equals(CURRENT_LOCATION_LABEL, ignoreCase = true)) {
            return currentLocation?.let { SearchPlace(CURRENT_LOCATION_LABEL, "GPS", it) }
        }
        return runCatching { PhotonGeocoder.search(text, currentLocation, 1).firstOrNull() }.getOrNull()
    }

    private fun resolveDestination(text: String): SearchPlace? {
        selectedTo?.let { return it }
        if (text.isBlank()) return null
        return runCatching { PhotonGeocoder.search(text, currentLocation, 1).firstOrNull() }.getOrNull()
    }

    private fun renderPlanResult(
        result: HumanRouterEngine.PlanResult,
        origin: SearchPlace,
        destination: SearchPlace
    ) {
        routeResultsPanel.removeAllViews()
        when (result) {
            is HumanRouterEngine.PlanResult.Success -> {
                addResultText("Варианты маршрута", 20f, true)
                addResultText("${origin.title} → ${destination.title}", 13f, false)
                result.routes.forEachIndexed { index, ranked ->
                    val route = ranked.route
                    val durationMin = maxOf(1, ceil(route.totalSeconds / 60.0).toInt())
                    val arrival = Instant.ofEpochSecond(route.arrivalEpochSec).atZone(zoneId).format(timeFormatter)
                    val successPct = (ranked.transferSuccessProbability * 100).toInt().coerceIn(0, 100)
                    val label = if (index == 0) "Самый быстрый" else objectiveLabel(ranked.objective)
                    val legs = route.legs.joinToString(" → ") { shortLeg(it) }
                    val risk = if (route.transferCount > 0) " · пересадка $successPct%" else ""
                    addResultText(
                        "$label · $durationMin мин · до $arrival\n$legs\nПешком ${route.walkMeters} м · пересадок ${route.transferCount}$risk",
                        14f,
                        index == 0,
                        card = true
                    )
                }
                addResultAction("Изменить маршрут") {
                    routeResultsPanel.visibility = View.GONE
                    openSearchDrawer()
                }
                searchPanel.visibility = View.INVISIBLE
                routeResultsPanel.visibility = View.VISIBLE
            }
            is HumanRouterEngine.PlanResult.RuntimeMissing -> showPlanError(result.reason)
            is HumanRouterEngine.PlanResult.ScheduleUnavailable -> showPlanError(
                "Для выбранного времени нет актуального расписания. Данные: ${result.serviceDate ?: "нет"}, запрос: ${result.requestedDate}"
            )
            is HumanRouterEngine.PlanResult.Failure -> showPlanError(result.reason)
        }
    }

    private fun objectiveLabel(objective: RouteObjective): String = when (objective) {
        RouteObjective.FASTEST -> "Быстрый"
        RouteObjective.RELIABLE -> "Надёжнее"
        RouteObjective.LESS_WALKING -> "Меньше пешком"
        RouteObjective.FEWER_TRANSFERS -> "Меньше пересадок"
    }

    private fun shortLeg(leg: RouteLeg): String = when (leg.mode) {
        TransportMode.WALK -> "пешком ${leg.walkMeters} м"
        TransportMode.BUS -> "автобус ${leg.lineName ?: leg.lineId.orEmpty()}"
        TransportMode.TRAM -> "трамвай ${leg.lineName ?: leg.lineId.orEmpty()}"
        TransportMode.METRO -> "метро ${leg.lineName ?: ""}"
        TransportMode.MCC -> "МЦК ${leg.lineName ?: ""}"
        TransportMode.MCD -> "МЦД ${leg.lineName ?: ""}"
        TransportMode.TRAIN -> "поезд ${leg.lineName ?: ""}"
    }

    private fun showPlanError(message: String) {
        addResultText("Маршрут не построен", 18f, true)
        addResultText(message, 13f, false)
        addResultAction("Изменить точки") {
            routeResultsPanel.visibility = View.GONE
            openSearchDrawer()
        }
        searchPanel.visibility = View.INVISIBLE
        routeResultsPanel.visibility = View.VISIBLE
    }

    private fun addResultText(textValue: String, size: Float, bold: Boolean, card: Boolean = false) {
        routeResultsPanel.addView(TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(0xFF111827.toInt())
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            if (card) background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(5) }
        })
    }

    private fun addResultAction(label: String, action: () -> Unit) {
        routeResultsPanel.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { topMargin = dp(8) }
        })
    }

    private fun configureNavigation() {
        mapNavButton.setOnClickListener {
            routeResultsPanel.visibility = View.GONE
            closeLeftDrawer(bottomNav)
        }
        routesNavButton.setOnClickListener {
            routeResultsPanel.visibility = View.GONE
            openSearchDrawer()
        }
    }

    private fun requestLocationPermissionIfNeeded() {
        if (hasLocationPermission()) {
            refreshCurrentLocation()
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            LOCATION_PERMISSION_REQUEST
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")
    private fun refreshCurrentLocation() {
        if (!hasLocationPermission()) return
        val providers = runCatching { locationManager.getProviders(true) }.getOrDefault(emptyList())
        val last = providers.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull(Location::getTime)
        last?.let { acceptLocation(it) }

        val provider = when {
            providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        } ?: return

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                acceptLocation(location)
                runCatching { locationManager.removeUpdates(this) }
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        runCatching { locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
    }

    private fun acceptLocation(location: Location) {
        currentLocation = GeoPoint(location.latitude, location.longitude)
        if (fromField.text.isBlank()) {
            selectedFrom = SearchPlace(CURRENT_LOCATION_LABEL, "GPS", currentLocation!!)
            setFieldText(fromField, CURRENT_LOCATION_LABEL)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            refreshCurrentLocation()
        }
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
        }.getOrElse { "Runtime: ещё не установлен" }
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
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4107)
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
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
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
                    } else if (settingsPanel.visibility == View.VISIBLE && pointInside(settingsPanel, globalDownX, globalDownY)) {
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
        routeResultsPanel.visibility = View.GONE
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
        routeResultsPanel.visibility = View.GONE
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
        routeResultsPanel.visibility = View.GONE
        refreshRuntimeVersion()
        settingsPanel.visibility = View.VISIBLE
        settingsPanel.post {
            settingsPanel.translationX = resources.displayMetrics.widthPixels.toFloat()
            settingsPanel.animate().translationX(0f).setDuration(190).start()
        }
    }

    private fun closeLeftDrawer(view: View) {
        if (view.visibility != View.VISIBLE) return
        val distance = if (view === searchPanel) -resources.displayMetrics.widthPixels.toFloat()
        else -(if (view.width > 0) view.width.toFloat() else 700f) - 24f
        view.animate().translationX(distance).setDuration(160).withEndAction {
            view.visibility = View.INVISIBLE
            view.translationX = 0f
        }.start()
    }

    private fun closeRightDrawer() {
        if (settingsPanel.visibility != View.VISIBLE) return
        settingsPanel.animate().translationX(resources.displayMetrics.widthPixels.toFloat()).setDuration(160).withEndAction {
            settingsPanel.visibility = View.INVISIBLE
            settingsPanel.translationX = 0f
        }.start()
    }

    private fun attachLeftEdgeSwipe() {
        var downX = 0f
        var downY = 0f
        leftEdgeZone.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; true }
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
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; true }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dx < -70f && abs(dx) > abs(dy)) openSettingsDrawer()
                    true
                }
                else -> true
            }
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        currentFocus?.clearFocus()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onDestroy() { executor.shutdownNow(); mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    companion object {
        private const val PREF_ROTATE = "map_rotate"
        private const val PREF_TILT = "map_tilt"
        private const val LOCATION_PERMISSION_REQUEST = 4108
        private const val CURRENT_LOCATION_LABEL = "Моё местоположение"
    }
}
