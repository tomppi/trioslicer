package com.tomppi.enderslicer.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class CuraMaterialParserTest {
    @Test
    fun machineSpecificMaterialValuesOverrideGenericValues() {
        val values = CuraMaterialParser.parse(
            """
            <fdmmaterial xmlns="http://www.ultimaker.com/material">
              <metadata><name><brand>Custom</brand><material>PLA</material></name><GUID>guid</GUID></metadata>
              <properties><density>1.24</density><diameter>1.75</diameter></properties>
              <settings>
                <setting key="standby temperature">175</setting>
                <setting key="print temperature">200</setting>
                <machine>
                  <machine_identifier product="creality_ender3" />
                  <setting key="standby temperature">180</setting>
                  <setting key="print cooling">100</setting>
                </machine>
              </settings>
            </fdmmaterial>
            """.trimIndent(),
            machineProduct = "creality_ender3",
        )

        assertEquals("Custom", values["material_brand"])
        assertEquals("PLA", values["material_type"])
        assertEquals("1.75", values["material_diameter"])
        assertEquals("200", values["material_print_temperature"])
        assertEquals("180", values["material_standby_temperature"])
        assertEquals("100", values["cool_fan_speed"])
    }
}
