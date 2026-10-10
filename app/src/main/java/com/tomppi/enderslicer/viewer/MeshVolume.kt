package com.tomppi.enderslicer.viewer

/**
 * The volume a closed mesh bounds: the signed volume of its triangles around
 * the origin, positive when the winding faces outwards.
 *
 * Pure arithmetic, no engine. It is what a report of "how much material did
 * that boolean move" is measured with - on the JVM where the native engine is
 * not packaged, and on the phone where a number the mesh already carries is
 * not worth a second boolean.
 */
object MeshVolume {
    /** The volume [mesh] bounds, in cubic millimetres. */
    fun of(mesh: StlMesh): Double {
        val vertices = mesh.interleavedVertices
        var total = 0.0
        for (triangle in 0 until mesh.triangleCount) {
            val at = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            val ax = vertices[at].toDouble()
            val ay = vertices[at + 1].toDouble()
            val az = vertices[at + 2].toDouble()
            val bx = vertices[at + 6].toDouble()
            val by = vertices[at + 7].toDouble()
            val bz = vertices[at + 8].toDouble()
            val cx = vertices[at + 12].toDouble()
            val cy = vertices[at + 13].toDouble()
            val cz = vertices[at + 14].toDouble()
            total += (ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)) / 6.0
        }
        return total
    }
}
