package com.tomppi.enderslicer.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class CuraIniParserTest {
    @Test
    fun preservesMultilineGcode() {
        val ini = CuraIniParser.parse(
            "[values]\nmachine_start_gcode = G28\n\tG29 L0\n\tG29 A\nspeed_print = 200\n",
        )

        assertEquals("G28\nG29 L0\nG29 A", ini.value("values", "machine_start_gcode"))
        assertEquals("200", ini.value("values", "speed_print"))
    }
}
