package com.tomppi.enderslicer.supportpaint

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.VertexData
import com.tomppi.enderslicer.viewer.StlSliceTransform
import java.io.File
import kotlin.math.sqrt

/** One support-painting modifier volume and the CuraEngine role it plays. */
data class SupportPaintModifier(
    val isBlocker: Boolean,
    val file: File,
)

/**
 * Converts painted triangle regions into closed STL volumes that CuraEngine
 * interprets as support enforcers ([isBlocker] = false) or support blockers
 * ([isBlocker] = true).
 *
 * Each painted triangle is extruded along its surface normal into a thin prism,
 * so the resulting volume encloses the model surface where it was painted.
 * Adjacent prisms overlap, and both engine writers deliberately set
 * `meshfix_union_all` to false, so the prisms reach CuraEngine as separate
 * shells: support generation unions their covered areas, which is what turns
 * the overlap into one seamless painted region without a mesh-boolean pass.
 *
 * [transform] mirrors [com.tomppi.enderslicer.engine.ThicknessAdaptiveWalls]:
 * when a resolved Cura profile swaps the displayed STL for its original source,
 * the paint indices still address the same triangles, but the prisms must be
 * written back into final build-plate coordinates.
 */
object SupportPaintModifiers {
    /**
     * Upper bound on painted triangles per slice; each becomes an 8-triangle
     * prism. 5,000 painted triangles (~40k prism triangles) slices in a few
     * seconds on the host engine, while larger regions dominate the per-layer
     * support-area computation and blow the slice budget.
     */
    const val MAX_PAINTED_TRIANGLES = 5_000

    fun generate(
        mesh: StlMesh,
        paint: SupportPaintState,
        destination: File,
        thicknessMm: Double,
        transform: StlSliceTransform? = null,
    ): List<SupportPaintModifier> {
        val paintedTriangles = paint.enforcerTriangles.size + paint.blockerTriangles.size
        require(paintedTriangles <= MAX_PAINTED_TRIANGLES) {
            "Support painting covers " + paintedTriangles + " triangles; the limit is " +
                MAX_PAINTED_TRIANGLES + ". Erase some paint or use a smaller brush before slicing."
        }
        require(destination.mkdirs() || destination.isDirectory) {
            "Unable to create the support-painting staging directory"
        }
        require(thicknessMm.isFinite() && thicknessMm > 0.0) { "Support-paint extrusion thickness must be positive" }
        val modifiers = mutableListOf<SupportPaintModifier>()
        if (paint.enforcerTriangles.isNotEmpty()) {
            val file = File(destination, "support-enforcer.stl")
            writePrisms(mesh, paint.enforcerTriangles, thicknessMm, transform, file)
            modifiers += SupportPaintModifier(isBlocker = false, file = file)
        }
        if (paint.blockerTriangles.isNotEmpty()) {
            val file = File(destination, "support-blocker.stl")
            writePrisms(mesh, paint.blockerTriangles, thicknessMm, transform, file)
            modifiers += SupportPaintModifier(isBlocker = true, file = file)
        }
        return modifiers
    }

    private fun writePrisms(
        mesh: StlMesh,
        triangles: Set<Int>,
        thicknessMm: Double,
        transform: StlSliceTransform?,
        file: File,
    ) {
        val vertices = mesh.interleavedVertices
        val linear = transform?.linear
        val translateX = transform?.translationXmm ?: 0.0
        val translateY = transform?.translationYmm ?: 0.0
        val translateZ = transform?.translationZmm ?: 0.0
        val half = (thicknessMm * 0.5).toFloat()
        val out = ArrayList<Float>(triangles.size * 8 * 18)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY

        fun place(x: Float, y: Float, z: Float): FloatArray {
            if (linear == null) return floatArrayOf(x, y, z)
            return floatArrayOf(
                (x * linear[0] + y * linear[1] + z * linear[2] + translateX).toFloat(),
                (x * linear[3] + y * linear[4] + z * linear[5] + translateY).toFloat(),
                (x * linear[6] + y * linear[7] + z * linear[8] + translateZ).toFloat(),
            )
        }

        fun addTriangle(
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            cx: Float, cy: Float, cz: Float,
        ) {
            val ux = bx - ax
            val uy = by - ay
            val uz = bz - az
            val vx = cx - ax
            val vy = cy - ay
            val vz = cz - az
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length > 1e-12f) {
                nx /= length
                ny /= length
                nz /= length
            } else {
                nx = 0f; ny = 0f; nz = 1f
            }
            for (p in listOf(
                Triple(ax, ay, az), Triple(bx, by, bz), Triple(cx, cy, cz),
            )) {
                out.add(p.first); out.add(p.second); out.add(p.third)
                out.add(nx); out.add(ny); out.add(nz)
                minX = minOf(minX, p.first); maxX = maxOf(maxX, p.first)
                minY = minOf(minY, p.second); maxY = maxOf(maxY, p.second)
                minZ = minOf(minZ, p.third); maxZ = maxOf(maxZ, p.third)
            }
        }

