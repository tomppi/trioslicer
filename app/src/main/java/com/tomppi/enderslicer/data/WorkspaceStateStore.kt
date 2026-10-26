package com.tomppi.enderslicer.data

import android.content.Context
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import com.tomppi.enderslicer.viewer.SnapFacing
import com.tomppi.enderslicer.viewer.SnapJoint
import com.tomppi.enderslicer.viewer.SnapJointStyle
import com.tomppi.enderslicer.viewer.SnapLibraryShape
import com.tomppi.enderslicer.viewer.SnapTightness
import com.tomppi.enderslicer.viewer.Vec3
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Atomic, bounded persistence for the active model workspace. */
class WorkspaceStateStore(private val filesDirectory: File) {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    /** One model of the plate, as it was when the workspace was saved. */
    data class Entry(
        val modelPath: String,
        val modelDisplayName: String,
        val placement: ModelPlacement,
        val supportPaint: SupportPaintState = SupportPaintState(),
        /**
         * The object's identity on the plate, so a pair of halves can be named again after a
         * relaunch. Null for entries written before identities were saved, and for the model of
         * an import that has not been assigned one yet: those restore under a fresh id.
         */
        val id: String? = null,
    )

    /**
     * The split pair a snap fit is offered on: the two halves, the assembly axis, and each
     * half's own mating face along it.
     *
     * Only the ids travel; every other field of the pair is derived from the plate the restore
     * builds. A pair naming an object the descriptor does not hold degrades to no snap rather
     * than failing the load, so a damaged descriptor costs the tool and never the plate.
     *
     * A pair that has been jointed carries its own RECORD as well: the two unjointed halves
     * every joint on it was built from, in the pair's assembled frame, the panel values those
     * joints were built at, and the joints themselves. That is what makes a joint editable
     * after the fact - the boolean is baked into the meshes on the plate, and a pocket that
     * has been cut cannot be un-cut, so Apply has to start from these files rather than from
     * what the plate holds. Every one of these fields is optional on the way in: a descriptor
     * written before the record existed decodes as the pair with no recorded joints, which is
     * exactly what that build knew.
     */
    data class SnapState(
        val lowHalfId: String,
        val highHalfId: String,
        val axis: ModelPlacement.Axis,
        val lowFaceMm: Float,
        val highFaceMm: Float,
        /** The pair's own unjointed halves, in its assembled frame; null when none recorded. */
        val lowSourcePath: String? = null,
        val highSourcePath: String? = null,
        /** The panel values the recorded joints were built at. */
        val scale: Float = 1f,
        val fullJoint: Boolean = false,
        val socketRamp: Boolean = false,
        val hookLengthMm: Float? = null,
        val hookThicknessMm: Float? = null,
        val hookLipMm: Float? = null,
        /** The joints built on the pair, in the pair's own frame; empty when none recorded. */
        val joints: List<JointState> = emptyList(),
    ) {
        /** True when both source halves are named: the pair carries a record. */
        val hasRecord: Boolean
            get() = !lowSourcePath.isNullOrBlank() && !highSourcePath.isNullOrBlank()

        /**
         * One joint as the record keeps it: where it sits in the pair's own frame - a plate
         * point travels with the packer, an own-frame one does not - which half the tap landed
         * on by SIDE rather than by id (every apply gives the halves fresh ids), and the four
         * choices that make one joint different from its neighbour.
         */
        data class JointState(
            val anchorXMm: Float,
            val anchorYMm: Float,
            val anchorZMm: Float,
            val onLowHalf: Boolean,
            /**
             * How deep the joint sits along the seam's own axis. Absent in a
             * record written before the move gizmo's depth arrow existed, and
             * then the joint is the one on the mating plane, which is what it
             * was.
             */
            val depthMm: Float = 0f,
            val beamHalf: SnapJoint.JointHalf,
            val tightness: SnapTightness,
            val barbs: Int,
            val facing: SnapFacing,
            /**
             * Which joint this is, and for a wall lip the two sizes the user set. All
             * three are optional on the way in: a record written before the lip
             * existed carries none of them and decodes as the cantilever it was.
             */
            val style: SnapJointStyle = SnapJointStyle.CANTILEVER,
            /**
             * Which bundled library shape a library hook joint was grafted from.
             * A joint recorded before the style existed carries none and decodes
             * as the shape the picker starts on, which is what a cantilever or a
             * lip joint ignores anyway.
             */
            val libraryShape: SnapLibraryShape = SnapLibraryShape.CANTILEVER01,
            val lipEngagementMm: Float? = null,
            val lipToothHeightMm: Float? = null,
            val lipWallMm: Float? = null,
            val lipRecessExtraMm: Float? = null,
            val lipCutWidthMm: Float? = null,
            val lipCutDepthMm: Float? = null,
            /** The cuts in the pair's own assembled frame, which the plate cannot move. */
            val lipCuts: List<Vec3> = emptyList(),
            /** The direction each slit runs in, in the pair's own frame, lined up with [lipCuts]. */
            val lipCutDirections: List<Vec3> = emptyList(),
        )
    }

