package com.tomppi.enderslicer.viewer

import java.util.Arrays

/**
 * Fills the holes in an open mesh, so the boolean engine will take it.
 *
 * A slicer does not care about a hole: it works layer by layer in 2D and never
 * has to decide inside from outside. A mesh boolean does, which is why Manifold
 * refuses a model that is not watertight. Scans and exported meshes routinely
 * have a few open edges, so before a joint is unioned on, the holes are closed
 * here.
 *
 * What it does, and only this: an edge used by exactly one triangle is a
 * boundary edge; boundary edges are chained through shared positions into
 * closed loops; each loop is filled by a fan from one of its own vertices,
 * wound *against* the loop's own direction so the patch faces out of the solid
 * the way the surface around it does. The patch reuses the loop's exact float
 * positions, so the seam welds.
 *
 * What it refuses, loudly, rather than guessing: a loop longer than
 * [Limits.maxLoopEdges], a loop any of whose edges (rim or fan spoke) is longer
 * than [Limits.maxFillEdgeMm], a boundary that does not chain into a closed
 * loop, and a mesh with no interior vertex at all - a single triangle and a
 * pair of triangles are open *surfaces*, not solids with a hole, and filling
 * them would only build a zero-volume shell. Every loop it declines to fill is
 * named in [Report.reason]; nothing is dropped silently.
 *
 * With nothing to do the very same [StlMesh] instance comes back, like
 * [BedClipper] does with a mesh that is already on the right side of its plane.
 */
object MeshRepair {
    /** The ceiling on how much one call will consider: 2048 edges, 25 mm. */
    const val DEFAULT_MAX_LOOP_EDGES = 2048

    /** A hole whose rim, or whose fan spokes, reach further than this is left open. */
    const val DEFAULT_MAX_FILL_EDGE_MM = 25f

    /** The caps on one repair pass; both exist so a runaway mesh cannot be chewed on. */
    data class Limits(
        val maxLoopEdges: Int = DEFAULT_MAX_LOOP_EDGES,
        val maxFillEdgeMm: Float = DEFAULT_MAX_FILL_EDGE_MM,
    )

    /** What a repair pass found and did. */
    data class Report(
        val loopsFound: Int,
        val loopsFilled: Int,
        /** Boundary edges that came out closed, all loops together. */
        val edgesClosed: Int,
        val loopsLeftOpen: Int,
        /** Why the loops left open were left open; null when there were none. */
        val reason: String?,
    ) {
        val changed: Boolean get() = loopsFilled > 0

        /** One line for a caller to show: "closed 3 holes (533 edges)". */
        val summary: String
            get() {
                if (loopsFound == 0) return "closed already"
                val text = StringBuilder("closed ")
                text.append(loopsFilled)
                text.append(if (loopsFilled == 1) " hole" else " holes")
                text.append(" (").append(edgesClosed).append(" edges)")
                if (loopsLeftOpen > 0) {
                    text.append(", left ").append(loopsLeftOpen).append(" open")
                    if (reason != null) text.append(": ").append(reason)
                }
                return text.toString()
            }
    }

    data class Result(val mesh: StlMesh, val report: Report)

