package com.tomppi.enderslicer.printer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure path of the USB bridge's start.
 *
 * Pinned here (source level, because claiming a USB device needs the Android USB stack
 * and this is a JVM test): open() and setParameters() run before the fields are
 * assigned, and the catch used to clean up only through stop(), which closes assigned
 * fields - so a start that failed left the port and its USB connection claimed, and no
 * later start could take the interface again until the process restarted. What the
 * attempt opened is closed in the catch itself.
 */
class PrinterBridgeSourceTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/" + relative),
            File("app/src/main/java/com/tomppi/enderslicer/" + relative),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate source file for " + relative)
        return file.readText()
    }

    @Test
    fun aStartThatFailsClosesWhatItOpened() {
        val bridge = source("printer/PrinterBridge.kt")
        val failure = bridge.substringAfter("could not start bridge")
        assertTrue(
            "the port this attempt opened is closed on the failure path",
            failure.contains("runCatching { serial.close() }"),
        )
        assertTrue(
            "and so is the USB connection behind it",
            failure.contains("runCatching { connection.close() }"),
        )
        assertTrue(
            "while stop() still releases whatever reached the fields",
            Regex("(?m)^\\s*stop\\(\\)\\s*$").containsMatchIn(failure),
        )
    }
}
