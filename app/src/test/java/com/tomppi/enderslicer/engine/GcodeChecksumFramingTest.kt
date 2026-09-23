package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A checksum exists only inside the framed form "N<line> <command> *<checksum>".
 * An asterisk anywhere else is payload - M117 and M118 carry free text - and
 * reading it as framing both truncated the message and made the safety policy
 * reject a line the user is allowed to write.
 */
class GcodeChecksumFramingTest {

    @Test
    fun anAsteriskInFreeTextIsNotFraming() {
        val parsed = requireNotNull(GcodeCommand.parse("M117 Loading * * *"))

        assertEquals("M117", parsed.opcode)
        assertFalse("free text is not framed", parsed.hasChecksum)
        assertFalse(parsed.hasLineNumber)
    }

    @Test
    fun freeTextWithAsterisksPassesTheCustomGcodePolicy() {
        listOf(
            "M117 Loading * * *",
            "M117 50% * 3 walls",
            "M118 note * here",
            "M117 heater * off",
        ).forEach { line ->
            GcodeCommandPolicy.requireSafeCustomEvent(requireNotNull(GcodeCommand.parse(line)))
        }
    }

    @Test
    fun realFramingIsStillDetectedAndStillRejected() {
        val parsed = requireNotNull(GcodeCommand.parse("N7 G1 X10 *64"))

        assertEquals("G1", parsed.opcode)
        assertTrue(parsed.hasLineNumber)
        assertTrue(parsed.hasChecksum)
        assertEquals(10.0, parsed.value('X')!!, 1e-9)
        val failure = runCatching { GcodeCommandPolicy.requireSafeCustomEvent(parsed) }.exceptionOrNull()
        assertTrue("framed G-code stays refused", failure is IllegalArgumentException)
    }

    @Test
    fun anAsteriskOfDigitsWithoutALineNumberIsPayload() {
        val parsed = requireNotNull(GcodeCommand.parse("M117 count *12"))

        assertFalse(parsed.hasChecksum)
        GcodeCommandPolicy.requireSafeCustomEvent(parsed)
    }
}
