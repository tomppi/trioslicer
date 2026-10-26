package com.tomppi.enderslicer.solidcraft

import com.tomppi.enderslicer.model.PlateObject
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import com.tomppi.enderslicer.viewer.StlMesh

/** One body of the document, exported to an STL that is now the plate's own model. */
data class CadConvertedPart(
    val body: String,
    val mesh: StlMesh,
    /** Where the exported STL is, so the plate object can restore itself from it. */
    val stlPath: String,
)

/**
 * What a conversion puts on the plate: one STL-backed object per part, and the mapping that
 * names each one next time it is converted again.
 */
data class CadConvertPlan(
    val models: List<PlateObject>,
    val parts: List<CadPart>,
    val triangleCount: Int,
    /** Bodies whose support paint had to be cleared because the exported mesh changed size. */
    val paintDropped: List<String>,
)

/**
 * The deliberate step from a live CAD document to the STLs the slicer reads.
 *
 * A document is the engine's; a slicer takes triangles. The two are different things, and the
 * user asked for them to stay different: Slice does not convert, and nothing converts on its
 * own. This plan is what the human's own Convert to STL press produces - the document's parts
 * become ordinary plate models, at the placements the human had already given them, so the
 * parts keep the arrangement they had while they were the document instead of jumping back to
 * the design's coordinates.
 *
 * A second conversion replaces the previous converted objects in place (same ids, same
 * placements, paint kept while the triangle count matches). The document itself is untouched:
 * it stays open in the engine, and an agent edit afterwards marks the plate stale rather than
 * pasting the new shape under the human.
 */
object CadConvert {

    /** The id prefix a converted mesh object carries, so a later conversion can keep it. */
    const val CONVERTED_ID_PREFIX = "stl:"

    /**
     * Plans the converted plate.
     *
     * [parts] are the exported bodies in the engine's own order; [models] is the plate as it
     * is; [replacing] is the mapping of the objects the conversion replaces - the live document
     * parts on the first press, the previous converted objects on a later one.
     */
    fun plan(
        parts: List<CadConvertedPart>,
        models: List<PlateObject>,
        replacing: List<CadPart>,
    ): CadConvertPlan {
        val replacingByBody = replacing.associateBy { it.body }
        val replacingIds = replacing.map { it.objectId }.toSet()
        val usedIds = models
            .map { it.id }
            .filterNot { it in replacingIds }
            .toMutableSet()
        val planned = LinkedHashMap<String, PlateObject>()
        val convertedParts = ArrayList<CadPart>(parts.size)
        val paintDropped = ArrayList<String>()
        var triangleCount = 0
        for (part in parts) {
            if (part.mesh.triangleCount <= 0) continue
            triangleCount += part.mesh.triangleCount
            val existing = replacingByBody[part.body]?.objectId
                ?.let { id -> models.firstOrNull { it.id == id } }
            // The first conversion replaces the document's own objects with new mesh objects -
            // the live part ids go away with the document shapes. A later conversion replaces
            // its own converted objects, so those keep their ids and stay where they are.
            val reuseConvertedId = existing != null && existing.id.startsWith(CONVERTED_ID_PREFIX)
            val id = if (existing != null && reuseConvertedId) {
                usedIds.add(existing.id)
                existing.id
            } else {
                CadPartPublish.uniqueId(CONVERTED_ID_PREFIX + part.body, usedIds)
            }
            val placement = existing?.placement ?: CadPartPublish.documentCoordinates(part.mesh)
            val keepsPaint = existing != null &&
                existing.sourceMesh.triangleCount == part.mesh.triangleCount
            if (existing != null && !keepsPaint && existing.supportPaint.hasPaint()) {
                paintDropped += part.body
            }
            val object3d = PlateObject(
                id = id,
                name = part.body,
                sourceMesh = part.mesh,
                mesh = placement.transformed(part.mesh),
                sourcePath = part.stlPath,
                placement = placement,
                supportPaint = if (keepsPaint) existing!!.supportPaint else SupportPaintState(),
            )
            planned[part.body] = object3d
            convertedParts += CadPart(body = part.body, objectId = id)
        }
        val replacements = planned.values.associateBy { it.id }
        val emitted = HashSet<String>()
        val result = ArrayList<PlateObject>(models.size + planned.size)
        for (model in models) {
            val replacement = replacements[model.id]
            if (replacement != null) {
                result += replacement
                emitted += replacement.id
                continue
            }
            if (model.id in replacingIds) continue
            result += model
        }
        for (object3d in planned.values) {
            if (emitted.add(object3d.id)) result += object3d
        }
        return CadConvertPlan(
            models = result,
            parts = convertedParts,
            triangleCount = triangleCount,
            paintDropped = paintDropped,
        )
    }
}
