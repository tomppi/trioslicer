package com.tomppi.enderslicer.data

import android.content.Context
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.supportpaint.SupportPaintState
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
    )

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
    )

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
        )
    }

    private fun encodeEntry(entry: Entry): JSONObject = JSONObject()
        .put("modelPath", entry.modelPath)
        .put("modelDisplayName", entry.modelDisplayName)
        .put("placement", encodePlacement(entry.placement))
        .put("supportPaint", encodePaint(entry.supportPaint))

    private fun decodeEntry(json: JSONObject): Entry = Entry(
        modelPath = json.getString("modelPath"),
        modelDisplayName = json.getString("modelDisplayName"),
        placement = decodePlacement(json.getJSONObject("placement")),
        supportPaint = decodeSupportPaint(json.optJSONObject("supportPaint")),
    )

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
