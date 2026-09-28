package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file's own limits, as klippy reads them.
 *
 * The rule that matters is klippy's: M204 is honoured as max_accel = min(P, T), and M201, M203
 * and M205 are Marlin's commands that klippy does not have at all. A file is free to carry them
 * - every slicer writes them - and the app has to know that they mean nothing here, or it will
 * report a limit that is not being applied.
 */
class KlipperFileLimitsTest {
    private fun limits(vararg lines: String) = KlipperFileLimits.fromGcode(lines.asSequence())

    @Test
    fun theAccelerationIsTheLowerOfPrintAndTravel() {
        // What OrcaSlicer writes at the head of a Creality file, and what the printer obeys.
        val head = limits("M204 P500 R1000 T500")
        assertEquals(500.0, head.askedAccel!!, 1e-9)
        assertEquals(500.0, head.askedTravelAccel!!, 1e-9)
        // An S sets both.
        val cura = limits("M204 S800")
        assertEquals(800.0, cura.askedAccel!!, 1e-9)
        assertEquals(800.0, cura.askedTravelAccel!!, 1e-9)
        // And when the two differ, the print acceleration is the lower one.
        assertEquals(1200.0, limits("M204 P1200 T3000").askedAccel!!, 1e-9)
    }

    @Test
    fun aFileWithNoAccelerationLineAsksForNothing() {
        assertNull(limits("G28", "G1 X10 Y10 F3000").askedAccel)
    }

    @Test
    fun marlinsLimitLinesAreNamedRatherThanBelieved() {
        val head = limits(
            "M201 X500 Y500 Z500 E5000",
            "M203 X500 Y500 Z10 E60",
            "M204 P500 R1000 T500",
            "M205 X8.00 Y8.00 Z0.40 E5.00",
        )
        assertEquals(listOf("M201", "M203", "M205"), head.ignoredMarlinLimits)
        // The one line klippy does read is the one it reports.
        assertEquals(500.0, head.askedAccel!!, 1e-9)
    }

    @Test
    fun commentsAndCaseDoNotConfuseIt() {
        val head = limits("m204 p250 t250 ; the profile's machine limit", "")
        assertEquals(250.0, head.askedAccel!!, 1e-9)
    }

    @Test
    fun theTwoFiguresAreComparedRatherThanReportedSideBySide() {
        val slower = limits("M204 P500 T500").copy(allowedAccel = 5000.0)
        assertTrue("the file is the tighter of the two", slower.accelerationIsTheFilesOwn)
        assertFalse(slower.accelerationIsClampedByThePrinter)
        val faster = limits("M204 P9000 T9000").copy(allowedAccel = 5000.0)
        assertTrue(faster.accelerationIsClampedByThePrinter)
        assertFalse(faster.accelerationIsTheFilesOwn)
        // Equal is neither: nothing to warn about.
        val same = limits("M204 P5000 T5000").copy(allowedAccel = 5000.0)
        assertFalse(same.accelerationIsTheFilesOwn)
        assertFalse(same.accelerationIsClampedByThePrinter)
        // And an unknown printer figure is not a difference.
        assertFalse(limits("M204 P500 T500").accelerationIsTheFilesOwn)
    }
}
