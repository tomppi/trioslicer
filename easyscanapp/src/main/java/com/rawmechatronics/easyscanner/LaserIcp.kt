package com.rawmechatronics.easyscanner

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 6-DoF rigid registration for overlapping DA3 + laser patches.
 *
 * Supports:
 * 1. Exact marker-board PnP transfer when both the submap anchor and the new patch
 *    observed the known-geometry ChArUco board (full 6-DoF including Z, zero drift).
 * 2. Fast voxel-hashed point-to-plane + point-to-point hybrid Gauss-Newton ICP on se(3)
 *    to refine a VIO initial pose against the active submap's metric surface.
 * 3. Strict overlap, residual, and max-correction gates so low-overlap patches stay
 *    in a separate submap instead of being forced into a bad merge.
 */
object LaserIcp {

    data class Config(
        val maxCorrDistM: Double = 0.025,
        val huberDeltaM: Double = 0.005,
        val maxIterations: Int = 15,
        val minInliers: Int = 25,
        val minOverlapFraction: Double = 0.20,
        val maxMedianResidualMm: Double = 6.0,
        val maxCorrectionTranslationM: Double = 0.08,
        val maxCorrectionRotationDeg: Double = 12.0,
        val pointToPointWeight: Double = 0.08,
    )

    data class Result(
        val accepted: Boolean,
        val refinedPose: RigidPose,
        val inlierCount: Int,
        val sourceCount: Int,
        val overlapFraction: Double,
        val initialMedianResidualMm: Double,
        val finalMedianResidualMm: Double,
        val correctionTranslationM: Double,
        val correctionRotationDeg: Double,
        val usedMarkerAnchor: Boolean,
        val reason: String,
    )

    /** Spatial voxel hash over target 3D points + optional unit normals (world metres). */
    class SpatialSurface(private val cellSizeM: Double) {
        private val px = ArrayList<Float>()
        private val py = ArrayList<Float>()
        private val pz = ArrayList<Float>()
        private val nx = ArrayList<Float>()
        private val ny = ArrayList<Float>()
        private val nz = ArrayList<Float>()
        private val hasNormal = ArrayList<Boolean>()
        private val buckets = HashMap<Long, ArrayList<Int>>()

        val size: Int get() = px.size

        fun addPoints(positions: FloatArray, normals: FloatArray? = null, stride: Int = 1) {
            val n = positions.size / 3
            var i = 0
            while (i < n) {
                val o = i * 3
                val x = positions[o]; val y = positions[o + 1]; val z = positions[o + 2]
                if (x.isFinite() && y.isFinite() && z.isFinite()) {
                    val idx = px.size
                    px.add(x); py.add(y); pz.add(z)
                    if (normals != null && o + 2 < normals.size) {
                        val a = normals[o]; val b = normals[o + 1]; val c = normals[o + 2]
                        val len = sqrt(a * a + b * b + c * c)
                        if (len > 0.5f && len.isFinite()) {
                            nx.add(a / len); ny.add(b / len); nz.add(c / len)
                            hasNormal.add(true)
                        } else {
                            nx.add(0f); ny.add(0f); nz.add(0f); hasNormal.add(false)
                        }
                    } else {
                        nx.add(0f); ny.add(0f); nz.add(0f); hasNormal.add(false)
                    }
                    val key = voxelKey(x.toDouble(), y.toDouble(), z.toDouble())
                    buckets.getOrPut(key) { ArrayList(4) }.add(idx)
                }
                i += stride
            }
        }

