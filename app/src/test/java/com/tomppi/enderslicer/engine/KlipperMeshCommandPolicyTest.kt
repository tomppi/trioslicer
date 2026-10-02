package com.tomppi.enderslicer.engine

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The mesh call a Klipper start script asks for, and what the safety check does with it.
 *
 * BED_MESH_CALIBRATE is not a G or M command, so the numeric parser cannot model it, and every
 * line the parser cannot model is refused unless it is on a short list of Klipper's textual
 * commands. It was not on that list: a Klipper slice whose start script called it - or that this
 * app wrote the call into - failed validation with the file withheld, and the engine's own exit
 * code sitting at 0 in the log.
 */
class KlipperMeshCommandPolicyTest {
    private fun check(line: String, flavor: String) {
        GcodeCommandPolicy.requirePublishedTextSafe(line, flavor, lineNumber = 1)
    }

    @Test
    fun theMeshCallIsAllowedOnAKlipperHost() {
        check("BED_MESH_CALIBRATE", "Klipper")
        check("BED_MESH_CALIBRATE MESH_MIN=10,10 MESH_MAX=200,200", "Klipper")
        check("BED_MESH_CLEAR", "Klipper")
        check("BED_MESH_PROFILE LOAD=default", "Klipper")
    }

    @Test
    fun theCommandsTheEnginesEmitForAKlipperHostAreAllowed() {
        // Both of these came back as "Unsupported textual or malformed command" from real
        // slices: OrcaSlicer's corner shaping, and the object definitions PrusaSlicer writes -
        // which are also exactly what KAMP reads, so refusing them refused adaptive meshing.
        check("SET_VELOCITY_LIMIT ACCEL=500 ACCEL_TO_DECEL=250", "Klipper")
        check("SET_VELOCITY_LIMIT SQUARE_CORNER_VELOCITY=5", "Klipper")
        check(
            "EXCLUDE_OBJECT_DEFINE NAME='model_stl' CENTER=113.391,114.999 " +
                "POLYGON=[[120.814,99.500],[124.039,99.655],[124.841,99.782]]",
            "Klipper",
        )
        check("EXCLUDE_OBJECT_START NAME='model_stl'", "Klipper")
        check("EXCLUDE_OBJECT_END NAME='model_stl'", "Klipper")
    }

    @Test
    fun itIsStillRefusedForAMarlinPrinter() {
        assertThrows(IllegalArgumentException::class.java) { check("BED_MESH_CALIBRATE", "Marlin") }
    }

    @Test
    fun otherTextIsStillRefused() {
        assertThrows(IllegalArgumentException::class.java) { check("BED_MESH_NONSENSE", "Klipper") }
        assertThrows(IllegalArgumentException::class.java) { check("GCODE_MACRO SOMETHING", "Klipper") }
        assertThrows(IllegalArgumentException::class.java) { check("BED_MESH_PROFILE WIPE=all", "Klipper") }
    }
}
