package com.tomppi.enderslicer.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.data.BuiltInGcode
import com.tomppi.enderslicer.data.WorkspaceStateStore
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.SnapFacing
import com.tomppi.enderslicer.viewer.SnapJoint
import com.tomppi.enderslicer.viewer.SnapJointStyle
import com.tomppi.enderslicer.viewer.SnapLibraryShape
import com.tomppi.enderslicer.viewer.SnapTightness
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.Vec3
import com.tomppi.enderslicer.viewer.VertexData
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the view model keeps between launches, and what it drops when a slice input changes.
 *
 * A recorded snap joint's own choices - the lip's sizes, its cuts and their directions, the
 * depth the move gizmo set and the library shape it was grafted from - have to survive the
 * workspace round trip in BOTH directions: the descriptor write and the restore mapping. And
 * an extra-settings edit, the one settings mutator that skipped it, has to drop the published
 * G-code like every other mutator so no stale slice stays exportable.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainViewModelStateContractTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun pointTheEnginesAtADirectory() {
        // The view model builds its engine runners up front, and they resolve the
        // bundled binary from the application's native library directory - which a
        // Robolectric application has none of unless it is given one.
        context.applicationInfo.nativeLibraryDir = context.cacheDir.absolutePath
    }

    @Test
    fun theRestoredJointKeepsEveryLipChoiceCutsAndTheLibraryShape() {
        saveFixtureWorkspace()
        val viewModel = MainViewModel(context)
        await("the workspace is restored") { viewModel.uiState.value.snapRecord != null }

        val joints = requireNotNull(viewModel.uiState.value.snapRecord).joints
        assertEquals(2, joints.size)
        val lip = joints[0]
        assertEquals(SnapJointStyle.WALL_LIP, lip.style)
        assertEquals("the depth the arrow set", 1.5f, lip.depthMm, 1e-4f)
        assertEquals(0.4f, lip.lipEngagementMm!!, 1e-4f)
        assertEquals(0.5f, lip.lipToothHeightMm!!, 1e-4f)
        assertEquals("the wall the ring leaves", 2.5f, lip.lipWallMm!!, 1e-4f)
        assertEquals("how far its recess floor goes past the step", 0.6f, lip.lipRecessExtraMm!!, 1e-4f)
        assertEquals("the width of a cut", 1.2f, lip.lipCutWidthMm!!, 1e-4f)
        assertEquals("how deep its slit runs", 3f, lip.lipCutDepthMm!!, 1e-4f)
        assertEquals("and the cuts themselves", listOf(Vec3(1f, 2f, 3f)), lip.lipCuts)
        assertEquals("with each stroke's own direction", listOf(Vec3(0f, 1f, 0f)), lip.lipCutDirections)
        val library = joints[1]
        assertEquals(SnapJointStyle.LIBRARY_HOOK, library.style)
        assertEquals(
            "the shape a library hook was grafted from, not the picker's default",
            SnapLibraryShape.ANNULAR,
            library.libraryShape,
        )
    }

    @Test
    fun theWorkspaceWriteKeepsEveryJointChoiceForTheNextLaunch() {
        saveFixtureWorkspace()
        val viewModel = MainViewModel(context)
        await("the workspace is restored") { viewModel.uiState.value.snapRecord != null }

        // A settings edit re-saves the descriptor from the state, the way any gated action
        // does; the fingerprint moves with the override, which is how the write is awaited.
        viewModel.updateSettings("fixture_override") { it }
        await("the workspace is re-saved") {
            WorkspaceStateStore(context).load()
                ?.let { it.configurationFingerprint != launchFingerprint() } == true
        }

        val saved = requireNotNull(WorkspaceStateStore(context).load())
        val joints = requireNotNull(saved.snap) { "the pair record is written again" }.joints
        assertEquals(2, joints.size)
        val lip = joints[0]
        assertEquals(SnapJointStyle.WALL_LIP, lip.style)
        assertEquals(1.5f, lip.depthMm, 1e-4f)
        assertEquals(2.5f, lip.lipWallMm!!, 1e-4f)
        assertEquals(0.6f, lip.lipRecessExtraMm!!, 1e-4f)
        assertEquals(1.2f, lip.lipCutWidthMm!!, 1e-4f)
        assertEquals(3f, lip.lipCutDepthMm!!, 1e-4f)
        assertEquals(listOf(Vec3(1f, 2f, 3f)), lip.lipCuts)
        assertEquals(listOf(Vec3(0f, 1f, 0f)), lip.lipCutDirections)
        val library = joints[1]
        assertEquals("the depth travels too", 0.75f, library.depthMm, 1e-4f)
        assertEquals(
            "and the shape a library hook was grafted from",
            SnapLibraryShape.ANNULAR,
            library.libraryShape,
        )
        assertEquals("the CAD document travels with the plate", "Fixture design", saved.cad?.name)
    }

    @Test
    fun addingAnExtraSettingDropsThePublishedSlice() {
        val viewModel = MainViewModel(context)
        await("the launch settles") { !viewModel.uiState.value.isBusy }
        assertTrue(viewModel.uiState.value.statusMessage != SETTINGS_INVALIDATED_MESSAGE)

        val refusal = viewModel.setExtraSetting(SlicerEngine.CURA, "zz_fixture_key", "4")

        assertNull("the catalogue takes an extra setting", refusal)
        assertEquals("4", viewModel.uiState.value.extraCuraSettings["zz_fixture_key"])
        assertEquals(
            "an extra-settings edit is a slice input change like any other",
            SETTINGS_INVALIDATED_MESSAGE,
            viewModel.uiState.value.statusMessage,
        )
    }

    @Test
    fun removingAnExtraSettingDropsThePublishedSlice() {
        val viewModel = MainViewModel(context)
        await("the launch settles") { !viewModel.uiState.value.isBusy }
        assertTrue(viewModel.uiState.value.statusMessage != SETTINGS_INVALIDATED_MESSAGE)

        viewModel.removeExtraSetting(SlicerEngine.CURA, "zz_fixture_key")

        assertEquals(
            "removing one is a slice input change too",
            SETTINGS_INVALIDATED_MESSAGE,
            viewModel.uiState.value.statusMessage,
        )
    }

    private companion object {
        const val LOW_ID = "fixture-low-id"
        const val HIGH_ID = "fixture-high-id"
        const val SETTINGS_INVALIDATED_MESSAGE = "Settings changed; slice again to export G-code"
    }

    /**
     * The fingerprint the launch restores under - exactly what the restore computes for a
     * store with no saved configuration - so the fixture descriptor is accepted rather than
     * skipped as belonging to other settings.
     */
    private fun launchFingerprint(): String = WorkspaceStateStore.fingerprint(
        "Built-in current Cura settings",
        "Cura 5.14.0-alpha.0 / setting version 27 reference",
        null,
        "27",
        SlicerSettings().copy(overriddenSettingKeys = emptySet()).withRecomputedDerived(),
        BuiltInGcode.defaultStartGcode,
        BuiltInGcode.defaultEndGcode,
    )

    /** A saved workspace holding a split pair whose record carries every joint choice. */
    private fun saveFixtureWorkspace() {
        val placement = ModelPlacement(
            centerXmm = 110.0,
            centerYmm = 110.0,
            baseZmm = 0.0,
            source = "Fixture placement",
        )
        val low = fixtureStl("fixture-low.stl")
        val high = fixtureStl("fixture-high.stl")
        val lowSource = fixtureStl("fixture-pair-low.stl")
        val highSource = fixtureStl("fixture-pair-high.stl")
        val design = File(context.filesDir, "solidcraft/workspace/designs/fixture.solidcraft").apply {
            parentFile?.mkdirs()
            writeText("fixture design")
        }
        WorkspaceStateStore(context).save(
            WorkspaceStateStore.Snapshot(
                modelPath = low.absolutePath,
                modelDisplayName = "fixture lower",
                placement = placement,
                configurationFingerprint = launchFingerprint(),
                models = listOf(
                    WorkspaceStateStore.Entry(low.absolutePath, "fixture lower", placement, id = LOW_ID),
                    WorkspaceStateStore.Entry(high.absolutePath, "fixture upper", placement, id = HIGH_ID),
                ),
                snap = WorkspaceStateStore.SnapState(
                    lowHalfId = LOW_ID,
                    highHalfId = HIGH_ID,
                    axis = ModelPlacement.Axis.Z,
                    lowFaceMm = 20f,
                    highFaceMm = 20f,
                    lowSourcePath = lowSource.absolutePath,
                    highSourcePath = highSource.absolutePath,
                    scale = 1.5f,
                    fullJoint = true,
                    socketRamp = true,
                    hookLengthMm = 9f,
                    hookThicknessMm = 1.6f,
                    hookLipMm = 0.5f,
                    joints = listOf(
                        WorkspaceStateStore.SnapState.JointState(
                            anchorXMm = 12f,
                            anchorYMm = 13f,
                            anchorZMm = 20f,
                            onLowHalf = true,
                            depthMm = 1.5f,
                            beamHalf = SnapJoint.JointHalf.LOW,
                            tightness = SnapTightness.LOOSE,
                            barbs = 2,
                            facing = SnapFacing.SAME,
                            style = SnapJointStyle.WALL_LIP,
                            lipEngagementMm = 0.4f,
                            lipToothHeightMm = 0.5f,
                            lipWallMm = 2.5f,
                            lipRecessExtraMm = 0.6f,
                            lipCutWidthMm = 1.2f,
                            lipCutDepthMm = 3f,
                            lipCuts = listOf(Vec3(1f, 2f, 3f)),
                            lipCutDirections = listOf(Vec3(0f, 1f, 0f)),
                        ),
                        WorkspaceStateStore.SnapState.JointState(
                            anchorXMm = 28f,
                            anchorYMm = 27f,
                            anchorZMm = 20f,
                            onLowHalf = false,
                            depthMm = 0.75f,
                            beamHalf = SnapJoint.JointHalf.HIGH,
                            tightness = SnapTightness.TIGHT,
                            barbs = 1,
                            facing = SnapFacing.OPPOSITE,
                            style = SnapJointStyle.LIBRARY_HOOK,
                            libraryShape = SnapLibraryShape.ANNULAR,
                        ),
                    ),
                ),
                cad = WorkspaceStateStore.CadState(
                    name = "Fixture design",
                    designPath = design.absolutePath,
                    parts = listOf(WorkspaceStateStore.CadState.PartState("body1", LOW_ID)),
                ),
            ),
        )
    }

    /** One parseable triangle, inside the app's files directory where the store wants it. */
    private fun fixtureStl(name: String): File {
        val file = File(context.filesDir, "models/" + name)
        file.parentFile?.mkdirs()
        StlMeshWriter.writeBinary(
            StlMesh(
                displayName = name,
                interleavedVertices = VertexData.fromArray(
                    floatArrayOf(
                        0f, 0f, 0f, 0f, 0f, 1f,
                        10f, 0f, 0f, 0f, 0f, 1f,
                        0f, 10f, 0f, 0f, 0f, 1f,
                    ),
                ),
                triangleCount = 1,
                bounds = MeshBounds(0f, 0f, 0f, 10f, 10f, 0f),
            ),
            file,
        )
        return file
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
}
