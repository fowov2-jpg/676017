package app.humanrouter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.humanrouter.location.LocationState
import app.humanrouter.location.LocationStateMachine
import app.humanrouter.routing.GeoPoint
import app.humanrouter.routing.HumanRouterEngine
import app.humanrouter.routing.LastPlanStore
import app.humanrouter.routing.RankedRoute
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.RouteFilter
import app.humanrouter.routing.RouteFilters
import app.humanrouter.routing.RouteLeg
import app.humanrouter.routing.RouteObjective
import app.humanrouter.routing.RoutePlace
import app.humanrouter.routing.RouteRanker
import app.humanrouter.routing.TransportMode
import app.humanrouter.search.PhotonGeocoder
import app.humanrouter.search.SearchPlace
import app.humanrouter.transit.NearbyRepository
import app.humanrouter.transit.NearbyTransitPlace
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor

class MainActivity : AppCompatActivity() {
    private lateinit var root: View
    private lateinit var mapView: MapView
    private lateinit var searchPanel: LinearLayout
    private lateinit var compactSearchRow: LinearLayout
    private lateinit var compactSearchButton: TextView
    private lateinit var expandedSearchContent: LinearLayout
    private lateinit var quickActions: LinearLayout
    private lateinit var fromField: EditText
    private lateinit var toField: EditText
    private lateinit var suggestionsScroll: ScrollView
    private lateinit var suggestionsPanel: LinearLayout
    private lateinit var routeButton: Button
    private lateinit var closeSearchButton: TextView
    private lateinit var clearFromButton: TextView
    private lateinit var clearToButton: TextView

    private lateinit var loadingPanel: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var retryButton: Button
    private lateinit var journeyRow: LinearLayout
    private lateinit var journeyImage: ImageView
    private lateinit var journeyStageText: TextView

    private lateinit var locationButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var locationActionPanel: LinearLayout
    private lateinit var locationActionMessage: TextView
    private lateinit var locationPrimaryAction: Button
    private lateinit var locationSecondaryAction: Button

    private lateinit var nearbyPanel: LinearLayout
    private lateinit var nearbyStateText: TextView
    private lateinit var nearbyList: LinearLayout
    private lateinit var routeResultsContainer: LinearLayout
    private lateinit var routeFiltersScroll: HorizontalScrollView
    private lateinit var routeFiltersPanel: LinearLayout
    private lateinit var routeResultsScroll: ScrollView
    private lateinit var routeResultsPanel: LinearLayout
    private lateinit var tabEmptyPanel: LinearLayout
    private lateinit var tabEmptyTitle: TextView
    private lateinit var tabEmptyMessage: TextView
    private lateinit var osmAttribution: TextView

    private lateinit var bottomNav: LinearLayout
    private lateinit var mapNavButton: TextView
    private lateinit var routesNavButton: TextView
    private lateinit var transportNavButton: TextView
    private lateinit var favoritesNavButton: TextView

    private lateinit var settingsScrim: FrameLayout
    private lateinit var settingsPanel: LinearLayout
    private lateinit var closeSettingsButton: TextView
    private lateinit var showStopsSwitch: SwitchCompat
    private lateinit var showTransportSwitch: SwitchCompat
    private lateinit var darkThemeSwitch: SwitchCompat
    private lateinit var lessWalkingSwitch: SwitchCompat
    private lateinit var avoidTransfersSwitch: SwitchCompat
    private lateinit var checkDataButton: Button
    private lateinit var appVersionText: TextView
    private lateinit var runtimeVersionText: TextView

    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val nearbyRepository by lazy { NearbyRepository(this) }
    private val locationMachine = LocationStateMachine()
    private val zoneId = ZoneId.of("Europe/Moscow")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val preferences by lazy { AppPreferences.prefs(this) }

    private var map: MapLibreMap? = null
    private var routeSource: GeoJsonSource? = null
    private var routeStopsSource: GeoJsonSource? = null
    private var nearbySource: GeoJsonSource? = null
    private var locationSource: GeoJsonSource? = null
    private var routeLayer: LineLayer? = null
    private var routeStopsLayer: CircleLayer? = null
    private var nearbyLayer: CircleLayer? = null
    private var locationLayer: CircleLayer? = null

    private var runtimeReady = false
    private var runtimeBusy = false
    private var selectedFrom: SearchPlace? = null
    private var selectedTo: SearchPlace? = null
    private var currentLocation: GeoPoint? = null
    private var currentLocationListener: LocationListener? = null
    private var locationPurpose = LocationPurpose.NONE
    private var pendingRouteAfterLocation = false
    private var pendingTripRoute: RouteCandidate? = null
    private var activeTripRoute: RouteCandidate? = null
    private var locationPermissionPreviouslyRequested = false
    private var suppressSearchWatcher = false
    private var searchSerial = 0
    private var nearbySerial = 0
    private var searchExpanded = false
    private var currentTab = Tab.MAP
    private var routeFilter = RouteFilter.FASTEST
    private var allRoutes: List<RankedRoute> = emptyList()
    private var selectedRouteId: String? = null
    private var lastNearby: List<NearbyTransitPlace> = emptyList()
    private var routingEngine: HumanRouterEngine? = null
    private var routingPreferencesSignature = ""
    private var journeyFrameIndex = 0
    private var journeyAnimationRunning = false
    private var surfaceScheduleDate: LocalDate? = null
    private var railTimetableEffectiveFrom: LocalDate? = null
    private var debugQaActive = false

    private val journeyFrames by lazy {
        intArrayOf(
            R.drawable.journey_person,
            R.drawable.journey_stop,
            R.drawable.journey_bus,
            R.drawable.journey_metro,
            R.drawable.journey_train
        )
    }
    private val journeyDrawables by lazy {
        journeyFrames.map { resource -> AppCompatResources.getDrawable(this, resource) }
    }
    private val journeyStages = arrayOf(
        "Пешком к остановке",
        "Проверяем остановку",
        "Сверяем автобусы",
        "Добавляем метро",
        "Проверяем поезда"
    )
    private val journeyAnimationRunnable = object : Runnable {
        override fun run() {
            if (journeyRow.visibility != View.VISIBLE) return
            val index = journeyFrameIndex % journeyDrawables.size
            journeyImage.animate().cancel()
            journeyImage.alpha = 0.35f
            journeyImage.setImageDrawable(journeyDrawables[index])
            journeyStageText.text = journeyStages[index]
            journeyImage.animate().alpha(1f).setDuration(220L).start()
            journeyFrameIndex = (index + 1) % journeyDrawables.size
            mainHandler.postDelayed(this, JOURNEY_FRAME_MS)
        }
    }

    private val locationTimeoutRunnable = Runnable {
        stopLocationUpdates()
        val wasRequesting = locationMachine.state is LocationState.Requesting
        locationMachine.timeout()
        if (wasRequesting) renderLocationState()
    }

