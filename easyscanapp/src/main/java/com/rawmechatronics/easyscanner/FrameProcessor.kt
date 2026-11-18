package com.rawmechatronics.easyscanner

import android.media.ImageReader

import java.nio.ByteBuffer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * The processing thread. Owns the native engine handle; camera frames,
 * configuration and commands all run here, in order, so the engine needs no
 * locks. Results go to the UI through a [TripleBuffer]: the camera never
 * waits for drawing and drawing never waits for the camera.
 */
class FrameProcessor(filesDir: String, private val listener: Listener) : ImageReader.OnImageAvailableListener {

    interface Listener {
        /** A new result is in [results] (called on the processing thread). */
        fun onResult(r: FrameResult)

        /** Status lines changed (JSON from the core), on the main thread. */
        fun onMessages(json: String)

        /** The core wants a photo keyframe now (called on the processing thread). */
        fun onPhotoDue()

        fun onError(msg: String)
    }

    private val thread = HandlerThread("es-proc", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    val results = TripleBuffer { FrameResult() }
    /** Optional offline tracker development recorder (Settings -> Development). */
    @Volatile var trackerRecorder: TrackerRecorder? = null
    @Volatile var vio: VioRunner? = null
    /** When true the engine takes its camera pose from the VIO instead of the board. */
    @Volatile var useVioPose = false
    /** true once the board anchor is verified and the engine AR lane is active. */
    @Volatile var arLaneReady = false
    /** frames the engine actually finished (used to gate calibration captures) */
    @Volatile var framesProcessed = 0

    private val vioPose = DoubleArray(16)
    private val vioK = DoubleArray(11)
    private var handle = 0L
    private var loggedW = 0
    private var loggedH = 0

    init {
        handler.post {
            try {
                handle = NativeLib.nativeCreate(filesDir)
            } catch (e: RuntimeException) {
                report("native core failed to start: ${e.message}")
            }
        }
    }

    fun configure(json: String) = handler.post {
        if (handle == 0L) return@post
        try {
            NativeLib.nativeConfigure(handle, json)
            pullMessages()
        } catch (e: RuntimeException) {
            report(e.message ?: "configure failed")
        }
    }

    fun command(cmd: Cmd, arg: String? = null, done: (String) -> Unit = {}) = handler.post {
        if (handle == 0L) return@post
        val msg = try {
            NativeLib.nativeCommand(handle, cmd.code, arg)
        } catch (e: RuntimeException) {
            e.message ?: "command failed"
        }
        pullMessages()
        main.post { done(msg) }
    }

    override fun onImageAvailable(reader: ImageReader) {
        val img = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null // reader closed by a resolution switch
        } ?: return
        try {
            if (handle == 0L) return
            val p = img.planes
            if (img.width != loggedW || img.height != loggedH) {
                loggedW = img.width
                loggedH = img.height
                Log.i(TAG, "frames ${img.width}x${img.height}: y stride ${p[0].rowStride}, " +
                    "uv stride ${p[1].rowStride} pixel stride ${p[1].pixelStride}")
            }
            val out = results.writeBuffer()
            val t0 = System.nanoTime()
            var arPose: DoubleArray? = null
            var vioValid = false
            val vr = vio
            if (vr != null && vr.tracking) {
                try {
                    val yDup = p[0].buffer.duplicate()
                    yDup.rewind()
                    val yBytes = ByteArray(yDup.remaining())
                    yDup.get(yBytes)
                    val ts = img.timestamp * 1e-9
                    val inited = vr.submitFrame(yBytes, p[0].rowStride, img.width, img.height, ts)
                    if (inited) vioValid = vr.cameraPose(vioPose)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "VIO frame failed", e)
                }
            }
            if (vr !== lastVio) {
                lastVio = vr
                if (arLane) {
                    arLane = false
                    arLaneReady = false
                    useVioPose = false
                    sendArMode(false)
                }
                anchorSet = false
                anchorGood = 0
            }
            if (arLane) {
                if (vioValid) {
                    arPose = buildArPose(vioPose)
                    vr?.poseApplied = true
                    vr?.lastPoseAppliedNs = System.nanoTime()
                }
                if (arPose == null) {
                    val now = System.nanoTime()
                    if (now - lastArSkipLogNs > 2_000_000_000L) {
                        lastArSkipLogNs = now
                        Log.w(TAG, "AR lane: no valid VIO pose, frame skipped")
                    }
                    return
                }
            }
            var n = if (arPose != null) NativeLib.nativeProcessAr(
                handle,
                p[0].buffer, p[0].rowStride,
                p[1].buffer, p[2].buffer, p[1].rowStride, p[1].pixelStride,
                img.width, img.height, img.timestamp, arPose, arK, 1.0, out.data,
            ) else NativeLib.nativeProcess(
                handle,
                p[0].buffer, p[0].rowStride,
                p[1].buffer, p[2].buffer, p[1].rowStride, p[1].pixelStride,
                img.width, img.height, img.timestamp, out.data,
            )
            out.procMs = (System.nanoTime() - t0) / 1e6
            if (n < 0) {
                // the frame was processed but its layers did not fit: grow for next time
                out.data = FloatArray(-n * 2)
                n = 0
            }
            out.length = n
            out.timestampNs = img.timestamp
            if (n == 0) return
            updateAnchor(out, vioValid, img.width, img.height)
            vio?.let { v ->
                val state = out[FrameResult.H.LASER_STATE].toInt()
                if (state == 2) {
                    v.setLaserLine(out[FrameResult.H.LASER_MEAN_X], out[FrameResult.H.LASER_MEAN_Y],
                        out[FrameResult.H.LASER_ANGLE], state, img.width, img.height)
                } else if (state == 0) {
                    v.setLaserLine(0f, 0f, 0f, 0, img.width, img.height)
                }
            }
            vio?.let { v ->
                var px: FloatArray? = null
                out.forEachLayer { kind, stride, count, offset ->
                    if (kind == FrameResult.Layer.LASER_RAW && stride == 2 && count > 0) {
                        px = FloatArray(count * 2)
                        System.arraycopy(out.data, offset, px!!, 0, count * 2)
                    }
                }
                val lp = px
                if (lp != null) v.setLaserPixels(lp, img.width, img.height)
            }
            val ev = out.events()
            val vioPoseToRecord = if (vio?.initialized == true) {
                val vp = DoubleArray(16)
                if (vio?.cameraPose(vp, false) == true) vp else null
            } else null
            trackerRecorder?.capture(img, out, out.procMs, vioPoseToRecord)
            results.publish()
            framesProcessed++
            if (ev and FrameResult.Ev.PHOTO_DUE != 0) listener.onPhotoDue()
            listener.onResult(out)
            if (ev and FrameResult.Ev.MESSAGES != 0) pullMessages()
        } catch (e: RuntimeException) {
            Log.e(TAG, "frame failed", e)
            report(e.message ?: "frame failed")
        } finally {
            img.close()
        }
    }

    // ---------------------------------------------------------------- VIO AR lane
    // The engine AR lane wants the pose board-frame -> camera in MILLIMETRES. VIO gives
    // world-frame -> camera in metres. A board detection supplies the one-time anchor
    // T_board<-world; it is validated against later board detections before AR frames are fed.
    private var arLane = false
    private var anchorSet = false
    private val anchor = DoubleArray(16)
    private var anchorGood = 0
    private val arK = DoubleArray(11)
    private var lastArSkipLogNs = 0L
    private var lastAnchorLogNs = 0L
    @Volatile private var lastVio: VioRunner? = null

    private fun sendArMode(on: Boolean) {
        if (handle == 0L) return
        try {
            NativeLib.nativeCommand(handle, Cmd.AR_MODE.code, if (on) "1" else "0")
            pullMessages()
        } catch (e: RuntimeException) {
            Log.w(TAG, "AR_MODE command failed", e)
        }
    }

    private fun updateArK(out: FrameResult, w: Int, h: Int) {
        if (!out.flag(FrameResult.H.K_OK)) return
        arK[0] = out[FrameResult.H.FX].toDouble()
        arK[1] = out[FrameResult.H.FY].toDouble()
        arK[2] = out[FrameResult.H.CX].toDouble()
        arK[3] = out[FrameResult.H.CY].toDouble()
        arK[4] = w.toDouble()
        arK[5] = h.toDouble()
        for (i in 6..10) arK[i] = 0.0
    }

    private fun updateAnchor(out: FrameResult, vioValid: Boolean, w: Int, h: Int) {
        updateArK(out, w, h)
        if (arLane) return
        if (!vioValid || !out.flag(FrameResult.H.DETECTED) || !out.flag(FrameResult.H.PLANE_OK) ||
            !out.flag(FrameResult.H.K_OK) || out.flag(FrameResult.H.SCAN_ON)) return
        val roll = Math.toRadians(out[FrameResult.H.ROLL].toDouble())
        val pitch = Math.toRadians(out[FrameResult.H.PITCH].toDouble())
        val yaw = Math.toRadians(out[FrameResult.H.YAW].toDouble())
        val rbc = AnchorMath.eulerToRbc(roll, pitch, yaw)
        val cb = doubleArrayOf(out[FrameResult.H.CAM_X].toDouble(), out[FrameResult.H.CAM_Y].toDouble(),
                               out[FrameResult.H.CAM_Z].toDouble())
        val rms = out[FrameResult.H.REPROJ_RMS].toDouble()
        if (!rms.isFinite() || rms > 2.0) return
        val m = AnchorMath.worldToCamMm(vioPose)
        if (anchorSet) {
            val err = AnchorMath.anchorError(anchor, m, rbc, cb)
            if (err == null) { anchorGood = 0; return }
            val posErr = err[0]
            val rotErr = err[1]
            if (posErr < 15.0 && rotErr < 2.0) {
                anchorGood++
                if (anchorGood >= 15) {
                    arLane = true
                    arLaneReady = true
                    useVioPose = true
                    sendArMode(true)
                    Log.i(TAG, "ANCHOR VERIFIED: pos err %.1f mm, rot err %.2f deg -> AR LANE ENABLED".format(posErr, rotErr))
                }
            } else {
                AnchorMath.anchorFromMeasurement(rbc, cb, m, anchor)
                anchorGood = 0
                val now = System.nanoTime()
                if (now - lastAnchorLogNs > 1_000_000_000L) {
                    lastAnchorLogNs = now
                    Log.i(TAG, "ANCHOR re-seeded: previous error %.1f mm / %.2f deg".format(posErr, rotErr))
                }
            }
        } else {
            AnchorMath.anchorFromMeasurement(rbc, cb, m, anchor)
            anchorSet = true
            anchorGood = 0
            Log.i(TAG, "ANCHOR candidate from board detection (reproj %.2f px)".format(rms))
        }
    }

    private fun buildArPose(worldToCam: DoubleArray): DoubleArray? {
        if (!anchorSet || !arK[0].isFinite() || arK[0] <= 0.0) return null
        return AnchorMath.boardToCamMm(anchor, AnchorMath.worldToCamMm(worldToCam))
    }

    private fun pullMessages() {
        val json = try {
            NativeLib.nativeMessages(handle)
        } catch (e: RuntimeException) {
            return
        }
        main.post { listener.onMessages(json) }
    }

    private fun report(msg: String) = main.post { listener.onError(msg) }

    /** Process an ARCore camera frame with its pose and intrinsics, on the processing thread. */
    fun processArFrame(
        y: ByteBuffer, yStride: Int,
        u: ByteBuffer, v: ByteBuffer, uvStride: Int, uvPixelStride: Int,
        width: Int, height: Int, timestampNs: Long,
        pose: DoubleArray, intrinsics: DoubleArray, upSign: Double,
    ) = handler.post {
        if (handle == 0L) return@post
        try {
            val out = results.writeBuffer()
            val t0 = System.nanoTime()
            var n = NativeLib.nativeProcessAr(
                handle, y, yStride, u, v, uvStride, uvPixelStride,
                width, height, timestampNs, pose, intrinsics, upSign, out.data,
            )
            out.procMs = (System.nanoTime() - t0) / 1e6
            if (n < 0) {
                out.data = FloatArray(-n * 2)
                n = 0
            }
            out.length = n
            out.timestampNs = timestampNs
            if (n == 0) return@post
            results.publish()
            listener.onResult(out)
            if (out.events() and FrameResult.Ev.MESSAGES != 0) pullMessages()
        } catch (e: RuntimeException) {
            Log.e(TAG, "AR frame failed", e)
            report(e.message ?: "AR frame failed")
        }
    }

    fun release() {
        handler.post {
            if (handle != 0L) NativeLib.nativeDestroy(handle)
            handle = 0L
        }
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "EasyScanner"
    }
}

