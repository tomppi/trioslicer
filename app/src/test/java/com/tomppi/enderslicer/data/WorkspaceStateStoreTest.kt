package com.tomppi.enderslicer.data

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.SlicerSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class WorkspaceStateStoreTest {
    @Test
    fun snapshotRoundTripsThroughAtomicDescriptor() {
        val files = createTempDirectory("enderslicer-workspace").toFile()
        val model = File(files, "models/model.stl").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val store = WorkspaceStateStore(files)
        val snapshot = snapshot(model)

        store.save(snapshot)
        val restored = requireNotNull(store.load())

        assertEquals(snapshot, restored)
        assertTrue(File(files, "persistent-state/current-workspace.json").isFile)
        assertNull(File(files, "persistent-state/current-workspace.next").takeIf(File::exists))
    }

    @Test
    fun clearRemovesCommittedAndTransactionalWorkspaceFiles() {
        val files = createTempDirectory("enderslicer-workspace-clear").toFile()
        val model = File(files, "models/model.stl").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val store = WorkspaceStateStore(files)
        store.save(snapshot(model))
        val stateDirectory = File(files, "persistent-state")
        File(stateDirectory, "current-workspace.next").writeText("stale-next")
        File(stateDirectory, "current-workspace.previous").writeText("stale-previous")

        store.clear()

        assertNull(store.load())
        assertFalse(File(stateDirectory, "current-workspace.json").exists())
        assertFalse(File(stateDirectory, "current-workspace.next").exists())
        assertFalse(File(stateDirectory, "current-workspace.previous").exists())
    }

    @Test
    fun modelOutsidePrivateDirectoryIsRejected() {
        val files = createTempDirectory("enderslicer-workspace-root").toFile()
        val external = createTempDirectory("enderslicer-external-model").resolve("model.stl").toFile().apply {
            writeBytes(byteArrayOf(1))
        }
        val store = WorkspaceStateStore(files)
        val snapshot = WorkspaceStateStore.Snapshot(
            modelPath = external.absolutePath,
            modelDisplayName = "external.stl",
            placement = ModelPlacement(centerXmm = 0.0, centerYmm = 0.0, baseZmm = 0.0),
            configurationFingerprint = WorkspaceStateStore.fingerprint("settings"),
        )

        val error = runCatching { store.save(snapshot) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun fingerprintIgnoresOverrideKeyInsertionOrder() {
        // saveSettings persists overriddenSettingKeys sorted, while a live
        // updateSettings appends in user-entered order. The fingerprint must
        // not depend on that ordering, or a plain restart would recompute a
        // different fingerprint and clear calibration events.
        val live = SlicerSettings().copy(
            overriddenSettingKeys = linkedSetOf("wallLineCount", "layerHeightMm"),
        )
        val restored = SlicerSettings().copy(
            overriddenSettingKeys = linkedSetOf("layerHeightMm", "wallLineCount"),
        )
        val liveFingerprint = WorkspaceStateStore.fingerprint(
            "Modified Ender 3 V2",
            "Cura project: reference.3mf",
            "5.14.0-alpha.0",
            "25",
            live,
            "start",
            "end",
        )
        val restoredFingerprint = WorkspaceStateStore.fingerprint(
            "Modified Ender 3 V2",
            "Cura project: reference.3mf",
            "5.14.0-alpha.0",
            "25",
            restored,
            "start",
            "end",
        )
        assertEquals(liveFingerprint, restoredFingerprint)
    }

    /**
     * The split pair is what makes the Snap fit tool available again after a relaunch, so it
     * has to survive the descriptor - and it is the OBJECT IDENTITIES that must, because the
     * pair is two ids resolved against the plate the restore builds.
     */
    @Test
    fun theSplitPairAndTheHalfIdentitiesSurviveTheDescriptor() {
        val files = createTempDirectory("enderslicer-workspace-snap").toFile()
        val store = WorkspaceStateStore(files)
        val low = model(files, "models/low.stl")
        val high = model(files, "models/high.stl")
        val base = snapshot(low)
        val held = base.copy(
            models = listOf(
                WorkspaceStateStore.Entry(
                    modelPath = low.absolutePath,
                    modelDisplayName = "part lower",
                    placement = base.placement,
                    id = "low-id",
                ),
                WorkspaceStateStore.Entry(
                    modelPath = high.absolutePath,
                    modelDisplayName = "part upper",
                    placement = base.placement.copy(baseZmm = 20.0),
                    id = "high-id",
                ),
            ),
            snap = WorkspaceStateStore.SnapState(
                lowHalfId = "low-id",
                highHalfId = "high-id",
                axis = ModelPlacement.Axis.Z,
                lowFaceMm = 20f,
                highFaceMm = 20f,
            ),
        )

        store.save(held)
        val restored = requireNotNull(store.load())

        assertEquals(held, restored)
        assertNotNull("the pair came back", restored.snap)
        val pair = requireNotNull(restored.snap)
        assertEquals("low-id", pair.lowHalfId)
        assertEquals("high-id", pair.highHalfId)
        assertEquals(ModelPlacement.Axis.Z, pair.axis)
        assertEquals(20f, pair.lowFaceMm, 0f)
        assertEquals(20f, pair.highFaceMm, 0f)
        assertEquals(
            "and the halves keep the identities the pair names",
            listOf("low-id", "high-id"),
            restored.models.map { it.id },
        )
    }

    /**
     * An older build wrote no snap pair and no object identities at all. Such a descriptor
     * must restore exactly as it did before the pair existed - a plate with no snap fit on
     * offer - rather than fail the load and lose every model with it.
     */
    @Test
    fun aDescriptorFromBeforeTheSnapPairRestoresWithNoSnap() {
        val files = createTempDirectory("enderslicer-workspace-old").toFile()
        val store = WorkspaceStateStore(files)
        val low = model(files, "models/low.stl")
        val high = model(files, "models/high.stl")
        val base = snapshot(low)
        store.save(
            base.copy(
                models = listOf(
                    WorkspaceStateStore.Entry(low.absolutePath, "part lower", base.placement, id = "low-id"),
                    WorkspaceStateStore.Entry(high.absolutePath, "part upper", base.placement, id = "high-id"),
                ),
                snap = WorkspaceStateStore.SnapState("low-id", "high-id", ModelPlacement.Axis.Z, 20f, 20f),
            ),
        )
        stripTheNewKeys(workspaceFile(files))

        val restored = requireNotNull(store.load())

        assertNull("no pair is the answer, not a failure", restored.snap)
        assertEquals(
            "and the identities it never wrote come back absent, not invented",
            listOf(null, null),
            restored.models.map { it.id },
        )
        assertEquals("model.stl", restored.modelDisplayName)
    }

    /**
     * A descriptor can be damaged - an interrupted write, a hand edit, a bug in a future
     * build - and the rule the file already keeps is that damage costs the optional field and
     * never the plate. Every way the pair can be wrong is read as "no pair".
     */
    @Test
    fun aDamagedSnapPairDegradesToNoSnapRatherThanFailingTheLoad() {
        val damaged = listOf(
            "a half missing" to JSONObject().put("lowHalfId", "low-id"),
            "the same half twice" to JSONObject()
                .put("lowHalfId", "low-id").put("highHalfId", "low-id")
                .put("axis", "Z").put("lowFaceMm", 20.0).put("highFaceMm", 20.0),
            "an axis that does not exist" to JSONObject()
                .put("lowHalfId", "low-id").put("highHalfId", "high-id")
                .put("axis", "W").put("lowFaceMm", 20.0).put("highFaceMm", 20.0),
            "faces that are not numbers" to JSONObject()
                .put("lowHalfId", "low-id").put("highHalfId", "high-id")
                .put("axis", "Z").put("lowFaceMm", "middle").put("highFaceMm", 20.0),
            "halves the descriptor does not hold" to JSONObject()
                .put("lowHalfId", "low-id").put("highHalfId", "gone-id")
                .put("axis", "Z").put("lowFaceMm", 20.0).put("highFaceMm", 20.0),
        )
        for ((what, snap) in damaged) {
            val files = createTempDirectory("enderslicer-workspace-damaged").toFile()
            val store = WorkspaceStateStore(files)
            val low = model(files, "models/low.stl")
            val high = model(files, "models/high.stl")
            val base = snapshot(low)
            store.save(
                base.copy(
                    models = listOf(
                        WorkspaceStateStore.Entry(low.absolutePath, "part lower", base.placement, id = "low-id"),
                        WorkspaceStateStore.Entry(high.absolutePath, "part upper", base.placement, id = "high-id"),
                    ),
                    snap = WorkspaceStateStore.SnapState("low-id", "high-id", ModelPlacement.Axis.Z, 20f, 20f),
                ),
            )
            val root = JSONObject(workspaceFile(files).readText()).put("snap", snap)
            workspaceFile(files).writeText(root.toString())

            val restored = requireNotNull(store.load())

            assertNull("$what must read as no pair", restored.snap)
            assertEquals("and the plate survives it: $what", 2, restored.models.size)
        }
    }

    private fun model(files: File, path: String): File = File(files, path).apply {
        parentFile?.mkdirs()
        writeBytes(byteArrayOf(1, 2, 3))
    }

    private fun workspaceFile(files: File): File = File(files, "persistent-state/current-workspace.json")

    /** The descriptor as an older build wrote it: no snap pair, no object identities. */
    private fun stripTheNewKeys(file: File) {
        val root = JSONObject(file.readText())
        root.remove("snap")
        root.optJSONArray("models")?.let { array ->
            for (index in 0 until array.length()) array.getJSONObject(index).remove("id")
        }
        file.writeText(root.toString())
    }

    private fun snapshot(model: File): WorkspaceStateStore.Snapshot = WorkspaceStateStore.Snapshot(
        modelPath = model.absolutePath,
        modelDisplayName = "model.stl",
        placement = ModelPlacement(
            centerXmm = 115.0,
            centerYmm = 115.0,
            baseZmm = 0.0,
            source = "Test placement",
        ),
        configurationFingerprint = WorkspaceStateStore.fingerprint("profile", "settings"),
    )
}
