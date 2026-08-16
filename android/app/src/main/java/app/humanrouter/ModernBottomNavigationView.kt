package app.humanrouter

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Material 3 bottom navigation used while the rest of the legacy View hierarchy is
 * migrated incrementally to Compose.
 *
 * This is a FrameLayout host instead of subclassing ComposeView: ComposeView is final.
 * MainActivity currently adds the navigation-bar inset as root padding. NavigationBar
 * also owns that inset, so the host translates itself down by exactly that inset. This
 * removes the duplicated visual "second floor" above the system gesture/navigation area
 * without hard-coding a system-bar height.
 */
class ModernBottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val composeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        setContent { BottomNavigation() }
    }

    init {
        addView(composeView)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.translationY = navigationBars.bottom.toFloat()
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    @Composable
    private fun BottomNavigation() {
        var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
        val destinations = listOf(
            Destination(R.string.nav_map, R.drawable.ic_map),
            Destination(R.string.nav_routes, R.drawable.ic_routes),
            Destination(R.string.nav_transport, R.drawable.ic_transport),
            Destination(R.string.nav_favorites, R.drawable.ic_star)
        )

        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = Color(0xFF0B57D0),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFD7E3FF),
                onPrimaryContainer = Color(0xFF001B3F),
                surface = Color(0xFFFFFBFF),
                onSurface = Color(0xFF1D1B20),
                onSurfaceVariant = Color(0xFF49454F)
            )
        ) {
            NavigationBar(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                destinations.forEachIndexed { index, destination ->
                    NavigationBarItem(
                        selected = selectedIndex == index,
                        onClick = {
                            selectedIndex = index
                            activateDestination(index)
                        },
                        icon = {
                            Icon(
                                painter = painterResource(destination.iconRes),
                                contentDescription = stringResource(destination.labelRes),
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(destination.labelRes),
                                maxLines = 1
                            )
                        }
                    )
                }
            }
        }
    }

    private fun activateDestination(index: Int) {
        val root = rootView
        val search = root.findViewById<View?>(R.id.searchPanel)
        val results = root.findViewById<LinearLayout?>(R.id.routeResultsPanel)
        val nearby = root.findViewById<View?>(R.id.nearbyPanel)
        val destination = root.findViewById<EditText?>(R.id.toField)

        when (index) {
            0 -> {
                results?.visibility = View.GONE
                nearby?.visibility = View.VISIBLE
                search?.visibility = View.VISIBLE
            }

            1 -> {
                results?.visibility = View.GONE
                nearby?.visibility = View.GONE
                search?.visibility = View.VISIBLE
                destination?.requestFocus()
            }

            2 -> {
                results?.visibility = View.GONE
                search?.visibility = View.VISIBLE
                nearby?.apply {
                    visibility = View.VISIBLE
                    alpha = 0f
                    translationY = dp(20).toFloat()
                    animate().alpha(1f).translationY(0f).setDuration(170).start()
                }
            }

            3 -> showFavorites(results, search, nearby)
        }
    }

    private fun showFavorites(
        results: LinearLayout?,
        search: View?,
        nearby: View?
    ) {
        search?.visibility = View.GONE
        nearby?.visibility = View.GONE
        results ?: return
        results.removeAllViews()
        results.addView(TextView(context).apply {
            text = "Избранное"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF101828.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(4))
        })
        results.addView(TextView(context).apply {
            text = "Сохранённых маршрутов пока нет"
            textSize = 14f
            setTextColor(0xFF667085.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(10))
        })
        results.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Destination(
        val labelRes: Int,
        val iconRes: Int
    )
}
