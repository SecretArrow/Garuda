package com.motion.cdp

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Discovers the DevTools abstract unix socket this process can self-connect to.
 *
 * Supported endpoints (plan §1 pillar 2):
 *  - "motion-devtools"                — the Brave/Chromium fork patch (Phase 1).
 *  - "webview_devtools_remote_<pid>"  — standard Android WebView debugging socket
 *    used by the agent-layer prototype app (plan "Catatan Penting").
 *
 * Discovery is tolerant: exact-name candidates first, then a scan of every
 * visible abstract socket containing "devtools" (covers WebView providers that
 * host the devtools server in a different process).
 */
object DevToolsLocator {

    const val FORK_SOCKET_NAME = "motion-devtools"
    private const val PROBE_TIMEOUT_MS = 2500

    /** Candidate names in priority order for this process. */
    fun candidateNames(): List<String> {
        val pid = android.os.Process.myPid()
        return listOf(
            FORK_SOCKET_NAME,
            "webview_devtools_remote_$pid",
            "chrome_devtools_remote_$pid",
        )
    }

    /** Names visible in /proc/net/unix (abstract sockets are prefixed with '@'). */
    fun visibleAbstractSockets(): List<String> = runCatching {
        File("/proc/net/unix").readLines().mapNotNull { line ->
            val last = line.substringAfterLast(' ', "")
            if (last.startsWith("@")) last.substring(1) else null
        }
    }.getOrDefault(emptyList())

    /** All names worth probing: exact candidates + any visible devtools socket. */
    fun allCandidates(): List<String> {
        val visible = visibleAbstractSockets()
        val scanned = visible.filter { it.contains("devtools") }
        return (candidateNames() + scanned).distinct()
    }

    /**
     * Probes candidates by opening each socket and issuing an HTTP health check.
     * Returns the first name that answers, or null when none is live.
     */
    fun findLiveSocket(): String? {
        for (name in allCandidates()) {
            val ok = runCatching {
                val body = LocalSocketHttp(name).get("/json/version", timeoutMs = PROBE_TIMEOUT_MS)
                body.contains("Browser") || body.contains("webSocketDebuggerUrl")
            }.getOrDefault(false)
            if (ok) return name
        }
        return null
    }

    /** Diagnostic dump used by tests/CI to explain discovery failures. */
    fun diagnostics(): String = buildString {
        append("visible=")
        append(visibleAbstractSockets().filter { it.contains("devtools") })
        append(" candidates=")
        append(allCandidates())
    }
}

/**
 * Minimal HTTP/1.1 GET client over an abstract-namespace [LocalSocket].
 * Used for the DevTools discovery endpoints (/json/version, /json/list).
 */
class LocalSocketHttp(private val socketName: String) {

    /** GET [path]; returns the response body as a string. */
    fun get(path: String, timeoutMs: Int = 8000): String {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = timeoutMs
            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: localhost\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            socket.outputStream.flush()

            val raw = readResponse(socket.inputStream, timeoutMs)
            val headerEnd = indexOfHeaderEnd(raw)
                ?: throw IOException("No HTTP header terminator from $socketName (got ${raw.size} bytes)")
            val headerText = String(raw, 0, headerEnd, Charsets.US_ASCII)
            val statusLine = headerText.lineSequence().firstOrNull().orEmpty()
            if (!statusLine.contains(" 200")) throw IOException("DevTools HTTP $statusLine for $path")
            val bodyStart = headerEnd + 4
            val contentLength = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(headerText)?.groupValues?.last()?.toIntOrNull()
            return if (contentLength != null && raw.size - bodyStart >= contentLength) {
                String(raw, bodyStart, contentLength, Charsets.UTF_8)
            } else {
                String(raw, bodyStart, raw.size - bodyStart, Charsets.UTF_8)
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Reads until the connection closes or goes quiet past [timeoutMs]. */
    private fun readResponse(input: InputStream, timeoutMs: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        val deadline = System.currentTimeMillis() + timeoutMs
        var quietRounds = 0
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val n = input.read(chunk)
                if (n > 0) {
                    buffer.write(chunk, 0, n)
                    quietRounds = 0
                    continue
                }
            }
            quietRounds++
            if (quietRounds >= 4) break
            Thread.sleep(15)
        }
        return buffer.toByteArray()
    }

    private fun indexOfHeaderEnd(raw: ByteArray): Int? {
        for (i in 0 until raw.size - 3) {
            if (raw[i].toInt() == 13 && raw[i + 1].toInt() == 10 &&
                raw[i + 2].toInt() == 13 && raw[i + 3].toInt() == 10) return i
        }
        return null
    }
}
