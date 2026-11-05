package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Every engine's "put it all back" is one tap that cannot be undone, so all four
 * of them ask first.
 *
 * B5 was that exactly one of the four did, which is how a destructive control
 * ends up being the one most likely to be hit by accident.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ResetConfirmationTest {

    @get:Rule
    val compose = createComposeRule()

    private val printer = PrinterDefinition(
        name = "Modified Ender 3 V2",
        widthMm = 220.0, depthMm = 220.0, heightMm = 250.0,
        buildPlateShape = "rectangular", originAtCenter = false,
        heatedBed = true, heatedBuildVolume = false,
        gcodeFlavor = "Marlin", extruders = 1,
        nozzleSizeMm = 0.4, filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0, printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0, printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
    )

    /** A settings set with something to clear, so the Cura buttons are enabled. */
    private val settings = mutableStateOf(
        SlicerSettings(overriddenSettingKeys = setOf(SlicerSettings.Keys.LAYER_HEIGHT)),
    )
    private val prusa = mutableStateOf(PrusaSliceSettings())
    private val orca = mutableStateOf(OrcaSliceSettings())
    private var cleared = 0
    private var written = 0

    private fun state() = MainUiState(printer = printer, settings = settings.value)

    @Test
    fun theCuraSheetAsksBeforeClearingTheOverrides() {
        compose.setContent {
            CategorizedSettingsSheet(
                state = state(),
                onSettings = { _, _ -> written++ },
                onResetOverrides = { cleared++ },
                // The phone's one column: this case is about the reset dialog, and
                // the wide split has its own tests.
                expandedLayout = false,
            )
        }
        compose.onNodeWithText("Reset all app overrides").performScrollTo().performClick()
        compose.onNodeWithText("Reset all app overrides?").assertIsDisplayed()
        assertEquals("nothing happens until it is confirmed", 0, cleared)
        compose.onNodeWithText("Keep settings").performClick()
        assertEquals("and backing out really does back out", 0, cleared)
    }

    @Test
    fun theCuraMachineSheetAsksBeforeClearingTheOverrides() {
        compose.setContent {
            // The sheet is a scrolling column in the app; in a test it needs the
            // scroll container put back for performScrollTo to have anywhere to go.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                MachineSettingsContent(
                    state = state(),
                    onSettings = { _, _ -> written++ },
                    onResetOverrides = { cleared++ },
                )
            }
        }
        compose.onNodeWithText("Reset all app overrides").performScrollTo().performClick()
        compose.onNodeWithText("Reset all app overrides?").assertIsDisplayed()
        assertEquals(0, cleared)
        compose.onNodeWithText("Reset").performClick()
        assertEquals("confirming does it, once", 1, cleared)
    }

    @Test
    fun thePrusaSheetAsksBeforeResetting() {
        compose.setContent {
            PrusaSettingsSheet(
                state = MainUiState(printer = printer, prusaSettings = prusa.value),
                onSettings = { _, change -> written++; prusa.value = change(prusa.value) },
                // The phone's one column: the wide split has its own tests.
                expandedLayout = false,
            )
        }
        compose.onNodeWithText("Reset to engine defaults").performScrollTo().performClick()
        compose.onNodeWithText("Reset to engine defaults?").assertIsDisplayed()
        assertEquals(0, written)
        compose.onNodeWithText("Reset").performClick()
        assertEquals(1, written)
    }

    @Test
    fun theOrcaSheetAsksBeforeResetting() {
        compose.setContent {
            OrcaSettingsSheet(
                state = MainUiState(printer = printer, orcaSettings = orca.value),
                onSettings = { _, change -> written++; orca.value = change(orca.value) },
                expandedLayout = false,
            )
        }
        compose.onNodeWithText("Reset to engine defaults").performScrollTo().performClick()
        compose.onNodeWithText("Reset to engine defaults?").assertIsDisplayed()
        assertEquals(0, written)
        compose.onNodeWithText("Reset").performClick()
        assertEquals(1, written)
    }

    /**
     * The Prusa reset does everything its dialog promises: every one of the settings' 42 fields
     * goes back to the engine's own default - the extrusion widths and the extra keys with
     * them - the preset selection is cleared, and the imported snapshot is discarded.
     */
    @Test
    fun thePrusaResetClearsEveryFieldThePresetsAndTheImport() {
        var presetsCleared = 0
        var importDiscarded = 0
        prusa.value = PrusaSliceSettings(
            layerHeightMm = 0.35,
            firstLayerExtrusionWidthMm = 0.5,
            extraKeys = mapOf("foo_setting" to "3"),
        )
        compose.setContent {
            PrusaSettingsSheet(
                state = MainUiState(
                    printer = printer,
                    settings = settings.value,
                    prusaSettings = prusa.value,
                    prusaSnapshot = EngineSnapshot(
                        "prusa-config.ini",
                        "Configuration snapshot",
                        7,
                        listOf("foo_setting"),
                    ),
                ),
                onSettings = { _, change -> written++; prusa.value = change(prusa.value) },
                onResetPresets = { presetsCleared++ },
                onDiscardSnapshot = { importDiscarded++ },
                expandedLayout = false,
            )
        }
        compose.onNodeWithText("Reset to engine defaults").performScrollTo().performClick()
        compose.onNodeWithText("Reset").performClick()

        assertEquals(
            "every field is the engine's default again",
            PrusaSliceSettings(),
            prusa.value,
        )
        assertEquals("the preset selection goes with it", 1, presetsCleared)
        assertEquals("and the imported snapshot is discarded", 1, importDiscarded)
    }
}
