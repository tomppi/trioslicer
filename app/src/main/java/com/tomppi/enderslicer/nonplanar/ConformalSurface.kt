package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.checkCancellation
import com.tomppi.enderslicer.viewer.StlMesh
import java.util.HashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The non-planar "surface projection" backend (Ahlers' method, as implemented
 * in the Slic3r_NonPlanar_Slicing fork): instead of warping the whole mesh,
 * the facets that face up and stay within the printable angle are grouped into
 * connected surface regions. The sliced toolpath is later projected straight
 * down onto these regions, so the nozzle follows the true 3D surface - diving
 * below the layer plane to the thinnest part and climbing to the thickest.
 */
internal class ConformalSurface(
    val regions: List<Region>,
    val diagnostics: ConformalDiagnostics,
) {
    /**
     * One connected, printable surface region. Triangles are stored as 9
     * floats each (x0,y0,z0,x1,y1,z1,x2,y2,z2) and indexed by a coarse XY grid
     * so ray-casts only test the triangles near the query point.
     */
    class Region(
        val triangles: FloatArray,
        val minX: Double,
        val minY: Double,
        val minZ: Double,
        val maxX: Double,
        val maxY: Double,
        val maxZ: Double,
        val areaMm2: Double,
        val gridOriginX: Double,
        val gridOriginY: Double,
        val gridCellMm: Double,
        val gridColumns: Int,
        val gridRows: Int,
        private val cellTriangleOffsets: IntArray,
        private val cellTriangles: IntArray,
    ) {
        val triangleCount: Int get() = triangles.size / 9

        /** Coarse rejection: is the query point near the region's XY projection? */
        fun maybeInside(x: Double, y: Double): Boolean {
            if (x < minX || x > maxX || y < minY || y > maxY) return false
            val gx = ((x - gridOriginX) / gridCellMm).toInt().coerceIn(0, gridColumns - 1)
            val gy = ((y - gridOriginY) / gridCellMm).toInt().coerceIn(0, gridRows - 1)
            return cellTriangleOffsets[gy * gridColumns + gx] != cellTriangleOffsets[gy * gridColumns + gx + 1]
        }

        /** Exact test: does any triangle's XY projection contain (x,y)? */
        fun contains(x: Double, y: Double): Boolean = surfaceZ(x, y) != null

        private class BoundaryIndex(
            val edges: FloatArray,
            val offsets: IntArray,
            val cellEdges: IntArray,
        )

        // Boundary edges (used by exactly one triangle) plus a cell index, so
        // the exact distance from a query point to the region boundary is
        // cheap. Derived lazily from the triangles - not part of the sidecar
        // format, rebuilt transparently after a read.
        private val boundaryIndex: BoundaryIndex by lazy {
            val counts = HashMap<Long, Int>(triangleCount * 3)
            val edges = HashMap<Long, FloatArray>(triangleCount * 3)
            for (t in 0 until triangleCount) {
                val offset = t * 9
                for (e in 0 until 3) {
                    val i = offset + e * 3
                    val j = offset + ((e + 1) % 3) * 3
                    val ax = triangles[i].toDouble()
                    val ay = triangles[i + 1].toDouble()
                    val bx = triangles[j].toDouble()
                    val by = triangles[j + 1].toDouble()
                    var x1 = ax; var y1 = ay; var x2 = bx; var y2 = by
                    if (x1 > x2 || (x1 == x2 && y1 > y2)) {
                        val tx = x1; val ty = y1
                        x1 = x2; y1 = y2; x2 = tx; y2 = ty
                    }
                    // Normalize -0.0 to +0.0: raw bits differ but the
                    // orientation swap above compares with ==, so a shared
                    // edge stored once as -0.0 and once as +0.0 would
                    // otherwise become two phantom boundary edges.
                    val kx1 = if (x1 == 0.0) 0.0 else x1
                    val ky1 = if (y1 == 0.0) 0.0 else y1
                    val kx2 = if (x2 == 0.0) 0.0 else x2
                    val ky2 = if (y2 == 0.0) 0.0 else y2
                    var key = kx1.toRawBits().toLong()
                    key = key * 31 + ky1.toRawBits().toLong()
                    key = key * 31 + kx2.toRawBits().toLong()
                    key = key * 31 + ky2.toRawBits().toLong()
                    counts[key] = (counts[key] ?: 0) + 1
                    edges[key] = floatArrayOf(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
                }
            }
            val boundaryEdges = ArrayList<FloatArray>(counts.size)
            for ((key, count) in counts) {
                if (count == 1) boundaryEdges += edges[key]!!
            }
            val edgeData = FloatArray(boundaryEdges.size * 4)
            for (index in boundaryEdges.indices) {
                val edge = boundaryEdges[index]
                edgeData[index * 4] = edge[0]
                edgeData[index * 4 + 1] = edge[1]
                edgeData[index * 4 + 2] = edge[2]
                edgeData[index * 4 + 3] = edge[3]
            }
            val cellCounts = IntArray(gridColumns * gridRows)
            for (edge in boundaryEdges) {
                val loX = max(min(edge[0], edge[2]), minX.toFloat())
                val hiX = min(max(edge[0], edge[2]), maxX.toFloat())
                val loY = max(min(edge[1], edge[3]), minY.toFloat())
                val hiY = min(max(edge[1], edge[3]), maxY.toFloat())
                val gx0 = ((loX - minX.toFloat()) / gridCellMm.toFloat()).toInt().coerceIn(0, gridColumns - 1)
                val gx1 = ((hiX - minX.toFloat()) / gridCellMm.toFloat()).toInt().coerceIn(0, gridColumns - 1)
                val gy0 = ((loY - minY.toFloat()) / gridCellMm.toFloat()).toInt().coerceIn(0, gridRows - 1)
                val gy1 = ((hiY - minY.toFloat()) / gridCellMm.toFloat()).toInt().coerceIn(0, gridRows - 1)
                for (gy in gy0..gy1) {
                    for (gx in gx0..gx1) {
                        cellCounts[gy * gridColumns + gx]++
                    }
                }
            }
            val offsets = IntArray(gridColumns * gridRows + 1)
            for (cell in 0 until gridColumns * gridRows) {
                offsets[cell + 1] = offsets[cell] + cellCounts[cell]
            }
            val cellEdges = IntArray(offsets[gridColumns * gridRows])
            val cursor = offsets.copyOf()
            for (index in boundaryEdges.indices) {
                val edge = boundaryEdges[index]
                val loX = max(min(edge[0], edge[2]), minX.toFloat())
                val hiX = min(max(edge[0], edge[2]), maxX.toFloat())
                val loY = max(min(edge[1], edge[3]), minY.toFloat())
                val hiY = min(max(edge[1], edge[3]), maxY.toFloat())
                val gx0 = ((loX - minX.toFloat()) / gridCellMm.toFloat()).toInt().coerceIn(0, gridColumns - 1)
                val gx1 = ((hiX - minX.toFloat()) / gridCellMm.toFloat()).toInt().coerceIn(0, gridColumns - 1)
                val gy0 = ((loY - minY.toFloat()) / gridCellMm.toFloat()).toInt().coerceIn(0, gridRows - 1)
                val gy1 = ((hiY - minY.toFloat()) / gridCellMm.toFloat()).toInt().coerceIn(0, gridRows - 1)
                for (gy in gy0..gy1) {
                    for (gx in gx0..gx1) {
                        val cell = gy * gridColumns + gx
                        cellEdges[cursor[cell]++] = index
                    }
                }
            }
            BoundaryIndex(edgeData, offsets, cellEdges)
        }

        /**
         * Exact distance from (x,y) to the nearest region boundary edge, or
         * +Infinity when no boundary edge is indexed nearby. Used to keep the
         * conformal shells a fixed back-off away from steep walls at the
         * region edge.
         */
        fun distanceToBoundary(x: Double, y: Double): Double {
            val index = boundaryIndex
            val gx = ((x - gridOriginX) / gridCellMm).toInt().coerceIn(0, gridColumns - 1)
            val gy = ((y - gridOriginY) / gridCellMm).toInt().coerceIn(0, gridRows - 1)
            var best = Double.POSITIVE_INFINITY
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val nx = gx + dx
                    val ny = gy + dy
                    if (nx !in 0 until gridColumns || ny !in 0 until gridRows) continue
                    val cell = ny * gridColumns + nx
                    for (entry in index.offsets[cell] until index.offsets[cell + 1]) {
                        val offset = index.cellEdges[entry] * 4
                        val distance = pointSegmentDistance(
                            x, y,
                            index.edges[offset].toDouble(), index.edges[offset + 1].toDouble(),
                            index.edges[offset + 2].toDouble(), index.edges[offset + 3].toDouble(),
                        )
                        if (distance < best) best = distance
                    }
                }
            }
            return best
        }

        /** The z of the topmost triangle whose XY projection covers (x,y), or null. */
        fun surfaceZ(x: Double, y: Double): Double? {
            if (!maybeInside(x, y)) return null
            val gx = ((x - gridOriginX) / gridCellMm).toInt().coerceIn(0, gridColumns - 1)
            val gy = ((y - gridOriginY) / gridCellMm).toInt().coerceIn(0, gridRows - 1)
            val cell = gy * gridColumns + gx
            var best: Double? = null
            for (entry in cellTriangleOffsets[cell] until cellTriangleOffsets[cell + 1]) {
                val offset = cellTriangles[entry] * 9
                val x0 = triangles[offset].toDouble()
                val y0 = triangles[offset + 1].toDouble()
                val z0 = triangles[offset + 2].toDouble()
                val x1 = triangles[offset + 3].toDouble()
                val y1 = triangles[offset + 4].toDouble()
                val z1 = triangles[offset + 5].toDouble()
                val x2 = triangles[offset + 6].toDouble()
                val y2 = triangles[offset + 7].toDouble()
                val z2 = triangles[offset + 8].toDouble()
                if (x < min(x0, min(x1, x2)) || x > max(x0, max(x1, x2)) ||
                    y < min(y0, min(y1, y2)) || y > max(y0, max(y1, y2))
                ) {
                    continue
                }
                if (!pointInTriangle(x, y, x0, y0, x1, y1, x2, y2)) continue
                val z = planeZ(x, y, x0, y0, z0, x1, y1, z1, x2, y2, z2) ?: continue
                if (best == null || z > best) best = z
            }
            return best
        }
    }

    companion object {
        private fun pointInTriangle(
            px: Double, py: Double,
            ax: Double, ay: Double,
            bx: Double, by: Double,
            cx: Double, cy: Double,
        ): Boolean {
            val d1 = crossSign(px, py, ax, ay, bx, by)
            val d2 = crossSign(px, py, bx, by, cx, cy)
            val d3 = crossSign(px, py, cx, cy, ax, ay)
            val hasNegative = d1 < 0.0 || d2 < 0.0 || d3 < 0.0
            val hasPositive = d1 > 0.0 || d2 > 0.0 || d3 > 0.0
            return !(hasNegative && hasPositive)
        }

        private fun crossSign(
            px: Double, py: Double,
            ax: Double, ay: Double,
            bx: Double, by: Double,
        ): Double = (px - bx) * (ay - by) - (ax - bx) * (py - by)

        private fun planeZ(
            px: Double, py: Double,
            x0: Double, y0: Double, z0: Double,
            x1: Double, y1: Double, z1: Double,
            x2: Double, y2: Double, z2: Double,
        ): Double? {
            // Barycentric solve of p = p0 + u*(p1-p0) + v*(p2-p0).
            val denominator = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
            if (absSmall(denominator)) return null
            val u = ((y2 - y0) * (px - x0) - (x2 - x0) * (py - y0)) / denominator
            val v = ((x1 - x0) * (py - y0) - (y1 - y0) * (px - x0)) / denominator
            return z0 + u * (z1 - z0) + v * (z2 - z0)
        }

        private fun pointSegmentDistance(
            px: Double, py: Double,
            ax: Double, ay: Double,
            bx: Double, by: Double,
        ): Double {
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared <= 0.0) return hypot(px - ax, py - ay)
            val t = ((px - ax) * dx + (py - ay) * dy) / lengthSquared
            val clamped = t.coerceIn(0.0, 1.0)
            return hypot(px - (ax + dx * clamped), py - (ay + dy * clamped))
        }

        private fun absSmall(value: Double): Boolean = value > -1e-12 && value < 1e-12
    }
}

