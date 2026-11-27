package com.rawmechatronics.easyscanner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracking self-test: runs the SuperPoint front-end (TFLite + QNN delegate, NPU
 * when available) on the live camera and records a device-calibrated session.
 * This is the scanner running its own camera->feature->match->pose check; it
 * stores telemetry for later tracker development and ends in a pass/fail once
 * the camera-IMU-laser calibration and the tracker are wired in.
 */
class TrackingSelfTestActivity : Activity() {
    private lateinit var image: ImageView
    private lateinit var status: TextView
    private var reader: ImageReader? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var tflite = 0L
    private val busy = AtomicBoolean(false)
    private var frames = 0
    private var saved = 0
    private var failed = 0
    private var totalInfer = 0.0
    private var totalMatch = 0.0
    private var lastKp = 0
    private var lastMatches = 0
    private var lastInliers = 0
    private var lastRot = 0f
    private var sessionDir: File? = null
    private var csv: FileOutputStream? = null
    private var sensors: SensorManager? = null
    @Volatile private var gyroAngle = 0.0
    private var gyroLastTs = 0L
    private var sensorOrientation = 90
    private var displayRotation = 0
    private var manualRot = 0
    private var prevFeat: FloatArray? = null
    private var lgPairs = 0
    private var lgMs = 0.0
    private var showLgView = true
    private var lgPrevY: ByteArray? = null
    private var lgPrevKp: FloatArray? = null
    private var lgCurKp: FloatArray? = null
    private var lgPairData: IntArray? = null
    private var lastFrameY: ByteArray? = null
    @Volatile private var lgInitDone = false
    @Volatile private var lgInitOk = false
    @Volatile private var lgBackend = "init..."
    private var cameraRetries = 0
    private var retryPending = false

    // Native pointers on Android ARM64 have top-byte tags (0xb400...) so valid handles can be negative;
    // only 0 and error codes -4..-1 indicate failure.
    private fun isValidTfliteHandle(h: Long): Boolean = h != 0L && (h < -4L || h > 0L)

