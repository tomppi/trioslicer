package com.tomppi.enderslicer.viewer

/**
 * The joints, drawn where they will go.
 *
 * A ghost rather than the real thing: while the preview is being computed - and
 * whenever it is stale - the plate still shows the two halves as they were, and
 * this is the outline of every solid that is about to be unioned onto one of
 * them. It is the wireframe of each joint's own mesh with the duplicate edges
 * dropped, so what is drawn is exactly what the booleans will put in, sitting
 * exactly where the anchors put it.
 *
 * Pure: no GL, no state - just the line geometry the gizmo overlay already
 * knows how to draw in plate coordinates.
 */
object SnapGhost {
    /** A green that reads as "this is being added" against the model's greys. */
    private val JOINT_COLOR = floatArrayOf(0.35f, 0.95f, 0.55f)

    /** Every joint's wireframe, ready for the viewer's gizmo overlay. */
    fun overlay(joints: List<SnapFitJoint>): GizmoOverlay = GizmoOverlay(
        groups = joints.map { joint ->
            GizmoGroup(
                color = JOINT_COLOR,
                // The beam and key, and the whole-seam registration boss with
                // them: the boss is the part of the joint the eye reads first.
                vertices = edges(joint.unionSolid) + edges(joint.registrationSolid),
            )
        },
    )

    /** [joint]'s wireframe, for the one-joint callers. */
    fun overlay(joint: SnapFitJoint): GizmoOverlay = overlay(listOf(joint))

    /** Every edge of [mesh] once, as xyz pairs ready for [GizmoRibbon]. */
    fun edges(mesh: StlMesh): FloatArray {
        val vertices = mesh.interleavedVertices
        val triangles = mesh.triangleCount
        val seen = HashSet<Edge>(triangles * 3)
        val lines = ArrayList<Float>(triangles * 12)
        for (triangle in 0 until triangles) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            for (corner in 0 until 3) {
                val from = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                val to = base + ((corner + 1) % 3) * MeshSolidBuilder.FLOATS_PER_VERTEX
                val edge = Edge.of(
                    vertices[from], vertices[from + 1], vertices[from + 2],
                    vertices[to], vertices[to + 1], vertices[to + 2],
                )
                // An edge shared by two triangles is one line, whichever way
                // round each of them walks it.
                if (!seen.add(edge)) continue
                lines.add(vertices[from])
                lines.add(vertices[from + 1])
                lines.add(vertices[from + 2])
                lines.add(vertices[to])
                lines.add(vertices[to + 1])
                lines.add(vertices[to + 2])
            }
        }
        return lines.toFloatArray()
    }

    /**
     * One undirected edge: both ends rounded to a micrometre and ordered, so the
     * two triangles sharing an edge produce the same value whichever way each
     * one walks it. The ends are compared as ints rather than as one packed
     * long, which would need more bits than a Long has.
     */
    private class Edge(val ax: Int, val ay: Int, val az: Int, val bx: Int, val by: Int, val bz: Int) {
        override fun equals(other: Any?): Boolean =
            other is Edge && ax == other.ax && ay == other.ay && az == other.az &&
                bx == other.bx && by == other.by && bz == other.bz

        override fun hashCode(): Int {
            var hash = ax
            hash = hash * 31 + ay
            hash = hash * 31 + az
            hash = hash * 31 + bx
            hash = hash * 31 + by
            hash = hash * 31 + bz
            return hash
        }

        companion object {
            fun of(
                ax: Float, ay: Float, az: Float,
                bx: Float, by: Float, bz: Float,
            ): Edge {
                val x1 = round(ax); val y1 = round(ay); val z1 = round(az)
                val x2 = round(bx); val y2 = round(by); val z2 = round(bz)
                // Order the two ends, so an edge walked from either end is one edge.
                val flip = x1 > x2 ||
                    (x1 == x2 && y1 > y2) ||
                    (x1 == x2 && y1 == y2 && z1 > z2)
                return if (flip) Edge(x2, y2, z2, x1, y1, z1) else Edge(x1, y1, z1, x2, y2, z2)
            }

            private fun round(value: Float): Int = Math.round(value * MATCH_PER_MM)
        }
    }

    /** Positions are matched to a micrometre, as [MeshRepair] matches a boundary. */
    private const val MATCH_PER_MM = 1_000f
}
