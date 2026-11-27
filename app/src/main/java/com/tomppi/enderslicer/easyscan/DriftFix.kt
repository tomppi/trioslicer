package com.tomppi.enderslicer.easyscan

import android.content.Context
import android.util.Log
import com.rawmechatronics.easyscanner.AssetCopy
import com.rawmechatronics.easyscanner.LoopDb
import com.rawmechatronics.easyscanner.LoopMatcher
import com.rawmechatronics.easyscanner.SpTflite
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Drift correction: SuperPoint loop closures + ARCore odometry solved as an
 * SE(3) pose graph (all three rotation and translation axes), then every point
 * is moved by the interpolated correction of its own engine frame.
 */
object DriftFix {
    private const val TAG = "DriftFix"
    private const val MAX_KF = 60
    private const val MAX_KP = 300
    private const val ROT_SCALE = 100.0
    private const val PRIOR_W = 500.0
    private const val ODOM_W = 1.0
    private const val LOOP_W = 0.35

    private class Kf(val file: File, val frame: Int, val W: DoubleArray, val C: DoubleArray, val K: DoubleArray)
    private class Feat(val kp: FloatArray, val desc: FloatArray, val n: Int)
    private class Edge(
        val a: Int, val b: Int,
        val rot: DoubleArray, val dir: DoubleArray, val scale: Double,
        val transW: Double, val rotW: Double,
    )
    data class Out(
        val keyframes: Int, val points: Int,
        val meanRotDeg: Double, val meanTransMm: Double,
        val before: Double, val after: Double, val out: File,
    )

