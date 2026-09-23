package com.tomppi.enderslicer.model

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class AllSettingsCatalogsTest {

    @Test
    fun keysTheAppDerivesOtherSettingsFromAreBlocked() {
        // infill_line_distance and support_line_distance are derived from the
        // pattern while the argument list is built, and an extra setting is
        // applied after every app-controlled one. Letting the catalogue shadow
        // the pattern therefore kept the spacing of the previous pattern: at
        // 20% density a cubic-to-gyroid swap printed 6.7% instead of 20%. Both
        // patterns have dedicated editors, so they are blocked rather than
        // merely flagged as managed.
        assertTrue("infill_pattern" in AllSettingsCatalogs.CURA_BLOCKED_KEYS)
        assertTrue("support_pattern" in AllSettingsCatalogs.CURA_BLOCKED_KEYS)
    }

    @Test
    fun curaCatalogWalksCategoriesRecursively() {
        val root = JSONObject(
            """
            {
              "name": "test",
              "settings": {
                "quality": {
                  "label": "Quality",
                  "type": "category",
                  "settings": {
                    "layer_height": {"label": "Layer Height", "type": "float"},
                    "wall_line_count": {"label": "Walls", "type": "int"}
                  }
                },
                "extruder": {
                  "label": "Extruder",
                  "type": "category",
                  "children": {
                    "extruder_settings": {
                      "label": "Extruder settings",
                      "children": {
                        "infill_spacing": {"label": "Infill Spacing", "type": "float"}
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )

        val specs = AllSettingsCatalogs.curaFromJson(root)
        val keys = specs.map { it.key }
        assertTrue(keys.contains("layer_height"))
        assertTrue(keys.contains("wall_line_count"))
        assertTrue(keys.contains("infill_spacing"))
        assertTrue(specs.any { it.label == "Layer Height" })
    }
}