    private val gyro = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (e.sensor.type != Sensor.TYPE_GYROSCOPE) return
            if (gyroLastTs != 0L) {
                val dt = (e.timestamp - gyroLastTs) * 1e-9
                if (dt > 0 && dt < 0.5) {
                    gyroAngle += Math.sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()) * dt
                }
            }
            gyroLastTs = e.timestamp
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            textSize = 13f
            setPadding(24, 20, 24, 20)
            text = "Tracking self-test: starting..."
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER }
        bar.addView(Button(this).apply { text = "ROT"; setOnClickListener { manualRot = (manualRot + 90) % 360 } })
        bar.addView(Button(this).apply { text = "LG VIEW"; setOnClickListener { showLgView = !showLgView } })
        bar.addView(Button(this).apply { text = "BACK"; setOnClickListener { finish() } })
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        thread = HandlerThread("self-test").apply { start() }
        handler = Handler(thread!!.looper)
        handler!!.post { startRecording() }
        // Load SuperPoint then LightGlue sequentially on a dedicated init thread so they never
        // contend for the Hexagon HTP compiler and the camera preview opens immediately.
        Thread({ initModel() }, "selftest-init").start()
        sensors = getSystemService(SensorManager::class.java)
        sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensors?.registerListener(gyro, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 9)
        } else {
            handler!!.post { openCamera() }
        }
    }

    override fun onPause() {
        try { sensors?.unregisterListener(gyro) } catch (_: Exception) {}
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        try { csv?.flush(); csv?.close() } catch (_: Exception) {}
        if (isValidTfliteHandle(tflite)) try { SpTflite.close(tflite) } catch (_: Exception) {}
        thread?.quitSafely()
        super.onDestroy()
    }

    private fun startRecording() {
        val root = getExternalFilesDir(null) ?: filesDir
        val dir = File(root, "tracker-selftest/session-" + System.currentTimeMillis())
        dir.mkdirs()
        sessionDir = dir
        csv = FileOutputStream(File(dir, "frames.csv")).also {
            it.write("frame,ts_ns,infer_ms,match_ms,kp,matches,inliers,rot_deg,gyro_deg,lg_pairs,lg_ms,jpeg\n".toByteArray())
        }
        Log.i(TAG, "self-test recording: " + dir.absolutePath)
    }

    private fun cameraK(): DoubleArray = try {
        val mgr = getSystemService(CameraManager::class.java)
        val id = mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        if (id != null) {
            val c = mgr.getCameraCharacteristics(id)
            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val k = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            if (active != null && k != null && k[0] > 0f && k[2] > 0f && k[3] > 0f) {
                val sx = 640.0 / active.width(); val sy = 480.0 / active.height()
                return doubleArrayOf(k[0] * sx, k[1] * sy, k[2] * sx, k[3] * sy)
            }
            val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (f != null && phys != null && phys.width > 0f) {
                val fx = (f / phys.width * 640.0).toDouble()
                return doubleArrayOf(fx, fx, 320.0, 240.0)
            }
        }
        doubleArrayOf(600.0, 600.0, 320.0, 240.0)
    } catch (e: Throwable) { doubleArrayOf(600.0, 600.0, 320.0, 240.0) }

    private fun initModel() {
        runOnUiThread { status.text = "Tracking self-test: loading XFeat on NPU..." }
        try {
            val model = AssetCopy.ensure(this, "xfeat.tflite", File(filesDir, "xfeat.tflite"))
            val k = cameraK()
            val t0 = System.currentTimeMillis()
            tflite = SpTflite.create(applicationInfo.nativeLibraryDir, model.absolutePath, k[0], k[1], k[2], k[3])
            Log.i(TAG, "self-test xfeat tflite=" + tflite + " ms=" + (System.currentTimeMillis() - t0))
        } catch (e: Throwable) {
            Log.e(TAG, "self-test model failed", e)
        }
        lgBackend = "compiling NPU..."
        try {
            val lg = AssetCopy.ensure(this, "lighterglue.tflite", File(filesDir, "lighterglue.tflite"))
            val t0 = System.currentTimeMillis()
            SpTflite.setFeatureCap(400)
            val rc = NativeLib.nativeVioLightGlueInit(applicationInfo.nativeLibraryDir, lg.absolutePath)
            val qnn = NativeLib.nativeVioLightGlueReady()
            val ms = System.currentTimeMillis() - t0
            lgInitOk = (rc == 0)
            lgBackend = when {
                rc != 0 -> "ERR rc=" + rc
                qnn -> "NPU"
                else -> "CPU"
            }
            lgInitDone = true
            Log.i("LGSELFTEST", "lighterglue init=" + rc + " qnn=" + qnn + " ms=" + ms)
        } catch (e: Throwable) {
            lgInitOk = false
            lgBackend = "ERR " + (e.message ?: e.javaClass.simpleName)
            lgInitDone = true
            Log.e("LGSELFTEST", "lighterglue init failed", e)
        }
    }

    private fun scheduleCameraRetry(reason: String) {
        if (retryPending || cameraRetries >= 15) {
            Log.e(TAG, "self-test camera unavailable after " + cameraRetries + " retries: " + reason)
            return
        }
        retryPending = true
        cameraRetries++
        Log.w(TAG, "self-test camera retry " + cameraRetries + ": " + reason)
        handler?.postDelayed({
            retryPending = false
            openCamera()
        }, 1500)
    }

    private fun openCamera() {
        if (device != null) return
        val mgr = getSystemService(CameraManager::class.java)
        val id = mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        if (id == null) {
            scheduleCameraRetry("no back camera")
            return
        }
        sensorOrientation = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        displayRotation = windowManager.defaultDisplay.rotation
        val r = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3)
        reader = r
        r.setOnImageAvailableListener({ ir -> onFrame(ir) }, handler)
        @Suppress("DEPRECATION")
        try {
        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                @Suppress("DEPRECATION")
                try {
                    d.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            req.addTarget(r.surface)
                            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            s.setRepeatingRequest(req.build(), null, handler)
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            Log.e(TAG, "self-test configure failed")
                            scheduleCameraRetry("configure failed")
                        }
                    }, handler)
                } catch (e: Exception) {
                    Log.e(TAG, "self-test session failed", e)
                    try { d.close() } catch (_: Exception) {}
                    device = null
                }
            }
            override fun onDisconnected(d: CameraDevice) {
                try { d.close() } catch (_: Exception) {}
                device = null
                scheduleCameraRetry("camera disconnected")
            }
            override fun onError(d: CameraDevice, error: Int) {
                Log.e(TAG, "self-test camera error " + error)
                try { d.close() } catch (_: Exception) {}
                device = null
                scheduleCameraRetry("camera error " + error)
            }
        }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "self-test openCamera failed", e)
            scheduleCameraRetry("openCamera exception")
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; device = null; reader = null
    }

    private fun onFrame(ir: ImageReader) {
        val img = try { ir.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return
        if (!busy.compareAndSet(false, true)) { img.close(); return }
        val w = img.width; val h = img.height
        val planes = img.planes
        val yp = planes[0]
        val y = ByteArray(w * h)
        for (row in 0 until h) { yp.buffer.position(row * yp.rowStride); yp.buffer.get(y, row * w, w) }
        val up = planes[1].buffer.duplicate().also { it.rewind() }
        val u = ByteArray(up.remaining()); up.get(u)
        val vp = planes[2].buffer.duplicate().also { it.rewind() }
        val v = ByteArray(vp.remaining()); vp.get(v)
        val uvStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride
        val rgb = YuvRgb.toRgb(y, u, v, w, h, w, uvStride, uvPixelStride, 640, 480)
        img.close()
        frames++
        handler?.post {
            try {
                val g0 = gyroAngle
                val spHandle = tflite
                if (!isValidTfliteHandle(spHandle)) {
                    val bmp = drawPreviewOnly(y, w, h)
                    runOnUiThread { image.setImageBitmap(bmp) }
                    return@post
                }
                val res = SpTflite.run(spHandle, y) ?: return@post
                if (lgInitDone && lgInitOk) try {
                    val feat = SpTflite.features(spHandle, y)
                    if (feat != null && feat.isNotEmpty()) {
                        val n = feat[0].toInt()
                        val curKp = feat.copyOfRange(1, 1 + n * 2)
                        val curD = feat.copyOfRange(1 + n * 2, 1 + n * 2 + n * 64)
                        val pf = prevFeat
                        if (pf != null && pf.isNotEmpty()) {
                            val pn = pf[0].toInt()
                            val prevKp = pf.copyOfRange(1, 1 + pn * 2)
                            val prevD = pf.copyOfRange(1 + pn * 2, 1 + pn * 2 + pn * 64)
                            val t0 = System.nanoTime()
                            val pr = NativeLib.nativeVioLightGlueMatch(prevKp, prevD, curKp, curD)
                            lgMs = (System.nanoTime() - t0) / 1e6
                            lgPairs = pr?.getOrNull(0) ?: 0
                            // Keep the pair indices and both keypoint sets for the
                            // side-by-side LightGlue view.
                            lgPrevY = lastFrameY?.copyOf()
                            lgPrevKp = prevKp.copyOf()
                            lgCurKp = curKp.copyOf()
                            lgPairData = pr
                            Log.i("LGSELFTEST", "kp=" + n + " pairs=" + lgPairs + " ms=" + String.format(java.util.Locale.ROOT, "%.1f", lgMs))
                        }
                        prevFeat = feat
                    }
                } catch (e: Throwable) {
                    Log.e("LGSELFTEST", "match failed", e)
                }
                val kpN = res[2].toInt(); val matchN = res[3].toInt(); val inl = res[4].toInt()
                val rot = res[5]; val shownM = res[6].toInt()
                totalInfer += res[0]; totalMatch += res[1]
                lastKp = kpN; lastMatches = matchN; lastInliers = inl; lastRot = rot
                val gyroDeg = (gyroAngle - g0) * 180.0 / Math.PI
                val jpeg = encodeJpeg(y, w, h)
                val name = "f%05d.jpg".format(saved)
                File(sessionDir, name).writeBytes(jpeg)
                csv?.write((saved.toString() + "," + System.nanoTime() + "," + String.format(java.util.Locale.ROOT, "%.1f", res[0]) + "," +
                    String.format(java.util.Locale.ROOT, "%.1f", res[1]) + "," + kpN + "," + matchN + "," + inl + "," +
                    String.format(java.util.Locale.ROOT, "%.3f", rot) + "," + String.format(java.util.Locale.ROOT, "%.3f", gyroDeg) + "," +
                    lgPairs + "," + String.format(java.util.Locale.ROOT, "%.1f", lgMs) + "," + name + "\n").toByteArray())
                if (saved % 10 == 0) csv?.flush()
                saved++
                lastFrameY = y.copyOf()
                var bmp = if (showLgView) drawLgFrame(y, w, h) ?: drawFrame(y, w, h, res, kpN, shownM) else drawFrame(y, w, h, res, kpN, shownM)
        val auto = ((sensorOrientation - displayRotation + 360) % 360 + 360) % 360
        val dispRot = (auto + manualRot) % 360
        if (dispRot != 0) {
            val m = android.graphics.Matrix().apply { postRotate(dispRot.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            bmp.recycle()
            bmp = rotated
        }
                runOnUiThread {
                    image.setImageBitmap(bmp)
                    val n = if (saved > 0) saved else 1
                    status.text = buildString {
                        append("Tracking self-test   frames ").append(frames).append("   saved ").append(saved)
                        append("\nkeypoints ").append(kpN).append("   matches ").append(matchN).append("   inliers ").append(inl)
                        append("\ninfer ").append(String.format("%.1f", totalInfer / n)).append(" ms   match ").append(String.format("%.1f", totalMatch / n)).append(" ms")
                        append("   rot ").append(String.format("%.1f", rot)).append(" deg   gyro ").append(String.format("%.1f", gyroDeg)).append(" deg")
                        append("\nLightGlue [").append(lgBackend).append("]: ").append(lgPairs).append(" pairs   ").append(String.format("%.1f", lgMs)).append(" ms")
                        append("\nrecording: ").append(sessionDir?.name ?: "?")
                    }
                }
            } catch (e: Exception) {
                failed++
                Log.e(TAG, "self-test frame failed", e)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun encodeJpeg(y: ByteArray, w: Int, h: Int): ByteArray {
        val nv21 = ByteArray(w * h + w * h / 2)
        System.arraycopy(y, 0, nv21, 0, w * h)
        java.util.Arrays.fill(nv21, w * h, nv21.size, 0x80.toByte())
        val bos = ByteArrayOutputStream(128 * 1024)
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 85, bos)
        return bos.toByteArray()
    }

    private fun drawPreviewOnly(y: ByteArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (i in 0 until w * h) {
            val v = y[i].toInt() and 0xff
            px[i] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    /** Side-by-side previous/current frame with the actual LightGlue pairs drawn across. */
    private fun drawLgFrame(cur: ByteArray, w: Int, h: Int): Bitmap? {
        val prev = lgPrevY ?: return null
        val pk = lgPrevKp ?: return null
        val ck = lgCurKp ?: return null
        val pairs = lgPairData ?: return null
        val count = pairs.getOrNull(0) ?: 0
        if (count <= 0) return null

        val bmp = Bitmap.createBitmap(w * 2, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * 2 * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val lv = prev[y * w + x].toInt() and 0xff
                px[y * w * 2 + x] = (0xff shl 24) or (lv shl 16) or (lv shl 8) or lv
                val rv = cur[y * w + x].toInt() and 0xff
                px[y * w * 2 + w + x] = (0xff shl 24) or (rv shl 16) or (rv shl 8) or rv
            }
        }
        bmp.setPixels(px, 0, w * 2, 0, 0, w * 2, h)
        val c = Canvas(bmp)
        val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 255, 140); style = Paint.Style.STROKE; strokeWidth = 2f }
        val yellow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 220, 60); style = Paint.Style.STROKE; strokeWidth = 2f }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 22f; style = Paint.Style.FILL
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        c.drawLine(w.toFloat(), 0f, w.toFloat(), h.toFloat(), green)
        c.drawText("previous", 12f, 26f, label)
        c.drawText("current  pairs=" + count, w + 12f, 26f, label)
        for (i in 0 until count) {
            val a = pairs[1 + i * 2]
            val b = pairs[2 + i * 2]
            if (a < 0 || b < 0) continue
            if (a * 2 + 1 >= pk.size || b * 2 + 1 >= ck.size) continue
            val x0 = pk[a * 2]; val y0 = pk[a * 2 + 1]
            val x1 = ck[b * 2]; val y1 = ck[b * 2 + 1]
            c.drawCircle(x0, y0, 3f, green)
            c.drawCircle(w + x1, y1, 3f, green)
            c.drawLine(x0, y0, w + x1, y1, yellow)
        }
        return bmp
    }

    private fun drawFrame(y: ByteArray, w: Int, h: Int, res: FloatArray, kpN: Int, shownM: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (i in 0 until w * h) {
            val v = y[i].toInt() and 0xff
            px[i] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        val c = Canvas(bmp)
        val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 255, 140); style = Paint.Style.STROKE; strokeWidth = 3f }
        val yellow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 220, 60); style = Paint.Style.STROKE; strokeWidth = 2f }
        var p = 7
        for (i in 0 until kpN) { c.drawCircle(res[p], res[p + 1], 5f, green); p += 3 }
        for (i in 0 until shownM) {
            c.drawLine(res[p], res[p + 1], res[p + 2], res[p + 3], yellow); p += 4
        }
        return bmp
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) handler!!.post { openCamera() }
    }

    companion object { private const val TAG = "EasyScanner" }
}
