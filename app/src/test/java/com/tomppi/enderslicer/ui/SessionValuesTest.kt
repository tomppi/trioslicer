package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session's in-place editors: every one writes the *active* engine's own
 * setting key, so the Plate cannot show one engine's numbers and edit another's.
 */
class SessionValuesTest {

    private val printer = PrinterDefinition(
        name = "Modified Ender 3 V2",
        widthMm = 230.0, depthMm = 230.0, heightMm = 250.0,
        buildPlateShape = "rectangular", originAtCenter = false,
        heatedBed = true, heatedBuildVolume = false,
        gcodeFlavor = "Marlin", extruders = 1,
        nozzleSizeMm = 0.4, filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0, printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0, printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
    )

    private val state = MainUiState(
        printer = printer,
        settings = SlicerSettings(
            layerHeightMm = 0.20,
            infillDensityPercent = 10.0,
            infillPattern = "cubic",
            supportsEnabled = true,
            adhesionType = "brim",
        ),
        prusaSettings = PrusaSliceSettings(
            layerHeightMm = 0.15,
            fillDensityPercent = 30.0,
            fillPattern = "gyroid",
            supportMaterial = false,
            supportPattern = "rectilinear",
            skirtLoops = 2,
            brimWidthMm = 0.0,
        ),
        orcaSettings = OrcaSliceSettings(
            layerHeightMm = 0.28,
            sparseInfillDensityPercent = 45.0,
            sparseInfillPattern = "honeycomb",
            supportEnabled = true,
            supportBasePattern = "snug",
            skirtLoops = 3,
            brimWidthMm = 5.0,
        ),
    )

    /** Every key the editors wrote, and what each engine's settings became. */
    private val keys = mutableListOf<String>()
    private var cura: SlicerSettings? = null
    private var prusa: PrusaSliceSettings? = null
    private var orca: OrcaSliceSettings? = null

    private fun values(engine: SlicerEngine) = sessionValues(
        engine = engine,
        summary = plateSessionSummary(engine, state),
        state = state,
        onSettings = { written, change ->
            keys += written
            cura = change(cura ?: state.settings)
        },
        onPrusaSettings = { written, change ->
            keys += written
            prusa = change(prusa ?: state.prusaSettings)
        },
        onOrcaSettings = { written, change ->
            keys += written
            orca = change(orca ?: state.orcaSettings)
        },
    )

    private fun number(value: SessionValue, index: Int = 0) =
        value.editors[index].editor as SessionEditor.Number

    private fun choice(value: SessionValue, index: Int = 0) =
        value.editors[index].editor as SessionEditor.Choice

    @Test
    fun everyEngineEditsItsOwnLayerHeight() {
        number(values(SlicerEngine.CURA).layerHeight).set(0.32)
        assertEquals(SlicerSettings.Keys.LAYER_HEIGHT, keys.last())
        assertEquals(0.32, cura!!.layerHeightMm, 1e-9)

        number(values(SlicerEngine.PRUSA).layerHeight).set(0.12)
        assertEquals(PrusaSliceSettings.Keys.LAYER_HEIGHT, keys.last())
        assertEquals(0.12, prusa!!.layerHeightMm, 1e-9)

        number(values(SlicerEngine.ORCA).layerHeight).set(0.30)
        assertEquals(OrcaSliceSettings.Keys.LAYER_HEIGHT, keys.last())
        assertEquals(0.30, orca!!.layerHeightMm, 1e-9)
    }

    @Test
    fun everyEngineEditsItsOwnInfillDensity() {
        number(values(SlicerEngine.CURA).infill).set(25.0)
        assertEquals(SlicerSettings.Keys.INFILL_DENSITY, keys.last())
        assertEquals(25.0, cura!!.infillDensityPercent, 1e-9)

        number(values(SlicerEngine.PRUSA).infill).set(35.0)
        assertEquals(PrusaSliceSettings.Keys.FILL_DENSITY, keys.last())
        assertEquals(35.0, prusa!!.fillDensityPercent, 1e-9)

        number(values(SlicerEngine.ORCA).infill).set(55.0)
        assertEquals(OrcaSliceSettings.Keys.SPARSE_INFILL_DENSITY, keys.last())
        assertEquals(55.0, orca!!.sparseInfillDensityPercent, 1e-9)
    }

    @Test
    fun theInfillRowCarriesBothTheDensityAndThePattern() {
        val curaValues = values(SlicerEngine.CURA)
        assertEquals(2, curaValues.infill.editors.size)
        assertEquals("Density", curaValues.infill.editors[0].section)
        assertEquals("Pattern", curaValues.infill.editors[1].section)
    }

