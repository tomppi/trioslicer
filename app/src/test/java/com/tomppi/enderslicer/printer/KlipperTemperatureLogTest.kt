package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The temperature chart's data: one reading a second, five minutes of it. */
class KlipperTemperatureLogTest {
    private fun heater(name: String, temperature: Double?) =
        KlipperHeater(name = name, label = name, temperature = temperature, target = null)

    @Test
    fun oneReadingPerIntervalAndNoMore() {
        val log = KlipperTemperatureLog(windowMillis = 10_000, intervalMillis = 1_000)
        val heaters = listOf(heater("extruder", 20.0))
        assertEquals(1, log.record(1_000, heaters)?.size)
        // Several notifications a second is normal; only one becomes a sample.
        assertNull(log.record(1_100, heaters))
        assertNull(log.record(1_900, heaters))
        assertEquals(2, log.record(2_000, heaters)?.size)
    }

    @Test
    fun readingsOlderThanTheWindowAreDropped() {
        val log = KlipperTemperatureLog(windowMillis = 5_000, intervalMillis = 1_000)
        val heaters = listOf(heater("extruder", 20.0))
        for (second in 1..10) log.record(second * 1_000L, heaters)
        val samples = log.snapshot()
        // Five seconds of window, one sample a second, both ends included.
        assertEquals(6, samples.size)
        assertEquals(5_000L, samples.first().atMillis)
        assertEquals(10_000L, samples.last().atMillis)
    }

    @Test
    fun aHeaterWithNoReadingIsNotASample() {
        val log = KlipperTemperatureLog()
        assertNull(log.record(1_000, listOf(heater("extruder", null))))
        assertNull(log.record(1_000, emptyList()))
    }

    @Test
    fun everyTemperatureReportedIsInTheSample() {
        val log = KlipperTemperatureLog()
        val samples = log.record(
            1_000,
            listOf(heater("extruder", 210.0), heater("heater_bed", 60.5), heater("temperature_sensor chamber", 31.0)),
        )!!
        assertEquals(3, samples.single().temperatures.size)
        assertEquals(60.5, samples.single().temperatures.getValue("heater_bed"), 0.001)
    }
}