    private val nearbyRefreshRunnable = Runnable {
        if (currentTab == Tab.MAP || currentTab == Tab.TRANSPORT) {
            val target = map?.cameraPosition?.target
            target?.let { refreshNearby(GeoPoint(it.latitude, it.longitude)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val qaScreen = if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_QA_SCREEN) else null
        if (BuildConfig.DEBUG && intent.hasExtra(EXTRA_QA_DARK)) {
            delegate.localNightMode = if (intent.getBooleanExtra(EXTRA_QA_DARK, false)) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        }
        super.onCreate(savedInstanceState)
        debugQaActive = qaScreen != null
        WindowCompat.setDecorFitsSystemWindows(window, false)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        bindViews()
        applySystemInsets()
        configureMap(savedInstanceState)
        configureSearch()
        configureQuickActions()
        configureNavigation()
        configureSettings()
        if (debugQaActive) {
            runtimeReady = true
            loadingPanel.visibility = View.GONE
        } else {
            configureRuntime()
        }

        currentTab = Tab.fromStored(preferences.getString(AppPreferences.KEY_SELECTED_TAB, null))
        selectTab(currentTab, persist = false)
        if (qaScreen != null) {
            root.post { applyDebugQaScenario(qaScreen) }
        } else {
            ActiveTripStore.load(this)?.let { snapshot ->
                root.post { restoreActiveTrip(snapshot) }
            }
        }
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        mapView = findViewById(R.id.mapView)
        searchPanel = findViewById(R.id.searchPanel)
        compactSearchRow = findViewById(R.id.compactSearchRow)
        compactSearchButton = findViewById(R.id.compactSearchButton)
        expandedSearchContent = findViewById(R.id.expandedSearchContent)
        quickActions = findViewById(R.id.quickActions)
        fromField = findViewById(R.id.fromField)
        toField = findViewById(R.id.toField)
        suggestionsScroll = findViewById(R.id.suggestionsScroll)
        suggestionsPanel = findViewById(R.id.suggestionsPanel)
        routeButton = findViewById(R.id.routeButton)
        closeSearchButton = findViewById(R.id.closeSearchButton)
        clearFromButton = findViewById(R.id.clearFromButton)
        clearToButton = findViewById(R.id.clearToButton)

        loadingPanel = findViewById(R.id.loadingPanel)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)
        retryButton = findViewById(R.id.retryButton)
        journeyRow = findViewById(R.id.journeyRow)
        journeyImage = findViewById(R.id.journeyImage)
        journeyStageText = findViewById(R.id.journeyStageText)

        locationButton = findViewById(R.id.locationButton)
        settingsButton = findViewById(R.id.settingsButton)
        locationActionPanel = findViewById(R.id.locationActionPanel)
        locationActionMessage = findViewById(R.id.locationActionMessage)
        locationPrimaryAction = findViewById(R.id.locationPrimaryAction)
        locationSecondaryAction = findViewById(R.id.locationSecondaryAction)

        nearbyPanel = findViewById(R.id.nearbyPanel)
        nearbyStateText = findViewById(R.id.nearbyStateText)
        nearbyList = findViewById(R.id.nearbyList)
        routeResultsContainer = findViewById(R.id.routeResultsContainer)
        routeFiltersScroll = findViewById(R.id.routeFiltersScroll)
        routeFiltersPanel = findViewById(R.id.routeFiltersPanel)
        routeResultsScroll = findViewById(R.id.routeResultsScroll)
        routeResultsPanel = findViewById(R.id.routeResultsPanel)
        tabEmptyPanel = findViewById(R.id.tabEmptyPanel)
        tabEmptyTitle = findViewById(R.id.tabEmptyTitle)
        tabEmptyMessage = findViewById(R.id.tabEmptyMessage)
        osmAttribution = findViewById(R.id.osmAttribution)

        bottomNav = findViewById(R.id.bottomNav)
        mapNavButton = findViewById(R.id.mapNavButton)
        routesNavButton = findViewById(R.id.routesNavButton)
        transportNavButton = findViewById(R.id.transportNavButton)
        favoritesNavButton = findViewById(R.id.favoritesNavButton)

        settingsScrim = findViewById(R.id.settingsScrim)
        settingsPanel = findViewById(R.id.settingsPanel)
        closeSettingsButton = findViewById(R.id.closeSettingsButton)
        showStopsSwitch = findViewById(R.id.showStopsSwitch)
        showTransportSwitch = findViewById(R.id.showTransportSwitch)
        darkThemeSwitch = findViewById(R.id.darkThemeSwitch)
        lessWalkingSwitch = findViewById(R.id.lessWalkingSwitch)
        avoidTransfersSwitch = findViewById(R.id.avoidTransfersSwitch)
        checkDataButton = findViewById(R.id.checkDataButton)
        appVersionText = findViewById(R.id.appVersionText)
        runtimeVersionText = findViewById(R.id.runtimeVersionText)
    }

    private fun configureMap(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { readyMap ->
            map = readyMap
            readyMap.cameraPosition = CameraPosition.Builder()
                .target(LatLng(MOSCOW_LAT, MOSCOW_LON))
                .zoom(11.2)
                .build()
            val styleUri = if (AppPreferences.isDarkTheme(this)) {
                "asset://map_style_dark.json"
            } else {
                "asset://map_style.json"
            }
            readyMap.setStyle(Style.Builder().fromUri(styleUri)) { style ->
                configureMapLayers(style)
                renderCurrentLocationMarker()
                renderNearbyMarkers(lastNearby)
                selectedRoute()?.let {
                    renderRouteOnMap(it, fit = routeResultsContainer.visibility == View.VISIBLE)
                }
            }
            readyMap.addOnCameraIdleListener {
                if (debugQaActive) return@addOnCameraIdleListener
                mainHandler.removeCallbacks(nearbyRefreshRunnable)
                mainHandler.postDelayed(nearbyRefreshRunnable, NEARBY_CAMERA_DEBOUNCE_MS)
            }
        }
        mapView.addOnDidFailLoadingMapListener {
            showCompactStatus("Карта временно недоступна. Проверьте интернет.", retryVisible = false)
        }
        locationButton.setOnClickListener { requestLocation(LocationPurpose.CENTER) }
    }

    private fun configureMapLayers(style: Style) {
        routeSource = GeoJsonSource(ROUTE_SOURCE_ID, emptyFeatures()).also { style.addSource(it) }
        routeStopsSource = GeoJsonSource(ROUTE_STOPS_SOURCE_ID, emptyFeatures()).also { style.addSource(it) }
        nearbySource = GeoJsonSource(NEARBY_SOURCE_ID, emptyFeatures()).also { style.addSource(it) }
        locationSource = GeoJsonSource(LOCATION_SOURCE_ID, emptyFeatures()).also { style.addSource(it) }

        routeLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(ContextCompat.getColor(this, R.color.vh_primary)),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineOpacity(0.92f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        ).also { style.addLayer(it) }

        routeStopsLayer = CircleLayer(ROUTE_STOPS_LAYER_ID, ROUTE_STOPS_SOURCE_ID).withProperties(
            PropertyFactory.circleColor(ContextCompat.getColor(this, R.color.vh_surface_solid)),
            PropertyFactory.circleStrokeColor(ContextCompat.getColor(this, R.color.vh_primary)),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circleRadius(5f)
        ).also { style.addLayer(it) }

        nearbyLayer = CircleLayer(NEARBY_LAYER_ID, NEARBY_SOURCE_ID).withProperties(
            PropertyFactory.circleColor(ContextCompat.getColor(this, R.color.vh_primary)),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleRadius(6f)
        ).also { style.addLayer(it) }

        locationLayer = CircleLayer(LOCATION_LAYER_ID, LOCATION_SOURCE_ID).withProperties(
            PropertyFactory.circleColor(ContextCompat.getColor(this, R.color.vh_primary)),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circleRadius(8f)
        ).also { style.addLayer(it) }

        applyLayerPreferences()
    }

    private fun configureSearch() {
        compactSearchRow.setOnClickListener { expandSearch(focusDestination = true) }
        closeSearchButton.setOnClickListener { collapseSearch() }
        clearFromButton.setOnClickListener {
            selectedFrom = null
            setFieldText(fromField, "")
            fromField.requestFocus()
            renderOriginChoices()
        }
        clearToButton.setOnClickListener {
            selectedTo = null
            setFieldText(toField, "")
            compactSearchButton.text = ""
            toField.requestFocus()
            renderDestinationChoices()
        }
        routeButton.setOnClickListener { planRouteNow() }
        toField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                planRouteNow()
                true
            } else {
                false
            }
        }