/**
 * Small rigid-transform helpers for the board-anchored VIO lane. All transforms are row-major
 * 4x4; translations are millimetres. Conventions (verified against the engine):
 *  - engine pose P: X_cam = R_cb * X_board + t_cb  (board -> camera, mm);
 *  - board header: CAM_X/Y/Z = camera centre in board (mm),
 *    ROLL/PITCH/YAW = euler XYZ (deg) of R_board<-camera = Rz(yaw)Ry(pitch)Rx(roll).
 */
private object AnchorMath {
    fun eulerToRbc(roll: Double, pitch: Double, yaw: Double): DoubleArray {
        val cr = Math.cos(roll); val sr = Math.sin(roll)
        val cp = Math.cos(pitch); val sp = Math.sin(pitch)
        val cy = Math.cos(yaw); val sy = Math.sin(yaw)
        return doubleArrayOf(
            cy * cp, cy * sp * sr - sy * cr, cy * sp * cr + sy * sr,
            sy * cp, sy * sp * sr + cy * cr, sy * sp * cr - cy * sr,
            -sp, cp * sr, cp * cr
        )
    }

    fun worldToCamMm(pose16: DoubleArray): DoubleArray {
        val m = pose16.copyOf()
        m[3] *= 1000.0
        m[7] *= 1000.0
        m[11] *= 1000.0
        return m
    }

