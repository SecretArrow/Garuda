package com.motion.browser

import android.app.Application

/**
 * Motion Browser application entry point.
 * Agent runtime, database, and provider subsystems are initialized lazily
 * by the ServiceLocator in the agent/ai modules (see ARCHITECTURE.md).
 */
class MotionApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
