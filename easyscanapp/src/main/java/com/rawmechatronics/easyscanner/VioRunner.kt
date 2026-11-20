package com.rawmechatronics.easyscanner

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.io.File
import kotlin.math.abs

/**
 * Feeds Camera2 YUV frames and IMU samples into the native OpenVINS VIO.
 * SuperPoint (TFLite + QNN delegate) supplies the feature points from the NPU.
 */
class VioRunner(private val ctx: Context) : SensorEventListener {
    private var vio = 0L
    private var sp = 0L
    private var sensors: SensorManager? = null
    private val accel = FloatArray(3)
    private val gyro = FloatArray(3)
    private val accelPrev = FloatArray(3)
    private var accelTs = 0.0
    private var accelPrevTs = -1.0
    private var gyroTs = 0.0
    private var haveAccel = false
    private var haveGyro = false
    private var lastImuTs = 0.0
    private var lastFrameTs = 0.0
    // IMU path diagnostics: a wrong rate/pairing here corrupts every propagation step.
    private var imuAccelEvents = 0L
    private var imuGyroEvents = 0L
    private var imuFed = 0L
    private var imuRejected = 0L
    private var lastAccelNorm = 0.0
    private var accelStaleSum = 0.0
    private var gyroStaleSum = 0.0
    private var staleMax = 0.0
    private var feedDtSum = 0.0
    private var lastFedTs = 0.0
    private var statSamples = 0L
    private var gyroSum = 0.0
    private var gyroMax = 0.0
    private var camOffSum = 0.0
    private var camOffMax = 0.0
    private var camOffN = 0L
    private val stPos = DoubleArray(3)
    private val stVel = DoubleArray(3)
    private val stBg = DoubleArray(3)
    private val stBa = DoubleArray(3)
    private val stQuat = DoubleArray(4)
    private var frameCount = 0

    @Volatile var initialized = false
        private set
    /** Set by release(): the native VIO/SuperPoint context is gone and must be recreated. */
    @Volatile var released = false
        private set
    // Per-stage timing of the VIO front-end (updated in submitFrame, logged by FrameProcessor).
    @Volatile var tYConvMs = 0.0
    @Volatile var tFeatMs = 0.0
    @Volatile var tMaskMs = 0.0
    @Volatile var tFeedMs = 0.0
    @Volatile var lastDetN = 0
    /** Start feeding VIO without the warm-up banner (calibration captures). */
    fun startTrackingSilent() { tracking = true }

    /** OpenVINS static-init stage: after stillness it asks the user to move. */
    fun initReadyToMove(): Boolean = vio != 0L && NativeLib.nativeVioInitReadyToMove(vio)

    /** Feed frames only while tracking is on (the UI has a Track button). */
    @Volatile var tracking = false
    /** Draw the SuperPoint features on the preview. */
    @Volatile var spOverlay = false
    /** Latest SuperPoint keypoints in the native 640x480 frame (x,y pairs). */
    @Volatile var lastKp: FloatArray? = null
    /** SuperPoint features submitted this frame (always updated, overlay or not). */
    @Volatile var lastFeatureCount = 0
    /** true when the engine actually received a VIO pose this frame (real capture, not just init). */
    @Volatile var poseApplied = false
    /** the 5 s warm-up completed; nothing (scan or calibration) starts before this */
    @Volatile var warmupDone = false
    @Volatile var lastPoseAppliedNs = 0L

    fun poseIsLive(): Boolean = poseApplied && System.nanoTime() - lastPoseAppliedNs < 1_500_000_000L

    /** Current filter speed (m/s); large values mean the filter has lost track/diverged. */
    fun velocityNorm(): Double {
        if (!initialized) return 0.0
        val x = stVel[0]; val y = stVel[1]; val z = stVel[2]
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return Double.POSITIVE_INFINITY
        return kotlin.math.sqrt(x * x + y * y + z * z)
    }
    // drawn by the UI thread while the VIO thread appends: must be thread-safe
    private val trajectory = java.util.concurrent.CopyOnWriteArrayList<FloatArray>()
    @Volatile private var laserPx = FloatArray(0)
    private var laserW = 0
    private var laserH = 0
    // Laser stripe exclusion band: line through (cx,cy) with unit normal (nx,ny), half width hw,
    // all in 640x480 feature coordinates. The stripe is fixed to the camera, so its points do not
    // move with the scene and would corrupt the filter if tracked.
    @Volatile private var bandOn = false
    private var bandCx = 0f
    private var bandCy = 0f
    private var bandNx = 0f
    private var bandNy = 1f
    private var bandHw = 0f

