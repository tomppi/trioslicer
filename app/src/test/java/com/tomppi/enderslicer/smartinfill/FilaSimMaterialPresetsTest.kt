package com.tomppi.enderslicer.smartinfill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preset table is what the panel fills the material fields from, so it has
 * to be the upstream library's values, and a preset must not disturb the
 * settings that are not material.
 */
class FilaSimMaterialPresetsTest {

    private fun preset(name: String) =
        FilaSimMaterialPresets.ALL.first { it.name == name }

    @Test
    fun theTableMatchesTheUpstreamMaterialLibrary() {
        val pla = preset("PLA")
        assertEquals(3500.0, pla.youngsModulusMpa, 1e-9)
        assertEquals(0.35, pla.poisson, 1e-9)
        assertEquals(1.24, pla.densityGramsPerCm3, 1e-9)
        assertEquals(50.0, pla.strengthMpa, 1e-9)
        assertEquals(35.0, pla.layerStrengthMpa, 1e-9)
        assertFalse(pla.isotropic)

        val abs = preset("ABS")
        assertEquals(2250.0, abs.youngsModulusMpa, 1e-9)
        assertEquals(38.0, abs.strengthMpa, 1e-9)
        assertEquals(25.0, abs.layerStrengthMpa, 1e-9)

        val steel = preset("Steel S235")
        assertEquals(210_000.0, steel.youngsModulusMpa, 1e-9)
        assertEquals(7.85, steel.densityGramsPerCm3, 1e-9)
        assertTrue("a metal has no build direction", steel.isotropic)
    }

    @Test
    fun anIsotropicMaterialHasNoLayerCriterion() {
        val configuration = preset("Aluminium 6061-T6").applyTo(FilaSimConfiguration())
        assertEquals(276.0, configuration.strengthMpa ?: 0.0, 1e-9)
        assertEquals("isotropic yield both ways", 276.0, configuration.layerStrengthMpa ?: 0.0, 1e-9)
        assertEquals(false, configuration.layerShearOn)
        // The engine derives the shear allowable (0.6 x layer strength) when it
        // is left open, which is what the upstream library carries.
        assertEquals(null, configuration.shearStrengthMpa)
    }

    @Test
    fun applyingAMaterialKeepsEverythingThatIsNotMaterial() {
        val before = FilaSimConfiguration(
            targetCells = 150_000,
            tolerance = 1e-4,
            accelerationMmPerS2 = listOf(0.0, 0.0, -9806.65),
        )
        val after = preset("PETG").applyTo(before)
        assertEquals(150_000, after.targetCells)
        assertEquals(1e-4, after.tolerance ?: 0.0, 1e-12)
        assertEquals(listOf(0.0, 0.0, -9806.65), after.accelerationMmPerS2)
        assertEquals(2100.0, after.youngsModulusMpa ?: 0.0, 1e-9)
        assertEquals(34.0, after.layerStrengthMpa ?: 0.0, 1e-9)
        assertEquals(true, after.layerShearOn)
        assertTrue("and the panel can highlight the active preset", preset("PETG").matches(after))
        assertFalse(preset("PLA").matches(after))
    }
}
