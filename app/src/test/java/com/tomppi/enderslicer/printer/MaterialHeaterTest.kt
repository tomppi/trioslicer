package com.tomppi.enderslicer.printer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which heaters a material preset is for.
 *
 * The card used to treat everything that was not the bed as a hotend, so a chamber heater or a
 * temperature fan could be commanded to 250 C by the ABS button.
 */
class MaterialHeaterTest {

    @Test
    fun theExtrudersAndTheBedAreMaterialHeaters() {
        assertTrue(isMaterialHeater("extruder"))
        assertTrue(isMaterialHeater("extruder1"))
        assertTrue(isMaterialHeater("heater_bed"))
    }

    @Test
    fun aChamberOrAFanIsNot() {
        assertFalse(isMaterialHeater("chamber"))
        assertFalse(isMaterialHeater("temperature_fan"))
        assertFalse(isMaterialHeater("heater_generic chamber"))
        assertFalse(isMaterialHeater("extruder_chamber"))
    }
}