        /** Exact nearest neighbor within [maxDistM] (auto-expands cell radius for coarse stage). */
        fun findNearest(qx: Double, qy: Double, qz: Double, maxDistM: Double): Int {
            val ix = floor(qx / cellSizeM).toInt()
            val iy = floor(qy / cellSizeM).toInt()
            val iz = floor(qz / cellSizeM).toInt()
            val rad = kotlin.math.ceil(maxDistM / cellSizeM).toInt().coerceIn(1, 3)
            var bestIdx = -1
            var bestD2 = maxDistM * maxDistM
            for (dz in -rad..rad) for (dy in -rad..rad) for (dx in -rad..rad) {
                val list = buckets[packKey(ix + dx, iy + dy, iz + dz)] ?: continue
                for (k in 0 until list.size) {
                    val idx = list[k]
                    val ex = qx - px[idx]
                    val ey = qy - py[idx]
                    val ez = qz - pz[idx]
                    val d2 = ex * ex + ey * ey + ez * ez
                    if (d2 < bestD2) {
                        bestD2 = d2
                        bestIdx = idx
                    }
                }
            }
            return bestIdx
        }

        fun pointX(i: Int): Double = px[i].toDouble()
        fun pointY(i: Int): Double = py[i].toDouble()
        fun pointZ(i: Int): Double = pz[i].toDouble()
        fun hasNormal(i: Int): Boolean = hasNormal[i]
        fun normalX(i: Int): Double = nx[i].toDouble()
        fun normalY(i: Int): Double = ny[i].toDouble()
        fun normalZ(i: Int): Double = nz[i].toDouble()

        private fun voxelKey(x: Double, y: Double, z: Double): Long =
            packKey(floor(x / cellSizeM).toInt(), floor(y / cellSizeM).toInt(), floor(z / cellSizeM).toInt())

        private fun packKey(ix: Int, iy: Int, iz: Int): Long =
            (ix.toLong() and 0x1fffffL) or
                ((iy.toLong() and 0x1fffffL) shl 21) or
                ((iz.toLong() and 0x1fffffL) shl 42)
    }

    // --------------------------------------------------------- pose algebra

    /** Compose `B * A` for two rigid world->camera transforms. */
    fun compose(b: RigidPose, a: RigidPose): RigidPose {
        val r = mul3(b.r, a.r)
        val t = doubleArrayOf(
            b.r[0] * a.t[0] + b.r[1] * a.t[1] + b.r[2] * a.t[2] + b.t[0],
            b.r[3] * a.t[0] + b.r[4] * a.t[1] + b.r[5] * a.t[2] + b.t[1],
            b.r[6] * a.t[0] + b.r[7] * a.t[1] + b.r[8] * a.t[2] + b.t[2],
        )
        return RigidPose(r, t)
    }

    /** Invert a rigid transform `(R, t)` -> `(R^T, -R^T t)`. */
    fun invert(p: RigidPose): RigidPose {
        val rt = transpose3(p.r)
        val t = doubleArrayOf(
            -(rt[0] * p.t[0] + rt[1] * p.t[1] + rt[2] * p.t[2]),
            -(rt[3] * p.t[0] + rt[4] * p.t[1] + rt[5] * p.t[2]),
            -(rt[6] * p.t[0] + rt[7] * p.t[1] + rt[8] * p.t[2]),
        )
        return RigidPose(rt, t)
    }

    /**
     * Transfers a new camera into the submap world using a shared calibration board
     * seen in both the anchor frame and the new frame (exact 6-DoF PnP, zero VIO drift):
     * `T_world->cam_new = T_board->cam_new * inv(T_board->cam_anchor) * T_world->cam_anchor`.
     */
    fun poseFromSharedBoard(
        anchorWorldToCam: RigidPose,
        anchorBoardToCam: RigidPose,
        newBoardToCam: RigidPose,
    ): RigidPose = compose(compose(newBoardToCam, invert(anchorBoardToCam)), anchorWorldToCam)

