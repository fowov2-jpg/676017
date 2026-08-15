package app.humanrouter

import android.content.Context
import app.humanrouter.routing.RoutePreferences

internal object AppPreferences {
    const val NAME = "vremyahodom_settings"
    const val KEY_SHOW_STOPS = "show_stops"
    const val KEY_SHOW_TRANSPORT = "show_transport"
    const val KEY_DARK_THEME = "dark_theme"
    const val KEY_LESS_WALKING = "less_walking"
    const val KEY_AVOID_TRANSFERS = "avoid_transfers"
    const val KEY_HOME = "home"
    const val KEY_WORK = "work"
    const val KEY_SELECTED_TAB = "selected_tab"

    fun isDarkTheme(context: Context): Boolean = prefs(context).getBoolean(KEY_DARK_THEME, false)

    fun routePreferences(context: Context): RoutePreferences {
        val values = prefs(context)
        return RoutePreferences(
            preferLessWalking = values.getBoolean(KEY_LESS_WALKING, false),
            preferFewerTransfers = values.getBoolean(KEY_AVOID_TRANSFERS, false)
        )
    }

    fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
