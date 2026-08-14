package app.humanrouter

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class HumanRouterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimeUpdateScheduler.schedule(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) bindQuickDestinations(activity)
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun bindQuickDestinations(activity: MainActivity) {
        val home = activity.findViewById<TextView?>(R.id.homeQuickButton) ?: return
        if (home.tag == QUICK_BIND_TAG) return
        home.tag = QUICK_BIND_TAG

        val work = activity.findViewById<TextView>(R.id.workQuickButton)
        val nearby = activity.findViewById<TextView>(R.id.nearbyQuickButton)
        val destination = activity.findViewById<EditText>(R.id.toField)
        val routeButton = activity.findViewById<Button>(R.id.routeButton)
        val prefs = activity.getSharedPreferences(QUICK_PREFS, MODE_PRIVATE)

        fun configureSavedPlace(view: TextView, key: String, title: String) {
            view.setOnClickListener {
                val value = prefs.getString(key, null)
                if (value.isNullOrBlank()) {
                    Toast.makeText(activity, "Введите адрес, затем удерживайте «$title», чтобы сохранить", Toast.LENGTH_LONG).show()
                    destination.requestFocus()
                    showKeyboard(activity, destination)
                } else {
                    destination.setText(value)
                    destination.setSelection(value.length)
                    routeButton.postDelayed({ routeButton.performClick() }, 120L)
                }
            }
            view.setOnLongClickListener {
                val value = destination.text.toString().trim()
                if (value.length < 3) {
                    Toast.makeText(activity, "Сначала введите адрес для «$title»", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString(key, value).apply()
                    Toast.makeText(activity, "$title сохранён", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }

        configureSavedPlace(home, KEY_HOME, "Дом")
        configureSavedPlace(work, KEY_WORK, "Работа")
        nearby.setOnClickListener {
            destination.text.clear()
            destination.hint = "Что ищем рядом?"
            destination.requestFocus()
            showKeyboard(activity, destination)
        }
    }

    private fun showKeyboard(activity: Activity, field: EditText) {
        field.post {
            (activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    companion object {
        private const val QUICK_BIND_TAG = "vremyahodom_quick_bound"
        private const val QUICK_PREFS = "vremyahodom_quick_places"
        private const val KEY_HOME = "home"
        private const val KEY_WORK = "work"
    }
}