    /**
     * [mesh] with its holes filled, and a report of what was and was not
     * closed. The same instance is returned when there is nothing to do.
     */
    fun repair(mesh: StlMesh, limits: Limits = Limits()): Result {
        val triangles = mesh.triangleCount
        val vertices = mesh.interleavedVertices
        val nothing = Report(0, 0, 0, 0, null)
        if (triangles <= 0 || vertices.size != triangles * MeshSolidBuilder.FLOATS_PER_TRIANGLE) {
            return Result(mesh, nothing)
        }

        // Weld by position, at the same tenth-of-a-micron key the clipper chains
        // its cross-section with: an STL is a triangle soup, and two triangles
        // only share an edge if their corner positions agree exactly.
        val weldedIds = HashMap<PositionKey, Int>(triangles * 2)
        val positions = ArrayList<Float>(triangles * 3)
        val corners = IntArray(triangles * 3)
        var welded = 0
        for (triangle in 0 until triangles) {
            for (corner in 0 until 3) {
                val base = (triangle * 3 + corner) * MeshSolidBuilder.FLOATS_PER_VERTEX
                val x = vertices[base]
                val y = vertices[base + 1]
                val z = vertices[base + 2]
                val key = PositionKey.of(x, y, z)
                val known = weldedIds[key]
                if (known != null) {
                    corners[triangle * 3 + corner] = known
                } else {
                    weldedIds[key] = welded
                    corners[triangle * 3 + corner] = welded
                    positions.add(x)
                    positions.add(y)
                    positions.add(z)
                    welded++
                }
            }
        }
        if (welded == 0) return Result(mesh, nothing)

        // Every directed edge, sorted, so an edge's reverse can be looked up.
        val directed = LongArray(triangles * 3)
        var directedCount = 0
        for (triangle in 0 until triangles) {
            for (corner in 0 until 3) {
                val from = corners[triangle * 3 + corner]
                val to = corners[triangle * 3 + (corner + 1) % 3]
                if (from == to) continue
                directed[directedCount++] = edgeKey(from, to)
            }
        }
        if (directedCount == 0) return Result(mesh, nothing)
        Arrays.sort(directed, 0, directedCount)

        // The boundary: a directed edge whose reverse is nowhere.
        val boundary = ArrayList<Long>()
        var index = 0
        while (index < directedCount) {
            val edge = directed[index]
            if (boundary.isEmpty() || boundary[boundary.size - 1] != edge) {
                if (Arrays.binarySearch(directed, 0, directedCount, reverse(edge)) < 0) boundary.add(edge)
            }
            index++
        }
        if (boundary.isEmpty()) return Result(mesh, nothing)

        // A mesh with no closed vertex is an open sheet, not a solid with a hole.
        val open = BooleanArray(welded)
        val undirected = LongArray(directedCount)
        for (edge in 0 until directedCount) {
            val from = (directed[edge] ushr 32).toInt()
            val to = directed[edge].toInt()
            undirected[edge] = edgeKey(minOf(from, to), maxOf(from, to))
        }
        Arrays.sort(undirected)
        index = 0
        while (index < directedCount) {
            var run = index
            while (run < directedCount && undirected[run] == undirected[index]) run++
            if (run - index != 2) {
                open[(undirected[index] ushr 32).toInt()] = true
                open[undirected[index].toInt()] = true
            }
            index = run
        }
        val solid = (0 until welded).any { !open[it] }

        // Chain the boundary into loops; each boundary vertex has one way out on
        // well-formed geometry, and anything else is reported rather than fixed.
        val outgoing = HashMap<Int, Int>(boundary.size * 2)
        for (edge in boundary) outgoing.putIfAbsent((edge ushr 32).toInt(), edge.toInt())

        val used = HashSet<Long>(boundary.size * 2)
        val patch = ArrayList<Float>()
        val failures = LinkedHashMap<String, Int>()
        var loopsFound = 0
        var loopsFilled = 0
        var edgesClosed = 0
        for (start in boundary) {
            if (start in used) continue
            loopsFound++
            val loop = ArrayList<Int>()
            loop.add((start ushr 32).toInt())
            var edge = start
            var failure: String? = null
            while (true) {
                used.add(edge)
                val to = edge.toInt()
                if (to == loop[0]) break
                if (loop.contains(to)) {
                    failure = "a boundary that revisits a vertex"
                    break
                }
                loop.add(to)
                if (loop.size > limits.maxLoopEdges) {
                    failure = "a hole of more than " + limits.maxLoopEdges + " edges"
                    break
                }
                val next = outgoing[to]
                if (next == null) {
                    failure = "a boundary that ends without closing"
                    break
                }
                edge = edgeKey(to, next)
            }

            if (failure == null && !solid) {
                // Filling one loop of an open surface would only build a shell
                // with no inside, so an open surface is refused whole.
                failure = OPEN_SURFACE
            }

            if (failure == null) {
                // The fill needs to reach across the hole; a rim edge or a fan
                // spoke longer than the cap means this is not a small hole.
                for (corner in loop.indices) {
                    val next = loop[(corner + 1) % loop.size]
                    if (distance(positions, loop[corner], next) > limits.maxFillEdgeMm) {
                        failure = "a hole spanning more than " + limits.maxFillEdgeMm + " mm"
                        break
                    }
                }
                if (failure == null) {
                    for (corner in 1 until loop.size) {
                        if (distance(positions, loop[0], loop[corner]) > limits.maxFillEdgeMm) {
                            failure = "a hole spanning more than " + limits.maxFillEdgeMm + " mm"
                            break
                        }
                    }
                }
            }

            if (failure != null) {
                // The walk may have stopped part way round - at the edge cap,
                // say - and the edges it never reached are still part of this
                // same hole. Walk the rest without keeping it, so the next loop
                // is the next hole and not the tail of this one.
                var cursor = edge
                var guard = 0
                while (guard++ <= boundary.size) {
                    val following = outgoing[cursor.toInt()] ?: break
                    val next = edgeKey(cursor.toInt(), following)
                    if (next == start || !used.add(next)) break
                    cursor = next
                }
                failures[failure] = (failures[failure] ?: 0) + 1
                continue
            }

            // Fan from the loop's first vertex, wound against the loop so the
            // patch's normals face the same way as the surface around it.
            for (corner in 1 until loop.size - 1) {
                add(positions, patch, loop[0])
                add(positions, patch, loop[corner + 1])
                add(positions, patch, loop[corner])
            }
            loopsFilled++
            edgesClosed += loop.size
        }

        val reason = if (failures.isEmpty()) {
            null
        } else {
            failures.entries.joinToString("; ") { (why, count) -> if (count == 1) why else count.toString() + " holes: " + why }
        }
        if (loopsFilled == 0) {
            val refusal = if (!solid) {
                val detail = reason?.takeIf { it != OPEN_SURFACE }
                OPEN_SURFACE + (detail?.let { " (" + it + ")" } ?: "")
            } else {
                reason
            }
            return Result(mesh, Report(loopsFound, 0, 0, loopsFound, refusal))
        }

        val builder = MeshSolidBuilder(mesh.displayName)
        for (triangle in 0 until triangles) builder.addInterleaved(vertices, triangle)
        var at = 0
        while (at < patch.size) {
            builder.addTriangle(
                patch[at], patch[at + 1], patch[at + 2],
                patch[at + 3], patch[at + 4], patch[at + 5],
                patch[at + 6], patch[at + 7], patch[at + 8],
            )
            at += 9
        }
        return Result(
            builder.build(),
            Report(loopsFound, loopsFilled, edgesClosed, loopsFound - loopsFilled, reason?.takeIf { loopsFound > loopsFilled }),
        )
    }

