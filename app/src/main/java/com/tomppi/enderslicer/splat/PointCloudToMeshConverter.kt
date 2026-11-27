package com.tomppi.enderslicer.splat

import android.content.Context
import android.util.Log
import com.rawmechatronics.easyscanner.NativeLiveTsdf
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Converts a point cloud (`.ply` or `.splat`), triangle-mesh `.ply`, or 3D Gaussian Splat `.ply`
 * into a printable binary `.stl` mesh on-device using [NativeLiveTsdf] (Dual Contouring +
 * Marching Cubes fallback).
 *
 * Strategy by input kind:
 * - **Triangle-mesh `.ply`** (`element face > 0`): converts faces directly to binary STL.
 * - **Trained 3DGS `.ply` with companion `dense.ply`** (`files/scans/<id>/dense.ply`): uses the
 *   dense, gravity-aligned, view-consistent oriented MVS surface points (typically 500k–1.5M pts)
 *   rotated from COLMAP Y-down to TrioSlicer Z-up so the resulting STL is solid and sits upright.
 * - **Standalone 3DGS `.ply`** (no companion `dense.ply`): adapts opacity filtering to Cross3DGS /
 *   MCMC opacity distributions, samples a 5-point tangent surfel disk across each flat Gaussian
 *   so voxels don't have pinholes, and rotates Y-down to Z-up.
 * - **Laser / Fusion `.ply` & `.splat` point clouds**: spatial floater cleanup + adaptive local
 *   3x3 covariance PCA normal estimation + Dual Contouring STL extraction.
 */
object PointCloudToMeshConverter {

    private const val TAG = "PointCloudToMesh"

    enum class DetailPreset(val label: String, val targetVoxelsAcross: Int, val minVoxelMm: Float) {
        COARSE("Coarse", 120, 2.0f),
        MEDIUM("Medium", 200, 1.2f),
        FINE("Fine", 280, 0.8f),
    }

    data class Options(
        val detail: DetailPreset = DetailPreset.MEDIUM,
        val cleanFloaters: Boolean = true,
    )

    data class Result(
        val stlFile: File,
        val triangles: Int,
        val inputPoints: Int,
        val keptPoints: Int,
        val voxelMm: Float,
        val sizeMm: FloatArray,
        val sourceNote: String,
        val directMeshCopy: Boolean = false,
    )

    internal data class OrientedCloud(
        val xyzM: FloatArray,
        val nxyz: FloatArray,
        val rgb: ByteArray,
        val count: Int,
        val inputCount: Int,
        val hasNormals: Boolean,
    )

    fun canConvert(file: File): Boolean =
        file.isFile && file.extension.lowercase() in setOf("ply", "splat")

