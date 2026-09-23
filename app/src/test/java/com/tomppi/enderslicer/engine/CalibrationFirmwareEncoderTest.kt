package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationFirmwareEncoderTest {
    @Test
    fun pressureAdvanceUsesTheDeclaredFirmwareDialect() {
        assertEquals(
            listOf("M900 K0.12"),
            commands("Marlin", LayerEventType.PRESSURE_ADVANCE, 0.12),
        )
        assertEquals(
            listOf("SET_PRESSURE_ADVANCE ADVANCE=0.12"),
            commands("Klipper", LayerEventType.PRESSURE_ADVANCE, 0.12),
        )
        assertEquals(
            listOf("M572 D0 S0.12"),
            commands("RepRapFirmware", LayerEventType.PRESSURE_ADVANCE, 0.12),
        )
    }

    @Test
    fun firmwareRetractionUsesTheDeclaredFirmwareDialect() {
        assertEquals(
            listOf("M207 S1.2 F2100"),
            commands("RepRap (Marlin/Sprinter)", LayerEventType.RETRACTION, 1.2, 35.0),
        )
        assertEquals(
            listOf("SET_RETRACTION RETRACT_LENGTH=1.2 RETRACT_SPEED=35"),
            commands("Klipper", LayerEventType.RETRACTION, 1.2, 35.0),
        )
        assertEquals(
            listOf("M207 S1.2 F2100"),
            commands("Duet RepRapFirmware", LayerEventType.RETRACTION, 1.2, 35.0),
        )
    }

    @Test
    fun junctionDeviationRejectsUnverifiedDialects() {
        listOf("Klipper", "RepRapFirmware", "Unknown firmware").forEach { flavor ->
            val error = runCatching {
                commands(flavor, LayerEventType.JUNCTION_DEVIATION, 0.02)
            }.exceptionOrNull()
            assertTrue(error is CalibrationFirmwareEncoder.UnsupportedFirmwareCommand)
        }
        assertEquals(
            listOf("M205 J0.02"),
            commands("Marlin", LayerEventType.JUNCTION_DEVIATION, 0.02),
        )
    }

    @Test
    fun commonCommandsRemainAvailableForGenericFirmware() {
        val firmware = CalibrationFirmwareEncoder.fromFlavor("Custom")
        assertEquals(
            listOf("M109 S210"),
            firmware.commands(LayerEventType.NOZZLE_TEMPERATURE, 0, 210.0),
        )
        assertEquals(
            listOf("M106 S128"),
            firmware.commands(LayerEventType.FAN_SPEED, 0, 50.0),
        )
        assertEquals("M104 S0", firmware.hotendOffCommand())
    }

    @Test
    fun marlinNozzleTemperatureWaitsForCooldownOnDescendingTowers() {
        // Marlin M109 S waits only while heating; R also waits for cooldown (descending towers).
        assertEquals(
            listOf("M109 R210"),
            commands("Marlin", LayerEventType.NOZZLE_TEMPERATURE, 210.0),
        )
    }

    @Test
    fun nonMarlinNozzleTemperatureKeepsWaitForHeatingForm() {
        // Klipper/RRF M109 already wait for the target in both directions.
        assertEquals(
            listOf("M109 S210"),
            commands("Klipper", LayerEventType.NOZZLE_TEMPERATURE, 210.0),
        )
        assertEquals(
            listOf("M109 S210"),
            commands("RepRapFirmware", LayerEventType.NOZZLE_TEMPERATURE, 210.0),
        )
    }

    private fun commands(
        flavor: String,
        type: LayerEventType,
        value: Double,
        secondary: Double? = null,
    ): List<String> = CalibrationFirmwareEncoder.fromFlavor(flavor).commands(
        type = type,
        layerNumber = 1,
        value = value,
        secondaryValue = secondary,
    )
}