    fun fix(ctx: Context, ply: File, kfDir: File, onProgress: (String) -> Unit): Out? {
        val side = File(ply.absolutePath + ".frames")
        if (!side.isFile) { onProgress("fix drift: no frame ids next to " + ply.name); return null }
        val all = loadKeyframes(kfDir)
        if (all.size < 4) { onProgress("fix drift: need keyframes with frame numbers"); return null }
        val step = maxOf(1, (all.size + MAX_KF - 1) / MAX_KF)
        val kfs = ArrayList<Kf>()
        var i = 0
        while (i < all.size) { kfs.add(all[i]); i += step }
        if (kfs.last() !== all.last()) kfs.add(all.last())
        onProgress("fix drift: " + kfs.size + " keyframes, extracting features...")

        val feats = arrayOfNulls<Feat>(kfs.size)
        var xfeatHandle = 0L
        try {
            val model = AssetCopy.ensure(ctx, "xfeat.tflite", File(ctx.filesDir, "xfeat.tflite"))
            xfeatHandle = SpTflite.create(ctx.applicationInfo.nativeLibraryDir, model.absolutePath, 0.0, 0.0, 0.0, 0.0)
            SpTflite.setFeatureCap(400)
            if (xfeatHandle != 0L && !(xfeatHandle in -4L..-1L)) {
                for (k in kfs.indices) {
                    feats[k] = features(xfeatHandle, kfs[k].file)
                    if (k % 4 == 0) onProgress("fix drift: features " + (k + 1) + "/" + kfs.size)
                }
            }
        } finally {
            if (xfeatHandle != 0L && !(xfeatHandle in -4L..-1L)) {
                try { SpTflite.close(xfeatHandle) } catch (_: Exception) { }
            }
        }
        val valid = kfs.indices.filter { feats[it] != null && feats[it]!!.n >= 40 }
        if (valid.size < 4) { onProgress("fix drift: too few usable keyframes"); return null }
        try { LoopMatcher.init(ctx) } catch (_: Exception) { }
        onProgress("fix drift: matching loop candidates...")

        val edges = ArrayList<Edge>()
        for (u in 0 until valid.size - 1) {
            val a = valid[u]; val b = valid[u + 1]
            val arcRot = mul3T(kfs[b].W, kfs[a].W)
            val arcT = mulVec3(transpose3(kfs[a].W), sub3(kfs[b].C, kfs[a].C))
            val s = len3(arcT)
            if (s < 1e-6) continue
            edges.add(Edge(a, b, arcRot, normalize3(arcT), s, ODOM_W, ODOM_W))
        }
        var loops = 0
        // XFeat 64-D local-descriptor MNN loop search.
        var dbLoaded = false
        try {
            dbLoaded = LoopDb.load("xfeat64")
        } catch (ex: Exception) {
            onProgress("fix drift: LoopDb unavailable: " + ex.message)
        }
        if (dbLoaded) {
            LoopDb.clear()
            val dbToValid = HashMap<Int, Int>()
            for (u in valid.indices) {
                val f = feats[valid[u]]!!
                val got = LoopDb.add(kfs[valid[u]].frame, f.desc, f.n)
                if (got >= 0) dbToValid[got] = u
            }
            for (u in valid.indices) {
                val f = feats[valid[u]]!!
                val candidates = LoopDb.query(f.desc, f.n, 8)
                for ((dbId, score) in candidates) {
                    val v = dbToValid[dbId] ?: continue
                    if (v <= u + 4) continue
                    val a = valid[u]; val b = valid[v]
                    val arcRot = mul3T(kfs[b].W, kfs[a].W)
                    val arcT = mulVec3(transpose3(kfs[a].W), sub3(kfs[b].C, kfs[a].C))
                    val s = len3(arcT)
                    if (s < 5.0) continue
                    val m = visual(kfs[a], kfs[b], feats[a]!!, feats[b]!!, arcRot, normalize3(arcT)) ?: continue
                    edges.add(Edge(a, b, m.first, m.second, s, LOOP_W, LOOP_W))
                    loops++
                }
            }
            onProgress("fix drift: DBoW3 candidates -> " + loops + " verified loops")
            if (loops == 0) {
                // A vocabulary trained on a different environment may not find
                // the loop; keep the old nearby-keyframe verification as a
                // last resort instead of failing the whole correction.
                for (u in valid.indices) {
                    for (gap in intArrayOf(12, 26)) {
                        val v = u + gap
                        if (v >= valid.size) continue
                        val a = valid[u]; val b = valid[v]
                        val arcRot = mul3T(kfs[b].W, kfs[a].W)
                        val arcT = mulVec3(transpose3(kfs[a].W), sub3(kfs[b].C, kfs[a].C))
                        val s = len3(arcT)
                        if (s < 5.0) continue
                        val m = visual(kfs[a], kfs[b], feats[a]!!, feats[b]!!, arcRot, normalize3(arcT)) ?: continue
                        edges.add(Edge(a, b, m.first, m.second, s, LOOP_W, LOOP_W))
                        loops++
                    }
                }
            }
        } else {
            for (u in valid.indices) {
                for (gap in intArrayOf(12, 26)) {
                    val v = u + gap
                    if (v >= valid.size) continue
                    val a = valid[u]; val b = valid[v]
                    val arcRot = mul3T(kfs[b].W, kfs[a].W)
                    val arcT = mulVec3(transpose3(kfs[a].W), sub3(kfs[b].C, kfs[a].C))
                    val s = len3(arcT)
                    if (s < 5.0) continue
                    val m = visual(kfs[a], kfs[b], feats[a]!!, feats[b]!!, arcRot, normalize3(arcT)) ?: continue
                    edges.add(Edge(a, b, m.first, m.second, s, LOOP_W, LOOP_W))
                    loops++
                }
            }
        }
        if (loops < 2) {
            onProgress("fix drift: no verified loop pairs (walk back over the object)")
            return null
        }
        onProgress("fix drift: " + loops + " loop edges, solving " + kfs.size + " poses...")

        val n = kfs.size
        val x = DoubleArray(n * 6)
        var before = 0.0
        var after = 0.0
        try {
            before = norm(residuals(x, kfs, edges))
            var lambda = 1e-3
            var cost = before
            for (iter in 0 until 8) {
                val r = residuals(x, kfs, edges)
                val m = 6 * n
                val e = r.size
                val jac = Array(e) { DoubleArray(m) }
                for (k in 0 until m) {
                    val h = if (k % 6 < 3) 1e-7 else 1e-3
                    x[k] += h
                    val r2 = residuals(x, kfs, edges)
                    x[k] -= h
                    for (row in 0 until e) jac[row][k] = (r2[row] - r[row]) / h
                }
                val jtj = Array(m) { DoubleArray(m) }
                val jtr = DoubleArray(m)
                for (row in 0 until e) {
                    val jr = jac[row]
                    for (p in 0 until m) {
                        val jp = jr[p]
                        if (jp == 0.0) continue
                        jtr[p] += jp * r[row]
                        for (q in 0..p) jtj[p][q] += jp * jr[q]
                    }
                }
                for (p in 0 until m) for (q in 0 until p) jtj[q][p] = jtj[p][q]
                for (p in 0 until m) jtj[p][p] = jtj[p][p] * (1.0 + lambda) + 1e-9
                val dx = solve(jtj, DoubleArray(m) { -jtr[it] }) ?: break
                for (k in 0 until m) x[k] += dx[k]
                val c2 = norm(residuals(x, kfs, edges))
                if (c2 > cost) {
                    for (k in 0 until m) x[k] -= dx[k]
                    lambda *= 7.0
                } else {
                    cost = c2
                    lambda = maxOf(1e-5, lambda * 0.5)
                }
            }
            after = norm(residuals(x, kfs, edges))
        } catch (ex: Exception) {
            Log.e(TAG, "pose graph failed", ex)
            return null
        }

        onProgress("fix drift: moving points...")
        val corr = corrections(x, kfs)
        val data = ply.readBytes()
        val marker = "end_header".toByteArray(Charsets.US_ASCII) + byteArrayOf(10)
        val end = indexOf(data, marker)
        if (end < 0) return null
        val headerEnd = end + marker.size
        val header = String(data, 0, headerEnd, Charsets.US_ASCII)
        val cnt = header.lineSequence().firstOrNull { it.startsWith("element vertex ") }
            ?.split(" ")?.last()?.toIntOrNull() ?: return null
        val stride = 15
        val fbb = ByteBuffer.wrap(side.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        if (fbb.int != cnt) return null
        val outData = data.copyOf()
        val ob = ByteBuffer.wrap(outData).order(ByteOrder.LITTLE_ENDIAN)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var sumRot = 0.0
        var sumTrans = 0.0
        var samples = 0
        for (i in 0 until cnt) {
            val f = fbb.int
            val o = headerEnd + i * stride
            val c = correctionAt(corr, f, kfs) ?: continue
            val px = bb.getFloat(o).toDouble()
            val py = bb.getFloat(o + 4).toDouble()
            val pz = bb.getFloat(o + 8).toDouble()
            val rot = doubleArrayOf(c[0],c[1],c[2],c[3],c[4],c[5],c[6],c[7],c[8])
            val centre = doubleArrayOf(c[9], c[10], c[11])
            val v = doubleArrayOf(c[12], c[13], c[14])
            val d = doubleArrayOf(px - centre[0], py - centre[1], pz - centre[2])
            val q = mulVec3(rot, d)
            ob.putFloat(o, (q[0] + centre[0] + v[0]).toFloat())
            ob.putFloat(o + 4, (q[1] + centre[1] + v[1]).toFloat())
            ob.putFloat(o + 8, (q[2] + centre[2] + v[2]).toFloat())
            if (i % 64 == 0) {
                sumRot += Math.toDegrees(rotAngle(rot))
                sumTrans += Math.hypot(v[0], Math.hypot(v[1], v[2]))
                samples++
            }
        }
        val out = File(ply.parentFile, ply.name.removeSuffix(".ply") + "-fixed.ply")
        FileOutputStream(out).use { it.write(outData) }
        Log.i(TAG, "fixed " + cnt + " pts / " + n + " keyframes, " + loops + " loops; cost " + before + " -> " + after)
        return Out(n, cnt, sumRot / Math.max(1, samples), sumTrans / Math.max(1, samples), before, after, out)
    }

    // ── keyframes and features ──────────────────────────────────────────────

    private fun loadKeyframes(dir: File): List<Kf> {
        val f = File(dir, "keyframes.jsonl")
        if (!f.isFile) return emptyList()
        val out = ArrayList<Kf>()
        for (line in f.readLines()) {
            if (line.isBlank()) continue
            try {
                val o = JSONObject(line)
                val frame = o.optInt("frame", 0)
                if (frame <= 0) continue
                val jpeg = File(dir, o.getString("file"))
                if (!jpeg.isFile) continue
                val p = o.getJSONArray("pose")
                val m = DoubleArray(16) { p.getDouble(it) }
                val Rsc = doubleArrayOf(m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10])
                val t = doubleArrayOf(m[3], m[7], m[11])
                val W = transpose3(Rsc)
                val C = mulVec3(W, doubleArrayOf(-t[0], -t[1], -t[2]))
                val k = o.getJSONArray("k")
                out.add(Kf(jpeg, frame, W, C, doubleArrayOf(k.getDouble(0), k.getDouble(1), k.getDouble(2), k.getDouble(3))))
            } catch (ex: Exception) {
                // skip malformed row
            }
        }
        return out.sortedBy { it.frame }
    }