internal data class ConformalDiagnostics(
    val sourceTriangles: Int,
    val candidateTriangles: Int,
    val regionsFound: Int,
    val regionsFilteredBySpan: Int,
    val regionsFilteredByArea: Int,
)

/**
 * Builds [ConformalSurface] regions from the displayed mesh: collect the
 * up-facing facets within the printable angle, group them by shared edges into
 * connected components, then keep the components whose height span fits the
 * nozzle travel and whose area is worth curving.
 */
internal object ConformalSurfaceBuilder {
    const val MIN_REGION_AREA_MM2 = 20.0
    private const val GRID_CELL_MM = 1.0
    private const val VERTEX_QUANTUM_MM = 1e-3
    /** The 21 bits each quantised axis occupies in the packed weld key. */
    private const val FIELD_MASK = 0x1FFFFFL

    fun build(mesh: StlMesh, settings: NonPlanarSettings): ConformalSurface {
        val safe = settings.validated()
        val maxAngle = safe.effectiveSlopeLimitDegrees
        val cosLimit = cos(Math.toRadians(maxAngle))
        val vertices = mesh.interleavedVertices
        val triangleCount = mesh.triangleCount

        // Pass 1: candidate facets (up-facing and within the printable angle).
        val candidates = ArrayList<Int>(triangleCount)
        for (triangle in 0 until triangleCount) {
            checkCancellation(triangle, "Conformal surface search")
            val offset = triangle * 18
            val ax = vertices[offset].toDouble()
            val ay = vertices[offset + 1].toDouble()
            val az = vertices[offset + 2].toDouble()
            val bx = vertices[offset + 6].toDouble()
            val by = vertices[offset + 7].toDouble()
            val bz = vertices[offset + 8].toDouble()
            val cx = vertices[offset + 12].toDouble()
            val cy = vertices[offset + 13].toDouble()
            val cz = vertices[offset + 14].toDouble()
            val ux = bx - ax; val uy = by - ay; val uz = bz - az
            val vx = cx - ax; val vy = cy - ay; val vz = cz - az
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length <= 1e-12) continue
            nx /= length; ny /= length; nz /= length
            if (nz >= cosLimit) candidates += triangle
        }

