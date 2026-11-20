
package com.rawmechatronics.imucalib

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.provider.MediaStore
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.PopupMenu
import com.rawmechatronics.easyscanner.AppSettings
import com.rawmechatronics.easyscanner.CameraController
import com.rawmechatronics.easyscanner.CameraInfo
import com.rawmechatronics.easyscanner.FrameProcessor
import com.rawmechatronics.easyscanner.FrameResult
import com.rawmechatronics.easyscanner.TrackerRecorder
import com.rawmechatronics.easyscanner.VendorIntrinsics
import com.rawmechatronics.imucalib.core.CalibOptions
import com.rawmechatronics.imucalib.core.CalibrationSession
import com.rawmechatronics.imucalib.core.KalibrCalibrator
import org.opencv.android.OpenCVLoader
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI

/**
 * Separate camera-IMU calibration app. Records a board-visible 3-axis rotation
 * session (board poses + 480 Hz IMU + camera timestamps, full-rate frames for an
 * offline Kalibr bag) and solves it with the Kalibr-style batch estimator in
 * :imucalibcore. Everything is kept outside EasyScan.
 */
class MainActivity : Activity(), FrameProcessor.Listener {

    private lateinit var processor: FrameProcessor
    private lateinit var camera: CameraController
    private lateinit var mgr: CameraManager
    private lateinit var preview: TextureView
    private lateinit var previewBox: FrameLayout
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var startBtn: Button
    private lateinit var solveBtn: Button
    private lateinit var resolutionBtn: Button
    private lateinit var noiseBtn: Button
    private lateinit var exposureBtn: Button
    private lateinit var isoBtn: Button
    private var noiseRecorder: ImuNoiseRecorder? = null
    private val main = Handler(Looper.getMainLooper())
    private var activeAnalysis: Size? = null
    private var stillSeq = 0
    private var stillTimer: Runnable? = null

    private var settings: AppSettings? = null
    private var info: CameraInfo? = null
    private var cameraStarted = false
    private var aprilDetector: AprilGridDetector? = null
    private var aprilRecorder: AprilGridRecorder? = null
    private var aprilSeen = 0
    private var aprilFrames = 0

    private var rec: TrackerRecorder? = null
    private var recDir: File? = null
    private var recStartNs = 0L
    private var recFrames = 0
    private var recSeen = 0
    private var lastUiNs = 0L
    private var solving = false

    // Test harness (dev phone): process a session directory pushed from the host.
    private var feedDir: File? = null
    private var feedModel: CameraModel? = null
    private var autoFinish = false
    // Rolling-shutter timing compensation: board row vs Kalibr-reference row.
    private var rowTauS = 0.102063e-3
    private var rowRefPx = 330.1
    // Pixel-reprojection mode uses its own measured row-time correction.
    private var pixelRowTauS = 0.0231e-3
    private var pixelRowRefPx = 330.1

    private val durationS = 40.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mgr = getSystemService(CameraManager::class.java)
        settings = AppSettings.load(this)
        VendorIntrinsics.ensure(this, settings!!)
        processor = FrameProcessor(filesDir.absolutePath, this)
        camera = CameraController(
            mgr, processor.handler, processor,
            onError = { msg -> main.post { status("camera: " + msg) } },
            onSizeFailed = { size -> main.post { status("camera refused " + size) } },
            onStill = { still -> saveCalibrationStill(still) },
            onStillFailed = { msg, _ -> main.post { status("still: " + msg) } },
        )