    /**
     * Applies a world-space rigid correction `p_new = dR * p_old + dt` to a
     * `world -> camera` pose: `R_new = R * dR^T`, `t_new = t - R_new * dt`.
     */
    fun applyWorldCorrection(pose: RigidPose, dR: DoubleArray, dt: DoubleArray): RigidPose {
        val rNew = mul3(pose.r, transpose3(dR))
        val tNew = doubleArrayOf(
            pose.t[0] - (rNew[0] * dt[0] + rNew[1] * dt[1] + rNew[2] * dt[2]),
            pose.t[1] - (rNew[3] * dt[0] + rNew[4] * dt[1] + rNew[5] * dt[2]),
            pose.t[2] - (rNew[6] * dt[0] + rNew[7] * dt[1] + rNew[8] * dt[2]),
        )
        return RigidPose(rNew, tNew)
    }

    /** Relative rotation angle between two camera poses in degrees. */
    fun rotationDeltaDeg(a: RigidPose, b: RigidPose): Double {
        // trace(R_a * R_b^T) = sum_ij R_a[i,j] * R_b[i,j]
        var tr = 0.0
        for (i in 0 until 9) tr += a.r[i] * b.r[i]
        val c = ((tr - 1.0) * 0.5).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }

    /** Euclidean distance between two camera centres in metres. */
    fun translationDeltaM(a: RigidPose, b: RigidPose): Double {
        val ca = a.cameraCentre()
        val cb = b.cameraCentre()
        val dx = ca[0] - cb[0]; val dy = ca[1] - cb[1]; val dz = ca[2] - cb[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // --------------------------------------------------------- 6-DoF hybrid ICP

    /**
     * Refines [initialPose] (world -> camera) so that camera-frame source points
     * [sourceCamM] align with [target] (world metres). Returns an accepted/rejected
     * [Result] based on overlap, final residual, and correction bounds.
     */
    fun align(
        target: SpatialSurface,
        sourceCamM: FloatArray,
        initialPose: RigidPose,
        config: Config = Config(),
        usedMarkerAnchor: Boolean = false,
    ): Result {
        val srcCount = sourceCamM.size / 3
        if (srcCount < config.minInliers || target.size < config.minInliers) {
            return Result(
                accepted = false, refinedPose = initialPose, inlierCount = 0, sourceCount = srcCount,
                overlapFraction = 0.0, initialMedianResidualMm = Double.NaN, finalMedianResidualMm = Double.NaN,
                correctionTranslationM = 0.0, correctionRotationDeg = 0.0,
                usedMarkerAnchor = usedMarkerAnchor, reason = "insufficient points",
            )
        }

        // Project source points to initial world coordinates.
        val initWorld = DoubleArray(srcCount * 3)
        var validSrc = 0
        for (i in 0 until srcCount) {
            val x = sourceCamM[i * 3].toDouble()
            val y = sourceCamM[i * 3 + 1].toDouble()
            val z = sourceCamM[i * 3 + 2].toDouble()
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() || z <= 0.02 || z > 20.0) continue
            val w = initialPose.cameraToWorld(x, y, z)
            initWorld[validSrc * 3] = w[0]
            initWorld[validSrc * 3 + 1] = w[1]
            initWorld[validSrc * 3 + 2] = w[2]
            validSrc++
        }
        if (validSrc < config.minInliers) {
            return Result(
                accepted = false, refinedPose = initialPose, inlierCount = 0, sourceCount = validSrc,
                overlapFraction = 0.0, initialMedianResidualMm = Double.NaN, finalMedianResidualMm = Double.NaN,
                correctionTranslationM = 0.0, correctionRotationDeg = 0.0,
                usedMarkerAnchor = usedMarkerAnchor, reason = "insufficient valid points",
            )
        }

        // Accumulate rigid world correction (dR, dt): p' = dR * p_init + dt.
        var dR = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        var dt = doubleArrayOf(0.0, 0.0, 0.0)

        var initialMedMm = Double.NaN
        var finalMedMm = Double.NaN
        var lastInliers = 0
        val residuals = ArrayList<Double>(validSrc)
        val hMat = DoubleArray(36)
        val gVec = DoubleArray(6)
        val jRow = DoubleArray(6)

        // Two-stage coarse-to-fine correspondence radius: first 5 iterations pull in up to
        // 3x maxCorrDistM (e.g. 10 cm) of VIO drift before polishing at the strict fine radius.
        val coarseDist = (config.maxCorrDistM * 2.8).coerceAtMost(0.11)
        val coarseIters = 5
        val totalIters = config.maxIterations + coarseIters
        for (iter in 0 until totalIters) {
            val iterCorrDist = if (iter < coarseIters) coarseDist else config.maxCorrDistM
            // Centroid of currently transformed source points for well-conditioned rotation Jacobian.
            var cx = 0.0; var cy = 0.0; var cz = 0.0
            for (i in 0 until validSrc) {
                val ox = initWorld[i * 3]; val oy = initWorld[i * 3 + 1]; val oz = initWorld[i * 3 + 2]
                cx += dR[0] * ox + dR[1] * oy + dR[2] * oz + dt[0]
                cy += dR[3] * ox + dR[4] * oy + dR[5] * oz + dt[1]
                cz += dR[6] * ox + dR[7] * oy + dR[8] * oz + dt[2]
            }
            cx /= validSrc; cy /= validSrc; cz /= validSrc

            hMat.fill(0.0)
            gVec.fill(0.0)
            residuals.clear()
            var inliers = 0

            for (i in 0 until validSrc) {
                val ox = initWorld[i * 3]; val oy = initWorld[i * 3 + 1]; val oz = initWorld[i * 3 + 2]
                val px = dR[0] * ox + dR[1] * oy + dR[2] * oz + dt[0]
                val py = dR[3] * ox + dR[4] * oy + dR[5] * oz + dt[1]
                val pz = dR[6] * ox + dR[7] * oy + dR[8] * oz + dt[2]
                val tid = target.findNearest(px, py, pz, iterCorrDist)
                if (tid < 0) continue
                inliers++
                val qx = target.pointX(tid); val qy = target.pointY(tid); val qz = target.pointZ(tid)
                val ex = px - qx; val ey = py - qy; val ez = pz - qz
                val dist = sqrt(ex * ex + ey * ey + ez * ez)
                val pcx = px - cx; val pcy = py - cy; val pcz = pz - cz

                val usePlane = target.hasNormal(tid)
                if (usePlane) {
                    val nx = target.normalX(tid); val ny = target.normalY(tid); val nz = target.normalZ(tid)
                    val rPlane = nx * ex + ny * ey + nz * ez
                    residuals.add(abs(rPlane) * 1000.0)
                    val w = huberWeight(abs(rPlane), config.huberDeltaM)
                    // J_plane = [(p_c x n)^T, n^T]
                    jRow[0] = pcy * nz - pcz * ny
                    jRow[1] = pcz * nx - pcx * nz
                    jRow[2] = pcx * ny - pcy * nx
                    jRow[3] = nx; jRow[4] = ny; jRow[5] = nz
                    accumNormalEq(hMat, gVec, jRow, rPlane, w)
                } else {
                    residuals.add(dist * 1000.0)
                }
                // Point-to-point term (primary when no normal, gentle stabilizer on planes).
                val wPt = (if (usePlane) config.pointToPointWeight else 1.0) * huberWeight(dist, config.huberDeltaM)
                if (wPt > 0.0) {
                    // x row: d(px)/d(w, t) = [0, pcz, -pcy, 1, 0, 0]
                    jRow[0] = 0.0; jRow[1] = pcz; jRow[2] = -pcy; jRow[3] = 1.0; jRow[4] = 0.0; jRow[5] = 0.0
                    accumNormalEq(hMat, gVec, jRow, ex, wPt)
                    // y row: d(py)/d(w, t) = [-pcz, 0, pcx, 0, 1, 0]
                    jRow[0] = -pcz; jRow[1] = 0.0; jRow[2] = pcx; jRow[3] = 0.0; jRow[4] = 1.0; jRow[5] = 0.0
                    accumNormalEq(hMat, gVec, jRow, ey, wPt)
                    // z row: d(pz)/d(w, t) = [pcy, -pcx, 0, 0, 0, 1]
                    jRow[0] = pcy; jRow[1] = -pcx; jRow[2] = 0.0; jRow[3] = 0.0; jRow[4] = 0.0; jRow[5] = 1.0
                    accumNormalEq(hMat, gVec, jRow, ez, wPt)
                }
            }

            lastInliers = inliers
            if (inliers < config.minInliers) break
            val medMm = median(residuals)
            if (iter == 0) initialMedMm = medMm
            finalMedMm = medMm

            // Levenberg-Marquardt damping for numerical stability.
            for (d in 0 until 6) hMat[d * 6 + d] += 1e-4
            val xi = solveSymmetric6x6(hMat, gVec) ?: break
            val wNorm = sqrt(xi[0] * xi[0] + xi[1] * xi[1] + xi[2] * xi[2])
            val tNorm = sqrt(xi[3] * xi[3] + xi[4] * xi[4] + xi[5] * xi[5])
            if (!wNorm.isFinite() || !tNorm.isFinite()) break

            // Apply incremental step around centroid c: p'' = R_step * (p' - c) + c + t_step.
            val rStep = rodrigues(xi[0], xi[1], xi[2])
            val dtMinusC = doubleArrayOf(dt[0] - cx, dt[1] - cy, dt[2] - cz)
            dR = mul3(rStep, dR)
            dt = doubleArrayOf(
                rStep[0] * dtMinusC[0] + rStep[1] * dtMinusC[1] + rStep[2] * dtMinusC[2] + cx + xi[3],
                rStep[3] * dtMinusC[0] + rStep[4] * dtMinusC[1] + rStep[5] * dtMinusC[2] + cy + xi[4],
                rStep[6] * dtMinusC[0] + rStep[7] * dtMinusC[1] + rStep[8] * dtMinusC[2] + cz + xi[5],
            )

            if (iter >= coarseIters && wNorm < 1e-4 && tNorm < 1e-5) break
        }

        val overlap = lastInliers.toDouble() / validSrc
        val refined = applyWorldCorrection(initialPose, dR, dt)
        val corrTrans = translationDeltaM(initialPose, refined)
        val corrRot = rotationDeltaDeg(initialPose, refined)

        // Re-evaluate final median residual at the converged pose.
        if (lastInliers >= config.minInliers) {
            residuals.clear()
            var finalInliers = 0
            for (i in 0 until validSrc) {
                val ox = initWorld[i * 3]; val oy = initWorld[i * 3 + 1]; val oz = initWorld[i * 3 + 2]
                val px = dR[0] * ox + dR[1] * oy + dR[2] * oz + dt[0]
                val py = dR[3] * ox + dR[4] * oy + dR[5] * oz + dt[1]
                val pz = dR[6] * ox + dR[7] * oy + dR[8] * oz + dt[2]
                val tid = target.findNearest(px, py, pz, config.maxCorrDistM)
                if (tid < 0) continue
                finalInliers++
                val ex = px - target.pointX(tid); val ey = py - target.pointY(tid); val ez = pz - target.pointZ(tid)
                val err = if (target.hasNormal(tid)) {
                    abs(target.normalX(tid) * ex + target.normalY(tid) * ey + target.normalZ(tid) * ez)
                } else sqrt(ex * ex + ey * ey + ez * ez)
                residuals.add(err * 1000.0)
            }
            lastInliers = finalInliers
            if (residuals.isNotEmpty()) finalMedMm = median(residuals)
        }

        val finalOverlap = lastInliers.toDouble() / validSrc
        val reason = when {
            lastInliers < config.minInliers -> "too few correspondences ($lastInliers/${config.minInliers})"
            finalOverlap < config.minOverlapFraction -> String.format("low overlap (%.0f%%)", finalOverlap * 100.0)
            !finalMedMm.isFinite() || finalMedMm > config.maxMedianResidualMm ->
                String.format("residual too high (%.1f mm)", finalMedMm)
            corrTrans > config.maxCorrectionTranslationM ->
                String.format("translation jump too large (%.1f mm)", corrTrans * 1000.0)
            corrRot > config.maxCorrectionRotationDeg ->
                String.format("rotation jump too large (%.1f deg)", corrRot)
            else -> "ok"
        }
        val accepted = reason == "ok"
        return Result(
            accepted = accepted,
            refinedPose = if (accepted) refined else initialPose,
            inlierCount = lastInliers,
            sourceCount = validSrc,
            overlapFraction = finalOverlap,
            initialMedianResidualMm = initialMedMm,
            finalMedianResidualMm = finalMedMm,
            correctionTranslationM = corrTrans,
            correctionRotationDeg = corrRot,
            usedMarkerAnchor = usedMarkerAnchor,
            reason = reason,
        )
    }

    // --------------------------------------------------------- linear algebra helpers

    private fun huberWeight(absR: Double, delta: Double): Double =
        if (absR <= delta) 1.0 else delta / absR

    private fun accumNormalEq(h: DoubleArray, g: DoubleArray, j: DoubleArray, r: Double, w: Double) {
        for (a in 0 until 6) {
            val wa = w * j[a]
            g[a] -= wa * r
            val row = a * 6
            for (b in a until 6) {
                h[row + b] += wa * j[b]
            }
        }
    }

    /** Solves symmetric positive-definite 6x6 system `A * x = b` via Cholesky decomposition. */
    private fun solveSymmetric6x6(a: DoubleArray, b: DoubleArray): DoubleArray? {
        val l = DoubleArray(36)
        for (i in 0 until 6) {
            for (j in 0..i) {
                var sum = if (j <= i) a[j * 6 + i] else a[i * 6 + j]
                for (k in 0 until j) sum -= l[i * 6 + k] * l[j * 6 + k]
                if (i == j) {
                    if (sum <= 1e-15 || !sum.isFinite()) return null
                    l[i * 6 + j] = sqrt(sum)
                } else {
                    l[i * 6 + j] = sum / l[j * 6 + j]
                }
            }
        }
        val y = DoubleArray(6)
        for (i in 0 until 6) {
            var sum = b[i]
            for (k in 0 until i) sum -= l[i * 6 + k] * y[k]
            y[i] = sum / l[i * 6 + i]
        }
        val x = DoubleArray(6)
        for (i in 5 downTo 0) {
            var sum = y[i]
            for (k in i + 1 until 6) sum -= l[k * 6 + i] * x[k]
            x[i] = sum / l[i * 6 + i]
        }
        return x
    }

    private fun rodrigues(wx: Double, wy: Double, wz: Double): DoubleArray {
        val theta = sqrt(wx * wx + wy * wy + wz * wz)
        if (theta < 1e-10) {
            return doubleArrayOf(
                1.0, -wz, wy,
                wz, 1.0, -wx,
                -wy, wx, 1.0,
            )
        }
        val kx = wx / theta; val ky = wy / theta; val kz = wz / theta
        val c = cos(theta); val s = sin(theta); val v = 1.0 - c
        return doubleArrayOf(
            c + kx * kx * v, kx * ky * v - kz * s, kx * kz * v + ky * s,
            ky * kx * v + kz * s, c + ky * ky * v, ky * kz * v - kx * s,
            kz * kx * v - ky * s, kz * ky * v + kx * s, c + kz * kz * v,
        )
    }

    private fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) {
            out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
        return out
    }

    private fun transpose3(a: DoubleArray): DoubleArray = doubleArrayOf(
        a[0], a[3], a[6],
        a[1], a[4], a[7],
        a[2], a[5], a[8],
    )

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        return s[((s.size - 1) * 0.5).roundToInt()]
    }
}