    /**
     * The saved plate.
     *
     * [modelPath], [modelDisplayName], [placement] and [supportPaint] describe the first model and
     * are what a descriptor written before multi-object printing held; [models] is the whole plate
     * and is empty for those older descriptors, which restore as a one-model plate.
     */
    data class Snapshot(
        val modelPath: String,
        val modelDisplayName: String,
        val placement: ModelPlacement,
        val configurationFingerprint: String,
        val supportPaint: SupportPaintState = SupportPaintState(),
        val models: List<Entry> = emptyList(),
        /** The split pair the snap fit was offered on, or null when there was none. */
        val snap: SnapState? = null,
        /**
         * The CAD document the plate was showing, or null when the model was a mesh.
         *
         * The document's source of truth is [CadState.designPath], an engine design file; the
         * triangles the plate draws are a display cache, as [CadState.displayMeshPath] names.
         */
        val cad: CadState? = null,
    )

    /**
     * The workspace half of a live CAD document.
     *
     * It is deliberately not a model file: the design is the document, and the tessellation
     * beside it is only what the last session's view drew. A restore reads the design back
     * through the engine (the Tessellated cache is a convenience for the frame before the
     * engine starts); Slice is what writes an STL, and its path is recorded here so a later
     * Slice can say honestly whether it reused that export.
     */
    data class CadState(
        val name: String,
        val designPath: String,
        /** The STEP/IGES/3MF/OBJ/glTF file the document was opened from, when it was. */
        val sourcePath: String? = null,
        /** The display cache the restored plate draws until the engine exports again. */
        val displayMeshPath: String? = null,
        val displayRevision: Long = 0L,
        val triangleCount: Int = 0,
        val bodyCount: Int = 0,
        /** The plate object drawing the display cache, so a repaint replaces it. */
        val displayObjectId: String? = null,
        /** One plate object per body of the live document, in the engine's body order. */
        val parts: List<PartState> = emptyList(),
        /** True when the plate holds STLs the human converted the document into. */
        val converted: Boolean = false,
        /** The display revision those STLs were exported from. */
        val convertedRevision: Long = -1L,
        /** One STL-backed plate object per part of the converted plate. */
        val convertedParts: List<PartState> = emptyList(),
        /** True when the plate held a split/snap layout made from the previous shape. */
        val derivedStale: Boolean = false,
        val staleReason: String? = null,
        val sliceStlPath: String? = null,
        val sliceExportRevision: Long = -1L,
        val sliceExportAtMillis: Long = 0L,
        val sliceTriangles: Int = 0,
    ) {
        /** One body of the document and the plate object that draws it. */
        data class PartState(
            val body: String,
            val objectId: String,
        )
    }

    private val stateDirectory = File(filesDirectory, "persistent-state").apply { mkdirs() }
    private val workspaceFile = File(stateDirectory, "current-workspace.json")

    @Synchronized
    fun save(snapshot: Snapshot) {
        validate(snapshot)
        val temporary = File(stateDirectory, "current-workspace.next")
        val backup = File(stateDirectory, "current-workspace.previous")
        temporary.delete()
        backup.delete()

        val bytes = encode(snapshot).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_WORKSPACE_BYTES) { "Workspace descriptor exceeds its safety limit" }
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        check(temporary.isFile && temporary.length() == bytes.size.toLong()) {
            "Unable to stage the workspace descriptor"
        }

