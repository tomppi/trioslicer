package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlParser
import com.tomppi.enderslicer.viewer.StlSliceTransform
import com.tomppi.enderslicer.viewer.VertexData
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One nested modifier volume and the wall reinforcement it applies in its region. */
data class AdaptiveWallModifier(
    val wallLineCount: Int,
    val wallFlowPercent: Double,
    val file: File,
)

/**
 * Automatic wall reinforcement where the part outline curves or corners.
 *
 * The base model keeps its normal walls. The mesh is sliced once at mid-height and the local radius
 * of curvature is measured at each outline vertex; any vertex tighter than the configured bend
 * radius - a curve or a sharp corner - gets extra walls, scaled by how tight it is. Neighbouring
 * tight vertices are merged into crescent-shaped bands that hug each bend and span the whole
 * height, so Cura clips them to the material. Modifier volumes reuse the Smart Infill infill_mesh
 * mechanism with wall overrides instead of density overrides.
 */
object ThicknessAdaptiveWalls {
    private const val SLAB_Z_PAD = 0.1f
    private const val WALL_DEPTH_MARGIN_MM = 1.0f
    private const val SMOOTH_HALF_WINDOW = 2
    private const val BEND_RUN_PAD = 4
    private const val GAP_MERGE_MM = 10.0f
    private const val OFFSET_CLAMP_FRACTION = 0.8f
    private const val MIN_BAND_DEPTH_MM = 1.5f
    private const val RESAMPLE_STEP_MM = 2.0f

    private data class BendRegion(
        val points: FloatArray,
        val clockwise: Boolean,
        val minRadius: Float,
    )

    fun generate(
        modelFile: File,
        settings: SlicerSettings,
        destination: File,
        transform: StlSliceTransform? = null,
    ): List<AdaptiveWallModifier> {
        require(destination.mkdirs() || destination.isDirectory) {
            "Unable to create the adaptive-walls staging directory"
        }
        require(settings.thicknessAdaptiveWallsFlowPercent in 100.0..200.0) {
            "Adaptive wall flow must be between 100% and 200%"
        }
        val mesh = StlParser.parse(modelFile, modelFile.name, MeshTriangleLimits.current())
        throwIfInterrupted()

        val triangles = triangleList(mesh)
        val baseWalls = settings.wallLineCount.coerceAtLeast(1)
        val zMin = mesh.bounds.minZ
        val zMax = mesh.bounds.maxZ
        val bendRadiusMm = settings.thicknessAdaptiveWallsBendRadiusMm.toFloat()
        val lineWidth = settings.lineWidthMm.toFloat()

        val referenceZ = (zMin + zMax) * 0.5f
        val loops = chainSegments(outlineSegmentsAt(triangles, referenceZ))
        val regions = mutableListOf<BendRegion>()
        for (loop in loops) {
            val resampled = resampleLoop(deduplicateLoop(loop), RESAMPLE_STEP_MM)
            regions += detectBendRegions(resampled, bendRadiusMm)
        }

        val extraWalls = settings.thicknessAdaptiveWallsExtraWalls.coerceIn(0, 20)
        val wallCount = baseWalls + extraWalls
        val modifiers = mutableListOf<AdaptiveWallModifier>()
        var order = 0
        val slabZ0 = (zMin - SLAB_Z_PAD).coerceAtLeast(0f)
        val slabZ1 = (zMax + SLAB_Z_PAD).coerceAtMost(settings.machineHeightMm.toFloat())
        // CuraEngine insets a modifier mesh's outermost wall past its outline by the base
        // wall count, so grow the band's outer boundary outward to keep wall 0 on the part.
        val outerOffset = baseWalls * lineWidth
        for (region in regions) {
            val depth = (wallCount * lineWidth + WALL_DEPTH_MARGIN_MM)
                .coerceAtMost(max(region.minRadius * OFFSET_CLAMP_FRACTION, MIN_BAND_DEPTH_MM))
            val file = File(destination, "adaptive-walls-${++order}-${wallCount}walls.stl")
            bandStl(region, depth, outerOffset, slabZ0, slabZ1, transform, file)
            modifiers += AdaptiveWallModifier(wallCount, settings.thicknessAdaptiveWallsFlowPercent, file)
            throwIfInterrupted()
        }
        return modifiers
    }

