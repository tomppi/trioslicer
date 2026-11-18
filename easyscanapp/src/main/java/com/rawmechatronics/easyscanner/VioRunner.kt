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
    private var haveAccel = false
    private var haveGyro = false
    private var lastImuTs = 0.0
    private var frameCount = 0

    var initialized = false
        private set
    /** Feed frames only while tracking is on (the UI has a Track button). */
    @Volatile var tracking = false
    /** Draw the SuperPoint features on the preview. */
    @Volatile var spOverlay = false
    /** Latest SuperPoint keypoints in the native 640x480 frame (x,y pairs). */
    @Volatile var lastKp: FloatArray? = null
    private val trajectory = ArrayList<FloatArray>()
    @Volatile private var laserPx = FloatArray(0)
    private var laserW = 0
    private var laserH = 0

    fun start(handler: android.os.Handler) {
        val dir = File(ctx.filesDir, "openvins-config")
        dir.mkdirs()
        for (name in CONFIGS) {
            val out = File(dir, name)
            if (out.exists() && out.length() > 0) continue // keep an on-phone solved calibration
            try {
                ctx.assets.open("openvins/" + name).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "config copy failed: " + name, e)
            }
        }
        vio = NativeLib.nativeVioCreate(dir.absolutePath)
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
            accel[0] = event.values[0]
            accel[1] = event.values[1]
            accel[2] = event.values[2]
            haveAccel = true
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            gyro[0] = event.values[0]
            gyro[1] = event.values[1]
            gyro[2] = event.values[2]
            haveGyro = true
        } else {
            return
        }
        if (vio == 0L || !haveAccel || !haveGyro) return
        if (abs(ts - lastImuTs) < 1.0 / 400.0) return
        lastImuTs = ts
        NativeLib.nativeVioFeedImu(vio, accel[0], accel[1], accel[2], gyro[0], gyro[1], gyro[2], ts)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Feed one Camera2 Y plane; returns true once VIO is initialised. */
    fun submitFrame(y: ByteArray, yStride: Int, width: Int, height: Int, timestampSec: Double): Boolean {
        if (!tracking) { lastKp = null; return initialized }
        if (vio == 0L || sp == 0L) return false
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
            if (lpx.isNotEmpty() && lw > 0 && lh > 0) {
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
        if (nKeep < 20) nKeep = n
        val kp = FloatArray(nKeep * 2)
        val desc = FloatArray(nKeep * 256)
        var w = 0
        for (i in 0 until n) {
            if (nKeep != n && !keep[i]) continue
            kp[w * 2] = feat[1 + i * 2]
            kp[w * 2 + 1] = feat[2 + i * 2]
            System.arraycopy(feat, 1 + n * 2 + i * 256, desc, w * 256, 256)
            w++
        }
        val nn = w
        lastKp = if (spOverlay) kp else null
        NativeLib.nativeVioSetFeatures(vio, 0, timestampSec, kp, desc, nn)
        NativeLib.nativeVioFeedFrame(vio, gray, GW, GH, timestampSec)
        val now = NativeLib.nativeVioInitialized(vio)
        if (now != initialized) {
            initialized = now
            Log.i(TAG, "VIO initialized=" + now)
        }
        frameCount++
        if (frameCount % 60 == 0) Log.i(TAG, "VIO frames=" + frameCount + " init=" + initialized + " features=" + n)
        return initialized
    }

    /** Camera pose as a 4x4 row-major matrix: world (VIO) -> camera, as the engine expects. */
    fun cameraPose(out: DoubleArray): Boolean {
        if (vio == 0L) return false
        val p = DoubleArray(3)
        val q = DoubleArray(4)
        if (!NativeLib.nativeVioGetPose(vio, p, q)) return false
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
        if (trajectory.size > 4000) trajectory.removeAt(0)
        trajectory.add(floatArrayOf(p[0].toFloat(), p[2].toFloat()))
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

    fun trajectorySize(): Int = trajectory.size

    /** Draw the tracked path (x,z) into a square box. */
    fun drawTrajectory(canvas: android.graphics.Canvas, left: Float, top: Float, size: Float, paint: android.graphics.Paint) {
        val n = trajectory.size
        if (n < 2) return
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (pt in trajectory) {
            if (pt[0] < minX) minX = pt[0]
            if (pt[0] > maxX) maxX = pt[0]
            if (pt[1] < minZ) minZ = pt[1]
            if (pt[1] > maxZ) maxZ = pt[1]
        }
        val span = maxOf(maxX - minX, maxZ - minZ, 0.05f)
        val scale = size * 0.9f / span
        val path = android.graphics.Path()
        for (i in 0 until n) {
            val x = left + size * 0.05f + (trajectory[i][0] - minX) * scale
            val y = top + size * 0.95f - (trajectory[i][1] - minZ) * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
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
    }

    private companion object {
        const val TAG = "VioRunner"
        const val GW = 640
        const val GH = 480
        val CONFIGS = listOf("estimator_config.yaml", "kalibr_imu_chain.yaml", "kalibr_imucam_chain.yaml")
    }
}
