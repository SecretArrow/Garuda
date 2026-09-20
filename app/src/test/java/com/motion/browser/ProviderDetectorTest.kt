package com.motion.browser

import com.motion.browser.agent.llm.ProviderDetector
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider auto-detection against a real HTTP surface (plan Prompt 5 §3). */
class ProviderDetectorTest {

    @Test
    fun `detects openai-compatible via models endpoint`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"id":"llama-3.3-70b"},{"id":"qwen-2-72b"}]}"""
            )
        )
        server.start()
        val detection = ProviderDetector.detect(server.url("/v1").toString(), "test-key")
        assertEquals("openai", detection.protocol)
        assertEquals(listOf("llama-3.3-70b", "qwen-2-72b"), detection.models)
        assertTrue(detection.latencyMs >= 0)
        server.shutdown()
    }

    @Test
    fun `vision heuristic flags known families`() {
        assertTrue(ProviderDetector.visionCapable("gpt-4o"))
        assertTrue(ProviderDetector.visionCapable("gemini-1.5-pro"))
        assertTrue(ProviderDetector.visionCapable("glm-4v-plus"))
        assertTrue(!ProviderDetector.visionCapable("deepseek-chat"))
    }
}
