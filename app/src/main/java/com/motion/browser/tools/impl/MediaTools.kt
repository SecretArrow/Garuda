package com.motion.browser.tools.impl

import android.graphics.Bitmap
import com.motion.browser.ServiceLocator
import com.motion.browser.browser.BrowserController
import com.motion.browser.security.ToolAction
import com.motion.browser.tools.BrowserTool
import com.motion.browser.tools.BaseTool
import com.motion.browser.tools.ToolContext
import com.motion.browser.tools.ToolResult
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Media tools (spec §17).
 *
 * takeScreenshot: real Bitmap capture from the browser, saved as PNG into the app's PRIVATE
 * files directory (filesDir/agent_screenshots). The file is returned by path so the user can
 * inspect it. Motion NEVER auto-uploads screenshots anywhere (§63 privacy).
 */
internal class MediaTools(private val browser: BrowserController) {

    fun all(): List<BrowserTool> = listOf(takeScreenshot)

    private val takeScreenshot = object : BaseTool(
        "takeScreenshot", ToolAction.READ,
        "Capture a PNG screenshot of the active page and save it locally in the app's private " +
            "storage (agent_screenshots/). Returns {\"savedTo\": absolutePath}. The screenshot is " +
            "never uploaded anywhere; it stays on the device for the user to inspect."
    ) {
        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult {
            val bitmap = try {
                browser.takeScreenshot()
            } catch (t: Throwable) {
                return ToolResult.fail("takeScreenshot failed: ${t.message ?: t.javaClass.simpleName}")
            } ?: return ToolResult.fail("Screenshot unavailable — the active page has no renderable content yet")

            return try {
                val dir = File(ServiceLocator.appContext.filesDir, "agent_screenshots")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "motion_shot_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    // PNG is lossless so agent vision passes see the true page.
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                }
                ToolResult.ok(buildJsonObject { put("savedTo", file.absolutePath) })
            } catch (t: Throwable) {
                ToolResult.fail("takeScreenshot could not save the file: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }
}
