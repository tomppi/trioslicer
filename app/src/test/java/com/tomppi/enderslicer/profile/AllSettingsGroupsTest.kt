package com.tomppi.enderslicer.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.model.AllSettingsCatalogs
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The list says which settings Cura will actually read.
 *
 * `retraction_hop_only_when_collides` is the setting that prompted this: it reads a flag the
 * engine never looks at unless retraction, Z hop and travel avoidance are all on, and the app's
 * defaults leave two of those off. Someone adds it, nothing changes, and nothing says why.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AllSettingsGroupsTest {

    private val assets get() = ApplicationProvider.getApplicationContext<Context>().assets
    private val specs get() = AllSettingsCatalogs.cura(assets)

    private fun valuesFor(settings: SlicerSettings = SlicerSettings()) = AllSettingsGroups.effectiveValues(
        specs = specs,
        appValues = CuraSettingDelta.standaloneValues(settings),
        extras = emptyMap(),
    )

    @Test
    fun theHopSettingIsInactiveWithTheAppsOwnDefaults() {
        val values = valuesFor()
        assertTrue(
            "the app's defaults turn travel avoidance off, so this setting cannot be read",
            values["travel_avoid_other_parts"] == "false",
        )
        val spec = specs.first { it.key == "retraction_hop_only_when_collides" }
        val reason = CuraEnabled.inactiveReason(spec.enabledExpression) { values[it] }
        assertTrue(
            "expected the hop setting to be waiting on something, got: $reason",
            reason != null && reason.startsWith("needs "),
        )
        assertTrue(
            "the reason should name travel_avoid_other_parts: $reason",
            reason!!.contains("travel_avoid_other_parts"),
        )
        assertTrue(
            "the reason should name retraction_hop_enabled: $reason",
            reason.contains("retraction_hop_enabled"),
        )
    }

    @Test
    fun turningItsConditionsOnMovesItAcross() {
        val values = valuesFor(
            SlicerSettings(zHopEnabled = true, avoidPrintedParts = true),
        )
        val spec = specs.first { it.key == "retraction_hop_only_when_collides" }
        assertEquals(
            "with Z hop and travel avoidance on, the setting is in effect",
            null,
            CuraEnabled.inactiveReason(spec.enabledExpression) { values[it] },
        )
    }

    @Test
    fun theSplitPutsItInTheInactiveGroup() {
        val values = valuesFor()
        val (_, inactive) = AllSettingsGroups.split(specs, values)
        val hop = inactive.firstOrNull { it.spec.key == "retraction_hop_only_when_collides" }
        assertTrue("retraction_hop_only_when_collides should be under Not active", hop != null)
        assertTrue(
            "every inactive entry carries its reason",
            inactive.all { it.reason.isNotBlank() },
        )
        // Not everything is inactive, or the grouping would be useless.
        val (active, _) = AllSettingsGroups.split(specs, values)
        assertTrue("expected most settings to be active, got ${active.size}", active.size > 300)
    }

    @Test
    fun aCatalogueWithNoExpressionsIsAllActive() {
        // Orca and Prusa carry no dependency information, so nothing there may be called inactive.
        val prusa = AllSettingsCatalogs.prusa(assets)
        assertTrue("the Prusa catalogue should carry no enabled expressions", prusa.all { it.enabledExpression == null })
        val (active, inactive) = AllSettingsGroups.split(prusa, emptyMap())
        assertTrue("nothing may be grouped inactive without an expression: ${inactive.map { it.spec.key }}", inactive.isEmpty())
        assertEquals(prusa.size, active.size)
    }
}
