package app.humanrouter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.humanrouter.routing.LastPlanStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TripResultsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private var transforming = false
    private var routeCardCount = 0
    private val moscowZone = ZoneId.of("Europe/Moscow")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        if (transforming || child == null) return

        when (child) {
            is Button -> if (child.tag != START_BUTTON_TAG && child.tag != STOP_BUTTON_TAG) {
                child.background = ContextCompat.getDrawable(context, R.drawable.bg_chip)
                child.setTextColor(0xFF287BFF.toInt())
                child.setTypeface(child.typeface, Typeface.BOLD)
            }
            is TextView -> styleIncomingText(child)
        }
        if (child.tag != START_BUTTON_TAG && child.tag != STOP_BUTTON_TAG) post { ensureStartButton() }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView !== this) return
        val nearby = rootView?.findViewById<View?>(R.id.nearbyPanel)
        if (visibility == View.VISIBLE) {
            nearby?.visibility = View.GONE
            post { ensureStartButton() }
        } else if (visibility == View.GONE) {
            nearby?.visibility = View.VISIBLE
        }
    }

    private fun styleIncomingText(child: TextView) {
        val raw = child.text?.toString().orEmpty()
        child.setLineSpacing(0f, 1.08f)
        when {
            raw == "Варианты маршрута" -> {
                routeCardCount = 0
                child.setTextColor(0xFF101828.toInt())
                child.textSize = 22f
                child.setTypeface(child.typeface, Typeface.BOLD)
                child.setPadding(2, dp(2), 2, dp(2))
            }
            raw.contains(" мин · до ") && raw.contains('\n') -> post { transformRouteText(child) }
            raw.contains(" → ") -> {
                child.setTextColor(0xFF475467.toInt())
                child.textSize = 14f
                child.setPadding(2, 0, 2, dp(6))
            }
            else -> {
                child.setTextColor(0xFF475467.toInt())
                if (child.textSize / resources.displayMetrics.scaledDensity < 14f) child.textSize = 13f
            }
        }
    }

    private fun transformRouteText(source: TextView) {
        if (source.parent !== this || source.tag == ROUTE_CARD_TAG) return
        if (routeCardCount >= 3) {
            source.visibility = View.GONE
            return
        }
        val raw = source.text?.toString().orEmpty()
        val lines = raw.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return

        val headerParts = lines.first().split(" · ")
        val label = headerParts.getOrNull(0).orEmpty().ifBlank { "Маршрут" }
        val duration = headerParts.firstOrNull { it.contains("мин") }.orEmpty().ifBlank { "—" }
        val arrival = headerParts.firstOrNull { it.startsWith("до ") }
            ?.removePrefix("до ")
            ?.trim()
            .orEmpty()
        val legs = lines.getOrNull(1).orEmpty()
        val details = lines.drop(2).joinToString(" · ")

        val card = LinearLayout(context).apply {
            tag = ROUTE_CARD_TAG
            orientation = VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_input)
            elevation = dp(4).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }

        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(context).apply {
            text = duration
            setTextColor(0xFF101828.toInt())
            textSize = 27f
            setTypeface(typeface, Typeface.BOLD)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        top.addView(TextView(context).apply {
            text = when {
                label.contains("быстр", ignoreCase = true) -> "лучший"
                label.contains("пеш", ignoreCase = true) -> "меньше ходьбы"
                label.contains("пересад", ignoreCase = true) -> "без пересадок"
                else -> label.lowercase()
            }
            setTextColor(0xFF287BFF.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_chip)
            backgroundTintList = ColorStateList.valueOf(0xFFEAF3FF.toInt())
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(9)
        })

        top.addView(TextView(context).apply {
            text = if (arrival.isBlank()) "" else "Прибытие $arrival"
            gravity = Gravity.END
            setTextColor(0xFF475467.toInt())
            textSize = 13f
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(8)
        })
        card.addView(top)

        if (legs.isNotBlank()) {
            card.addView(TextView(context).apply {
                text = decorateLegs(legs)
                setTextColor(0xFF344054.toInt())
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(9), 0, dp(2))
            })
        }
        if (details.isNotBlank()) {
            card.addView(TextView(context).apply {
                text = details.replace("пересадок 0", "без пересадок")
                setTextColor(0xFF667085.toInt())
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
            })
        }

        val index = indexOfChild(source)
        transforming = true
        removeView(source)
        addView(card, index)
        transforming = false
        routeCardCount += 1
    }

    private fun decorateLegs(input: String): String = input
        .replace("пешком", "🚶 пешком", ignoreCase = true)
        .replace("автобус", "▣ автобус", ignoreCase = true)
        .replace("трамвай", "◇ трамвай", ignoreCase = true)
        .replace("метро", "M метро", ignoreCase = true)
        .replace("МЦК", "◯ МЦК", ignoreCase = true)
        .replace("МЦД", "D МЦД", ignoreCase = true)
        .replace("поезд", "▰ поезд", ignoreCase = true)

    private fun ensureStartButton() {
        if (visibility != View.VISIBLE) return
        if (findViewWithTag<View>(START_BUTTON_TAG) != null) return
        if (findViewWithTag<View>(ACTIVE_TRIP_TAG) != null) return
        val seed = LastPlanStore.seed ?: return

        addView(Button(context).apply {
            tag = START_BUTTON_TAG
            text = "Поехали"
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
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
                showActiveTrip(seed.routeId, seed.baselineArrivalEpochSec)
                Toast.makeText(context, "Навигация ВремяХодом запущена", Toast.LENGTH_SHORT).show()
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(52)).apply {
                topMargin = dp(12)
            }
        })
    }

    private fun showActiveTrip(routeId: String, arrivalEpochSec: Long) {
        transforming = true
        removeAllViews()
        routeCardCount = 0

        val panel = LinearLayout(context).apply {
            tag = ACTIVE_TRIP_TAG
            orientation = VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        panel.addView(TextView(context).apply {
            text = "В пути"
            setTextColor(0xFF101828.toInt())
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        panel.addView(TextView(context).apply {
            text = if (routeId.isBlank()) "Активный маршрут" else "Маршрут ${routeId.take(12)}"
            setTextColor(0xFF287BFF.toInt())
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(7), 0, 0)
        })
        panel.addView(TextView(context).apply {
            text = "ВремяХодом следит за движением и пересчитывает путь. Перед важным изменением придёт уведомление."
            setTextColor(0xFF475467.toInt())
            textSize = 14f
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(9), 0, 0)
        })
        if (arrivalEpochSec > 0L) {
            val arrival = Instant.ofEpochSecond(arrivalEpochSec).atZone(moscowZone).format(timeFormatter)
            panel.addView(TextView(context).apply {
                text = "Ориентировочное прибытие  $arrival"
                setTextColor(0xFF101828.toInt())
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                background = ContextCompat.getDrawable(context, R.drawable.bg_chip)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                }
            })
        }
        panel.addView(TextView(context).apply {
            text = "Навигация работает в фоне — экран можно свернуть."
            setTextColor(0xFF667085.toInt())
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        })
        addView(panel)

        addView(Button(context).apply {
            tag = STOP_BUTTON_TAG
            text = "Завершить поездку"
            isAllCaps = false
            setTextColor(0xFF287BFF.toInt())
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            background = ContextCompat.getDrawable(context, R.drawable.bg_chip)
            setOnClickListener {
                context.startService(Intent(context, TripNavigationService::class.java).setAction(TripNavigationService.ACTION_STOP))
                visibility = View.GONE
                rootView?.findViewById<View?>(R.id.nearbyPanel)?.visibility = View.VISIBLE
                rootView?.findViewById<View?>(R.id.searchPanel)?.visibility = View.VISIBLE
                rootView?.findViewById<View?>(R.id.bottomNav)?.visibility = View.VISIBLE
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(50)).apply {
                topMargin = dp(12)
            }
        })
        transforming = false
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val START_BUTTON_TAG = "active_trip_start"
        private const val STOP_BUTTON_TAG = "active_trip_stop"
        private const val ACTIVE_TRIP_TAG = "active_trip_panel"
        private const val ROUTE_CARD_TAG = "route_card"
    }
}