    private fun outlineSegmentsAt(triangles: List<FloatArray>, z: Float): List<FloatArray> {
        val segments = ArrayList<FloatArray>()
        for (tri in triangles) {
            val z0 = tri[2]
            val z1 = tri[5]
            val z2 = tri[8]
            val triZMin = min(z0, min(z1, z2))
            val triZMax = max(z0, max(z1, z2))
            if (z <= triZMin || z >= triZMax) continue
            var count = 0
            val points = FloatArray(4)
            fun edge(x1: Float, y1: Float, zz1: Float, x2: Float, y2: Float, zz2: Float) {
                if ((zz1 - z) * (zz2 - z) < 0f && count < 2) {
                    val t = (z - zz1) / (zz2 - zz1)
                    points[count * 2] = x1 + t * (x2 - x1)
                    points[count * 2 + 1] = y1 + t * (y2 - y1)
                    count++
                }
            }
            edge(tri[0], tri[1], z0, tri[3], tri[4], z1)
            edge(tri[3], tri[4], z1, tri[6], tri[7], z2)
            edge(tri[6], tri[7], z2, tri[0], tri[1], z0)
            if (count == 2) segments += points
        }
        return segments
    }

    private fun detectBendRegions(loop: FloatArray, bendRadiusMm: Float): List<BendRegion> {
        val n = loop.size / 2
        if (n < 3) return emptyList()
        val radii = vertexRadii(loop)
        val clockwise = signedArea(loop) < 0f
        val tight = BooleanArray(n)
        for (i in 0 until n) {
            var smoothed = Float.POSITIVE_INFINITY
            for (w in -SMOOTH_HALF_WINDOW..SMOOTH_HALF_WINDOW) {
                val radius = radii[(i + w + n) % n]
                if (radius < smoothed) smoothed = radius
            }
            tight[i] = smoothed < bendRadiusMm
        }

        val runs = mutableListOf<IntArray>()
        var i = 0
        while (i < n) {
            if (!tight[i]) {
                i++
                continue
            }
            var j = i
            while (j + 1 < n && tight[j + 1]) j++
            runs += intArrayOf(i, j)
            i = j + 1
        }
        if (runs.isEmpty()) return emptyList()
        if (runs.size >= 2 && runs.first()[0] == 0 && runs.last()[1] == n - 1) {
            val head = runs.removeAt(0)
            val tail = runs.removeAt(runs.size - 1)
            runs.add(0, intArrayOf(tail[0], head[1]))
        }

        val merged = mutableListOf<IntArray>()
        var current = runs[0]
        for (index in 1 until runs.size) {
            val next = runs[index]
            if (loopDistance(loop, current[1], next[0], n) < GAP_MERGE_MM) {
                current = intArrayOf(current[0], next[1])
            } else {
                merged += current
                current = next
            }
        }
        merged += current

        val regions = mutableListOf<BendRegion>()
        for (run in merged) {
            var minRadius = Float.POSITIVE_INFINITY
            var vertex = run[0]
            while (true) {
                minRadius = min(minRadius, radii[vertex])
                if (vertex == run[1]) break
                vertex = (vertex + 1) % n
            }
            val start: Int
            val end: Int
            if (run[1] - run[0] + 1 >= n - 2 * BEND_RUN_PAD) {
                start = 0
                end = n - 1
            } else {
                start = (run[0] - BEND_RUN_PAD + n) % n
                end = (run[1] + BEND_RUN_PAD) % n
            }
            regions += BendRegion(
                points = collectLoopPoints(loop, start, end, n),
                clockwise = clockwise,
                minRadius = minRadius,
            )
        }
        return regions
    }

    private fun loopDistance(loop: FloatArray, fromIndex: Int, toIndex: Int, n: Int): Float {
        var distance = 0f
        var index = fromIndex
        while (index != toIndex) {
            val next = (index + 1) % n
            distance += hypot(loop[next * 2] - loop[index * 2], loop[next * 2 + 1] - loop[index * 2 + 1])
            index = next
        }
        return distance
    }

