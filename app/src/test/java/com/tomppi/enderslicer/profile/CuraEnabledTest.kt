package com.tomppi.enderslicer.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Cura states, per setting, whether it is in effect - and the app never read it.
 *
 * `retraction_hop_only_when_collides` is inert unless `retraction_enable`,
 * `retraction_hop_enabled` and `travel_avoid_other_parts` are all on. Someone who adds it from
 * the All-settings list and sees nothing happen has no way to find that out: the engine never
 * reads the setting, and the list shows it like any other.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CuraEnabledTest {

    private val assets get() = ApplicationProvider.getApplicationContext<Context>().assets

    /** Every enabled expression the bundled definitions declare. */
    private fun allExpressions(): List<String> {
        val found = mutableListOf<String>()
        fun walk(node: JSONObject) {
            val names = node.keys()
            while (names.hasNext()) {
                val child = node.optJSONObject(names.next()) ?: continue
                (child.opt("enabled") as? String)?.let { found.add(it) }
                walk(child)
            }
        }
        for (path in listOf(
            "cura/definitions/fdmprinter.def.json",
            "cura/definitions/creality_base.def.json",
            "cura/definitions/creality_ender3.def.json",
        )) {
            JSONObject(assets.open(path).bufferedReader().use { it.readText() })
                .optJSONObject("settings")?.let { walk(it) }
        }
        return found
    }

    /**
     * The grammar Cura actually uses, measured rather than assumed: 514 expressions, of which
     * six use Python list membership or a bare `False`. They are named here so that a seventh
     * cannot appear unnoticed, and so the count cannot quietly grow.
     *
     * An expression that cannot be read is reported as **active**, never as inactive: saying a
     * setting is switched off when the truth is unknown would be worse than saying nothing, and
     * `inactiveReason` returns null for it.
     */
    @Test
    fun everyExpressionIsReadExceptTheSevenKnownOnes() {
        val expressions = allExpressions()
        assertTrue("no expressions found, so this test proves nothing", expressions.size > 400)
        val unreadable = expressions.filterNot { CuraEnabled.isReadable(it) }
        assertEquals(
            "the set of unreadable expressions changed; if the grammar grew, teach the parser: " +
                unreadable.joinToString(" | "),
            7,
            unreadable.size,
        )
        // Each is one of the shapes deliberately left out: Python list membership ('in' /
        // 'not in'), the one modulo, a generator expression inside all(...), or a bare False.
        // None of them guards a setting anyone reaches for, and an unreadable expression is
        // reported as active rather than inactive, so the cost of leaving them is a group
        // placement rather than a wrong claim about the printer.
        for (expression in unreadable) {
            val known = expression.contains("not in") || expression.contains(" in ") ||
                expression.contains("%") || expression.contains("all(") || expression.contains("False")
            assertTrue("a new shape appeared in an enabled expression: $expression", known)
        }
    }

    @Test
    fun aSettingWithAllItsConditionsMetIsActive() {
        val values = mapOf(
            "retraction_enable" to "true",
            "retraction_hop_enabled" to "true",
            "travel_avoid_other_parts" to "true",
        )
        assertNull(
            CuraEnabled.inactiveReason("retraction_enable and retraction_hop_enabled and travel_avoid_other_parts") { values[it] },
        )
    }

    @Test
    fun theUnmetConditionIsNamedBack() {
        val values = mapOf(
            "retraction_enable" to "true",
            "retraction_hop_enabled" to "true",
            "travel_avoid_other_parts" to "false",
        )
        assertEquals(
            "needs travel_avoid_other_parts",
            CuraEnabled.inactiveReason("retraction_enable and retraction_hop_enabled and travel_avoid_other_parts") { values[it] },
        )
    }

    @Test
    fun theEnginesSpellingOfTrueIsTheOnlyOne() {
        // Settings.cpp: "on", "yes", "true", "True" - or a non-zero number. "TRUE" is false,
        // which is the whole reason booleans in the sheet are switches.
        assertTrue(CuraEnabled.truthy("true"))
        assertTrue(CuraEnabled.truthy("True"))
        assertTrue(CuraEnabled.truthy("on"))
        assertTrue(CuraEnabled.truthy("yes"))
        assertTrue(CuraEnabled.truthy("1"))
        assertTrue(CuraEnabled.truthy("-2"))
        assertTrue(!CuraEnabled.truthy("TRUE"))
        assertTrue(!CuraEnabled.truthy("ON"))
        assertTrue(!CuraEnabled.truthy("false"))
        assertTrue(!CuraEnabled.truthy(""))
        assertTrue(!CuraEnabled.truthy(null))
    }

    @Test
    fun comparisonsCoverStringsNumbersAndParens() {
        assertEquals(
            "needs machine_gcode_flavor",
            CuraEnabled.inactiveReason("machine_gcode_flavor != \"UltiGCode\"") { "UltiGCode" },
        )
        assertNull(CuraEnabled.inactiveReason("machine_gcode_flavor != \"UltiGCode\"") { "Marlin" })
        assertNull(CuraEnabled.inactiveReason("(machine_extruder_count > 1) or retraction_enable") { key ->
            if (key == "machine_extruder_count") "1" else "true"
        })
        // A name containing "or" must not be split at it.
        assertTrue(CuraEnabled.isReadable("support_order or infill_order"))
    }

    @Test
    fun anUnreadableExpressionIsNotCalledInactive() {
        // Claiming a setting is switched off would be worse than admitting the expression is unknown.
        assertNull(CuraEnabled.inactiveReason("this is not an expression !!!") { "true" })
        assertNull(CuraEnabled.inactiveReason(null) { "true" })
    }
}
