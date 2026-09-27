package com.tomppi.enderslicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The G-code a profile starts with.
 *
 * The default was Marlin's on a printer that runs Klipper - a mistake with a cost measured in
 * minutes, because G29 is a macro there: the two UBL lines homed and probed the bed twice on
 * top of the G28 above them.
 */
class BuiltInGcodeTest {
    @Test
    fun theStartGcodeSpeaksKlipper() {
        assertFalse("UBL is not a Klipper command", BuiltInGcode.START.contains("G29 L0"))
        assertFalse(BuiltInGcode.START.contains("G29 A"))
        assertTrue(BuiltInGcode.START.contains("G28"))
        assertTrue(BuiltInGcode.START.contains("BED_MESH_CALIBRATE"))
    }

    @Test
    fun theEndGcodeDoesNotClaimToSpareZ() {
        // Klipper's M84 takes no axis words: it releases every stepper.
        assertFalse(BuiltInGcode.END.contains("M84 X Y E"))
        assertTrue(BuiltInGcode.END.contains("M84"))
    }

    @Test
    fun anUntouchedProfileIsMovedToTheNewDefaults() {
        assertEquals(BuiltInGcode.START, BuiltInGcode.migrateStart(BuiltInGcode.LEGACY_START))
        assertEquals(BuiltInGcode.END, BuiltInGcode.migrateEnd(BuiltInGcode.LEGACY_END))
        // Whitespace from a round trip through a preferences file is not an edit.
        assertEquals(BuiltInGcode.START, BuiltInGcode.migrateStart(BuiltInGcode.LEGACY_START + "\n"))
    }

    @Test
    fun anEditedProfileIsLeftAlone() {
        val mine = "G28\nG1 Z5 F3000\n"
        assertEquals(mine, BuiltInGcode.migrateStart(mine))
        val myEnd = "M84\n"
        assertEquals(myEnd, BuiltInGcode.migrateEnd(myEnd))
    }
}
