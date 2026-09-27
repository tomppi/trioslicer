package com.tomppi.enderslicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The G-code a profile starts with, in the dialect that profile is for.
 *
 * This app slices for two machines: a printer on the far end of OctoPrint, which is usually
 * Marlin, and the Klipper host inside the app. One default for both was wrong in both
 * directions - Marlin's UBL lines cost three homes and two mesh probes on Klipper, and
 * Klipper's BED_MESH_CALIBRATE is a command a Marlin board does not have.
 */
class BuiltInGcodeTest {
    @Test
    fun theKlipperStartGcodeSpeaksKlipper() {
        assertFalse("UBL is not a Klipper command", BuiltInGcode.START_KLIPPER.contains("G29 L0"))
        assertFalse(BuiltInGcode.START_KLIPPER.contains("G29 A"))
        assertTrue(BuiltInGcode.START_KLIPPER.contains("G28"))
        assertTrue(BuiltInGcode.START_KLIPPER.contains("BED_MESH_CALIBRATE"))
    }

    @Test
    fun theMarlinStartGcodeIsLeftAsMarlin() {
        assertTrue("UBL is what a Marlin printer wants", BuiltInGcode.START_MARLIN.contains("G29 L0"))
        assertTrue(BuiltInGcode.START_MARLIN.contains("G29 A"))
        assertFalse(BuiltInGcode.START_MARLIN.contains("BED_MESH_CALIBRATE"))
    }

    @Test
    fun theEndGcodeDoesNotClaimToSpareZOnKlipper() {
        // Klipper's M84 takes no axis words: it releases every stepper.
        assertFalse(BuiltInGcode.END_KLIPPER.contains("M84 X Y E"))
        assertTrue(BuiltInGcode.END_KLIPPER.contains("M84"))
        // Marlin's does take them, and sparing Z is the point there.
        assertTrue(BuiltInGcode.END_MARLIN.contains("M84 X Y E"))
    }

    @Test
    fun aProfileIsGivenTheDefaultForItsOwnDialect() {
        assertEquals(BuiltInGcode.START_KLIPPER, BuiltInGcode.startGcodeFor("Klipper"))
        assertEquals(BuiltInGcode.START_KLIPPER, BuiltInGcode.startGcodeFor("klipper"))
        assertEquals(BuiltInGcode.START_MARLIN, BuiltInGcode.startGcodeFor("Marlin"))
        assertEquals(BuiltInGcode.START_MARLIN, BuiltInGcode.startGcodeFor("RepRap"))
        assertEquals(BuiltInGcode.START_MARLIN, BuiltInGcode.startGcodeFor(""))
        assertEquals(BuiltInGcode.END_KLIPPER, BuiltInGcode.endGcodeFor("Klipper"))
        assertEquals(BuiltInGcode.END_MARLIN, BuiltInGcode.endGcodeFor("Marlin"))
    }

    @Test
    fun anUntouchedDefaultIsMovedToTheRightDialect() {
        // A Klipper profile that still holds the old Marlin text is what the app itself stored.
        assertEquals(
            BuiltInGcode.START_KLIPPER,
            BuiltInGcode.migrateStart(BuiltInGcode.START_MARLIN, "Klipper"),
        )
        // A Marlin profile keeps its UBL lines: nothing about the Klipper fix may reach it.
        assertEquals(
            BuiltInGcode.START_MARLIN,
            BuiltInGcode.migrateStart(BuiltInGcode.START_MARLIN, "Marlin"),
        )
        // Whitespace from a round trip through a preferences file is not an edit.
        assertEquals(
            BuiltInGcode.START_KLIPPER,
            BuiltInGcode.migrateStart(BuiltInGcode.START_MARLIN + "\n", "Klipper"),
        )
    }

    @Test
    fun anEditedProfileIsLeftAlone() {
        val mine = "G28\nG1 Z5 F3000\n"
        assertEquals(mine, BuiltInGcode.migrateStart(mine, "Klipper"))
        assertEquals(mine, BuiltInGcode.migrateStart(mine, "Marlin"))
        assertEquals("M84\n", BuiltInGcode.migrateEnd("M84\n", "Klipper"))
    }
}
