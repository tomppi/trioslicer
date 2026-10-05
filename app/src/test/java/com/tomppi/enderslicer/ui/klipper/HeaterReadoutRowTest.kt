package com.tomppi.enderslicer.ui.klipper

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.printer.KlipperHeater
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A heater's rows are all there, whatever it is doing at the moment.
 *
 * The target and power rows used to be drawn only while non-zero. A heater holding its
 * temperature therefore added and removed two lines as the PID cycled - roughly once a second on
 * a steady printer - and everything below them moved each time. Nothing was wrong with the
 * numbers; the card simply rewrote itself, which reads as a fault.
 *
 * The test is about presence, not values: the same three rows exist at 0%, at full power, and at
 * every reading between.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HeaterReadoutRowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun bed(power: Double?, target: Double? = 60.0) = KlipperHeater(
        name = "heater_bed",
        label = "Bed",
        temperature = 60.0,
        target = target,
        power = power,
    )

    private fun assertThreeRows() {
        compose.onNodeWithText("Bed temperature").assertExists()
        compose.onNodeWithText("Bed target").assertExists()
        compose.onNodeWithText("Bed heater power").assertExists()
    }

    @Test
    fun theRowsSurviveThePidCycling() {
        val power = mutableStateOf(0.0)
        compose.setContent { HeaterReadoutRow(bed(power.value)) }
        assertThreeRows()
        for (reading in listOf(0.0, 0.42, 1.0, 0.0, 0.98, 0.0)) {
            power.value = reading
            compose.waitForIdle()
            assertThreeRows()
        }
    }

    @Test
    fun aHeaterThatHasNotReportedStillShowsTheRows() {
        compose.setContent { HeaterReadoutRow(bed(power = null, target = null)) }
        assertThreeRows()
    }

    @Test
    fun aSensorShowsOnlyItsTemperature() {
        compose.setContent {
            HeaterReadoutRow(
                KlipperHeater(
                    name = "temperature_sensor chamber",
                    label = "Chamber",
                    temperature = 31.5,
                    target = null,
                ),
            )
        }
        compose.onNodeWithText("Chamber temperature").assertExists()
        compose.onNodeWithText("Chamber target").assertDoesNotExist()
        compose.onNodeWithText("Chamber heater power").assertDoesNotExist()
    }
}
