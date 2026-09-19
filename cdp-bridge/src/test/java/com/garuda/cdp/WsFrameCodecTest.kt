package com.garuda.cdp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

class WsFrameCodecTest {

    private val random = SecureRandom(byteArrayOf(1, 2, 3, 4))

    @Test
    fun `small text frame roundtrip keeps payload`() {
        val payload = "{\"id\":1,\"method\":\"Page.navigate\"}".toByteArray()
        val encoded = WsFrameCodec.encodeClientFrame(WsFrameCodec.OP_TEXT, payload, random)
        val decoded = WsFrameCodec.decodeFrame(ByteArrayInputStream(encoded))
        assertTrue(decoded.fin)
        assertEquals(WsFrameCodec.OP_TEXT, decoded.opcode)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `client frames are always masked`() {
        val payload = "hello".toByteArray()
        val encoded = WsFrameCodec.encodeClientFrame(WsFrameCodec.OP_TEXT, payload, random)
        // bit 0 of second byte must be set (mask flag) and length must be 5
        assertEquals(0x81, encoded[0].toInt() and 0xFF)
        assertTrue((encoded[1].toInt() and 0x80) != 0)
        assertEquals(5, encoded[1].toInt() and 0x7F)
        // masked payload must differ from raw payload
        assertFalse(payload.contentEquals(encoded.copyOfRange(6, 11)))
    }

    @Test
    fun `16-bit extended length roundtrip`() {
        val payload = ByteArray(1000) { (it % 251).toByte() }
        val encoded = WsFrameCodec.encodeClientFrame(WsFrameCodec.OP_TEXT, payload, random)
        val decoded = WsFrameCodec.decodeFrame(ByteArrayInputStream(encoded))
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `ping frame decodes and payload survives`() {
        val encoded = WsFrameCodec.encodeClientFrame(WsFrameCodec.OP_PING, "hb".toByteArray(), random)
        val decoded = WsFrameCodec.decodeFrame(ByteArrayInputStream(encoded))
        assertEquals(WsFrameCodec.OP_PING, decoded.opcode)
        assertArrayEquals("hb".toByteArray(), decoded.payload)
    }

    @Test
    fun `large payload 70k uses 64-bit length`() {
        val payload = ByteArray(70_000) { (it % 7).toByte() }
        val encoded = WsFrameCodec.encodeClientFrame(WsFrameCodec.OP_TEXT, payload, random)
        val decoded = WsFrameCodec.decodeFrame(ByteArrayInputStream(encoded))
        assertEquals(70_000, decoded.payload.size)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `fragmented stream decodes frames back to back`() {
        val out = ByteArrayOutputStream()
        out.write(WsFrameCodec.encodeClientFrame(WsFrameCodec.OP_TEXT, "one".toByteArray(), random))
        out.write(WsFrameCodec.encodeClientFrame(WsFrameCodec.OP_TEXT, "two".toByteArray(), random))
        val stream = ByteArrayInputStream(out.toByteArray())
        assertEquals("one", String(WsFrameCodec.decodeFrame(stream).payload))
        assertEquals("two", String(WsFrameCodec.decodeFrame(stream).payload))
    }
}
