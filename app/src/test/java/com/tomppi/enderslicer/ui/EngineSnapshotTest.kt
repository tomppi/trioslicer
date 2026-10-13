package com.tomppi.enderslicer.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.data.AppStateStore
import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerSettings
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The PrusaSlicer and OrcaSlicer snapshots: what an import set, kept per engine.
 *
 * Cura's import has always kept one - the values, the file they came from, and the
 * line that says they are a snapshot of it - so its profile block can show where the
 * settings came from and "Reset all app overrides" can put them back. These are the
 * same thing for the other two engines, one baseline each, with "Reset to engine
 * defaults" left as the separate, stronger action it already was.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EngineSnapshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun pointTheEnginesAtADirectory() {
        // The view model builds its engine runners up front, and they resolve the
        // bundled binary from the application's native library directory - which a
        // Robolectric application has none of unless it is given one.
        context.applicationInfo.nativeLibraryDir = context.cacheDir.absolutePath
    }

    private val printer = PrinterDefinition(
        name = "Modified Ender 3 V2",
        widthMm = 220.0, depthMm = 220.0, heightMm = 250.0,
        buildPlateShape = "rectangular", originAtCenter = false,
        heatedBed = true, heatedBuildVolume = false,
        gcodeFlavor = "Marlin", extruders = 1,
        nozzleSizeMm = 0.4, filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0, printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0, printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
    )

    /** A real .ini: the keys an import reads, one it does not, and a machine bed. */
    private val prusaIni = """
        [print]
        layer_height = 0.35
        perimeters = 5
        fill_density = 40%
        thin_walls = 1
        foo_setting = 3
        [filament]
        temperature = 215
        [printer]
        bed_shape = 0x0,220x0,220x220,0x220
    """.trimIndent()

    /** A process preset the desktop app would export, with three values of its own. */
    private val orcaProfile = """
        {
          "name": "My snapshot profile",
          "from": "User",
          "version": "2.4.2.1",
          "print_settings_id": "My snapshot profile",
          "inherits": "0.20mm Standard @Creality Ender3V2",
          "layer_height": "0.32",
          "wall_loops": "5",
          "sparse_infill_density": "30%"
        }
    """.trimIndent()

    private fun register(name: String, text: String): Uri {
        val uri = Uri.parse("content://enderslicer.test/" + name)
        shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
        return uri
    }

    /** Runs what the view model posted to the main looper and waits for its IO. */
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            runBlocking { delay(25) }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(what, condition())
    }

    @Test
    fun anImportedPrusaPresetComesBackFromResetAllAppOverrides() {
        val viewModel = MainViewModel(context)
        await("the launch settles") { !viewModel.uiState.value.isBusy }

        viewModel.importPrusaConfig(register("prusa-config.ini", prusaIni))
        await("the .ini is imported") { viewModel.uiState.value.prusaSettings.layerHeightMm == 0.35 }

        // What the import set, and the machine envelope it carried with it.
        assertEquals(5, viewModel.uiState.value.prusaSettings.perimeters)
        assertEquals(40.0, viewModel.uiState.value.prusaSettings.fillDensityPercent, 1e-9)
        assertEquals(220.0, viewModel.uiState.value.settings.machineWidthMm, 1e-9)

        val snapshot = viewModel.uiState.value.prusaSnapshot
        assertNotNull("the import leaves a snapshot behind", snapshot)
        assertEquals("prusa-config.ini", snapshot!!.name)
        assertEquals("Configuration snapshot", snapshot.source)
        assertEquals(7, snapshot.importedValues)
        // The key the app never sends is named rather than dropped in silence.
        assertEquals(listOf("foo_setting"), snapshot.notSentKeys)

        // An override on top of it: the value the user typed.
        viewModel.updatePrusaSettings(PrusaSliceSettings.Keys.LAYER_HEIGHT) { it.copy(layerHeightMm = 0.9) }
        assertEquals(0.9, viewModel.uiState.value.prusaSettings.layerHeightMm, 1e-9)
        assertTrue(viewModel.uiState.value.settings.isOverridden(PrusaSliceSettings.Keys.LAYER_HEIGHT))

        viewModel.resetPrusaToSnapshot()

        assertEquals(
            "the reset puts the imported value back",
            0.35,
            viewModel.uiState.value.prusaSettings.layerHeightMm,
            1e-9,
        )
        assertNotEquals(
            "and not the engine's own default",
            PrusaSliceSettings().layerHeightMm,
            viewModel.uiState.value.prusaSettings.layerHeightMm,
            1e-9,
        )
        assertFalse(viewModel.uiState.value.settings.isOverridden(PrusaSliceSettings.Keys.LAYER_HEIGHT))
    }

    @Test
    fun anImportedOrcaProfileComesBackFromResetAllAppOverrides() {
        val viewModel = MainViewModel(context)
        await("the launch settles") { !viewModel.uiState.value.isBusy }

        viewModel.importOrcaProfile(register("orca-profile.json", orcaProfile))
        await("the profile is imported") { viewModel.uiState.value.orcaSettings.layerHeightMm == 0.32 }

        assertEquals(5, viewModel.uiState.value.orcaSettings.wallLoops)
        assertEquals(30.0, viewModel.uiState.value.orcaSettings.sparseInfillDensityPercent, 1e-9)

        val snapshot = viewModel.uiState.value.orcaSnapshot
        assertNotNull("the import leaves a snapshot behind", snapshot)
        assertEquals("orca-profile.json", snapshot!!.name)
        assertEquals("Configuration snapshot", snapshot.source)
        assertEquals(3, snapshot.importedValues)

        viewModel.updateOrcaSettings(OrcaSliceSettings.Keys.WALL_LOOPS) { it.copy(wallLoops = 9) }
        assertEquals(9, viewModel.uiState.value.orcaSettings.wallLoops)

        viewModel.resetOrcaToSnapshot()

        assertEquals("the reset puts the imported value back", 5, viewModel.uiState.value.orcaSettings.wallLoops)
        assertEquals(0.32, viewModel.uiState.value.orcaSettings.layerHeightMm, 1e-9)
        assertNotEquals(
            "and not the engine's own default",
            OrcaSliceSettings().wallLoops,
            viewModel.uiState.value.orcaSettings.wallLoops,
        )
    }

    /**
     * A relaunch: both baselines are in the same persisted state as Cura's, so a new
     * process shows the snapshots again and can still reset to them.
     */
    @Test
    fun aRelaunchKeepsBothSnapshots() {
        val first = MainViewModel(context)
        await("the launch settles") { !first.uiState.value.isBusy }
        first.importPrusaConfig(register("prusa-config.ini", prusaIni))
        await("the .ini is imported") { first.uiState.value.prusaSettings.layerHeightMm == 0.35 }
        first.importOrcaProfile(register("orca-profile.json", orcaProfile))
        await("the profile is imported") { first.uiState.value.orcaSettings.layerHeightMm == 0.32 }
        runBlocking { delay(100) }

        val relaunched = MainViewModel(context)
        await("both snapshots come back") {
            relaunched.uiState.value.prusaSnapshot != null && relaunched.uiState.value.orcaSnapshot != null
        }
        assertEquals("prusa-config.ini", relaunched.uiState.value.prusaSnapshot?.name)
        assertEquals("orca-profile.json", relaunched.uiState.value.orcaSnapshot?.name)
        assertEquals(7, relaunched.uiState.value.prusaSnapshot?.importedValues)
        assertEquals(listOf("foo_setting"), relaunched.uiState.value.prusaSnapshot?.notSentKeys)

        relaunched.resetPrusaToSnapshot()
        assertEquals(0.35, relaunched.uiState.value.prusaSettings.layerHeightMm, 1e-9)
        relaunched.resetOrcaToSnapshot()
        assertEquals(0.32, relaunched.uiState.value.orcaSettings.layerHeightMm, 1e-9)
    }

    /** The store's own round trip, which is what a relaunch reads. */
    @Test
    fun theStoredBaselinesCarryEverythingTheCardShows() {
        val store = AppStateStore(context)
        store.savePrusaSnapshotBaseline(
            AppStateStore.PrusaSnapshotBaseline(
                machine = SlicerSettings(machineWidthMm = 250.0, overriddenSettingKeys = setOf("machine_width")),
                settings = PrusaSliceSettings(layerHeightMm = 0.35, perimeters = 5),
                startGcode = "G28",
                endGcode = "M84",
                profileName = "prusa-config.ini",
                profileSource = "Configuration snapshot",
                importedValues = 7,
                unmappedKeys = listOf("foo_setting"),
            ),
        )
        store.saveOrcaSnapshotBaseline(
            AppStateStore.OrcaSnapshotBaseline(
                machine = SlicerSettings(machineWidthMm = 260.0),
                settings = OrcaSliceSettings(layerHeightMm = 0.32, wallLoops = 5),
                profileName = "orca-profile.json",
                profileSource = "Configuration snapshot",
                importedValues = 3,
                refusedKeys = listOf("printer_settings_id"),
            ),
        )

        val relaunched = AppStateStore(context)
        val prusa = relaunched.prusaSnapshotBaseline()
        assertEquals("prusa-config.ini", prusa?.profileName)
        assertEquals("Configuration snapshot", prusa?.profileSource)
        assertEquals(0.35, prusa!!.settings.layerHeightMm, 1e-9)
        assertEquals(5, prusa.settings.perimeters)
        assertEquals(250.0, prusa.machine.machineWidthMm, 1e-9)
        assertEquals("G28", prusa.startGcode)
        assertEquals("M84", prusa.endGcode)
        assertEquals(7, prusa.importedValues)
        assertEquals(listOf("foo_setting"), prusa.unmappedKeys)

        val orca = relaunched.orcaSnapshotBaseline()
        assertEquals("orca-profile.json", orca?.profileName)
        assertEquals(0.32, orca!!.settings.layerHeightMm, 1e-9)
        assertEquals(5, orca.settings.wallLoops)
        assertEquals(260.0, orca.machine.machineWidthMm, 1e-9)
        assertEquals(listOf("printer_settings_id"), orca.refusedKeys)

        // Clearing one leaves the other where it is: one baseline per engine.
        relaunched.clearPrusaSnapshotBaseline()
        assertEquals(null, AppStateStore(context).prusaSnapshotBaseline())
        assertNotNull(AppStateStore(context).orcaSnapshotBaseline())
    }

    /** The card itself: the file, the source line, the counts and the refused keys. */
    @Test
    fun theProfileBlockShowsTheImportedSnapshot() {
        showPrusaSheet(EngineSnapshot("prusa-config.ini", "Configuration snapshot", 7, listOf("foo_setting")))

        compose.onNodeWithText("prusa-config.ini").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Configuration snapshot").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("7 imported values · 1 app overrides").performScrollTo().assertExists()
        compose.onNodeWithText("Not sent to this engine: foo_setting").performScrollTo().assertExists()
        // Both resets, each named for what it does.
        compose.onNodeWithText("Reset all app overrides").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reset to engine defaults").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theOrcaProfileBlockShowsItsSnapshotToo() {
        var settings by mutableStateOf(OrcaSliceSettings(layerHeightMm = 0.32))
        compose.setContent {
            OrcaSettingsSheet(
                state = MainUiState(
                    printer = printer,
                    settings = SlicerSettings(),
                    orcaSettings = settings,
                    orcaSnapshot = EngineSnapshot(
                        "orca-profile.json",
                        "Configuration snapshot",
                        3,
                        listOf("printer_settings_id"),
                    ),
                ),
                onSettings = { _, change -> settings = change(settings) },
                expandedLayout = false,
                modifier = Modifier.fillMaxSize(),
            )
        }

        compose.onNodeWithText("orca-profile.json").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Configuration snapshot").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3 imported values · 0 app overrides").performScrollTo().assertExists()
        compose.onNodeWithText("Not sent to this engine: printer_settings_id").performScrollTo().assertExists()
        compose.onNodeWithText("Reset all app overrides").performScrollTo().assertIsDisplayed()
    }

    /**
     * Each reset asks its own question, and the question says which one it is: the
     * snapshot's keeps the import, the engine's discards it.
     */
    @Test
    fun eachResetAsksItsOwnQuestion() {
        showPrusaSheet(EngineSnapshot("prusa-config.ini", "Configuration snapshot", 7, emptyList()))

        compose.onNodeWithText("Reset all app overrides").performScrollTo().performClick()
        compose.onNodeWithText("Reset all app overrides?").assertIsDisplayed()
        compose.onNodeWithText(
            "Every app override goes back to what prusa-config.ini imported. PrusaSlicer's own " +
                "defaults are not touched - that is \"Reset to engine defaults\". There is no undo.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Keep settings").performClick()
        assertEquals("dismissing resets nothing", 0, snapshotResets)

        compose.onNodeWithText("Reset to engine defaults").performScrollTo().performClick()
        compose.onNodeWithText("Reset to engine defaults?").assertIsDisplayed()
        compose.onNodeWithText("Reset").performClick()
        assertEquals("the engine's own reset is the one that ran", 1, engineResets)
        assertEquals("and the snapshot's did not", 0, snapshotResets)
    }

    /** "Reset to engine defaults" is still the engine's defaults, not the import's. */
    @Test
    fun theEngineDefaultsResetStillGoesToTheEngineDefaults() {
        showPrusaSheet(EngineSnapshot("prusa-config.ini", "Configuration snapshot", 7, emptyList()))

        compose.onNodeWithText("Reset to engine defaults").performScrollTo().performClick()
        compose.onNodeWithText("Reset").performClick()

        assertEquals(1, engineResets)
        assertEquals(
            "the engine's own default, not the imported 0.35",
            PrusaSliceSettings().layerHeightMm,
            settings.layerHeightMm,
            1e-9,
        )
    }

    private var settings = PrusaSliceSettings(layerHeightMm = 0.35)
    private var snapshotResets = 0
    private var engineResets = 0

    private fun showPrusaSheet(snapshot: EngineSnapshot) {
        compose.setContent {
            PrusaSettingsSheet(
                state = MainUiState(
                    printer = printer,
                    settings = SlicerSettings(overriddenSettingKeys = setOf(PrusaSliceSettings.Keys.LAYER_HEIGHT)),
                    prusaSettings = settings,
                    prusaSnapshot = snapshot,
                ),
                onSettings = { _, change ->
                    settings = change(settings)
                    engineResets++
                },
                onResetToSnapshot = { snapshotResets++ },
                expandedLayout = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
