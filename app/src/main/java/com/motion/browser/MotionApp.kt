package com.motion.browser

import android.app.Application
import com.motion.browser.ServiceLocator

/**
 * Motion Browser application entry point: wires every subsystem exactly once
 * (persistence, secrets, AI providers, audit, notifier, safety guard, browser
 * core, agent runtime, trigger scheduler). See ARCHITECTURE.md §3.1.
 */
class MotionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
