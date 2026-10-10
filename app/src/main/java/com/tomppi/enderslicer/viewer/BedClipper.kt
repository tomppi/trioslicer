package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cuts a placed mesh at an axis-aligned plane and keeps one side of it.
 *
 * [clipToBed] is the build plate's own cut: the mesh is cut at Z=0 and what is
 * above it is kept. This is what makes a model that hangs below the bed
 * sliceable: the user may drag an object down through the plate, the viewer
 * keeps drawing the whole thing so they can see what they are cutting, and only
 * the geometry the engine is handed is clipped. The cross-section is left open -
 * the slicer treats the first layer as the bottom, which is exactly "the stuff
 * above is the new bottom" - so nothing is capped and the removed volume is not
 * re-sealed.
 *
 * [clip] is the same cut on any axis, which is what the Split action commits:
 * the two sides of one plane are the two halves of the model.
 *
 * A triangle that crosses the plane is replaced by the part of it on the kept
 * side: one triangle when it keeps one or two of its corners, two when it keeps
 * two and gains a crossing. Crossing points are interpolated along the crossing
 * edge, vertex normals included, and every replacement triangle is written in
 * the original's own edge order, so the winding is preserved.
 */
object BedClipper {
    /**
     * Which side of the cut a clip keeps.
     *
     * [HIGH] is the material at or above the plane - above the cut on Z, and on
     * the +X or +Y side of it - and [LOW] the material at or below it. A corner
     * on the plane is on both kept sides, so a triangle lying in the plane is
     * kept whole by either.
     */
    enum class Half { HIGH, LOW }

    /**
     * The mesh [mesh] clipped to Z >= 0, or [mesh] itself when it has no
     * geometry below the plane.
     *
     * The identity return is the guarantee that a model sitting on or above the
     * bed goes on to be staged byte-for-byte as before: nothing is copied,
     * nothing is rewritten, and no clipped file is produced.
     *
     * The clipped result has no retained precision slice source: that source is
     * the *unclipped* original geometry, so handing it to CuraEngine with its
     * affine sidecar would put the below-bed part back into the engine. A
     * clipped object is sliced from the clipped vertices themselves.
     *
     * A result with [StlMesh.triangleCount] == 0 means the whole model was below
     * the plane. The caller must not stage it: there is nothing to print, and an
     * empty mesh is refused by [StlMeshWriter].
     */
    fun clipToBed(mesh: StlMesh): StlMesh =
        clip(mesh, ModelPlacement.Axis.Z, 0f, Half.HIGH)

    /**
     * The part of [mesh] on the [half] side of the axis-aligned plane at
     * [offsetMm] along [axis], in build-plate millimetres.
     *
     * Exactly like [clipToBed], a mesh with nothing on the removed side comes
     * back as the very same instance, and a mesh entirely on the removed side
     * comes back empty rather than throwing.
     */
    fun clip(
        mesh: StlMesh,
        axis: ModelPlacement.Axis,
        offsetMm: Float,
        half: Half,
    ): StlMesh = clipped(mesh, axis, offsetMm, half, closeCrossSection = false)

    /**
     * The part of [mesh] on the [half] side of the plane, with the cut face
     * capped so the half is a closed solid.
     *
     * [clip] leaves the cross-section open, which is what the bed's own cut and
     * the live preview want. A half that is going to be *printed* is a different
     * matter: CuraEngine builds each layer out of the mesh's own outline and
     * only stitches an outline whose two ends are within 10 mm of each other
     * (`SlicerLayer::stitch`, `max_stitch1` in src/slicer.cpp), so a model cut
     * through one of its vertical walls has an open outline at every layer and
     * slices to nothing at all. Capping the cross-section is what turns each
     * half into a part the engines can print.
     *
     * A cross-section whose segments do not chain into closed loops - a
     * non-manifold mesh, or float dust that will not match - is left open rather
     * than guessed at.
     */
    fun clipClosed(
        mesh: StlMesh,
        axis: ModelPlacement.Axis,
        offsetMm: Float,
        half: Half,
    ): StlMesh = clipped(mesh, axis, offsetMm, half, closeCrossSection = true)