        bindSearchField(fromField, isOrigin = true)
        bindSearchField(toField, isOrigin = false)
        fromField.setOnFocusChangeListener { _, focused ->
            if (focused && fromField.text.toString().trim().length < 3) renderOriginChoices()
        }
        toField.setOnFocusChangeListener { _, focused ->
            if (focused && toField.text.toString().trim().length < 3) renderDestinationChoices()
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
                if (!isOrigin) compactSearchButton.text = query
                if (query.length < 3 || query == CURRENT_LOCATION_LABEL) {
                    if (field.hasFocus()) {
                        if (isOrigin) renderOriginChoices() else renderDestinationChoices()
                    } else {
                        hideSuggestions()
                    }
                    return
                }

                val token = ++searchSerial
                showSuggestionMessage("Ищем…")
                mainHandler.postDelayed({
                    if (token != searchSerial || !field.hasFocus()) return@postDelayed
                    executor.execute {
                        val result = runCatching { PhotonGeocoder.search(query, currentLocation) }
                        runOnUiThread {
                            if (token != searchSerial || !field.hasFocus() || field.text.toString().trim() != query) {
                                return@runOnUiThread
                            }
                            result.fold(
                                onSuccess = { places -> renderSuggestions(field, isOrigin, places) },
                                onFailure = { showSuggestionMessage("Не удалось выполнить поиск. Проверьте интернет.") }
                            )
                        }
                    }
                }, SEARCH_DEBOUNCE_MS)
            }
        })
    }

    private fun renderOriginChoices() {
        suggestionsPanel.removeAllViews()
        suggestionsPanel.addView(suggestionView(CURRENT_LOCATION_LABEL, "По геопозиции").apply {
            setOnClickListener {
                hideKeyboard()
                requestLocation(LocationPurpose.ORIGIN)
            }
        })
        suggestionsPanel.addView(suggestionView("Точка на карте", "Использовать центр карты").apply {
            setOnClickListener { chooseOriginFromMap() }
        })
        suggestionsScroll.visibility = View.VISIBLE
    }

    private fun renderDestinationChoices() {
        suggestionsPanel.removeAllViews()
        suggestionsPanel.addView(suggestionView("Точка на карте", "Использовать центр карты как место назначения").apply {
            setOnClickListener { chooseDestinationFromMap() }
        })
        suggestionsScroll.visibility = View.VISIBLE
    }

    private fun renderSuggestions(field: EditText, isOrigin: Boolean, results: List<SearchPlace>) {
        suggestionsPanel.removeAllViews()
        if (isOrigin) {
            suggestionsPanel.addView(suggestionView(CURRENT_LOCATION_LABEL, "По геопозиции").apply {
                setOnClickListener {
                    hideKeyboard()
                    requestLocation(LocationPurpose.ORIGIN)
                }
            })
        } else {
            suggestionsPanel.addView(suggestionView("Точка на карте", "Использовать центр карты как место назначения").apply {
                setOnClickListener { chooseDestinationFromMap() }
            })
        }
        if (results.isEmpty()) {
            showSuggestionMessage("Ничего не найдено. Попробуйте уточнить адрес.")
            return
        }
        for (place in results.take(MAX_SEARCH_RESULTS)) {
            suggestionsPanel.addView(suggestionView(place.title, place.subtitle).apply {
                setOnClickListener {
                    if (isOrigin) selectedFrom = place else selectedTo = place
                    setFieldText(field, place.displayLabel())
                    if (!isOrigin) compactSearchButton.text = place.title
                    hideSuggestions()
                    hideKeyboard()
                }
            })
        }
        suggestionsScroll.visibility = View.VISIBLE
    }

    private fun suggestionView(title: String, subtitle: String): TextView = TextView(this).apply {
        text = if (subtitle.isBlank()) title else "$title\n$subtitle"
        textSize = 14f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_primary))
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
        backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this@MainActivity, R.color.vh_surface_muted)
        )
        minHeight = dp(52)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
    }

    private fun showSuggestionMessage(message: String) {
        suggestionsPanel.removeAllViews()
        suggestionsPanel.addView(TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_tertiary))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        })
        suggestionsScroll.visibility = View.VISIBLE
    }

    private fun hideSuggestions() {
        suggestionsPanel.removeAllViews()
        suggestionsScroll.visibility = View.GONE
    }

    private fun expandSearch(focusDestination: Boolean) {
        if (searchExpanded) {
            if (focusDestination) toField.requestFocus()
            return
        }
        searchExpanded = true
        compactSearchRow.visibility = View.GONE
        expandedSearchContent.visibility = View.VISIBLE
        quickActions.visibility = View.GONE
        loadingPanel.translationY = dp(118).toFloat()
        routeResultsContainer.visibility = View.GONE
        tabEmptyPanel.visibility = View.GONE
        if (focusDestination) {
            toField.requestFocus()
            showKeyboard(toField)
        }
    }

    private fun collapseSearch() {
        searchExpanded = false
        hideKeyboard()
        hideSuggestions()
        expandedSearchContent.visibility = View.GONE
        compactSearchRow.visibility = View.VISIBLE
        quickActions.visibility = View.VISIBLE
        loadingPanel.translationY = 0f
        compactSearchButton.text = selectedTo?.title ?: toField.text.toString().trim()
    }

    private fun configureQuickActions() {
        bindSavedPlace(findViewById(R.id.homeQuickButton), AppPreferences.KEY_HOME, getString(R.string.quick_home))
        bindSavedPlace(findViewById(R.id.workQuickButton), AppPreferences.KEY_WORK, getString(R.string.quick_work))
        findViewById<TextView>(R.id.nearbyQuickButton).setOnClickListener {
            selectTab(Tab.TRANSPORT)
        }
    }

    private fun bindSavedPlace(view: TextView, key: String, label: String) {
        view.setOnClickListener {
            val saved = preferences.getString(key, null)
            if (saved.isNullOrBlank()) {
                expandSearch(focusDestination = true)
                Toast.makeText(this, "Введите адрес и удерживайте «$label», чтобы сохранить", Toast.LENGTH_LONG).show()
            } else {
                expandSearch(focusDestination = false)
                selectedTo = null
                setFieldText(toField, saved)
                compactSearchButton.text = saved
                planRouteNow()
            }
        }
        view.setOnLongClickListener {
            val value = toField.text.toString().trim()
            if (value.length < 3) {
                expandSearch(focusDestination = true)
                Toast.makeText(this, "Сначала введите адрес", Toast.LENGTH_SHORT).show()
            } else {
                preferences.edit().putString(key, value).apply()
                Toast.makeText(this, "$label сохранён", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun planRouteNow() {
        val fromText = fromField.text.toString().trim()
        val toText = toField.text.toString().trim()
        if (selectedTo == null && toText.length < 2) {
            expandSearch(focusDestination = true)
            showSuggestionMessage("Укажите место назначения")
            return
        }
        if (selectedFrom == null && (fromText.isBlank() || fromText == CURRENT_LOCATION_LABEL) && currentLocation == null) {
            pendingRouteAfterLocation = true
            requestLocation(LocationPurpose.ORIGIN)
            return
        }
        if (!runtimeReady) {
            showPlanError("Данные ещё не готовы", "Дождитесь окончания загрузки или повторите её.")
            return
        }

        setPlanBusy(true)
        hideKeyboard()
        hideSuggestions()
        val token = ++searchSerial
        executor.execute {
            val origin = resolveOrigin(fromText)
            val destination = resolveDestination(toText)
            if (token != searchSerial) return@execute
            if (origin == null || destination == null) {
                runOnUiThread {
                    setPlanBusy(false)
                    val message = if (origin == null) {
                        "Не удалось найти точку отправления. Выберите подсказку или точку на карте."
                    } else {
                        "Не удалось найти место назначения. Выберите один из результатов поиска."
                    }
                    showPlanError("Проверьте адрес", message)
                }
                return@execute
            }

            val result = engineForCurrentPreferences().planOptions(
                origin = origin.point,
                destination = destination.point,
                departureEpochSec = Instant.now().epochSecond
            )
            runOnUiThread {
                if (token != searchSerial) return@runOnUiThread
                setPlanBusy(false)
                selectedFrom = origin
                selectedTo = destination
                setFieldText(fromField, origin.displayLabel())
                setFieldText(toField, destination.displayLabel())
                compactSearchButton.text = destination.title
                collapseSearch()
                renderPlanResult(result)
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

    private fun engineForCurrentPreferences(): HumanRouterEngine {
        val routePreferences = AppPreferences.routePreferences(this)
        val signature = "${routePreferences.preferLessWalking}:${routePreferences.preferFewerTransfers}"
        if (routingEngine == null || signature != routingPreferencesSignature) {
            routingPreferencesSignature = signature
            routingEngine = HumanRouterEngine(this, routePreferences)
        }
        return routingEngine!!
    }

    private fun renderPlanResult(result: HumanRouterEngine.PlanResult) {
        when (result) {
            is HumanRouterEngine.PlanResult.Success -> {
                surfaceScheduleDate = result.serviceDate
                railTimetableEffectiveFrom = result.railTimetableEffectiveFrom
                allRoutes = result.routes
                routeFilter = RouteFilter.FASTEST
                selectedRouteId = result.routes.first().route.id
                LastPlanStore.select(result.routes.first().route, selectedTo?.point ?: result.routes.first().route.legs.last().to.point)
                routeFiltersScroll.visibility = View.VISIBLE
                renderRouteFilters()
                renderFilteredRoutes()
                selectTab(Tab.ROUTES)
                renderRouteOnMap(result.routes.first().route, fit = true)
            }
            is HumanRouterEngine.PlanResult.RuntimeMissing ->
                showPlanError("Нужны транспортные данные", result.reason)
            is HumanRouterEngine.PlanResult.ScheduleUnavailable -> {
                val available = result.serviceDate?.toString() ?: "неизвестна"
                showPlanError(
                    "Расписание требует обновления",
                    "Доступная дата: $available. Запрошенная дата: ${result.requestedDate}. Метро и пешие варианты показываются только при наличии подходящего маршрута.",
                    actionLabel = "Проверить данные"
                ) { enqueueRuntimeDownload(replace = true) }
            }
            is HumanRouterEngine.PlanResult.Failure ->
                showPlanError("Маршрут не найден", friendlyRoutingError(result.reason))
        }
    }

    private fun renderRouteFilters() {
        routeFiltersPanel.removeAllViews()
        val filters = buildList {
            add(RouteFilter.FASTEST)
            if (allRoutes.size > 1) add(RouteFilter.LESS_WALKING)
            if (allRoutes.any { it.route.transferCount == 0 }) add(RouteFilter.NO_TRANSFERS)
            if (allRoutes.any { ranked -> ranked.route.legs.any { it.mode in METRO_FILTER_MODES } }) {
                add(RouteFilter.METRO)
            }
            if (allRoutes.any { ranked -> ranked.route.legs.any { it.mode == TransportMode.BUS || it.mode == TransportMode.TRAM } }) {
                add(RouteFilter.SURFACE)
            }
        }
        for (filter in filters) {
            routeFiltersPanel.addView(filterChip(filter).apply {
                setOnClickListener {
                    routeFilter = filter
                    renderRouteFilters()
                    renderFilteredRoutes()
                }
            })
        }
    }

    private fun filterChip(filter: RouteFilter): TextView = TextView(this).apply {
        text = filter.label
        gravity = Gravity.CENTER
        textSize = 13f
        minHeight = dp(36)
        setPadding(dp(13), 0, dp(13), 0)
        setTextColor(
            ContextCompat.getColor(
                this@MainActivity,
                if (filter == routeFilter) R.color.vh_primary else R.color.vh_text_secondary
            )
        )
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
        backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this@MainActivity,
                if (filter == routeFilter) R.color.vh_primary_soft else R.color.vh_surface_muted
            )
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(36)
        ).apply { rightMargin = dp(7) }
    }

    private fun filteredRoutes(): List<RankedRoute> {
        return RouteFilters.apply(allRoutes, routeFilter)
    }

    private fun renderFilteredRoutes() {
        routeResultsPanel.removeAllViews()
        val routes = filteredRoutes()
        if (routes.none { it.route.id == selectedRouteId }) {
            selectedRouteId = routes.firstOrNull()?.route?.id
            routes.firstOrNull()?.route?.let { route ->
                LastPlanStore.select(route, selectedTo?.point ?: route.legs.last().to.point)
                renderRouteOnMap(route, fit = true)
            }
        }

        routeResultsPanel.addView(TextView(this).apply {
            val origin = selectedFrom?.title ?: routes.firstOrNull()?.route?.legs?.firstOrNull()?.from?.name.orEmpty()
            val destination = selectedTo?.title ?: routes.firstOrNull()?.route?.legs?.lastOrNull()?.to?.name.orEmpty()
            text = listOf(origin, destination).filter(String::isNotBlank).joinToString("  →  ")
            maxLines = 2
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_secondary))
            setPadding(0, dp(2), 0, dp(2))
        })

        routeResultsPanel.addView(TextView(this).apply {
            text = "Варианты маршрута"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_primary))
            setPadding(0, dp(2), 0, dp(4))
        })
        for (ranked in routes) routeResultsPanel.addView(routeCard(ranked))
        selectedRoute()?.let { route ->
            routeResultsPanel.addView(Button(this).apply {
                val favoriteId = currentFavoriteId()
                val saved = favoriteId != null && FavoriteRoutesStore.load(preferences).any { it.id == favoriteId }
                text = if (saved) "✓ Маршрут сохранён" else "☆ Сохранить маршрут"
                isAllCaps = false
                isEnabled = !saved
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_primary))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
                setOnClickListener {
                    saveCurrentFavorite()
                    renderFilteredRoutes()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48)
                ).apply { topMargin = dp(10) }
            })
            routeResultsPanel.addView(Button(this).apply {
                text = "Поехали"
                isAllCaps = false
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_primary)
                setOnClickListener { beginTrip(route) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(50)
                ).apply { topMargin = dp(10) }
            })
        }
        routeResultsScroll.post { routeResultsScroll.scrollTo(0, 0) }
    }

    private fun routeCard(ranked: RankedRoute): View {
        val route = ranked.route
        val selected = route.id == selectedRouteId
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_route_card)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (selected) R.color.vh_primary_soft else R.color.vh_surface_muted
                )
            )
            elevation = if (selected) dp(3).toFloat() else 0f
            setPadding(dp(13), dp(11), dp(13), dp(11))
            contentDescription = routeAccessibilityLabel(route)
            setOnClickListener {
                selectedRouteId = route.id
                LastPlanStore.select(route, selectedTo?.point ?: route.legs.last().to.point)
                renderFilteredRoutes()
                renderRouteOnMap(route, fit = true)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(7) }

            val top = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val durationMin = maxOf(1, ceil(route.totalSeconds / 60.0).toInt())
            top.addView(TextView(this@MainActivity).apply {
                text = "$durationMin мин"
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_primary))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(TextView(this@MainActivity).apply {
                text = "${if (routeTimingIsApproximate(route)) "≈" else "к"} ${formatTime(route.arrivalEpochSec)}"
                gravity = Gravity.END
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_secondary))
            })
            addView(top)

            val badges = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            badges.addView(TextView(this@MainActivity).apply {
                text = objectiveLabel(ranked.objective)
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_primary))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.vh_surface_solid))
                setPadding(dp(8), dp(3), dp(8), dp(3))
            })
            badges.addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 17f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_tertiary))
                setPadding(dp(4), 0, dp(4), 0)
            })
            route.legs.forEachIndexed { index, leg ->
                badges.addView(modeBadge(leg))
                if (index < route.legs.lastIndex) {
                    badges.addView(TextView(this@MainActivity).apply {
                        text = "›"
                        textSize = 17f
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_tertiary))
                        setPadding(dp(4), 0, dp(4), 0)
                    })
                }
            }
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(badges)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            addView(TextView(this@MainActivity).apply {
                val transferText = when (route.transferCount) {
                    0 -> "без пересадок"
                    1 -> "1 пересадка"
                    else -> "${route.transferCount} пересадки"
                }
                val walkingMinutes = maxOf(1, ceil(route.legs.filter { it.mode == TransportMode.WALK }.sumOf { it.durationSeconds } / 60.0).toInt())
                text = "Пешком ${formatDistance(route.walkMeters)} ($walkingMinutes мин) · $transferText"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_tertiary))
                setPadding(0, dp(7), 0, 0)
            })
            routeTimingNotice(route)?.let { notice ->
                addView(TextView(this@MainActivity).apply {
                    text = notice
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_tertiary))
                    setPadding(0, dp(6), 0, 0)
                })
            }
        }
    }

    private fun modeBadge(leg: RouteLeg): TextView = TextView(this).apply {
        text = when (leg.mode) {
            TransportMode.WALK -> "Пешком"
            else -> listOf(modeLabel(leg.mode), leg.lineName ?: leg.lineId.orEmpty())
                .filter(String::isNotBlank)
                .joinToString(" ")
        }
        maxLines = 1
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(modeColor(leg.mode))
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.vh_surface_solid))
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }

    private fun showPlanError(
        title: String,
        message: String,
        actionLabel: String = "Изменить точки",
        action: () -> Unit = { expandSearch(focusDestination = false) }
    ) {
        allRoutes = emptyList()
        selectedRouteId = null
        clearRouteOnMap()
        routeFiltersScroll.visibility = View.GONE
        routeResultsPanel.removeAllViews()
        routeResultsPanel.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_primary))
        })
        routeResultsPanel.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_secondary))
            setPadding(0, dp(8), 0, dp(6))
        })
        routeResultsPanel.addView(Button(this).apply {
            text = actionLabel
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_primary))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { topMargin = dp(8) }
        })
        routeResultsContainer.visibility = View.VISIBLE
        nearbyPanel.visibility = View.GONE
        tabEmptyPanel.visibility = View.GONE
        currentTab = Tab.ROUTES
        renderNavigationState()
    }

    private fun selectedRoute(): RouteCandidate? = allRoutes
        .firstOrNull { it.route.id == selectedRouteId }
        ?.route

    private fun renderRouteOnMap(route: RouteCandidate, fit: Boolean) {
        val points = route.legs
            .flatMap { listOf(it.from.point, it.to.point) }
            .distinct()
        if (points.size < 2) return
        val line = LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })
        routeSource?.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(line)))
        routeStopsSource?.setGeoJson(
            FeatureCollection.fromFeatures(
                points.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }.toTypedArray()
            )
        )
        applyLayerPreferences()
        if (fit) {
            val builder = LatLngBounds.Builder()
            points.forEach { builder.include(LatLng(it.lat, it.lon)) }
            runCatching {
                val mapHeight = mapView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
                val topPadding = dp(138).coerceAtMost(mapHeight / 3)
                val desiredBottomPadding = if (routeResultsContainer.visibility == View.VISIBLE) dp(420) else dp(112)
                val bottomPadding = desiredBottomPadding.coerceAtMost(
                    (mapHeight - topPadding - dp(96)).coerceAtLeast(dp(72))
                )
                map?.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        builder.build(),
                        dp(48),
                        topPadding,
                        dp(48),
                        bottomPadding
                    ),
                    500
                )
            }
        }
    }

    private fun clearRouteOnMap() {
        routeSource?.setGeoJson(emptyFeatures())
        routeStopsSource?.setGeoJson(emptyFeatures())
    }

    private fun beginTrip(route: RouteCandidate) {
        pendingTripRoute = route
        if (!hasLocationPermission()) {
            requestLocation(LocationPurpose.TRIP)
            return
        }
        requestNotificationPermissionForTrip()
    }

    private fun requestNotificationPermissionForTrip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
        } else {
            pendingTripRoute?.let(::startTrip)
        }
    }

    private fun startTrip(route: RouteCandidate) {
        pendingTripRoute = null
        val destination = selectedTo?.point ?: route.legs.last().to.point
        LastPlanStore.select(route, destination)
        ActiveTripStore.save(
            this,
            ActiveTripSnapshot(
                route = route,
                originTitle = selectedFrom?.title ?: route.legs.first().from.name,
                originSubtitle = selectedFrom?.subtitle.orEmpty(),
                destinationTitle = selectedTo?.title ?: route.legs.last().to.name,
                destinationSubtitle = selectedTo?.subtitle.orEmpty()
            )
        )
        val firstTransit = route.legs.firstOrNull { it.mode != TransportMode.WALK }
        val summary = route.legs.joinToString(" → ") { compactLegLabel(it) }
        val intent = Intent(this, TripNavigationService::class.java).apply {
            action = TripNavigationService.ACTION_START
            putExtra(TripNavigationService.EXTRA_DEST_LAT, destination.lat)
            putExtra(TripNavigationService.EXTRA_DEST_LON, destination.lon)
            putExtra(TripNavigationService.EXTRA_BASELINE_ARRIVAL, route.arrivalEpochSec)
            putExtra(TripNavigationService.EXTRA_ROUTE_ID, route.id)
            putExtra(TripNavigationService.EXTRA_ROUTE_SUMMARY, summary)
            putExtra(TripNavigationService.EXTRA_NEXT_STOP, firstTransit?.to?.name.orEmpty())
            putExtra(TripNavigationService.EXTRA_STOPS_REMAINING, firstTransit?.stopCount ?: 0)
        }
        ContextCompat.startForegroundService(this, intent)
        renderActiveTrip(route)
    }

    private fun restoreActiveTrip(snapshot: ActiveTripSnapshot) {
        val route = snapshot.route
        selectedFrom = SearchPlace(
            snapshot.originTitle,
            snapshot.originSubtitle,
            route.legs.first().from.point
        )
        selectedTo = SearchPlace(
            snapshot.destinationTitle,
            snapshot.destinationSubtitle,
            route.legs.last().to.point
        )
        setFieldText(fromField, selectedFrom!!.displayLabel())
        setFieldText(toField, selectedTo!!.displayLabel())
        compactSearchButton.text = snapshot.destinationTitle
        allRoutes = listOf(RouteRanker.score(route, RouteObjective.FASTEST))
        selectedRouteId = route.id
        LastPlanStore.select(route, route.legs.last().to.point)
        renderActiveTrip(route)
        renderRouteOnMap(route, fit = true)
    }

    private fun renderActiveTrip(route: RouteCandidate) {
        activeTripRoute = route
        routeFiltersScroll.visibility = View.GONE
        routeResultsPanel.removeAllViews()
        routeResultsPanel.addView(TextView(this).apply {
            text = "В пути"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_primary))
        })
        routeResultsPanel.addView(TextView(this).apply {
            text = "● Поездка активна · ${formatTime(route.arrivalEpochSec)} прибытие"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_success))
            setPadding(0, dp(4), 0, 0)
        })

        val nowEpochSec = Instant.now().epochSecond
        val currentLegIndex = route.legs.indexOfFirst { nowEpochSec < it.arrivalEpochSec }
            .takeIf { it >= 0 }
            ?: route.legs.lastIndex
        val currentLeg = route.legs[currentLegIndex]
        val nextLeg = route.legs.getOrNull(currentLegIndex + 1)
        val plannedStopsRemaining = if (currentLeg.stopCount > 0) {
            val elapsed = (nowEpochSec - currentLeg.departureEpochSec).coerceAtLeast(0L)
            val fraction = (elapsed.toDouble() / currentLeg.durationSeconds.coerceAtLeast(1))
                .coerceIn(0.0, 1.0)
            (currentLeg.stopCount - floor(currentLeg.stopCount * fraction).toInt()).coerceAtLeast(0)
        } else {
            0
        }
        routeResultsPanel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_route_card)
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.vh_primary_soft))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }

            addView(TextView(this@MainActivity).apply {
                text = compactLegLabel(currentLeg)
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(modeColor(currentLeg.mode))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Направление: ${currentLeg.to.name}"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_secondary))
                setPadding(0, dp(5), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Сейчас: ${currentLeg.from.name}\nДалее: ${currentLeg.to.name}"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_secondary))
                setPadding(0, dp(7), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = if (plannedStopsRemaining > 0) {
                    "По плану: выходите через $plannedStopsRemaining ${stopWord(plannedStopsRemaining)} · ${currentLeg.to.name}"
                } else if (nextLeg != null) {
                    "Затем: ${compactLegLabel(nextLeg)} · ${nextLeg.to.name}"
                } else {
                    "Финиш: ${currentLeg.to.name}"
                }
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_primary))
                setPadding(0, dp(8), 0, 0)
            })
            addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = route.legs.size.coerceAtLeast(1)
                progress = currentLegIndex + 1
                progressTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.vh_primary))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5)).apply { topMargin = dp(10) })
            addView(TextView(this@MainActivity).apply {
                val minutes = ceil((currentLeg.arrivalEpochSec - nowEpochSec).coerceAtLeast(0L) / 60.0).toInt()
                val untilNext = if (minutes == 0) "сейчас" else "$minutes мин"
                text = "Этап ${currentLegIndex + 1} из ${route.legs.size} · до следующего этапа $untilNext"
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_tertiary))
                setPadding(0, dp(5), 0, 0)
            })
        })

        routeResultsPanel.addView(TextView(this).apply {
            text = "Маршрут поездки"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_tertiary))
            setPadding(0, dp(12), 0, dp(3))
        })
        route.legs.forEach { leg ->
            routeResultsPanel.addView(TextView(this).apply {
                text = "${formatTime(leg.departureEpochSec)}  ${compactLegLabel(leg)}\n${leg.from.name} → ${leg.to.name}"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_secondary))
                setPadding(dp(8), dp(7), dp(8), dp(7))
            })
        }
        routeResultsPanel.addView(Button(this).apply {
            text = "Завершить поездку"
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_primary))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
            setOnClickListener {
                activeTripRoute = null
                ActiveTripStore.clear(this@MainActivity)
                startService(Intent(this@MainActivity, TripNavigationService::class.java).setAction(TripNavigationService.ACTION_STOP))
                renderRouteFilters()
                renderFilteredRoutes()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { topMargin = dp(10) }
        })
        routeResultsContainer.visibility = View.VISIBLE
        nearbyPanel.visibility = View.GONE
        currentTab = Tab.ROUTES
        renderNavigationState()
        routeResultsScroll.post { routeResultsScroll.scrollTo(0, 0) }
    }

    private fun configureNavigation() {
        mapNavButton.setOnClickListener { selectTab(Tab.MAP) }
        routesNavButton.setOnClickListener { selectTab(Tab.ROUTES) }
        transportNavButton.setOnClickListener { selectTab(Tab.TRANSPORT) }
        favoritesNavButton.setOnClickListener { selectTab(Tab.FAVORITES) }
    }

    private fun selectTab(tab: Tab, persist: Boolean = true) {
        currentTab = tab
        if (persist) preferences.edit().putString(AppPreferences.KEY_SELECTED_TAB, tab.name).apply()
        tabEmptyPanel.visibility = View.GONE
        when (tab) {
            Tab.MAP -> {
                routeResultsContainer.visibility = View.GONE
                nearbyPanel.visibility = View.VISIBLE
                collapseSearch()
                if (!debugQaActive) activeMapCenter()?.let(::refreshNearby)
            }
            Tab.ROUTES -> {
                nearbyPanel.visibility = View.GONE
                val activeTrip = activeTripRoute
                if (activeTrip != null) {
                    renderActiveTrip(activeTrip)
                    routeResultsContainer.visibility = View.VISIBLE
                    collapseSearch()
                } else if (allRoutes.isNotEmpty() || routeResultsPanel.childCount > 0) {
                    routeResultsContainer.visibility = View.VISIBLE
                    collapseSearch()
                } else {
                    routeResultsContainer.visibility = View.GONE
                    expandSearch(focusDestination = true)
                }
            }
            Tab.TRANSPORT -> {
                routeResultsContainer.visibility = View.GONE
                nearbyPanel.visibility = View.VISIBLE
                collapseSearch()
                if (!debugQaActive) activeMapCenter()?.let(::refreshNearby)
            }
            Tab.FAVORITES -> {
                nearbyPanel.visibility = View.GONE
                collapseSearch()
                renderFavorites()
            }
        }
        renderNavigationState()
    }

    private fun renderNavigationState() {
        val items = listOf(
            mapNavButton to Tab.MAP,
            routesNavButton to Tab.ROUTES,
            transportNavButton to Tab.TRANSPORT,
            favoritesNavButton to Tab.FAVORITES
        )
        for ((view, tab) in items) {
            val active = currentTab == tab
            val color = ContextCompat.getColor(this, if (active) R.color.vh_primary else R.color.vh_text_tertiary)
            view.setTextColor(color)
            view.compoundDrawableTintList = ColorStateList.valueOf(color)
            view.setTypeface(view.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
            view.isSelected = active
        }
    }

    private fun saveCurrentFavorite() {
        val origin = selectedFrom
        val destination = selectedTo
        if (origin == null || destination == null) {
            Toast.makeText(this, "Сначала постройте маршрут", Toast.LENGTH_SHORT).show()
            return
        }
        FavoriteRoutesStore.save(
            preferences,
            FavoriteRoute(
                id = FavoriteRoutesStore.stableId(origin.point, destination.point),
                originTitle = origin.title,
                originSubtitle = origin.subtitle,
                origin = origin.point,
                destinationTitle = destination.title,
                destinationSubtitle = destination.subtitle,
                destination = destination.point
            )
        )
        Toast.makeText(this, "Маршрут добавлен в избранное", Toast.LENGTH_SHORT).show()
    }

    private fun currentFavoriteId(): String? {
        val origin = selectedFrom?.point ?: return null
        val destination = selectedTo?.point ?: return null
        return FavoriteRoutesStore.stableId(origin, destination)
    }

    private fun renderFavorites() {
        val favorites = FavoriteRoutesStore.load(preferences)
        routeResultsPanel.removeAllViews()
        routeFiltersScroll.visibility = View.GONE
        if (favorites.isEmpty()) {
            routeResultsContainer.visibility = View.GONE
            tabEmptyTitle.text = getString(R.string.nav_favorites)
            tabEmptyMessage.text = getString(R.string.favorites_empty)
            tabEmptyPanel.visibility = View.VISIBLE
            return
        }

        tabEmptyPanel.visibility = View.GONE
        routeResultsContainer.visibility = View.VISIBLE
        routeResultsPanel.addView(TextView(this).apply {
            text = getString(R.string.nav_favorites)
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_primary))
            setPadding(0, dp(3), 0, dp(3))
        })
        routeResultsPanel.addView(TextView(this).apply {
            text = "Нажмите, чтобы построить маршрут заново"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_tertiary))
            setPadding(0, 0, 0, dp(5))
        })

        favorites.forEach { favorite ->
            routeResultsPanel.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                contentDescription = "${favorite.originTitle}, ${favorite.destinationTitle}"
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_route_card)
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.vh_surface_muted))
                setPadding(dp(13), dp(8), dp(4), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(7) }
                setOnClickListener {
                    selectedFrom = SearchPlace(favorite.originTitle, favorite.originSubtitle, favorite.origin)
                    selectedTo = SearchPlace(favorite.destinationTitle, favorite.destinationSubtitle, favorite.destination)
                    setFieldText(fromField, selectedFrom!!.displayLabel())
                    setFieldText(toField, selectedTo!!.displayLabel())
                    compactSearchButton.text = favorite.destinationTitle
                    planRouteNow()
                }

                addView(TextView(this@MainActivity).apply {
                    text = "${favorite.originTitle}\n→ ${favorite.destinationTitle}"
                    maxLines = 3
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_primary))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                addView(TextView(this@MainActivity).apply {
                    text = "×"
                    gravity = Gravity.CENTER
                    textSize = 24f
                    contentDescription = "Удалить маршрут ${favorite.destinationTitle}"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_tertiary))
                    setOnClickListener {
                        FavoriteRoutesStore.remove(preferences, favorite.id)
                        renderFavorites()
                    }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            })
        }
    }

    private fun refreshNearby(center: GeoPoint) {
        if (!runtimeReady) {
            nearbyList.removeAllViews()
            nearbyStateText.visibility = View.VISIBLE
            nearbyStateText.text = "Транспортные данные ещё загружаются."
            return
        }
        val token = ++nearbySerial
        nearbyStateText.visibility = View.VISIBLE
        nearbyStateText.text = getString(R.string.nearby_loading)
        nearbyList.removeAllViews()
        executor.execute {
            val result = runCatching {
                nearbyRepository.findNearby(center, Instant.now().epochSecond)
            }
            runOnUiThread {
                if (token != nearbySerial) return@runOnUiThread
                result.fold(
                    onSuccess =(::renderNearby),
                    onFailure = {
                        nearbyStateText.visibility = View.VISIBLE
                        nearbyStateText.text = "Не удалось прочитать данные рядом. Повторите позже."
                    }
                )
            }
        }
    }

    private fun renderNearby(places: List<NearbyTransitPlace>) {
        lastNearby = places
        nearbyList.removeAllViews()
        if (places.isEmpty()) {
            nearbyStateText.visibility = View.VISIBLE
            nearbyStateText.text = "Поблизости ничего не найдено. Переместите карту."
            renderNearbyMarkers(emptyList())
            return
        }
        nearbyStateText.visibility = View.GONE
        for (place in places) nearbyList.addView(nearbyRow(place))
        renderNearbyMarkers(places)
    }

    private fun nearbyRow(place: NearbyTransitPlace): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(58)
        isClickable = true
        isFocusable = true
        contentDescription = nearbyAccessibilityLabel(place)
        setOnClickListener {
            if (activeTripRoute != null) {
                Toast.makeText(
                    this@MainActivity,
                    "Сначала завершите активную поездку",
                    Toast.LENGTH_SHORT
                ).show()
                selectTab(Tab.ROUTES)
                return@setOnClickListener
            }
            selectedTo = SearchPlace(
                title = place.name,
                subtitle = place.routeLabels.joinToString(" · "),
                point = place.point
            )
            setFieldText(toField, selectedTo!!.displayLabel())
            compactSearchButton.text = place.name
            map?.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(place.point.lat, place.point.lon)).zoom(15.5).build()
                ),
                420
            )
            allRoutes = emptyList()
            selectedRouteId = null
            routeResultsPanel.removeAllViews()
            routeFiltersScroll.visibility = View.GONE
            clearRouteOnMap()
            if (selectedFrom == null) {
                currentLocation?.let { point ->
                    selectedFrom = SearchPlace(CURRENT_LOCATION_LABEL, "GPS", point)
                    setFieldText(fromField, CURRENT_LOCATION_LABEL)
                }
            }
            if (selectedFrom != null) {
                planRouteNow()
            } else {
                selectTab(Tab.ROUTES)
                fromField.requestFocus()
                renderOriginChoices()
            }
        }

        addView(TextView(this@MainActivity).apply {
            text = place.modes.sortedBy { it.ordinal }.joinToString("/") { modeShortLabel(it) }
            gravity = Gravity.CENTER
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(modeColor(place.modes.firstOrNull() ?: TransportMode.BUS))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.vh_primary_soft))
            setPadding(dp(7), dp(5), dp(7), dp(5))
        }, LinearLayout.LayoutParams(dp(52), dp(40)))

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            addView(TextView(this@MainActivity).apply {
                text = place.name
                maxLines = 1
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_primary))
            })
            addView(TextView(this@MainActivity).apply {
                text = place.routeLabels.take(6).joinToString(" · ").ifBlank { place.modes.joinToString(" · ", transform = ::modeLabel) }
                maxLines = 1
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_text_tertiary))
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        addView(TextView(this@MainActivity).apply {
            text = place.nextDepartureEpochSec?.let { departure ->
                val minutes = ceil((departure - Instant.now().epochSecond).coerceAtLeast(0L) / 60.0).toInt()
                if (minutes <= 0) "по расп. сейчас" else "по расп. $minutes мин"
            } ?: formatDistance(place.distanceMeters)
            gravity = Gravity.END
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.vh_primary))
        })
    }

    private fun renderNearbyMarkers(places: List<NearbyTransitPlace>) {
        val visible = preferences.getBoolean(AppPreferences.KEY_SHOW_STOPS, true)
        val sourceData = if (visible) places else emptyList()
        nearbySource?.setGeoJson(
            FeatureCollection.fromFeatures(
                sourceData.map { Feature.fromGeometry(Point.fromLngLat(it.point.lon, it.point.lat)) }.toTypedArray()
            )
        )
    }

    private fun configureSettings() {
        settingsButton.setOnClickListener { openSettings() }
        closeSettingsButton.setOnClickListener { closeSettings() }
        settingsScrim.setOnClickListener { closeSettings() }
        settingsPanel.setOnClickListener { /* consume clicks inside the panel */ }

        showStopsSwitch.isChecked = preferences.getBoolean(AppPreferences.KEY_SHOW_STOPS, true)
        showTransportSwitch.isChecked = preferences.getBoolean(AppPreferences.KEY_SHOW_TRANSPORT, true)
        darkThemeSwitch.isChecked = AppPreferences.isDarkTheme(this)
        lessWalkingSwitch.isChecked = preferences.getBoolean(AppPreferences.KEY_LESS_WALKING, false)
        avoidTransfersSwitch.isChecked = preferences.getBoolean(AppPreferences.KEY_AVOID_TRANSFERS, false)

        showStopsSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(AppPreferences.KEY_SHOW_STOPS, checked).apply()
            renderNearbyMarkers(lastNearby)
        }
        showTransportSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(AppPreferences.KEY_SHOW_TRANSPORT, checked).apply()
            applyLayerPreferences()
        }
        darkThemeSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(AppPreferences.KEY_DARK_THEME, checked).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
        lessWalkingSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(AppPreferences.KEY_LESS_WALKING, checked).apply()
            invalidateRoutingEngine()
        }
        avoidTransfersSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(AppPreferences.KEY_AVOID_TRANSFERS, checked).apply()
            invalidateRoutingEngine()
        }
        checkDataButton.setOnClickListener {
            enqueueRuntimeDownload(replace = true)
            closeSettings()
        }
        appVersionText.text = "${getString(R.string.app_version)}: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        refreshRuntimeVersion()
    }

    private fun invalidateRoutingEngine() {
        routingEngine = null
        routingPreferencesSignature = ""
        if (selectedFrom != null && selectedTo != null && runtimeReady) {
            mainHandler.postDelayed({ planRouteNow() }, 180L)
        }
    }

    private fun openSettings() {
        refreshRuntimeVersion()
        settingsScrim.visibility = View.VISIBLE
        settingsPanel.alpha = 0.9f
        settingsPanel.translationX = dp(80).toFloat()
        settingsPanel.animate().alpha(1f).translationX(0f).setDuration(190).start()
    }

    private fun closeSettings() {
        if (settingsScrim.visibility != View.VISIBLE) return
        settingsPanel.animate().translationX(dp(80).toFloat()).alpha(0.9f).setDuration(150).withEndAction {
            settingsScrim.visibility = View.GONE
            settingsPanel.translationX = 0f
            settingsPanel.alpha = 1f
        }.start()
    }

    private fun applyLayerPreferences() {
        val showStops = preferences.getBoolean(AppPreferences.KEY_SHOW_STOPS, true)
        val showTransport = preferences.getBoolean(AppPreferences.KEY_SHOW_TRANSPORT, true)
        nearbyLayer?.setProperties(PropertyFactory.visibility(if (showStops) Property.VISIBLE else Property.NONE))
        locationLayer?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
        routeLayer?.setProperties(PropertyFactory.visibility(if (showTransport) Property.VISIBLE else Property.NONE))
        routeStopsLayer?.setProperties(PropertyFactory.visibility(if (showTransport) Property.VISIBLE else Property.NONE))
    }

    private fun requestLocation(purpose: LocationPurpose) {
        locationPurpose = purpose
        val existing = locationMachine.state as? LocationState.Available
        if (existing != null && System.currentTimeMillis() - existing.capturedAtMillis <= LOCATION_CACHE_MAX_AGE_MS) {
            applyLocation(existing.point, existing.isLastKnown)
            return
        }
        if (!hasLocationPermission()) {
            locationPermissionPreviouslyRequested = preferences.getBoolean(PREF_LOCATION_REQUESTED, false)
            preferences.edit().putBoolean(PREF_LOCATION_REQUESTED, true).apply()
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }
        fetchCurrentLocation()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocation() {
        if (!hasLocationPermission()) return
        locationMachine.requestStarted()
        renderLocationState()
        val providers = runCatching { locationManager.getProviders(true) }.getOrDefault(emptyList())
        if (providers.isEmpty()) {
            locationMachine.providerDisabled()
            renderLocationState()
            return
        }

        providers.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull(Location::getTime)?.let { last ->
            if (System.currentTimeMillis() - last.time <= LAST_LOCATION_MAX_AGE_MS) {
                acceptLocation(last, isLastKnown = true)
            }
        }

        val provider = when {
            providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            if (currentLocation == null) {
                locationMachine.providerDisabled()
                renderLocationState()
            }
            return
        }

        stopLocationUpdates()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                acceptLocation(location, isLastKnown = false)
                stopLocationUpdates()
            }

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                if (currentLocation == null) {
                    locationMachine.providerDisabled()
                    renderLocationState()
                }
            }

            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        currentLocationListener = listener
        val requested = runCatching {
            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }.isSuccess
        if (!requested && currentLocation == null) {
            locationMachine.error("Не удалось запросить геопозицию")
            renderLocationState()
            return
        }
        mainHandler.removeCallbacks(locationTimeoutRunnable)
        mainHandler.postDelayed(locationTimeoutRunnable, LOCATION_TIMEOUT_MS)
    }

    private fun acceptLocation(location: Location, isLastKnown: Boolean) {
        val point = GeoPoint(location.latitude, location.longitude)
        locationMachine.locationAvailable(point, location.time, isLastKnown)
        applyLocation(point, isLastKnown)
    }

    private fun applyLocation(point: GeoPoint, isLastKnown: Boolean) {
        currentLocation = point
        renderCurrentLocationMarker()
        locationActionPanel.visibility = View.GONE
        when (locationPurpose) {
            LocationPurpose.CENTER -> centerMap(point)
            LocationPurpose.ORIGIN -> {
                selectedFrom = SearchPlace(CURRENT_LOCATION_LABEL, if (isLastKnown) "Последняя геопозиция" else "GPS", point)
                setFieldText(fromField, CURRENT_LOCATION_LABEL)
                hideSuggestions()
            }
            LocationPurpose.NEARBY -> refreshNearby(point)
            LocationPurpose.TRIP -> {
                selectedFrom = selectedFrom ?: SearchPlace(CURRENT_LOCATION_LABEL, "GPS", point)
                pendingTripRoute?.let { requestNotificationPermissionForTrip() }
            }
            LocationPurpose.NONE -> Unit
        }
        locationPurpose = LocationPurpose.NONE
        if (pendingRouteAfterLocation) {
            pendingRouteAfterLocation = false
            mainHandler.post { planRouteNow() }
        }
        if (!debugQaActive && (currentTab == Tab.MAP || currentTab == Tab.TRANSPORT)) refreshNearby(point)
    }

    private fun renderLocationState() {
        when (val state = locationMachine.state) {
            LocationState.Unknown, is LocationState.Available -> locationActionPanel.visibility = View.GONE
            LocationState.Requesting -> {
                locationActionPanel.visibility = View.VISIBLE
                locationActionMessage.text = "Определяем ваше местоположение…"
                locationPrimaryAction.visibility = View.GONE
                locationSecondaryAction.visibility = View.VISIBLE
                locationSecondaryAction.text = getString(R.string.permission_choose_map)
                locationSecondaryAction.setOnClickListener { chooseOriginFromMap() }
            }
            is LocationState.PermissionDenied -> {
                locationActionPanel.visibility = View.VISIBLE
                locationActionMessage.text = if (state.permanently) {
                    "Доступ к геопозиции отключён. Откройте настройки приложения или выберите точку на карте."
                } else {
                    "Геопозиция не разрешена. Можно повторить запрос или выбрать точку на карте."
                }
                locationPrimaryAction.visibility = View.VISIBLE
                locationPrimaryAction.text = if (state.permanently) getString(R.string.permission_settings) else getString(R.string.permission_allow)
                locationPrimaryAction.setOnClickListener {
                    if (state.permanently) openApplicationSettings() else requestLocation(locationPurpose.coerceUseful())
                }
                locationSecondaryAction.visibility = View.VISIBLE
                locationSecondaryAction.text = getString(R.string.permission_choose_map)
                locationSecondaryAction.setOnClickListener { chooseOriginFromMap() }
            }
            LocationState.ProviderDisabled -> {
                locationActionPanel.visibility = View.VISIBLE
                locationActionMessage.text = "Геолокация на телефоне выключена. Включите её или выберите точку на карте."
                locationPrimaryAction.visibility = View.VISIBLE
                locationPrimaryAction.text = "Включить геолокацию"
                locationPrimaryAction.setOnClickListener {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                locationSecondaryAction.visibility = View.VISIBLE
                locationSecondaryAction.text = getString(R.string.permission_choose_map)
                locationSecondaryAction.setOnClickListener { chooseOriginFromMap() }
            }
            LocationState.Timeout -> renderRetryableLocationError("Не удалось получить свежую геопозицию вовремя.")
            is LocationState.Error -> renderRetryableLocationError(state.userMessage)
        }
    }

    private fun renderRetryableLocationError(message: String) {
        locationActionPanel.visibility = View.VISIBLE
        locationActionMessage.text = message
        locationPrimaryAction.visibility = View.VISIBLE
        locationPrimaryAction.text = "Повторить"
        locationPrimaryAction.setOnClickListener { requestLocation(locationPurpose.coerceUseful()) }
        locationSecondaryAction.visibility = View.VISIBLE
        locationSecondaryAction.text = getString(R.string.permission_choose_map)
        locationSecondaryAction.setOnClickListener { chooseOriginFromMap() }
    }

    private fun chooseOriginFromMap() {
        val target = map?.cameraPosition?.target ?: LatLng(MOSCOW_LAT, MOSCOW_LON)
        val point = GeoPoint(target.latitude, target.longitude)
        selectedFrom = SearchPlace("Точка на карте", formatCoordinates(point), point)
        setFieldText(fromField, selectedFrom!!.displayLabel())
        locationActionPanel.visibility = View.GONE
        hideSuggestions()
        expandSearch(focusDestination = true)
        if (pendingRouteAfterLocation) {
            pendingRouteAfterLocation = false
            mainHandler.post { planRouteNow() }
        }
    }

    private fun chooseDestinationFromMap() {
        val target = map?.cameraPosition?.target ?: LatLng(MOSCOW_LAT, MOSCOW_LON)
        val point = GeoPoint(target.latitude, target.longitude)
        selectedTo = SearchPlace("Точка на карте", formatCoordinates(point), point)
        setFieldText(toField, selectedTo!!.displayLabel())
        compactSearchButton.text = selectedTo!!.title
        hideSuggestions()
        hideKeyboard()
    }

    private fun centerMap(point: GeoPoint) {
        map?.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(point.lat, point.lon)).zoom(15.2).build()
            ),
            450
        )
    }

    private fun renderCurrentLocationMarker() {
        val point = currentLocation
        val features = if (point == null) {
            emptyFeatures()
        } else {
            FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)))
        }
        locationSource?.setGeoJson(features)
    }

    private fun stopLocationUpdates() {
        mainHandler.removeCallbacks(locationTimeoutRunnable)
        currentLocationListener?.let { listener ->
            if (hasLocationPermission()) runCatching { locationManager.removeUpdates(listener) }
        }
        currentLocationListener = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST -> {
                if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                    fetchCurrentLocation()
                } else {
                    val permanently = !ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) && locationPermissionPreviouslyRequested
                    locationMachine.permissionDenied(permanently)
                    pendingRouteAfterLocation = false
                    pendingTripRoute = null
                    renderLocationState()
                }
            }
            NOTIFICATION_PERMISSION_REQUEST -> {
                if (grantResults.none { it == PackageManager.PERMISSION_GRANTED }) {
                    Toast.makeText(this, "Уведомления выключены; статус поездки останется в приложении", Toast.LENGTH_LONG).show()
                }
                pendingTripRoute?.let(::startTrip)
            }
        }
    }

    private fun openApplicationSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    private fun configureRuntime() {
        runtimeReady = hasUsableRuntime()
        retryButton.setOnClickListener { enqueueRuntimeDownload(replace = true) }
        observeRuntimeDownload()
        if (runtimeReady) {
            loadingPanel.visibility = View.GONE
            refreshRuntimeVersion()
        } else {
            enqueueRuntimeDownload(replace = false)
        }
    }

    private fun hasUsableRuntime(): Boolean {
        val root = File(filesDir, "runtime")
        return !RuntimeInstaller.transactionInProgress(filesDir) &&
            File(root, "manifest.json").exists() &&
            (File(root, "surface/manifest.json").exists() || File(root, "rail/graph.json").exists())
    }

    private fun enqueueRuntimeDownload(replace: Boolean) {
        val hadRuntime = hasUsableRuntime()
        runtimeReady = hadRuntime
        runtimeBusy = true
        retryButton.visibility = View.GONE
        loadingPanel.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        progress.progress = if (hadRuntime) 100 else 0
        progressText.text = if (hadRuntime) "проверка" else "0%"
        status.text = if (hadRuntime) "Проверяем обновления…" else "Подготавливаем данные Москвы…"
        startJourneyAnimation()
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
                        runtimeBusy = true
                        loadingPanel.visibility = View.VISIBLE
                        progress.visibility = View.VISIBLE
                        status.text = if (runtimeReady) "Обновление ждёт сеть; установленные данные доступны" else "Ожидаем сеть…"
                        startJourneyAnimation()
                    }
                    WorkInfo.State.RUNNING -> {
                        runtimeBusy = true
                        loadingPanel.visibility = View.VISIBLE
                        progress.visibility = View.VISIBLE
                        retryButton.visibility = View.GONE
                        val percent = info.progress.getInt(RuntimeDownloadWorker.KEY_PERCENT, 0)
                        progress.progress = percent
                        progressText.text = "$percent%"
                        status.text = info.progress.getString(RuntimeDownloadWorker.KEY_MESSAGE) ?: "Загружаем данные…"
                        startJourneyAnimation()
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        runtimeBusy = false
                        stopJourneyAnimation()
                        runtimeReady = hasUsableRuntime()
                        loadingPanel.visibility = View.GONE
                        refreshRuntimeVersion()
                        activeMapCenter()?.let(::refreshNearby)
                    }
                    WorkInfo.State.FAILED -> {
                        runtimeBusy = false
                        stopJourneyAnimation()
                        runtimeReady = hasUsableRuntime()
                        loadingPanel.visibility = View.VISIBLE
                        status.text = if (runtimeReady) {
                            "Обновление не удалось; используем установленные данные"
                        } else {
                            "Не удалось загрузить данные. Проверьте сеть и повторите."
                        }
                        progressText.text = ""
                        retryButton.visibility = View.VISIBLE
                        refreshRuntimeVersion()
                    }
                    WorkInfo.State.CANCELLED -> {
                        runtimeBusy = false
                        stopJourneyAnimation()
                        runtimeReady = hasUsableRuntime()
                        loadingPanel.visibility = if (runtimeReady) View.GONE else View.VISIBLE
                        status.text = "Загрузка остановлена"
                        retryButton.visibility = if (runtimeReady) View.GONE else View.VISIBLE
                    }
                }
            }
    }

    private fun showCompactStatus(message: String, retryVisible: Boolean) {
        if (!runtimeBusy) stopJourneyAnimation()
        loadingPanel.visibility = View.VISIBLE
        status.text = message
        progressText.text = ""
        progress.visibility = View.GONE
        retryButton.visibility = if (retryVisible) View.VISIBLE else View.GONE
    }

    private fun refreshRuntimeVersion() {
        val root = File(filesDir, "runtime")
        runtimeVersionText.text = runCatching {
            val manifest = JSONObject(File(root, "manifest.json").readText())
            val version = manifest.optString("version", "—")
            val surfaceDate = runCatching {
                JSONObject(File(root, "surface/manifest.json").readText()).optString("service_date")
            }.getOrNull().orEmpty()
            buildString {
                append(getString(R.string.runtime_version)).append(": ").append(version)
                if (surfaceDate.isNotBlank()) append("\nРасписание: ").append(surfaceDate)
                railTimetableDate()?.let { date ->
                    append("\nМЦД-3/электрички: с ").append(dateFormatter.format(date)).append(" (плановое)")
                }
            }
        }.getOrElse { "${getString(R.string.runtime_version)}: не установлена" }
    }

    private fun railTimetableDate(): LocalDate? {
        railTimetableEffectiveFrom?.let { return it }
        railTimetableEffectiveFrom = runCatching {
            val text = assets.open("rail_timetable_mtppk_2026-04-27.json")
                .bufferedReader()
                .use { it.readText() }
            JSONObject(text).optString("effective_from")
                .takeIf(String::isNotBlank)
                ?.let(LocalDate::parse)
        }.getOrNull()
        return railTimetableEffectiveFrom
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            searchPanel.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = bars.top + dp(12) }
            quickActions.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = bars.top + dp(78) }
            loadingPanel.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = bars.top + dp(130) }
            bottomNav.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = bars.bottom + dp(8) }
            nearbyPanel.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = bars.bottom + dp(82) }
            routeResultsContainer.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = bars.bottom + dp(82) }
            tabEmptyPanel.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = bars.bottom + dp(82) }
            locationActionPanel.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = bars.bottom + dp(304) }
            osmAttribution.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = bars.bottom + dp(84) }
            settingsPanel.updatePadding(top = bars.top + dp(16), bottom = bars.bottom + dp(24))

            bottomNav.visibility = if (imeVisible) View.GONE else View.VISIBLE
            if (imeVisible) {
                nearbyPanel.visibility = View.GONE
                routeResultsContainer.visibility = View.GONE
                tabEmptyPanel.visibility = View.GONE
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setPlanBusy(busy: Boolean) {
        routeButton.isEnabled = !busy
        routeButton.text = if (busy) "Строим маршрут…" else getString(R.string.search_action)
        if (busy) {
            loadingPanel.visibility = View.VISIBLE
            status.text = "Строим маршрут по транспортным данным…"
            progress.visibility = View.GONE
            progressText.text = ""
            retryButton.visibility = View.GONE
            startJourneyAnimation()
        } else if (runtimeBusy) {
            progress.visibility = View.VISIBLE
            startJourneyAnimation()
        } else {
            stopJourneyAnimation()
            loadingPanel.visibility = View.GONE
        }
    }

    private fun startJourneyAnimation() {
        if (journeyAnimationRunning) return
        journeyAnimationRunning = true
        journeyFrameIndex = 0
        journeyRow.visibility = View.VISIBLE
        mainHandler.removeCallbacks(journeyAnimationRunnable)
        mainHandler.post(journeyAnimationRunnable)
    }

    private fun stopJourneyAnimation() {
        journeyAnimationRunning = false
        mainHandler.removeCallbacks(journeyAnimationRunnable)
        journeyImage.animate().cancel()
        journeyRow.visibility = View.GONE
    }

    private fun setFieldText(field: EditText, value: String) {
        suppressSearchWatcher = true
        field.setText(value)
        field.setSelection(value.length)
        suppressSearchWatcher = false
    }

    private fun showKeyboard(field: View) {
        field.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        currentFocus?.clearFocus()
    }

    private fun activeMapCenter(): GeoPoint? {
        currentLocation?.let { return it }
        val target = map?.cameraPosition?.target ?: return null
        return GeoPoint(target.latitude, target.longitude)
    }

    private fun emptyFeatures(): FeatureCollection = FeatureCollection.fromFeatures(emptyArray<Feature>())

    private fun SearchPlace.displayLabel(): String = listOf(title, subtitle)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(", ")

    private fun formatTime(epochSec: Long): String = Instant.ofEpochSecond(epochSec)
        .atZone(zoneId)
        .format(timeFormatter)

    private fun formatDistance(meters: Int): String = if (meters < 1_000) {
        "$meters м"
    } else {
        String.format(Locale("ru"), "%.1f км", meters / 1_000.0)
    }

    private fun formatCoordinates(point: GeoPoint): String = String.format(
        Locale.US,
        "%.5f, %.5f",
        point.lat,
        point.lon
    )

    private fun objectiveLabel(objective: RouteObjective): String = when (objective) {
        RouteObjective.FASTEST -> "быстрее"
        RouteObjective.RELIABLE -> "надёжнее"
        RouteObjective.LESS_WALKING -> "меньше пешком"
        RouteObjective.FEWER_TRANSFERS -> "меньше пересадок"
    }

    private fun compactLegLabel(leg: RouteLeg): String = when (leg.mode) {
        TransportMode.WALK -> "Пешком ${formatDistance(leg.walkMeters)}"
        else -> listOf(modeLabel(leg.mode), leg.lineName ?: leg.lineId.orEmpty())
            .filter(String::isNotBlank)
            .joinToString(" ")
    }

    private fun modeLabel(mode: TransportMode): String = when (mode) {
        TransportMode.WALK -> "Пешком"
        TransportMode.BUS -> "Автобус"
        TransportMode.TRAM -> "Трамвай"
        TransportMode.METRO -> "Метро"
        TransportMode.MCC -> "МЦК"
        TransportMode.MCD -> "МЦД"
        TransportMode.TRAIN -> "Поезд"
    }

    private fun modeShortLabel(mode: TransportMode): String = when (mode) {
        TransportMode.WALK -> "П"
        TransportMode.BUS -> "А"
        TransportMode.TRAM -> "Т"
        TransportMode.METRO -> "М"
        TransportMode.MCC -> "МЦК"
        TransportMode.MCD -> "D"
        TransportMode.TRAIN -> "Э"
    }

    private fun modeColor(mode: TransportMode): Int = ContextCompat.getColor(
        this,
        when (mode) {
            TransportMode.WALK -> R.color.vh_text_secondary
            TransportMode.BUS -> R.color.vh_bus
            TransportMode.TRAM -> R.color.vh_tram
            TransportMode.METRO -> R.color.vh_metro
            TransportMode.MCC -> R.color.vh_mcc
            TransportMode.MCD -> R.color.vh_mcd
            TransportMode.TRAIN -> R.color.vh_train
        }
    )

    private fun routeAccessibilityLabel(route: RouteCandidate): String {
        val duration = maxOf(1, ceil(route.totalSeconds / 60.0).toInt())
        val arrival = if (routeTimingIsApproximate(route)) "ориентировочное прибытие" else "прибытие"
        return "$duration минут, $arrival ${formatTime(route.arrivalEpochSec)}, " +
            route.legs.joinToString(", ", transform = ::compactLegLabel)
    }

    private fun routeTimingIsApproximate(route: RouteCandidate): Boolean =
        route.legs.any { leg ->
            (leg.mode == TransportMode.BUS || leg.mode == TransportMode.TRAM) && leg.realtimeConfidence <= 0.2
        } || route.legs.any { it.mode == TransportMode.METRO || it.mode == TransportMode.MCC }

    private fun routeTimingNotice(route: RouteCandidate): String? {
        val notes = buildList {
            val staleSurface = route.legs.any { leg ->
                (leg.mode == TransportMode.BUS || leg.mode == TransportMode.TRAM) && leg.realtimeConfidence <= 0.2
            }
            if (staleSurface) {
                val date = surfaceScheduleDate?.let(dateFormatter::format) ?: "предыдущую дату"
                add("Наземный транспорт: ориентировочно по расписанию за $date.")
            }
            if (route.legs.any { it.mode == TransportMode.METRO || it.mode == TransportMode.MCC }) {
                add("Метро/МЦК: расчётное время без live-данных.")
            }
            if (route.legs.any { it.mode == TransportMode.MCD || it.mode == TransportMode.TRAIN }) {
                val date = railTimetableDate()?.let(dateFormatter::format) ?: "опубликованной версии"
                add("МЦД-3/электрички: плановое расписание с $date, без оперативных изменений.")
            }
        }
        return notes.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun nearbyAccessibilityLabel(place: NearbyTransitPlace): String = buildString {
        append(place.name).append(", ")
        append(place.modes.joinToString(", ", transform = ::modeLabel)).append(", ")
        if (place.nextDepartureEpochSec != null) {
            append("ближайшее отправление по расписанию ").append(formatTime(place.nextDepartureEpochSec))
        } else {
            append(formatDistance(place.distanceMeters))
        }
    }

    private fun friendlyRoutingError(raw: String): String {
        val safe = raw.trim()
        return when {
            safe.isBlank() -> "Попробуйте изменить точки или проверить данные."
            safe.contains("маршрут", ignoreCase = true) -> safe
            safe.contains("данн", ignoreCase = true) -> "Транспортные данные недоступны. Проверьте обновление в настройках."
            else -> "Не удалось построить маршрут. Измените точки и попробуйте ещё раз."
        }
    }

    private fun stopWord(value: Int): String {
        val n10 = value % 10
        val n100 = value % 100
        return when {
            n10 == 1 && n100 != 11 -> "остановку"
            n10 in 2..4 && n100 !in 12..14 -> "остановки"
            else -> "остановок"
        }
    }

    /** Visual-regression fixtures are reachable only from an explicit debug-build intent extra. */
    private fun applyDebugQaScenario(screen: String) {
        if (!BuildConfig.DEBUG) return
        runtimeBusy = false
        runtimeReady = true
        stopJourneyAnimation()
        loadingPanel.visibility = View.GONE
        locationActionPanel.visibility = View.GONE

        when (screen.lowercase(Locale.ROOT)) {
            "location_allowed" -> {
                selectTab(Tab.MAP, persist = false)
                locationPurpose = LocationPurpose.ORIGIN
                locationMachine.locationAvailable(
                    GeoPoint(55.751244, 37.618423),
                    System.currentTimeMillis(),
                    false
                )
                applyLocation(GeoPoint(55.751244, 37.618423), isLastKnown = false)
                expandSearch(focusDestination = true)
            }
            "permission_denied", "permission_permanently_denied", "location_disabled" -> {
                selectTab(Tab.MAP, persist = false)
                locationPurpose = LocationPurpose.ORIGIN
                when (screen.lowercase(Locale.ROOT)) {
                    "permission_denied" -> locationMachine.permissionDenied(permanently = false)
                    "permission_permanently_denied" -> locationMachine.permissionDenied(permanently = true)
                    else -> locationMachine.providerDisabled()
                }
                renderLocationState()
            }
            "nearby" -> {
                selectTab(Tab.MAP, persist = false)
                renderNearby(
                    listOf(
                        NearbyTransitPlace(
                            id = "qa:bus",
                            name = "Театральная площадь",
                            point = GeoPoint(55.7585, 37.6188),
                            distanceMeters = 180,
                            modes = setOf(TransportMode.BUS),
                            routeLabels = listOf("м2", "м3", "н11"),
                            nextDepartureEpochSec = Instant.now().epochSecond + 7 * 60
                        ),
                        NearbyTransitPlace(
                            id = "qa:metro",
                            name = "Охотный Ряд",
                            point = GeoPoint(55.7578, 37.6161),
                            distanceMeters = 340,
                            modes = setOf(TransportMode.METRO),
                            routeLabels = listOf("Сокольническая линия"),
                            nextDepartureEpochSec = null
                        ),
                        NearbyTransitPlace(
                            id = "qa:tram",
                            name = "Метро «Чистые пруды»",
                            point = GeoPoint(55.7657, 37.6388),
                            distanceMeters = 920,
                            modes = setOf(TransportMode.TRAM),
                            routeLabels = listOf("А", "3"),
                            nextDepartureEpochSec = Instant.now().epochSecond + 12 * 60
                        ),
                        NearbyTransitPlace(
                            id = "qa:d3",
                            name = "Останкино",
                            point = GeoPoint(55.8175, 37.6033),
                            distanceMeters = 1_150,
                            modes = setOf(TransportMode.MCD, TransportMode.TRAIN),
                            routeLabels = listOf("D3", "6605"),
                            nextDepartureEpochSec = Instant.now().epochSecond + 16 * 60
                        )
                    )
                )
            }
            "routes", "route_map", "trip" -> {
                val routes = debugQaRoutes()
                selectedFrom = SearchPlace("Большой театр", "Театральная площадь", routes.first().legs.first().from.point)
                selectedTo = SearchPlace("Бабушкинская", "Москва", routes.first().legs.last().to.point)
                setFieldText(fromField, selectedFrom!!.displayLabel())
                setFieldText(toField, selectedTo!!.displayLabel())
                compactSearchButton.text = selectedTo!!.title
                surfaceScheduleDate = LocalDate.now(zoneId)
                railTimetableEffectiveFrom = LocalDate.of(2026, 4, 27)
                allRoutes = routes.map { RouteRanker.score(it, RouteObjective.FASTEST) }
                selectedRouteId = routes.first().id
                LastPlanStore.select(routes.first(), selectedTo!!.point)
                if (screen.equals("trip", ignoreCase = true)) {
                    renderActiveTrip(routes.first())
                } else if (screen.equals("route_map", ignoreCase = true)) {
                    selectTab(Tab.MAP, persist = false)
                } else {
                    routeFilter = RouteFilter.FASTEST
                    renderRouteFilters()
                    renderFilteredRoutes()
                    selectTab(Tab.ROUTES, persist = false)
                }
                renderRouteOnMap(routes.first(), fit = true)
            }
            "settings" -> {
                selectTab(Tab.MAP, persist = false)
                openSettings()
            }
            else -> selectTab(Tab.MAP, persist = false)
        }
    }

    private fun debugQaRoutes(): List<RouteCandidate> {
        val now = Instant.now().epochSecond
        val origin = RoutePlace("qa:origin", "Большой театр", GeoPoint(55.7601, 37.6187))
        val stop = RoutePlace("qa:stop", "Театральная площадь", GeoPoint(55.7588, 37.6194))
        val metro = RoutePlace("qa:metro", "Лубянка", GeoPoint(55.7598, 37.6270))
        val interchange = RoutePlace("qa:interchange", "Свиблово", GeoPoint(55.8552, 37.6527))
        val destination = RoutePlace("qa:destination", "Бабушкинская", GeoPoint(55.8694, 37.6644))

        val busMetro = RouteCandidate(
            id = "qa-bus-metro",
            requestedDepartureEpochSec = now,
            legs = listOf(
                RouteLeg(TransportMode.WALK, origin, stop, now, now + 5 * 60, walkMeters = 360, realtimeConfidence = 0.96),
                RouteLeg(TransportMode.BUS, stop, metro, now + 7 * 60, now + 18 * 60, lineId = "qa:m2", lineName = "м2", waitSeconds = 2 * 60, realtimeConfidence = 0.78, stopCount = 5),
                RouteLeg(TransportMode.WALK, metro, metro, now + 18 * 60, now + 21 * 60, walkMeters = 170, realtimeConfidence = 0.9),
                RouteLeg(TransportMode.METRO, metro, interchange, now + 23 * 60, now + 38 * 60, lineId = "qa:6", lineName = "6", waitSeconds = 2 * 60, realtimeConfidence = 0.72, stopCount = 7),
                RouteLeg(TransportMode.WALK, interchange, destination, now + 38 * 60, now + 43 * 60, walkMeters = 390, realtimeConfidence = 0.96)
            )
        )
        val tramMcc = RouteCandidate(
            id = "qa-tram-mcc",
            requestedDepartureEpochSec = now,
            legs = listOf(
                RouteLeg(TransportMode.WALK, origin, stop, now, now + 4 * 60, walkMeters = 290, realtimeConfidence = 0.96),
                RouteLeg(TransportMode.TRAM, stop, metro, now + 8 * 60, now + 22 * 60, lineId = "qa:39", lineName = "39", waitSeconds = 4 * 60, realtimeConfidence = 0.78, stopCount = 6),
                RouteLeg(TransportMode.MCC, metro, interchange, now + 26 * 60, now + 43 * 60, lineId = "qa:mcc", lineName = "МЦК", waitSeconds = 4 * 60, realtimeConfidence = 0.7, stopCount = 8),
                RouteLeg(TransportMode.WALK, interchange, destination, now + 43 * 60, now + 49 * 60, walkMeters = 430, realtimeConfidence = 0.96)
            )
        )
        val mcdTrain = RouteCandidate(
            id = "qa-mcd-train",
            requestedDepartureEpochSec = now,
            legs = listOf(
                RouteLeg(TransportMode.WALK, origin, metro, now, now + 8 * 60, walkMeters = 590, realtimeConfidence = 0.96),
                RouteLeg(TransportMode.MCD, metro, interchange, now + 12 * 60, now + 35 * 60, lineId = "mtppk:qa-d3", lineName = "D3 · 7201", waitSeconds = 4 * 60, realtimeConfidence = 0.82, stopCount = 9),
                RouteLeg(TransportMode.TRAIN, interchange, destination, now + 40 * 60, now + 51 * 60, lineId = "mtppk:qa-6501", lineName = "6501", waitSeconds = 5 * 60, realtimeConfidence = 0.82, stopCount = 3),
                RouteLeg(TransportMode.WALK, destination, destination, now + 51 * 60, now + 53 * 60, walkMeters = 140, realtimeConfidence = 0.96)
            )
        )
        return listOf(busMetro, tramMcc, mcdTrain)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            settingsScrim.visibility == View.VISIBLE -> closeSettings()
            searchExpanded -> collapseSearch()
            currentTab != Tab.MAP || routeResultsContainer.visibility == View.VISIBLE || tabEmptyPanel.visibility == View.VISIBLE ->
                selectTab(Tab.MAP)
            else -> super.onBackPressed()
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        val state = locationMachine.state
        if (hasLocationPermission() && (
                state is LocationState.PermissionDenied ||
                    state is LocationState.ProviderDisabled ||
                    state is LocationState.Timeout ||
                    state is LocationState.Error
                )
        ) {
            fetchCurrentLocation()
        }
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopLocationUpdates()
        executor.shutdownNow()
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    private enum class Tab {
        MAP,
        ROUTES,
        TRANSPORT,
        FAVORITES;

        companion object {
            fun fromStored(value: String?): Tab = entries.firstOrNull { it.name == value } ?: MAP
        }
    }

    private enum class LocationPurpose {
        NONE,
        CENTER,
        ORIGIN,
        NEARBY,
        TRIP;

        fun coerceUseful(): LocationPurpose = if (this == NONE) ORIGIN else this
    }

    companion object {
        private const val MOSCOW_LAT = 55.751244
        private const val MOSCOW_LON = 37.618423
        private const val CURRENT_LOCATION_LABEL = "Моё местоположение"
        private const val LOCATION_PERMISSION_REQUEST = 4108
        private const val NOTIFICATION_PERMISSION_REQUEST = 4107
        private const val EXTRA_QA_SCREEN = "qa_screen"
        private const val EXTRA_QA_DARK = "qa_dark"
        private const val PREF_LOCATION_REQUESTED = "location_permission_requested"
        private const val SEARCH_DEBOUNCE_MS = 420L
        private const val NEARBY_CAMERA_DEBOUNCE_MS = 650L
        private const val LOCATION_TIMEOUT_MS = 12_000L
        private const val LOCATION_CACHE_MAX_AGE_MS = 2 * 60_000L
        private const val LAST_LOCATION_MAX_AGE_MS = 30 * 60_000L
        private const val JOURNEY_FRAME_MS = 720L
        private const val MAX_SEARCH_RESULTS = 6

        private const val ROUTE_SOURCE_ID = "vh-route-source"
        private const val ROUTE_LAYER_ID = "vh-route-layer"
        private const val ROUTE_STOPS_SOURCE_ID = "vh-route-stops-source"
        private const val ROUTE_STOPS_LAYER_ID = "vh-route-stops-layer"
        private const val NEARBY_SOURCE_ID = "vh-nearby-source"
        private const val NEARBY_LAYER_ID = "vh-nearby-layer"
        private const val LOCATION_SOURCE_ID = "vh-location-source"
        private const val LOCATION_LAYER_ID = "vh-location-layer"

        private val METRO_FILTER_MODES = setOf(
            TransportMode.METRO,
            TransportMode.MCC
        )
    }
}
