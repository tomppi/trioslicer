package com.tomppi.enderslicer.conical

import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.profile.CuraSettingDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConicalPreparationsTest {
    @Test
    fun adjustSettingsDisablesAdhesion() {
        val adjusted = ConicalPreparations.adjustSettings(SlicerSettings(adhesionType = "skirt"))
        assertEquals("none", adjusted.adhesionType)
    }

    @Test
    fun adjustSettingsEmitsAdhesionOverrideForResolvedSlices() {
        val adjusted = ConicalPreparations.adjustSettings(SlicerSettings(adhesionType = "skirt"))
        assertEquals("none", CuraSettingDelta.explicitValues(adjusted)["adhesion_type"])
    }

    @Test
    fun adjustSettingsStripsCustomStartGcodePrimeLines() {
        val adjusted = ConicalPreparations.adjustSettings(
            SlicerSettings(
                customStartGcodeEnabled = true,
                customStartGcode = """
                    G28
                    G1 X0.1 Y200 Z0.3 F1500 E15 ; prime line
                    G92 E0
                """.trimIndent(),
            ),
        )
        assertFalse("prime line" in adjusted.customStartGcode)
        assertTrue("G28" in adjusted.customStartGcode)
        assertTrue("G92 E0" in adjusted.customStartGcode)
    }

    @Test
    fun stripPrimeLinesRemovesOnlyExtrusionMoves() {
        val start = """
            ; Ender 3 Custom Start G-code
            G92 E0 ; Reset Extruder
            G28 ; Home all axes
            G1 Z2.0 F3000 ; Move Z Axis up
            G1 X0.1 Y20 Z0.3 F5000.0 ; Move to start position
            G1 X0.1 Y200.0 Z0.3 F1500.0 E15 ; Draw the first line
            G1 X0.4 Y200.0 Z0.3 F5000.0 ; Move to side a little
            G1 X0.4 Y20 Z0.3 F1500.0 E30 ; Draw the second line
            G92 E0 ; Reset Extruder
            M82 ;absolute extrusion mode
        """.trimIndent()
        val stripped = ConicalPreparations.stripPrimeLines(start)

        assertFalse("Prime line must be removed", "Draw the first line" in stripped)
        assertFalse("Second prime line must be removed", "Draw the second line" in stripped)
        assertTrue("Homing must stay", "G28" in stripped)
        assertTrue("Reset extruder must stay", "G92 E0" in stripped)
        assertTrue("Move to start position must stay", "Move to start position" in stripped)
        assertTrue("Absolute extrusion mode must stay", "M82" in stripped)
    }
}