    private fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray {
        val c = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var v = 0.0
            for (k in 0..2) v += a[i * 3 + k] * b[k * 3 + j]
            c[i * 3 + j] = v
        }
        return c
    }

    private fun transpose3(a: DoubleArray): DoubleArray = doubleArrayOf(
        a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]
    )

    private fun mulVec3(a: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
        a[0] * v[0] + a[1] * v[1] + a[2] * v[2],
        a[3] * v[0] + a[4] * v[1] + a[5] * v[2],
        a[6] * v[0] + a[7] * v[1] + a[8] * v[2]
    )

    fun anchorFromMeasurement(rbc: DoubleArray, cb: DoubleArray, m: DoubleArray, out: DoubleArray) {
        val rCw = doubleArrayOf(m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10])
        val tCw = doubleArrayOf(m[3], m[7], m[11])
        val rBw = mul3(rbc, rCw)
        val tBw = mulVec3(rbc, tCw)
        for (i in 0..2) {
            out[i * 4 + 0] = rBw[i * 3 + 0]
            out[i * 4 + 1] = rBw[i * 3 + 1]
            out[i * 4 + 2] = rBw[i * 3 + 2]
            out[i * 4 + 3] = tBw[i] + cb[i]
        }
        out[12] = 0.0; out[13] = 0.0; out[14] = 0.0; out[15] = 1.0
    }

    fun boardToCamMm(anchor: DoubleArray, m: DoubleArray): DoubleArray {
        val rBw = doubleArrayOf(anchor[0], anchor[1], anchor[2], anchor[4], anchor[5], anchor[6], anchor[8], anchor[9], anchor[10])
        val tBw = doubleArrayOf(anchor[3], anchor[7], anchor[11])
        val rCw = doubleArrayOf(m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10])
        val tCw = doubleArrayOf(m[3], m[7], m[11])
        val rWc = transpose3(rCw)
        val rBc = mul3(rBw, rWc)
        val neg = doubleArrayOf(-tCw[0], -tCw[1], -tCw[2])
        val tmp = mulVec3(rWc, neg)
        val cb = mulVec3(rBw, tmp)
        cb[0] += tBw[0]; cb[1] += tBw[1]; cb[2] += tBw[2]
        val r = transpose3(rBc)
        val t = doubleArrayOf(
            -(r[0] * cb[0] + r[1] * cb[1] + r[2] * cb[2]),
            -(r[3] * cb[0] + r[4] * cb[1] + r[5] * cb[2]),
            -(r[6] * cb[0] + r[7] * cb[1] + r[8] * cb[2])
        )
        return doubleArrayOf(
            r[0], r[1], r[2], t[0],
            r[3], r[4], r[5], t[1],
            r[6], r[7], r[8], t[2],
            0.0, 0.0, 0.0, 1.0
        )
    }

    fun anchorError(anchor: DoubleArray, m: DoubleArray, rbcMeas: DoubleArray, cbMeas: DoubleArray): DoubleArray? {
        val pred = boardToCamMm(anchor, m)
        if (!pred.all { it.isFinite() }) return null
        val rPred = doubleArrayOf(pred[0], pred[1], pred[2], pred[4], pred[5], pred[6], pred[8], pred[9], pred[10])
        val tPred = doubleArrayOf(pred[3], pred[7], pred[11])
        val cPred = doubleArrayOf(
            -(rPred[0] * tPred[0] + rPred[3] * tPred[1] + rPred[6] * tPred[2]),
            -(rPred[1] * tPred[0] + rPred[4] * tPred[1] + rPred[7] * tPred[2]),
            -(rPred[2] * tPred[0] + rPred[5] * tPred[1] + rPred[8] * tPred[2])
        )
        val dp = doubleArrayOf(cPred[0] - cbMeas[0], cPred[1] - cbMeas[1], cPred[2] - cbMeas[2])
        val posErr = Math.sqrt(dp[0] * dp[0] + dp[1] * dp[1] + dp[2] * dp[2])
        val rMeas = transpose3(rbcMeas)
        val rel = mul3(transpose3(rPred), rMeas)
        val tr = rel[0] + rel[4] + rel[8]
        val rotErr = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, (tr - 1.0) / 2.0))))
        if (!posErr.isFinite() || !rotErr.isFinite()) return null
        return doubleArrayOf(posErr, rotErr)
    }
}