    @Test
    fun theInfillListOffersTheEnginesOwnPatterns() {
        val curaPattern = choice(values(SlicerEngine.CURA).infill, index = 1)
        // Choosing what is already selected has to leave the pattern alone: that
        // is the index the list marks as current.
        curaPattern.set(curaPattern.selectedIndex)
        assertEquals("cubic", cura!!.infillPattern)
        assertEquals(SlicerSettings.Keys.INFILL_PATTERN, keys.last())
        curaPattern.set(curaPattern.selectedIndex - 1)
        assertNotEquals("cubic", cura!!.infillPattern)

        val orcaPattern = choice(values(SlicerEngine.ORCA).infill, index = 1)
        orcaPattern.set(orcaPattern.selectedIndex)
        assertEquals("honeycomb", orca!!.sparseInfillPattern)

        val prusaPattern = choice(values(SlicerEngine.PRUSA).infill, index = 1)
        prusaPattern.set(prusaPattern.selectedIndex)
        assertEquals("gyroid", prusa!!.fillPattern)
    }

    @Test
    fun curaPicksItsAdhesionByName() {
        val adhesion = choice(values(SlicerEngine.CURA).adhesion)
        adhesion.set(0)
        assertEquals("none", cura!!.adhesionType)
        adhesion.set(3)
        assertEquals("raft", cura!!.adhesionType)
        assertEquals(SlicerSettings.Keys.ADHESION_TYPE, keys.last())
    }

    @Test
    fun prusaAndOrcaPickAdhesionByWidthAndLoops() {
        val prusaAdhesion = choice(values(SlicerEngine.PRUSA).adhesion)
        prusaAdhesion.set(1)
        assertEquals(0.0, prusa!!.brimWidthMm, 1e-9)
        assertEquals(1, prusa!!.skirtLoops)

        prusaAdhesion.set(2)
        assertEquals(5.0, prusa!!.brimWidthMm, 1e-9)
        assertEquals(0, prusa!!.skirtLoops)

        prusaAdhesion.set(0)
        assertEquals(0.0, prusa!!.brimWidthMm, 1e-9)
        assertEquals(0, prusa!!.skirtLoops)
        assertTrue(keys.contains(PrusaSliceSettings.Keys.BRIM_WIDTH))
        assertTrue(keys.contains(PrusaSliceSettings.Keys.SKIRT_LOOPS))

        val orcaAdhesion = choice(values(SlicerEngine.ORCA).adhesion)
        // Orca already has a brim, so choosing one keeps its width.
        orcaAdhesion.set(2)
        assertEquals(5.0, orca!!.brimWidthMm, 1e-9)
        assertEquals(0, orca!!.skirtLoops)
        assertTrue(keys.contains(OrcaSliceSettings.Keys.BRIM_WIDTH))
        assertTrue(keys.contains(OrcaSliceSettings.Keys.SKIRT_LOOPS))
    }

    @Test
    fun theAdhesionSelectionReadsWhatIsAlreadySet() {
        assertEquals(2, adhesionSelection(5.0, 0))
        assertEquals(1, adhesionSelection(0.0, 2))
        assertEquals(0, adhesionSelection(0.0, 0))
        // Both at once shows as a brim: that is what the summary line says.
        assertEquals(2, adhesionSelection(5.0, 3))
        assertEquals(8.0, adhesionBrimWidth(2, 8.0), 1e-9)
        assertEquals(5.0, adhesionBrimWidth(2, 0.0), 1e-9)
        assertEquals(0.0, adhesionBrimWidth(1, 8.0), 1e-9)
        assertEquals(1, adhesionSkirtLoops(1))
        assertEquals(0, adhesionSkirtLoops(2))
    }

    @Test
    fun supportsIsAChoiceThatWritesTheEnginesKey() {
        choice(values(SlicerEngine.CURA).supports).set(0)
        assertEquals(SlicerSettings.Keys.SUPPORTS_ENABLED, keys.last())
        assertEquals(false, cura!!.supportsEnabled)

        choice(values(SlicerEngine.PRUSA).supports).set(1)
        assertEquals(PrusaSliceSettings.Keys.SUPPORT_MATERIAL, keys.last())
        assertEquals(true, prusa!!.supportMaterial)

        choice(values(SlicerEngine.ORCA).supports).set(0)
        assertEquals(OrcaSliceSettings.Keys.SUPPORT_ENABLED, keys.last())
        assertEquals(false, orca!!.supportEnabled)
    }

    @Test
    fun aLayerHeightOutsideTheUsualBandKeepsItsOwnValue() {
        val tall = state.copy(settings = state.settings.copy(layerHeightMm = 1.2))
        val editor = number(
            sessionValues(
                engine = SlicerEngine.CURA,
                summary = plateSessionSummary(SlicerEngine.CURA, tall),
                state = tall,
                onSettings = { _, _ -> },
                onPrusaSettings = { _, _ -> },
                onOrcaSettings = { _, _ -> },
            ).layerHeight,
        )

        assertTrue("1.2 must be inside ${editor.range}", 1.2f <= editor.range.endInclusive)
        assertTrue("0.6 must be inside ${editor.range}", 0.6f <= editor.range.endInclusive)
    }
}