        preview = TextureView(this)
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 8, 0, 8)
        }
        results = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        startBtn = Button(this).apply {
            text = "Record 40 s"
            setOnClickListener { if (rec != null) stopRecording(false) else beginRecording() }
        }
        solveBtn = Button(this).apply {
            text = "Solve latest recording"
            setOnClickListener { solveLatest() }
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = "Camera-IMU calibration"
            textSize = 20f
            setPadding(0, 8, 0, 4)
        })
        root.addView(TextView(this).apply {
            textSize = 12f
            text = getString(R.string.instructions)
            setPadding(0, 0, 0, 8)
        })
        previewBox = FrameLayout(this)
        previewBox.addView(preview, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        previewBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreviewToBox() }
        root.addView(previewBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f))
        root.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        resolutionBtn = Button(this).apply {
            text = "Resolution: highest"
            setOnClickListener { showResolutionMenu() }
        }
        root.addView(resolutionBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val exposureRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        exposureBtn = Button(this).apply {
            setOnClickListener { showExposureMenu() }
        }
        isoBtn = Button(this).apply {
            setOnClickListener { showIsoMenu() }
        }
        exposureRow.addView(exposureBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        exposureRow.addView(isoBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(exposureRow)
        updateExposureLabels()
        noiseBtn = Button(this).apply {
            text = "Measure IMU noise (stationary)"
            setOnClickListener { toggleNoise() }
        }
        root.addView(noiseBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(startBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(solveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)
        val scroll = ScrollView(this)
        scroll.addView(results)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.4f))
        setContentView(root)
        try {
            if (!OpenCVLoader.initLocal()) status("OpenCV failed to load; AprilGrid detection unavailable")
        } catch (e: Throwable) {
            Log.w(TAG, "OpenCV init failed", e)
            status("OpenCV failed: " + e.message)
        }
        // Probe the OpenCL path once; the status is also written to a file because the
        // camera's log spam can evict app log lines from the device log buffer.
        val gpuProbe = runCatching { GpuPixelBatch(applicationInfo.nativeLibraryDir).status }.getOrElse { "probe failed: $it" }
        runCatching { File(filesDir, "gpu_status.txt").writeText(gpuProbe) }
        Log.i(TAG, "GPU_OPENCL status=" + gpuProbe)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
        }
        // Dev-phone harness: solve a session directory pushed from the host, no camera needed.
        val feedPath = intent.getStringExtra("calib_session_dir")
        if (!feedPath.isNullOrBlank()) {
            feedDir = File(feedPath)
            feedModel = parseCameraModel(intent.getStringExtra("calib_camera_model"))
            if (feedModel == null) {
                val mf = File(feedDir, "camera_model.json")
                if (mf.exists()) feedModel = parseCameraModel(mf.readText())
            }
            autoFinish = intent.getBooleanExtra("calib_finish", false)
            rowTauS = intent.getDoubleExtra("calib_row_tau_ms", 0.102063) * 1e-3
            rowRefPx = intent.getDoubleExtra("calib_row_ref", 330.1)
            pixelRowTauS = intent.getDoubleExtra("calib_pixel_row_tau_ms", 0.0231) * 1e-3
            pixelRowRefPx = intent.getDoubleExtra("calib_pixel_row_ref", 330.1)
            status("Feed session: " + feedPath)
            main.postDelayed({ feedDir?.let { d -> if (!solving) solve(d) } }, 1500)
        }
        status("Hold the phone still, point at the board, then press Record.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera() else status("Camera permission is required.")
    }

    private fun toggleNoise() {
        val running = noiseRecorder
        if (running != null) {
            noiseRecorder = null
            running.stop(compute = false)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setNoiseUi(false)
            status("IMU noise capture cancelled.")
            return
        }
        if (rec != null || solving) { status("Stop the calibration recording/solve first."); return }
        val labels = arrayOf("5 minutes (quick)", "10 minutes", "20 minutes")
        AlertDialog.Builder(this)
            .setTitle("Record stationary IMU noise")
            .setItems(labels) { _, i ->
                startNoise(intArrayOf(300, 600, 1200)[i])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startNoise(seconds: Int) {
        if (cameraStarted) {
            camera.close()
            cameraStarted = false
            info = null
            activeAnalysis = null
        }
        val r = ImuNoiseRecorder(this, seconds,
            onProgress = { p ->
                runOnUiThread { status(String.format(Locale.US, "IMU noise: keep the phone still … %.0f%%", p * 100)) }
            },
            onDone = { res -> runOnUiThread { finishNoise(res) } })
        if (!r.start()) { status("Accelerometer/gyroscope not available"); r.stop(false); return }
        noiseRecorder = r
        setNoiseUi(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status("Keep the phone perfectly still on a table for " + (seconds / 60) + " min. Do not touch it.")
    }

    private fun setNoiseUi(running: Boolean) {
        noiseBtn.text = if (running) "Stop IMU noise" else "Measure IMU noise (stationary)"
        startBtn.isEnabled = !running
        solveBtn.isEnabled = !running
        resolutionBtn.isEnabled = !running
    }

    private fun finishNoise(res: ImuNoiseRecorder.Result?) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        noiseRecorder = null
        setNoiseUi(false)
        if (res == null) {
            status("IMU noise capture stopped — no result.")
            openCamera()
            return
        }
        val report = res.report()
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dir = File(filesDir, "imu-noise-" + stamp).apply { mkdirs() }
            val yaml = File(dir, "imu.yaml").apply { writeText(res.yaml()) }
            val txt = File(dir, "imu_noise_report.txt").apply { writeText(report) }
            val exported = exportToDownloads(dir.name, yaml, txt)
            status("IMU noise measured." + (if (exported != null) " Export: " + exported else " Export failed"))
            results.append("\nIMU noise\n" + report + "\n")
        }.onFailure { status("IMU noise result save failed: " + it.message) }
        openCamera()
    }

    /**
     * A pulled session can carry the camera model of the phone that recorded it.
     * That lets the solver run on another device (or the PC) without silently using
     * the local camera's intrinsics.
     */
    private fun sessionCameraModel(dir: File): CameraModel? {
        val f = File(dir, "camera_model.json")
        if (!f.exists()) return null
        return runCatching {
            val j = JSONObject(f.readText())
            val d = j.optJSONArray("dist")
            val dist = DoubleArray(4) { if (d != null && it < d.length()) d.optDouble(it, 0.0) else 0.0 }
            val w = j.optInt("width", 640)
            val h = j.optInt("height", 480)
            CameraModel(j.getDouble("fx"), j.getDouble("fy"), j.getDouble("cx"), j.getDouble("cy"),
                dist, "camera_model.json", w, h)
        }.onFailure { Log.w(TAG, "session camera_model.json failed", it) }.getOrNull()
    }

    private fun appPrefs() =
        getSharedPreferences("com.rawmechatronics.imucalib_preferences", MODE_PRIVATE)

    private fun updateExposureLabels() {
        val p = appPrefs()
        val manual = p.getBoolean("manual_exposure", false)
        val us = p.getInt("exposure_us", 8000)
        val iso = p.getInt("iso", 400)
        if (::exposureBtn.isInitialized) exposureBtn.text =
            if (manual) "Exposure: " + (us / 1000.0) + " ms" else "Exposure: Auto"
        if (::isoBtn.isInitialized) isoBtn.text =
            if (manual) "ISO: " + iso else "ISO: Auto"
    }

    private fun showExposureMenu() {
        if (rec != null || solving) { status("Stop recording/solve before changing exposure"); return }
        val labels = arrayOf("Auto exposure", "1 ms", "2 ms", "4 ms", "8 ms", "16 ms")
        val us = intArrayOf(0, 1000, 2000, 4000, 8000, 16000)
        AlertDialog.Builder(this).setTitle("Camera exposure").setItems(labels) { _, i ->
            if (i == 0) {
                appPrefs().edit().putBoolean("manual_exposure", false).apply()
            } else {
                appPrefs().edit().putBoolean("manual_exposure", true)
                    .putInt("exposure_us", us[i]).apply()
            }
            updateExposureLabels()
            status("Exposure set. Reopening camera…")
            reopenCamera()
        }.show()
    }

    private fun showIsoMenu() {
        if (rec != null || solving) { status("Stop recording/solve before changing ISO"); return }
        val labels = arrayOf("100", "200", "400", "800", "1600")
        val values = intArrayOf(100, 200, 400, 800, 1600)
        AlertDialog.Builder(this).setTitle("ISO (manual exposure)").setItems(labels) { _, i ->
            appPrefs().edit().putBoolean("manual_exposure", true)
                .putInt("iso", values[i]).apply()
            updateExposureLabels()
            status("ISO set. Reopening camera…")
            reopenCamera()
        }.show()
    }

    private fun analysisPref(): String =
        getSharedPreferences("imu_calib", MODE_PRIVATE).getString("analysis_size", "max") ?: "max"

    private fun showResolutionMenu() {
        if (rec != null || solving) { status("Stop recording before changing resolution"); return }
        val inf = info ?: return
        val sizes = inf.analysisSizes.distinctBy { it.width to it.height }
        if (sizes.isEmpty()) { status("No analysis sizes reported"); return }
        val menu = PopupMenu(this, resolutionBtn)
        menu.menu.add(0, 0, 0, "Highest (" + sizes.first().width + "x" + sizes.first().height + ")")
        sizes.forEachIndexed { i, sz -> menu.menu.add(0, i + 1, i + 1, sz.width.toString() + "x" + sz.height) }
        menu.setOnMenuItemClickListener { item ->
            val key = if (item.itemId == 0) "max" else sizes[item.itemId - 1].let { it.width.toString() + "x" + it.height }
            getSharedPreferences("imu_calib", MODE_PRIVATE).edit().putString("analysis_size", key).apply()
            status("Resolution selected: " + (if (key == "max") "highest" else key) + " - reopening camera")
            reopenCamera()
            true
        }
        menu.show()
    }

    private fun reopenCamera() {
        if (rec != null || solving) return
        camera.close()
        cameraStarted = false
        info = null
        activeAnalysis = null
        // AppSettings is cached at startup; the exposure/ISO/resolution controls write the
        // shared preferences directly, so reload before reopening or the old values are reused.
        settings = AppSettings.load(this)
        main.postDelayed({ openCamera() }, 350L)
    }

    private fun openCamera() {
        if (cameraStarted || !preview.isAvailable) return
        val st = settings ?: AppSettings.load(this).also { settings = it }
        val ids = CameraInfo.backCameraIds(mgr)
        if (ids.isEmpty()) { status("No back camera"); return }
        // Calibration wants the most pixels the camera can stream, not the scanner's
        // real-time 640x480 analysis cap. CameraInfo.query has this mode for exactly this.
        val inf = CameraInfo.query(mgr, st.cameraId.takeIf { it in ids } ?: ids.first(),
            st.targetFps, st.maxLongSide, preferHighestResolution = true)
        runCatching {
            File(filesDir, "camera_info.txt").writeText(
                inf.label + "\n" +
                "analysis=" + inf.analysisSizes.take(6).joinToString { it.width.toString() + "x" + it.height } + "\n" +
                "stills=" + inf.stillSizes.take(8).joinToString { it.width.toString() + "x" + it.height })
        }
        info = inf
        cameraStarted = true
        processor.configure(st.nativeConfig(inf))
        val pref = analysisPref()
        val chosen = if (pref == "max" || pref.isBlank()) inf.analysisSizes.firstOrNull()
            else inf.analysisSizes.firstOrNull { it.width.toString() + "x" + it.height == pref }
                ?: inf.analysisSizes.firstOrNull()
        if (chosen == null) { status("No analysis size"); return }
        activeAnalysis = chosen
        camera.open(inf, preview.surfaceTexture!!, chosen, st)
        resolutionBtn.text = "Resolution: " + chosen.width + "x" + chosen.height
        updateExposureLabels()
        Log.i(TAG, "camera " + inf.label + " analysis " + chosen)
        preview.post { fitPreviewToBox() }
    }

    /** Letterbox the viewfinder to the camera's own aspect instead of stretching it. */
    private fun fitPreviewToBox() {
        val inf = info ?: return
        val analysis = activeAnalysis ?: inf.analysisSizes.firstOrNull() ?: return
        val previewSize = inf.previewFor(analysis)
        val bw = previewBox.width
        val bh = previewBox.height
        if (bw <= 0 || bh <= 0) return
        val landscape = bw >= bh
        val aspect = if (landscape) {
            previewSize.width.toFloat() / previewSize.height.toFloat()
        } else {
            previewSize.height.toFloat() / previewSize.width.toFloat()
        }
        val w: Int
        val h: Int
        if (bw.toFloat() / bh > aspect) {
            h = bh
            w = (bh * aspect).toInt()
        } else {
            w = bw
            h = (bw / aspect).toInt()
        }
        preview.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        preview.requestLayout()
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
    }

    override fun onPause() {
        noiseRecorder?.let { it.stop(compute = false); noiseRecorder = null }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (rec != null) stopRecording(true)
        camera.close()
        cameraStarted = false
        super.onPause()
    }

    override fun onDestroy() {
        stopSolveForeground()
        runCatching { noiseRecorder?.stop(compute = false) }
        noiseRecorder = null
        camera.quit()
        processor.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- recording

    private fun beginRecording() {
        if (rec != null || solving) return
        val st = settings ?: return
        val inf = info
        val meta = JSONObject()
            .put("kind", "camera_imu_calibration")
            .put("board", st.board.key)
            .put("camera", inf?.label ?: "")
            .put("analysis", activeAnalysis?.let { it.width.toString() + "x" + it.height } ?: "")
            .put("focus_diopters", st.focusDiopters.toDouble())
            .put("recorded_by", "imucalib app")
            .put("duration_s", durationS)
            .toString(2)
        val r = TrackerRecorder(this, meta, gateOnScan = false, recordFrames = true,
            dirPrefix = "imu-calib-", imageEveryN = 1)
        r.start()
        rec = r
        recDir = r.sessionDir
        // AprilTag live detection: the engine only knows ChArUco, so imucalib detects the
        // Kalibr AprilGrid itself and writes the frames/points the solver consumes.
        runCatching {
            val cm = cameraModel()
            if (cm != null) {
                aprilDetector = AprilGridDetector(fx = cm.fx, fy = cm.fy, cx = cm.cx, cy = cm.cy, dist = cm.dist)
                val sessionDir = r.sessionDir
                if (sessionDir != null) aprilRecorder = AprilGridRecorder(sessionDir)
                aprilSeen = 0
                aprilFrames = 0
                processor.frameListener = { img, _ ->
                    val det = aprilDetector
                    if (det != null) {
                        val gray = det.gray(img)
                        try {
                            val f = det.detect(gray, img.timestamp)
                            aprilFrames++
                            if (f.tags > 0) aprilSeen++
                            aprilRecorder?.write(f)
                        } finally {
                            gray.release()
                        }
                    }
                }
            } else {
                status("AprilGrid unavailable: camera model not known")
            }
        }.onFailure { Log.w(TAG, "AprilGrid detector start failed", it) }
        processor.trackerRecorder = r
        recStartNs = System.nanoTime()
        recFrames = 0
        recSeen = 0
        // No periodic full-sensor stills: they are unused by the solver and add camera-still
        // requests that perturb the analysis frame timing. Old good captures had none either.
        stillSeq = 0
        status("Recording… keep the whole board in view.")
        startBtn.text = "Stop"
        solveBtn.isEnabled = false
    }

    private fun stopRecording(cancelled: Boolean) {
        val r = rec ?: return
        rec = null
        processor.frameListener = null
        processor.trackerRecorder = null
        runCatching { aprilRecorder?.close() }
        aprilRecorder = null
        stillTimer?.let { main.removeCallbacks(it) }
        stillTimer = null
        val dir = recDir
        recDir = null
        r.stop()
        startBtn.text = "Record 40 s"
        solveBtn.isEnabled = true
        val frames = r.savedFrames
        val seenPct = if (recFrames > 0) 100 * recSeen / recFrames else 0
        if (dir == null) { status("Recording failed."); return }
        val aprilPct = if (aprilFrames > 0) 100 * aprilSeen / aprilFrames else 0
        status("Recorded " + frames + " frames, board seen " + seenPct + "%" +
            (if (aprilFrames > 0) " (AprilGrid " + aprilPct + "%, " + (aprilDetector?.lastTags ?: 0) + " tags)" else "") +
            ".\nSession: " + dir.absolutePath + "\nPress Solve latest recording when ready.")
        // Solve on demand: the user decides when to spend the (long) solver run.
    }

    /** Full-sensor JPEG stills for calibration; saved beside the engine frames. */
    private fun saveCalibrationStill(still: com.rawmechatronics.easyscanner.Still) {
        val dir = recDir ?: return
        if (rec == null) return
        try {
            val f = File(dir, "still_" + String.format(Locale.US, "%05d", stillSeq) + ".jpg")
            f.writeBytes(still.jpeg)
            File(dir, "stills.csv").appendText(
                String.format(Locale.US, "%d,%d,%d,%d,%s\n", still.timestampNs, still.width, still.height, stillSeq, f.name))
            stillSeq++
        } catch (e: Throwable) {
            main.post { status("still write failed: " + e.message) }
        }
    }

    private fun solveLatest() {
        val root = File(getExternalFilesDir(null) ?: filesDir, "tracker-debug")
        val dir = root.listFiles { f -> f.isDirectory && f.name.startsWith("imu-calib-") }?.maxByOrNull { it.name }
        if (dir == null) { status("No recording yet. Press Record 40 s first."); return }
        solve(dir)
    }

    private fun solve(dir: File) {
        if (solving) return
        solving = true
        solveBtn.isEnabled = false
        status("Solving camera-IMU calibration…")
        startSolveForeground()
        Thread {
            try {
                val framesCsv = File(dir, "frames.csv")
                val imuCsv = File(dir, "imu.csv")
                val aprilFrames = File(dir, "april_frames.csv")
                val aprilPoints = File(dir, "april_points.csv")
                val useApril = aprilFrames.isFile && aprilFrames.length() > 100 &&
                    aprilPoints.isFile && aprilPoints.length() > 20
                val framesText = if (useApril) aprilFrames.readText() else framesCsv.readText()
                val bs = boardSpec(dir)
                val layout = com.rawmechatronics.imucalib.core.BoardLayout(bs.first, bs.second, bs.third)
                val cornersFile = File(dir, "corners.csv")
                val cornersText = if (cornersFile.exists()) cornersFile.readText() else null
                // AprilGrid points.csv (frame,ts_ns,x,y,z,u,v) is the dense-target
                // observation format; load it when the recording used that board.
                val pointsFile = if (useApril) aprilPoints
                    else listOf(File(dir, "points.csv"), File(dir, "april_points.csv")).firstOrNull { it.exists() }
                val pointsText = pointsFile?.readText()
                val session = CalibrationSession.fromCsv(
                    framesText, imuCsv.readText(), cornersText, layout, pointsText)
                if (session == null) {
                    ui { status("Session has no usable board poses or IMU samples."); finishSolve() }
                    return@Thread
                }
                val cm = sessionCameraModel(dir) ?: cameraModel()
                val pixelMode = session.corners.isNotEmpty() && cm != null
                val opts = if (pixelMode) {
                    // Pixel reprojection with Kalibr-style time-varying biases and the lever
                    // arm enabled: this is what turned the lever estimate from a motion-error
                    // absorber into a real measurement (within ~3 mm of Kalibr's).
                    CalibOptions(splineDt = 0.02, imuSubsample = 1, maxIterations = 200,
                        useBoardPosePrior = false, pixelSigmaPx = 0.30,
                        biasKnotsPerSecond = 0.1,
                        biasSmoothSigmaG = 1e-4, biasSmoothSigmaA = 1e-3,
                        estimateLeverArm = true, leverArmSigma = 0.02,
                        markerSigmaPx = 0.30)
                } else {
                    CalibOptions(splineDt = 0.2, imuSubsample = 1, maxIterations = 50)
                }
                val gpuBatch = if (pixelMode) runCatching { GpuPixelBatch(applicationInfo.nativeLibraryDir, File(filesDir, "gpu_eval_timing.txt")) }
                    .onFailure { Log.w(TAG, "GPU batch init failed: " + it, it) }.getOrNull() else null
                val cal = if (pixelMode) {
                    KalibrCalibrator(session.boardPoses, session.imu, opts, session.corners,
                        com.rawmechatronics.imucalib.core.PinholeModel(cm!!.fx, cm.fy, cm.cx, cm.cy,
                            cm.dist.getOrElse(0) { 0.0 }, cm.dist.getOrElse(1) { 0.0 },
                            cm.dist.getOrElse(2) { 0.0 }, cm.dist.getOrElse(3) { 0.0 }),
                        layout,
                        if (gpuBatch?.available == true) gpuBatch else null)
                } else {
                    KalibrCalibrator(session.boardPoses, session.imu, opts)
                }
                cal.progress = { iteration, cost ->
                    ui { status("Solving camera-IMU calibration… iteration " + iteration +
                        " · cost " + String.format(Locale.US, "%.0f", cost)) }
                }
                val solveStarted = System.currentTimeMillis()
                val res = cal.calibrate()
                val solveMs = System.currentTimeMillis() - solveStarted
                val out = File(getExternalFilesDir(null) ?: filesDir, "calib-output/" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())).apply { mkdirs() }
                // The board-pose timing is the mean image row of its corners; a
                // rolling-shutter sensor sees other rows at other times. Compensate
                // the solved time offset to the reference row used for the profile
                // we are matching (measured on this device: see rowTauS/rowRefPx).
                var params = res.params
                var rowNote = ""
                var boardRow = Double.NaN
                if (cm != null) {
                    val row = com.rawmechatronics.imucalib.core.BoardTiming.meanCornerRow(
                        session.boardPoses,
                        com.rawmechatronics.imucalib.core.CalibCameraModel(cm.fx, cm.fy, cm.cx, cm.cy, cm.dist),
                        bs.first, bs.second, bs.third)
                    if (row != null) {
                        boardRow = row
                        val tau = if (pixelMode) pixelRowTauS else rowTauS
                        val ref = if (pixelMode) pixelRowRefPx else rowRefPx
                        val corr = tau * (ref - row)
                        params = res.params.copy(td = res.params.td + corr)
                        rowNote = String.format(Locale.US,
                            "%s board row %.1f px -> time-offset correction %+.3f ms (tau %.4f ms/px, ref row %.1f px)\n",
                            if (pixelMode) "pixel" else "pose", row, corr * 1000, tau * 1000, ref)
                    }
                }
                val yaml = writeYaml(File(out, "kalibr_imucam_chain.yaml"), params, cm)
                var report = report(res, session, dir, yaml, params, rowNote, cm)
                File(out, "calib_report.txt").writeText(report)
                File(out, "solve_time_ms.txt").writeText(solveMs.toString())
                val exported = exportToDownloads(out.name,
                    File(out, "kalibr_imucam_chain.yaml"), File(out, "calib_report.txt"))
                if (exported != null) {
                    report += "\nExported for EasyScan:\n  " + exported + "\n" +
                        "Open EasyScan > Calibration > Load calibrations and pick the YAML there.\n"
                } else {
                    report += "\nCould not export to Download/dsh-agent; the file is in " +
                        out.absolutePath + "\n"
                }
                File(out, "calib_report.txt").writeText(report)
                        val finalReport = report
                Log.i(TAG, String.format(Locale.US,
                    "CALIB_DONE mode=%s gpu=%s solve_ms=%d poses=%d imu=%d corners=%d rot=%.4f,%.4f,%.4f pCi_mm=%.3f,%.3f,%.3f td_solved_ms=%.3f row=%.1f corr_ms=%.3f td_out_ms=%.3f board=%.3fdeg/%.3fmm gyro=%.5f accel=%.5f pixel=%.3fpx yaml=%s out=%s",
                    if (pixelMode) "pixel" else "pose",
                    if (gpuBatch?.available == true) "opencl" else "cpu",
                    solveMs,
                    session.boardPoses.size, session.imu.size, session.corners.size,
                    Math.toDegrees(params.rCi[0]), Math.toDegrees(params.rCi[1]), Math.toDegrees(params.rCi[2]),
                    params.pCi[0] * 1000, params.pCi[1] * 1000, params.pCi[2] * 1000,
                    res.params.td * 1000, boardRow, (params.td - res.params.td) * 1000, params.td * 1000,
                    res.diagnostics.rmsBoardRotDeg, res.diagnostics.rmsBoardTransMm,
                    res.diagnostics.rmsGyro, res.diagnostics.rmsAccel, res.diagnostics.rmsPixelPx, yaml, out.absolutePath))
                ui {
                    status("Calibration solved. Output: " + out.absolutePath)
                    results.text = finalReport
                    finishSolve()
                }
                if (autoFinish) main.postDelayed({ finish() }, 600)
            } catch (e: Throwable) {
                Log.e(TAG, "solve failed", e)
                ui { status("Solve failed: " + (e.message ?: e.javaClass.simpleName)) }
                finishSolve()
            }
        }.start()
    }

    private fun startSolveForeground() {
        runCatching {
            val i = Intent(this, SolveService::class.java).putExtra(
                "text", "Solving camera–IMU calibration — keep the app installed and leave the phone alone"
            )
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }.onFailure { Log.w(TAG, "foreground solve service failed", it) }
    }

    private fun stopSolveForeground() {
        runCatching { stopService(Intent(this, SolveService::class.java)) }
    }

    private fun finishSolve() {
        solving = false
        solveBtn.isEnabled = true
        stopSolveForeground()
    }

    private fun ui(block: () -> Unit) = main.post(block)

    // ---------------------------------------------------------------- outputs

    private data class CameraModel(val fx: Double, val fy: Double, val cx: Double, val cy: Double,
                                    val dist: DoubleArray, val source: String,
                                    val width: Int = 640, val height: Int = 480)

    /** Camera model of the 640x480 VIO stream, scaled from the engine's live calibration. */
    private fun cameraModel(): CameraModel? {
        feedModel?.let { return it }
        val f = File(filesDir, "live_intrinsics.json")
        if (f.exists()) {
            try {
                val j = JSONObject(f.readText())
                val w = j.optDouble("width", 0.0)
                if (w > 1.0) {
                    val analysisW = (activeAnalysis?.width ?: 640).toDouble()
                    val s = analysisW / w
                    val d = j.optJSONArray("dist")
                    val dist = DoubleArray(4) { if (d != null && it < d.length()) d.optDouble(it, 0.0) else 0.0 }
                    return CameraModel(j.optDouble("fx") * s, j.optDouble("fy") * s, j.optDouble("cx") * s,
                        j.optDouble("cy") * s, dist, if (j.optBoolean("calibrated")) "board/factory calibration" else "vendor")
                }
            } catch (_: Throwable) {}
        }
        val g = info?.guess ?: return null
        val analysisW = (activeAnalysis?.width ?: 640).toDouble()
        val s = analysisW / g.width
        val dist = DoubleArray(4) { if (it < g.dist.size) g.dist[it] else 0.0 }
        return CameraModel(g.fx * s, g.fy * s, g.cx * s, g.cy * s, dist, "vendor camera2")
    }

    /** Copy solved files to Download/dsh-agent/imu-calib/<folder>/ so EasyScan's loader can pick them. */
    private fun exportToDownloads(folder: String, vararg files: File): String? {
        val rel = Environment.DIRECTORY_DOWNLOADS + "/dsh-agent/imu-calib/" + folder
        return try {
            for (f in files) {
                if (!f.exists()) continue
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                    put(MediaStore.MediaColumns.MIME_TYPE,
                        if (f.name.endsWith(".yaml")) "text/yaml" else "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                contentResolver.openOutputStream(uri)?.use { os ->
                    f.inputStream().use { it.copyTo(os) }
                } ?: return null
            }
            "Download/dsh-agent/imu-calib/" + folder
        } catch (e: Throwable) {
            Log.w(TAG, "export to Downloads failed", e)
            null
        }
    }

    /** Board geometry from the session meta.json ("4x6/45.0/33.3/5"), default A4 4x6/45 mm. */
    private fun boardSpec(dir: File): Triple<Int, Int, Double> {
        return try {
            val f = File(dir, "meta.json")
            if (!f.exists()) return Triple(4, 6, 45.0)
            val spec = JSONObject(f.readText()).optString("board", "")
            val parts = spec.split('/')
            if (parts.size < 3) return Triple(4, 6, 45.0)
            val wh = parts[0].lowercase().split('x')
            val sx = wh.getOrNull(0)?.toIntOrNull() ?: 4
            val sy = wh.getOrNull(1)?.toIntOrNull() ?: 6
            val sq = parts[1].toDoubleOrNull() ?: 45.0
            Triple(sx, sy, sq)
        } catch (e: Throwable) {
            Triple(4, 6, 45.0)
        }
    }

    private fun parseCameraModel(json: String?): CameraModel? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val d = o.optJSONArray("dist")
            val dist = DoubleArray(4) { if (d != null && it < d.length()) d.optDouble(it, 0.0) else 0.0 }
            CameraModel(o.optDouble("fx"), o.optDouble("fy"), o.optDouble("cx"), o.optDouble("cy"), dist, "feed/intent")
        } catch (e: Throwable) {
            Log.w(TAG, "camera model extra ignored", e)
            null
        }
    }

    private fun writeYaml(file: File, p: com.rawmechatronics.imucalib.core.CalibParams,
                          camOverride: CameraModel? = null): Boolean {
        val cam = camOverride ?: cameraModel() ?: return false
        val model = com.rawmechatronics.imucalib.core.CalibCameraModel(
            cam.fx, cam.fy, cam.cx, cam.cy, cam.dist, cam.width, cam.height,
        )
        file.writeText(com.rawmechatronics.imucalib.core.KalibrYaml.imuCamChain(p, model))
        return true
    }

    private fun report(
        res: KalibrCalibrator.Result,
        session: CalibrationSession,
        dir: File,
        wroteYaml: Boolean,
        params: com.rawmechatronics.imucalib.core.CalibParams,
        rowNote: String,
        camOverride: CameraModel? = null,
    ): String {
        val p = params
        val d = res.diagnostics
        val sb = StringBuilder()
        sb.append("Camera-IMU calibration report\n\n")
        sb.append("session: ").append(dir.absolutePath).append('\n')
        sb.append("board poses: ").append(session.boardPoses.size).append('\n')
        sb.append("corner observations: ").append(session.corners.size).append('\n')
        sb.append("IMU samples: ").append(session.imu.size).append('\n')
        sb.append("camera model: ").append((camOverride ?: cameraModel())?.source ?: "none").append('\n')
        sb.append(String.format(Locale.US, "T_cam_imu rotvec (deg): %.4f %.4f %.4f\n", Math.toDegrees(p.rCi[0]), Math.toDegrees(p.rCi[1]), Math.toDegrees(p.rCi[2])))
        sb.append(String.format(Locale.US, "lever arm p_ci (mm): %.3f %.3f %.3f\n", p.pCi[0] * 1000, p.pCi[1] * 1000, p.pCi[2] * 1000))
        sb.append(String.format(Locale.US, "timeshift_cam_imu (ms): %.3f\n", p.td * 1000))
        if (rowNote.isNotEmpty()) sb.append(rowNote)
        sb.append(String.format(Locale.US, "gyro bias (rad/s): %.6f %.6f %.6f\n", p.bg[0], p.bg[1], p.bg[2]))
        sb.append(String.format(Locale.US, "accel bias (m/s^2): %.6f %.6f %.6f\n", p.ba[0], p.ba[1], p.ba[2]))
        sb.append(String.format(Locale.US, "gravity direction: %.5f %.5f %.5f\n", p.gravityDir[0], p.gravityDir[1], p.gravityDir[2]))
        sb.append("\niterations: ").append(d.iterations).append("  converged: ").append(d.converged).append('\n')
        sb.append(String.format(Locale.US, "board rms: %.3f deg / %.3f mm\n", d.rmsBoardRotDeg, d.rmsBoardTransMm))
        sb.append(String.format(Locale.US, "gyro rms: %.5f rad/s   accel rms: %.5f m/s^2\n", d.rmsGyro, d.rmsAccel))
        if (session.corners.isNotEmpty()) {
            sb.append(String.format(Locale.US, "pixel rms: %.3f px\n", d.rmsPixelPx))
        }
        sb.append("yaml written: ").append(wroteYaml).append('\n')
        sb.append("\nLoad into EasyScan: Calibration > Load calibrations, then pick the\n")
        sb.append("kalibr_imucam_chain.yaml exported under Download/dsh-agent/imu-calib/.\n")
        return sb.toString()
    }

    // ---------------------------------------------------------------- callbacks

    override fun onResult(r: FrameResult) {
        if (rec == null) return
        recFrames++
        if (r.flag(FrameResult.H.DETECTED) || (aprilDetector?.lastTags ?: 0) > 0) recSeen++
        val now = System.nanoTime()
        if (now - lastUiNs > 100_000_000L) {
            lastUiNs = now
            val elapsed = (now - recStartNs) / 1e9
            val pct = (100 * recSeen / recFrames.coerceAtLeast(1))
            ui {
                status(String.format(Locale.US, "Recording %.0f / %.0f s · board seen %d%%\n%s",
                    elapsed, durationS, pct, phase(elapsed)))
            }
            if (elapsed >= durationS && rec != null) ui { stopRecording(false) }
        }
    }

    private fun phase(elapsed: Double): String = when {
        elapsed < 3 -> "hold still"
        elapsed < 12 -> "rotate left and right (yaw), board in view"
        elapsed < 21 -> "tilt up and down (pitch)"
        elapsed < 31 -> "roll the phone in your hand"
        elapsed < 38 -> "hold still again"
        else -> "finishing"
    }

    override fun onMessages(json: String) {}
    override fun onPhotoDue() {}
    override fun onError(msg: String) { ui { status("error: " + msg) } }

    private fun status(text: String) {
        status.text = text
    }

    private companion object {
        const val TAG = "ImuCalib"
        val PI = 0.0 // unused placeholder to keep imports stable
    }
}