    fun convert(
        context: Context,
        source: File,
        displayName: String,
        options: Options = Options(),
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Result {
        require(source.isFile && source.length() > 0L) { "File not found: ${source.name}" }
        val ext = source.extension.lowercase()
        require(ext in setOf("ply", "splat")) { "Mesh conversion supports .ply and .splat files" }

        val outDir = File(context.filesDir, "converted-meshes").apply { mkdirs() }
        val stem = sanitizeStem(displayName.substringBeforeLast('.'))
        val outStl = File(outDir, "${stem}-${options.detail.name.lowercase()}.stl")

        onProgress(0.08f, "Reading ${source.name}…")
        if (ext == "ply") {
            val direct = tryConvertTriangleMeshPlyToStl(source, outStl)
            if (direct != null) {
                onProgress(1f, "Mesh ready")
                return direct
            }
        }

        require(NativeLiveTsdf.available) { "Native TSDF mesher is unavailable on this device" }

        val isGaussian = ext == "ply" && SplatLibrary.isGaussianSplat(source)
        val companionDense = if (isGaussian) findCompanionDensePly(context, source) else null

        val sourceNote: String
        val parsed: OrientedCloud
        if (companionDense != null) {
            onProgress(0.22f, "Reading dense MVS surface (${companionDense.name})…")
            // Parallax dense.ply is COLMAP Y-down gravity-aligned; rotate Y-down -> Z-up for build plate.
            parsed = parsePointCloudPly(
                ply = companionDense,
                filterFloaters = options.cleanFloaters,
                yDownToZUp = true,
                maxKeep = 350_000,
            )
            sourceNote = "Dense MVS surface (${parsed.inputCount} pts)"
        } else if (ext == "splat") {
            onProgress(0.22f, "Reading .splat point cloud…")
            parsed = parseSplatCloud(source, options.cleanFloaters)
            sourceNote = "Point cloud (.splat)"
        } else if (isGaussian) {
            onProgress(0.22f, "Sampling Gaussian surfels…")
            parsed = parseGaussianSplatPly(source, options.cleanFloaters)
            sourceNote = "3DGS surfels"
        } else {
            onProgress(0.22f, "Reading PLY point cloud…")
            parsed = parsePointCloudPly(source, options.cleanFloaters, yDownToZUp = false, maxKeep = 300_000)
            sourceNote = "Point cloud (.ply)"
        }
        require(parsed.count >= 30) { "Not enough usable points (${parsed.count}) to build a mesh" }

        val cleaned = if (options.cleanFloaters && parsed.count >= 200) {
            onProgress(0.42f, "Removing floaters & outliers…")
            removeSpatialFloaters(parsed)
        } else {
            parsed
        }
        require(cleaned.count >= 30) { "Too few points remained after floater cleanup (${cleaned.count})" }

        val oriented = if (cleaned.hasNormals) {
            cleaned
        } else {
            onProgress(0.58f, "Estimating surface normals (${cleaned.count} pts)…")
            estimateNormalsPca(cleaned)
        }

        val (robMin, robMax) = computeRobustBounds(oriented.xyzM, oriented.count)
        val sx = (robMax[0] - robMin[0]).coerceAtLeast(0.01f)
        val sy = (robMax[1] - robMin[1]).coerceAtLeast(0.01f)
        val sz = (robMax[2] - robMin[2]).coerceAtLeast(0.01f)
        val spanM = maxOf(sx, sy, sz)

        val voxelM = max(
            options.detail.minVoxelMm * 0.001f,
            spanM / options.detail.targetVoxelsAcross.toFloat(),
        ).coerceIn(0.00075f, 0.025f)
        val voxelMm = voxelM * 1000f

        onProgress(0.74f, "Integrating ${oriented.count} points (${String.format("%.2f", voxelMm)} mm voxels)…")
        val packed = FloatArray(oriented.count * 6)
        for (i in 0 until oriented.count) {
            val p3 = i * 3
            val p6 = i * 6
            packed[p6] = oriented.xyzM[p3]
            packed[p6 + 1] = oriented.xyzM[p3 + 1]
            packed[p6 + 2] = oriented.xyzM[p3 + 2]
            packed[p6 + 3] = oriented.nxyz[p3]
            packed[p6 + 4] = oriented.nxyz[p3 + 1]
            packed[p6 + 5] = oriented.nxyz[p3 + 2]
        }

        val maxDepthM = max(4.0, spanM * 2.5)
        var triangles = 0
        val handle = NativeLiveTsdf.nativeCreate(voxelM, voxelM * 4.0f, 131072, maxDepthM)
        try {
            NativeLiveTsdf.nativeIntegrateOrientedPoints(handle, packed, oriented.rgb, oriented.count, 3.0f)
            onProgress(0.88f, "Extracting Dual Contouring STL mesh…")
            var tris = NativeLiveTsdf.nativeWriteMeshStlDualContouring(handle, outStl.absolutePath, true)
            if (tris <= 0) {
                tris = NativeLiveTsdf.nativeWriteMeshStl(handle, outStl.absolutePath, true)
            }
            triangles = max(0, tris)
        } finally {
            NativeLiveTsdf.nativeDestroy(handle)
        }

        require(triangles > 0 && outStl.isFile && outStl.length() > 84L) {
            "TSDF extracted 0 triangles — try Coarse detail or disable floater filtering"
        }

        onProgress(1f, "STL mesh ready")
        Log.i(
            TAG,
            "meshed ${source.name} ($sourceNote) -> ${outStl.name} " +
                "pts=${oriented.count}/${parsed.inputCount} voxel=${String.format("%.2f", voxelMm)}mm tris=$triangles",
        )
        return Result(
            stlFile = outStl,
            triangles = triangles,
            inputPoints = parsed.inputCount,
            keptPoints = oriented.count,
            voxelMm = voxelMm,
            sizeMm = floatArrayOf(sx * 1000f, sy * 1000f, sz * 1000f),
            sourceNote = sourceNote,
            directMeshCopy = false,
        )
    }

    /** Finds the companion Parallax `dense.ply` for a trained EasyScanner/SplatProto splat. */
    private fun findCompanionDensePly(context: Context, source: File): File? {
        source.parentFile?.takeIf { it.name == "exports" }?.parentFile?.let { scanDir ->
            File(scanDir, "dense.ply").takeIf { it.isFile && it.length() > 1000L }?.let { return it }
        }
        val name = source.name
        if (name.endsWith("-splat.ply", ignoreCase = true)) {
            val scanName = name.substring(0, name.length - "-splat.ply".length)
            val candidate = File(context.filesDir, "scans/$scanName/dense.ply")
            if (candidate.isFile && candidate.length() > 1000L) return candidate
        }
        return null
    }

    // ---------------------------------------------------------------- Direct triangle-mesh PLY -> STL

    internal fun tryConvertTriangleMeshPlyToStl(ply: File, outStl: File): Result? {
        val data = ply.readBytes()
        val parsed = parsePlyHeader(data) ?: return null
        if (parsed.faceCount <= 0 || !parsed.isBinaryLe) return null
        val px = parsed.vertexProps.firstOrNull { it.name == "x" } ?: return null
        val py = parsed.vertexProps.firstOrNull { it.name == "y" } ?: return null
        val pz = parsed.vertexProps.firstOrNull { it.name == "z" } ?: return null

        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val vCount = parsed.vertexCount
        if (vCount <= 0 || parsed.payloadStart + vCount.toLong() * parsed.vertexStride > data.size) return null

        val vx = FloatArray(vCount)
        val vy = FloatArray(vCount)
        val vz = FloatArray(vCount)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until vCount) {
            val base = parsed.payloadStart + i * parsed.vertexStride
            val x = readPropFloat(bb, base, px)
            val y = readPropFloat(bb, base, py)
            val z = readPropFloat(bb, base, pz)
            vx[i] = x; vy[i] = y; vz[i] = z
            if (x.isFinite() && y.isFinite() && z.isFinite()) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            }
        }
        val span = maxOf(maxX - minX, maxY - minY, maxZ - minZ).coerceAtLeast(1e-4f)
        val scaleToMm = if (span < 15f) 1000f else 1f
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val cz = minZ

        var pos = parsed.payloadStart + vCount * parsed.vertexStride
        val trisIdx = IntArray(parsed.faceCount * 3)
        var triCount = 0
        for (f in 0 until parsed.faceCount) {
            if (pos >= data.size) break
            val cnt = when (parsed.faceCountTypeSize) {
                1 -> data[pos].toInt() and 0xFF
                2 -> bb.getShort(pos).toInt() and 0xFFFF
                4 -> bb.getInt(pos)
                else -> return null
            }
            pos += parsed.faceCountTypeSize
            if (cnt < 3 || pos + cnt * parsed.faceIndexTypeSize > data.size) break
            val i0 = readIndex(bb, data, pos, parsed.faceIndexTypeSize)
            for (k in 1 until cnt - 1) {
                val i1 = readIndex(bb, data, pos + k * parsed.faceIndexTypeSize, parsed.faceIndexTypeSize)
                val i2 = readIndex(bb, data, pos + (k + 1) * parsed.faceIndexTypeSize, parsed.faceIndexTypeSize)
                if (i0 in 0 until vCount && i1 in 0 until vCount && i2 in 0 until vCount) {
                    if (triCount * 3 + 2 >= trisIdx.size) break
                    trisIdx[triCount * 3] = i0
                    trisIdx[triCount * 3 + 1] = i1
                    trisIdx[triCount * 3 + 2] = i2
                    triCount++
                }
            }
            pos += cnt * parsed.faceIndexTypeSize
        }
        if (triCount <= 0) return null

