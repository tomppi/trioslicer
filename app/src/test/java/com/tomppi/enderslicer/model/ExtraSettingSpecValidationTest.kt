package com.tomppi.enderslicer.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An extra setting is persisted and re-sent on every later slice, so its value
 * rules are the only thing standing between a typo and a slice that fails with
 * a generic engine error minutes later.
 */
class ExtraSettingSpecValidationTest {
    @Test
    fun rejectsValuesThatCouldNotBeSentToTheEngine() {
        assertNull(ExtraSettingValidation.rejectReason("speed_print", "45"))
        assertNull(ExtraSettingValidation.rejectReason("infill_pattern", "grid"))
        assertEquals("the value is blank", ExtraSettingValidation.rejectReason("speed_print", "   "))
        assertEquals("the value contains '='", ExtraSettingValidation.rejectReason("speed_print", "45=50"))
        assertEquals(
            "the value contains a line break or control character",
            ExtraSettingValidation.rejectReason("speed_print", "45\nM104 S0"),
        )
        assertEquals(
            "the value has leading or trailing whitespace",
            ExtraSettingValidation.rejectReason("speed_print", " 45"),
        )
        assertTrue(
            requireNotNull(ExtraSettingValidation.rejectReason("speed_print", "x".repeat(501))).contains("500"),
        )
    }

    @Test
    fun requiresNumbersForKeysTheCatalogueMarksNumeric() {
        val numeric = ExtraSettingSpec(key = "speed_print", label = "Print Speed", numeric = true)
        val text = ExtraSettingSpec(key = "infill_pattern", label = "Infill Pattern")

        assertEquals("the value must be a number", ExtraSettingValidation.rejectReason("speed_print", "fast", numeric))
        assertNull(ExtraSettingValidation.rejectReason("speed_print", "45.5", numeric))
        assertNull(ExtraSettingValidation.rejectReason("infill_pattern", "grid", text))

        val failure = runCatching {
            ExtraSettingValidation.requireValid("speed_print", "fast", numeric)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("speed_print"))
        assertTrue(failure?.message.orEmpty().contains("must be a number"))
    }

    @Test
    fun readsTheNumericFlagFromTheCuraDefinitionTypes() {
        val root = JSONObject(
            """
            {"settings": {
                "speed_print": {"label": "Print Speed", "type": "float"},
                "infill_sparse_density": {"label": "Infill Density", "type": "int"},
                "infill_pattern": {"label": "Infill Pattern", "type": "enum"},
                "machine_name": {"label": "Machine Name", "type": "str"}
            }}
            """.trimIndent(),
        )

        val specs = AllSettingsCatalogs.curaFromJson(root).associateBy(ExtraSettingSpec::key)

        assertTrue(requireNotNull(specs["speed_print"]).numeric)
        assertTrue(requireNotNull(specs["infill_sparse_density"]).numeric)
        assertFalse(requireNotNull(specs["infill_pattern"]).numeric)
        assertFalse(requireNotNull(specs["machine_name"]).numeric)
    }

    @Test
    fun validOnlyDropsEntriesThatCannotBeSentToTheEngine() {
        val catalog = listOf(
            ExtraSettingSpec(key = "speed_print", label = "Print Speed", numeric = true),
            ExtraSettingSpec(key = "layer_height", label = "Layer Height", numeric = true),
        )

        // Dropped: an invalid key, a blank value, and a non-numeric value for a
        // catalogue key that declares a number. A key the catalogue does not know
        // keeps its text value.
        assertEquals(
            mapOf("speed_print" to "45", "seam_position" to "nearest"),
            ExtraSettingValidation.validOnly(
                linkedMapOf(
                    "speed_print" to "45",
                    "seam_position" to "nearest",
                    "bad key" to "1",
                    "broken" to " ",
                    "layer_height" to "tall",
                ),
                catalog,
            ),
        )
    }
}