    private fun vertexRadii(loop: FloatArray): FloatArray {
        val n = loop.size / 2
        val radii = FloatArray(n) { Float.POSITIVE_INFINITY }
        if (n < 3) return radii
        for (i in 0 until n) {
            val ax = loop[((i - 1 + n) % n) * 2]
            val ay = loop[((i - 1 + n) % n) * 2 + 1]
            val bx = loop[i * 2]
            val by = loop[i * 2 + 1]
            val cx = loop[((i + 1) % n) * 2]
            val cy = loop[((i + 1) % n) * 2 + 1]
            val la = hypot(bx - cx, by - cy)
            val lb = hypot(ax - cx, ay - cy)
            val lc = hypot(ax - bx, ay - by)
            val hp = (la + lb + lc) * 0.5f
            val areaSquared = hp * (hp - la) * (hp - lb) * (hp - lc)
            if (areaSquared <= 1e-12f) continue
            radii[i] = (la * lb * lc) / (4f * sqrt(areaSquared))
        }
        return radii
    }

    private fun signedArea(loop: FloatArray): Float {
        val n = loop.size / 2
        var area = 0f
        for (i in 0 until n) {
            val x1 = loop[i * 2]
            val y1 = loop[i * 2 + 1]
            val x2 = loop[((i + 1) % n) * 2]
            val y2 = loop[((i + 1) % n) * 2 + 1]
            area += x1 * y2 - x2 * y1
        }
        return area * 0.5f
    }

    private fun resampleLoop(loop: FloatArray, stepMm: Float): FloatArray {
        val n = loop.size / 2
        if (n == 0) return loop
        val cumulative = FloatArray(n + 1)
        for (i in 0 until n) {
            val j = (i + 1) % n
            cumulative[i + 1] = cumulative[i] +
                hypot(loop[j * 2] - loop[i * 2], loop[j * 2 + 1] - loop[i * 2 + 1])
        }
        val total = cumulative[n]
        if (total <= stepMm) return loop
        val out = mutableListOf<Float>()
        var target = 0f
        var segment = 0
        while (target < total) {
            while (segment < n && cumulative[segment + 1] <= target) segment++
            val segmentLength = cumulative[segment + 1] - cumulative[segment]
            val t = if (segmentLength > 1e-6f) (target - cumulative[segment]) / segmentLength else 0f
            val next = (segment + 1) % n
            out += loop[segment * 2] + t * (loop[next * 2] - loop[segment * 2])
            out += loop[segment * 2 + 1] + t * (loop[next * 2 + 1] - loop[segment * 2 + 1])
            target += stepMm
        }
        return out.toFloatArray()
    }

    private fun deduplicateLoop(loop: FloatArray): FloatArray {
        val n = loop.size / 2
        val out = mutableListOf<Float>()
        for (i in 0 until n) {
            val x = loop[i * 2]
            val y = loop[i * 2 + 1]
            if (out.size >= 2 && out[out.size - 2] == x && out[out.size - 1] == y) continue
            out += x
            out += y
        }
        if (out.size >= 4 && out[0] == out[out.size - 2] && out[1] == out[out.size - 1]) {
            out.removeAt(out.size - 1)
            out.removeAt(out.size - 1)
        }
        return out.toFloatArray()
    }

    private fun collectLoopPoints(loop: FloatArray, start: Int, end: Int, n: Int): FloatArray {
        val out = mutableListOf<Float>()
        var idx = start
        var guard = 0
        while (guard <= n) {
            out += loop[idx * 2]
            out += loop[idx * 2 + 1]
            if (idx == end) break
            idx = (idx + 1) % n
            guard++
        }
        return out.toFloatArray()
    }

