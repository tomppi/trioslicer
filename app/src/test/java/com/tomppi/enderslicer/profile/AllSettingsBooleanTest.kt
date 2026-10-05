package com.tomppi.enderslicer.profile

import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.tomppi.enderslicer.model.AllSettingsCatalogs
import com.tomppi.enderslicer.model.ExtraSettingSpec
import com.tomppi.enderslicer.ui.AllSettingsSheet
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Booleans are chosen, never typed.
 *
 * CuraEngine's `get<bool>` accepts only "on", "yes", "true" and "True" as true; every other
 * spelling, "TRUE" and "ON" included, falls through to false - silently, with nothing in the
 * engine log and nothing from the app. A text field invites exactly that, so a setting the
 * catalogue declares boolean gets a switch instead.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AllSettingsBooleanTest {

    @get:Rule
    val compose = createComposeRule()

    private val assets get() = ApplicationProvider.getApplicationContext<Context>().assets

    @Test
    fun theCatalogsMarkTheirBooleans() {
        val cura = AllSettingsCatalogs.cura(assets).associateBy { it.key }
        assertTrue(
            "retraction_hop_only_when_collides is a Cura bool but is not marked boolean",
            cura["retraction_hop_only_when_collides"]?.boolean == true,
        )
        assertTrue("jerk_print is a number, not a boolean", cura["jerk_print"]?.boolean == false)
        assertTrue("retraction_hop is a number, not a boolean", cura["retraction_hop"]?.boolean == false)
        assertTrue("acceleration_print is a number", cura["acceleration_print"]?.boolean == false)
    }

    @Test
    fun aBooleanOpensASwitchRatherThanATextField() {
        compose.setContent {
            AllSettingsSheet(
                engineLabel = "Cura",
                specs = listOf(
                    ExtraSettingSpec(
                        key = "retraction_hop_only_when_collides",
                        label = "Z Hop Only Over Printed Parts",
                        boolean = true,
                    ),
                ),
                added = emptyMap(),
                managedKeys = emptySet(),
                blockedKeys = emptySet(),
                onAdd = { _, _ -> null },
                onRemove = {},
            )
        }
        compose.onNodeWithText("retraction_hop_only_when_collides").performClick()
        compose.waitForIdle()
        compose.onNode(isToggleable()).assertExists()
        // Opens on the catalogue's default, not always off - a boolean with no declared
        // default is false, and the switch says so rather than showing nothing.
        compose.onNodeWithText("false").assertExists()
    }

    @Test
    fun aNumberStillOpensATextField() {
        compose.setContent {
            AllSettingsSheet(
                engineLabel = "Cura",
                specs = listOf(ExtraSettingSpec(key = "jerk_print", label = "jerk_print", numeric = true)),
                added = emptyMap(),
                managedKeys = emptySet(),
                blockedKeys = emptySet(),
                onAdd = { _, _ -> null },
                onRemove = {},
            )
        }
        compose.onNodeWithText("jerk_print").performClick()
        compose.waitForIdle()
        compose.onNode(isToggleable()).assertDoesNotExist()
    }
}
