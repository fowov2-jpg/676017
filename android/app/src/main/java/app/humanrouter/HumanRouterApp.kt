package app.humanrouter

import android.app.Application

class HumanRouterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimeUpdateScheduler.schedule(this)
    }
}
