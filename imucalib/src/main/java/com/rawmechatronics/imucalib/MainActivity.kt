
package com.rawmechatronics.imucalib

import android.Manifest
import android.app.Activity
import android.content.ContentValues
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
    private val main = Handler(Looper.getMainLooper())
    private var activeAnalysis: Size? = null
    private var stillSeq = 0
    private var stillTimer: Runnable? = null

    private var settings: AppSettings? = null
    private var info: CameraInfo? = null
    private var cameraStarted = false

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
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(startBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(solveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)
        val scroll = ScrollView(this)
        scroll.addView(results)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.4f))
        setContentView(root)
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

    private fun openCamera() {
        if (cameraStarted || !preview.isAvailable) return
        val st = settings ?: AppSettings.load(this).also { settings = it }
        val ids = CameraInfo.backCameraIds(mgr)
        if (ids.isEmpty()) { status("No back camera"); return }
        val inf = CameraInfo.query(mgr, st.cameraId.takeIf { it in ids } ?: ids.first(), st.targetFps, st.maxLongSide)
        runCatching {
            File(filesDir, "camera_info.txt").writeText(
                inf.label + "\n" +
                "analysis=" + inf.analysisSizes.take(6).joinToString { it.width.toString() + "x" + it.height } + "\n" +
                "stills=" + inf.stillSizes.take(8).joinToString { it.width.toString() + "x" + it.height })
        }
        info = inf
        cameraStarted = true
        processor.configure(st.nativeConfig(inf))
        activeAnalysis = inf.analysisSizes.firstOrNull()
        camera.open(inf, preview.surfaceTexture!!, inf.analysisSizes.first(), st)
        Log.i(TAG, "camera " + inf.label + " analysis " + inf.analysisSizes.first())
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
        if (rec != null) stopRecording(true)
        camera.close()
        cameraStarted = false
        super.onPause()
    }

    override fun onDestroy() {
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
        processor.trackerRecorder = r
        recStartNs = System.nanoTime()
        recFrames = 0
        recSeen = 0
        stillSeq = 0
        val topStill = info?.stillSizes?.firstOrNull()
        if (topStill != null) {
            camera.setStills(listOf(topStill))
            val tick = object : Runnable {
                override fun run() {
                    if (rec == null) return
                    camera.captureStill()
                    main.postDelayed(this, 500)
                }
            }
            stillTimer = tick
            main.postDelayed(tick, 800)
            status("Recording… sensor stills at " + topStill.width + "x" + topStill.height)
        } else {
            status("Recording… keep the whole board in view.")
        }
        startBtn.text = "Stop"
        solveBtn.isEnabled = false
    }

    private fun stopRecording(cancelled: Boolean) {
        val r = rec ?: return
        rec = null
        processor.trackerRecorder = null
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
        status("Recorded " + frames + " frames, board seen " + seenPct + "%.\nSession: " + dir.absolutePath)
        if (cancelled) return
        solve(dir)
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
        Thread {
            try {
                val framesCsv = File(dir, "frames.csv")
                val imuCsv = File(dir, "imu.csv")
                val bs = boardSpec(dir)
                val layout = com.rawmechatronics.imucalib.core.BoardLayout(bs.first, bs.second, bs.third)
                val cornersFile = File(dir, "corners.csv")
                val cornersText = if (cornersFile.exists()) cornersFile.readText() else null
                // AprilGrid points.csv (frame,ts_ns,x,y,z,u,v) is the dense-target
                // observation format; load it when the recording used that board.
                val pointsFile = listOf(File(dir, "points.csv"), File(dir, "april_points.csv"))
                    .firstOrNull { it.exists() }
                val pointsText = pointsFile?.readText()
                val session = CalibrationSession.fromCsv(
                    framesCsv.readText(), imuCsv.readText(), cornersText, layout, pointsText)
                if (session == null) {
                    ui { status("Session has no usable board poses or IMU samples."); finishSolve() }
                    return@Thread
                }
                val cm = cameraModel()
                val pixelMode = session.corners.isNotEmpty() && cm != null
                val opts = if (pixelMode) {
                    // Pixel reprojection with Kalibr-style time-varying biases and the lever
                    // arm enabled: this is what turned the lever estimate from a motion-error
                    // absorber into a real measurement (within ~3 mm of Kalibr's).
                    CalibOptions(splineDt = 0.02, imuSubsample = 4, maxIterations = 100,
                        useBoardPosePrior = false, pixelSigmaPx = 0.30,
                        biasKnotsPerSecond = 0.1,
                        biasSmoothSigmaG = 1e-4, biasSmoothSigmaA = 1e-3,
                        estimateLeverArm = true, leverArmSigma = 0.02,
                        markerSigmaPx = 0.30)
                } else {
                    CalibOptions(splineDt = 0.2, imuSubsample = 8, maxIterations = 20)
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
                val yaml = writeYaml(File(out, "kalibr_imucam_chain.yaml"), params)
                var report = report(res, session, dir, yaml, params, rowNote)
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

    private fun finishSolve() {
        solving = false
        solveBtn.isEnabled = true
    }

    private fun ui(block: () -> Unit) = main.post(block)

    // ---------------------------------------------------------------- outputs

    private data class CameraModel(val fx: Double, val fy: Double, val cx: Double, val cy: Double, val dist: DoubleArray, val source: String)

    /** Camera model of the 640x480 VIO stream, scaled from the engine's live calibration. */
    private fun cameraModel(): CameraModel? {
        feedModel?.let { return it }
        val f = File(filesDir, "live_intrinsics.json")
        if (f.exists()) {
            try {
                val j = JSONObject(f.readText())
                val w = j.optDouble("width", 0.0)
                if (w > 1.0) {
                    val s = 640.0 / w
                    val d = j.optJSONArray("dist")
                    val dist = DoubleArray(4) { if (d != null && it < d.length()) d.optDouble(it, 0.0) else 0.0 }
                    return CameraModel(j.optDouble("fx") * s, j.optDouble("fy") * s, j.optDouble("cx") * s,
                        j.optDouble("cy") * s, dist, if (j.optBoolean("calibrated")) "board/factory calibration" else "vendor")
                }
            } catch (_: Throwable) {}
        }
        val g = info?.guess ?: return null
        val s = 640.0 / g.width
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

    private fun writeYaml(file: File, p: com.rawmechatronics.imucalib.core.CalibParams): Boolean {
        val cam = cameraModel() ?: return false
        val model = com.rawmechatronics.imucalib.core.CalibCameraModel(
            cam.fx, cam.fy, cam.cx, cam.cy, cam.dist,
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
    ): String {
        val p = params
        val d = res.diagnostics
        val sb = StringBuilder()
        sb.append("Camera-IMU calibration report\n\n")
        sb.append("session: ").append(dir.absolutePath).append('\n')
        sb.append("board poses: ").append(session.boardPoses.size).append('\n')
        sb.append("corner observations: ").append(session.corners.size).append('\n')
        sb.append("IMU samples: ").append(session.imu.size).append('\n')
        sb.append("camera model: ").append(cameraModel()?.source ?: "none").append('\n')
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
        if (r.flag(FrameResult.H.DETECTED)) recSeen++
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