package com.tomppi.enderslicer.viewer

/**
 * Small mesh fixtures and checks shared by the viewer tests that work on
 * geometry: the boolean bridge, the joint generator and the hole repair all
 * need a closed box, a way to knock a face out of one, and a way to ask
 * whether a mesh really is closed.
 */
object MeshFixtures {
    /** Corner order the box faces below are written in. */
    private val CORNERS = arrayOf(
        floatArrayOf(0f, 0f, 0f),
        floatArrayOf(1f, 0f, 0f),
        floatArrayOf(1f, 1f, 0f),
        floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f),
        floatArrayOf(1f, 0f, 1f),
        floatArrayOf(1f, 1f, 1f),
        floatArrayOf(0f, 1f, 1f),
    )

    /** Bottom, top, -Y, +X, +Y, -X; every quad wound outwards. */
    private val FACES = arrayOf(
        intArrayOf(0, 3, 2, 1),
        intArrayOf(4, 5, 6, 7),
        intArrayOf(0, 1, 5, 4),
        intArrayOf(1, 2, 6, 5),
        intArrayOf(2, 3, 7, 6),
        intArrayOf(3, 0, 4, 7),
    )

    /** Index of each face in [FACES], for tests that take one off. */
    const val BOTTOM = 0
    const val TOP = 1
    const val FRONT = 2

    /** A closed axis-aligned box: two triangles per face, twelve in all. */
    fun box(
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float,
        name: String = "box",
    ): StlMesh {
        val soup = ArrayList<Float>()
        for (face in FACES) {
            val corners = face.map { corner ->
                val unit = CORNERS[corner]
                Vec3(
                    if (unit[0] == 0f) minX else maxX,
                    if (unit[1] == 0f) minY else maxY,
                    if (unit[2] == 0f) minZ else maxZ,
                )
            }
            quad(soup, corners[0], corners[1], corners[2], corners[3])
        }
        return fromSoup(name, soup.toFloatArray())
    }

    /**
     * The same box with every face fanned into four triangles around its own
     * centre vertex. Taking one triangle out of it leaves a three-edge hole,
     * which is what a hole in a real mesh usually looks like.
     */
    fun fannedBox(
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float,
        name: String = "fanned-box",
    ): StlMesh {
        val soup = ArrayList<Float>()
        for (face in FACES) {
            val corners = face.map { corner ->
                val unit = CORNERS[corner]
                Vec3(
                    if (unit[0] == 0f) minX else maxX,
                    if (unit[1] == 0f) minY else maxY,
                    if (unit[2] == 0f) minZ else maxZ,
                )
            }
            val centre = (corners[0] + corners[1] + corners[2] + corners[3]) * 0.25f
            for (index in corners.indices) {
                triangle(soup, corners[index], corners[(index + 1) % corners.size], centre)
            }
        }
        return fromSoup(name, soup.toFloatArray())
    }

    /**
     * A box whose face at maxZ is a ring: a [size] x [size] x [height] block
     * with a [cavity] x [cavity] pocket sunk from that face halfway down. Its
     * cap is a thin band of material, so a face-sized pad over it has air under
     * most of its middle - the shape a boat hull's cross-section has to the
     * joint generator.
     */
    fun hollowBox(size: Float, height: Float, cavity: Float, name: String = "hollow-box"): StlMesh {
        require(cavity < size) { "The cavity has to leave a wall" }
        val inset = (size - cavity) / 2f
        val floor = height * 0.5f
        val soup = ArrayList<Float>()
        fun point(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
        fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) {
            soup.addAll(triangleOf(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]).toList())
            soup.addAll(triangleOf(a[0], a[1], a[2], c[0], c[1], c[2], d[0], d[1], d[2]).toList())
        }
        val a = point(0f, 0f, 0f)
        val b = point(size, 0f, 0f)
        val c = point(size, size, 0f)
        val d = point(0f, size, 0f)
        val e = point(0f, 0f, height)
        val f = point(size, 0f, height)
        val g = point(size, size, height)
        val h = point(0f, size, height)
        quad(a, d, c, b) // bottom, -Z
        quad(a, b, f, e) // front, -Y
        quad(d, h, g, c) // back, +Y
        quad(a, e, h, d) // left, -X
        quad(b, c, g, f) // right, +X
        val ia = point(inset, inset, height)
        val ib = point(size - inset, inset, height)
        val ic = point(size - inset, size - inset, height)
        val id = point(inset, size - inset, height)
        quad(e, f, ib, ia) // cap ring, +Z
        quad(f, g, ic, ib)
        quad(g, h, id, ic)
        quad(h, e, ia, id)
        val fa = point(inset, inset, floor)
        val fb = point(size - inset, inset, floor)
        val fc = point(size - inset, size - inset, floor)
        val fd = point(inset, size - inset, floor)
        quad(ia, ib, fb, fa) // pocket walls, facing into the pocket
        quad(ib, ic, fc, fb)
        quad(ic, id, fd, fc)
        quad(id, ia, fa, fd)
        quad(fa, fb, fc, fd) // pocket floor, +Z
        return fromSoup(name, soup.toFloatArray())
    }

    /** [mesh] without the triangles [drop] selects, as a fresh mesh. */
    fun without(mesh: StlMesh, drop: (Int) -> Boolean): StlMesh {
        val builder = MeshSolidBuilder(mesh.displayName)
        for (triangle in 0 until mesh.triangleCount) {
            if (drop(triangle)) continue
            builder.addInterleaved(mesh.interleavedVertices, triangle)
        }
        return builder.build()
    }

    /** A closed solid from a triangle soup, normals computed from the winding. */
    fun fromSoup(name: String, soup: FloatArray): StlMesh {
        val builder = MeshSolidBuilder(name)
        for (triangle in 0 until soup.size / 9) {
            val at = triangle * 9
            builder.addTriangle(
                soup[at], soup[at + 1], soup[at + 2],
                soup[at + 3], soup[at + 4], soup[at + 5],
                soup[at + 6], soup[at + 7], soup[at + 8],
            )
        }
        return builder.build()
    }

    /** One triangle of three corners: nine floats, the soup layout [fromSoup] reads. */
    fun triangleOf(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float): FloatArray =
        floatArrayOf(ax, ay, az, bx, by, bz, cx, cy, cz)

    /** Positive when the surface faces outwards and bounds a solid. */
    fun signedVolume(mesh: StlMesh): Double = MeshVolume.of(mesh)

    /**
     * True when every edge is shared by exactly two triangles, welded by
     * position first because a soup has no shared vertices. This is the same
     * check the JNI shim runs on the device, spelled out again here so the JVM
     * tests do not take Manifold's word for it.
     */
    fun isClosed(mesh: StlMesh): Boolean {
        val vertices = mesh.interleavedVertices
        val ids = HashMap<PositionKey, Int>(mesh.triangleCount * 2)
        val corners = IntArray(mesh.triangleCount * 3)
        var welded = 0
        for (vertex in 0 until mesh.triangleCount * 3) {
            val base = vertex * 6
            val key = PositionKey(
                Math.round(vertices[base] * 10_000f),
                Math.round(vertices[base + 1] * 10_000f),
                Math.round(vertices[base + 2] * 10_000f),
            )
            val known = ids[key]
            if (known != null) {
                corners[vertex] = known
            } else {
                ids[key] = welded
                corners[vertex] = welded
                welded++
            }
        }
        val used = HashMap<Long, Int>(mesh.triangleCount * 3)
        for (triangle in 0 until mesh.triangleCount) {
            for (corner in 0 until 3) {
                val from = corners[triangle * 3 + corner]
                val to = corners[triangle * 3 + (corner + 1) % 3]
                if (from == to) return false
                val low = minOf(from, to).toLong()
                val high = maxOf(from, to).toLong()
                val key = (low shl 32) or high
                used[key] = (used[key] ?: 0) + 1
            }
        }
        return mesh.triangleCount > 0 && used.isNotEmpty() && used.all { it.value == 2 }
    }

    /** A position rounded to a tenth of a micron, for welding a soup's corners. */
    private data class PositionKey(val x: Int, val y: Int, val z: Int)

    private fun quad(out: ArrayList<Float>, a: Vec3, b: Vec3, c: Vec3, d: Vec3) {
        triangle(out, a, b, c)
        triangle(out, a, c, d)
    }

    /** Three corners, nine floats: the soup layout [fromSoup] reads. */
    private fun triangle(out: ArrayList<Float>, a: Vec3, b: Vec3, c: Vec3) {
        for (point in listOf(a, b, c)) {
            out.add(point.x)
            out.add(point.y)
            out.add(point.z)
        }
    }
}
