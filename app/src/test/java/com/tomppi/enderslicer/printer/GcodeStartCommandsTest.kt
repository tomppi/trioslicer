package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a sliced file will do to a Klipper printer that its slicer did not intend.
 *
 * The case this was written for: a file whose header says `;FLAVOR:Marlin` and whose start block
 * is Marlin's UBL - `G28`, `G29 L0`, `G29 A`. On a Klipper host `G29` is the printer's own macro,
 * so the print homed twice, meshed twice and probed the bed twice before its first layer. An
 * earlier version read the flavour line instead and warned about Klipper-sliced files, which carry
 * the same line for a different reason.
 */
class GcodeStartCommandsTest {
    private fun start(vararg lines: String): String? = marlinOnlyStartCommand(lines.asSequence())

    @Test
    fun marlinsUblStartBlockIsFound() {
        val command = start(
            ";FLAVOR:Marlin",
            ";Generated with Cura_SteamEngine",
            "M140 S60",
            "G28 ; Home all axes",
            "G29 L0 ; load a valid mesh from slot 0",
            "G29 A  ; active the UBL system",
        )
        assertEquals("G29 L0", command)
    }

    @Test
    fun theKlipperStartScriptIsNotFlaggedEvenThoughItsHeaderSaysMarlin() {
        // Which is the whole point: Klipper reads Marlin G-code, so the header says Marlin
        // whichever firmware the file is for.
        assertNull(
            start(
                ";FLAVOR:Marlin",
                ";Generated with Cura_SteamEngine",
                "G28 ; home all axes",
                "BED_MESH_CALIBRATE ; probe the bed for this print",
                "G1 Z2.0 F3000",
            ),
        )
    }

    @Test
    fun aCommentMentioningG29IsNotACommand() {
        assertNull(start("; G29 is not used here", "G28", "BED_MESH_CALIBRATE"))
    }

    @Test
    fun theOtherMarlinOnlyCommandsAreFoundToo() {
        assertEquals("M420 S1", start("G28", "M420 S1 ; use the saved mesh"))
        // The whole command, not just its name: the message on screen names the line.
        assertEquals("G26 P50", start("G28", "G26 P50 ; test pattern"))
    }
}
