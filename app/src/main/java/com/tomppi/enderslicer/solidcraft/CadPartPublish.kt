package com.tomppi.enderslicer.solidcraft

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.PlateObject
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import com.tomppi.enderslicer.viewer.StlMesh
import kotlin.math.abs

/**
 * What one republish of the live CAD document does to the plate.
 *
 * [models] is the whole plate after the publish, [parts] names one body per plate object, and
 * [paintDropped] lists the bodies whose support paint had to be cleared because their
 * tessellation changed size (the same rule a mesh repaint has always had).
 */
data class CadPartPlan(
    val models: List<PlateObject>,
    val parts: List<CadPart>,
    val triangleCount: Int,
    val paintDropped: List<String>,
)

/**
 * The plate's half of a live CAD document: one plate object per engine body.
 *
 * A document is a design with parts - a box and its lid, a case and its cover - and the plate
 * has always said so for an imported mesh (the split tool makes two objects out of one). A
 * single composed mesh hid that: only the whole design could be moved, painted or arranged.
 * One object per body is what makes the document's parts behave like the parts beside them,
 * while the document itself stays one document (one engine session, one design file, one
 * identity for the agent).
 *
 * The rules here are the ones the single-object repaint already had, made per part:
 * - a part's object keeps its id and its name (the body name) across an edit, so the camera
 *   does not reset;
 * - the human's placement is carried onto the new tessellation relative to the old one, so a
 *   part that grows keeps its turn and its place;
 * - support paint survives while the triangle count does not change;
 * - a body the document no longer has loses its plate object, and a new body gets a new one.
 */
object CadPartPublish {

    /**
     * Lays the document's bodies out as plate objects.
     *
     * [bodies] are the composer's bodies in the engine's own order; [models] is the plate as it
     * is now; [previousParts] is the body-to-object mapping of the last publish, which is what
     * lets an edit keep every part where the human put it. [displayPath] names the plate's
     * source file for a body (the engine's own per-body display export).
     *
     * [legacyObjectId] is the single plate object a 1.9.6 descriptor restored for the whole
     * document: it is replaced by the parts in place, so the plate takes the new shape without
     * keeping a stale copy of the whole design beside it.
     */
    fun plan(
        bodies: List<Pair<String, StlMesh>>,
        models: List<PlateObject>,
        previousParts: List<CadPart>,
        displayPath: (String) -> String,
        legacyObjectId: String? = null,
    ): CadPartPlan {
        val previousByBody = previousParts.associateBy { it.body }
        val previousIds = previousParts.map { it.objectId }.toSet()
        val legacyIds = listOfNotNull(legacyObjectId).toSet()
        // Ids that are already spoken for by models this publish does not own. The document's
        // own ids are given back before planning so a part keeps its id across a republish.
        val usedIds = models
            .map { it.id }
            .filterNot { it in previousIds || it in legacyIds }
            .toMutableSet()
        val planned = LinkedHashMap<String, PlateObject>()
        val parts = ArrayList<CadPart>(bodies.size)
        val paintDropped = ArrayList<String>()
        var triangleCount = 0
        for ((body, mesh) in bodies) {
            if (mesh.triangleCount <= 0) continue
            triangleCount += mesh.triangleCount
            val existing = previousByBody[body]?.objectId
                ?.let { id -> models.firstOrNull { it.id == id } }
            val id = if (existing != null) {
                usedIds.add(existing.id)
                existing.id
            } else {
                uniqueId("cad:" + body, usedIds)
            }
            // A body the edit did not touch still holds the very same mesh instance: its plate
            // object is kept as it is, which is what makes an edit patch only the part it named
            // (the alternative re-transforms every other part on every edit).
            val object3d = if (existing != null && existing.sourceMesh === mesh) {
                existing
            } else {
                val placement = if (existing != null) {
                    carryPlacement(existing.placement, existing.sourceMesh, mesh)
                } else {
                    documentCoordinates(mesh)
                }
                val keepsPaint = existing != null &&
                    existing.sourceMesh.triangleCount == mesh.triangleCount
                if (existing != null && !keepsPaint && existing.supportPaint.hasPaint()) {
                    paintDropped += body
                }
                PlateObject(
                    id = id,
                    name = body,
                    sourceMesh = mesh,
                    mesh = placement.transformed(mesh),
                    sourcePath = displayPath(body),
                    placement = placement,
                    supportPaint = if (keepsPaint) existing!!.supportPaint else SupportPaintState(),
                )
            }
            planned[body] = object3d
            parts += CadPart(body = body, objectId = id)
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
            if (model.id in previousIds) continue
            if (model.id in legacyIds) {
                // The single whole-document object is replaced, in its own place, by the parts.
                for (object3d in planned.values) {
                    if (emitted.add(object3d.id)) result += object3d
                }
                continue
            }
            result += model
        }
        for (object3d in planned.values) {
            if (emitted.add(object3d.id)) result += object3d
        }
        return CadPartPlan(
            models = result,
            parts = parts,
            triangleCount = triangleCount,
            paintDropped = paintDropped,
        )
    }

    /**
     * The placement that puts a mesh at the document's own coordinates: exactly the identity.
     *
     * Where the agent puts a part in the design is where it prints. The app adds no plate
     * placement of its own on top of that; the human's transforms are carried separately, by
     * [carryPlacement].
     */
    fun documentCoordinates(mesh: StlMesh): ModelPlacement = ModelPlacement(
        centerXmm = mesh.bounds.centerX.toDouble(),
        centerYmm = mesh.bounds.centerY.toDouble(),
        baseZmm = mesh.bounds.minZ.toDouble(),
        source = "SolidCraft document coordinates",
    )

    /**
     * Carries the human's plate transform onto a newly derived mesh.
     *
     * A placement is the mesh's identity plus what the human did to it: the linear part holds
     * their rotation and scale, and the centre and base are the identity's plus their move. So
     * the transform is expressed relative to the old mesh and applied to the new one - a part
     * that grows keeps the place and the turn the user gave it, and never recentres itself.
     */
    fun carryPlacement(old: ModelPlacement, oldMesh: StlMesh, newMesh: StlMesh): ModelPlacement {
        val oldIdentity = documentCoordinates(oldMesh)
        val movedX = old.centerXmm - oldIdentity.centerXmm
        val movedY = old.centerYmm - oldIdentity.centerYmm
        val movedZ = old.baseZmm - oldIdentity.baseZmm
        val untouched = old.linear == ModelPlacement.IDENTITY &&
            abs(movedX) < 1e-6 &&
            abs(movedY) < 1e-6 &&
            abs(movedZ) < 1e-6
        if (untouched) return documentCoordinates(newMesh)
        return ModelPlacement(
            linear = old.linear,
            centerXmm = newMesh.bounds.centerX.toDouble() + movedX,
            centerYmm = newMesh.bounds.centerY.toDouble() + movedY,
            baseZmm = newMesh.bounds.minZ.toDouble() + movedZ,
            source = old.source,
        )
    }

    /** [base] if it is free, else the first base#n that is. */
    fun uniqueId(base: String, used: MutableSet<String>): String {
        if (used.add(base)) return base
        var suffix = 2
        while (!used.add(base + "#" + suffix)) suffix++
        return base + "#" + suffix
    }
}

/** True when the paint holds anything that would be lost by a tessellation change. */
internal fun SupportPaintState.hasPaint(): Boolean =
    enforcerTriangles.isNotEmpty() || blockerTriangles.isNotEmpty()
