package app.humanrouter

import android.animation.AnimatorSet
import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.WeakHashMap

/** Lightweight visual polish layered over the existing screen without changing routing logic. */
internal object UiPolish {
    private val controllers = WeakHashMap<MainActivity, Controller>()

    @Synchronized
    fun install(activity: MainActivity) {
        if (controllers.containsKey(activity)) return
        controllers[activity] = Controller(activity)
    }

    private class Controller(private val activity: MainActivity) {
        private val root = activity.findViewById<ViewGroup>(R.id.root)
        private val density = activity.resources.displayMetrics.density
        private val visibility = LinkedHashMap<View, Int>()
        private val pressStyled = WeakHashMap<View, Boolean>()
        private val easeOut = DecelerateInterpolator(1.35f)

        private val appearanceTargets = listOfNotNull(
            activity.findViewById<View?>(R.id.expandedSearchContent),
            activity.findViewById<View?>(R.id.quickActions),
            activity.findViewById<View?>(R.id.nearbyPanel),
            activity.findViewById<View?>(R.id.routeResultsContainer),
            activity.findViewById<View?>(R.id.tabEmptyPanel),
            activity.findViewById<View?>(R.id.bottomNav),
            activity.findViewById<View?>(R.id.settingsScrim)
        )

        private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            appearanceTargets.forEach(::animateIfJustShown)
        }

        init {
            configureMotionContainers()
            styleStaticControls()
            installDynamicStyling()
            appearanceTargets.forEach { visibility[it] = it.visibility }
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
        }

        private fun configureMotionContainers() {
            val groups = intArrayOf(
                R.id.searchPanel,
                R.id.expandedSearchContent,
                R.id.suggestionsPanel,
                R.id.routeFiltersPanel,
                R.id.routeResultsPanel,
                R.id.nearbyList,
                R.id.bottomNav
            )
            groups.forEach { id ->
                activity.findViewById<ViewGroup?>(id)?.layoutTransition = smoothTransition()
            }
        }