    private fun add(positions: ArrayList<Float>, patch: ArrayList<Float>, vertex: Int) {
        patch.add(positions[vertex * 3])
        patch.add(positions[vertex * 3 + 1])
        patch.add(positions[vertex * 3 + 2])
    }

    private fun distance(positions: ArrayList<Float>, first: Int, second: Int): Float {
        val dx = positions[first * 3] - positions[second * 3]
        val dy = positions[first * 3 + 1] - positions[second * 3 + 1]
        val dz = positions[first * 3 + 2] - positions[second * 3 + 2]
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun edgeKey(from: Int, to: Int): Long = (from.toLong() shl 32) or (to.toLong() and 0xffffffffL)

    private fun reverse(edge: Long): Long = edgeKey(edge.toInt(), (edge ushr 32).toInt())

    /** A position rounded to a tenth of a micron, for matching an edge's ends. */
    private data class PositionKey(val x: Int, val y: Int, val z: Int) {
        companion object {
            fun of(x: Float, y: Float, z: Float): PositionKey = PositionKey(
                Math.round(x * MATCH_PER_MM),
                Math.round(y * MATCH_PER_MM),
                Math.round(z * MATCH_PER_MM),
            )
        }
    }

    private const val MATCH_PER_MM = 10_000f

    private const val OPEN_SURFACE = "the mesh is an open surface, not a solid with a hole"
}
