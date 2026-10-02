package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bead height of the first moves of a real slice, which is where it went wrong.
 *
 * The fixture is the opening of a Cura slice off the printer this was written for: header, the
 * start script that primes at Z0.3 through a mesh call, then layer 0 at Z0.28. The parser used to
 * derive the bead height from Z rises alone, starting from zero - so the first rise it saw was the
 * prime, the next was downwards, and the height stayed at zero. The bead width comes from
 * deltaE / (length * height), so the preview drew beads with no height at all: the surface showing
 * through between them, which is what a shredded-looking toolpath is.
 *
 * The slicer states the height in the header. These tests hold the parser to it.
 */
class NozzlePathBeadHeightTest {
    private fun opening(): File {
        val stream = javaClass.getResourceAsStream("/nozzlepath/opening.gcode")
            ?: error("fixture /nozzlepath/opening.gcode is missing")
        val file = File.createTempFile("nozzle-path-opening", ".gcode")
        stream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        return file
    }

    @Test
    fun everyExtrudingMoveHasABeadWithAHeight() {
        val path = GcodeNozzlePathParser.parse(opening())
        val moves = path.moves
        var extruding = 0
        for (index in 0 until path.moveCount) {
            val offset = index * GcodeNozzlePath.VALUES_PER_MOVE
            if (moves[offset + GcodeNozzlePath.KIND] != GcodeNozzlePath.Kind.EXTRUSION.code) continue
            val height = moves[offset + GcodeNozzlePath.LAYER_HEIGHT]
            assertTrue(
                "move $index extrudes but its bead height is " + height,
                height >= 0.01f,
            )
            extruding++
        }
        assertTrue("the fixture contains no extruding move", extruding > 0)
    }

    @Test
    fun theFirstBeadUsesTheHeightTheSlicerStated() {
        val path = GcodeNozzlePathParser.parse(opening())
        val moves = path.moves
        for (index in 0 until path.moveCount) {
            val offset = index * GcodeNozzlePath.VALUES_PER_MOVE
            if (moves[offset + GcodeNozzlePath.KIND] != GcodeNozzlePath.Kind.EXTRUSION.code) continue
            assertEquals(0.2f, moves[offset + GcodeNozzlePath.LAYER_HEIGHT], 1e-4f)
            return
        }
        error("the fixture contains no extruding move")
    }
}
