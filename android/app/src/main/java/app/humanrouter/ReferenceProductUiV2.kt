package app.humanrouter

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import app.humanrouter.routing.LastPlanStore
import app.humanrouter.routing.RouteCandidate
import app.humanrouter.routing.RouteLeg
import app.humanrouter.routing.TransportMode
import java.time.Instant
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Event-driven product presentation matching the approved reference screens.
 *
 * Routing/search state remains owned by MainActivity. This controller only changes visual
 * composition and reacts to actual hierarchy changes; it never polls layout, uses reflection,
 * fabricates transport data or keeps the main thread permanently busy.
 */
internal object ReferenceProductUiV2 {
    private val controllers = WeakHashMap<MainActivity, Controller>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (controllers.containsKey(activity)) return
        controllers[activity] = Controller(activity)
    }

    private class Controller(private val activity: MainActivity) {
        private val density = activity.resources.displayMetrics.density
        private val root = activity.findViewById<FrameLayout>(R.id.root)
        private val searchPanel = activity.findViewById<LinearLayout>(R.id.searchPanel)
        private val compactSearchRow = activity.findViewById<LinearLayout>(R.id.compactSearchRow)
        private val compactSearchButton = activity.findViewById<TextView>(R.id.compactSearchButton)
        private val expandedSearch = activity.findViewById<View>(R.id.expandedSearchContent)
        private val quickActions = activity.findViewById<LinearLayout>(R.id.quickActions)
        private val locationButton = activity.findViewById<ImageButton>(R.id.locationButton)
        private val settingsButton = activity.findViewById<ImageButton>(R.id.settingsButton)
        private val nearbyPanel = activity.findViewById<LinearLayout>(R.id.nearbyPanel)
        private val nearbyList = activity.findViewById<LinearLayout>(R.id.nearbyList)
        private val routeSheet = activity.findViewById<LinearLayout>(R.id.routeResultsContainer)
        private val routeFilters = activity.findViewById<HorizontalScrollView>(R.id.routeFiltersScroll)
        private val routeFilterPanel = activity.findViewById<LinearLayout>(R.id.routeFiltersPanel)
        private val routePanel = activity.findViewById<LinearLayout>(R.id.routeResultsPanel)
        private val routePrimaryAction = activity.findViewById<Button>(R.id.routePrimaryAction)
        private val bottomNav = activity.findViewById<LinearLayout>(R.id.bottomNav)
        private val settingsScrim = activity.findViewById<FrameLayout>(R.id.settingsScrim)
        private val settingsPanel = activity.findViewById<LinearLayout>(R.id.settingsPanel)
        private val loadingPanel = activity.findViewById<LinearLayout>(R.id.loadingPanel)
        private val fromField = activity.findViewById<EditText>(R.id.fromField)
        private val toField = activity.findViewById<EditText>(R.id.toField)

        private var refreshPosted = false
        private var activeRouteId: String? = null
        private var endpointRouteId: String? = null

        init {
            styleStaticChrome()
            installHierarchyHooks()
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    controllers.remove(activity)
                    root.removeOnAttachStateChangeListener(this)
                }
            })
            scheduleRefresh(0L)
            scheduleRefresh(180L)
            scheduleRefresh(650L)
        }

        private fun installHierarchyHooks() {
            routePanel.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    child?.post {
                        styleRoutePanel()
                        scheduleRefresh(0L)
                    }
                }
                override fun onChildViewRemoved(parent: View?, child: View?) {
                    scheduleRefresh(0L)
                }
            })
            nearbyList.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    child?.post { styleNearbyRow(child) }
                }
                override fun onChildViewRemoved(parent: View?, child: View?) = Unit
            })
        }

        private fun scheduleRefresh(delayMs: Long) {
            if (delayMs == 0L && refreshPosted) return
            if (delayMs == 0L) refreshPosted = true
            root.postDelayed({
                if (delayMs == 0L) refreshPosted = false
                refreshComposition()
            }, delayMs)
        }

        private fun styleStaticChrome() {
            val surface = color(R.color.vh_surface_solid)
            val border = color(R.color.vh_border)

            searchPanel.apply {
                elevation = dp(14).toFloat()
                background = rounded(surface, 31f, border, 0.6f)
                setPadding(dp(8), dp(5), dp(8), dp(5))
                (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.leftMargin = dp(28)
                    lp.rightMargin = dp(28)
                    layoutParams = lp
                }
            }
            compactSearchRow.layoutParams = compactSearchRow.layoutParams.apply { height = dp(60) }
            compactSearchButton.apply {
                textSize = 17.5f
                includeFontPadding = false
            }

            (quickActions.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.leftMargin = dp(44)
                lp.rightMargin = dp(44)
                lp.height = dp(50)
                quickActions.layoutParams = lp
            }
            intArrayOf(R.id.homeQuickButton, R.id.workQuickButton, R.id.nearbyQuickButton).forEach { id ->
                activity.findViewById<TextView>(id).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    elevation = dp(6).toFloat()
                    background = rounded(surface, 24f, border, 0.5f)
                    minimumHeight = dp(48)
                    setPadding(dp(12), 0, dp(12), 0)
                }
            }

            styleRoundMapButton(locationButton)
            styleRoundMapButton(settingsButton)

            nearbyPanel.apply {
                elevation = dp(22).toFloat()
                background = rounded(surface, 30f, border, 0.5f)
                setPadding(dp(18), dp(10), dp(18), dp(8))
            }
            bottomNav.apply {
                elevation = dp(24).toFloat()
                background = rounded(surface, 28f, border, 0.5f)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.leftMargin = dp(16)
                    lp.rightMargin = dp(16)
                    lp.height = dp(70)
                    layoutParams = lp
                }
            }
            intArrayOf(R.id.mapNavButton, R.id.routesNavButton, R.id.transportNavButton, R.id.favoritesNavButton).forEach { id ->
                activity.findViewById<TextView>(id).apply {
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    textSize = 10.5f
                    minimumHeight = dp(60)
                    compoundDrawablePadding = dp(5)
                }
            }

            routeSheet.apply {
                elevation = dp(26).toFloat()
                background = rounded(surface, 31f, border, 0.5f)
                setPadding(dp(18), dp(9), dp(18), dp(12))
            }
            routePrimaryAction.apply {
                minimumHeight = dp(54)
                textSize = 17f
                background = rounded(color(R.color.vh_primary), 18f)
            }
            loadingPanel.apply {
                elevation = dp(14).toFloat()
                background = rounded(surface, 24f, border, 0.5f)
            }

            styleSettingsSheet()
            styleFilterPanel()
            styleRoutePanel()
            for (index in 0 until nearbyList.childCount) styleNearbyRow(nearbyList.getChildAt(index))
        }

        private fun styleRoundMapButton(button: ImageButton) {
            button.apply {
                (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.width = dp(56)
                    lp.height = dp(56)
                    lp.rightMargin = dp(20)
                    layoutParams = lp
                }
                elevation = dp(15).toFloat()
                background = rounded(color(R.color.vh_surface_solid), 28f, color(R.color.vh_border), 0.5f)
                imageTintList = ColorStateList.valueOf(color(R.color.vh_primary))
                setPadding(dp(15), dp(15), dp(15), dp(15))
            }
        }

        private fun styleSettingsSheet() {
            settingsScrim.setBackgroundColor(Color.argb(58, 8, 20, 39))
            val scroll = settingsPanel.parent as? ScrollView ?: return
            val width = activity.resources.displayMetrics.widthPixels
            val target = max((width * 0.60f).roundToInt(), dp(248)).coerceAtMost(width - dp(36))
            (scroll.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = target
                lp.height = FrameLayout.LayoutParams.MATCH_PARENT
                lp.gravity = Gravity.END
                lp.leftMargin = 0
                scroll.layoutParams = lp
            }
            scroll.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color(R.color.vh_surface_solid))
                cornerRadii = floatArrayOf(
                    dp(32).toFloat(), dp(32).toFloat(), 0f, 0f,
                    0f, 0f, dp(32).toFloat(), dp(32).toFloat()
                )
            }
            settingsPanel.setPadding(dp(24), settingsPanel.paddingTop, dp(22), settingsPanel.paddingBottom)

            val descriptions = mapOf(
                R.id.showStopsSwitch to ("Показывать остановки" to "Отображать остановки общественного транспорта на карте"),
                R.id.showTransportSwitch to ("Показывать линии маршрута" to "Показывать транспортные сегменты выбранного маршрута"),
                R.id.darkThemeSwitch to ("Тёмная тема" to "Использовать тёмную тему интерфейса и карты"),
                R.id.lessWalkingSwitch to ("Меньше ходьбы" to "Предпочитать варианты с минимальной ходьбой"),
                R.id.avoidTransfersSwitch to ("Избегать пересадок" to "Предпочитать маршруты с меньшим числом пересадок")
            )
            descriptions.forEach { (id, copy) ->
                activity.findViewById<SwitchCompat>(id).apply {
                    text = "${copy.first}\n${copy.second}"
                    textSize = 14f
                    includeFontPadding = false
                    minimumHeight = dp(74)
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, dp(8), 0, dp(8))
                }
            }
            activity.findViewById<TextView>(R.id.closeSettingsButton).apply {
                textSize = 31f
                setTextColor(color(R.color.vh_text_secondary))
            }
            activity.findViewById<Button>(R.id.checkDataButton).apply {
                minimumHeight = dp(52)
                background = rounded(color(R.color.vh_primary_soft), 17f)
                setTextColor(color(R.color.vh_primary))
            }
        }

        private fun refreshComposition() {
            styleRoutePanel()
            styleFilterPanel()
            val active = isActiveTrip()
            val routes = routeSheet.visibility == View.VISIBLE && routeFilters.visibility == View.VISIBLE && !active
            val expanded = expandedSearch.visibility == View.VISIBLE

            if (active) {
                searchPanel.visibility = View.GONE
                quickActions.visibility = View.GONE
                locationButton.visibility = View.GONE
                settingsButton.visibility = View.GONE
                bottomNav.visibility = View.GONE
                removeEndpointHeader()
                ensureActiveChrome()
                hideLegacyActiveHeader()
                applyRouteSheetGeometry(true)
            } else {
                removeActiveChrome()
                if (!expanded) searchPanel.visibility = View.VISIBLE
                locationButton.visibility = View.VISIBLE
                settingsButton.visibility = View.VISIBLE
                if (routes) {
                    quickActions.visibility = View.GONE
                    bottomNav.visibility = View.GONE
                    ensureEndpointHeader()
                    applyRouteSheetGeometry(false)
                } else {
                    removeEndpointHeader()
                    if (!expanded && settingsScrim.visibility != View.VISIBLE) bottomNav.visibility = View.VISIBLE
                    applyNearbyGeometry()
                }
            }
        }

        private fun applyNearbyGeometry() {
            if (nearbyPanel.visibility != View.VISIBLE) return
            val target = (activity.resources.configuration.screenHeightDp * 0.28f)
                .roundToInt().coerceIn(210, 292)
            val navLp = bottomNav.layoutParams as? FrameLayout.LayoutParams
            (nearbyPanel.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.height = dp(target)
                lp.leftMargin = dp(16)
                lp.rightMargin = dp(16)
                lp.bottomMargin = (navLp?.bottomMargin ?: 0) + dp(68)
                nearbyPanel.layoutParams = lp
            }
        }

        private fun applyRouteSheetGeometry(active: Boolean) {
            val target = if (active) {
                (activity.resources.configuration.screenHeightDp * 0.35f).roundToInt().coerceIn(280, 360)
            } else {
                (activity.resources.configuration.screenHeightDp * 0.48f).roundToInt().coerceIn(350, 470)
            }
            val navBottom = (bottomNav.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin ?: 0
            (routeSheet.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.height = dp(target)
                lp.leftMargin = dp(14)
                lp.rightMargin = dp(14)
                lp.bottomMargin = navBottom + if (active) dp(88) else dp(8)
                routeSheet.layoutParams = lp
            }
        }

        private fun styleFilterPanel() {
            for (index in 0 until routeFilterPanel.childCount) {
                (routeFilterPanel.getChildAt(index) as? TextView)?.apply {
                    minimumHeight = dp(40)
                    includeFontPadding = false
                    textSize = 11.5f
                    setPadding(dp(11), 0, dp(11), 0)
                    elevation = 0f
                }
            }
            routeFilters.layoutParams = routeFilters.layoutParams.apply { height = dp(46) }
        }

        private fun styleRoutePanel() {
            for (index in 0 until routePanel.childCount) {
                when (val child = routePanel.getChildAt(index)) {
                    is TextView -> {
                        val text = child.text?.toString().orEmpty()
                        if (text.contains("  →  ")) child.visibility = View.GONE
                        if (text == "Варианты маршрута") {
                            child.visibility = View.VISIBLE
                            child.textSize = 12f
                            child.setTextColor(color(R.color.vh_text_tertiary))
                        }
                    }
                    is LinearLayout -> if (child.isClickable) styleRouteCard(child)
                }
            }
        }

        private fun styleRouteCard(card: LinearLayout) {
            val selected = card.elevation > 0f
            card.apply {
                elevation = dp(if (selected) 7 else 3).toFloat()
                background = rounded(
                    color(R.color.vh_surface_solid), 22f,
                    color(if (selected) R.color.vh_primary else R.color.vh_border),
                    if (selected) 1.3f else 0.6f
                )
                setPadding(dp(16), dp(13), dp(16), dp(13))
                (layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.topMargin = dp(10)
                    layoutParams = lp
                }
            }
            val first = card.getChildAt(0) as? LinearLayout
            (first?.getChildAt(0) as? TextView)?.apply {
                textSize = 25f
                includeFontPadding = false
            }
        }

        private fun styleNearbyRow(view: View) {
            val row = view as? LinearLayout ?: return
            row.minimumHeight = dp(64)
            (row.getChildAt(0) as? TextView)?.apply {
                val lp = layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(dp(48), dp(48))
                lp.width = dp(48)
                lp.height = dp(48)
                layoutParams = lp
                gravity = Gravity.CENTER
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                background = rounded(color(R.color.vh_primary_soft), 14f)
            }
        }

        private fun ensureEndpointHeader() {
            val route = currentRoute() ?: return
            val existing = routeSheet.findViewWithTag<View>(ENDPOINTS_TAG)
            if (existing != null && endpointRouteId == route.id) {
                updateEndpointTexts(existing as ViewGroup)
                return
            }
            removeEndpointHeader()
            endpointRouteId = route.id

            val card = LinearLayout(activity).apply {
                tag = ENDPOINTS_TAG
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val first = endpointRow(displayOrigin(route), color(R.color.vh_success))
            val second = endpointRow(displayDestination(route), color(R.color.vh_mcc))
            card.addView(first, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))
            card.addView(second, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)).apply {
                topMargin = dp(2)
            })
            routeSheet.addView(
                card,
                1.coerceAtMost(routeSheet.childCount),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76))
            )
        }

        private fun updateEndpointTexts(card: ViewGroup) {
            val labels = descendants(card).filterIsInstance<TextView>().toList()
            val route = currentRoute() ?: return
            if (labels.isNotEmpty()) labels[0].text = displayOrigin(route)
            if (labels.size > 1) labels[1].text = displayDestination(route)
        }

        private fun displayOrigin(route: RouteCandidate): String =
            fromField.text?.toString().orEmpty().substringBefore(',').trim()
                .ifBlank { route.legs.first().from.name }

        private fun displayDestination(route: RouteCandidate): String =
            toField.text?.toString().orEmpty().substringBefore(',').trim()
                .ifBlank { route.legs.last().to.name }

        private fun endpointRow(textValue: String, dotColor: Int): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color(R.color.vh_surface_solid))
                    setStroke(dp(3), dotColor)
                }
            }, LinearLayout.LayoutParams(dp(17), dp(17)).apply { rightMargin = dp(12) })
            addView(TextView(activity).apply {
                text = textValue
                maxLines = 1
                textSize = 18f
                includeFontPadding = false
                setTextColor(color(R.color.vh_text_primary))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        private fun removeEndpointHeader() {
            routeSheet.findViewWithTag<View>(ENDPOINTS_TAG)?.let(routeSheet::removeView)
            endpointRouteId = null
        }

        private fun ensureActiveChrome() {
            val route = currentRoute() ?: return
            if (activeRouteId == route.id && root.findViewWithTag<View>(ACTIVE_TOP_TAG) != null) return
            removeActiveChrome()
            activeRouteId = route.id
            val leg = currentTransitLeg(route)
            val now = Instant.now().epochSecond
            val stops = leg.stopCount.coerceAtLeast(3).coerceAtMost(7)
            val remainingStops = remainingStops(leg, now)
            val minutes = max(1, ceil((leg.arrivalEpochSec - now).coerceAtLeast(0L) / 60.0).toInt())
            val line = leg.lineName?.takeIf(String::isNotBlank) ?: modeLabel(leg.mode)

            val top = LinearLayout(activity).apply {
                tag = ACTIVE_TOP_TAG
                orientation = LinearLayout.VERTICAL
                elevation = dp(28).toFloat()
                background = rounded(color(R.color.vh_surface_solid), 30f, color(R.color.vh_border), 0.5f)
                setPadding(dp(18), dp(16), dp(18), dp(16))
                addView(activeHeader(line, leg))
                addView(activeStationRow(leg))
                addView(ReferenceTripProgressViewV2(activity).apply {
                    progressFraction = tripProgress(leg, now)
                    stopCount = stops
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
                addView(activeExitRow(leg, remainingStops, minutes))
            }
            val topMargin = (searchPanel.layoutParams as? FrameLayout.LayoutParams)?.topMargin ?: dp(18)
            root.addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
                leftMargin = dp(20)
                rightMargin = dp(20)
                this.topMargin = topMargin
            })

            val mini = LinearLayout(activity).apply {
                tag = ACTIVE_MINI_TAG
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                elevation = dp(28).toFloat()
                background = rounded(color(R.color.vh_surface_solid), 27f, color(R.color.vh_border), 0.5f)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                addView(lineBadge(line, leg.mode, compact = true))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(activity).apply {
                        text = "В пути до ${leg.to.name}"
                        maxLines = 1
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(color(R.color.vh_text_primary))
                    })
                    addView(TextView(activity).apply {
                        text = if (remainingStops > 0) "Выходите через $remainingStops ${stopWord(remainingStops)}" else "Следующая — выход"
                        textSize = 12f
                        setTextColor(color(R.color.vh_text_tertiary))
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
                addView(TextView(activity).apply {
                    text = "$minutes мин"
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.vh_success))
                    background = rounded(Color.argb(28, 18, 183, 106), 14f)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                })
            }
            val navBottom = (bottomNav.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin ?: 0
            root.addView(mini, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(72)).apply {
                gravity = Gravity.BOTTOM
                leftMargin = dp(18)
                rightMargin = dp(18)
                bottomMargin = navBottom
            })
        }

        private fun activeHeader(line: String, leg: RouteLeg): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(lineBadge(line, leg.mode, compact = false))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = "В пути"
                    textSize = 22f
                    includeFontPadding = false
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.vh_text_primary))
                })
                addView(TextView(activity).apply {
                    text = "В направлении ${leg.to.name}"
                    textSize = 14f
                    includeFontPadding = false
                    setTextColor(color(R.color.vh_text_secondary))
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(14) })
            addView(TextView(activity).apply {
                text = "⌄"
                gravity = Gravity.CENTER
                textSize = 24f
                setTextColor(color(R.color.vh_text_secondary))
            }, LinearLayout.LayoutParams(dp(38), dp(44)))
        }

        private fun lineBadge(line: String, mode: TransportMode, compact: Boolean): TextView = TextView(activity).apply {
            text = line
            gravity = Gravity.CENTER
            textSize = if (compact) 15f else 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(modeColor(mode), if (compact) 13f else 15f)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_transport, 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
            compoundDrawablePadding = dp(5)
            layoutParams = LinearLayout.LayoutParams(if (compact) dp(82) else dp(96), if (compact) dp(50) else dp(58))
        }

        private fun activeStationRow(leg: RouteLeg): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), 0, dp(3))
            addView(TextView(activity).apply {
                text = leg.from.name
                maxLines = 1
                textSize = 12.5f
                setTextColor(color(R.color.vh_text_secondary))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(activity).apply {
                text = "Сейчас"
                gravity = Gravity.CENTER
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.vh_primary))
            }, LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(TextView(activity).apply {
                text = leg.to.name
                maxLines = 1
                gravity = Gravity.END
                textSize = 12.5f
                setTextColor(color(R.color.vh_text_secondary))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        private fun activeExitRow(leg: RouteLeg, remainingStops: Int, minutes: Int): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(TextView(activity).apply {
                text = "↳"
                gravity = Gravity.CENTER
                textSize = 22f
                setTextColor(color(R.color.vh_success))
                background = rounded(color(R.color.vh_surface_muted), 14f)
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = if (remainingStops > 0) "Выходите через $remainingStops ${stopWord(remainingStops)}" else "Готовьтесь к выходу"
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.vh_text_primary))
                })
                addView(TextView(activity).apply {
                    text = leg.to.name
                    textSize = 13f
                    setTextColor(color(R.color.vh_text_tertiary))
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(activity).apply {
                text = "$minutes мин\nдо пересадки"
                gravity = Gravity.CENTER
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.vh_success))
                background = rounded(Color.argb(28, 18, 183, 106), 15f)
                setPadding(dp(12), dp(7), dp(12), dp(7))
            })
        }

        private fun hideLegacyActiveHeader() {
            descendants(routePanel)
                .filterIsInstance<TextView>()
                .filter { it.text?.toString() == "В пути" }
                .forEach { it.visibility = View.GONE }
        }

        private fun removeActiveChrome() {
            root.findViewWithTag<View>(ACTIVE_TOP_TAG)?.let(root::removeView)
            root.findViewWithTag<View>(ACTIVE_MINI_TAG)?.let(root::removeView)
            activeRouteId = null
        }

        private fun currentRoute(): RouteCandidate? = LastPlanStore.seed?.route

        private fun currentTransitLeg(route: RouteCandidate): RouteLeg {
            val now = Instant.now().epochSecond
            return route.legs.firstOrNull { it.mode != TransportMode.WALK && now < it.arrivalEpochSec }
                ?: route.legs.firstOrNull { it.mode != TransportMode.WALK }
                ?: route.legs.first()
        }

        private fun isActiveTrip(): Boolean = routeSheet.visibility == View.VISIBLE &&
            routePrimaryAction.visibility == View.VISIBLE &&
            routePrimaryAction.text?.toString()?.contains("Завершить", ignoreCase = true) == true

        private fun remainingStops(leg: RouteLeg, now: Long): Int {
            if (leg.stopCount <= 0) return 0
            val fraction = tripProgress(leg, now)
            return (leg.stopCount - (leg.stopCount * fraction).toInt()).coerceAtLeast(0)
        }

        private fun tripProgress(leg: RouteLeg, now: Long): Float {
            val duration = (leg.arrivalEpochSec - leg.departureEpochSec).coerceAtLeast(1L)
            return ((now - leg.departureEpochSec).toDouble() / duration.toDouble()).coerceIn(0.08, 0.92).toFloat()
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

        private fun modeColor(mode: TransportMode): Int = color(
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

        private fun stopWord(count: Int): String = when {
            count % 10 == 1 && count % 100 != 11 -> "остановку"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "остановки"
            else -> "остановок"
        }

        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }

        private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 0f): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(fill)
                cornerRadius = dp(radiusDp).toFloat()
                if (stroke != null && strokeDp > 0f) setStroke(dp(strokeDp).coerceAtLeast(1), stroke)
            }

        private fun color(id: Int): Int = ContextCompat.getColor(activity, id)
        private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        private fun dp(value: Float): Int = (value * density + 0.5f).toInt()
    }

    private const val ENDPOINTS_TAG = "reference_route_endpoints"
    private const val ACTIVE_TOP_TAG = "reference_active_trip_top"
    private const val ACTIVE_MINI_TAG = "reference_active_trip_mini"
}

internal class ReferenceTripProgressViewV2(context: android.content.Context) : View(context) {
    var progressFraction: Float = 0.5f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var stopCount: Int = 5
        set(value) { field = value.coerceIn(3, 7); invalidate() }

    private val density = resources.displayMetrics.density
    private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.vh_primary)
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val inactive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.vh_border)
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 10f * density
        val right = width - 10f * density
        val y = height / 2f
        val currentX = left + (right - left) * progressFraction
        canvas.drawLine(left, y, right, y, inactive)
        canvas.drawLine(left, y, currentX, y, active)
        for (index in 0 until stopCount) {
            val x = left + (right - left) * index / (stopCount - 1).toFloat()
            canvas.drawCircle(x, y, 4.2f * density, if (x <= currentX) active else inactive)
            canvas.drawCircle(x, y, 2f * density, white)
        }
        canvas.drawCircle(currentX, y, 9f * density, white)
        canvas.drawCircle(currentX, y, 7f * density, active)
        canvas.drawCircle(currentX, y, 3f * density, white)
    }
}
