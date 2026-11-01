package com.tomppi.enderslicer.scanruler.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRulerFramesTest {

    @Test
    fun aMessageWithoutBytesIsOneTextFrame() {
        val frames = ScanRulerFrames.encode(JSONObject().put("id", 1).put("method", "list"))
        assertEquals(1, frames.size)
        val json = JSONObject((frames[0] as ScanRulerFrames.Frame.Text).text)
        assertEquals(1, json.getInt("id"))
        assertEquals("list", json.getString("method"))
    }

    @Test
    fun announcedBytesAreTheNextBinaryFrameAndComeBack() {
        val bytes = ByteArray(4) { it.toByte() }
        val frames = ScanRulerFrames.encode(
            JSONObject().put("id", 2).put("input", JSONObject().put("bytes", bytes)),
            "input.bytes",
        )
        assertEquals(2, frames.size)
        val head = JSONObject((frames[0] as ScanRulerFrames.Frame.Text).text)
        assertEquals("input.bytes", head.getString("binary"))
        assertEquals(4, head.getJSONObject("input").getJSONObject("bytes").getInt("size"))
        val decoder = ScanRulerFrames.Decoder()
        assertNull(decoder.text(head.toString()))
        val message = decoder.binary(bytes)
        assertArrayEquals(bytes, message.getJSONObject("input").get("bytes") as ByteArray)
    }

    @Test
    fun unsafeBinaryPathsAreRefused() {
        val encode = runCatching {
            ScanRulerFrames.encode(
                JSONObject().put("input", JSONObject().put("bytes", ByteArray(1))),
                "__proto__.bytes",
            )
        }
        assertTrue(encode.isFailure)
        val decode = runCatching {
            ScanRulerFrames.Decoder().text(
                JSONObject().put("binary", "constructor.prototype").put("input", JSONObject()).toString(),
            )
        }
        assertTrue(decode.isFailure)
    }

    @Test
    fun theDecoderRejectsUnannouncedBytes() {
        val raising = runCatching { ScanRulerFrames.Decoder().binary(byteArrayOf(1)) }
        assertTrue(raising.isFailure)
    }
}