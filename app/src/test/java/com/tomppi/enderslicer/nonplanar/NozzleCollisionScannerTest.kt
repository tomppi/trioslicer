package com.tomppi.enderslicer.nonplanar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NozzleCollisionScannerTest {
    private fun settings(
        nozzleAngleDegrees: Double = 60.0,
    ) = NonPlanarSettings(
        enabled = true,
        nozzleClearanceHeightMm = 15.0,
        nozzleClearanceAngleDegrees = 45.0,
        nozzleAngleDegrees = nozzleAngleDegrees,
        nozzleProtrusionMm = 5.0,
        heatingBlockWidthMm = 20.0,
        heatingBlockDepthMm = 16.0,
        heatingBlockOffsetXmm = 0.0,
        heatingBlockOffsetYmm = 0.0,
    )

    private fun scanGcode(body: String, nozzleAngleDegrees: Double = 60.0): NozzleCollisionAlert? {
        val directory = kotlin.io.path.createTempDirectory("nozzle-scan").toFile()
        val gcode = File(directory, "output.gcode")
        gcode.writeText(";FLAVOR:Marlin\nG90\nM82\n" + body)
        return try {
            NozzleCollisionScanner.scan(gcode, settings(nozzleAngleDegrees))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun flatPrintHasNoCollisions() {
        assertNull(
            scanGcode(
                ";LAYER:0\n" +
                    "G1 X10 Y10 Z0.2 E1\n" +
                    "G1 X20 Y10 E2\n" +
                    "G1 X20 Y20 E3\n" +
                    "G1 X10 Y20 E4\n" +
                    ";LAYER:1\n" +
                    "G1 X10 Y10 Z0.4 E5\n" +
                    "G1 X20 Y10 E6\n",
            ),
        )
    }

    @Test
    fun nozzleConeViolationIsDetected() {
        // The taper angle is measured from horizontal: a 75-degree cone is
        // thin (slope from vertical = tan 15 = 0.268). A travel at z=0.2 that
        // passes 0.15 mm beside a column 1.0 mm higher pokes into it.
        val alert = scanGcode(
            ";LAYER:0\n" +
                "G1 X10 Y10 Z0.2 E1\n" +
                "G1 X20 Y20 Z1.2 E2\n" +
                "G1 X20 Y10 E3\n" +
                "G1 X10 Y20 E4\n" +
                "G0 X19.85 Y20 Z0.2\n" +
                "G1 X10 Y10 E5\n",
            nozzleAngleDegrees = 75.0,
        )
        assertNotNull(alert)
        assertTrue(alert!!.violatingMoves > 0)
        assertTrue(alert.maximumViolationMm > 0.05)
        assertTrue(alert.offendingLayers.isNotEmpty())
    }

    @Test
    fun thinNozzleConeLeavesNearbyLowerMaterialAlone() {
        // The same 75-degree cone allows only 0.268 mm of overhang per 1 mm
        // of height: a travel 1.5 mm away from a column 1.0 mm higher must
        // NOT warn. The block frustum must not warn either - the surface is
        // below the 5 mm nozzle/block junction. (A 75-degree cone measured
        // from vertical would have been enormous and would have warned here.)
        assertNull(
            scanGcode(
                ";LAYER:0\n" +
                    "G1 X10 Y10 Z0.2 E1\n" +
                    "G1 X20 Y20 Z1.2 E2\n" +
                    "G1 X20 Y10 E3\n" +
                    "G1 X10 Y20 E4\n" +
                    "G0 X18.5 Y20 Z0.2\n" +
                    "G1 X10 Y10 E5\n",
                nozzleAngleDegrees = 75.0,
            ),
        )
    }

    @Test
    fun blockFrustumViolationIsDetected() {
        // Surface 10 mm above the tip at 5 mm horizontal distance: inside
        // the block frustum (half-width 10 mm + 5 mm rise at 45 degrees).
        val alert = scanGcode(
            ";LAYER:0\n" +
                "G1 X10 Y10 Z0.2 E1\n" +
                "G1 X50 Y50 Z10.2 E2\n" +
                "G1 X20 Y10 E3\n" +
                "G1 X10 Y20 E4\n" +
                "G0 X45 Y50 Z0.2\n" +
                "G1 X10 Y10 E5\n",
        )
        assertNotNull(alert)
        assertTrue(alert!!.maximumViolationMm >= 9.9)
    }

    @Test
    fun cutoffAboveHoldingObjectIsDetected() {
        // A column whose top is 25 mm above the low tip: the whole-plate
        // cutoff above the holder (5 mm protrusion + 15 mm cone) must warn.
        val alert = scanGcode(
            ";LAYER:0\n" +
                "G1 X10 Y10 Z0.2 E1\n" +
                "G1 X50 Y50 Z25.2 E2\n" +
                "G1 X20 Y10 E3\n" +
                "G1 X10 Y20 E4\n" +
                "G0 X20 Y20 Z0.2\n" +
                "G1 X10 Y10 E5\n",
        )
        assertNotNull(alert)
        assertTrue(alert!!.cutoffViolatingMoves > 0)
    }

    @Test
    fun midMoveCrossingOfATallColumnIsDetected() {
        // A straight travel from (10,20) to (30,20) at z=0.2 passes directly
        // under a 2.6 mm tall column top at (20,20.3): both endpoints are far
        // outside the thin cone, so only the mid-move sweep can catch it. The
        // travel is the 5th move so the sampling stride checks it.
        val alert = scanGcode(
            ";LAYER:0\n" +
                "G1 X10 Y20 Z0.2 E1\n" +
                "G1 X20 Y20.3 Z2.6 E2\n" +
                "G1 X10 Y10 Z0.2 E3\n" +
                "G1 X10 Y20 E4\n" +
                "G0 X30 Y20 Z0.2\n" +
                "G1 X10 Y10 E5\n",
            nozzleAngleDegrees = 75.0,
        )
        assertNotNull(alert)
        assertTrue(alert!!.violatingMoves > 0)
        assertTrue(alert.maximumViolationMm > 0.3)
    }

    @Test
    fun offsetBlockFrustumRespectsTheMeasuredNozzlePosition() {
        // Block centre at (+8, 0): a surface 8 mm to the right of the tip
        // just above the 5 mm junction is inside the block footprint.
        val alert = scanGcode(
            ";LAYER:0\n" +
                "G1 X10 Y10 Z0.2 E1\n" +
                "G1 X18 Y10 Z5.3 E2\n" +
                "G1 X20 Y10 E3\n" +
                "G1 X10 Y20 E4\n" +
                "G0 X16 Y10 Z0.2\n" +
                "G1 X10 Y10 E5\n",
        ).let { result ->
            requireNotNull(result)
            result
        }
        assertTrue(alert.violatingMoves > 0)
    }
}