        for (triangle in triangles) {
            val base = triangle * 18
            val v0 = place(vertices[base], vertices[base + 1], vertices[base + 2])
            val v1 = place(vertices[base + 6], vertices[base + 7], vertices[base + 8])
            val v2 = place(vertices[base + 12], vertices[base + 13], vertices[base + 14])

            val ux = v1[0] - v0[0]; val uy = v1[1] - v0[1]; val uz = v1[2] - v0[2]
            val wx = v2[0] - v0[0]; val wy = v2[1] - v0[1]; val wz = v2[2] - v0[2]
            var nx = uy * wz - uz * wy
            var ny = uz * wx - ux * wz
            var nz = ux * wy - uy * wx
            val normalLength = sqrt(nx * nx + ny * ny + nz * nz)
            if (normalLength > 1e-12f) {
                nx /= normalLength
                ny /= normalLength
                nz /= normalLength
            } else {
                nx = 0f; ny = 0f; nz = 1f
            }

            val dx = nx * half
            val dy = ny * half
            val dz = nz * half
            val t0x = v0[0] + dx; val t0y = v0[1] + dy; val t0z = v0[2] + dz
            val t1x = v1[0] + dx; val t1y = v1[1] + dy; val t1z = v1[2] + dz
            val t2x = v2[0] + dx; val t2y = v2[1] + dy; val t2z = v2[2] + dz
            val b0x = v0[0] - dx; val b0y = v0[1] - dy; val b0z = v0[2] - dz
            val b1x = v1[0] - dx; val b1y = v1[1] - dy; val b1z = v1[2] - dz
            val b2x = v2[0] - dx; val b2y = v2[1] - dy; val b2z = v2[2] - dz

            addTriangle(t0x, t0y, t0z, t1x, t1y, t1z, t2x, t2y, t2z)
            addTriangle(b0x, b0y, b0z, b2x, b2y, b2z, b1x, b1y, b1z)
            addTriangle(t0x, t0y, t0z, t1x, t1y, t1z, b1x, b1y, b1z)
            addTriangle(t0x, t0y, t0z, b1x, b1y, b1z, b0x, b0y, b0z)
            addTriangle(t1x, t1y, t1z, t2x, t2y, t2z, b2x, b2y, b2z)
            addTriangle(t1x, t1y, t1z, b2x, b2y, b2z, b1x, b1y, b1z)
            addTriangle(t2x, t2y, t2z, t0x, t0y, t0z, b0x, b0y, b0z)
            addTriangle(t2x, t2y, t2z, b0x, b0y, b0z, b2x, b2y, b2z)
        }

        require(out.isNotEmpty()) { "Support-paint region contains no triangles" }
        val paintedMesh = StlMesh(
            displayName = file.name,
            interleavedVertices = VertexData.fromArray(out.toFloatArray()),
            triangleCount = out.size / 18,
            bounds = MeshBounds(minX, minY, minZ, maxX, maxY, maxZ),
        )
        StlMeshWriter.writeBinary(paintedMesh, file)
        require(file.isFile && file.length() > 0L) { "Unable to write the support-paint modifier" }
    }
}
