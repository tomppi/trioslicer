package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.model.ExtraSettingSpec
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.profile.CuraSettingDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sidebar's rows: the user's own list per engine, in the order it was set, with
 * an added row built from that engine's catalogue metadata rather than a
 * hand-written row per setting - and editing the same engine setting the settings
 * screen's own row for it feeds.
 */
class SessionSidebarTest {

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
            infillDensityPercent = 15.0,
            infillPattern = "grid",
            supportsEnabled = true,
            adhesionType = "skirt",
        ),
        extraCuraSettings = mapOf("wall_line_count" to "4"),
    )

    /** The catalogue entries these tests put on the sidebar, shaped like Cura's own. */
    private val wallLineCount = ExtraSettingSpec(
        key = "wall_line_count",
        label = "Wall Line Count",
        defaultValue = "1",
        numeric = true,
        minimum = 0.0,
        maximum = 999.0,
    )
    private val retractionEnable = ExtraSettingSpec(
        key = "retraction_enable",
        label = "Enable Retraction",
        defaultValue = "true",
        boolean = true,
    )
    private val startGcode = ExtraSettingSpec(
        key = "machine_start_gcode",
        label = "Start G-code",
        defaultValue = "G28",
    )

    private val written = mutableListOf<Pair<String, String>>()

    private fun rows(
        chosen: List<String>,
        specs: List<ExtraSettingSpec>,
        from: MainUiState = state,
    ): List<SessionSidebarRow> = sessionSidebarRows(
        engine = SlicerEngine.CURA,
        summary = plateSessionSummary(SlicerEngine.CURA, from),
        state = from,
        chosen = chosen,
        specs = specs,
        values = sidebarEffectiveValues(SlicerEngine.CURA, specs, from),
        onSettings = { _, _ -> },
        onPrusaSettings = { _, _ -> },
        onOrcaSettings = { _, _ -> },
        onExtraSetting = { key, value -> written += key to value },
    )

    @Test
    fun theDefaultSidebarIsStillTheFourItHasAlwaysShown() {
        assertEquals(
            listOf(SIDEBAR_LAYER_HEIGHT, SIDEBAR_INFILL, SIDEBAR_SUPPORTS, SIDEBAR_ADHESION),
            SidebarSettings().forEngine(SlicerEngine.CURA),
        )
        assertEquals(
            listOf("Layer height", "Infill density", "Supports", "Adhesion"),
            rows(DEFAULT_SIDEBAR_SETTINGS, emptyList()).map { it.value.label },
        )
    }

    @Test
    fun theRowsAreTheChosenOnesInTheChosenOrder() {
        val chosen = listOf(SIDEBAR_ADHESION, "wall_line_count", SIDEBAR_LAYER_HEIGHT)
        val shown = rows(chosen, listOf(wallLineCount))

        assertEquals(chosen, shown.map { it.id })
        assertEquals("Wall Line Count", shown[1].value.label)
        assertEquals("4", shown[1].value.value)
        assertEquals("wall_line_count", shown[1].value.detail)
    }

    @Test
    fun aRowTakenOffIsGoneAndTheRestStay() {
        val shown = rows(listOf(SIDEBAR_LAYER_HEIGHT, SIDEBAR_ADHESION), emptyList())

        assertEquals(listOf(SIDEBAR_LAYER_HEIGHT, SIDEBAR_ADHESION), shown.map { it.id })
        assertTrue(SIDEBAR_INFILL !in shown.map { it.id })
        assertTrue(SIDEBAR_SUPPORTS !in shown.map { it.id })
    }

    /**
     * The round trip, in the one place it decides anything: an added row's editor
     * writes the engine key, and that is the key the settings screen's own row for
     * the same setting feeds - so the two name one setting, not two.
     */
    @Test
    fun anAddedRowsEditorWritesTheSameEngineKeyItsSettingsRowFeeds() {
        val row = rows(listOf("wall_line_count"), listOf(wallLineCount)).single()
        val editor = row.value.editors.single().editor as SessionEditor.Number
        assertEquals(4.0, editor.value, 1e-9)

        editor.set(6.0)
        assertEquals(listOf("wall_line_count" to "6"), written)
        assertEquals(
            "the settings screen's row for Wall line count feeds that same key",
            "6",
            CuraSettingDelta.standaloneValues(SlicerSettings(wallLineCount = 6))["wall_line_count"],
        )

        // And a value written that way comes back in the row it was written from.
        val writtenBack = state.copy(extraCuraSettings = mapOf("wall_line_count" to "6"))
        assertEquals("6", rows(listOf("wall_line_count"), listOf(wallLineCount), from = writtenBack).single().value.value)
    }

    @Test
    fun aSwitchRowWritesTheEnginesOwnSpellingOfTrueAndFalse() {
        val row = rows(listOf("retraction_enable"), listOf(retractionEnable)).single()
        assertEquals("On", row.value.value)
        val choice = row.value.editors.single().editor as SessionEditor.Choice
        assertEquals(listOf("Off", "On"), choice.options)
        assertEquals(1, choice.selectedIndex)

        choice.set(0)
        assertEquals(listOf("retraction_enable" to "false"), written)
    }

    @Test
    fun aTextSettingIsRefusedWithASentenceRatherThanDrawnDead() {
        assertNull("a number has an editor", sidebarEditorRefusal(wallLineCount))
        assertNull("a switch has an editor", sidebarEditorRefusal(retractionEnable))
        val refusal = sidebarEditorRefusal(startGcode)
        assertTrue("the picker says why, and it was: $refusal", refusal!!.contains("no sidebar editor"))
        assertTrue(
            "and no row is drawn for it",
            rows(listOf("machine_start_gcode"), listOf(startGcode)).isEmpty(),
        )
    }

    @Test
    fun theSliderCoversTheCataloguesOwnRangeAndAlwaysHoldsItsValue() {
        val declared = sidebarNumberRange(wallLineCount, 5.0)
        assertEquals(0f, declared.start, 0f)
        assertEquals(999f, declared.endInclusive, 0f)

        val undeclared = ExtraSettingSpec(
            key = "skirt_gap",
            label = "Skirt Gap",
            defaultValue = "3",
            numeric = true,
        )
        assertTrue("3.0 must be inside the band", 3.0f in sidebarNumberRange(undeclared, 3.0))

        // A value the catalogue's range excludes keeps its own place on the slider:
        // opening the editor must not drag the setting into the range.
        val outside = sidebarNumberRange(wallLineCount, 1000.0)
        assertTrue("1000.0 must be inside $outside", 1000.0f <= outside.endInclusive)
    }

    @Test
    fun aStoredListRestoresAsTheRowsThatStillMeanSomething() {
        assertEquals(
            listOf(SIDEBAR_INFILL, "wall_line_count"),
            sanitizeSidebarRows(listOf(" @infill ", "Wall_Line_Count", "", "not a key", "@infill")),
        )
    }

    @Test
    fun eachEngineKeepsItsOwnRows() {
        val chosen = SidebarSettings().withRows(SlicerEngine.CURA, listOf(SIDEBAR_LAYER_HEIGHT, "wall_line_count"))

        assertEquals(listOf(SIDEBAR_LAYER_HEIGHT, "wall_line_count"), chosen.forEngine(SlicerEngine.CURA))
        assertEquals(DEFAULT_SIDEBAR_SETTINGS, chosen.forEngine(SlicerEngine.PRUSA))
        assertEquals(DEFAULT_SIDEBAR_SETTINGS, chosen.forEngine(SlicerEngine.ORCA))
    }
}