        private fun smoothTransition(): LayoutTransition = LayoutTransition().apply {
            setDuration(LayoutTransition.APPEARING, 190L)
            setDuration(LayoutTransition.DISAPPEARING, 135L)
            setDuration(LayoutTransition.CHANGE_APPEARING, 190L)
            setDuration(LayoutTransition.CHANGE_DISAPPEARING, 165L)
            setStartDelay(LayoutTransition.APPEARING, 0L)
            setStartDelay(LayoutTransition.DISAPPEARING, 0L)
            setStartDelay(LayoutTransition.CHANGE_APPEARING, 0L)
            setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0L)
            setInterpolator(LayoutTransition.APPEARING, easeOut)
            setInterpolator(LayoutTransition.DISAPPEARING, easeOut)
            setInterpolator(LayoutTransition.CHANGE_APPEARING, easeOut)
            setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, easeOut)
            setAnimateParentHierarchy(false)
        }

        private fun styleStaticControls() {
            intArrayOf(
                R.id.routeButton,
                R.id.retryButton,
                R.id.locationPrimaryAction,
                R.id.locationSecondaryAction,
                R.id.routePrimaryAction,
                R.id.checkDataButton
            ).forEach { id ->
                activity.findViewById<Button?>(id)?.let(::styleButton)
            }

            activity.findViewById<Button?>(R.id.routeButton)?.apply {
                setExactHeight(dp(52))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            }
            activity.findViewById<Button?>(R.id.routePrimaryAction)?.apply {
                setExactHeight(dp(52))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            }
            activity.findViewById<Button?>(R.id.retryButton)?.apply {
                background = ContextCompat.getDrawable(activity, R.drawable.bg_chip)
                setTextColor(ContextCompat.getColor(activity, R.color.vh_primary))
            }

            activity.findViewById<ViewGroup?>(R.id.quickActions)?.let { row ->
                if (row.layoutParams.height > 0 && row.layoutParams.height < dp(48)) {
                    row.layoutParams = row.layoutParams.apply { height = dp(48) }
                }
            }
            intArrayOf(R.id.homeQuickButton, R.id.workQuickButton, R.id.nearbyQuickButton).forEach { id ->
                activity.findViewById<TextView?>(id)?.apply {
                    if (layoutParams.height > 0 && layoutParams.height < dp(48)) {
                        layoutParams = layoutParams.apply { height = dp(48) }
                    }
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    minimumHeight = dp(48)
                    setPadding(dp(10), 0, dp(10), 0)
                    installPressMotion(this)
                }
            }

            intArrayOf(R.id.clearFromButton, R.id.clearToButton, R.id.closeSearchButton, R.id.closeSettingsButton).forEach { id ->
                activity.findViewById<TextView?>(id)?.apply {
                    minimumWidth = dp(48)
                    minimumHeight = dp(48)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    installPressMotion(this)
                }
            }

            intArrayOf(R.id.mapNavButton, R.id.routesNavButton, R.id.transportNavButton, R.id.favoritesNavButton).forEach { id ->
                activity.findViewById<TextView?>(id)?.apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    compoundDrawablePadding = dp(4)
                    minimumHeight = dp(56)
                    maxLines = 1
                    installPressMotion(this)
                }
            }

            activity.findViewById<ImageButton?>(R.id.locationButton)?.let(::installPressMotion)
            activity.findViewById<ImageButton?>(R.id.settingsButton)?.let(::installPressMotion)
        }

        private fun styleButton(button: Button) {
            button.isAllCaps = false
            button.gravity = Gravity.CENTER
            button.includeFontPadding = false
            button.minimumHeight = dp(48)
            button.minHeight = dp(48)
            if (button.layoutParams.height > 0 && button.layoutParams.height < dp(48)) {
                button.layoutParams = button.layoutParams.apply { height = dp(48) }
            }
            button.setPadding(dp(18), 0, dp(18), 0)
            button.maxLines = 1
            button.letterSpacing = 0f
            installPressMotion(button)
        }

        private fun Button.setExactHeight(heightPx: Int) {
            minimumHeight = heightPx
            minHeight = heightPx
            layoutParams = layoutParams.apply { height = heightPx }
        }

        private fun installDynamicStyling() {
            intArrayOf(R.id.suggestionsPanel, R.id.routeFiltersPanel, R.id.routeResultsPanel, R.id.nearbyList).forEach { id ->
                val group = activity.findViewById<ViewGroup?>(id) ?: return@forEach
                for (index in 0 until group.childCount) styleTree(group.getChildAt(index))
                group.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewAdded(parent: View?, child: View?) {
                        child?.post { styleTree(child) }
                    }
                    override fun onChildViewRemoved(parent: View?, child: View?) = Unit
                })
            }
        }

        private fun styleTree(view: View) {
            when (view) {
                is Button -> styleButton(view)
                is TextView -> {
                    view.includeFontPadding = false
                    if (view.isClickable) installPressMotion(view)
                }
                is ImageButton -> installPressMotion(view)
            }
            if (view is ViewGroup && view !is ScrollView) {
                for (i in 0 until view.childCount) styleTree(view.getChildAt(i))
            }
        }

        private fun installPressMotion(view: View) {
            if (view is EditText || pressStyled.put(view, true) == true) return
            val pressed = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 0.985f),
                    ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.985f),
                    ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.94f)
                )
                duration = 85L
                interpolator = easeOut
            }
            val released = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, View.SCALE_X, 0.985f, 1f),
                    ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.985f, 1f),
                    ObjectAnimator.ofFloat(view, View.ALPHA, 0.94f, 1f)
                )
                duration = 155L
                interpolator = easeOut
            }
            view.stateListAnimator = StateListAnimator().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), released)
            }
        }

        private fun animateIfJustShown(view: View) {
            val old = visibility.put(view, view.visibility)
            if (old == null || old == View.VISIBLE || view.visibility != View.VISIBLE) return
            view.animate().cancel()
            val offset = when (view.id) {
                R.id.routeResultsContainer, R.id.nearbyPanel, R.id.tabEmptyPanel -> dp(12).toFloat()
                R.id.settingsScrim -> dp(7).toFloat()
                R.id.expandedSearchContent -> -dp(5).toFloat()
                else -> dp(3).toFloat()
            }
            view.alpha = 0f
            view.translationY = offset
            view.scaleX = 0.995f
            view.scaleY = 0.995f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setInterpolator(easeOut)
                .setDuration(235L)
                .start()
        }

        private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    }
}
