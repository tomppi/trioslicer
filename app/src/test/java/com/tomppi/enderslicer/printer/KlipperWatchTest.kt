package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which objects the app follows.
 *
 * klippy refuses a subscription for an object that does not exist, so this is the
 * difference between a printer with a chamber sensor being followed and a printer with
 * no [bed_mesh] section failing to subscribe to anything at all.
 */
class KlipperWatchTest {
    @Test
    fun onlyTheFixedObjectsThePrinterActuallyPublishesAreAskedFor() {
        val published = listOf("extruder", "heater_bed", "toolhead", "fan")
        val wanted = KlipperWatch.forPrinter(published)
        assertTrue(wanted.containsAll(listOf("extruder", "heater_bed", "toolhead", "fan")))
        // This printer has no [bed_mesh] and no [exclude_object]: asking for either
        // would cost the whole subscription.
        assertFalse(wanted.contains("bed_mesh"))
        assertFalse(wanted.contains("exclude_object"))
        assertFalse(wanted.contains("configfile"))
    }

    @Test
    fun theFamiliesAreFollowedByTheirNames() {
        val published = listOf(
            "extruder",
            "temperature_sensor chamber",
            "fan_generic partfan",
            "heater_generic drybox",
            "mcu mcu toolhead",
            "servo zhop",
        )
        val wanted = KlipperWatch.forPrinter(published)
        assertTrue(wanted.contains("temperature_sensor chamber"))
        assertTrue(wanted.contains("fan_generic partfan"))
        assertTrue(wanted.contains("heater_generic drybox"))
        assertTrue(wanted.contains("mcu mcu toolhead"))
        // Not everything: an object the screens have no way to show is not followed.
        assertFalse(wanted.contains("servo zhop"))
    }

    @Test
    fun aPrinterThatWillNotSayWhatItHasFallsBackToTheFixedList() {
        // Better to ask for the objects every Klipper has and be refused one than to
        // subscribe to nothing: an empty list means the question could not be answered,
        // not that the printer is empty.
        assertEquals(KlipperWatch.always, KlipperWatch.forPrinter(emptyList()))
    }
}
