package com.tomppi.enderslicer.data

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.viewer.SnapFacing
import com.tomppi.enderslicer.viewer.SnapJoint
import com.tomppi.enderslicer.viewer.SnapTightness
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

    /**
     * The pair's RECORD - the unjointed halves every joint on it was built from, the panel
     * values, and the joints themselves - has to survive the descriptor too: it is what makes
     * an applied joint editable in the next session rather than final.
     *
     * The anchors are the part worth checking: they are stored in the pair's OWN frame, so
     * they mean the same thing on a plate the packer has since rearranged.
     */
    @Test
    fun theRecordedJointsAndTheirSourceHalvesSurviveTheDescriptor() {
        val files = createTempDirectory("enderslicer-workspace-record").toFile()
        val store = WorkspaceStateStore(files)
        val low = model(files, "models/low.stl")
        val high = model(files, "models/high.stl")
        val lowSource = model(files, "models/pair-low.stl")
        val highSource = model(files, "models/pair-high.stl")
        val base = snapshot(low)
        val held = base.copy(
            models = listOf(
                WorkspaceStateStore.Entry(low.absolutePath, "part lower", base.placement, id = "low-id"),
                WorkspaceStateStore.Entry(high.absolutePath, "part upper", base.placement, id = "high-id"),
            ),
            snap = WorkspaceStateStore.SnapState(
                lowHalfId = "low-id",
                highHalfId = "high-id",
                axis = ModelPlacement.Axis.Z,
                lowFaceMm = 20f,
                highFaceMm = 20f,
                lowSourcePath = lowSource.absolutePath,
                highSourcePath = highSource.absolutePath,
                scale = 1.25f,
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
                        beamHalf = SnapJoint.JointHalf.LOW,
                        tightness = SnapTightness.LOOSE,
                        barbs = 1,
                        facing = SnapFacing.SAME,
                    ),
                    WorkspaceStateStore.SnapState.JointState(
                        anchorXMm = 28f,
                        anchorYMm = 27f,
                        anchorZMm = 20f,
                        onLowHalf = false,
                        beamHalf = SnapJoint.JointHalf.HIGH,
                        tightness = SnapTightness.TIGHT,
                        barbs = 2,
                        facing = SnapFacing.OPPOSITE,
                    ),
                ),
            ),
        )

        store.save(held)
        val restored = requireNotNull(store.load())

        assertEquals("the record is part of the pair", held, restored)
        val pair = requireNotNull(restored.snap)
        assertTrue("which now carries one", pair.hasRecord)
        assertEquals(
            listOf(lowSource.absolutePath, highSource.absolutePath),
            listOf(pair.lowSourcePath, pair.highSourcePath),
        )
        assertEquals(1.25f, pair.scale, 0f)
        assertTrue(pair.fullJoint)
        assertTrue(pair.socketRamp)
        assertEquals(9f, pair.hookLengthMm!!, 0f)
        assertEquals(2, pair.joints.size)
        assertEquals(
            "and every choice that makes one joint different travels",
            SnapTightness.TIGHT,
            pair.joints[1].tightness,
        )
        assertEquals(SnapJoint.JointHalf.HIGH, pair.joints[1].beamHalf)
        assertEquals(2, pair.joints[1].barbs)
        assertEquals(SnapFacing.OPPOSITE, pair.joints[1].facing)
        assertFalse("with the side the tap landed on, not an object id", pair.joints[1].onLowHalf)
        assertEquals(28f, pair.joints[1].anchorXMm, 0f)
    }

    /**
     * A descriptor written before the record existed has a snap object with only the pair in
     * it. It must read as exactly that - the pair, with no joints - because a build that
     * never remembered joints cannot have any to offer.
     */
    @Test
    fun aDescriptorFromBeforeTheRecordDecodesAsAPairWithNoJoints() {
        val files = createTempDirectory("enderslicer-workspace-old-snap").toFile()
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
                snap = WorkspaceStateStore.SnapState(
                    lowHalfId = "low-id",
                    highHalfId = "high-id",
                    axis = ModelPlacement.Axis.Z,
                    lowFaceMm = 20f,
                    highFaceMm = 20f,
                    lowSourcePath = "pair-low.stl",
                    highSourcePath = "pair-high.stl",
                    scale = 1.5f,
                    joints = listOf(jointAt(1f, 2f, 3f)),
                ),
            ),
        )
        stripTheRecordKeys(workspaceFile(files))

        val restored = requireNotNull(store.load())

        val pair = requireNotNull(restored.snap) { "the pair itself is untouched" }
        assertEquals("low-id", pair.lowHalfId)
        assertEquals(20f, pair.lowFaceMm, 0f)
        assertFalse("there is no record in what that build wrote", pair.hasRecord)
        assertNull(pair.lowSourcePath)
        assertTrue(pair.joints.isEmpty())
        assertEquals("and the panel opens at its own defaults", 1f, pair.scale, 0f)
    }

    /**
     * The record is optional, so damage to it costs the record - never the pair, and never the
     * plate. A joint whose enums do not exist is what a hand edit or a future build looks
     * like, and the whole record goes rather than a joint being silently left out of a seam
     * the user is about to apply.
     */
    @Test
    fun aDamagedRecordCostsTheJointsAndNeverThePair() {
        val files = createTempDirectory("enderslicer-workspace-damaged-record").toFile()
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
                snap = WorkspaceStateStore.SnapState(
                    lowHalfId = "low-id",
                    highHalfId = "high-id",
                    axis = ModelPlacement.Axis.Z,
                    lowFaceMm = 20f,
                    highFaceMm = 20f,
                    lowSourcePath = "pair-low.stl",
                    highSourcePath = "pair-high.stl",
                    joints = listOf(jointAt(1f, 2f, 3f)),
                ),
            ),
        )
        val root = JSONObject(workspaceFile(files).readText())
        root.getJSONObject("snap").getJSONArray("joints").getJSONObject(0).put("beamHalf", "SIDEWAYS")
        workspaceFile(files).writeText(root.toString())

        val restored = requireNotNull(store.load())

        val pair = requireNotNull(restored.snap) { "the pair is still the pair" }
        assertFalse("but its record cannot be trusted", pair.hasRecord)
        assertTrue(pair.joints.isEmpty())
        assertEquals("and the plate is untouched", 2, restored.models.size)
    }

    /** One recorded joint, in the shape the descriptor writes. */
    private fun jointAt(x: Float, y: Float, z: Float) = WorkspaceStateStore.SnapState.JointState(
        anchorXMm = x,
        anchorYMm = y,
        anchorZMm = z,
        onLowHalf = true,
        beamHalf = SnapJoint.JointHalf.LOW,
        tightness = SnapTightness.LOOSE,
        barbs = 1,
        facing = SnapFacing.SAME,
    )

    /** The descriptor as a build without the record wrote it: the pair, and none of its keys. */
    private fun stripTheRecordKeys(file: File) {
        val root = JSONObject(file.readText())
        val snap = root.getJSONObject("snap")
        listOf(
            "lowSourcePath", "highSourcePath", "scale", "fullJoint", "socketRamp",
            "hookLengthMm", "hookThicknessMm", "hookLipMm", "joints",
        ).forEach { snap.remove(it) }
        file.writeText(root.toString())
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
