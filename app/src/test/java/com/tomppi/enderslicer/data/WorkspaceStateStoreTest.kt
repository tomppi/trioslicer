package com.tomppi.enderslicer.data

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
