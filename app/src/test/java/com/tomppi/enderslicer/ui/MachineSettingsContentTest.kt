package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The machine sheet's settings writes: which keys reach the view model, which is
 * what decides whether an edit survives the next launch.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MachineSettingsContentTest {

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

    // AML on, so the section that holds the button is rendered: the button is the
    // subject, and toggling the switch first would add its own key to the list.
    private val settings = mutableStateOf(SlicerSettings(adaptiveMeshLevelingEnabled = true))
    private val registered = mutableListOf<String>()

    private fun show() {
        compose.setContent {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                MachineSettingsContent(
                    state = MainUiState(printer = printer, settings = settings.value),
                    onSettings = { key, change ->
                        registered += key
                        settings.value = change(settings.value)
                    },
                    onResetOverrides = {},
                )
            }
        }
    }

    /**
     * The recommended AML button fills the script in *and* turns the custom-start
     * switch on. Only the key a settings call is made with is registered as an
     * override, and a restored state re-applies the overridden keys alone - so
     * turning the switch on inside the script's call left it unregistered: the AML
     * script stayed stored but stopped being used after the next launch.
     */
    @Test
    fun theRecommendedAmlButtonRegistersTheSwitchItTurnsOn() {
        show()
        compose.onNodeWithText("Use the official AML start G-code (recommended)")
            .performScrollTo()
            .performClick()

        assertEquals(
            listOf(
                SlicerSettings.Keys.CUSTOM_START_GCODE_ENABLED,
                SlicerSettings.Keys.CUSTOM_START_GCODE,
            ),
            registered,
        )
        assertTrue("the switch is on in the state", settings.value.customStartGcodeEnabled)
        assertTrue(
            "and the script it was turned on for is the AML one",
            settings.value.customStartGcode.contains("G29"),
        )
    }
}