    private fun clipped(
        mesh: StlMesh,
        axis: ModelPlacement.Axis,
        offsetMm: Float,
        half: Half,
        closeCrossSection: Boolean,
    ): StlMesh {
        require(offsetMm.isFinite()) { "A cut plane offset must be finite" }
        // Working in a signed distance from the plane is what lets one piece of
        // code keep either side: "below" always means the side being removed.
        val sign = if (half == Half.HIGH) 1f else -1f
        val span = mesh.bounds.spanAlong(axis)

        // A hair under a nanometre of slack: the placed mesh is float, so a model
        // that sits exactly on the bed can land on a minZ of -1e-7. Treating that
        // as "below the bed" would re-triangulate the whole bottom surface for a
        // difference no printer can move, so such a mesh is returned untouched -
        // and the caller's own < 0 test is what decides to call this at all. The
        // same slack keeps a cut inside the bounds from shaving the surface it
        // only touches.
        if (span.start >= offsetMm - PLANE_EPSILON_MM && half == Half.HIGH) return mesh
        if (span.endInclusive <= offsetMm + PLANE_EPSILON_MM && half == Half.LOW) return mesh
        require(mesh.triangleCount > 0 && mesh.interleavedVertices.size == mesh.triangleCount * FLOATS_PER_TRIANGLE) {
            "Bed clipping needs complete model geometry"
        }

        fun distanceOf(vertex: ClipVertex): Float = sign * (vertex.along(axis) - offsetMm)

        // A triangle that crosses the plane keeps at most two of its own: one
        // that does not stays one. The buffer is sized from that ceiling, and
        // the count that comes out is what the emitted triangles add up to.
        var capacity = 0
        forEachTriangle(mesh) { triangle ->
            capacity += if (crossesThePlane(triangle, axis, offsetMm, sign)) MAX_TRIANGLES_PER_CUT else 1
        }

        val floats = FloatArray(Math.multiplyExact(capacity, FLOATS_PER_TRIANGLE))
        var written = 0
        var keptTriangles = 0
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY

        /** Appends one vertex, its cut-axis coordinate on the kept side of the plane. */
        fun keep(vertex: ClipVertex) {
            // A corner the tolerance already reads as on the plane is written as
            // exactly the plane's own coordinate, so float rounding cannot carry
            // it to the removed side of the engine's cut.
            val distance = distanceOf(vertex)
            val coordinate = if (distance >= 0f) vertex.along(axis) else offsetMm
            val x = if (axis == ModelPlacement.Axis.X) coordinate else vertex.x
            val y = if (axis == ModelPlacement.Axis.Y) coordinate else vertex.y
            val z = if (axis == ModelPlacement.Axis.Z) coordinate else vertex.z
            floats[written++] = x
            floats[written++] = y
            floats[written++] = z
            floats[written++] = vertex.nx
            floats[written++] = vertex.ny
            floats[written++] = vertex.nz
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            minZ = minOf(minZ, z)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
            maxZ = maxOf(maxZ, z)
        }

        fun keepTriangle(first: ClipVertex, second: ClipVertex, third: ClipVertex) {
            keep(first)
            keep(second)
            keep(third)
            keptTriangles++
        }

        /** Where the edge from [below] to [above] meets the plane. */
        fun cut(below: ClipVertex, above: ClipVertex): ClipVertex =
            ClipVertex.between(below, above, -distanceOf(below) / (distanceOf(above) - distanceOf(below)), axis, offsetMm)

        // Where the plane meets the mesh: one directed segment per crossing
        // triangle, in the order that triangle walks its own corners. On closed
        // geometry they chain end to end into the cross-section's outline, which
        // is what the cap is built from.
        val crossings = if (closeCrossSection) ArrayList<FloatArray>() else null

        forEachTriangle(mesh) { triangle ->
            val a = ClipVertex.from(triangle, 0)
            val b = ClipVertex.from(triangle, 1)
            val c = ClipVertex.from(triangle, 2)
            val distanceA = distanceOf(a)
            val distanceB = distanceOf(b)
            val distanceC = distanceOf(c)
            val belowA = strictlyBelow(distanceA)
            val belowB = strictlyBelow(distanceB)
            val belowC = strictlyBelow(distanceC)

            if (crossings != null) {
                val corners = arrayOf(a, b, c)
                val distances = floatArrayOf(distanceA, distanceB, distanceC)
                var enters: ClipVertex? = null
                var leaves: ClipVertex? = null
                for (edge in 0 until VERTICES_PER_TRIANGLE) {
                    val next = (edge + 1) % VERTICES_PER_TRIANGLE
                    if (strictlyBelow(distances[edge]) == strictlyBelow(distances[next])) continue
                    val crossing = ClipVertex.between(
                        corners[edge],
                        corners[next],
                        -distances[edge] / (distances[next] - distances[edge]),
                        axis,
                        offsetMm,
                    )
                    if (enters == null) enters = crossing else leaves = crossing
                }
                if (enters != null && leaves != null) {
                    crossings += floatArrayOf(
                        enters.x, enters.y, enters.z,
                        leaves.x, leaves.y, leaves.z,
                    )
                }
            }

            // One corner below: the edges that leave it are cut, and what is
            // left is that corner's two neighbours and the two crossings,
            // around the same loop the triangle walked.
            if (belowA && !belowB && !belowC) {
                val onAB = cut(a, b)
                val onCA = cut(a, c)
                keepTriangle(b, c, onCA)
                keepTriangle(b, onCA, onAB)
            } else if (belowB && !belowC && !belowA) {
                val onAB = cut(b, a)
                val onBC = cut(b, c)
                keepTriangle(c, a, onAB)
                keepTriangle(c, onAB, onBC)
            } else if (belowC && !belowA && !belowB) {
                val onBC = cut(c, b)
                val onCA = cut(c, a)
                keepTriangle(a, b, onBC)
                keepTriangle(a, onBC, onCA)
            } else if (belowA && belowB && !belowC) {
                // Two corners below: the one above and the two crossings make
                // the whole of what is left, still in the triangle's own order.
                keepTriangle(c, cut(c, a), cut(c, b))
            } else if (belowB && belowC && !belowA) {
                keepTriangle(a, cut(a, b), cut(a, c))
            } else if (belowC && belowA && !belowB) {
                keepTriangle(b, cut(b, c), cut(b, a))
            } else {
                // Nothing below the plane: copied across unchanged. A triangle
                // lying in the plane is copied too - it has no part to cut. The
                // last case, all three below, falls through to nothing.
                if (!belowA) keepTriangle(a, b, c)
            }
        }
        check(written == keptTriangles * FLOATS_PER_TRIANGLE && written <= floats.size) {
            "Bed clipping wrote an unexpected number of vertices"
        }

        // The cap closes what the cut opened. Every crossing point it uses is a
        // vertex of a kept triangle, so the clipped bounds already cover it.
        val cap = if (crossings == null) EMPTY_FLOATS else crossSectionCap(crossings, axis, offsetMm, half)
        val capTriangles = cap.size / FLOATS_PER_TRIANGLE
        val complete = FloatArray(written + cap.size)
        System.arraycopy(floats, 0, complete, 0, written)
        System.arraycopy(cap, 0, complete, written, cap.size)
        // The cap is built band by band, and a band boundary can run straight
        // through corners the section's outline already has on it. The cap then
        // carries one long edge where the surface beside it carries several, so
        // each of those edges is used by exactly one triangle: the half measures
        // the right volume and is refused as non-manifold by every boolean
        // engine. Splitting the long triangles at the vertices already on the
        // edge costs nothing geometrically and makes the half closed.
        val welded = if (cap.isEmpty()) complete else weldCutSection(complete, keptTriangles + capTriangles, axis, offsetMm)
        val totalTriangles = welded.size / FLOATS_PER_TRIANGLE
        val vertices = if (totalTriangles >= OFF_HEAP_MIN_TRIANGLES) {
            val direct = ByteBuffer.allocateDirect(welded.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            direct.put(welded, 0, welded.size)
            direct.position(0)
            VertexData.fromDirect(direct)
        } else {
            VertexData.fromArray(welded)
        }
        return StlMesh(
            displayName = mesh.displayName,
            interleavedVertices = vertices,
            triangleCount = totalTriangles,
            bounds = if (totalTriangles == 0) EMPTY_BOUNDS else MeshBounds(minX, minY, minZ, maxX, maxY, maxZ),
        )
    }

    /**
     * Splits the triangles that meet along the cut plane so the cap and the
     * surface it closes share every vertex the section already has there.
     *
     * A vertex that lies on an edge is a vertex the triangle owning that edge
     * can be split at, which changes no geometry - the edge runs through the
     * vertex either way - and pairs the two sides of the seam. The vertices are
     * hashed into millimetre cells in the plane, so an edge only looks at the
     * handful of points near it rather than at the whole section.
     */
    private fun weldCutSection(
        triangles: FloatArray,
        triangleCount: Int,
        axis: ModelPlacement.Axis,
        offsetMm: Float,
    ): FloatArray {
        val cornerCount = triangleCount * VERTICES_PER_TRIANGLE
        val onPlane = BooleanArray(cornerCount)
        val planeU = FloatArray(cornerCount)
        val planeV = FloatArray(cornerCount)
        val cells = HashMap<Long, MutableList<Int>>()
        for (corner in 0 until cornerCount) {
            val base = corner * FLOATS_PER_VERTEX
            val x = triangles[base]
            val y = triangles[base + 1]
            val z = triangles[base + 2]
            val along = when (axis) {
                ModelPlacement.Axis.X -> x
                ModelPlacement.Axis.Y -> y
                ModelPlacement.Axis.Z -> z
            }
            if (Math.abs(along - offsetMm) > PLANE_EPSILON_MM) continue
            onPlane[corner] = true
            planeU[corner] = planeCoordinate(x, y, z, axis, first = true)
            planeV[corner] = planeCoordinate(x, y, z, axis, first = false)
            cells.getOrPut(cellKey(planeU[corner], planeV[corner])) { ArrayList(4) }.add(corner)
        }

        var welded = FloatArray(triangles.size)
        var weldedFloats = 0
        val pending = ArrayDeque<WeldTriangle>()
        var guard = triangleCount * 8 + 64
        for (triangle in 0 until triangleCount) {
            val first = triangle * 3
            val second = first + 1
            val third = first + 2
            // The common triangle touches neither the plane nor itself, and
            // goes straight out: only the few at the seam pay for a work item.
            if (!onPlane[first] && !onPlane[second] && !onPlane[third] &&
                !cornerPositionsMatch(triangles, first, second, third)
            ) {
                if (weldedFloats + FLOATS_PER_TRIANGLE > welded.size) welded = welded.copyOf(welded.size * 2)
                for (corner in first..third) {
                    val base = corner * FLOATS_PER_VERTEX
                    for (channel in 0 until FLOATS_PER_VERTEX) welded[weldedFloats++] = triangles[base + channel]
                }
                continue
            }
            val corners = intArrayOf(first, second, third)
            pending.addLast(WeldTriangle(corners, normalsOf(triangles, corners)))
            while (pending.isNotEmpty()) {
                val current = pending.removeLast()
                val split = if (guard-- > 0) {
                    sectionSplit(current.corners, onPlane, planeU, planeV, cells)
                } else {
                    null
                }
                if (split == null) {
                    // A corner the cut left folded onto another has no area and
                    // no side: it would only double one of its edges. Dropping it
                    // is what lets the edge pair with the triangle opposite.
                    if (cornerPositionsMatch(triangles, current.corners[0], current.corners[1], current.corners[2])) continue
                    if (weldedFloats + FLOATS_PER_TRIANGLE > welded.size) welded = welded.copyOf(welded.size * 2)
                    for (corner in 0 until VERTICES_PER_TRIANGLE) {
                        val base = current.corners[corner] * FLOATS_PER_VERTEX
                        welded[weldedFloats++] = triangles[base]
                        welded[weldedFloats++] = triangles[base + 1]
                        welded[weldedFloats++] = triangles[base + 2]
                        welded[weldedFloats++] = current.normals[corner * 3]
                        welded[weldedFloats++] = current.normals[corner * 3 + 1]
                        welded[weldedFloats++] = current.normals[corner * 3 + 2]
                    }
                    continue
                }
                val edge = split.first
                val between = split.second
                val first = current.corners[edge]
                val second = current.corners[(edge + 1) % VERTICES_PER_TRIANGLE]
                val opposite = current.corners[(edge + 2) % VERTICES_PER_TRIANGLE]
                val secondAt = ((edge + 1) % VERTICES_PER_TRIANGLE) * 3
                val oppositeAt = ((edge + 2) % VERTICES_PER_TRIANGLE) * 3
                val firstNormal = floatArrayOf(current.normals[edge * 3], current.normals[edge * 3 + 1], current.normals[edge * 3 + 2])
                val secondNormal = floatArrayOf(current.normals[secondAt], current.normals[secondAt + 1], current.normals[secondAt + 2])
                val oppositeNormal = floatArrayOf(current.normals[oppositeAt], current.normals[oppositeAt + 1], current.normals[oppositeAt + 2])
                var previous = first
                var previousNormal = firstNormal
                for ((fraction, vertex) in between) {
                    // The split sits on the edge, so its normal is the edge's
                    // own, interpolated the same way the cut interpolates one.
                    val vertexNormal = floatArrayOf(
                        firstNormal[0] + (secondNormal[0] - firstNormal[0]) * fraction,
                        firstNormal[1] + (secondNormal[1] - firstNormal[1]) * fraction,
                        firstNormal[2] + (secondNormal[2] - firstNormal[2]) * fraction,
                    )
                    pending.addLast(
                        WeldTriangle(
                            intArrayOf(previous, vertex, opposite),
                            floatArrayOf(
                                previousNormal[0], previousNormal[1], previousNormal[2],
                                vertexNormal[0], vertexNormal[1], vertexNormal[2],
                                oppositeNormal[0], oppositeNormal[1], oppositeNormal[2],
                            ),
                        ),
                    )
                    previous = vertex
                    previousNormal = vertexNormal
                }
                pending.addLast(
                    WeldTriangle(
                        intArrayOf(previous, second, opposite),
                        floatArrayOf(
                            previousNormal[0], previousNormal[1], previousNormal[2],
                            secondNormal[0], secondNormal[1], secondNormal[2],
                            oppositeNormal[0], oppositeNormal[1], oppositeNormal[2],
                        ),
                    ),
                )
            }
        }
        return if (weldedFloats == welded.size) welded else welded.copyOf(weldedFloats)
    }

    /** The three corner normals of a triangle, in the interleaved layout. */
    private fun normalsOf(triangles: FloatArray, corners: IntArray): FloatArray {
        val normals = FloatArray(VERTICES_PER_TRIANGLE * 3)
        for (corner in 0 until VERTICES_PER_TRIANGLE) {
            val base = corners[corner] * FLOATS_PER_VERTEX + 3
            normals[corner * 3] = triangles[base]
            normals[corner * 3 + 1] = triangles[base + 1]
            normals[corner * 3 + 2] = triangles[base + 2]
        }
        return normals
    }

    /** A triangle on its way through the weld: its corners and their normals. */
    private class WeldTriangle(val corners: IntArray, val normals: FloatArray)

    /** True when two of a triangle's corners sit at the same position. */
    private fun cornerPositionsMatch(triangles: FloatArray, a: Int, b: Int, c: Int): Boolean {
        val first = positionKeyOf(triangles, a)
        val second = positionKeyOf(triangles, b)
        if (first == second) return true
        val third = positionKeyOf(triangles, c)
        return third == first || third == second
    }

    private fun positionKeyOf(triangles: FloatArray, corner: Int): PositionKey {
        val base = corner * FLOATS_PER_VERTEX
        return PositionKey.of(triangles[base], triangles[base + 1], triangles[base + 2])
    }

    /**
     * The edge of [triangle] that has one of the section's own vertices sitting
     * inside it, and those vertices along that edge, or null when there is no
     * such edge. An endpoint is never a split: it is already a corner.
     */
    private fun sectionSplit(
        triangle: IntArray,
        onPlane: BooleanArray,
        planeU: FloatArray,
        planeV: FloatArray,
        cells: HashMap<Long, MutableList<Int>>,
    ): Pair<Int, List<Pair<Float, Int>>>? {
        for (edge in 0 until VERTICES_PER_TRIANGLE) {
            val first = triangle[edge]
            val second = triangle[(edge + 1) % VERTICES_PER_TRIANGLE]
            val opposite = triangle[(edge + 2) % VERTICES_PER_TRIANGLE]
            if (!onPlane[first] || !onPlane[second]) continue
            val ax = planeU[first]
            val ay = planeV[first]
            val dx = planeU[second] - ax
            val dy = planeV[second] - ay
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared <= SECTION_WELD_MIN_MM * SECTION_WELD_MIN_MM) continue
            val found = ArrayList<Pair<Float, Int>>(4)
            for (cell in cellsAlong(ax, ay, ax + dx, ay + dy)) {
                cells[cell]?.forEach { candidate ->
                    if (candidate == first || candidate == second) return@forEach
                    val px = planeU[candidate] - ax
                    val py = planeV[candidate] - ay
                    val fraction = (px * dx + py * dy) / lengthSquared
                    if (fraction <= SECTION_WELD_FRACTION || fraction >= 1f - SECTION_WELD_FRACTION) return@forEach
                    val offX = px - fraction * dx
                    val offY = py - fraction * dy
                    if (offX * offX + offY * offY > SECTION_WELD_EPSILON_MM * SECTION_WELD_EPSILON_MM) return@forEach
                    found.add(fraction to candidate)
                }
            }
            if (found.isEmpty()) continue
            // One splitting vertex per position: the same point can be a corner
            // of several triangles, and fanning through it twice would only make
            // slivers. A triangle whose own corners are collinear has no area to
            // split either.
            if (Math.abs((planeU[second] - ax) * (planeV[opposite] - ay) - (planeV[second] - ay) * (planeU[opposite] - ax)) <= SECTION_WELD_AREA_MM2) {
                return null
            }
            found.sortBy { it.first }
            val vertices = LinkedHashMap<PositionKey, Pair<Float, Int>>()
            for (candidate in found) {
                vertices.putIfAbsent(
                    PositionKey.of(planeU[candidate.second], planeV[candidate.second], 0f),
                    candidate,
                )
            }
            vertices.remove(PositionKey.of(planeU[opposite], planeV[opposite], 0f))
            if (vertices.isEmpty()) continue
            return edge to vertices.values.toList()
        }
        return null
    }

