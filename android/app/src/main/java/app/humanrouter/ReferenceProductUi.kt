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
import android.view.ViewTreeObserver
import android.widget.Button
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
 * Product-level presentation skin based on the approved visual references.
 *
 * It deliberately stays above routing/search logic: no private MainActivity access, no reflection,
 * no fabricated transport state. The class owns composition, spacing, cards and the active-trip
 * chrome while MainActivity remains the source of user actions and route data.
 */
internal object ReferenceProductUi {
    private val controllers = WeakHashMap<MainActivity, Controller>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (controllers.containsKey(activity)) return
        controllers[activity] = Controller(activity)
    }

    private class Controller(private val activity: MainActivity) {
        private val root = activity.findViewById<FrameLayout>(R.id.root)
        private val searchPanel = activity.findViewById<LinearLayout>(R.id.searchPanel)
        private val compactSearchRow = activity.findViewById<LinearLayout>(R.id.compactSearchRow)
        private val expandedSearch = activity.findViewById<View>(R.id.expandedSearchContent)
        private val compactSearchButton = activity.findViewById<TextView>(R.id.compactSearchButton)
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
        private val density = activity.resources.displayMetrics.density
        private var refreshPosted = false
        private var activeChromeRouteId: String? = null
        private var endpointRouteId: String? = null

        private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener { scheduleRefresh() }

        init {
            styleStaticChrome()
            installDynamicStyling()
            root.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    if (root.viewTreeObserver.isAlive) {
                        root.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
                    }
                    controllers.remove(activity)
                    root.removeOnAttachStateChangeListener(this)
                }
            })
            root.post(::refresh)
        }

        private fun scheduleRefresh() {
            if (refreshPosted) return
            refreshPosted = true
            root.post {
                refreshPosted = false
                refresh()
            }
        }

        private fun styleStaticChrome() {
            val primary = color(R.color.vh_primary)
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

            quickActions.apply {
                layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
                    leftMargin = dp(44)
                    rightMargin = dp(44)
                    height = dp(50)
                }
            }
            intArrayOf(R.id.homeQuickButton, R.id.workQuickButton, R.id.nearbyQuickButton).forEach { id ->
                activity.findViewById<TextView>(id).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    elevation = dp(6).toFloat()
                    background = rounded(surface, 24f, border, 0.5f)
                    minHeight = dp(48)
                    setPadding(dp(12), 0, dp(12), 0)
                }
            }

            styleRoundMapButton(locationButton, primary)
            styleRoundMapButton(settingsButton, primary)
            locationButton.translationY = -dp(44).toFloat()
            settingsButton.translationY = dp(22).toFloat()

            nearbyPanel.apply {
                elevation = dp(22).toFloat()
                background = rounded(surface, 30f, border, 0.5f)
                setPadding(dp(18), dp(10), dp(18), dp(8))
            }
            bottomNav.apply {
                elevation = dp(24).toFloat()
                background = rounded(surface, 28f, border, 0.5f)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
                    leftMargin = dp(16)
                    rightMargin = dp(16)
                    height = dp(70)
                }
            }
            intArrayOf(R.id.mapNavButton, R.id.routesNavButton, R.id.transportNavButton, R.id.favoritesNavButton).forEach { id ->
                activity.findViewById<TextView>(id).apply {
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    textSize = 10.5f
                    minHeight = dp(60)
                    compoundDrawablePadding = dp(5)
                }
            }

            routeSheet.apply {
                elevation = dp(26).toFloat()
                background = rounded(surface, 31f, border, 0.5f)
                setPadding(dp(18), dp(9), dp(18), dp(12))
                (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.leftMargin = dp(14)
                    lp.rightMargin = dp(14)
                    layoutParams = lp
                }
            }
            routePrimaryAction.apply {
                minHeight = dp(54)
                textSize = 17f
                background = rounded(primary, 18f)
            }

            loadingPanel.apply {
                elevation = dp(14).toFloat()
                background = rounded(surface, 24f, border, 0.5f)
            }
            styleSettings()
        }

        private fun styleRoundMapButton(button: ImageButton, tint: Int) {
            button.apply {
                layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
                    width = dp(56)
                    height = dp(56)
                    rightMargin = dp(20)
                }
                elevation = dp(15).toFloat()
                background = rounded(color(R.color.vh_surface_solid), 28f, color(R.color.vh_border), 0.5f)
                imageTintList = ColorStateList.valueOf(tint)
                setPadding(dp(15), dp(15), dp(15), dp(15))
            }
        }

        private fun styleSettings() {
            settingsScrim.setBackgroundColor(Color.argb(58, 8, 20, 39))
            val sheet = settingsPanel.parent as? ScrollView ?: return
            val width = activity.resources.displayMetrics.widthPixels
            val target = max((width * 0.60f).roundToInt(), dp(248)).coerceAtMost(width - dp(36))
            sheet.layoutParams = (sheet.layoutParams as FrameLayout.LayoutParams).apply {
                this.width = target
                height = FrameLayout.LayoutParams.MATCH_PARENT
                gravity = Gravity.END
                leftMargin = 0
            }
            sheet.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color(R.color.vh_surface_solid))
                cornerRadii = floatArrayOf(
                    dp(32).toFloat(), dp(32).toFloat(),
                    0f, 0f,
                    0f, 0f,
                    dp(32).toFloat(), dp(32).toFloat()
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
                    minHeight = dp(74)
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, dp(8), 0, dp(8))
                }
            }
            activity.findViewById<TextView>(R.id.closeSettingsButton).apply {
                textSize = 31f
                setTextColor(color(R.color.vh_text_secondary))
            }
            activity.findViewById<Button>(R.id.checkDataButton).apply {
                minHeight = dp(52)
                background = rounded(color(R.color.vh_primary_soft), 17f)
                setTextColor(color(R.color.vh_primary))
            }
        }

        private fun installDynamicStyling() {
            routePanel.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    child?.post { styleRoutePanel(); scheduleRefresh() }
                }
                override fun onChildViewRemoved(parent: View?, child: View?) = Unit
            })
            nearbyList.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    child?.post { styleNearbyRow(child) }
                }
                override fun onChildViewRemoved(parent: View?, child: View?) = Unit
            })
        }

        private fun refresh() {
            styleRoutePanel()
            styleFilterPanel()
            styleSettings()

            val active = isActiveTrip()
            val routeOptions = routeSheet.visibility == View.VISIBLE && routeFilters.visibility == View.VISIBLE && !active
            val searchExpanded = expandedSearch.visibility == View.VISIBLE

            if (active) {
                searchPanel.visibility = View.GONE
                quickActions.visibility = View.GONE
                locationButton.visibility = View.GONE
                settingsButton.visibility = View.GONE
                bottomNav.visibility = View.GONE
                ensureActiveChrome()
                removeEndpointsHeader()
                applyRouteSheetGeometry(activeTrip = true)
            } else {
                removeActiveChrome()
                if (!searchExpanded) searchPanel.visibility = View.VISIBLE
                locationButton.visibility = View.VISIBLE
                settingsButton.visibility = View.VISIBLE
                if (routeOptions) {
                    bottomNav.visibility = View.GONE
                    ensureEndpointsHeader()
                    applyRouteSheetGeometry(activeTrip = false)
                } else {
                    removeEndpointsHeader()
                    if (!searchExpanded && settingsScrim.visibility != View.VISIBLE) bottomNav.visibility = View.VISIBLE
                    styleNearbyGeometry()
                }
            }
        }

        private fun styleNearbyGeometry() {
            if (nearbyPanel.visibility != View.VISIBLE) return
            val screenHeightDp = activity.resources.configuration.screenHeightDp
            val targetDp = (screenHeightDp * 0.28f).roundToInt().coerceIn(210, 292)
            nearbyPanel.layoutParams = (nearbyPanel.layoutParams as FrameLayout.LayoutParams).apply {
                height = dp(targetDp)
                leftMargin = dp(16)
                rightMargin = dp(16)
                val navLp = bottomNav.layoutParams as FrameLayout.LayoutParams
                bottomMargin = navLp.bottomMargin + dp(68)
            }
        }

        private fun applyRouteSheetGeometry(activeTrip: Boolean) {
            val screenHeightDp = activity.resources.configuration.screenHeightDp
            val targetDp = if (activeTrip) {
                (screenHeightDp * 0.35f).roundToInt().coerceIn(280, 360)
            } else {
                (screenHeightDp * 0.48f).roundToInt().coerceIn(350, 470)
            }
            routeSheet.layoutParams = (routeSheet.layoutParams as FrameLayout.LayoutParams).apply {
                height = dp(targetDp)
                leftMargin = dp(14)
                rightMargin = dp(14)
                val navLp = bottomNav.layoutParams as FrameLayout.LayoutParams
                bottomMargin = if (activeTrip) navLp.bottomMargin + dp(88) else navLp.bottomMargin + dp(8)
            }
        }

        private fun styleFilterPanel() {
            for (index in 0 until routeFilterPanel.childCount) {
                (routeFilterPanel.getChildAt(index) as? TextView)?.apply {
                    minHeight = dp(40)
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
                val child = routePanel.getChildAt(index)
                when (child) {
                    is TextView -> {
                        val text = child.text?.toString().orEmpty()
                        if (text == "Варианты маршрута" || text.contains("  →  ")) {
                            child.visibility = View.GONE
                        }
                    }
                    is Button -> if (child !== routePrimaryAction) {
                        child.background = rounded(color(R.color.vh_surface_solid), 17f, color(R.color.vh_border), 0.7f)
                        child.setTextColor(color(R.color.vh_primary))
                        child.minHeight = dp(48)
                    }
                    is LinearLayout -> {
                        if (child.isClickable) styleRouteCard(child)
                    }
                }
            }
        }

        private fun styleRouteCard(card: LinearLayout) {
            val selected = card.elevation > 0f
            card.apply {
                elevation = dp(if (selected) 7 else 3).toFloat()
                background = rounded(
                    color(R.color.vh_surface_solid),
                    22f,
                    color(if (selected) R.color.vh_primary else R.color.vh_border),
                    if (selected) 1.3f else 0.6f
                )
                setPadding(dp(16), dp(13), dp(16), dp(13))
                (layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.topMargin = dp(10)
                    layoutParams = lp
                }
            }
            val firstRow = card.getChildAt(0) as? LinearLayout
            val duration = firstRow?.getChildAt(0) as? TextView
            duration?.apply {
                textSize = 25f
                includeFontPadding = false
            }
        }

        private fun styleNearbyRow(view: View) {
            val row = view as? LinearLayout ?: return
            row.minimumHeight = dp(64)
            val badge = row.getChildAt(0) as? TextView
            badge?.apply {
                layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                    width = dp(48)
                    height = dp(48)
                }
                gravity = Gravity.CENTER
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                background = rounded(color(R.color.vh_primary_soft), 14f)
            }
            val divider = View(activity).apply {
                setBackgroundColor(color(R.color.vh_border))
            }
            if (row.findViewWithTag<View>(NEARBY_DIVIDER_TAG) == null) {
                divider.tag = NEARBY_DIVIDER_TAG
                row.addView(divider, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            }
        }

        private fun ensureEndpointsHeader() {
            val route = currentRoute() ?: return
            if (endpointRouteId == route.id && routeSheet.findViewWithTag<View>(ENDPOINTS_TAG) != null) return
            removeEndpointsHeader()
            endpointRouteId = route.id
            val origin = route.legs.first().from.name
            val destination = route.legs.last().to.name
            val card = LinearLayout(activity).apply {
                tag = ENDPOINTS_TAG
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(5), dp(4), dp(5))
                addView(endpointRow(origin, color(R.color.vh_success)))
                addView(endpointRow(destination, color(R.color.vh_mcc)).apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = dp(3)
                })
            }
            routeSheet.addView(
                card,
                1.coerceAtMost(routeSheet.childCount),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(78))
            )
        }

        private fun endpointRow(label: String, dotColor: Int): View = LinearLayout(activity).apply {
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
                text = label
                maxLines = 1
                textSize = 18f
                includeFontPadding = false
                setTextColor(color(R.color.vh_text_primary))
            }, LinearLayout.LayoutParams(0, dp(32), 1f))
        }

        private fun removeEndpointsHeader() {
            routeSheet.findViewWithTag<View>(ENDPOINTS_TAG)?.let(routeSheet::removeView)
            endpointRouteId = null
        }

        private fun ensureActiveChrome() {
            val route = currentRoute() ?: return
            if (activeChromeRouteId == route.id && root.findViewWithTag<View>(ACTIVE_TOP_TAG) != null) return
            removeActiveChrome()
            activeChromeRouteId = route.id
            val current = currentTransitLeg(route)
            val now = Instant.now().epochSecond
            val stops = current.stopCount.coerceAtLeast(1)
            val remainingStops = remainingStops(current, now)
            val remainingMinutes = max(1, ceil((current.arrivalEpochSec - now).coerceAtLeast(0) / 60.0).toInt())
            val line = current.lineName?.takeIf(String::isNotBlank) ?: modeLabel(current.mode)

            val top = LinearLayout(activity).apply {
                tag = ACTIVE_TOP_TAG
                orientation = LinearLayout.VERTICAL
                elevation = dp(28).toFloat()
                background = rounded(color(R.color.vh_surface_solid), 30f, color(R.color.vh_border), 0.5f)
                setPadding(dp(18), dp(16), dp(18), dp(16))

                val header = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                header.addView(TextView(activity).apply {
                    text = line
                    gravity = Gravity.CENTER
                    textSize = 18f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = rounded(modeColor(current.mode), 15f)
                    setPadding(dp(12), 0, dp(12), 0)
                }, LinearLayout.LayoutParams(dp(88), dp(58)).apply { rightMargin = dp(14) })
                header.addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(activity).apply {
                        text = "В пути"
                        textSize = 22f
                        includeFontPadding = false
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(color(R.color.vh_text_primary))
                    })
                    addView(TextView(activity).apply {
                        text = "В направлении ${current.to.name}"
                        textSize = 14f
                        includeFontPadding = false
                        setTextColor(color(R.color.vh_text_secondary))
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                header.addView(TextView(activity).apply {
                    text = "⌄"
                    gravity = Gravity.CENTER
                    textSize = 24f
                    setTextColor(color(R.color.vh_text_secondary))
                }, LinearLayout.LayoutParams(dp(38), dp(44)))
                addView(header)

                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(13), 0, dp(3))
                    addView(TextView(activity).apply {
                        text = current.from.name
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
                        text = current.to.name
                        maxLines = 1
                        gravity = Gravity.END
                        textSize = 12.5f
                        setTextColor(color(R.color.vh_text_secondary))
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                })
                addView(ReferenceTripProgressView(activity).apply {
                    progressFraction = tripProgress(current, now)
                    stopCount = stops.coerceIn(3, 7)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))

                addView(LinearLayout(activity).apply {
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
                            text = current.to.name
                            textSize = 13f
                            setTextColor(color(R.color.vh_text_tertiary))
                        })
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(activity).apply {
                        text = "$remainingMinutes мин\nдо пересадки"
                        gravity = Gravity.CENTER
                        textSize = 13f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(color(R.color.vh_success))
                        background = rounded(Color.argb(28, 18, 183, 106), 15f)
                        setPadding(dp(12), dp(7), dp(12), dp(7))
                    })
                })
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
                addView(TextView(activity).apply {
                    text = line
                    gravity = Gravity.CENTER
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = rounded(modeColor(current.mode), 13f)
                }, LinearLayout.LayoutParams(dp(78), dp(50)).apply { rightMargin = dp(12) })
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(activity).apply {
                        text = "В пути до ${current.to.name}"
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
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(activity).apply {
                    text = "$remainingMinutes мин"
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.vh_success))
                    background = rounded(Color.argb(28, 18, 183, 106), 14f)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                })
            }
            val navLp = bottomNav.layoutParams as FrameLayout.LayoutParams
            root.addView(mini, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(72)).apply {
                gravity = Gravity.BOTTOM
                leftMargin = dp(18)
                rightMargin = dp(18)
                bottomMargin = navLp.bottomMargin
            })
        }

        private fun removeActiveChrome() {
            root.findViewWithTag<View>(ACTIVE_TOP_TAG)?.let(root::removeView)
            root.findViewWithTag<View>(ACTIVE_MINI_TAG)?.let(root::removeView)
            activeChromeRouteId = null
        }

        private fun currentRoute(): RouteCandidate? = LastPlanStore.seed?.route

        private fun currentTransitLeg(route: RouteCandidate): RouteLeg {
            val now = Instant.now().epochSecond
            return route.legs.firstOrNull { it.mode != TransportMode.WALK && now < it.arrivalEpochSec }
                ?: route.legs.firstOrNull { it.mode != TransportMode.WALK }
                ?: route.legs.first()
        }

        private fun remainingStops(leg: RouteLeg, now: Long): Int {
            if (leg.stopCount <= 0) return 0
            val fraction = tripProgress(leg, now)
            return (leg.stopCount - (leg.stopCount * fraction).toInt()).coerceAtLeast(0)
        }

        private fun tripProgress(leg: RouteLeg, now: Long): Float {
            val duration = (leg.arrivalEpochSec - leg.departureEpochSec).coerceAtLeast(1)
            return ((now - leg.departureEpochSec).toDouble() / duration.toDouble()).coerceIn(0.08, 0.92).toFloat()
        }

        private fun isActiveTrip(): Boolean = routeSheet.visibility == View.VISIBLE &&
            routePrimaryAction.visibility == View.VISIBLE &&
            routePrimaryAction.text?.toString()?.contains("Завершить", ignoreCase = true) == true

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

    private const val ACTIVE_TOP_TAG = "reference_active_trip_top"
    private const val ACTIVE_MINI_TAG = "reference_active_trip_mini"
    private const val ENDPOINTS_TAG = "reference_route_endpoints"
    private const val NEARBY_DIVIDER_TAG = "reference_nearby_divider"
}

internal class ReferenceTripProgressView(context: android.content.Context) : View(context) {
    var progressFraction: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    var stopCount: Int = 5
        set(value) {
            field = value.coerceIn(3, 7)
            invalidate()
        }

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
            val passed = x <= currentX
            canvas.drawCircle(x, y, 4.2f * density, if (passed) active else inactive)
            canvas.drawCircle(x, y, 2.0f * density, white)
        }
        canvas.drawCircle(currentX, y, 9f * density, white)
        canvas.drawCircle(currentX, y, 7f * density, active)
        canvas.drawCircle(currentX, y, 3f * density, white)
    }
}
