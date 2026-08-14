package app.humanrouter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.humanrouter.routing.LastPlanStore

class TripResultsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        if (child?.tag != START_BUTTON_TAG) post { ensureStartButton() }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this && visibility == View.VISIBLE) post { ensureStartButton() }
    }

    private fun ensureStartButton() {
        if (visibility != View.VISIBLE) return
        if (findViewWithTag<View>(START_BUTTON_TAG) != null) return
        val seed = LastPlanStore.seed ?: return

        addView(Button(context).apply {
            tag = START_BUTTON_TAG
            text = "Начать поездку"
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            background = ContextCompat.getDrawable(context, R.drawable.bg_primary)
            setOnClickListener {
                if (!hasLocationPermission()) {
                    Toast.makeText(context, "Для навигации нужен доступ к геопозиции", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val intent = Intent(context, TripNavigationService::class.java).apply {
                    action = TripNavigationService.ACTION_START
                    putExtra(TripNavigationService.EXTRA_DEST_LAT, seed.destination.lat)
                    putExtra(TripNavigationService.EXTRA_DEST_LON, seed.destination.lon)
                    putExtra(TripNavigationService.EXTRA_BASELINE_ARRIVAL, seed.baselineArrivalEpochSec)
                    putExtra(TripNavigationService.EXTRA_ROUTE_ID, seed.routeId)
                }
                ContextCompat.startForegroundService(context, intent)
                Toast.makeText(context, "ВремяХодом ведёт по маршруту и пересчитывает его каждую минуту", Toast.LENGTH_SHORT).show()
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(50)).apply {
                topMargin = dp(10)
            }
        })
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val START_BUTTON_TAG = "active_trip_start"
    }
}
