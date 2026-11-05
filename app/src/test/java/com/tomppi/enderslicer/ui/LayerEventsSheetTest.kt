package com.tomppi.enderslicer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.engine.GcodeLayerPreview
import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The layer-events sheet's input gate and its disabled-button reason.
 *
 * The gate is where non-finite input has to die: "NaN", "Infinity" and "1e400"
 * all parse as doubles, and the event constructor's require(isFinite) then threw
 * straight out of the Add click handler. Finite-but-wild values are refused later
 * by the validator with a named error; only these spellings crashed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LayerEventsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun layer() = GcodeLayerPreview.Layer(
        number = 3,
        z = 0.6f,
        height = 0.2f,
        segments = FloatArray(0),
        supportSegmentCount = 0,
        supportInterfaceSegmentCount = 0,
    )

    @Test
    fun nonFiniteValuesNeverGetPastTheInputGate() {
        assertNull("NaN".toNumber())
        assertNull("Infinity".toNumber())
        assertNull("-Infinity".toNumber())
        assertNull("1e400".toNumber())
        assertNull("".toNumber())
        assertEquals(210.0, "210".toNumber()!!, 1e-9)
        assertEquals(210.5, "210,5".toNumber()!!, 1e-9)
    }

    @Test
    fun theGateAsksForWhatEachEventNeeds() {
        assertTrue(inputReady(LayerEventType.NOZZLE_TEMPERATURE, "210", "", ""))
        assertFalse(
            "NaN is not a nozzle temperature",
            inputReady(LayerEventType.NOZZLE_TEMPERATURE, "NaN", "", ""),
        )
        assertFalse(inputReady(LayerEventType.NOZZLE_TEMPERATURE, "1e400", "", ""))
        assertFalse("a retraction needs both numbers", inputReady(LayerEventType.RETRACTION, "2", "NaN", ""))
        assertTrue(inputReady(LayerEventType.RETRACTION, "2", "40", ""))
        assertFalse("a message needs text", inputReady(LayerEventType.MESSAGE, "", "", " "))
        assertTrue(inputReady(LayerEventType.MESSAGE, "", "", "Bed at 60"))
        assertTrue("a pause carries no value", inputReady(LayerEventType.PAUSE, "", "", ""))
    }

    @Test
    fun theAddReasonNamesWhatIsMissing() {
        assertEquals(
            "Nozzle temperature (°C) must be a finite number.",
            inputMissingReason(LayerEventType.NOZZLE_TEMPERATURE, "NaN", ""),
        )
        assertEquals(
            "Retraction distance must be a finite number.",
            inputMissingReason(LayerEventType.RETRACTION, "NaN", "40"),
        )
        assertEquals(
            "Retraction speed must be a finite number.",
            inputMissingReason(LayerEventType.RETRACTION, "2", "NaN"),
        )
        assertEquals(
            "Type the printer display message first.",
            inputMissingReason(LayerEventType.MESSAGE, "", ""),
        )
        assertEquals(
            "Type the custom G-code first.",
            inputMissingReason(LayerEventType.CUSTOM_GCODE, "", ""),
        )
    }

    @Test
    fun aDisabledAddSaysWhyInsteadOfBeingADeadButton() {
        var adds = 0
        compose.setContent {
            LayerEventsSheet(
                layer = layer(),
                events = emptyList(),
                settings = SlicerSettings(),
                isBusy = true,
                onAdd = { _, _, _, _ -> adds++ },
                onRemove = {},
                onClearUserEvents = {},
            )
        }

        compose.onNodeWithText("Add to this layer").assertIsNotEnabled()
        compose.onNodeWithText("Wait for the running operation, then add the event.").assertIsDisplayed()
        assertEquals(0, adds)
    }

    @Test
    fun aFiniteEntryMakesTheAddButtonUsableAgain() {
        compose.setContent {
            LayerEventsSheet(
                layer = layer(),
                events = emptyList(),
                settings = SlicerSettings(),
                isBusy = false,
                onAdd = { _, _, _, _ -> },
                onRemove = {},
                onClearUserEvents = {},
            )
        }

        // The pause default needs no value, so the gate is open and says nothing.
        compose.onNodeWithText("Add to this layer").assertIsEnabled()
        compose.onNodeWithText("Wait for the running operation, then add the event.").assertDoesNotExist()
    }
}