        try {
            if (workspaceFile.exists()) {
                check(
                    workspaceFile.renameTo(backup) ||
                        workspaceFile.copyTo(backup, overwrite = true).let { workspaceFile.delete(); true },
                ) { "Unable to preserve the previous workspace descriptor" }
            }
            try {
                check(
                    temporary.renameTo(workspaceFile) ||
                        temporary.copyTo(workspaceFile, overwrite = true).let { temporary.delete(); true },
                ) { "Unable to commit the workspace descriptor" }
            } catch (error: Throwable) {
                workspaceFile.delete()
                if (backup.exists()) {
                    backup.renameTo(workspaceFile) ||
                        backup.copyTo(workspaceFile, overwrite = true).let { backup.delete(); true }
                }
                throw error
            }
            backup.delete()
        } finally {
            temporary.delete()
            if (backup.exists() && workspaceFile.exists()) backup.delete()
        }
    }

    @Synchronized
    fun load(): Snapshot? {
        val backup = File(stateDirectory, "current-workspace.previous")
        val staged = File(stateDirectory, "current-workspace.next")
        if ((!workspaceFile.isFile || workspaceFile.length() == 0L) && backup.isFile && backup.length() > 0L) {
            if (workspaceFile.exists()) workspaceFile.delete()
            backup.renameTo(workspaceFile) ||
                backup.copyTo(workspaceFile, overwrite = true).let { backup.delete(); true }
        }
        if (!workspaceFile.isFile || workspaceFile.length() == 0L) {
            if (staged.isFile && staged.length() > 0L) {
                staged.renameTo(workspaceFile) ||
                    staged.copyTo(workspaceFile, overwrite = true).let { staged.delete(); true }
            }
            if (!workspaceFile.isFile || workspaceFile.length() == 0L) return null
        }
        require(workspaceFile.length() <= MAX_WORKSPACE_BYTES) {
            "Saved workspace descriptor exceeds its safety limit"
        }
        val snapshot = runCatching {
            decode(JSONObject(workspaceFile.readText()))
        }.getOrElse { error ->
            if (backup.isFile && backup.length() > 0L) {
                workspaceFile.delete()
                backup.renameTo(workspaceFile) ||
                    backup.copyTo(workspaceFile, overwrite = true).let { backup.delete(); true }
                decode(JSONObject(workspaceFile.readText()))
            } else {
                throw error
            }
        }
        validate(snapshot)
        return snapshot
    }

    @Synchronized
    fun clear() {
        workspaceFile.delete()
        File(stateDirectory, "current-workspace.next").delete()
        File(stateDirectory, "current-workspace.previous").delete()
    }

    private fun validate(snapshot: Snapshot) {
        require(snapshot.configurationFingerprint.matches(FINGERPRINT_PATTERN)) {
            "Workspace configuration fingerprint is invalid"
        }
        validateEntry(snapshot.modelPath, snapshot.modelDisplayName, snapshot.supportPaint)
        snapshot.models.forEach { entry ->
            validateEntry(entry.modelPath, entry.modelDisplayName, entry.supportPaint)
        }
        // The design file is not a model file: it lives in the engine's own workspace inside
        // the app's files directory, and it is the one piece of the descriptor that must never
        // name a path outside it.
        snapshot.cad?.let { cad ->
            require(cad.name.isNotBlank() && cad.name.length <= MAX_DISPLAY_NAME_LENGTH) {
                "Workspace CAD document name is invalid"
            }
            val design = File(cad.designPath).canonicalFile
            val files = filesDirectory.canonicalFile
            require(design.path.startsWith(files.path + File.separator)) {
                "Workspace CAD document is outside the app's files directory"
            }
        }
    }

    /** Every model on the plate has to be one of ours, present and unpainted into nonsense. */
    private fun validateEntry(path: String, displayName: String, paint: SupportPaintState) {
        require(displayName.isNotBlank() && displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "Workspace model name is invalid"
        }
        val model = File(path).canonicalFile
        val modelDirectory = File(filesDirectory, "models").canonicalFile
        require(model.path.startsWith(modelDirectory.path + File.separator)) {
            "Workspace model is outside the private model directory"
        }
        require(model.isFile && model.length() > 0L) { "Workspace model is unavailable" }
        require(paint.enforcerTriangles.all { it >= 0 }) {
            "Workspace paint contains a negative enforcer triangle index"
        }
        require(paint.blockerTriangles.all { it >= 0 }) {
            "Workspace paint contains a negative blocker triangle index"
        }
    }

    private fun encode(snapshot: Snapshot): JSONObject {
        val root = JSONObject()
            .put("version", VERSION)
            .put("modelPath", snapshot.modelPath)
            .put("modelDisplayName", snapshot.modelDisplayName)
            .put("placement", encodePlacement(snapshot.placement))
            .put("supportPaint", encodePaint(snapshot.supportPaint))
            .put("configurationFingerprint", snapshot.configurationFingerprint)
        // Written only when there is more than the legacy fields describe, so a one-model
        // descriptor stays byte-shaped the way older builds wrote it.
        if (snapshot.models.isNotEmpty()) {
            root.put("models", JSONArray().apply { snapshot.models.forEach { put(encodeEntry(it)) } })
        }
        // Same rule for the snap pair: nothing is written when there was none, so a descriptor
        // from a build without the tool stays byte-shaped the way that build wrote it.
        snapshot.snap?.let { snap ->
            root.put(
                "snap",
                JSONObject()
                    .put("lowHalfId", snap.lowHalfId)
                    .put("highHalfId", snap.highHalfId)
                    .put("axis", snap.axis.name)
                    .put("lowFaceMm", snap.lowFaceMm.toDouble())
                    .put("highFaceMm", snap.highFaceMm.toDouble())
                    // The record is written only when the pair carries one, so a pair that has
                    // never been jointed keeps the byte shape older builds wrote - and reads
                    // back the same way in one.
                    .apply {
                        if (!snap.hasRecord) return@apply
                        put("lowSourcePath", snap.lowSourcePath)
                        put("highSourcePath", snap.highSourcePath)
                        put("scale", snap.scale.toDouble())
                        put("fullJoint", snap.fullJoint)
                        put("socketRamp", snap.socketRamp)
                        snap.hookLengthMm?.let { put("hookLengthMm", it.toDouble()) }
                        snap.hookThicknessMm?.let { put("hookThicknessMm", it.toDouble()) }
                        snap.hookLipMm?.let { put("hookLipMm", it.toDouble()) }
                        put(
                            "joints",
                            JSONArray().apply {
                                snap.joints.forEach { joint ->
                                    put(
                                        JSONObject()
                                            .put("x", joint.anchorXMm.toDouble())
                                            .put("y", joint.anchorYMm.toDouble())
                                            .put("z", joint.anchorZMm.toDouble())
                                            .put("onLowHalf", joint.onLowHalf)
                                            // Written only when the joint is off the
                                            // mating plane, so every record that was
                                            // on it keeps the byte shape older builds
                                            // wrote.
                                            .apply {
                                                if (joint.depthMm.isFinite() && joint.depthMm != 0f) {
                                                    put("depthMm", joint.depthMm.toDouble())
                                                }
                                            }
                                            .put("beamHalf", joint.beamHalf.name)
                                            .put("tightness", joint.tightness.name)
                                            .put("barbs", joint.barbs)
                                            .put("facing", joint.facing.name)
                                            // Written only for a lip, so a cantilever
                                            // joint keeps the byte shape older builds wrote.
                                            .apply {
                                                if (joint.style == SnapJointStyle.CANTILEVER) return@apply
                                                put("style", joint.style.name)
                                                // Written only for a library hook, so the two
                                                // styles that came before keep the byte shape
                                                // their own builds wrote.
                                                if (joint.style == SnapJointStyle.LIBRARY_HOOK) {
                                                    put("libraryShape", joint.libraryShape.name)
                                                }
                                                joint.lipEngagementMm?.let { put("lipEngagementMm", it.toDouble()) }
                                                joint.lipToothHeightMm?.let { put("lipToothHeightMm", it.toDouble()) }
                                                joint.lipWallMm?.let { put("lipWallMm", it.toDouble()) }
                                                joint.lipRecessExtraMm?.let { put("lipRecessExtraMm", it.toDouble()) }
                                                joint.lipCutWidthMm?.let { put("lipCutWidthMm", it.toDouble()) }
                                                joint.lipCutDepthMm?.let { put("lipCutDepthMm", it.toDouble()) }
                                                // Only when there are cuts, so a lip that
                                                // has none keeps the byte shape it had.
                                                if (joint.lipCuts.isNotEmpty()) {
                                                    put("lipCuts", vectorArray(joint.lipCuts))
                                                }
                                                // A stroke's own directions, only for the
                                                // cuts that carry one and only when there
                                                // are any: an automatic cut writes nothing.
                                                if (joint.lipCutDirections.any { it.isFinite() && it.length() > 0f }) {
                                                    put("lipCutDirections", vectorArray(joint.lipCutDirections))
                                                }
                                            },
                                    )
                                }
                            },
                        )
                    },
            )
        }
        // The CAD document is written beside the plate it was shown on, never as one of the
        // plate's models: the design is the document, and the display cache entry is only the
        // frame the last session drew. Written only when a document is live, so a mesh plate's
        // descriptor keeps the byte shape it had.
        snapshot.cad?.let { cad ->
            root.put(
                "cad",
                JSONObject()
                    .put("name", cad.name)
                    .put("designPath", cad.designPath)
                    .put("displayRevision", cad.displayRevision)
                    .put("triangleCount", cad.triangleCount)
                    .put("bodyCount", cad.bodyCount)
                    .put("sliceExportRevision", cad.sliceExportRevision)
                    .put("sliceExportAtMillis", cad.sliceExportAtMillis)
                    .put("sliceTriangles", cad.sliceTriangles)
                    .put("derivedStale", cad.derivedStale)
                    .apply {
                        cad.sourcePath?.let { put("sourcePath", it) }
                        cad.displayMeshPath?.let { put("displayMeshPath", it) }
                        cad.displayObjectId?.let { put("displayObjectId", it) }
                        cad.sliceStlPath?.let { put("sliceStlPath", it) }
                        cad.staleReason?.let { put("staleReason", it) }
                        if (cad.parts.isNotEmpty()) put("parts", encodeParts(cad.parts))
                        if (cad.converted) {
                            put("converted", true)
                            put("convertedRevision", cad.convertedRevision)
                        }
                        if (cad.convertedParts.isNotEmpty()) {
                            put("convertedParts", encodeParts(cad.convertedParts))
                        }
                    },
            )
        }
        return root
    }

    private fun decode(root: JSONObject): Snapshot {
        require(root.getInt("version") == VERSION) { "Unsupported workspace descriptor version" }
        val models = root.optJSONArray("models")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::decodeEntry)
            }
        }.orEmpty()
        return Snapshot(
            modelPath = root.getString("modelPath"),
            modelDisplayName = root.getString("modelDisplayName"),
            placement = decodePlacement(root.getJSONObject("placement")),
            configurationFingerprint = root.getString("configurationFingerprint"),
            supportPaint = decodeSupportPaint(root.optJSONObject("supportPaint")),
            models = models,
            snap = decodeSnap(root.optJSONObject("snap"), models),
            cad = decodeCad(root.optJSONObject("cad")),
        )
    }

    /**
     * The saved CAD document, or null when the descriptor has none - or carries one without
     * the two things that make it a document. Every field is read leniently: a damaged record
     * costs the CAD state, never the plate the rest of the descriptor describes.
     */
    private fun decodeCad(json: JSONObject?): CadState? {
        if (json == null) return null
        return runCatching {
            val name = json.optString("name").takeIf { it.isNotBlank() } ?: return null
            val designPath = json.optString("designPath").takeIf { it.isNotBlank() } ?: return null
            CadState(
                name = name,
                designPath = designPath,
                sourcePath = json.optString("sourcePath").takeIf { it.isNotBlank() },
                displayMeshPath = json.optString("displayMeshPath").takeIf { it.isNotBlank() },
                displayRevision = json.optLong("displayRevision", 0L).coerceAtLeast(0L),
                triangleCount = json.optInt("triangleCount", 0).coerceAtLeast(0),
                bodyCount = json.optInt("bodyCount", 0).coerceAtLeast(0),
                displayObjectId = json.optString("displayObjectId").takeIf { it.isNotBlank() },
                parts = decodeParts(json.optJSONArray("parts")),
                converted = json.optBoolean("converted", false),
                convertedRevision = json.optLong("convertedRevision", -1L),
                convertedParts = decodeParts(json.optJSONArray("convertedParts")),
                derivedStale = json.optBoolean("derivedStale", false),
                staleReason = json.optString("staleReason").takeIf { it.isNotBlank() },
                sliceStlPath = json.optString("sliceStlPath").takeIf { it.isNotBlank() },
                sliceExportRevision = json.optLong("sliceExportRevision", -1L),
                sliceExportAtMillis = json.optLong("sliceExportAtMillis", 0L).coerceAtLeast(0L),
                sliceTriangles = json.optInt("sliceTriangles", 0).coerceAtLeast(0),
            )
        }.getOrNull()
    }

    private fun encodeParts(parts: List<CadState.PartState>): JSONArray = JSONArray().apply {
        for (part in parts) {
            put(JSONObject().put("body", part.body).put("objectId", part.objectId))
        }
    }

    /** Lenient: a damaged part row costs that row, never the document's other state. */
    private fun decodeParts(array: JSONArray?): List<CadState.PartState> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val body = json.optString("body").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val objectId = json.optString("objectId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CadState.PartState(body, objectId)
        }
    }

    private fun encodeEntry(entry: Entry): JSONObject = JSONObject()
        .put("modelPath", entry.modelPath)
        .put("modelDisplayName", entry.modelDisplayName)
        .put("placement", encodePlacement(entry.placement))
        .put("supportPaint", encodePaint(entry.supportPaint))
        .apply { entry.id?.let { put("id", it) } }

    private fun decodeEntry(json: JSONObject): Entry = Entry(
        modelPath = json.getString("modelPath"),
        modelDisplayName = json.getString("modelDisplayName"),
        placement = decodePlacement(json.getJSONObject("placement")),
        supportPaint = decodeSupportPaint(json.optJSONObject("supportPaint")),
        id = json.optString("id").takeIf { it.isNotBlank() },
    )

    /**
     * The saved snap pair, or null when the descriptor does not hold one - or does not hold it
     * completely. Every field is read leniently on purpose: an older descriptor has no "snap"
     * at all, and a damaged one may name halves that are not on the plate. Both mean the same
     * thing to the caller - no pair to offer - and neither may cost the models the file is for.
     */
    private fun decodeSnap(json: JSONObject?, models: List<Entry>): SnapState? {
        if (json == null) return null
        return runCatching {
            val low = json.optString("lowHalfId").takeIf { it.isNotBlank() } ?: return null
            val high = json.optString("highHalfId").takeIf { it.isNotBlank() } ?: return null
            if (low == high) return null
            if (models.none { it.id == low } || models.none { it.id == high }) return null
            val axis = runCatching { ModelPlacement.Axis.valueOf(json.optString("axis")) }
                .getOrNull() ?: return null
            val lowFace = json.optDouble("lowFaceMm").takeIf { it.isFinite() }?.toFloat() ?: return null
            val highFace = json.optDouble("highFaceMm").takeIf { it.isFinite() }?.toFloat() ?: return null
            // The record is decoded on its own and leniently: a descriptor written before it
            // existed has none of these keys, and a damaged one costs the record - never the
            // pair, and never the plate.
            val record = decodeRecord(json)
            SnapState(
                lowHalfId = low,
                highHalfId = high,
                axis = axis,
                lowFaceMm = lowFace,
                highFaceMm = highFace,
                lowSourcePath = record?.lowSourcePath,
                highSourcePath = record?.highSourcePath,
                scale = record?.scale ?: 1f,
                fullJoint = record?.fullJoint ?: false,
                socketRamp = record?.socketRamp ?: false,
                hookLengthMm = record?.hookLengthMm,
                hookThicknessMm = record?.hookThicknessMm,
                hookLipMm = record?.hookLipMm,
                joints = record?.joints.orEmpty(),
            )
        }.getOrNull()
    }

    /** A decoded record: the pair's own source halves, the panel values and the joints. */
    private data class Record(
        val lowSourcePath: String,
        val highSourcePath: String,
        val scale: Float,
        val fullJoint: Boolean,
        val socketRamp: Boolean,
        val hookLengthMm: Float?,
        val hookThicknessMm: Float?,
        val hookLipMm: Float?,
        val joints: List<SnapState.JointState>,
    )

    /**
     * The pair's own record, or null when the descriptor carries none - or carries a damaged
     * one. Nothing here throws: every field is read for what it is worth, and a joint that
     * cannot be read costs the whole record rather than silently leaving a joint out of a seam
     * the user will then apply.
     */
    private fun decodeRecord(json: JSONObject): Record? = runCatching {
        val low = json.optString("lowSourcePath").takeIf { it.isNotBlank() } ?: return null
        val high = json.optString("highSourcePath").takeIf { it.isNotBlank() } ?: return null
        val array = json.optJSONArray("joints") ?: JSONArray()
        val joints = (0 until array.length()).map { index ->
            decodeJoint(array.getJSONObject(index))
        }
        Record(
            lowSourcePath = low,
            highSourcePath = high,
            scale = json.optDouble("scale", 1.0).takeIf { it.isFinite() && it > 0.0 }
                ?.toFloat() ?: 1f,
            fullJoint = json.optBoolean("fullJoint", false),
            socketRamp = json.optBoolean("socketRamp", false),
            hookLengthMm = json.optDouble("hookLengthMm").takeIf { it.isFinite() }?.toFloat(),
            hookThicknessMm = json.optDouble("hookThicknessMm").takeIf { it.isFinite() }?.toFloat(),
            hookLipMm = json.optDouble("hookLipMm").takeIf { it.isFinite() }?.toFloat(),
            joints = joints,
        )
    }.getOrNull()

    /** One recorded joint; throws on anything unreadable, which [decodeRecord] turns into none. */
    private fun decodeJoint(json: JSONObject): SnapState.JointState = SnapState.JointState(
        anchorXMm = json.getDouble("x").toFloat(),
        anchorYMm = json.getDouble("y").toFloat(),
        anchorZMm = json.getDouble("z").toFloat(),
        onLowHalf = json.getBoolean("onLowHalf"),
        // Read leniently: a joint recorded before the depth arrow existed sits on
        // the mating plane, and an unreadable number costs the depth rather than
        // the joint.
        depthMm = json.optDouble("depthMm").takeIf { it.isFinite() }?.toFloat() ?: 0f,
        beamHalf = SnapJoint.JointHalf.valueOf(json.getString("beamHalf")),
        tightness = SnapTightness.valueOf(json.getString("tightness")),
        barbs = json.getInt("barbs"),
        facing = SnapFacing.valueOf(json.getString("facing")),
        // The style is read leniently on purpose: a joint recorded before the wall
        // lip existed has no "style" key at all, and an unreadable one costs the
        // style rather than the joint - the cantilever is what it must have been.
        style = runCatching { SnapJointStyle.valueOf(json.optString("style")) }
            .getOrDefault(SnapJointStyle.CANTILEVER),
        // The bundled shape, read the same way: a joint recorded before the
        // library existed has no key, and an unreadable one costs the shape
        // rather than the joint.
        libraryShape = runCatching { SnapLibraryShape.valueOf(json.optString("libraryShape")) }
            .getOrDefault(SnapLibraryShape.CANTILEVER01),
        lipEngagementMm = json.optDouble("lipEngagementMm").takeIf { it.isFinite() }?.toFloat(),
        lipToothHeightMm = json.optDouble("lipToothHeightMm").takeIf { it.isFinite() }?.toFloat(),
        // The two the ring is sized by: absent in a record written before they
        // existed, which is the 2.00 mm wall and no extra depth - the joint as it
        // was.
        lipWallMm = json.optDouble("lipWallMm").takeIf { it.isFinite() }?.toFloat(),
        lipRecessExtraMm = json.optDouble("lipRecessExtraMm").takeIf { it.isFinite() }?.toFloat(),
        // The cuts, their width and their depth: a record written before they
        // existed simply has none, which is the uncut ring it was.
        lipCutWidthMm = json.optDouble("lipCutWidthMm").takeIf { it.isFinite() }?.toFloat(),
        lipCutDepthMm = json.optDouble("lipCutDepthMm").takeIf { it.isFinite() }?.toFloat(),
        lipCuts = decodeVectors(json.optJSONArray("lipCuts")),
        lipCutDirections = decodeVectors(json.optJSONArray("lipCutDirections")),
    )

    /** A list of points or directions as the record writes them; empty when unreadable. */
    private fun decodeVectors(array: JSONArray?): List<Vec3> = array?.let { list ->
        (0 until list.length()).mapNotNull { at ->
            val entry = list.optJSONObject(at) ?: return@mapNotNull null
            val x = entry.optDouble("x").takeIf { it.isFinite() }?.toFloat() ?: return@mapNotNull null
            val y = entry.optDouble("y").takeIf { it.isFinite() }?.toFloat() ?: return@mapNotNull null
            val z = entry.optDouble("z").takeIf { it.isFinite() }?.toFloat() ?: return@mapNotNull null
            Vec3(x, y, z)
        }
    }.orEmpty()

    /** The same list the other way: an array of {x, y, z} objects. */
    private fun vectorArray(vectors: List<Vec3>): JSONArray = JSONArray().apply {
        for (vector in vectors) {
            put(
                JSONObject()
                    .put("x", vector.x.toDouble())
                    .put("y", vector.y.toDouble())
                    .put("z", vector.z.toDouble()),
            )
        }
    }

    private fun encodePlacement(placement: ModelPlacement): JSONObject = JSONObject()
        .put("linear", JSONArray(placement.linear))
        .put("centerXmm", placement.centerXmm)
        .put("centerYmm", placement.centerYmm)
        .put("baseZmm", placement.baseZmm)
        .put("source", placement.source)

    private fun decodePlacement(json: JSONObject): ModelPlacement {
        val linearJson = json.getJSONArray("linear")
        require(linearJson.length() == 9) { "Workspace placement matrix must contain nine values" }
        return ModelPlacement(
            linear = List(9) { index -> linearJson.getDouble(index) },
            centerXmm = json.getDouble("centerXmm"),
            centerYmm = json.getDouble("centerYmm"),
            baseZmm = json.getDouble("baseZmm"),
            source = json.optString("source", "Restored workspace"),
        )
    }

    private fun encodePaint(paint: SupportPaintState): JSONObject = JSONObject()
        .put("enforcer", JSONArray(paint.enforcerTriangles.toList()))
        .put("blocker", JSONArray(paint.blockerTriangles.toList()))
        .put("brushRadiusMm", paint.brushRadiusMm)

    private fun decodeSupportPaint(json: JSONObject?): SupportPaintState {
        if (json == null) return SupportPaintState()
        val enforcer = json.optJSONArray("enforcer")?.let { array ->
            (0 until array.length()).map { array.getInt(it) }.toSet()
        } ?: emptySet()
        val blocker = json.optJSONArray("blocker")?.let { array ->
            (0 until array.length()).map { array.getInt(it) }.toSet()
        } ?: emptySet()
        val brush = json.optDouble("brushRadiusMm", SupportPaintState.DEFAULT_BRUSH_RADIUS_MM)
            .coerceIn(SupportPaintState.MIN_BRUSH_RADIUS_MM, SupportPaintState.MAX_BRUSH_RADIUS_MM)
        return SupportPaintState(
            enforcerTriangles = enforcer,
            blockerTriangles = blocker,
            brushRadiusMm = brush,
        )
    }

    companion object {
        fun fingerprint(vararg parts: Any?): String {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach { part ->
                val normalized = (part as? SlicerSettings)?.copy(
                    overriddenSettingKeys = part.overriddenSettingKeys.toSortedSet(),
                ) ?: part
                digest.update((normalized?.toString() ?: "<null>").toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
            }
            return digest.hexDigest()
        }

        private const val VERSION = 1
        private const val MAX_WORKSPACE_BYTES = 512 * 1024
        private const val MAX_DISPLAY_NAME_LENGTH = 512
        private val FINGERPRINT_PATTERN = Regex("[0-9a-f]{64}")
    }
}