    private fun features(xfeatHandle: Long, jpeg: File): Feat? {
        val src = android.graphics.BitmapFactory.decodeFile(jpeg.absolutePath) ?: return null
        val bmp = android.graphics.Bitmap.createScaledBitmap(src, 640, 480, true)
        val pixels = IntArray(640 * 480)
        bmp.getPixels(pixels, 0, 640, 0, 0, 640, 480)
        if (bmp !== src) bmp.recycle()
        src.recycle()
        val gray = ByteArray(640 * 480)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xff
            val g = (p ushr 8) and 0xff
            val b = p and 0xff
            gray[i] = ((r * 77 + g * 150 + b * 29) ushr 8).toByte()
        }
        val packed = SpTflite.features(xfeatHandle, gray) ?: return null
        if (packed.isEmpty()) return null
        val n = packed[0].toInt()
        if (n < 40 || packed.size < 1 + n * 2 + n * 64) return null
        val kp = packed.copyOfRange(1, 1 + n * 2)
        val desc = packed.copyOfRange(1 + n * 2, 1 + n * 2 + n * 64)
        return Feat(kp, desc, n)
    }

    /** Visual loop measurement: rotation chosen against ARCore, translation direction signed by it. */
    private fun visual(a: Kf, b: Kf, fa: Feat, fb: Feat, arcRot: DoubleArray, arcDir: DoubleArray): Pair<DoubleArray, DoubleArray>? {
        val p1 = ArrayList<Double>(); val p2 = ArrayList<Double>()
        val lg = try { LoopMatcher.match(fa.kp, fa.desc, fb.kp, fb.desc) } catch (_: Exception) { null }
        // nativeVioLightGlueMatch returns [pairCount, a0, b0, a1, b1, ...].
        val lgCount = if (lg != null && lg.isNotEmpty()) minOf(lg[0], (lg.size - 1) / 2) else 0
        if (lg != null && lgCount >= 4) {
            for (k in 0 until lgCount) {
                val ia = lg[1 + k * 2]; val ib = lg[2 + k * 2]
                if (ia in 0 until fa.n && ib in 0 until fb.n) {
                    p1.add((fa.kp[ia * 2] - a.K[2]) / a.K[0]); p1.add((fa.kp[ia * 2 + 1] - a.K[3]) / a.K[1])
                    p2.add((fb.kp[ib * 2] - b.K[2]) / b.K[0]); p2.add((fb.kp[ib * 2 + 1] - b.K[3]) / b.K[1])
                }
            }
        } else {
            for (i in 0 until fa.n) {
                var best = -1; var bs = -2f; var second = -2f
                val oa = i * 64
                for (j in 0 until fb.n) {
                    var s = 0f
                    val ob = j * 64
                    for (c in 0 until 64) s += fa.desc[oa + c] * fb.desc[ob + c]
                    if (s > bs) { second = bs; bs = s; best = j } else if (s > second) second = s
                }
                if (best >= 0 && bs > 0.72f && second < bs - 0.05f) {
                    p1.add((fa.kp[i * 2] - a.K[2]) / a.K[0]); p1.add((fa.kp[i * 2 + 1] - a.K[3]) / a.K[1])
                    p2.add((fb.kp[best * 2] - b.K[2]) / b.K[0]); p2.add((fb.kp[best * 2 + 1] - b.K[3]) / b.K[1])
                }
            }
        }
        val n = p1.size / 2
        if (n < 30) return null
        return try {
            if (!org.opencv.android.OpenCVLoader.initLocal()) return null
            if (pnpInliers(p1, p2, a, b) < 8) return null
            val m1 = org.opencv.core.MatOfPoint2f()
            val m2 = org.opencv.core.MatOfPoint2f()
            val l1 = ArrayList<org.opencv.core.Point>(); val l2 = ArrayList<org.opencv.core.Point>()
            for (i in 0 until n) {
                l1.add(org.opencv.core.Point(p1[i * 2], p1[i * 2 + 1]))
                l2.add(org.opencv.core.Point(p2[i * 2], p2[i * 2 + 1]))
            }
            m1.fromList(l1); m2.fromList(l2)
            val eye = org.opencv.core.Mat.eye(3, 3, org.opencv.core.CvType.CV_64F)
            val E = org.opencv.calib3d.Calib3d.findEssentialMat(m1, m2, eye, org.opencv.calib3d.Calib3d.RANSAC, 0.999, 2.0 / 1000)
            if (E.empty()) null else {
                val r1 = org.opencv.core.Mat(); val r2 = org.opencv.core.Mat(); val tv = org.opencv.core.Mat()
                org.opencv.calib3d.Calib3d.decomposeEssentialMat(E, r1, r2, tv)
                val a1 = DoubleArray(9); r1.get(0, 0, a1)
                val a2 = DoubleArray(9); r2.get(0, 0, a2)
                val best = if (rotDist(a1, arcRot) <= rotDist(a2, arcRot)) a1 else a2
                val t = DoubleArray(3); tv.get(0, 0, t)
                val dir = normalize3(t)
                val sgn = if (dot3(dir, arcDir) < 0) -1.0 else 1.0
                Pair(best, doubleArrayOf(dir[0] * sgn, dir[1] * sgn, dir[2] * sgn))
            }
        } catch (ex: Exception) {
            null
        }
    }

    /**
     * PnP RANSAC verification of a candidate loop. The two keyframe poses give
     * a prior for triangulating the matched pixels; solvePnPRansac then checks
     * that the current keyframe's 2D points are consistent with those 3D points.
     * This is the SuperVINS loop-verification step, with normalized image
     * coordinates so the camera matrix is identity.
     */
    private fun pnpInliers(p1: ArrayList<Double>, p2: ArrayList<Double>, a: Kf, b: Kf): Int {
        val n = p1.size / 2
        if (n < 8) return 0
        try {
            fun projection(W: DoubleArray, C: DoubleArray): org.opencv.core.Mat {
                // W is camera->world; projection matrix needs world->camera R = W^T and t = -R * C.
                val R = transpose3(W)
                val m = org.opencv.core.Mat(3, 4, org.opencv.core.CvType.CV_64F)
                val t = mulVec3(R, C)
                m.put(0, 0, R[0], R[1], R[2], -t[0],
                                R[3], R[4], R[5], -t[1],
                                R[6], R[7], R[8], -t[2])
                return m
            }
            val pa = projection(a.W, a.C)
            val pb = projection(b.W, b.C)
            val qa = org.opencv.core.MatOfPoint2f()
            val qb = org.opencv.core.MatOfPoint2f()
            val la = ArrayList<org.opencv.core.Point>()
            val lb = ArrayList<org.opencv.core.Point>()
            for (i in 0 until n) {
                la.add(org.opencv.core.Point(p1[i * 2], p1[i * 2 + 1]))
                lb.add(org.opencv.core.Point(p2[i * 2], p2[i * 2 + 1]))
            }
            qa.fromList(la); qb.fromList(lb)
            val x4 = org.opencv.core.Mat()
            org.opencv.calib3d.Calib3d.triangulatePoints(pa, pb, qa, qb, x4)
            val raw = DoubleArray(4 * n)
            x4.get(0, 0, raw)
            val obj = org.opencv.core.MatOfPoint3f()
            val p3 = Array(n) { i ->
                val w = raw[3 * n + i]
                if (kotlin.math.abs(w) < 1e-9) org.opencv.core.Point3(0.0, 0.0, 0.0)
                else org.opencv.core.Point3(raw[i] / w, raw[n + i] / w, raw[2 * n + i] / w)
            }
            obj.fromArray(*p3)
            val k = org.opencv.core.Mat.eye(3, 3, org.opencv.core.CvType.CV_64F)
            val dist = org.opencv.core.MatOfDouble()
            val rvec = org.opencv.core.Mat()
            val tvec = org.opencv.core.Mat()
            val inl = org.opencv.core.Mat()
            val ok = org.opencv.calib3d.Calib3d.solvePnPRansac(
                obj, qb, k, dist, rvec, tvec, false, 100, 0.01f, 0.99, inl,
            )
            return if (ok) inl.rows() else 0
        } catch (_: Exception) {
            return 0
        }
    }

    private fun rotDist(a: DoubleArray, b: DoubleArray): Double = Math.acos(((a[0]*b[0]+a[1]*b[1]+a[2]*b[2]+a[3]*b[3]+a[4]*b[4]+a[5]*b[5]+a[6]*b[6]+a[7]*b[7]+a[8]*b[8]-1)/2).coerceIn(-1.0,1.0))

    // ── pose graph ──────────────────────────────────────────────────────────

    private fun residuals(x: DoubleArray, kfs: List<Kf>, edges: List<Edge>): DoubleArray {
        val n = kfs.size
        val rot = Array(n) { i -> mul3(expSO3(doubleArrayOf(x[i*6], x[i*6+1], x[i*6+2])), kfs[i].W) }
        val cen = Array(n) { i -> add3(kfs[i].C, doubleArrayOf(x[i*6+3], x[i*6+4], x[i*6+5])) }
        val out = ArrayList<Double>()
        for (k in 0 until 6) out.add(PRIOR_W * x[k])
        for (e in edges) {
            val wi = rot[e.a]; val wj = rot[e.b]
            val ci = cen[e.a]; val cj = cen[e.b]
            val mc = mul3T(wj, wi)
            val rres = logSO3(mul3TM(e.rot, mc))
            for (k in 0 until 3) out.add(ROT_SCALE * e.rotW * rres[k])
            val d = mulVec3(transpose3(wi), sub3(cj, ci))
            for (k in 0 until 3) out.add(e.transW * (d[k] - e.scale * e.dir[k]))
        }
        return out.toDoubleArray()
    }

    /** 15 values per keyframe: rotation 3x3, camera centre, translation. */
    private fun corrections(x: DoubleArray, kfs: List<Kf>): Array<DoubleArray> =
        Array(kfs.size) { i ->
            val w = doubleArrayOf(x[i*6], x[i*6+1], x[i*6+2])
            val r = expSO3(w)
            doubleArrayOf(
                r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8],
                kfs[i].C[0], kfs[i].C[1], kfs[i].C[2],
                x[i*6+3], x[i*6+4], x[i*6+5],
            )
        }

    private fun correctionAt(corr: Array<DoubleArray>, frame: Int, kfs: List<Kf>): DoubleArray? {
        if (corr.isEmpty()) return null
        var lo = 0
        for (i in kfs.indices) if (kfs[i].frame <= frame) lo = i
        val hi = minOf(lo + 1, kfs.size - 1)
        val f0 = kfs[lo].frame.toDouble(); val f1 = kfs[hi].frame.toDouble()
        val t = if (f1 > f0) ((frame - f0) / (f1 - f0)).coerceIn(0.0, 1.0) else 0.0
        val a = corr[lo]; val b = corr[hi]
        // interpolate rotation through log/exp of the relative rotation
        val ra = doubleArrayOf(a[0],a[1],a[2],a[3],a[4],a[5],a[6],a[7],a[8])
        val rb = doubleArrayOf(b[0],b[1],b[2],b[3],b[4],b[5],b[6],b[7],b[8])
        val d = logSO3(mul3(rb, transpose3(ra)))
        val r = mul3(expSO3(doubleArrayOf(d[0]*t, d[1]*t, d[2]*t)), ra)
        return doubleArrayOf(
            r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8],
            a[9] + (b[9]-a[9])*t, a[10] + (b[10]-a[10])*t, a[11] + (b[11]-a[11])*t,
            a[12] + (b[12]-a[12])*t, a[13] + (b[13]-a[13])*t, a[14] + (b[14]-a[14])*t,
        )
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }
        val rhs = b.copyOf()
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r
            if (Math.abs(m[piv][col]) < 1e-14) return null
            if (piv != col) { val t = m[piv]; m[piv] = m[col]; m[col] = t; val s = rhs[piv]; rhs[piv] = rhs[col]; rhs[col] = s }
            val d = m[col][col]
            for (r in col + 1 until n) {
                val f = m[r][col] / d
                if (f == 0.0) continue
                for (c in col until n) m[r][c] -= f * m[col][c]
                rhs[r] -= f * rhs[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = rhs[r]
            for (c in r + 1 until n) s -= m[r][c] * x[c]
            x[r] = s / m[r][r]
        }
        return x
    }

    // ── 3D helpers ──────────────────────────────────────────────────────────

    private fun expSO3(w: DoubleArray): DoubleArray {
        val th = Math.sqrt(w[0]*w[0] + w[1]*w[1] + w[2]*w[2])
        if (th < 1e-12) return doubleArrayOf(1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0)
        val x = w[0]/th; val y = w[1]/th; val z = w[2]/th
        val c = Math.cos(th); val s = Math.sin(th); val t = 1 - c
        return doubleArrayOf(
            c + x*x*t, x*y*t - z*s, x*z*t + y*s,
            y*x*t + z*s, c + y*y*t, y*z*t - x*s,
            z*x*t - y*s, z*y*t + x*s, c + z*z*t,
        )
    }

    private fun logSO3(m: DoubleArray): DoubleArray {
        val th = Math.acos(((m[0]+m[4]+m[8]-1)/2).coerceIn(-1.0,1.0))
        if (th < 1e-9) return doubleArrayOf(0.0,0.0,0.0)
        val s = Math.sin(th)
        return doubleArrayOf((m[7]-m[5])/(2*s)*th, (m[2]-m[6])/(2*s)*th, (m[3]-m[1])/(2*s)*th)
    }

    private fun rotAngle(c: DoubleArray): Double = Math.acos(((c[0]+c[4]+c[8]-1)/2).coerceIn(-1.0,1.0))

    private fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            var v = 0.0
            for (k in 0 until 3) v += a[r*3+k] * b[k*3+c]
            o[r*3+c] = v
        }
        return o
    }
    private fun mul3TM(a: DoubleArray, b: DoubleArray): DoubleArray = mul3(transpose3(a), b)
    private fun mul3T(a: DoubleArray, b: DoubleArray): DoubleArray = mul3(transpose3(a), b)
    private fun transpose3(a: DoubleArray): DoubleArray = doubleArrayOf(a[0],a[3],a[6], a[1],a[4],a[7], a[2],a[5],a[8])
    private fun mulVec3(m: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
        m[0]*v[0]+m[1]*v[1]+m[2]*v[2], m[3]*v[0]+m[4]*v[1]+m[5]*v[2], m[6]*v[0]+m[7]*v[1]+m[8]*v[2])
    private fun add3(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0]+b[0], a[1]+b[1], a[2]+b[2])
    private fun sub3(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0]-b[0], a[1]-b[1], a[2]-b[2])
    private fun dot3(a: DoubleArray, b: DoubleArray) = a[0]*b[0]+a[1]*b[1]+a[2]*b[2]
    private fun len3(a: DoubleArray) = Math.sqrt(dot3(a, a))
    private fun normalize3(a: DoubleArray): DoubleArray {
        val l = len3(a)
        return if (l < 1e-12) doubleArrayOf(0.0,0.0,1.0) else doubleArrayOf(a[0]/l, a[1]/l, a[2]/l)
    }
    private fun norm(a: DoubleArray): Double {
        var s = 0.0
        for (v in a) s += v*v
        return Math.sqrt(s)
    }

    // ── files ───────────────────────────────────────────────────────────────

    private fun find(dir: File, needle: String): File? {
        for (f in dir.listFiles() ?: return null) {
            if (f.isDirectory) find(f, needle)?.let { return it } else if (f.name.contains(needle) && f.name.endsWith(".raw")) return f
        }
        return null
    }

    private fun readHalf(file: File): FloatArray {
        val bytes = file.readBytes()
        val n = bytes.size / 2
        val out = FloatArray(n)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) out[i] = halfToFloat(bb.short)
        return out
    }

    private fun halfToFloat(h: Short): Float {
        val bits = h.toInt() and 0xffff
        val sign = (bits and 0x8000) shl 16
        val exp = (bits ushr 10) and 0x1f
        val mant = bits and 0x3ff
        return when {
            exp == 0 -> java.lang.Float.intBitsToFloat(sign or (mant shl 13))
            exp == 0x1f -> java.lang.Float.intBitsToFloat(sign or 0x7f800000 or (mant shl 13))
            else -> java.lang.Float.intBitsToFloat(sign or ((exp - 15 + 127) shl 23) or (mant shl 13))
        }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