        // Pass 2: connect candidates through shared edges (quantized vertex ids).
        val vertexIds = HashMap<Long, Int>(candidates.size * 3)
        fun vertexId(x: Double, y: Double, z: Double): Int {
            val key = packVertex(x, y, z)
            return vertexIds.getOrPut(key) { vertexIds.size }
        }
        val edgeTriangles = HashMap<Long, ArrayList<Int>>()
        for (candidateIndex in candidates.indices) {
            val triangle = candidates[candidateIndex]
            val offset = triangle * 18
            val ids = IntArray(3)
            for (vertex in 0 until 3) {
                val base = offset + vertex * 6
                ids[vertex] = vertexId(
                    vertices[base].toDouble(),
                    vertices[base + 1].toDouble(),
                    vertices[base + 2].toDouble(),
                )
            }
            for (edge in 0 until 3) {
                val a = ids[edge]
                val b = ids[(edge + 1) % 3]
                val key = edgeKey(a, b)
                edgeTriangles.getOrPut(key) { ArrayList(2) }.add(candidateIndex)
            }
        }
        val parent = IntArray(candidates.size) { it }
        fun find(index: Int): Int {
            var root = index
            while (parent[root] != root) root = parent[root]
            var current = index
            while (parent[current] != current) {
                val next = parent[current]
                parent[current] = root
                current = next
            }
            return root
        }
        fun union(a: Int, b: Int) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) parent[maxOf(rootA, rootB)] = minOf(rootA, rootB)
        }
        for (sharing in edgeTriangles.values) {
            if (sharing.size < 2) continue
            for (index in 1 until sharing.size) union(sharing[0], sharing[index])
        }

        // Pass 3: per-component stats + filters.
        val componentBounds = HashMap<Int, DoubleArray>() // root -> minX,minY,minZ,maxX,maxY,maxZ
        val componentArea = HashMap<Int, Double>()
        val componentTriangles = HashMap<Int, ArrayList<Int>>()
        for (candidateIndex in candidates.indices) {
            val triangle = candidates[candidateIndex]
            val offset = triangle * 18
            val x0 = vertices[offset].toDouble(); val y0 = vertices[offset + 1].toDouble(); val z0 = vertices[offset + 2].toDouble()
            val x1 = vertices[offset + 6].toDouble(); val y1 = vertices[offset + 7].toDouble(); val z1 = vertices[offset + 8].toDouble()
            val x2 = vertices[offset + 12].toDouble(); val y2 = vertices[offset + 13].toDouble(); val z2 = vertices[offset + 14].toDouble()
            val root = find(candidateIndex)
            val bounds = componentBounds.getOrPut(root) {
                doubleArrayOf(
                    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                )
            }
            bounds[0] = min(bounds[0], min(x0, min(x1, x2)))
            bounds[1] = min(bounds[1], min(y0, min(y1, y2)))
            bounds[2] = min(bounds[2], min(z0, min(z1, z2)))
            bounds[3] = max(bounds[3], max(x0, max(x1, x2)))
            bounds[4] = max(bounds[4], max(y0, max(y1, y2)))
            bounds[5] = max(bounds[5], max(z0, max(z1, z2)))
            val ux = x1 - x0; val uy = y1 - y0
            val vx = x2 - x0; val vy = y2 - y0
            // The reference filters on the XY-projected triangle area.
            componentArea.merge(root, 0.5 * abs(ux * vy - uy * vx)) { a, b -> a + b }
            componentTriangles.getOrPut(root) { ArrayList() }.add(triangle)
        }

        var filteredBySpan = 0
        var filteredByArea = 0
        val regions = ArrayList<ConformalSurface.Region>()
        for ((root, bounds) in componentBounds) {
            val spanZ = bounds[5] - bounds[2]
            if (spanZ > safe.maximumLiftMm) {
                filteredBySpan++
                continue
            }
            val area = componentArea[root] ?: 0.0
            if (area < MIN_REGION_AREA_MM2) {
                filteredByArea++
                continue
            }
            val component = componentTriangles[root] ?: continue
            val triangleData = FloatArray(component.size * 9)
            var dataOffset = 0
            for (triangle in component) {
                val offset = triangle * 18
                for (vertex in 0 until 3) {
                    val base = offset + vertex * 6
                    triangleData[dataOffset++] = vertices[base]
                    triangleData[dataOffset++] = vertices[base + 1]
                    triangleData[dataOffset++] = vertices[base + 2]
                }
            }
            regions += buildRegion(triangleData, bounds, area)
        }

        return ConformalSurface(
            regions = regions,
            diagnostics = ConformalDiagnostics(
                sourceTriangles = triangleCount,
                candidateTriangles = candidates.size,
                regionsFound = regions.size,
                regionsFilteredBySpan = filteredBySpan,
                regionsFilteredByArea = filteredByArea,
            ),
        )
    }

    /** Rebuilds the grid index for a region read back from the sidecar. */
    fun rebuildRegion(
        triangles: FloatArray,
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
        areaMm2: Double,
    ): ConformalSurface.Region = buildRegion(
        triangles,
        doubleArrayOf(minX, minY, minZ, maxX, maxY, maxZ),
        areaMm2,
    )

    private fun buildRegion(
        triangleData: FloatArray,
        bounds: DoubleArray,
        area: Double,
    ): ConformalSurface.Region {
        val minX = bounds[0]; val minY = bounds[1]; val minZ = bounds[2]
        val maxX = bounds[3]; val maxY = bounds[4]; val maxZ = bounds[5]
        val columns = max(2, ((maxX - minX) / GRID_CELL_MM).toInt() + 1)
        val rows = max(2, ((maxY - minY) / GRID_CELL_MM).toInt() + 1)
        val cellTriangleCounts = IntArray(columns * rows)
        val triangleCount = triangleData.size / 9
        for (triangle in 0 until triangleCount) {
            val offset = triangle * 9
            val x0 = triangleData[offset].toDouble(); val y0 = triangleData[offset + 1].toDouble()
            val x1 = triangleData[offset + 3].toDouble(); val y1 = triangleData[offset + 4].toDouble()
            val x2 = triangleData[offset + 6].toDouble(); val y2 = triangleData[offset + 7].toDouble()
            val loX = max(min(x0, min(x1, x2)), minX)
            val hiX = min(max(x0, max(x1, x2)), maxX)
            val loY = max(min(y0, min(y1, y2)), minY)
            val hiY = min(max(y0, max(y1, y2)), maxY)
            val gx0 = ((loX - minX) / GRID_CELL_MM).toInt().coerceIn(0, columns - 1)
            val gx1 = ((hiX - minX) / GRID_CELL_MM).toInt().coerceIn(0, columns - 1)
            val gy0 = ((loY - minY) / GRID_CELL_MM).toInt().coerceIn(0, rows - 1)
            val gy1 = ((hiY - minY) / GRID_CELL_MM).toInt().coerceIn(0, rows - 1)
            for (gy in gy0..gy1) {
                for (gx in gx0..gx1) {
                    cellTriangleCounts[gy * columns + gx]++
                }
            }
        }
        val cellTriangleOffsets = IntArray(columns * rows + 1)
        for (cell in 0 until columns * rows) {
            cellTriangleOffsets[cell + 1] = cellTriangleOffsets[cell] + cellTriangleCounts[cell]
        }
        val cellTriangles = IntArray(cellTriangleOffsets[columns * rows])
        val cursor = cellTriangleOffsets.copyOf()
        for (triangle in 0 until triangleCount) {
            val offset = triangle * 9
            val x0 = triangleData[offset].toDouble(); val y0 = triangleData[offset + 1].toDouble()
            val x1 = triangleData[offset + 3].toDouble(); val y1 = triangleData[offset + 4].toDouble()
            val x2 = triangleData[offset + 6].toDouble(); val y2 = triangleData[offset + 7].toDouble()
            val loX = max(min(x0, min(x1, x2)), minX)
            val hiX = min(max(x0, max(x1, x2)), maxX)
            val loY = max(min(y0, min(y1, y2)), minY)
            val hiY = min(max(y0, max(y1, y2)), maxY)
            val gx0 = ((loX - minX) / GRID_CELL_MM).toInt().coerceIn(0, columns - 1)
            val gx1 = ((hiX - minX) / GRID_CELL_MM).toInt().coerceIn(0, columns - 1)
            val gy0 = ((loY - minY) / GRID_CELL_MM).toInt().coerceIn(0, rows - 1)
            val gy1 = ((hiY - minY) / GRID_CELL_MM).toInt().coerceIn(0, rows - 1)
            for (gy in gy0..gy1) {
                for (gx in gx0..gx1) {
                    val cell = gy * columns + gx
                    cellTriangles[cursor[cell]++] = triangle
                }
            }
        }

        return ConformalSurface.Region(
            triangles = triangleData,
            minX = minX, minY = minY, minZ = minZ, maxX = maxX, maxY = maxY, maxZ = maxZ,
            areaMm2 = area,
            gridOriginX = minX, gridOriginY = minY,
            gridCellMm = GRID_CELL_MM,
            gridColumns = columns, gridRows = rows,
            cellTriangleOffsets = cellTriangleOffsets,
            cellTriangles = cellTriangles,
        )
    }

    /** Injective weld key for one quantised vertex; visible so tests can pin it. */
    internal fun packVertex(x: Double, y: Double, z: Double): Long {
        // Printer-scale coordinates: 21 bits per axis covers ±1048.576 mm at
        // the 1e-3 weld quantum, far beyond any build plate.
        val qx = (x / VERTEX_QUANTUM_MM).toLong()
        val qy = (y / VERTEX_QUANTUM_MM).toLong()
        val qz = (z / VERTEX_QUANTUM_MM).toLong()
        require(qx in -0x100000..0xFFFFF && qy in -0x100000..0xFFFFF && qz in -0x100000..0xFFFFF) {
            "Model coordinate out of range for the conformal surface search: (" + x + ", " + y + ", " + z + ")"
        }
        // Each field is masked to its 21 bits before the shift. Without the
        // masks a negative y or z sign-extends into every field above it, so
        // points that differ only in x - any mesh whose displayed coordinates
        // cross the bed centre - packed to one id. Those ids are the builder's
        // only connectivity input, so the welds silently fused unrelated
        // facets into one region and measured the wrong boundary.
        return ((qx and FIELD_MASK) shl 42) or ((qy and FIELD_MASK) shl 21) or (qz and FIELD_MASK)
    }

    // Injective packing: two Int ids cannot collide regardless of their
    // values (the old multiplicative hash merged unrelated edges at ~100k+
    // facets, silently fusing disconnected regions).
    private fun edgeKey(a: Int, b: Int): Long =
        (minOf(a, b).toLong() shl 32) or (maxOf(a, b).toLong() and 0xFFFFFFFFL)
}
