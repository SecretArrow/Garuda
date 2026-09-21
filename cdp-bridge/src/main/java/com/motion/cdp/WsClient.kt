package com.motion.cdp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * RFC 6455 WebSocket frame codec (client side — frames we send are masked,
 * frames we receive are not). Pure JVM logic: unit-testable without Android.
 */
object WsFrameCodec {

    const val OP_CONTINUATION = 0x0
    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    data class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    /** Encodes a client frame with a fresh random mask (RFC 6455 §5.3). */
    fun encodeClientFrame(opcode: Int, payload: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x80 or (opcode and 0x0F)) // FIN + opcode
        val maskBit = 0x80
        when {
            payload.size < 126 -> out.write(maskBit or payload.size)
            payload.size < 65536 -> {
                out.write(maskBit or 126)
                out.write((payload.size shr 8) and 0xFF)
                out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(maskBit or 127)
                val len = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) {
                    out.write(((len shr shift) and 0xFF).toInt())
                }
            }
        }
        val mask = ByteArray(4)
        random.nextBytes(mask)
        out.write(mask)
        for (i in payload.indices) {
            out.write(payload[i].toInt() xor mask[i % 4].toInt())
        }
        return out.toByteArray()
    }

    /**
     * Decodes one frame from [input]. Blocking on the first byte only; the rest
     * must already be available or arrive promptly (DevTools keeps the pipe hot).
     */
    fun decodeFrame(input: InputStream): Frame {
        val b0 = input.read()
        if (b0 < 0) throw IOException("Stream closed while reading frame")
        val b1 = input.read()
        if (b1 < 0) throw IOException("Stream closed while reading frame length")
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            len = ((input.read() and 0xFF).toLong() shl 8) or (input.read() and 0xFF).toLong()
        } else if (len == 127L) {
            len = 0
            for (shift in 56 downTo 0 step 8) {
                len = len or ((input.read() and 0xFF).toLong() shl shift)
            }
        }
        if (len > Int.MAX_VALUE) throw IOException("Frame too large: $len")
        val mask = if (masked) {
            ByteArray(4).also { readFully(input, it) }
        } else null
        val payload = ByteArray(len.toInt())
        readFully(input, payload)
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return Frame(fin, opcode, payload)
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var read = 0
        while (read < target.size) {
            val n = input.read(target, read, target.size - read)
            if (n < 0) throw IOException("Stream closed mid-frame")
            read += n
        }
    }
}

/**
 * WebSocket client that runs over an Android abstract-namespace [android.net.LocalSocket].
 * DevTools' HTTP handler lives on the same socket, so the upgrade request is written
 * directly to the connected socket with Host: localhost (WebView rejects other hosts).
 *
 * Thread model: one reader loop owns [input]; writes are serialized on [sendLock].
 */