        val outBuf = ByteBuffer.allocate(84 + triCount * 50).order(ByteOrder.LITTLE_ENDIAN)
        val hdrBytes = "TrioSlicer binary STL".toByteArray(Charsets.US_ASCII)
        outBuf.put(hdrBytes)
        outBuf.position(80)
        outBuf.putInt(triCount)
        for (t in 0 until triCount) {
            val a = trisIdx[t * 3]
            val b = trisIdx[t * 3 + 1]
            val c = trisIdx[t * 3 + 2]
            val ax = (vx[a] - cx) * scaleToMm; val ay = (vy[a] - cy) * scaleToMm; val az = (vz[a] - cz) * scaleToMm
            val bx = (vx[b] - cx) * scaleToMm; val by = (vy[b] - cy) * scaleToMm; val bz = (vz[b] - cz) * scaleToMm
            val cxp = (vx[c] - cx) * scaleToMm; val cyp = (vy[c] - cy) * scaleToMm; val czp = (vz[c] - cz) * scaleToMm
            val ux = bx - ax; val uy = by - ay; val uz = bz - az
            val wx = cxp - ax; val wy = cyp - ay; val wz = czp - az
            var nx = uy * wz - uz * wy
            var ny = uz * wx - ux * wz
            var nz = ux * wy - uy * wx
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            if (nl > 1e-8f) { nx /= nl; ny /= nl; nz /= nl } else { nx = 0f; ny = 0f; nz = 1f }
            outBuf.putFloat(nx); outBuf.putFloat(ny); outBuf.putFloat(nz)
            outBuf.putFloat(ax); outBuf.putFloat(ay); outBuf.putFloat(az)
            outBuf.putFloat(bx); outBuf.putFloat(by); outBuf.putFloat(bz)
            outBuf.putFloat(cxp); outBuf.putFloat(cyp); outBuf.putFloat(czp)
            outBuf.putShort(0)
        }
        FileOutputStream(outStl).use { it.write(outBuf.array()) }
        return Result(
            stlFile = outStl,
            triangles = triCount,
            inputPoints = vCount,
            keptPoints = vCount,
            voxelMm = 0f,
            sizeMm = floatArrayOf(
                (maxX - minX) * scaleToMm,
                (maxY - minY) * scaleToMm,
                (maxZ - minZ) * scaleToMm,
            ),
            sourceNote = "Direct triangle mesh",
            directMeshCopy = true,
        )
    }

    // ---------------------------------------------------------------- Standalone 3DGS PLY -> Surfel Cloud

    /**
     * Parses a standalone 3DGS `.ply` (when no companion `dense.ply` exists):
     * - Adapts opacity threshold to the scene's actual opacity distribution (Cross3DGS MCMC uses
     *   many overlapping Gaussians in the 0.03–0.25 range).
     * - Samples a 5-point tangent surfel disk across each flat Gaussian so adjacent Gaussians
     *   form a continuous surface in the TSDF instead of sparse pinholes.
     * - Rotates COLMAP Y-down -> TrioSlicer Z-up `(x, z, -y)` and leaves normals for consistent
     *   local PCA estimation.
     */
    internal fun parseGaussianSplatPly(ply: File, filterFloaters: Boolean): OrientedCloud {
        val data = ply.readBytes()
        val hdr = parsePlyHeader(data) ?: error("Invalid PLY header")
        require(hdr.isBinaryLe) { "Only binary little-endian 3DGS PLY is supported" }
        val px = hdr.vertexProps.firstOrNull { it.name == "x" } ?: error("Missing x")
        val py = hdr.vertexProps.firstOrNull { it.name == "y" } ?: error("Missing y")
        val pz = hdr.vertexProps.firstOrNull { it.name == "z" } ?: error("Missing z")
        val pOp = hdr.vertexProps.firstOrNull { it.name == "opacity" }
        val pS0 = hdr.vertexProps.firstOrNull { it.name == "scale_0" }
        val pS1 = hdr.vertexProps.firstOrNull { it.name == "scale_1" }
        val pS2 = hdr.vertexProps.firstOrNull { it.name == "scale_2" }
        val pR0 = hdr.vertexProps.firstOrNull { it.name == "rot_0" }
        val pR1 = hdr.vertexProps.firstOrNull { it.name == "rot_1" }
        val pR2 = hdr.vertexProps.firstOrNull { it.name == "rot_2" }
        val pR3 = hdr.vertexProps.firstOrNull { it.name == "rot_3" }
        val pDc0 = hdr.vertexProps.firstOrNull { it.name == "f_dc_0" }
        val pDc1 = hdr.vertexProps.firstOrNull { it.name == "f_dc_1" }
        val pDc2 = hdr.vertexProps.firstOrNull { it.name == "f_dc_2" }

        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val total = hdr.vertexCount

        // Pass 1: probe actual opacity, bounds, and scale distribution.
        val sampleXs = ArrayList<Float>(4096)
        val sampleYs = ArrayList<Float>(4096)
        val sampleZs = ArrayList<Float>(4096)
        val sampleOps = ArrayList<Float>(4096)
        val sampleScales = ArrayList<Float>(4096)
        val probeStep = (total / 4096).coerceAtLeast(1)
        var pi = 0
        while (pi < total) {
            val base = hdr.payloadStart + pi * hdr.vertexStride
            if (base + hdr.vertexStride > data.size) break
            val x = readPropFloat(bb, base, px)
            val y = readPropFloat(bb, base, py)
            val z = readPropFloat(bb, base, pz)
            if (x.isFinite() && y.isFinite() && z.isFinite()) {
                sampleXs.add(x); sampleYs.add(y); sampleZs.add(z)
                val op = pOp?.let { sigmoid(readPropFloat(bb, base, it)) } ?: 1f
                sampleOps.add(op)
                if (pS0 != null && pS1 != null && pS2 != null) {
                    val ms = maxOf(
                        safeExp(readPropFloat(bb, base, pS0)),
                        safeExp(readPropFloat(bb, base, pS1)),
                        safeExp(readPropFloat(bb, base, pS2)),
                    )
                    if (ms.isFinite() && ms > 0f) sampleScales.add(ms)
                }
            }
            pi += probeStep
        }
        require(sampleXs.size >= 10) { "No valid Gaussian splats found" }
        sampleXs.sort(); sampleYs.sort(); sampleZs.sort(); sampleOps.sort()

        // Adaptive opacity cutoff: keep top ~65% of Gaussians (bounded cleanly in [0.02, 0.25]).
        val minOpacity = if (filterFloaters) {
            sampleOps[(sampleOps.size * 0.35).toInt().coerceIn(0, sampleOps.size - 1)].coerceIn(0.02f, 0.25f)
        } else {
            0.01f
        }
        val pLo = (sampleXs.size * 0.04).toInt().coerceIn(0, sampleXs.size - 1)
        val pHi = (sampleXs.size * 0.96).toInt().coerceIn(pLo, sampleXs.size - 1)
        val span = maxOf(
            sampleXs[pHi] - sampleXs[pLo],
            sampleYs[pHi] - sampleYs[pLo],
            sampleZs[pHi] - sampleZs[pLo],
        ).coerceAtLeast(1e-3f)
        val cx = (sampleXs[pLo] + sampleXs[pHi]) * 0.5f
        val cy = (sampleYs[pLo] + sampleYs[pHi]) * 0.5f
        val cz = (sampleZs[pLo] + sampleZs[pHi]) * 0.5f
        val pad = span * 0.12f
        val minX = sampleXs[pLo] - pad; val maxX = sampleXs[pHi] + pad
        val minY = sampleYs[pLo] - pad; val maxY = sampleYs[pHi] + pad
        val minZ = sampleZs[pLo] - pad; val maxZ = sampleZs[pHi] + pad

        val maxAllowedScale = if (sampleScales.isNotEmpty()) {
            sampleScales.sort()
            val p90Scale = sampleScales[(sampleScales.size * 0.90).toInt().coerceIn(0, sampleScales.size - 1)]
            min(p90Scale * 2.5f, span * 0.12f)
        } else {
            span * 0.12f
        }
        val toMetres = if (span > 15f) 0.001f else 1f
        val hasQuat = pS0 != null && pS1 != null && pS2 != null &&
            pR0 != null && pR1 != null && pR2 != null && pR3 != null

        // Target ~60k base Gaussians -> up to 300k tangent-disk surfel samples.
        val maxBase = 60_000
        val step = (total / maxBase).coerceAtLeast(1)
        val samplesPerGaussian = if (hasQuat) 5 else 1
        val cap = ((total + step - 1) / step) * samplesPerGaussian
        val xyzM = FloatArray(cap * 3)
        val rgb = ByteArray(cap * 3)
        var kept = 0
        val shC0 = 0.2820948f

        fun pushPoint(gx: Float, gy: Float, gz: Float, r: Byte, g: Byte, b: Byte) {
            if (kept >= cap) return
            // Rotate COLMAP Y-down -> TrioSlicer Z-up: (x, y, z) -> (x, z, -y)
            xyzM[kept * 3] = (gx - cx) * toMetres
            xyzM[kept * 3 + 1] = (gz - cz) * toMetres
            xyzM[kept * 3 + 2] = -(gy - cy) * toMetres
            rgb[kept * 3] = r
            rgb[kept * 3 + 1] = g
            rgb[kept * 3 + 2] = b
            kept++
        }

        var i = 0
        while (i < total && kept + samplesPerGaussian <= cap) {
            val base = hdr.payloadStart + i * hdr.vertexStride
            if (base + hdr.vertexStride > data.size) break
            val op = pOp?.let { sigmoid(readPropFloat(bb, base, it)) } ?: 1f
            if (op >= minOpacity) {
                val x = readPropFloat(bb, base, px)
                val y = readPropFloat(bb, base, py)
                val z = readPropFloat(bb, base, pz)
                if (x.isFinite() && y.isFinite() && z.isFinite() &&
                    (!filterFloaters || (x in minX..maxX && y in minY..maxY && z in minZ..maxZ))
                ) {
                    val s0 = pS0?.let { safeExp(readPropFloat(bb, base, it)) } ?: 0.01f
                    val s1 = pS1?.let { safeExp(readPropFloat(bb, base, it)) } ?: 0.01f
                    val s2 = pS2?.let { safeExp(readPropFloat(bb, base, it)) } ?: 0.01f
                    val maxS = maxOf(s0, s1, s2)
                    if (!filterFloaters || maxS <= maxAllowedScale) {
                        val r = (pDc0?.let { ((0.5f + shC0 * readPropFloat(bb, base, it)).coerceIn(0f, 1f) * 255f).toInt() } ?: 200).toByte()
                        val g = (pDc1?.let { ((0.5f + shC0 * readPropFloat(bb, base, it)).coerceIn(0f, 1f) * 255f).toInt() } ?: 200).toByte()
                        val b = (pDc2?.let { ((0.5f + shC0 * readPropFloat(bb, base, it)).coerceIn(0f, 1f) * 255f).toInt() } ?: 200).toByte()

                        pushPoint(x, y, z, r, g, b)
                        if (hasQuat && pR0 != null && pR1 != null && pR2 != null && pR3 != null) {
                            val qw = readPropFloat(bb, base, pR0)
                            val qx = readPropFloat(bb, base, pR1)
                            val qy = readPropFloat(bb, base, pR2)
                            val qz = readPropFloat(bb, base, pR3)
                            val (u, v) = tangentAxes(qw, qx, qy, qz, s0, s1, s2)
                            pushPoint(x + u[0], y + u[1], z + u[2], r, g, b)
                            pushPoint(x - u[0], y - u[1], z - u[2], r, g, b)
                            pushPoint(x + v[0], y + v[1], z + v[2], r, g, b)
                            pushPoint(x - v[0], y - v[1], z - v[2], r, g, b)
                        }
                    }
                }
            }
            i += step
        }
        return OrientedCloud(
            xyzM = xyzM.copyOf(kept * 3),
            nxyz = FloatArray(kept * 3),
            rgb = rgb.copyOf(kept * 3),
            count = kept,
            inputCount = total,
            hasNormals = false, // Let PCA compute globally smooth surface normals across overlapping surfels.
        )
    }

    internal fun parsePointCloudPly(
        ply: File,
        filterFloaters: Boolean,
        yDownToZUp: Boolean = false,
        maxKeep: Int = 300_000,
    ): OrientedCloud {
        val data = ply.readBytes()
        val hdr = parsePlyHeader(data) ?: error("Invalid PLY header")
        val px = hdr.vertexProps.firstOrNull { it.name == "x" } ?: error("Missing x")
        val py = hdr.vertexProps.firstOrNull { it.name == "y" } ?: error("Missing y")
        val pz = hdr.vertexProps.firstOrNull { it.name == "z" } ?: error("Missing z")
        val pnx = hdr.vertexProps.firstOrNull { it.name == "nx" }
        val pny = hdr.vertexProps.firstOrNull { it.name == "ny" }
        val pnz = hdr.vertexProps.firstOrNull { it.name == "nz" }
        val pr = hdr.vertexProps.firstOrNull { it.name == "red" || it.name == "r" }
        val pg = hdr.vertexProps.firstOrNull { it.name == "green" || it.name == "g" }
        val pb = hdr.vertexProps.firstOrNull { it.name == "blue" || it.name == "b" }

        val total = hdr.vertexCount
        val step = (total / maxKeep).coerceAtLeast(1)
        val cap = (total + step - 1) / step
        val rawX = FloatArray(cap)
        val rawY = FloatArray(cap)
        val rawZ = FloatArray(cap)
        val rawNx = FloatArray(cap)
        val rawNy = FloatArray(cap)
        val rawNz = FloatArray(cap)
        val rgb = ByteArray(cap * 3)
        var hasValidNormals = pnx != null && pny != null && pnz != null
        var n = 0

        if (hdr.isBinaryLe) {
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < total && n < cap) {
                val base = hdr.payloadStart + i * hdr.vertexStride
                if (base + hdr.vertexStride > data.size) break
                val x = readPropFloat(bb, base, px)
                val y = readPropFloat(bb, base, py)
                val z = readPropFloat(bb, base, pz)
                if (x.isFinite() && y.isFinite() && z.isFinite()) {
                    if (yDownToZUp) {
                        rawX[n] = x; rawY[n] = z; rawZ[n] = -y
                    } else {
                        rawX[n] = x; rawY[n] = y; rawZ[n] = z
                    }
                    if (hasValidNormals && pnx != null && pny != null && pnz != null) {
                        val nx0 = readPropFloat(bb, base, pnx)
                        val ny0 = readPropFloat(bb, base, pny)
                        val nz0 = readPropFloat(bb, base, pnz)
                        val nl = sqrt(nx0 * nx0 + ny0 * ny0 + nz0 * nz0)
                        if (nl > 1e-4f) {
                            val nx = nx0 / nl; val ny = ny0 / nl; val nz = nz0 / nl
                            if (yDownToZUp) {
                                rawNx[n] = nx; rawNy[n] = nz; rawNz[n] = -ny
                            } else {
                                rawNx[n] = nx; rawNy[n] = ny; rawNz[n] = nz
                            }
                        } else {
                            hasValidNormals = false
                        }
                    }
                    rgb[n * 3] = readPropColorByte(bb, data, base, pr)
                    rgb[n * 3 + 1] = readPropColorByte(bb, data, base, pg)
                    rgb[n * 3 + 2] = readPropColorByte(bb, data, base, pb)
                    n++
                }
                i += step
            }
        } else {
            val ix = hdr.vertexProps.indexOf(px)
            val iy = hdr.vertexProps.indexOf(py)
            val iz = hdr.vertexProps.indexOf(pz)
            val inx = pnx?.let { hdr.vertexProps.indexOf(it) } ?: -1
            val iny = pny?.let { hdr.vertexProps.indexOf(it) } ?: -1
            val inz = pnz?.let { hdr.vertexProps.indexOf(it) } ?: -1
            val ir = pr?.let { hdr.vertexProps.indexOf(it) } ?: -1
            val ig = pg?.let { hdr.vertexProps.indexOf(it) } ?: -1
            val ib = pb?.let { hdr.vertexProps.indexOf(it) } ?: -1
            val body = String(data, hdr.payloadStart, data.size - hdr.payloadStart, Charsets.US_ASCII)
            var idx = 0
            for (line in body.lineSequence()) {
                if (idx >= total || n >= cap) break
                val t = line.trim()
                if (t.isEmpty()) continue
                if (idx % step == 0) {
                    val cols = t.split(Regex("\\s+"))
                    val x = cols.getOrNull(ix)?.toFloatOrNull()
                    val y = cols.getOrNull(iy)?.toFloatOrNull()
                    val z = cols.getOrNull(iz)?.toFloatOrNull()
                    if (x != null && y != null && z != null && x.isFinite() && y.isFinite() && z.isFinite()) {
                        if (yDownToZUp) {
                            rawX[n] = x; rawY[n] = z; rawZ[n] = -y
                        } else {
                            rawX[n] = x; rawY[n] = y; rawZ[n] = z
                        }
                        if (hasValidNormals && inx >= 0 && iny >= 0 && inz >= 0) {
                            val nx0 = cols.getOrNull(inx)?.toFloatOrNull() ?: 0f
                            val ny0 = cols.getOrNull(iny)?.toFloatOrNull() ?: 0f
                            val nz0 = cols.getOrNull(inz)?.toFloatOrNull() ?: 0f
                            val nl = sqrt(nx0 * nx0 + ny0 * ny0 + nz0 * nz0)
                            if (nl > 1e-4f) {
                                val nx = nx0 / nl; val ny = ny0 / nl; val nz = nz0 / nl
                                if (yDownToZUp) {
                                    rawNx[n] = nx; rawNy[n] = nz; rawNz[n] = -ny
                                } else {
                                    rawNx[n] = nx; rawNy[n] = ny; rawNz[n] = nz
                                }
                            } else {
                                hasValidNormals = false
                            }
                        }
                        rgb[n * 3] = (cols.getOrNull(ir)?.toIntOrNull() ?: 210).coerceIn(0, 255).toByte()
                        rgb[n * 3 + 1] = (cols.getOrNull(ig)?.toIntOrNull() ?: 210).coerceIn(0, 255).toByte()
                        rgb[n * 3 + 2] = (cols.getOrNull(ib)?.toIntOrNull() ?: 210).coerceIn(0, 255).toByte()
                        n++
                    }
                }
                idx++
            }
        }
        return normalizeCloudToMetres(rawX, rawY, rawZ, rawNx, rawNy, rawNz, rgb, n, total, hasValidNormals, filterFloaters)
    }

    private fun parseSplatCloud(splat: File, filterFloaters: Boolean): OrientedCloud {
        val bytes = splat.readBytes()
        val total = bytes.size / 32
        require(total >= 30) { "Splat file is empty" }
        val maxKeep = 260_000
        val step = (total / maxKeep).coerceAtLeast(1)
        val cap = (total + step - 1) / step
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val rawX = FloatArray(cap)
        val rawY = FloatArray(cap)
        val rawZ = FloatArray(cap)
        val rgb = ByteArray(cap * 3)
        var n = 0
        var i = 0
        while (i < total && n < cap) {
            val o = i * 32
            val x = bb.getFloat(o)
            val y = bb.getFloat(o + 4)
            val z = bb.getFloat(o + 8)
            val alpha = bytes[o + 27].toInt() and 0xFF
            if (x.isFinite() && y.isFinite() && z.isFinite() && (!filterFloaters || alpha >= 60)) {
                // Our .splat exports are Y-up -> Z-up: (x, z, y)
                rawX[n] = x; rawY[n] = z; rawZ[n] = y
                rgb[n * 3] = bytes[o + 24]
                rgb[n * 3 + 1] = bytes[o + 25]
                rgb[n * 3 + 2] = bytes[o + 26]
                n++
            }
            i += step
        }
        return normalizeCloudToMetres(
            rawX, rawY, rawZ, FloatArray(0), FloatArray(0), FloatArray(0),
            rgb, n, total, hasNormals = false, filterFloaters = filterFloaters,
        )
    }

    private fun normalizeCloudToMetres(
        rawX: FloatArray,
        rawY: FloatArray,
        rawZ: FloatArray,
        rawNx: FloatArray,
        rawNy: FloatArray,
        rawNz: FloatArray,
        rgb: ByteArray,
        n: Int,
        totalInput: Int,
        hasNormals: Boolean,
        filterFloaters: Boolean,
    ): OrientedCloud {
        require(n > 0) { "No valid 3D points found" }
        val sx = rawX.copyOf(n).apply { sort() }
        val sy = rawY.copyOf(n).apply { sort() }
        val sz = rawZ.copyOf(n).apply { sort() }
        val lo = if (filterFloaters) (n * 0.01).toInt().coerceIn(0, n - 1) else 0
        val hi = if (filterFloaters) (n * 0.99).toInt().coerceIn(lo, n - 1) else n - 1
        val span = maxOf(sx[hi] - sx[lo], sy[hi] - sy[lo], sz[hi] - sz[lo]).coerceAtLeast(1e-4f)
        val toMetres = if (span > 15f) 0.001f else 1f
        val cx = (sx[lo] + sx[hi]) * 0.5f
        val cy = (sy[lo] + sy[hi]) * 0.5f
        val cz = (sz[lo] + sz[hi]) * 0.5f
        val pad = span * 0.15f
        val minX = sx[lo] - pad; val maxX = sx[hi] + pad
        val minY = sy[lo] - pad; val maxY = sy[hi] + pad
        val minZ = sz[lo] - pad; val maxZ = sz[hi] + pad

        val outXyz = FloatArray(n * 3)
        val outNxyz = FloatArray(n * 3)
        val outRgb = ByteArray(n * 3)
        var kept = 0
        for (i in 0 until n) {
            if (filterFloaters && (rawX[i] !in minX..maxX || rawY[i] !in minY..maxY || rawZ[i] !in minZ..maxZ)) {
                continue
            }
            outXyz[kept * 3] = (rawX[i] - cx) * toMetres
            outXyz[kept * 3 + 1] = (rawY[i] - cy) * toMetres
            outXyz[kept * 3 + 2] = (rawZ[i] - cz) * toMetres
            if (hasNormals && i < rawNx.size) {
                outNxyz[kept * 3] = rawNx[i]
                outNxyz[kept * 3 + 1] = rawNy[i]
                outNxyz[kept * 3 + 2] = rawNz[i]
            }
            outRgb[kept * 3] = rgb[i * 3]
            outRgb[kept * 3 + 1] = rgb[i * 3 + 1]
            outRgb[kept * 3 + 2] = rgb[i * 3 + 2]
            kept++
        }
        return OrientedCloud(
            xyzM = outXyz.copyOf(kept * 3),
            nxyz = outNxyz.copyOf(kept * 3),
            rgb = outRgb.copyOf(kept * 3),
            count = kept,
            inputCount = totalInput,
            hasNormals = hasNormals,
        )
    }

    // ---------------------------------------------------------------- Pre-mesh cleanup & PCA normals

    /** Drops isolated floating specks whose 3D grid cell has fewer than 2 points. */
    internal fun removeSpatialFloaters(cloud: OrientedCloud): OrientedCloud {
        val (bMin, bMax) = computeRobustBounds(cloud.xyzM, cloud.count)
        val span = maxOf(bMax[0] - bMin[0], bMax[1] - bMin[1], bMax[2] - bMin[2]).coerceAtLeast(0.01f)
        val cell = (span / 64f).coerceAtLeast(0.003f)
        val invCell = 1f / cell
        val counts = HashMap<Long, Int>(cloud.count / 2)
        val keys = LongArray(cloud.count)
        for (i in 0 until cloud.count) {
            val k = packCell(
                floor(cloud.xyzM[i * 3] * invCell).toInt(),
                floor(cloud.xyzM[i * 3 + 1] * invCell).toInt(),
                floor(cloud.xyzM[i * 3 + 2] * invCell).toInt(),
            )
            keys[i] = k
            counts[k] = (counts[k] ?: 0) + 1
        }
        val outXyz = FloatArray(cloud.count * 3)
        val outNxyz = FloatArray(cloud.count * 3)
        val outRgb = ByteArray(cloud.count * 3)
        var kept = 0
        for (i in 0 until cloud.count) {
            if ((counts[keys[i]] ?: 0) >= 2) {
                outXyz[kept * 3] = cloud.xyzM[i * 3]
                outXyz[kept * 3 + 1] = cloud.xyzM[i * 3 + 1]
                outXyz[kept * 3 + 2] = cloud.xyzM[i * 3 + 2]
                if (cloud.hasNormals) {
                    outNxyz[kept * 3] = cloud.nxyz[i * 3]
                    outNxyz[kept * 3 + 1] = cloud.nxyz[i * 3 + 1]
                    outNxyz[kept * 3 + 2] = cloud.nxyz[i * 3 + 2]
                }
                outRgb[kept * 3] = cloud.rgb[i * 3]
                outRgb[kept * 3 + 1] = cloud.rgb[i * 3 + 1]
                outRgb[kept * 3 + 2] = cloud.rgb[i * 3 + 2]
                kept++
            }
        }
        if (kept < 30) return cloud
        return OrientedCloud(
            xyzM = outXyz.copyOf(kept * 3),
            nxyz = outNxyz.copyOf(kept * 3),
            rgb = outRgb.copyOf(kept * 3),
            count = kept,
            inputCount = cloud.inputCount,
            hasNormals = cloud.hasNormals,
        )
    }

    /** Fast spatial-hash 3x3 covariance PCA normal estimation, oriented toward a consistent view hemisphere. */
    internal fun estimateNormalsPca(cloud: OrientedCloud): OrientedCloud {
        val n = cloud.count
        val (bMin, bMax) = computeRobustBounds(cloud.xyzM, n)
        val span = maxOf(bMax[0] - bMin[0], bMax[1] - bMin[1], bMax[2] - bMin[2]).coerceAtLeast(0.01f)
        val cell = (1.4f * span / sqrt(n.toFloat().coerceAtLeast(4f)))
            .coerceIn(span / 40f, span / 4f)
            .coerceAtLeast(0.003f)
        val invCell = 1f / cell

        val buckets = HashMap<Long, IntArray>(n / 3)
        val bucketSizes = HashMap<Long, Int>(n / 3)
        val cxArr = IntArray(n)
        val cyArr = IntArray(n)
        val czArr = IntArray(n)
        val maxPerBucket = 16
        for (i in 0 until n) {
            val ix = floor(cloud.xyzM[i * 3] * invCell).toInt()
            val iy = floor(cloud.xyzM[i * 3 + 1] * invCell).toInt()
            val iz = floor(cloud.xyzM[i * 3 + 2] * invCell).toInt()
            cxArr[i] = ix; cyArr[i] = iy; czArr[i] = iz
            val k = packCell(ix, iy, iz)
            val sz = bucketSizes[k] ?: 0
            if (sz < maxPerBucket) {
                val arr = buckets.getOrPut(k) { IntArray(maxPerBucket) }
                arr[sz] = i
                bucketSizes[k] = sz + 1
            }
        }

        // Virtual overhead observer at (0, 0, +span) keeps flat tabletop/ground surfaces consistently +Z up
        // while still orienting vertical sides outward from the object center.
        val viewZ = span * 1.2f
        val nxyz = FloatArray(n * 3)
        val nbr = IntArray(24)
        for (i in 0 until n) {
            val px = cloud.xyzM[i * 3]
            val py = cloud.xyzM[i * 3 + 1]
            val pz = cloud.xyzM[i * 3 + 2]
            var kCount = 0
            val ix = cxArr[i]; val iy = cyArr[i]; val iz = czArr[i]
            search@ for (dx in -1..1) {
                for (dy in -1..1) {
                    for (dz in -1..1) {
                        val key = packCell(ix + dx, iy + dy, iz + dz)
                        val arr = buckets[key] ?: continue
                        val sz = bucketSizes[key] ?: 0
                        for (m in 0 until sz) {
                            nbr[kCount++] = arr[m]
                            if (kCount >= nbr.size) break@search
                        }
                    }
                }
            }
            var nx: Float; var ny: Float; var nz: Float
            if (kCount >= 4) {
                var mx = 0.0; var my = 0.0; var mz = 0.0
                for (m in 0 until kCount) {
                    val j = nbr[m] * 3
                    mx += cloud.xyzM[j]; my += cloud.xyzM[j + 1]; mz += cloud.xyzM[j + 2]
                }
                mx /= kCount; my /= kCount; mz /= kCount
                var c00 = 0.0; var c01 = 0.0; var c02 = 0.0
                var c11 = 0.0; var c12 = 0.0; var c22 = 0.0
                for (m in 0 until kCount) {
                    val j = nbr[m] * 3
                    val dx = cloud.xyzM[j] - mx
                    val dy = cloud.xyzM[j + 1] - my
                    val dz = cloud.xyzM[j + 2] - mz
                    c00 += dx * dx; c01 += dx * dy; c02 += dx * dz
                    c11 += dy * dy; c12 += dy * dz; c22 += dz * dz
                }
                val eig = smallestEigenvector3x3(c00, c01, c02, c11, c12, c22)
                nx = eig[0]; ny = eig[1]; nz = eig[2]
            } else {
                val rl = sqrt(px * px + py * py + pz * pz).coerceAtLeast(1e-6f)
                nx = px / rl; ny = py / rl; nz = pz / rl
            }
            // Orient consistently: mostly horizontal surfaces face +Z up; vertical walls face outward.
            val orientDot = if (abs(nz) > 0.6f) nz * (viewZ - pz) else nx * px + ny * py + nz * (viewZ - pz) * 0.25f
            if (orientDot < 0f) {
                nx = -nx; ny = -ny; nz = -nz
            }
            nxyz[i * 3] = nx; nxyz[i * 3 + 1] = ny; nxyz[i * 3 + 2] = nz
        }
        return cloud.copy(nxyz = nxyz, hasNormals = true)
    }

    /** Smallest-eigenvalue unit eigenvector of a symmetric 3x3 matrix via Jacobi rotations. */
    internal fun smallestEigenvector3x3(
        a00: Double, a01: Double, a02: Double,
        a11: Double, a12: Double, a22: Double,
    ): FloatArray {
        val a = arrayOf(
            doubleArrayOf(a00, a01, a02),
            doubleArrayOf(a01, a11, a12),
            doubleArrayOf(a02, a12, a22),
        )
        val v = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
        )
        for (iter in 0 until 10) {
            var p = 0; var q = 1
            var maxOff = abs(a[0][1])
            if (abs(a[0][2]) > maxOff) { p = 0; q = 2; maxOff = abs(a[0][2]) }
            if (abs(a[1][2]) > maxOff) { p = 1; q = 2; maxOff = abs(a[1][2]) }
            if (maxOff < 1e-12) break
            val diff = a[q][q] - a[p][p]
            val t = if (abs(a[p][q]) < 1e-15 * abs(diff)) {
                a[p][q] / diff
            } else {
                val phi = diff / (2.0 * a[p][q])
                val sign = if (phi >= 0.0) 1.0 else -1.0
                1.0 / (phi + sign * sqrt(phi * phi + 1.0))
            }
            val c = 1.0 / sqrt(1.0 + t * t)
            val s = t * c
            val tau = s / (1.0 + c)
            val h = t * a[p][q]
            a[p][p] -= h
            a[q][q] += h
            a[p][q] = 0.0
            a[q][p] = 0.0
            for (r in 0..2) {
                if (r != p && r != q) {
                    val arp = a[r][p]
                    val arq = a[r][q]
                    a[r][p] = arp - s * (arq + tau * arp)
                    a[p][r] = a[r][p]
                    a[r][q] = arq + s * (arp - tau * arq)
                    a[q][r] = a[r][q]
                }
                val vrp = v[r][p]
                val vrq = v[r][q]
                v[r][p] = vrp - s * (vrq + tau * vrp)
                v[r][q] = vrq + s * (vrp - tau * vrq)
            }
        }
        var minCol = 0
        if (a[1][1] < a[minCol][minCol]) minCol = 1
        if (a[2][2] < a[minCol][minCol]) minCol = 2
        val nx = v[0][minCol].toFloat()
        val ny = v[1][minCol].toFloat()
        val nz = v[2][minCol].toFloat()
        val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
        return floatArrayOf(nx / len, ny / len, nz / len)
    }

    /** Extracts the unit vector along the smallest scale axis of a 3D Gaussian ellipsoid. */
    internal fun shortestAxisNormal(
        qw: Float, qx: Float, qy: Float, qz: Float,
        s0: Float, s1: Float, s2: Float,
    ): FloatArray {
        val ql = sqrt(qw * qw + qx * qx + qy * qy + qz * qz).coerceAtLeast(1e-6f)
        val w = qw / ql; val x = qx / ql; val y = qy / ql; val z = qz / ql
        val res = when {
            s0 <= s1 && s0 <= s2 -> floatArrayOf(
                1f - 2f * (y * y + z * z),
                2f * (x * y + z * w),
                2f * (x * z - y * w),
            )
            s1 <= s0 && s1 <= s2 -> floatArrayOf(
                2f * (x * y - z * w),
                1f - 2f * (x * x + z * z),
                2f * (y * z + x * w),
            )
            else -> floatArrayOf(
                2f * (x * z + y * w),
                2f * (y * z - x * w),
                1f - 2f * (x * x + y * y),
            )
        }
        val len = sqrt(res[0] * res[0] + res[1] * res[1] + res[2] * res[2]).coerceAtLeast(1e-6f)
        res[0] /= len; res[1] /= len; res[2] /= len
        return res
    }

    /** Returns the two tangent offset vectors (scaled by 0.65 * major/mid radius) of a 3D Gaussian disk. */
    private fun tangentAxes(
        qw: Float, qx: Float, qy: Float, qz: Float,
        s0: Float, s1: Float, s2: Float,
    ): Pair<FloatArray, FloatArray> {
        val ql = sqrt(qw * qw + qx * qx + qy * qy + qz * qz).coerceAtLeast(1e-6f)
        val w = qw / ql; val x = qx / ql; val y = qy / ql; val z = qz / ql
        val c0 = floatArrayOf(1f - 2f * (y * y + z * z), 2f * (x * y + z * w), 2f * (x * z - y * w))
        val c1 = floatArrayOf(2f * (x * y - z * w), 1f - 2f * (x * x + z * z), 2f * (y * z + x * w))
        val c2 = floatArrayOf(2f * (x * z + y * w), 2f * (y * z - x * w), 1f - 2f * (x * x + y * y))
        fun scaled(c: FloatArray, s: Float): FloatArray {
            val k = s * 0.65f
            return floatArrayOf(c[0] * k, c[1] * k, c[2] * k)
        }
        return when {
            s0 <= s1 && s0 <= s2 -> scaled(c1, s1) to scaled(c2, s2)
            s1 <= s0 && s1 <= s2 -> scaled(c0, s0) to scaled(c2, s2)
            else -> scaled(c0, s0) to scaled(c1, s1)
        }
    }

    // ---------------------------------------------------------------- PLY header & helpers

    private data class PlyProp(val name: String, val type: String, val offset: Int, val size: Int)
    private data class PlyHeader(
        val isBinaryLe: Boolean,
        val vertexCount: Int,
        val faceCount: Int,
        val vertexProps: List<PlyProp>,
        val vertexStride: Int,
        val faceCountTypeSize: Int,
        val faceIndexTypeSize: Int,
        val payloadStart: Int,
    )

    private fun parsePlyHeader(data: ByteArray): PlyHeader? {
        val endMarker = "end_header".toByteArray(Charsets.US_ASCII)
        var headerEnd = -1
        outer@ for (i in 0..(data.size - endMarker.size).coerceAtMost(65536)) {
            for (j in endMarker.indices) if (data[i + j] != endMarker[j]) continue@outer
            headerEnd = i
            break
        }
        if (headerEnd < 0) return null
        val header = String(data, 0, headerEnd, Charsets.US_ASCII)
        val lines = header.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val isBinaryLe = lines.any { it.startsWith("format binary_little_endian") }
        val isAscii = lines.any { it.startsWith("format ascii") }
        if (!isBinaryLe && !isAscii) return null

        var section = ""
        var vertexCount = 0
        var faceCount = 0
        val props = ArrayList<PlyProp>()
        var stride = 0
        var faceCountSize = 1
        var faceIndexSize = 4

        fun typeSize(t: String): Int = when (t) {
            "char", "uchar", "int8", "uint8" -> 1
            "short", "ushort", "int16", "uint16" -> 2
            "int", "uint", "int32", "uint32", "float", "float32" -> 4
            "double", "float64" -> 8
            else -> 0
        }

        for (line in lines) {
            if (line.startsWith("element ")) {
                val parts = line.split(Regex("\\s+"))
                section = parts.getOrNull(1).orEmpty()
                val cnt = parts.getOrNull(2)?.toIntOrNull() ?: 0
                if (section == "vertex") vertexCount = cnt
                else if (section == "face") faceCount = cnt
            } else if (section == "vertex" && line.startsWith("property ") && !line.startsWith("property list")) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 3) {
                    val sz = typeSize(parts[1])
                    if (sz <= 0) return null
                    props += PlyProp(parts[2], parts[1], stride, sz)
                    stride += sz
                }
            } else if (section == "face" && line.startsWith("property list ")) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 5) {
                    faceCountSize = typeSize(parts[2]).coerceAtLeast(1)
                    faceIndexSize = typeSize(parts[3]).coerceAtLeast(1)
                }
            }
        }
        var payloadStart = headerEnd + endMarker.size
        while (payloadStart < data.size && (data[payloadStart] == '\r'.code.toByte() || data[payloadStart] == '\n'.code.toByte())) {
            payloadStart++
        }
        return PlyHeader(isBinaryLe, vertexCount, faceCount, props, stride, faceCountSize, faceIndexSize, payloadStart)
    }

    private fun readPropFloat(bb: ByteBuffer, base: Int, p: PlyProp): Float = when (p.type) {
        "float", "float32" -> bb.getFloat(base + p.offset)
        "double", "float64" -> bb.getDouble(base + p.offset).toFloat()
        else -> 0f
    }

    private fun readPropColorByte(bb: ByteBuffer, data: ByteArray, base: Int, p: PlyProp?): Byte {
        if (p == null) return 0xD2.toByte()
        return when (p.type) {
            "uchar", "uint8", "char", "int8" -> data[base + p.offset]
            "float", "float32" -> (bb.getFloat(base + p.offset).coerceIn(0f, 1f) * 255f).toInt().toByte()
            else -> 0xD2.toByte()
        }
    }

    private fun readIndex(bb: ByteBuffer, data: ByteArray, offset: Int, size: Int): Int = when (size) {
        1 -> data[offset].toInt() and 0xFF
        2 -> bb.getShort(offset).toInt() and 0xFFFF
        4 -> bb.getInt(offset)
        else -> -1
    }

    private fun sigmoid(x: Float): Float {
        val c = x.coerceIn(-20f, 20f)
        return (1.0 / (1.0 + exp(-c.toDouble()))).toFloat()
    }

    private fun safeExp(x: Float): Float = exp(x.coerceIn(-20f, 10f).toDouble()).toFloat()

    private fun packCell(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x1FFFFFL) shl 42) or ((y.toLong() and 0x1FFFFFL) shl 21) or (z.toLong() and 0x1FFFFFL)

    /** 2nd–98th percentile bounds so stray outlier points cannot inflate voxel sizing. */
    private fun computeRobustBounds(xyz: FloatArray, count: Int): Pair<FloatArray, FloatArray> {
        if (count <= 0) return floatArrayOf(0f, 0f, 0f) to floatArrayOf(0f, 0f, 0f)
        val step = (count / 8192).coerceAtLeast(1)
        val m = (count + step - 1) / step
        val xs = FloatArray(m)
        val ys = FloatArray(m)
        val zs = FloatArray(m)
        var k = 0
        var i = 0
        while (i < count && k < m) {
            xs[k] = xyz[i * 3]; ys[k] = xyz[i * 3 + 1]; zs[k] = xyz[i * 3 + 2]
            k++; i += step
        }
        xs.sort(0, k); ys.sort(0, k); zs.sort(0, k)
        val lo = (k * 0.02).toInt().coerceIn(0, k - 1)
        val hi = (k * 0.98).toInt().coerceIn(lo, k - 1)
        return floatArrayOf(xs[lo], ys[lo], zs[lo]) to floatArrayOf(xs[hi], ys[hi], zs[hi])
    }

    private fun sanitizeStem(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifEmpty { "scan" }.take(64)
}
