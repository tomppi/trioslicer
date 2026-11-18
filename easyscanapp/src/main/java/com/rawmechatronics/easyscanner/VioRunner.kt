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
    private var frameCount = 0

    @Volatile var initialized = false
        private set
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
        vio = NativeLib.nativeVioCreate(dir.absolutePath)
        if (vio == 0L) {
            Log.e(TAG, "VIO create failed (config/parse error) - marker-free pose disabled")
            return
        }
        val model = File(ctx.filesDir, "superpoint.tflite")
        if (!model.exists()) {
            try {
                ctx.assets.open("superpoint.tflite").use { input ->
                    model.outputStream().use { input.copyTo(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "model copy failed", e)
            }
        }
        sp = SpTflite.create(ctx.applicationInfo.nativeLibraryDir, model.absolutePath, 0.0, 0.0, 0.0, 0.0)
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
        val ts = event.timestamp * 1e-9
        if (type == Sensor.TYPE_ACCELEROMETER) {
            imuAccelEvents++
            if (accelTs > 0.0) {
                accelPrev[0] = accel[0]
                accelPrev[1] = accel[1]
                accelPrev[2] = accel[2]
                accelPrevTs = accelTs
            }
            accel[0] = event.values[0]
            accel[1] = event.values[1]
            accel[2] = event.values[2]
            accelTs = ts
            haveAccel = true
            return
        }
        if (type != Sensor.TYPE_GYROSCOPE) return
        gyro[0] = event.values[0]
        gyro[1] = event.values[1]
        gyro[2] = event.values[2]
        gyroTs = ts
        haveGyro = true
        imuGyroEvents++
        if (vio == 0L || !haveAccel) return
        if (!(ts > lastImuTs)) { imuRejected++; return }  // reject duplicate/reversed samples
        // Pair the gyro sample with the accelerometer interpolated to the gyro timestamp so the
        // two sensor values carry one consistent timestamp (feeding a stale value under the other
        // sensor's stamp biases propagation).
        val ax: Float
        val ay: Float
        val az: Float
        if (accelPrevTs > 0.0 && ts > accelPrevTs && ts <= accelTs) {
            val a = ((ts - accelPrevTs) / (accelTs - accelPrevTs)).toFloat()
            ax = accelPrev[0] + (accel[0] - accelPrev[0]) * a
            ay = accelPrev[1] + (accel[1] - accelPrev[1]) * a
            az = accelPrev[2] + (accel[2] - accelPrev[2]) * a
        } else {
            ax = accel[0]
            ay = accel[1]
            az = accel[2]
        }
        if (!ax.isFinite() || !ay.isFinite() || !az.isFinite()) return
        lastImuTs = ts
        lastAccelNorm = Math.sqrt((ax * ax + ay * ay + az * az).toDouble())
        imuFed++
        NativeLib.nativeVioFeedImu(vio, ax, ay, az, gyro[0], gyro[1], gyro[2], ts)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Feed one Camera2 Y plane; returns true once VIO is initialised. */
    fun submitFrame(y: ByteArray, yStride: Int, width: Int, height: Int, timestampSec: Double): Boolean {
        if (!tracking) { lastKp = null; return initialized }
        if (vio == 0L || sp == 0L) return false
        if (!(timestampSec > lastFrameTs)) return initialized  // drop duplicate/reversed camera frames
        lastFrameTs = timestampSec
        val gray = ByteArray(GW * GH)
        for (yy in 0 until GH) {
            val sy = yy * height / GH
            val row = sy * yStride
            for (xx in 0 until GW) {
                val sx = xx * width / GW
                gray[yy * GW + xx] = y[row + sx]
            }
        }
        val feat = SpTflite.features(sp, gray)
        if (feat == null) { if (frameCount % 30 == 0) Log.w(TAG, "features() null sp=" + sp); return false }
        if (feat.isEmpty()) return false
        val n = feat[0].toInt()
        if (n <= 0) return false
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
        lastFeatureCount = nKeep
        lastKp = if (spOverlay) kp else null
        NativeLib.nativeVioSetFeatures(vio, 0, timestampSec, kp, desc, nn)
        NativeLib.nativeVioFeedFrame(vio, gray, GW, GH, timestampSec)
        val now = NativeLib.nativeVioInitialized(vio)
        if (now != initialized) {
            initialized = now
            Log.i(TAG, "VIO initialized=" + now)
        }
        frameCount++
        if (frameCount % 60 == 0) Log.i(TAG, "VIO frames=" + frameCount + " init=" + initialized + " features=" + n +
                " imu: accelEv=" + imuAccelEvents + " gyroEv=" + imuGyroEvents + " fed=" + imuFed +
                " reversed=" + imuRejected + " |a|=" + String.format(java.util.Locale.US, "%.2f", lastAccelNorm))
        return initialized
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
            trajectory.add(floatArrayOf(p[0].toFloat(), p[2].toFloat()))
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

    /** True only when the trajectory has real, finite extent worth drawing. */
    fun trajectoryHasData(): Boolean {
        var finite = 0
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (pt in trajectory) {
            if (pt[0].isNaN() || pt[1].isNaN() || pt[0].isInfinite() || pt[1].isInfinite()) continue
            finite++
            if (pt[0] < minX) minX = pt[0]
            if (pt[0] > maxX) maxX = pt[0]
            if (pt[1] < minZ) minZ = pt[1]
            if (pt[1] > maxZ) maxZ = pt[1]
        }
        if (finite < 2) return false
        val span = maxOf(maxX - minX, maxZ - minZ)
        return span > 0.003f   // 3 mm of real movement at least
    }

    /** Draw the tracked path (x,z) into a square box. */
    fun drawTrajectory(canvas: android.graphics.Canvas, left: Float, top: Float, size: Float, paint: android.graphics.Paint) {
        val pts = trajectory.toTypedArray()
        val n = pts.size
        if (n < 2) return
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (pt in pts) {
            if (pt[0].isNaN() || pt[1].isNaN()) continue
            if (pt[0] < minX) minX = pt[0]
            if (pt[0] > maxX) maxX = pt[0]
            if (pt[1] < minZ) minZ = pt[1]
            if (pt[1] > maxZ) maxZ = pt[1]
        }
        if (minX > maxX || minZ > maxZ) return
        val span = maxOf(maxX - minX, maxZ - minZ, 0.05f)
        val scale = size * 0.9f / span
        val path = android.graphics.Path()
        for (i in 0 until n) {
            if (pts[i][0].isNaN() || pts[i][1].isNaN()) continue
            val x = left + size * 0.05f + (pts[i][0] - minX) * scale
            val y = top + size * 0.95f - (pts[i][1] - minZ) * scale
            if (path.isEmpty) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    fun release() {
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
    }

    private companion object {
        const val TAG = "VioRunner"
        const val GW = 640
        const val GH = 480
        val CONFIGS = listOf("estimator_config.yaml", "kalibr_imu_chain.yaml", "kalibr_imucam_chain.yaml")
    }
}