    private fun bandStl(
        region: BendRegion,
        depth: Float,
        outerOffset: Float,
        z0: Float,
        z1: Float,
        transform: StlSliceTransform?,
        file: File,
    ) {
        val n = region.points.size / 2
        require(n >= 2) { "Adaptive wall band is degenerate" }
        val outerX = FloatArray(n)
        val outerY = FloatArray(n)
        val innerX = FloatArray(n)
        val innerY = FloatArray(n)
        for (i in 0 until n) {
            val prev = if (i == 0) 0 else i - 1
            val next = if (i == n - 1) n - 1 else i + 1
            val tx = region.points[next * 2] - region.points[prev * 2]
            val ty = region.points[next * 2 + 1] - region.points[prev * 2 + 1]
            val len = hypot(tx, ty)
            val ux = if (len > 1e-6f) tx / len else 0f
            val uy = if (len > 1e-6f) ty / len else 0f
            val nx = if (region.clockwise) uy else -uy
            val ny = if (region.clockwise) -ux else ux
            outerX[i] = region.points[i * 2] - nx * outerOffset
            outerY[i] = region.points[i * 2 + 1] - ny * outerOffset
            innerX[i] = outerX[i] + nx * depth
            innerY[i] = outerY[i] + ny * depth
        }
        val vertices = mutableListOf<Float>()
        fun tri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float) {
            vertices.add(ax); vertices.add(ay); vertices.add(az)
            vertices.add(bx); vertices.add(by); vertices.add(bz)
            vertices.add(cx); vertices.add(cy); vertices.add(cz)
        }
        for (j in 0 until n - 1) {
            val k = j + 1
            tri(outerX[j], outerY[j], z1, outerX[k], outerY[k], z1, innerX[k], innerY[k], z1)
            tri(outerX[j], outerY[j], z1, innerX[k], innerY[k], z1, innerX[j], innerY[j], z1)
            tri(outerX[j], outerY[j], z0, innerX[k], innerY[k], z0, outerX[k], outerY[k], z0)
            tri(outerX[j], outerY[j], z0, innerX[j], innerY[j], z0, innerX[k], innerY[k], z0)
            tri(outerX[j], outerY[j], z0, outerX[k], outerY[k], z0, outerX[k], outerY[k], z1)
            tri(outerX[j], outerY[j], z0, outerX[k], outerY[k], z1, outerX[j], outerY[j], z1)
            tri(innerX[j], innerY[j], z0, innerX[k], innerY[k], z1, innerX[k], innerY[k], z0)
            tri(innerX[j], innerY[j], z0, innerX[j], innerY[j], z1, innerX[k], innerY[k], z1)
        }
        tri(outerX[0], outerY[0], z0, innerX[0], innerY[0], z0, innerX[0], innerY[0], z1)
        tri(outerX[0], outerY[0], z0, innerX[0], innerY[0], z1, outerX[0], outerY[0], z1)
        val last = n - 1
        tri(outerX[last], outerY[last], z0, innerX[last], innerY[last], z1, innerX[last], innerY[last], z0)
        tri(outerX[last], outerY[last], z0, outerX[last], outerY[last], z1, innerX[last], innerY[last], z1)
        require(vertices.size % 9 == 0) { "Adaptive wall band produced a malformed shell" }
        writeShellStl(vertices.toFloatArray(), transform, file)
    }

    private fun chainSegments(segments: List<FloatArray>): List<FloatArray> {
        data class Key(val x: Int, val y: Int)
        fun key(x: Float, y: Float) = Key((x * 100f).roundToInt(), (y * 100f).roundToInt())
        val endToSeg = HashMap<Key, MutableList<Int>>()
        segments.forEachIndexed { index, s ->
            endToSeg.getOrPut(key(s[0], s[1])) { mutableListOf() } += index
            endToSeg.getOrPut(key(s[2], s[3])) { mutableListOf() } += index
        }
        val used = BooleanArray(segments.size)
        val loops = mutableListOf<FloatArray>()
        for (start in segments.indices) {
            if (used[start]) continue
            used[start] = true
            val loop = mutableListOf<Float>()
            val startKey = key(segments[start][0], segments[start][1])
            var currentKey = key(segments[start][2], segments[start][3])
            loop += segments[start][0]
            loop += segments[start][1]
            loop += segments[start][2]
            loop += segments[start][3]
            var guard = 0
            while (guard++ <= segments.size) {
                if (currentKey == startKey) break
                val candidates = endToSeg[currentKey] ?: break
                var next = -1
                for (ci in candidates) {
                    if (!used[ci]) {
                        next = ci
                        break
                    }
                }
                if (next == -1) break
                used[next] = true
                val s = segments[next]
                if (key(s[0], s[1]) == currentKey) {
                    loop += s[2]
                    loop += s[3]
                    currentKey = key(s[2], s[3])
                } else {
                    loop += s[0]
                    loop += s[1]
                    currentKey = key(s[0], s[1])
                }
            }
            loops += loop.toFloatArray()
        }
        return loops
    }

    private fun triangleList(mesh: StlMesh): List<FloatArray> {
        val v = mesh.interleavedVertices
        val count = mesh.triangleCount
        val out = ArrayList<FloatArray>(count)
        for (t in 0 until count) {
            val b = t * 18
            out += floatArrayOf(
                v[b], v[b + 1], v[b + 2],
                v[b + 6], v[b + 7], v[b + 8],
                v[b + 12], v[b + 13], v[b + 14],
            )
        }
        return out
    }

    private fun writeShellStl(vertices: FloatArray, transform: StlSliceTransform?, file: File) {
        val count = vertices.size / 9
        val linear = transform?.linear
        val transformX = transform?.translationXmm ?: 0.0
        val transformY = transform?.translationYmm ?: 0.0
        val transformZ = transform?.translationZmm ?: 0.0
        fun place(x: Float, y: Float, z: Float): FloatArray {
            if (linear == null) return floatArrayOf(x, y, z)
            return floatArrayOf(
                (x * linear[0] + y * linear[1] + z * linear[2] + transformX).toFloat(),
                (x * linear[3] + y * linear[4] + z * linear[5] + transformY).toFloat(),
                (x * linear[6] + y * linear[7] + z * linear[8] + transformZ).toFloat(),
            )
        }
        val interleaved = FloatArray(count * 18)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        var out = 0
        for (t in 0 until count) {
            val b = t * 9
            val p0 = place(vertices[b], vertices[b + 1], vertices[b + 2])
            val p1 = place(vertices[b + 3], vertices[b + 4], vertices[b + 5])
            val p2 = place(vertices[b + 6], vertices[b + 7], vertices[b + 8])
            val ax = p1[0] - p0[0]
            val ay = p1[1] - p0[1]
            val az = p1[2] - p0[2]
            val bx = p2[0] - p0[0]
            val by = p2[1] - p0[1]
            val bz = p2[2] - p0[2]
            var nx = ay * bz - az * by
            var ny = az * bx - ax * bz
            var nz = ax * by - ay * bx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length > 1e-12f) {
                nx /= length
                ny /= length
                nz /= length
            }
            repeat(3) { vertex ->
                val px = when (vertex) { 0 -> p0[0]; 1 -> p1[0]; else -> p2[0] }
                val py = when (vertex) { 0 -> p0[1]; 1 -> p1[1]; else -> p2[1] }
                val pz = when (vertex) { 0 -> p0[2]; 1 -> p1[2]; else -> p2[2] }
                minX = min(minX, px); maxX = max(maxX, px)
                minY = min(minY, py); maxY = max(maxY, py)
                minZ = min(minZ, pz); maxZ = max(maxZ, pz)
                interleaved[out++] = px
                interleaved[out++] = py
                interleaved[out++] = pz
                interleaved[out++] = if (vertex == 0) nx else 0f
                interleaved[out++] = if (vertex == 0) ny else 0f
                interleaved[out++] = if (vertex == 0) nz else 0f
            }
        }
        val bounds = MeshBounds(minX, minY, minZ, maxX, maxY, maxZ)
        val mesh = StlMesh(
            displayName = file.name,
            interleavedVertices = VertexData.fromArray(interleaved),
            triangleCount = count,
            bounds = bounds,
        )
        StlMeshWriter.writeBinary(mesh, file)
        require(file.isFile && file.length() > 0L) { "Unable to write the adaptive wall modifier" }
    }

    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Adaptive-walls generation was cancelled")
        }
    }
}