    fun start(handler: android.os.Handler) {
        synchronized(PROCESS_LOCK) {
            ACTIVE?.let { if (it !== this) it.releaseLocked() }
            ACTIVE = this
            released = false
            startLocked(handler)
        }
    }

    private fun startLocked(handler: android.os.Handler) {
        val dir = File(ctx.filesDir, "openvins-config")
        dir.mkdirs()
        for (name in CONFIGS) {
            val out = File(dir, name)
            // estimator_config is app-owned: always take the bundled version so new defaults reach
            // existing installs. The Kalibr camera-IMU/IMU files are on-phone calibration results
            // and are only replaced when missing or malformed.
            if (name != "estimator_config.yaml" && out.exists() && out.length() > 0) {
                val bad = try {
                    val text = out.readText()
                    Regex("\\d,\\d").containsMatchIn(text) || (!text.contains("T_cam_imu") && name.contains("imucam"))
                } catch (e: Exception) {
                    true
                }
                if (!bad) continue
                Log.w(TAG, "reset invalid calibration yaml: " + name)
                out.delete()
            }
            try {
                ctx.assets.open("openvins/" + name).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "config copy failed: " + name, e)
            }
        }
        syncVioIntrinsics(dir)
        vio = NativeLib.nativeVioCreate(dir.absolutePath)
        if (vio == 0L) {
            Log.e(TAG, "VIO create failed (config/parse error) - marker-free pose disabled")
            return
        }
        // Prefer the int8-quantized SuperPoint (QNN HTP int8 path); fall back to float16/float32.
        var spHandle = 0L
        for (name in arrayOf("superpoint_single.tflite", "superpoint.tflite")) {
            val model = File(ctx.filesDir, name)
            if (!model.exists() || model.length() < 1000) {
                try {
                    ctx.assets.open(name).use { input -> model.outputStream().use { input.copyTo(it) } }
                } catch (e: Exception) {
                    Log.w(TAG, "model copy failed: " + name, e)
                }
            }
            if (!model.exists() || model.length() < 1000) continue
            val h = SpTflite.create(ctx.applicationInfo.nativeLibraryDir, model.absolutePath, 0.0, 0.0, 0.0, 0.0)
            if (h != 0L && !(h >= -4L && h <= -1L)) {
                spHandle = h
                Log.i(TAG, "SuperPoint model: " + name + " (" + model.length() + " bytes)")
                break
            }
            Log.w(TAG, "model create failed: " + name + " (" + h + ")")
        }
        sp = spHandle
        // Handles are native pointers (Android tags the top byte, so they can look negative);
        // only 0 and the create error codes -1..-4 mean failure.
        if (sp == 0L || (sp >= -4L && sp <= -1L)) {
            Log.e(TAG, "SuperPoint engine create failed (" + sp + ") - marker-free pose disabled")
            sp = 0L
        }
        // NPU matmul used by the tracker to re-acquire lost tracks (hybrid tracker)
        try {
            val mm = File(ctx.filesDir, "superpoint_matmul.tflite")
            if (!mm.exists() || mm.length() < 100) {
                ctx.assets.open("superpoint_matmul.tflite").use { input -> mm.outputStream().use { input.copyTo(it) } }
            }
            val ok = SpTflite.matmulInit(ctx.applicationInfo.nativeLibraryDir, mm.absolutePath)
            Log.i(TAG, "OVMM init=" + ok)
        } catch (e: Exception) {
            Log.w(TAG, "OVMM init failed", e)
        }
        // flood experiment: optional caps from files/flood.txt (max_tracks=, min_dist=, max_features=)
        try {
            val f = java.io.File(ctx.filesDir, "flood.txt")
            if (f.exists()) {
                var mt = 200
                var md = 10.0f
                var mf = 300
                var rq = 1
                for (line in f.readLines()) {
                    val p = line.split('=')
                    if (p.size == 2) {
                        when (p[0].trim()) {
                            "max_tracks" -> mt = p[1].trim().toIntOrNull() ?: mt
                            "min_dist" -> md = p[1].trim().toFloatOrNull() ?: md
                            "max_features" -> mf = p[1].trim().toIntOrNull() ?: mf
                            "reacq" -> rq = p[1].trim().toIntOrNull() ?: rq
                        }
                    }
                }
                NativeLib.nativeVioSetCaps(mt, md)
                SpTflite.setFeatureCap(mf)
                NativeLib.nativeVioSetReacq(rq)
                Log.i(TAG, "VIO flood caps: tracks=" + mt + " min_dist=" + md + " features=" + mf + " reacq=" + rq)
            }
        } catch (e: Exception) {
            Log.w(TAG, "flood.txt read failed", e)
        }
        sensors = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensors?.let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                ?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
            sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                ?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
        }
        Log.i(TAG, "VIO started vio=" + vio + " sp=" + sp)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val type = event.sensor.type
        val evTs = event.timestamp * 1e-9
        if (type == Sensor.TYPE_ACCELEROMETER) {
            imuAccelEvents++
            accelTs = evTs
            accel[0] = event.values[0]
            accel[1] = event.values[1]
            accel[2] = event.values[2]
            haveAccel = true
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            imuGyroEvents++
            gyroTs = evTs
            gyro[0] = event.values[0]
            gyro[1] = event.values[1]
            gyro[2] = event.values[2]
            haveGyro = true
        } else {
            return
        }
        // Reference implementation (OpenVINS Android): feed one combined sample at the completing
        // event's timestamp, then wait for a fresh pair. One sample per sensor event pair.
        if (vio == 0L || !haveAccel || !haveGyro) return
        val ts = event.timestamp * 1e-9
        if (!(ts > lastImuTs)) { imuRejected++; return }
        lastImuTs = ts
        lastAccelNorm = Math.sqrt((accel[0] * accel[0] + accel[1] * accel[1] + accel[2] * accel[2]).toDouble())
        imuFed++
        val aStale = Math.abs(ts - accelTs)
        val gStale = Math.abs(ts - gyroTs)
        accelStaleSum += aStale
        gyroStaleSum += gStale
        if (aStale > staleMax) staleMax = aStale
        if (gStale > staleMax) staleMax = gStale
        if (lastFedTs > 0.0) feedDtSum += (ts - lastFedTs)
        lastFedTs = ts
        statSamples++
        val gw = Math.sqrt((gyro[0]*gyro[0] + gyro[1]*gyro[1] + gyro[2]*gyro[2]).toDouble())
        gyroSum += gw
        if (gw > gyroMax) gyroMax = gw
        NativeLib.nativeVioFeedImu(vio, accel[0], accel[1], accel[2], gyro[0], gyro[1], gyro[2], ts)
        haveAccel = false
        haveGyro = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Feed one Camera2 Y plane; returns true once VIO is initialised. */
    fun submitFrame(y: ByteArray, yStride: Int, width: Int, height: Int, timestampSec: Double): Boolean {
        if (!tracking) { lastKp = null; return initialized }
        if (vio == 0L || sp == 0L) return false
        if (!(timestampSec > lastFrameTs)) return initialized  // drop duplicate/reversed camera frames
        lastFrameTs = timestampSec
        tYConvMs = 0.0; tFeatMs = 0.0; tMaskMs = 0.0; tFeedMs = 0.0
        val tStage0 = System.nanoTime()
        if (lastImuTs > 0.0) {
            val off = timestampSec - lastImuTs
            camOffSum += Math.abs(off)
            if (Math.abs(off) > camOffMax) camOffMax = Math.abs(off)
            camOffN++
        }
        val gray = ByteArray(GW * GH)
        for (yy in 0 until GH) {
            val sy = yy * height / GH
            val row = sy * yStride
            for (xx in 0 until GW) {
                val sx = xx * width / GW
                gray[yy * GW + xx] = y[row + sx]
            }
        }
        tYConvMs = (System.nanoTime() - tStage0) / 1e6
        val tFeat0 = System.nanoTime()
        val feat = SpTflite.features(sp, gray)
        tFeatMs = (System.nanoTime() - tFeat0) / 1e6
        if (feat == null) { if (frameCount % 30 == 0) Log.w(TAG, "features() null sp=" + sp); return false }
        if (feat.isEmpty()) return false
        val n = feat[0].toInt()
        lastDetN = n
        if (n <= 0) return false
        val tMask0 = System.nanoTime()
        // drop features on the laser stripe (they move with the line and corrupt tracks)
        val lw = laserW
        val lh = laserH
        val lpx = laserPx
        val keep = BooleanArray(n)
        var nKeep = 0
        for (i in 0 until n) {
            val x = feat[1 + i * 2]
            val y = feat[2 + i * 2]
            var ok = true
            if (bandOn) {
                val d = bandNx * (x - bandCx) + bandNy * (y - bandCy)
                if (kotlin.math.abs(d) < bandHw) ok = false
            } else if (lpx.isNotEmpty() && lw > 0 && lh > 0) {
                val sx = 640f / lw
                val sy = 480f / lh
                val r2 = 14f * 14f
                var p = 0
                while (p + 1 < lpx.size) {
                    val dx = x - lpx[p] * sx
                    val dy = y - lpx[p + 1] * sy
                    if (dx * dx + dy * dy < r2) { ok = false; break }
                    p += 2
                }
            }
            keep[i] = ok
            if (ok) nKeep++
        }
        if (nKeep < 20) {
            if (frameCount % 30 == 0) Log.w(TAG, "too few features after stripe mask: " + nKeep)
            return initialized
        }
        val kp = FloatArray(nKeep * 2)
        val desc = FloatArray(nKeep * 256)
        var w = 0
        for (i in 0 until n) {
            if (!keep[i]) continue
            kp[w * 2] = feat[1 + i * 2]
            kp[w * 2 + 1] = feat[2 + i * 2]
            System.arraycopy(feat, 1 + n * 2 + i * 256, desc, w * 256, 256)
            w++
        }
        val nn = w
        tMaskMs = (System.nanoTime() - tMask0) / 1e6
        lastFeatureCount = nKeep
        lastKp = if (spOverlay) kp else null
        val tFeed0 = System.nanoTime()
        NativeLib.nativeVioSetFeatures(vio, 0, timestampSec, kp, desc, nn)
        NativeLib.nativeVioFeedFrame(vio, gray, GW, GH, timestampSec)
        val now = NativeLib.nativeVioInitialized(vio)
        if (now != initialized) {
            initialized = now
            Log.i(TAG, "VIO initialized=" + now)
        }
        tFeedMs = (System.nanoTime() - tFeed0) / 1e6
        frameCount++
        if (frameCount % 60 == 0) {
            val ok = NativeLib.nativeVioGetState(vio, stPos, stVel, stBg, stBa, stQuat)
            val ns = statSamples.coerceAtLeast(1).toDouble()
            Log.i(TAG, "VIOSTAGE ok=" + ok +
                " p=%.3f,%.3f,%.3f v=%.3f,%.3f,%.3f |v|=%.3f bg=%.4f,%.4f,%.4f ba=%.4f,%.4f,%.4f".format(
                    stPos[0], stPos[1], stPos[2], stVel[0], stVel[1], stVel[2],
                    Math.sqrt(stVel[0]*stVel[0]+stVel[1]*stVel[1]+stVel[2]*stVel[2]),
                    stBg[0], stBg[1], stBg[2], stBa[0], stBa[1], stBa[2]) +
                " evA=" + imuAccelEvents + " evG=" + imuGyroEvents + " fed=" + imuFed +
                " rej=" + imuRejected + " dt=%.2fms aStale=%.2fms gStale=%.2fms staleMax=%.2fms gyro=%.1f/%.1fdeg camOff=%.1f/%.1fms".format(
                    feedDtSum / ns * 1000.0, accelStaleSum / ns * 1000.0, gyroStaleSum / ns * 1000.0, staleMax * 1000.0,
                    Math.toDegrees(gyroSum / ns), Math.toDegrees(gyroMax),
                    if (camOffN > 0) camOffSum / camOffN * 1000.0 else 0.0,
                    camOffMax * 1000.0))
            accelStaleSum = 0.0; gyroStaleSum = 0.0; staleMax = 0.0
            feedDtSum = 0.0; lastFedTs = 0.0; statSamples = 0; imuRejected = 0
            gyroSum = 0.0; gyroMax = 0.0; camOffSum = 0.0; camOffMax = 0.0; camOffN = 0
        }
        if (frameCount % 60 == 0) Log.i(TAG, "VIO frames=" + frameCount + " init=" + initialized + " features=" + n +
                " imu: accelEv=" + imuAccelEvents + " gyroEv=" + imuGyroEvents + " fed=" + imuFed +
                " reversed=" + imuRejected + " |a|=" + String.format(java.util.Locale.US, "%.2f", lastAccelNorm))
        return initialized
    }

    /**
     * The VIO front-end consumes the same camera as the engine, downscaled to 640x480, so its
     * pinhole+radtan model must be the engine's calibrated live_intrinsics.json scaled by
     * 640 / calibrated_width. The on-device Kalibr file is only an initial/legacy source for
     * T_cam_imu and timeshift; using a stale intrinsics block here biases every visual residual
     * and shows up as accelerometer-bias drift.
     */
    private fun syncVioIntrinsics(dir: File) {
        try {
            val live = File(ctx.filesDir, "live_intrinsics.json")
            if (!live.exists() || live.length() < 20) return
            val o = org.json.JSONObject(live.readText())
            if (!o.optBoolean("calibrated", false)) return
            val w = o.optDouble("width", 0.0)
            val h = o.optDouble("height", 0.0)
            if (w <= 1.0 || h <= 1.0) return
            val s = 640.0 / w
            val fx = o.optDouble("fx", 0.0) * s
            val fy = o.optDouble("fy", 0.0) * s
            val cx = o.optDouble("cx", 0.0) * s
            val cy = o.optDouble("cy", 0.0) * s
            if (!fx.isFinite() || !fy.isFinite() || !cx.isFinite() || !cy.isFinite() || fx <= 0 || fy <= 0) return
            val dist = o.optJSONArray("dist")
            val d = DoubleArray(4)
            if (dist != null) for (i in 0 until minOf(4, dist.length())) d[i] = dist.optDouble(i, 0.0)
            val f = File(dir, "kalibr_imucam_chain.yaml")
            if (!f.exists()) return
            var text = f.readText()
            fun repl(key: String, value: String): Boolean {
                val re = Regex("(?m)^(\\s*$key:).*$")
                if (!re.containsMatchIn(text)) return false
                text = re.replace(text) { m -> m.groupValues[1] + " " + value }
                return true
            }
            repl("intrinsics", String.format(java.util.Locale.US, "[%.9f, %.9f, %.9f, %.9f]", fx, fy, cx, cy))
            repl("distortion_coeffs", String.format(java.util.Locale.US, "[%.9f, %.9f, %.9f, %.9f]", d[0], d[1], d[2], d[3]))
            repl("resolution", "[640, 480]")
            // drop any cached projection matrices from a previous aspect/resolution
            val kf = File(dir, "camera_intrinsics.yaml")
            if (kf.exists()) kf.delete()
            f.writeText(text)
            Log.i(TAG, String.format(java.util.Locale.US,
                "VIO camera model: fx=%.2f fy=%.2f cx=%.2f cy=%.2f dist=[%.3f,%.3f,%.3f,%.3f] (engine %.0fx%.0f)",
                fx, fy, cx, cy, d[0], d[1], d[2], d[3], w, h))
        } catch (e: Exception) {
            Log.w(TAG, "syncVioIntrinsics failed", e)
        }
    }

    /** Camera pose as a 4x4 row-major matrix: world (VIO) -> camera, as the engine expects. */
    fun cameraPose(out: DoubleArray, addTrajectory: Boolean = true): Boolean {
        if (vio == 0L) return false
        val p = DoubleArray(3)
        val q = DoubleArray(4)
        if (!NativeLib.nativeVioGetPose(vio, p, q)) return false
        if (!p.all { it.isFinite() } || !q.all { it.isFinite() }) return false
        val w = q[0]
        val x = q[1]
        val y = q[2]
        val z = q[3]
        // camera -> world rotation from Hamilton quaternion
        val r00 = 1.0 - 2.0 * (y * y + z * z)
        val r01 = 2.0 * (x * y - z * w)
        val r02 = 2.0 * (x * z + y * w)
        val r10 = 2.0 * (x * y + z * w)
        val r11 = 1.0 - 2.0 * (x * x + z * z)
        val r12 = 2.0 * (y * z - x * w)
        val r20 = 2.0 * (x * z - y * w)
        val r21 = 2.0 * (y * z + x * w)
        val r22 = 1.0 - 2.0 * (x * x + y * y)
        if (frameCount % 60 == 0) {
            android.util.Log.i(TAG, "pose log p=(%.3f,%.3f,%.3f) q=(%.3f,%.3f,%.3f,%.3f) traj=%d".format(
                p[0], p[1], p[2], q[0], q[1], q[2], q[3], trajectory.size))
        }
        if (addTrajectory) {
            if (trajectory.size > 4000) trajectory.removeAt(0)
            trajectory.add(floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat()))
        }
        // engine wants world -> camera: transpose
        out[0] = r00
        out[1] = r10
        out[2] = r20
        out[3] = -(r00 * p[0] + r10 * p[1] + r20 * p[2])
        out[4] = r01
        out[5] = r11
        out[6] = r21
        out[7] = -(r01 * p[0] + r11 * p[1] + r21 * p[2])
        out[8] = r02
        out[9] = r12
        out[10] = r22
        out[11] = -(r02 * p[0] + r12 * p[1] + r22 * p[2])
        out[12] = 0.0
        out[13] = 0.0
        out[14] = 0.0
        out[15] = 1.0
        return true
    }

    /** Laser stripe pixels (analysis-frame x,y pairs) from the latest engine frame. */
    fun setLaserPixels(px: FloatArray, w: Int, h: Int) {
        laserPx = px
        laserW = w
        laserH = h
    }

    /**
     * Update the laser stripe exclusion band from the engine header (analysis-frame pixels and
     * degrees). state: 0 laser off, 1 on but no stripe found, 2 stripe found.
     */
    fun setLaserLine(meanX: Float, meanY: Float, angleDeg: Float, state: Int, w: Int, h: Int) {
        if (state != 2 || w <= 0 || h <= 0 || !meanX.isFinite() || !meanY.isFinite() || !angleDeg.isFinite()) {
            bandOn = false
            if (vio != 0L) NativeLib.nativeVioSetLaserBand(vio, 0.0, 0.0, 0.0, 1.0, 0.0, false)
            return
        }
        val sx = GW.toFloat() / w
        val sy = GH.toFloat() / h
        val a = Math.toRadians(angleDeg.toDouble())
        bandCx = meanX * sx
        bandCy = meanY * sy
        bandNx = -Math.sin(a).toFloat()
        bandNy = Math.cos(a).toFloat()
        bandHw = 22f
        bandOn = true
        if (vio != 0L) {
            NativeLib.nativeVioSetLaserBand(vio, bandCx.toDouble(), bandCy.toDouble(),
                bandNx.toDouble(), bandNy.toDouble(), bandHw.toDouble(), true)
        }
    }

    fun trajectorySize(): Int = trajectory.size

    /** Snapshot of the tracked path in VIO world metres (x, y, z; +Z is up). */
    fun trajectoryPoints(): Array<FloatArray> = trajectory.toTypedArray()

    /** True only when the trajectory has real, finite extent worth drawing. */
    fun trajectoryHasData(): Boolean {
        var finite = 0
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (pt in trajectory) {
            if (pt.size < 3) continue
            if (!pt[0].isFinite() || !pt[1].isFinite() || !pt[2].isFinite()) continue
            finite++
            for (k in 0 until 3) {
                if (pt[k] < min[k]) min[k] = pt[k]
                if (pt[k] > max[k]) max[k] = pt[k]
            }
        }
        if (finite < 2) return false
        val span = maxOf(max[0] - min[0], max[1] - min[1], max[2] - min[2])
        return span > 0.003f   // 3 mm of real movement at least
    }

    fun release() {
        synchronized(PROCESS_LOCK) { releaseLocked() }
    }

    private fun releaseLocked() {
        try {
            sensors?.unregisterListener(this)
        } catch (_: Exception) {
        }
        sensors = null
        if (vio != 0L) {
            NativeLib.nativeVioDestroy(vio)
            vio = 0L
        }
        if (sp != 0L) {
            SpTflite.close(sp)
            sp = 0L
        }
        initialized = false
        tracking = false
        lastKp = null
        frameCount = 0
        haveAccel = false
        haveGyro = false
        accelTs = 0.0
        accelPrevTs = -1.0
        gyroTs = 0.0
        lastImuTs = 0.0
        lastFrameTs = 0.0
        imuAccelEvents = 0L
        imuGyroEvents = 0L
        imuFed = 0L
        imuRejected = 0L
        lastAccelNorm = 0.0
        released = true
        if (ACTIVE === this) ACTIVE = null
    }

    private companion object {
        private val PROCESS_LOCK = Any()
        @Volatile private var ACTIVE: VioRunner? = null
        const val TAG = "VioRunner"
        const val GW = 640
        const val GH = 480
        val CONFIGS = listOf("estimator_config.yaml", "kalibr_imu_chain.yaml", "kalibr_imucam_chain.yaml")
    }
}