    /** Every cell the segment ([ax], [ay]) to ([bx], [by]) passes near. */
    private fun cellsAlong(ax: Float, ay: Float, bx: Float, by: Float): List<Long> {
        val lowX = cellOf(minOf(ax, bx))
        val highX = cellOf(maxOf(ax, bx))
        val lowY = cellOf(minOf(ay, by))
        val highY = cellOf(maxOf(ay, by))
        val keys = ArrayList<Long>((highX - lowX + 1) * (highY - lowY + 1))
        for (x in lowX..highX) {
            for (y in lowY..highY) keys.add(cellKey(x, y))
        }
        return keys
    }

    private fun cellKey(u: Float, v: Float): Long = cellKey(cellOf(u), cellOf(v))

    private fun cellKey(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

    private fun cellOf(coordinate: Float): Int = Math.floor((coordinate / SECTION_WELD_CELL_MM).toDouble()).toInt()

    /** True when any corner of the triangle is on the removed side of the plane. */
    private fun crossesThePlane(
        triangle: FloatArray,
        axis: ModelPlacement.Axis,
        offsetMm: Float,
        sign: Float,
    ): Boolean = CUT_CHANNELS.any { channel ->
        strictlyBelow(sign * (triangle[channel + positionChannel(axis)] - offsetMm))
    }

    /** Where the axis's own position sits inside a vertex: X, Y or Z. */
    private fun positionChannel(axis: ModelPlacement.Axis): Int = when (axis) {
        ModelPlacement.Axis.X -> 0
        ModelPlacement.Axis.Y -> 1
        ModelPlacement.Axis.Z -> 2
    }

    /**
     * True when a corner at [distance] from the plane is on the removed side by
     * more than the tolerance.
     *
     * The tolerance is what keeps a placed mesh from being re-triangulated over
     * float dust: a model that sits exactly on the bed can land a millionth of a
     * millimetre below it, and that is a corner on the plane, not under it.
     */
    private fun strictlyBelow(distance: Float): Boolean = distance < -PLANE_EPSILON_MM

    /**
     * The cut face as triangles: the plane region the removed half stood on.
     *
     * The segments are first chained into the cross-section's loops, then each
     * strip between two neighbouring heights across the section is filled between
     * the edges that cross it, paired left to right. That is even-odd filling of
     * the outline, so a concave section and one with holes in it both come out
     * right - which is what the engines themselves do with the polygons they
     * slice. An outline that will not chain is left uncapped rather than guessed
     * at.
     */
    private fun crossSectionCap(
        segments: List<FloatArray>,
        axis: ModelPlacement.Axis,
        offsetMm: Float,
        half: Half,
    ): FloatArray {
        val loops = chainCrossSection(segments) ?: return EMPTY_FLOATS
        val projected = loops.map { loop ->
            FloatArray(loop.size / 3 * 2).also { points ->
                var read = 0
                var write = 0
                while (read < loop.size) {
                    points[write++] = planeCoordinate(loop[read], loop[read + 1], loop[read + 2], axis, first = true)
                    points[write++] = planeCoordinate(loop[read], loop[read + 1], loop[read + 2], axis, first = false)
                    read += 3
                }
            }
        }
        // Every height a corner of the section sits at. Between two neighbouring
        // ones every edge is a single straight run, so the region is a set of
        // trapezoids.
        val levels = sortedSetOf<Float>()
        projected.forEach { points ->
            var index = 1
            while (index < points.size) {
                levels.add(points[index])
                index += 2
            }
        }
        val heights = levels.toFloatArray()
        // The cap faces the material that was removed: down from a high half, up
        // from a low one.
        val outward = if (half == Half.HIGH) -1f else 1f
        val triangles = ArrayList<Float>(LOOP_CAPACITY)
        for (band in 0 until heights.size - 1) {
            val low = heights[band]
            val high = heights[band + 1]
            if (high <= low) continue
            val middle = (low + high) * 0.5f
            // Every edge crossing this strip, with where it is met on each side of
            // the strip and in its middle, so the pairing left to right is the
            // strip's own interior.
            val edges = ArrayList<FloatArray>(8)
            for (points in projected) {
                var index = 0
                while (index < points.size) {
                    val next = (index + 2) % points.size
                    val u0 = points[index]
                    val v0 = points[index + 1]
                    val u1 = points[next]
                    val v1 = points[next + 1]
                    if (v0 != v1 && ((v0 <= middle && v1 > middle) || (v1 <= middle && v0 > middle))) {
                        val slope = (u1 - u0) / (v1 - v0)
                        val top = maxOf(v0, v1)
                        val bottom = minOf(v0, v1)
                        edges.add(
                            floatArrayOf(
                                (low.coerceIn(bottom, top) - v0) * slope + u0,
                                (high.coerceIn(bottom, top) - v0) * slope + u0,
                                (middle - v0) * slope + u0,
                            ),
                        )
                    }
                    index += 2
                }
            }
            edges.sortBy { it[2] }
            var pair = 0
            while (pair + 1 < edges.size) {
                val left = edges[pair]
                val right = edges[pair + 1]
                appendCapTriangle(
                    triangles,
                    left[0], low, right[0], low, right[1], high,
                    axis, offsetMm, outward,
                )
                appendCapTriangle(
                    triangles,
                    left[0], low, right[1], high, left[1], high,
                    axis, offsetMm, outward,
                )
                pair += 2
            }
        }
        return triangles.toFloatArray()
    }

    /**
     * The cross-section segments as closed loops of positions, or null when they
     * do not chain - which on closed, manifold geometry they always do.
     *
     * Each segment runs the way the triangle that produced it was wound, so the
     * loops come out consistently oriented; the cap turns them round as a whole
     * to face the removed material.
     */
    private fun chainCrossSection(segments: List<FloatArray>): List<FloatArray>? {
        // Every crossing point is shared by exactly two segment ends on closed
        // geometry, whichever way round each segment runs - the two triangles
        // beside a crossing edge walk it in opposite directions.
        val ends = HashMap<PositionKey, MutableList<Int>>(segments.size * 2)
        segments.forEachIndexed { index, segment ->
            ends.getOrPut(PositionKey.of(segment[0], segment[1], segment[2])) { ArrayList(2) }
                .add(index * 2)
            ends.getOrPut(PositionKey.of(segment[3], segment[4], segment[5])) { ArrayList(2) }
                .add(index * 2 + 1)
        }
        val used = BooleanArray(segments.size)
        val loops = ArrayList<FloatArray>(4)
        for (seed in segments.indices) {
            if (used[seed]) continue
            val first = PositionKey.of(segments[seed][0], segments[seed][1], segments[seed][2])
            val points = ArrayList<Float>(64)
            var index = seed
            var entered = 0
            while (true) {
                used[index] = true
                val segment = segments[index]
                points.add(segment[entered])
                points.add(segment[entered + 1])
                points.add(segment[entered + 2])
                val exit = if (entered == 0) 3 else 0
                val exitKey = PositionKey.of(segment[exit], segment[exit + 1], segment[exit + 2])
                if (exitKey == first) break
                var following = -1
                var followingEntry = 0
                ends[exitKey]?.forEach { end ->
                    val candidate = end / 2
                    if (following < 0 && candidate != index && !used[candidate]) {
                        following = candidate
                        followingEntry = if (end % 2 == 0) 0 else 3
                    }
                }
                if (following < 0) return null
                index = following
                entered = followingEntry
            }
            loops.add(points.toFloatArray())
        }
        return loops.takeIf { it.isNotEmpty() }
    }

    /**
     * One cap triangle from three points of the plane, in plane coordinates.
     *
     * A sliver is dropped - the section can have corners on the plane, and a
     * triangle with no area is not geometry - and a triangle facing the kept
     * material is turned round, so every cap faces the removed half.
     */
    private fun appendCapTriangle(
        out: ArrayList<Float>,
        au: Float,
        av: Float,
        bu: Float,
        bv: Float,
        cu: Float,
        cv: Float,
        axis: ModelPlacement.Axis,
        offsetMm: Float,
        outward: Float,
    ) {
        // In plane coordinates the third dimension is constant, so the triangle's
        // normal along the cut axis is exactly twice its signed area.
        val twiceArea = (bu - au) * (cv - av) - (cu - au) * (bv - av)
        if (twiceArea == 0f) return
        val flip = (twiceArea > 0f) != (outward > 0f)
        fun emit(u: Float, v: Float) {
            when (axis) {
                ModelPlacement.Axis.X -> {
                    out.add(offsetMm)
                    out.add(u)
                    out.add(v)
                }

                // The plane's own axes, back in the order they came from:
                // [planeCoordinate] reads Y as (z, x).
                ModelPlacement.Axis.Y -> {
                    out.add(v)
                    out.add(offsetMm)
                    out.add(u)
                }

                ModelPlacement.Axis.Z -> {
                    out.add(u)
                    out.add(v)
                    out.add(offsetMm)
                }
            }
            out.add(0f)
            out.add(0f)
            out.add(0f)
            out[out.size - 3 + positionChannel(axis)] = outward
        }
        emit(au, av)
        if (flip) {
            emit(cu, cv)
            emit(bu, bv)
        } else {
            emit(bu, bv)
            emit(cu, cv)
        }
    }

    /**
     * The plane's two axes, taken in the order Y-Z, Z-X, X-Y.
     *
     * That order is the right-handed one around [axis], which is what makes the
     * normal along the axis in [appendCapTriangle] exactly twice the signed area
     * of the triangle read in these two.
     */
    private fun planeCoordinate(x: Float, y: Float, z: Float, axis: ModelPlacement.Axis, first: Boolean): Float =
        when (axis) {
            ModelPlacement.Axis.X -> if (first) y else z
            ModelPlacement.Axis.Y -> if (first) z else x
            ModelPlacement.Axis.Z -> if (first) x else y
        }

    /** A position rounded to a tenth of a micron, for matching segment ends. */
    private data class PositionKey(val x: Int, val y: Int, val z: Int) {
        companion object {
            fun of(x: Float, y: Float, z: Float): PositionKey = PositionKey(
                Math.round(x * MATCH_PER_MM),
                Math.round(y * MATCH_PER_MM),
                Math.round(z * MATCH_PER_MM),
            )
        }
    }

    private inline fun forEachTriangle(mesh: StlMesh, block: (FloatArray) -> Unit) {
        val vertices = mesh.interleavedVertices
        val triangle = FloatArray(FLOATS_PER_TRIANGLE)
        var offset = 0
        repeat(mesh.triangleCount) {
            for (index in 0 until FLOATS_PER_TRIANGLE) triangle[index] = vertices[offset + index]
            block(triangle)
            offset += FLOATS_PER_TRIANGLE
        }
    }

    /** One vertex of the 18-float interleaved position+normal layout. */
    private class ClipVertex(
        val x: Float,
        val y: Float,
        val z: Float,
        val nx: Float,
        val ny: Float,
        val nz: Float,
    ) {
        /** This vertex's coordinate along [axis]. */
        fun along(axis: ModelPlacement.Axis): Float = when (axis) {
            ModelPlacement.Axis.X -> x
            ModelPlacement.Axis.Y -> y
            ModelPlacement.Axis.Z -> z
        }

        companion object {
            fun from(triangle: FloatArray, slot: Int): ClipVertex {
                val base = slot * 6
                return ClipVertex(
                    x = triangle[base],
                    y = triangle[base + 1],
                    z = triangle[base + 2],
                    nx = triangle[base + 3],
                    ny = triangle[base + 4],
                    nz = triangle[base + 5],
                )
            }

            /**
             * The point [fraction] of the way from [from] to [to], with its
             * coordinate along [axis] set to exactly [offsetMm].
             *
             * The walk from a corner on the removed side to one that is not
             * crosses the plane exactly once, at a parameter in [0, 1]: the
             * returned position is a convex combination of the two vertices,
             * which keeps it inside the mesh's own bounds. The vertex normal is
             * interpolated the same way.
             */
            fun between(
                from: ClipVertex,
                to: ClipVertex,
                fraction: Float,
                axis: ModelPlacement.Axis,
                offsetMm: Float,
            ): ClipVertex {
                val f = fraction.coerceIn(0f, 1f)
                val x = from.x + (to.x - from.x) * f
                val y = from.y + (to.y - from.y) * f
                val z = from.z + (to.z - from.z) * f
                return ClipVertex(
                    x = if (axis == ModelPlacement.Axis.X) offsetMm else x,
                    y = if (axis == ModelPlacement.Axis.Y) offsetMm else y,
                    z = if (axis == ModelPlacement.Axis.Z) offsetMm else z,
                    nx = from.nx + (to.nx - from.nx) * f,
                    ny = from.ny + (to.ny - from.ny) * f,
                    nz = from.nz + (to.nz - from.nz) * f,
                )
            }
        }
    }

    private const val VERTICES_PER_TRIANGLE = 3
    private const val FLOATS_PER_VERTEX = 6
    private const val FLOATS_PER_TRIANGLE = 18

    /** The most triangles one clipped triangle can become: two crossings. */
    private const val MAX_TRIANGLES_PER_CUT = 2
    private const val PLANE_EPSILON_MM = 1e-6f

    /** Position matching for the cross-section chain: a tenth of a micron. */
    private const val MATCH_PER_MM = 10_000f

    /** The grid the cut plane's own vertices are hashed into for the weld. */
    private const val SECTION_WELD_CELL_MM = 1f

    /** How far off a straight edge a vertex may sit and still be on it. */
    private const val SECTION_WELD_EPSILON_MM = 5e-4f

    /** An edge shorter than this is a point, not an edge to split. */
    private const val SECTION_WELD_MIN_MM = 1e-3f

    /** Twice the area below which a triangle is a sliver, not a face to split. */
    private const val SECTION_WELD_AREA_MM2 = 1e-6f

    /** How far along an edge a split vertex must sit to not be an endpoint. */
    private const val SECTION_WELD_FRACTION = 1e-3f

    /** Room for the first cap triangles; the list grows past it when it has to. */
    private const val LOOP_CAPACITY = 256

    private val EMPTY_FLOATS = FloatArray(0)

    /** Where each corner's position starts inside one 18-float triangle. */
    private val CUT_CHANNELS = intArrayOf(0, 6, 12)
    private val EMPTY_BOUNDS = MeshBounds(0f, 0f, 0f, 0f, 0f, 0f)
}

/**
 * The range of coordinates the box spans along [axis]: what a cut on that axis
 * has to sit inside to leave geometry on both sides.
 */
fun MeshBounds.spanAlong(axis: ModelPlacement.Axis): ClosedFloatingPointRange<Float> = when (axis) {
    ModelPlacement.Axis.X -> minX..maxX
    ModelPlacement.Axis.Y -> minY..maxY
    ModelPlacement.Axis.Z -> minZ..maxZ
}
