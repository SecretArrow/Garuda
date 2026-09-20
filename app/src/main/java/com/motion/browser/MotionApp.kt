package com.motion.browser

import android.app.Application
import com.motion.browser.agent.runtime.MotionAgentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MotionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.attach(this)
        // Auto-resume interrupted tasks on process start (plan Prompt 6C).
        ServiceLocator.resumeUnfinishedTasks()
        val background = runCatching {
            runBlocking(Dispatchers.IO) { ServiceLocator.agentSettings.settings.first().backgroundEnabled }
        }.getOrDefault(true)
        if (background) MotionAgentService.start(this)
    }
}
