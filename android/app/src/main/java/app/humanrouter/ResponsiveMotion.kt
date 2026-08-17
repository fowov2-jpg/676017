package app.humanrouter

import android.animation.AnimatorSet
import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Motion-only companion to ResponsiveProductUi.
 *
 * It deliberately never changes LayoutParams, visibility, hierarchy or text. This keeps touch
 * feedback and container transitions smooth without becoming a second geometry owner.
 */
internal object ResponsiveMotion {
    private val installed = WeakHashMap<MainActivity, Boolean>()
    private val styled = WeakHashMap<View, Boolean>()
    private val easeOut = DecelerateInterpolator(1.35f)

    @Synchronized
    fun install(activity: MainActivity) {
        if (installed.put(activity, true) == true) return

        intArrayOf(
            R.id.searchPanel,
            R.id.expandedSearchContent,
            R.id.suggestionsPanel,
            R.id.routeFiltersPanel,
            R.id.routeResultsPanel,
            R.id.nearbyList,
            R.id.bottomNav
        ).forEach { id ->
            activity.findViewById<ViewGroup?>(id)?.layoutTransition = smoothTransition()
        }

        intArrayOf(
            R.id.routeButton,
            R.id.retryButton,
            R.id.locationPrimaryAction,
            R.id.locationSecondaryAction,
            R.id.routePrimaryAction,
            R.id.checkDataButton
        ).forEach { id -> activity.findViewById<Button?>(id)?.let(::stylePressable) }

        intArrayOf(
            R.id.homeQuickButton,
            R.id.workQuickButton,
            R.id.nearbyQuickButton,
            R.id.clearFromButton,
            R.id.clearToButton,
            R.id.closeSearchButton,
            R.id.closeSettingsButton,
            R.id.mapNavButton,
            R.id.routesNavButton,
            R.id.transportNavButton,
            R.id.favoritesNavButton
        ).forEach { id ->
            activity.findViewById<TextView?>(id)?.apply {
                includeFontPadding = false
                gravity = Gravity.CENTER
                if (this !is EditText) stylePressable(this)
            }
        }

        activity.findViewById<ImageButton?>(R.id.locationButton)?.let(::stylePressable)
        activity.findViewById<ImageButton?>(R.id.settingsButton)?.let(::stylePressable)
    }

    private fun smoothTransition(): LayoutTransition = LayoutTransition().apply {
        setDuration(LayoutTransition.APPEARING, 170L)
        setDuration(LayoutTransition.DISAPPEARING, 120L)
        setDuration(LayoutTransition.CHANGE_APPEARING, 170L)
        setDuration(LayoutTransition.CHANGE_DISAPPEARING, 145L)
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

    private fun stylePressable(view: View) {
        if (view is EditText || styled.put(view, true) == true) return
        val pressed = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 0.985f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.985f),
                ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.94f)
            )
            duration = 75L
            interpolator = easeOut
        }
        val released = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.985f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.985f, 1f),
                ObjectAnimator.ofFloat(view, View.ALPHA, 0.94f, 1f)
            )
            duration = 135L
            interpolator = easeOut
        }
        view.stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), released)
        }
    }
}
