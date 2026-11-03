package dev.phoneagent.app

import android.app.Application
import dev.phoneagent.app.adb.HelperAutoLaunch

/** Process start: once the built-in adb client is paired, the app keeps its helper running (phase1 §2.2). */
class PhoneAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HelperAutoLaunch.startIfPaired(this)
    }
}
