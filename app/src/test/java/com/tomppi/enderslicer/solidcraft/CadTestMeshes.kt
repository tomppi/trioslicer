package com.tomppi.enderslicer.solidcraft

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData

/** A one-part mesh with a known place, so a placement can be told from a re-centring. */
internal fun cadTestMesh(name: String = "body", x: Float = 0f, triangles: Int = 1): StlMesh {
    val floats = FloatArray(triangles * 18)
    for (triangle in 0 until triangles) {
        val base = triangle * 18
        floats[base] = x
        floats[base + 1] = 0f
        floats[base + 2] = 0f
        floats[base + 3] = 0f
        floats[base + 4] = 0f
        floats[base + 5] = 1f
        floats[base + 6] = x + 10f
        floats[base + 7] = 0f
        floats[base + 8] = 0f
        floats[base + 9] = 0f
        floats[base + 10] = 0f
        floats[base + 11] = 1f
        floats[base + 12] = x
        floats[base + 13] = 10f
        floats[base + 14] = 0f
        floats[base + 15] = 0f
        floats[base + 16] = 0f
        floats[base + 17] = 1f
    }
    return StlMesh(
        displayName = name,
        interleavedVertices = VertexData.fromArray(floats),
        triangleCount = triangles,
        bounds = MeshBounds(x, 0f, 0f, x + 10f, 10f, 0f),
    )
}

/** The one display path a publish hands each part. */
internal fun cadTestDisplayPath(body: String): String = "/ws/display/design-body-" + body + ".stl"
