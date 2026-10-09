package com.tomppi.enderslicer.model

import com.tomppi.enderslicer.supportpaint.SupportPaintState
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh

/**
 * One model on the build plate.
 *
 * The plate used to hold exactly one of these, spread across three fields of the UI state. It now
 * holds a list, and everything that means "the model" means the selected one.
 *
 * [sourceMesh] is the mesh as imported and never changes; [mesh] is that mesh with [placement]
 * applied - what the viewer draws and what the engines are handed. Both are kept because
 * re-transforming a million triangles on every recomposition is not free.
 */
data class PlateObject(
    val id: String,
    val name: String,
    val sourceMesh: StlMesh,
    val mesh: StlMesh,
    val sourcePath: String?,
    val placement: ModelPlacement,
    val supportPaint: SupportPaintState = SupportPaintState(),
) {
    /** How much bed this object takes, in bed coordinates. */
    val bounds: MeshBounds get() = mesh.bounds

    /** The same object moved by a whole-plate delta, which is what arrangement computes. */
    fun movedBy(dxMm: Double, dyMm: Double): PlateObject =
        withCenter(placement.centerXmm + dxMm, placement.centerYmm + dyMm)

    /** The same object re-centred on the bed, keeping its rotation, scale and Z. */
    fun withCenter(centerXmm: Double, centerYmm: Double): PlateObject =
        withPlacement(placement.copy(centerXmm = centerXmm, centerYmm = centerYmm))

    fun withPlacement(next: ModelPlacement): PlateObject =
        copy(placement = next, mesh = next.transformed(sourceMesh))

    fun withPaint(paint: SupportPaintState): PlateObject =
        copy(supportPaint = paint.clippedToMesh(mesh.triangleCount))

    /** Paint indices address triangles, and every copy of a mesh keeps the same order. */
    fun withMeshName(displayName: String): PlateObject =
        copy(name = displayName)

    companion object {
        /** A stable identity for a freshly imported model. */
        fun newId(): String = java.util.UUID.randomUUID().toString()
    }
}
