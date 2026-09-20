package com.motion.browser

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Swaps in a test Application that skips the foreground-service pump. */
class HookTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, MotionTestApp::class.java.name, context)
}

class MotionTestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.attach(this)
    }
}