class WsClient(
    private val socketName: String,
    private val wsPath: String,
    private val connectTimeoutMs: Int = 8000,
) {
    private val random = SecureRandom()
    private var socket: android.net.LocalSocket? = null
    private var input: InputStream? = null
    /** Bytes read past the handshake header — must be replayed to the frame reader. */
    private var handshakeLeftover: ByteArray = ByteArray(0)
    private val sendLock = Any()
    @Volatile private var closed = false

    /** Opens the socket and performs the RFC 6455 handshake. Throws on failure. */
    fun connect() {
        check(socket == null) { "Already connected" }
        val s = android.net.LocalSocket()
        // NOTE: LocalSocket.connect(endpoint, timeout) throws UnsupportedOperationException
        // (unimplemented in the framework) — always use the 1-arg blocking connect.
        s.connect(android.net.LocalSocketAddress(socketName, android.net.LocalSocketAddress.Namespace.ABSTRACT))
        s.soTimeout = connectTimeoutMs
        val keyB64 = java.util.Base64.getEncoder().encodeToString(ByteArray(16).also { random.nextBytes(it) })
        val request = buildString {
            append("GET ").append(wsPath).append(" HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(keyB64).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }
        s.outputStream.write(request.toByteArray(Charsets.US_ASCII))
        s.outputStream.flush()
        val response = readHandshakeResponse(s.inputStream)
        if (!response.first.contains("101")) {
            throw IOException("WebSocket upgrade failed: ${response.first.lineSequence().firstOrNull()}")
        }
        // Infinite read timeout: CDP connections stay quiet between events.
        // Liveness is enforced by per-command timeouts in CdpConnection.
        s.soTimeout = 0
        handshakeLeftover = response.second
        socket = s
        input = s.inputStream
    }

    /** Sends a text message (all CDP traffic is JSON text frames). */
    fun sendText(text: String) {
        val frame = WsFrameCodec.encodeClientFrame(WsFrameCodec.OP_TEXT, text.toByteArray(Charsets.UTF_8), random)
        synchronized(sendLock) {
            socket?.outputStream?.apply {
                write(frame)
                flush()
            } ?: throw IOException("Socket not connected")
        }
    }

    /**
     * Returns the next TEXT message payload, transparently answering server
     * pings and skipping pong/close frames. Null on clean close.
     */
    fun receiveText(): String? {
        val raw = input ?: throw IOException("Socket not connected")
        // Replay whatever bytes arrived together with the handshake header first.
        val stream: InputStream = if (handshakeLeftover.isNotEmpty()) {
            val leftover = handshakeLeftover
            handshakeLeftover = ByteArray(0)
            java.io.SequenceInputStream(
                java.io.ByteArrayInputStream(leftover),
                raw,
            )
        } else raw
        val fragments = ByteArrayOutputStream()
        while (true) {
            val frame = WsFrameCodec.decodeFrame(stream)
            when (frame.opcode) {
                WsFrameCodec.OP_TEXT, WsFrameCodec.OP_BINARY -> {
                    fragments.write(frame.payload)
                    if (frame.fin) {
                        return fragments.toString(Charsets.UTF_8.name()).also { fragments.reset() }
                    }
                }
                WsFrameCodec.OP_CONTINUATION -> {
                    fragments.write(frame.payload)
                    if (frame.fin) {
                        return fragments.toString(Charsets.UTF_8.name()).also { fragments.reset() }
                    }
                }
                WsFrameCodec.OP_PING -> {
                    val pong = WsFrameCodec.encodeClientFrame(WsFrameCodec.OP_PONG, frame.payload, random)
                    synchronized(sendLock) { socket?.outputStream?.write(pong); socket?.outputStream?.flush() }
                }
                WsFrameCodec.OP_PONG -> Unit
                WsFrameCodec.OP_CLOSE -> {
                    close()
                    return null
                }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching {
            val goodbye = WsFrameCodec.encodeClientFrame(WsFrameCodec.OP_CLOSE, ByteArray(0), random)
            synchronized(sendLock) { socket?.outputStream?.write(goodbye); socket?.outputStream?.flush() }
        }
        runCatching { socket?.close() }
        socket = null
        input = null
    }

    /** Reads the HTTP upgrade response header; returns (header, leftoverBytes). */
    private fun readHandshakeResponse(stream: InputStream): Pair<String, ByteArray> {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(1024)
        var headerEnd = -1
        val deadline = System.currentTimeMillis() + connectTimeoutMs
        while (headerEnd < 0 && System.currentTimeMillis() < deadline) {
            val n = stream.read(chunk)
            if (n > 0) buffer.write(chunk, 0, n)
            val raw = buffer.toByteArray()
            headerEnd = raw.indices.firstOrNull { i ->
                i < raw.size - 3 && raw[i].toInt() == 13 && raw[i + 1].toInt() == 10 &&
                    raw[i + 2].toInt() == 13 && raw[i + 3].toInt() == 10
            } ?: -1
        }
        if (headerEnd < 0) throw IOException("Handshake timeout / no header terminator")
        val header = String(buffer.toByteArray(), 0, headerEnd, Charsets.US_ASCII)
        val leftover = buffer.toByteArray().copyOfRange(headerEnd + 4, buffer.size())
        return header to leftover
    }
}
