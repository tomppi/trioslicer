package com.rawmechatronics.easyscanner

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.Image
import android.util.Log
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Records what an offline tracker needs: grayscale frames with their sensor
 * timestamps and the core's per-frame result (board pose as ground truth),
 * plus timestamped accelerometer and gyroscope samples.
 *
 * Enabled from Settings -> Development -> "Record tracker debug data". While
 * it is on, every processed frame of an active scan is JPEG-encoded on a
 * worker thread and written under
 *   Android/data/<package>/files/tracker-debug/session-<epoch>/
 * next to frames.csv, imu.csv and meta.json. Pull that directory over adb.
 */
class TrackerRecorder(
    private val context: Context,
    private val metaJson: String,
    private val gateOnScan: Boolean = true,
    private val recordFrames: Boolean = true,
) {
    @Volatile var sessionDir: File? = null
        private set
    @Volatile var savedFrames = 0
        private set
    @Volatile var droppedFrames = 0
        private set

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "es-tracker-rec") }
    private val queue = ArrayBlockingQueue<Frame>(8)
    private var framesWriter: BufferedWriter? = null
    private var imuWriter: BufferedWriter? = null
    private var imuRunning = false
    private var sensorManager: SensorManager? = null

    private data class Frame(val nv21: ByteArray, val w: Int, val h: Int, val result: FloatArray, val tsNs: Long, val procMs: Double, val vioPose: DoubleArray?)

    private val imuListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val w = imuWriter ?: return
            val type = when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> "a"
                Sensor.TYPE_GYROSCOPE -> "g"
                else -> return
            }
            synchronized(lock) {
                try {
                    w.write(type + "," + e.timestamp + "," + e.values[0] + "," + e.values[1] + "," + e.values[2] + "\n")
                } catch (_: Exception) {}
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start(): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "tracker-debug/session-" + System.currentTimeMillis())
        dir.mkdirs()
        framesWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(File(dir, "frames.csv")), Charsets.UTF_8))
        framesWriter?.write("frame,ts_ns,scan_on,detected,corners,cam_x,cam_y,cam_z,roll,pitch,yaw,distance,reproj_rms,fx,fy,cx,cy,laser_state,laser_points,proc_ms,laser_cam_count,pose16,jpeg\n")
        framesWriter?.flush()
        imuWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(File(dir, "imu.csv")), Charsets.UTF_8))
        imuWriter?.write("type,ts_ns,x,y,z\n")
        imuWriter?.flush()
        File(dir, "meta.json").writeText(metaJson)
        startImu()
        io.execute { drainLoop() }
        sessionDir = dir
        Log.i(TAG, "tracker recording: " + dir.absolutePath)
        return dir
    }

    private fun startImu() {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager = sm
        imuRunning = true
        sm.registerListener(imuListener, a, SensorManager.SENSOR_DELAY_FASTEST)
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sm.registerListener(imuListener, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    private fun stopImu() {
        if (!imuRunning) return
        imuRunning = false
        try { sensorManager?.unregisterListener(imuListener) } catch (_: Exception) {}
        sensorManager = null
    }

    /** Copy the planes while the Image is valid; encode and write on the IO thread. */
    fun capture(img: Image, r: FrameResult, procMs: Double, vioPose: DoubleArray? = null) {
        if (sessionDir == null || savedFrames >= MAX_FRAMES) return
        if (!recordFrames) return
        if (r.length <= FrameResult.H.SCAN_ON) return
        if (gateOnScan && r[FrameResult.H.SCAN_ON] < 0.5f) return
        val w = img.width
        val h = img.height
        val planes = img.planes
        val y = planes[0]; val u = planes[1]; val v = planes[2]
        val nv21 = ByteArray(w * h + w * h / 2)
        for (row in 0 until h) {
            y.buffer.position(row * y.rowStride)
            y.buffer.get(nv21, row * w, w)
        }
        var p = w * h
        for (row in 0 until h / 2) {
            val uvRow = row * u.rowStride
            for (col in 0 until w / 2) {
                nv21[p++] = v.buffer.get(uvRow + col * v.pixelStride)
                nv21[p++] = u.buffer.get(uvRow + col * u.pixelStride)
            }
        }
        val frame = Frame(nv21, w, h, r.data.copyOf(r.length), r.timestampNs, procMs, vioPose)
        if (!queue.offer(frame)) droppedFrames++
    }

    private fun drainLoop() {
        while (true) {
            val f = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null } ?: continue
            if (f.w == 0 || f.nv21.isEmpty()) {
                if (sessionDir == null) return else continue
            }
            writeFrame(f)
        }
    }

    private fun writeFrame(f: Frame) {
        val dir = sessionDir ?: return
        val idx = savedFrames
        try {
            val bos = ByteArrayOutputStream(256 * 1024)
            YuvImage(f.nv21, ImageFormat.NV21, f.w, f.h, null).compressToJpeg(Rect(0, 0, f.w, f.h), 88, bos)
            val name = "f%05d.jpg".format(idx)
            File(dir, name).writeBytes(bos.toByteArray())
            val laserDir = File(dir, "laser").apply { mkdirs() }
            var laserCamCount = 0
            var poseStr = ""
            var li = FrameResult.H.LEN
            while (li + 3 <= f.result.size) {
                val kind = f.result[li].toInt()
                val stride = f.result[li + 1].toInt()
                val count = f.result[li + 2].toInt()
                val off = li + 3
                if (kind == FrameResult.Layer.LASER_CAM && stride == 3 && count > 0) {
                    val bb = java.nio.ByteBuffer.allocate(count * 3 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (k in 0 until count * 3) bb.putFloat(f.result[off + k])
                    File(laserDir, "f%05d.f32".format(idx)).writeBytes(bb.array())
                    laserCamCount = count
                } else if (kind == FrameResult.Layer.POSE && stride == 16) {
                    val sb = StringBuilder(256)
                    for (k in 0 until 16) { if (k > 0) sb.append(' '); sb.append(f.result[off + k]) }
                    poseStr = sb.toString()
                }
                if (stride <= 0) break
                li += 3 + stride * count
            }
            val d = f.result
            fun g(slot: Int) = if (slot < d.size) d[slot] else 0f
            val row = StringBuilder(160)
                .append(idx).append(',')
                .append(f.tsNs).append(',')
                .append(g(FrameResult.H.SCAN_ON)).append(',')
                .append(g(FrameResult.H.DETECTED)).append(',')
                .append(g(FrameResult.H.N_CORNERS)).append(',')
                .append(g(FrameResult.H.CAM_X)).append(',')
                .append(g(FrameResult.H.CAM_Y)).append(',')
                .append(g(FrameResult.H.CAM_Z)).append(',')
                .append(g(FrameResult.H.ROLL)).append(',')
                .append(g(FrameResult.H.PITCH)).append(',')
                .append(g(FrameResult.H.YAW)).append(',')
                .append(g(FrameResult.H.DISTANCE)).append(',')
                .append(g(FrameResult.H.REPROJ_RMS)).append(',')
                .append(g(FrameResult.H.FX)).append(',')
                .append(g(FrameResult.H.FY)).append(',')
                .append(g(FrameResult.H.CX)).append(',')
                .append(g(FrameResult.H.CY)).append(',')
                .append(g(FrameResult.H.LASER_STATE)).append(',')
                .append(g(FrameResult.H.LASER_POINTS)).append(',')
                .append("%.2f".format(f.procMs)).append(',')
                .append(laserCamCount).append(',')
                .append(poseStr).append(',')
            val vp = f.vioPose
            val sb2 = StringBuilder(256)
            if (vp != null && vp.size == 16) {
                for (k in 0 until 16) { if (k > 0) sb2.append(' '); sb2.append(vp[k]) }
            }
            row.append(sb2.toString())
            row.append(',').append(name).append('\n')
            synchronized(lock) {
                framesWriter?.write(row.toString())
                if (savedFrames % 10 == 0) framesWriter?.flush()
            }
            savedFrames++
        } catch (e: Exception) {
            Log.w(TAG, "tracker frame write failed", e)
        }
    }

    fun stop() {
        stopImu()
        val dir = sessionDir
        sessionDir = null
        queue.clear()
        queue.offer(Frame(ByteArray(0), 0, 0, FloatArray(0), 0, 0.0, null))
        io.shutdown()
        try { io.awaitTermination(3, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        synchronized(lock) {
            try { framesWriter?.flush(); framesWriter?.close() } catch (_: Exception) {}
            try { imuWriter?.flush(); imuWriter?.close() } catch (_: Exception) {}
            framesWriter = null
            imuWriter = null
        }
        if (dir != null) Log.i(TAG, "tracker recording stopped: " + dir.absolutePath + " frames=" + savedFrames + " dropped=" + droppedFrames)
    }

    companion object {
        private const val TAG = "EasyScanner"
        const val MAX_FRAMES = 1200
    }
}
