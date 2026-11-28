package com.rawmechatronics.easyscanner

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.rawmechatronics.easyscanner.FrameResult.Calib
import com.rawmechatronics.easyscanner.FrameResult.Ev
import com.rawmechatronics.easyscanner.FrameResult.H
import com.rawmechatronics.easyscanner.FrameResult.Photo
import com.rawmechatronics.easyscanner.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * The scanner screen: live preview + pose overlay + HUD under one of two
 * panels — the guided steps (board → focus → camera → laser plane → scan →
 * saved), or in expert mode the desktop prototype's four modes: viewfinder,
 * camera (intrinsics) calibration, laser-plane calibration and scanning.
 */
class MainActivity : AppCompatActivity(), FrameProcessor.Listener {

    private enum class Mode { VIEW, CAMERA, LASER, SCAN }

    /** One button of the guided panel. */
    private class Act(val label: String, val enabled: Boolean = true, val run: () -> Unit)

    private lateinit var b: ActivityMainBinding
    private lateinit var mgr: CameraManager
    private lateinit var processor: FrameProcessor
    private lateinit var camera: CameraController
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var info: CameraInfo? = null
    /** Analysis stream size chosen for this camera session (manifest metadata). */
    private var analysisSize: android.util.Size? = null
    @Volatile private var governor: ResolutionGovernor? = null
    private var settings: AppSettings? = null
    private var mode = Mode.VIEW
    private var messages = JSONObject()
    /** Focus the saved camera calibration was taken at, diopters (NaN = unknown). */
    private var kFocusDiopters = Double.NaN
    /** The working-distance guard paused the scan; resume automatically when back in range. */
    private var rangePausedByApp = false
    private var lastRangeCmdMs = 0L
    private val fps = FpsMeter()
    private val hudPending = AtomicBoolean(false)

    // last header values the buttons depend on (UI thread)
    private var calibActive = false
    private var planeActive = false
    private var scanOn = false

    /** The open photo session's directory (set on the UI thread, read on the still thread). */
    @Volatile private var photoDir: File? = null
    /**
     * The serial this side gave its photo session. The core echoes the serial
     * of the last session it opened, so "off" with OUR serial means the core
     * closed it, and results from before the session started are told apart.
     */
    private var photoSerial = 0

    private val wizard = Wizard()
    private var readiness = Readiness(boardConfirmed = true, focusLocked = false, calibrated = true, planeReady = false)
    private val stepViews = ArrayList<TextView>()
    private var acts: List<Act?> = emptyList()
    /** The last copy to Download, for the Saved step, and the session it is of. */
    private var copied: SessionExport.Copied? = null
    private var exporting: File? = null
    private var copyNote = ""
    /** Which capture tab produced the current Step.DONE result. */
    private var lastCaptureKind: Step = Step.FUSION
    /** Active high-res Splat-only capture session (null when idle). */
    @Volatile private var splatSession: SplatCaptureSession? = null
    private var splatFinishedDir: File? = null
    private var splatFinishedCount = 0
    private var splatFinishedResolution = ""
    /** Result of direct Laser-Only PLY + STL export for Step.DONE actions. */
    private var laserOnlyExportResult: LaserOnlyExporter.Result? = null
    private var pendingCaptureStep: Step = Step.FUSION
    private var splatLaserSuppressed = false
    /** Unsaved sessions are offered once per process. */
    private var pendingOffered = false

    private val expert get() = settings?.expertMode == true

    // on a grant the activity resumes next, and onResume opens the camera
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) status(getString(R.string.need_camera))
    }

    /** The camera was (re)opened for the current resume; reset by onPause. */
    private var cameraStarted = false
    /** Offline tracker development recorder (Settings -> Development). */
    private var trackerRecorder: TrackerRecorder? = null
    private var hybridRecorder: TrackerRecorder? = null
    /** Marker-free pose source (OpenVINS + SuperPoint on the NPU). */
    @Volatile private var vio: VioRunner? = null
    /** Live DA3/MVS coarse mesh + real-time metric laser detail refinement. */
    private lateinit var liveMesh: LiveMeshController
    private val liveMeshRangeGate = CaptureRangeGate()
    private var livePoseMode: LiveMeshController.PoseMode = LiveMeshController.PoseMode.VIO
    private var fusionPreviewExpanded = false
    private var lastIntrinsicsMtime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mgr = getSystemService(CameraManager::class.java)
        settings = AppSettings.load(this)
        // Seed calibrated focus immediately from live_intrinsics.json so the focus-linked range
        // gate is anchored right from frame #1 before native messages arrive.
        runCatching {
            val f = File(filesDir, "live_intrinsics.json")
            if (f.isFile) {
                val d = JSONObject(f.readText()).optDouble("focus_diopters", Double.NaN)
                if (d.isFinite() && d > 0.01) kFocusDiopters = d
            }
        }
        // camera's factory calibration as the starting model (no board camera step needed)
        VendorIntrinsics.ensure(this, settings!!)
        processor = FrameProcessor(filesDir.absolutePath, this)
        syncRangeSettings()
        camera = CameraController(
            mgr, processor.handler, processor,
            onError = { msg -> main.post { onError(msg) } },
            onSizeFailed = { size -> main.post { fallBackFrom(size) } },
            onStill = ::onStill,
            onStillFailed = { msg, fatal -> main.post { onStillFailed(msg, fatal) } },
        )
        b.overlay.source = processor.results
        liveMesh = LiveMeshController(
            this,
            b.liveMeshView,
            onStatus = { text -> b.liveMeshStatus.text = text },
            onMeshVisible = { visible ->
                b.liveMeshView.visibility = if (visible && liveMesh.active) View.VISIBLE else View.GONE
            },
        )
        processor.liveMeshListener = { img, r, vp -> liveMesh.onFrame(img, r, vp) }
        b.liveMeshLaserBtn.setOnClickListener { toggleLiveMeshLaser() }
        b.liveMeshSourceBtn.setOnClickListener { toggleLiveMeshSource() }
        b.liveMeshPoseBtn.setOnClickListener { toggleLivePoseMode() }
        b.liveMeshShadeBtn.setOnClickListener { cycleLiveMeshShade() }
        b.liveMeshTsdfBtn.setOnClickListener { toggleLiveMeshTsdf() }
        b.liveMeshViewBtn.setOnClickListener { toggleLiveMeshViewMode() }
        b.liveMeshAutoBtn.setOnClickListener { toggleLiveMeshAuto() }
        b.liveMeshAddPatchBtn.setOnClickListener { liveMesh.requestAddPatch() }
        liveMesh.vioSpeedProvider = { vio?.lastSpeedMps ?: 0.0 }
        liveMesh.featureProvider = { vio?.lastFeaturePacked }
        b.liveMeshReseedBtn.setOnClickListener { resetLiveMeshAndVioIfDiverged() }
        b.liveMeshSaveBtn.setOnClickListener { saveLiveMesh() }
        b.liveMeshCloseBtn.setOnClickListener { stopLiveMesh() }
        b.liveMeshDistPill.setOnClickListener { showRangeDialog() }
        b.fusionPreviewExpandBtn.setOnClickListener { toggleFusionPreviewExpanded() }
        lastIntrinsicsMtime = File(filesDir, "live_intrinsics.json").lastModified()
        ensureVio()

        b.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.libraryButton.setOnClickListener { startActivity(Intent(this, LibraryActivity::class.java)) }
        b.rangeCard.setOnClickListener { showRangeDialog() }
        b.modes.check(R.id.modeView)
        b.modes.addOnButtonCheckedListener { _, id, checked ->
            if (checked && expert) setMode(
                when (id) {
                    R.id.modeCamera -> Mode.CAMERA
                    R.id.modeLaser -> Mode.LASER
                    R.id.modeScan -> Mode.SCAN
                    else -> Mode.VIEW
                }
            )
        }
        for (s in STEPS) {
            val v = TextView(this).apply {
                gravity = Gravity.CENTER
                setPadding(2, 6, 2, 6)
                textSize = 11.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener { openStep(s) }
            }
            b.steps.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            stepViews.add(v)
        }
        listOf(b.action1, b.action2, b.action3, b.action4).forEachIndexed { i, btn ->
            btn.setOnClickListener { onAction(i) }
        }
        b.action4.setOnLongClickListener {
            if (!expert || mode != Mode.SCAN || photoDir == null) return@setOnLongClickListener false
            processor.command(Cmd.PHOTO_SNAP) { renderStatus() }
            true
        }
        b.texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startCamera()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        applyInterface()
        setMode(Mode.VIEW)
        b.stepTitle.setOnLongClickListener { toggleVioPoints(); true }
        handleCalibCaptureIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_VIO_TUNE, false) == true) {
            intent.removeExtra(EXTRA_VIO_TUNE)
            main.postDelayed({ showVioTunePanel() }, 800L)
        }
        val rootForFit = findViewById<android.view.View>(R.id.root)
        rootForFit.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreviewToScreen() }
        rootForFit.post { fitPreviewToScreen() }
    }

    /** Size the 3:4 preview to the room left above the panel (unfolded tablets/phones). */
    private fun fitPreviewToScreen() {
        val root = findViewById<android.view.View>(R.id.root) ?: return
        val frame = findViewById<android.view.View>(R.id.previewFrame) ?: return
        val panel = findViewById<android.view.View>(R.id.panel) ?: return
        val wAvail = root.width - root.paddingLeft - root.paddingRight
        var panelH = panel.height
        if (panelH <= 0) panelH = (root.height * 0.45f).toInt()   // first pass: panel not measured yet
        val hAvail = root.height - root.paddingTop - root.paddingBottom - panelH
        if (wAvail <= 0 || hAvail <= 0) return
        val s = minOf(wAvail / 3f, hAvail / 4f)
        val w = (3 * s).toInt().coerceAtLeast(1)
        val h = (4 * s).toInt().coerceAtLeast(1)
        val lp = frame.layoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            frame.layoutParams = lp
            android.util.Log.i("EasyScanner", "fitPreview sizes root=" + root.width + "x" + root.height +
                    " panelH=" + panel.height + " availH=" + hAvail + " preview=" + w + "x" + h)
        }
    }

    override fun onResume() {
        super.onResume()
        b.liveMeshView.onResume()
        b.fusionPreviewView.onResume()
        ensureVio()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            askCamera.launch(Manifest.permission.CAMERA)
        } else {
            startCamera()
        }
    }

    override fun onPause() {
        // the scan session stays open and carries on after the resume (unless the
        // settings change the board or the camera); its cloud goes to disk now,
        // in case the process does not come back
        if (photoDir != null) processor.command(Cmd.CHECKPOINT)
        if (captureActive) cancelCapture()
        warmupActive = false
        warmupTicker?.let { main.removeCallbacks(it) }
        registerStillSensor(false)
        stopTrackerRecorder()
        camera.close()
        cameraStarted = false
        // Do not carry a stale VIO across a pause: the IMU would integrate the whole background gap
        // into the next frame. Tear it down; onResume creates a fresh one.
        if (liveMesh.active) stopLiveMesh()
        splatSession?.let {
            it.paused = true
            it.onStillFailed()
        }
        processor.splatAutoCaptureHook = null
        b.liveMeshView.onPause()
        b.fusionPreviewView.onPause()
        stopVio()
        pendingScanStart = false
        super.onPause()
    }

    override fun onDestroy() {
        processor.liveMeshListener = null
        processor.fusionPreviewListener = null
        processor.splatAutoCaptureHook = null
        liveMesh.release()
        stopVio()
        camera.quit()
        processor.release()
        super.onDestroy()
    }

    /** Start the marker-free pose source on the processing thread (also registers IMU there). */
    private fun ensureVio(onReady: (() -> Unit)? = null) {
        if (vio != null && vio?.released != true) { onReady?.invoke(); return }
        processor.handler.post {
            if (vio != null && vio?.released != true) { main.post { onReady?.invoke() }; return@post }
            try {
                val v = VioRunner(this)
                v.start(processor.handler)
                vio = v
                processor.vio = v
                b.overlay.vio = v
                Log.i(TAG, "VIO ready")
                main.post { onReady?.invoke() }
            } catch (e: Throwable) {
                Log.e(TAG, "VIO start failed", e)
                main.post { onReady?.invoke() }
            }
        }
    }

    private var calibStop: Runnable? = null
    private var captureReturnToCalib = false

    private fun handleCalibCaptureIntent(intent: android.content.Intent?) {
        val kind = intent?.getStringExtra(EXTRA_CALIB_CAPTURE) ?: return
        intent.removeExtra(EXTRA_CALIB_CAPTURE)
        captureReturnToCalib = true
        // Wait until the camera is open AND frames are actually being processed: a capture that
        // starts before the pipeline runs records an empty session.
        var tries = 0
        var baseline = -1
        val wait = object : Runnable {
            override fun run() {
                if (captureActive) return
                if (info == null || !cameraStarted) {
                    if (++tries < 100) main.postDelayed(this, 100L) else status("Calibration capture: camera did not start")
                    return
                }
                val n = processor.framesProcessed
                if (baseline < 0) baseline = n
                if (n - baseline < 5) {
                    if (++tries < 100) main.postDelayed(this, 100L)
                    else status("Calibration capture: engine is not processing frames")
                    return
                }
                // warm-up first: nothing starts before the warm-up has completed
                if (vio != null && vio?.warmupDone != true) {
                    if (vio?.tracking != true) vio?.startTrackingSilent()
                    if (!warmupActive) startWarmup()
                    status("Calibration: warm-up first — hold still, then move gently")
                    if (++tries < 900) main.postDelayed(this, 100L)
                    else status("Calibration capture: warm-up did not complete — capture aborted")
                    return
                }
                if (kind == "board") {
                    val r = processor.results.latest()
                    val boardPose = r?.flag(FrameResult.H.DETECTED) == true &&
                            !r!!.get(FrameResult.H.CAM_X).isNaN() && !r!!.get(FrameResult.H.CAM_Z).isNaN()
                    val vioPose = vio?.initialized == true
                    if (!boardPose || !vioPose) {
                        android.util.Log.i("EasyScanner", "calib board: waiting boardPose=" + boardPose + " vioPose=" + vioPose)
                        status(if (!boardPose) "Calibration: point the camera at the board — waiting for board pose"
                               else "Calibration: waiting for VIO pose (hold still, then move gently)")
                        if (++tries < 600) main.postDelayed(this, 100L)
                        else status("Calibration capture: pose tracking did not start — capture aborted")
                        return
                    }
                }
                android.util.Log.i("EasyScanner", "calib capture starting: kind=" + kind + " frames=" + (n - baseline))
                startCalibRecording(board = kind == "board")
            }
        }
        main.postDelayed(wait, 200L)
    }

    override fun onBackPressed() {
        if (captureReturnToCalib) {
            captureReturnToCalib = false
            startActivity(android.content.Intent(this, CalibrationActivity::class.java))
            return
        }
        super.onBackPressed()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCalibCaptureIntent(intent)
        if (intent.getBooleanExtra(EXTRA_VIO_TUNE, false)) {
            intent.removeExtra(EXTRA_VIO_TUNE)
            main.postDelayed({ showVioTunePanel() }, 400L)
        }
    }

    /** Record a fixed-length calibration session (still IMU or board motion). */
    private fun startCalibRecording(board: Boolean, seconds: Int = 30) {
        if (captureActive) return
        calibStop?.let { main.removeCallbacks(it) }
        stopTrackerRecorder()
        val meta = org.json.JSONObject()
            .put("calib_mode", if (board) "board" else "still")
            .put("duration_s", seconds)
            .toString(2)
        val rec = TrackerRecorder(this, meta, gateOnScan = false, recordFrames = board)
        trackerRecorder = rec
        processor.trackerRecorder = rec
        rec.start()
        captureActive = true
        captureBoard = board
        captureTargetS = seconds.toDouble()
        captureStartNs = System.nanoTime()
        captureLastNs = captureStartNs
        captureQuietS = 0.0
        captureSeen = 0
        captureFrames = 0
        gyroMag = 0f
        if (!board) registerStillSensor(true)
        val tick = object : Runnable {
            override fun run() {
                if (!captureActive) return
                val now = System.nanoTime()
                val dt = (now - captureLastNs) / 1e9
                captureLastNs = now
                val elapsed = (now - captureStartNs) / 1e9
                if (!captureBoard) {
                    if (gyroMag < 0.06f) captureQuietS += dt
                } else {
                    processor.results.latest()?.let { r ->
                        captureFrames++
                        if (r.flag(FrameResult.H.DETECTED)) captureSeen++
                    }
                }
                val progress = if (captureBoard) elapsed else captureQuietS
                val pct = (100 * progress / captureTargetS).toInt().coerceIn(0, 100)
                val seenPct = if (captureFrames > 0) 100 * captureSeen / captureFrames else 0
                val head = if (captureBoard) "BOARD CALIBRATION" else "STILL CALIBRATION"
                val hint = when {
                    !captureBoard && gyroMag >= 0.06f -> "move detected - hold the phone still"
                    !captureBoard -> "keep the phone down"
                    seenPct < 30 -> "point at the board so it is fully visible"
                    else -> "hold still 1 s, then move slowly in 3D"
                }
                val line2 = if (captureBoard)
                    "%.1f / %.0f s   board seen %d%%".format(elapsed, captureTargetS, seenPct)
                else "%.1f / %.0f s quiet".format(captureQuietS, captureTargetS)
                banner(head + "\n" + line2 + "\n" + hint + "\n" + pct + "%")
                val done = progress >= captureTargetS
                val hardCap = elapsed >= captureTargetS * 2.5
                if (done || hardCap) finishCapture() else main.postDelayed(this, 100)
            }
        }
        captureTicker = tick
        banner((if (board) "BOARD CALIBRATION" else "STILL CALIBRATION") + "\nstarting...")
        main.postDelayed(tick, 100)
    }

    private var vioTuneDialog: AlertDialog? = null
    private var vioTuneTicker: Runnable? = null

    /**
     * Manual camera-IMU timing step: path/net distance live from the VIO, and a
     * single time-offset slider. OpenVINS reads the offset from its YAML at
     * startup, so applying restarts the filter without closing the camera.
     */
    private fun showVioTunePanel() {
        if (vioTuneDialog?.isShowing == true) return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), px(4))
        }
        val help = TextView(this).apply {
            textSize = 13f
            text = "Move the phone and compare path/net distance with a measured distance. " +
                "Time offset mainly affects fast rotation; adjust it there, then move again."
        }
        col.addView(help)
        val distance = TextView(this).apply {
            textSize = 16f
            setPadding(0, px(12), 0, px(8))
        }
        col.addView(distance)
        val offsetText = TextView(this).apply { textSize = 14f; setPadding(0, px(8), 0, 0) }
        col.addView(offsetText)
        val current = VioTuning.readMs(this).coerceIn(-50.0, 50.0)
        val bar = SeekBar(this).apply {
            max = 100
            progress = (current + 50.0).roundToInt()
        }
        fun offsetMs() = bar.progress - 50.0
        fun label() { offsetText.text = "Camera-IMU time offset: %+.1f ms".format(offsetMs()) }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = label()
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        label()
        col.addView(bar)
        val apply = Button(this).apply {
            text = "Apply and restart VIO"
            setOnClickListener {
                if (!VioTuning.writeMs(this@MainActivity, offsetMs())) {
                    status("No VIO config yet: start tracking once, then retry")
                    return@setOnClickListener
                }
                status("Time offset %+.1f ms applied; VIO restarting — hold still".format(offsetMs()))
                vioTuneDialog?.dismiss()
                restartVioForTuning()
            }
        }
        col.addView(apply)
        val zero = Button(this).apply {
            text = "Set to 0 ms"
            setOnClickListener {
                bar.progress = 50
                status("Slider set to 0 ms; press Apply to use it")
            }
        }
        col.addView(zero)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Manual VIO calibration")
            .setView(col)
            .setNegativeButton("Close") { _, _ -> }
            .create()
        val tick = object : Runnable {
            override fun run() {
                val v = vio
                distance.text = if (v == null || !v.initialized) {
                    "VIO not tracking yet"
                } else {
                    "Path %.2f m   ·   net %.2f m\nSpeed %.2f m/s".format(
                        v.traveledMeters(), v.netMeters(), v.velocityNorm())
                }
                if (dlg.isShowing) main.postDelayed(this, 200L)
            }
        }
        dlg.setOnDismissListener {
            vioTuneTicker?.let { main.removeCallbacks(it) }
            vioTuneTicker = null
            vioTuneDialog = null
        }
        vioTuneDialog = dlg
        vioTuneTicker = tick
        dlg.show()
        main.post(tick)
    }

    private fun restartVioForTuning() {
        warmupActive = false
        warmupTicker?.let { main.removeCallbacks(it) }
        stopVio()
        main.postDelayed({
            ensureVio {
                val v = vio ?: return@ensureVio
                v.warmupDone = false
                v.startTrackingSilent()
                startWarmup()
            }
        }, 300L)
    }

    private fun toggleVioTracking() {
        val v = vio ?: return
        v.tracking = !v.tracking
        if (!v.tracking) {
            v.spOverlay = false
            warmupActive = false
            warmupTicker?.let { main.removeCallbacks(it) }
            registerStillSensor(false)
            banner(null)
            status("VIO tracking stopped")
        } else {
            startWarmup()
            status("VIO tracking started")
        }
    }

    /** The warm-up IS the OpenVINS static initialisation: still -> move -> initialized = tracking. */
    private fun startWarmup() {
        val v = vio ?: return
        if (v.initialized) {
            v.warmupDone = true
            if (pendingScanStart) { pendingScanStart = false; performScanStart() }
            return
        }
        warmupActive = true
        vio?.warmupDone = false
        if (v.tracking != true) v.startTrackingSilent()
        val warmupStartMs = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                if (!warmupActive || vio?.tracking != true) return
                val init = v.initialized
                val ready = v.initReadyToMove()
                val elapsedMs = System.currentTimeMillis() - warmupStartMs
                val line = when {
                    init -> "TRACKING - the minimap moves when you move"
                    ready -> "now move & tilt smoothly (>10 deg) to initialise"
                    elapsedMs >= 1500L -> "move & tilt phone smoothly (>10 deg) or rest still 1 s"
                    else -> "hold still 1 s, then move & tilt smoothly"
                }
                banner("VIO INIT\n" + line)
                if (init) {
                    v.warmupDone = true
                    warmupActive = false
                    banner("VIO TRACKING\nminimap movement is the tracking indicator")
                    if (pendingScanStart) {
                        pendingScanStart = false
                        when (pendingCaptureStep) {
                            Step.LASER_ONLY -> performScanStart(laserOnly = true)
                            else -> performScanStart(laserOnly = false)
                        }
                    }
                    main.postDelayed({ if (vio?.tracking == true && !captureActive) banner(null) }, 3000)
                    return
                }
                main.postDelayed(this, 100)
            }
        }
        warmupTicker?.let { main.removeCallbacks(it) }
        warmupTicker = tick
        main.postDelayed(tick, 100)
    }

    private fun toggleVioPoints() {
        val v = vio ?: return
        v.spOverlay = !v.spOverlay
        status(if (v.spOverlay) "SuperPoint overlay on" else "SuperPoint overlay off")
    }

    private fun setFusionPreviewVisible(visible: Boolean, reset: Boolean = false) {
        if (reset) b.fusionPreviewView.clearPreview()
        if (visible) {
            b.overlay.hideMinimap = true
            b.fusionPreviewCard.visibility = View.VISIBLE
            processor.fusionPreviewListener = { cap, live, pose, kf ->
                b.fusionPreviewView.pushFrame(cap, live, pose, kf)
            }
            applyFusionPreviewLayout()
        } else {
            processor.fusionPreviewListener = null
            fusionPreviewExpanded = false
            b.fusionPreviewCard.visibility = View.GONE
            b.overlay.hideMinimap = false
        }
    }

    private fun toggleFusionPreviewExpanded() {
        if (b.fusionPreviewCard.visibility != View.VISIBLE) return
        fusionPreviewExpanded = !fusionPreviewExpanded
        applyFusionPreviewLayout()
        renderWizard()
    }

    private fun applyFusionPreviewLayout() {
        val lp = b.fusionPreviewCard.layoutParams as android.widget.FrameLayout.LayoutParams
        val dp = resources.displayMetrics.density
        if (fusionPreviewExpanded) {
            lp.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = android.view.Gravity.FILL
            lp.setMargins(0, 0, 0, 0)
            b.fusionPreviewExpandBtn.text = "⤡"
        } else {
            val sz = (172 * dp).roundToInt()
            val m = (10 * dp).roundToInt()
            lp.width = sz
            lp.height = sz
            lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            lp.setMargins(m, m, m, m)
            b.fusionPreviewExpandBtn.text = "⤢"
        }
        b.fusionPreviewCard.layoutParams = lp
        b.fusionPreviewView.setFullscreenPointScale(fusionPreviewExpanded)
    }

    private fun updateFusionPreviewBadge() {
        if (b.fusionPreviewCard.visibility != View.VISIBLE) return
        val splat = splatSession
        if (splat != null) {
            val res = if (splat.lastWidth > 0) " · ${splat.lastWidth}×${splat.lastHeight}" else ""
            b.fusionPreviewBadge.setIfChanged("${splat.savedCount} photos$res")
            return
        }
        val pts = b.fusionPreviewView.totalCapturedPoints
        val kfs = b.fusionPreviewView.totalKeyframes
        val ptsLabel = if (pts >= 1000) "%.1fk".format(Locale.US, pts / 1000f) else pts.toString()
        val text = if (lastCaptureKind == Step.LASER_ONLY) "$ptsLabel pts" else "$ptsLabel pts · $kfs kf"
        b.fusionPreviewBadge.setIfChanged(text)
    }

    private fun startLiveMesh() {
        setFusionPreviewVisible(false)
        if (livePoseMode == LiveMeshController.PoseMode.VIO && vio?.initialized != true) {
            vio?.let { v ->
                v.tracking = true
                startWarmup()
            }
        }
        // Keep the GL surface hidden until the first mesh is ready so the live camera,
        // laser overlay, 3D minimap, and VIO INIT banner stay completely visible.
        b.liveMeshView.visibility = View.GONE
        b.liveMeshBar.visibility = View.VISIBLE
        b.liveMeshDistPill.visibility = View.VISIBLE
        liveMeshRangeGate.reset()
        updateLiveMeshButtons()
        liveMesh.nominalDepthM = (focusTargetMm()?.div(1000.0) ?: 0.50).coerceIn(0.25, 1.50)
        liveMesh.start(livePoseMode)
        renderButtons()
    }

    private fun stopLiveMesh() {
        liveMesh.stop()
        liveMeshRangeGate.reset()
        b.liveMeshView.visibility = View.GONE
        b.liveMeshBar.visibility = View.GONE
        b.liveMeshDistPill.visibility = View.GONE
        renderButtons()
    }

    private fun toggleLivePoseMode() {
        livePoseMode = if (livePoseMode == LiveMeshController.PoseMode.VIO) {
            LiveMeshController.PoseMode.BOARD
        } else {
            LiveMeshController.PoseMode.VIO
        }
        if (livePoseMode == LiveMeshController.PoseMode.VIO && liveMesh.active && vio?.initialized != true) {
            vio?.let { v ->
                v.tracking = true
                startWarmup()
            }
        }
        liveMesh.setPoseMode(livePoseMode)
        updateLiveMeshButtons()
        renderButtons()
        status("Live pose source: " + livePoseMode.label)
    }

    private fun cycleLiveMeshShade() {
        val modes = LiveMeshView.ShadeMode.values()
        b.liveMeshView.shadeMode = modes[(b.liveMeshView.shadeMode.ordinal + 1) % modes.size]
        updateLiveMeshButtons()
    }

    private fun toggleLiveMeshViewMode() {
        b.liveMeshView.viewMode = if (b.liveMeshView.viewMode == LiveMeshView.ViewMode.ORBIT) {
            LiveMeshView.ViewMode.AR_OVERLAY
        } else {
            LiveMeshView.ViewMode.ORBIT
        }
        updateLiveMeshButtons()
    }

    private fun resetLiveMeshAndVioIfDiverged() {
        if (livePoseMode == LiveMeshController.PoseMode.VIO && (vio?.lastSpeedMps ?: 0.0) > 1.0) {
            stopVio()
            ensureVio {
                vio?.let { v ->
                    v.tracking = true
                    startWarmup()
                }
            }
        }
        liveMesh.resetMap()
    }

    private fun toggleLiveMeshAuto() {
        liveMesh.autoPatch = !liveMesh.autoPatch
        updateLiveMeshButtons()
    }

    private fun toggleLiveMeshTsdf() {
        liveMesh.setMeshMode(!liveMesh.useTsdf)
        updateLiveMeshButtons()
    }

    private fun toggleLiveMeshLaser() {
        val next = !laserEnabled()
        prefs().edit().putBoolean("laser_enabled", next).apply()
        applySettingsLive()
        updateLiveMeshButtons()
        renderButtons()
        status(if (next) "Laser detection ON" else "Laser detection OFF")
    }

    private fun toggleLiveMeshSource() {
        val next = if (liveMesh.depthSource == LiveMeshController.DepthSource.MVS) {
            LiveMeshController.DepthSource.DA3
        } else {
            LiveMeshController.DepthSource.MVS
        }
        liveMesh.setDepthSource(next)
        updateLiveMeshButtons()
        status("Rough mesh source: " + next.label)
    }

    private fun updateLiveMeshButtons() {
        val isDa3 = liveMesh.depthSource == LiveMeshController.DepthSource.DA3
        b.liveMeshLaserBtn.text = if (laserEnabled()) "Laser: ON" else "Laser: OFF"
        b.liveMeshSourceBtn.text = liveMesh.depthSource.label
        b.liveMeshPoseBtn.text = "Pose: " + livePoseMode.label
        b.liveMeshShadeBtn.text = b.liveMeshView.shadeMode.label
        b.liveMeshTsdfBtn.text = if (liveMesh.useTsdf) "TSDF" else "Patch"
        b.liveMeshTsdfBtn.visibility = if (isDa3) View.VISIBLE else View.GONE
        b.liveMeshViewBtn.text = b.liveMeshView.viewMode.label
        b.liveMeshAutoBtn.text = if (liveMesh.autoPatch) "Auto: ON" else "Auto: OFF"
        b.liveMeshAutoBtn.visibility = if (isDa3) View.VISIBLE else View.GONE
        b.liveMeshAddPatchBtn.visibility = if (isDa3) View.VISIBLE else View.GONE
    }

    private fun saveLiveMesh() {
        b.liveMeshStatus.text = "Saving refined mesh PLY…"
        liveMesh.saveMeshPly { file, msg ->
            b.liveMeshStatus.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            if (file != null) shareScan(file)
        }
    }

    private fun stopVio() {
        val v = vio ?: return
        vio = null
        processor.vio = null
        processor.handler.post { v.release() }
    }

    /** (Re)read the settings and (re)open the camera with them. */
    private fun startCamera() {
        if (cameraStarted || !b.texture.isAvailable || !hasCameraPermission()) return
        cameraStarted = true
        val st = AppSettings.load(this)
        settings = st
        syncRangeSettings()
        applyInterface()
        val ids = CameraInfo.backCameraIds(mgr)
        if (ids.isEmpty()) {
            status(getString(R.string.no_camera))
            return
        }
        val inf = CameraInfo.query(mgr, st.cameraId.takeIf { it in ids } ?: ids.first(), st.targetFps, st.maxLongSide)
        info = inf
        analysisSize = inf.analysisSizes.firstOrNull()
        val pv = inf.previewSize
        val portrait = inf.sensorOrientation % 180 != 0
        (b.previewFrame.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio =
            if (portrait) "${pv.height}:${pv.width}" else "${pv.width}:${pv.height}"
        b.previewFrame.requestLayout()
        b.overlay.sensorOrientation = inf.sensorOrientation
        liveMesh.sensorOrientation = inf.sensorOrientation

        processor.configure(st.nativeConfig(inf))
        val sizes = inf.analysisSizes
        // Resolution is the user's choice: no automatic switching at all.
        governor = null
        camera.open(inf, b.texture.surfaceTexture!!, sizes.first(), st)
        startTrackerRecorder(st, inf, sizes.first())
        renderButtons()
        offerPending()
    }

    /** Start the offline tracker recorder when the Development switch is on. */
    private fun startTrackerRecorder(st: AppSettings, inf: CameraInfo, size: android.util.Size) {
        if (!st.trackerDebug) return
        stopTrackerRecorder()
        val meta = org.json.JSONObject()
            .put("camera", inf.label)
            .put("camera_id", inf.id)
            .put("sensor_orientation", inf.sensorOrientation)
            .put("analysis", size.width.toString() + "x" + size.height)
            .put("max_long_side", st.maxLongSide)
            .put("target_fps", st.targetFps)
            .put("manual_focus", st.manualFocus)
            .put("focus_diopters", st.focusDiopters.toDouble())
            .put("manual_exposure", st.manualExposure)
            .put("exposure_us", st.exposureUs)
            .put("iso", st.iso)
            .put("native_config", org.json.JSONObject(st.nativeConfig(inf)))
            .toString(2)
        val rec = TrackerRecorder(this, meta)
        trackerRecorder = rec
        processor.trackerRecorder = rec
        rec.start()
        Toast.makeText(this, "Tracker debug recording: " + (rec.sessionDir?.absolutePath ?: "?"), Toast.LENGTH_LONG).show()
    }

    private fun stopTrackerRecorder() {
        val rec = trackerRecorder ?: return
        processor.trackerRecorder = null
        trackerRecorder = null
        rec.stop()
        Toast.makeText(this, "Tracker debug: " + rec.savedFrames + " frames, " + rec.droppedFrames + " dropped, " + (rec.sessionDir?.absolutePath ?: ""), Toast.LENGTH_LONG).show()
    }

    /** The camera refused [size]: continue one size smaller and never retry it. */
    private fun fallBackFrom(size: android.util.Size) {
        val sizes = info?.analysisSizes ?: return
        val next = sizes.indexOf(size) + 1
        if (next <= 0 || next > sizes.lastIndex) {
            onError("camera session failed at ${size.width}x${size.height}")
            return
        }
        status("camera refused ${size.width}x${size.height}, using ${sizes[next].width}x${sizes[next].height}")
        processor.handler.post { governor?.limitTo(next, System.nanoTime()) }
        analysisSize = sizes[next]
        camera.setAnalysisSize(sizes[next])
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Settings changed here (focus lock, board, laser): reload them and hand them on. */
    private fun applySettingsLive() {
        val st = AppSettings.load(this)
        settings = st
        val inSplat = !expert && wizard.step == Step.SPLAT
        camera.forceAutofocus = inSplat
        syncRangeSettings()
        camera.applySettings(st)
        processor.configure(nativeConfigForCurrentStep(st, inSplat))
        splatLaserSuppressed = inSplat
    }

    private fun nativeConfigForCurrentStep(st: AppSettings, suppressLaser: Boolean): String {
        val raw = st.nativeConfig(info)
        if (!suppressLaser) return raw
        return runCatching {
            val j = JSONObject(raw)
            j.optJSONObject("laser")?.put("enabled", false)
            j.toString()
        }.getOrDefault(raw)
    }

    /** Push calibrated focus distance ± tolerance into FrameProcessor's live capture gate. */
    private fun syncRangeSettings() {
        val st = settings ?: return
        val inSplat = !expert && wizard.step == Step.SPLAT
        processor.applyRangeSettings(
            st,
            focusTargetMm() ?: 380.0,
            laserEnabled = !inSplat && laserEnabled(),
        )
    }

    // ── processing-thread callbacks ──────────────────────────────────────────

    override fun onResult(r: FrameResult) {
        fps.tick(r.timestampNs)
        governor?.let { g ->
            val photos = r[H.PHOTO_STATE].toInt()
            // one size per calibration solve, and per photo session (its stills share one K)
            g.frozen = r.flag(H.CALIB_ACTIVE) || r[H.CALIB_STATE].toInt() == Calib.SOLVING ||
                (photos != Photo.OFF && photos != Photo.NO_PHOTOS)
            val from = g.size
            // frames still in flight from before a switch say nothing about the new size
            if (r[H.FRAME_W].toInt() != from.width || r[H.FRAME_H].toInt() != from.height) return@let
            g.onFrame(r.procMs, System.nanoTime())?.let { idx ->
                info?.analysisSizes?.getOrNull(idx)?.let { size ->
                    Log.i(TAG, "resolution $from -> ${size.width}x${size.height}")
                    camera.setAnalysisSize(size)
                }
            }
        }
        if (r.events() and (Ev.INTRINSICS_SAVED or Ev.PLANE_SAVED) != 0) {
            // a re-done calibration step is complete: the guided flow moves on
            main.post { wizard.completed(currentReadiness()) }
        }
        b.overlay.postInvalidateOnAnimation()
        if (hudPending.compareAndSet(false, true)) {
            main.postDelayed({
                hudPending.set(false)
                renderHud()
            }, HUD_PERIOD_MS)
        }
    }

    override fun onPhotoDue() {
        camera.captureStill()
    }

    /** A still is back (still thread): route to active Splat session or Rust core photo session. */
    private fun onStill(s: Still) {
        val splat = splatSession
        if (splat != null) {
            try {
                val pose16 = processor.lastVioPose16
                val entry = splat.recordStill(s, pose16)
                val pose = pose16?.let { RigidPose.fromRowMajor16(it, 1.0) }
                if (entry != null && pose != null) {
                    b.fusionPreviewView.pushFrame(null, null, pose, isKeyframe = true)
                }
                main.post {
                    if (!isFinishing && !isDestroyed) {
                        updateFusionPreviewBadge()
                        renderWizard()
                    }
                }
            } catch (e: IOException) {
                splat.onStillFailed()
                main.post { status("Splat photo not written: ${e.message}") }
            }
            return
        }
        val file = photoDir?.let { File(it, "photos/.pending_${s.timestampNs}.jpg") } ?: return
        try {
            file.writeBytes(s.jpeg)
        } catch (e: IOException) {
            main.post { onStillFailed("photo not written: ${e.message}", false) }
            return
        }
        val arg = JSONObject().put("file", file.absolutePath).put("ts", s.timestampNs)
            .put("w", s.width).put("h", s.height)
        processor.command(Cmd.PHOTO_ADD, arg.toString())
    }

    /**
     * A still did not happen: the core backs off (and stops the photos after a
     * few); when none is possible at all the session carries on without photos.
     */
    private fun onStillFailed(msg: String, fatal: Boolean) {
        val splat = splatSession
        if (splat != null) {
            splat.onStillFailed()
            status(msg)
            if (fatal) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                splat.paused = true
                renderWizard()
            }
            return
        }
        if (photoDir == null) return
        status(msg)
        if (fatal) {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            processor.command(Cmd.PHOTOS_OFF, msg) { renderStatus() }
            camera.setStills(emptyList())
        } else {
            processor.command(Cmd.PHOTO_FAILED, msg) { renderStatus() }
        }
    }

    override fun onMessages(json: String) {
        messages = runCatching { JSONObject(json) }.getOrDefault(messages)
        val kf = messages.optDouble("k_focus", Double.NaN)
        if (kf.isFinite() && kf > 0.01) kFocusDiopters = kf
        syncRangeSettings()
        renderStatus()
    }

    override fun onError(msg: String) {
        status(msg)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ── HUD / buttons ────────────────────────────────────────────────────────

    private fun renderHud() {
        val r = processor.results.latest() ?: return
        fun f(slot: Int) = r[slot]
        fun has(slot: Int) = !f(slot).isNaN()
        val sb = StringBuilder()
        sb.append("%.0fx%.0f  %.1f fps  proc %.1f ms (board %.1f, laser %.1f)\n".format(
            f(H.FRAME_W), f(H.FRAME_H), fps.fps, r.procMs, f(H.T_BOARD_MS), f(H.T_LASER_MS)))
        sb.append(governor?.let { "auto: ${it.status}" } ?: "fixed resolution").append('\n')
        if (r.flag(H.DETECTED)) {
            sb.append("board %d pts  rms %.2f px  margin %s  focus %s\n".format(
                f(H.N_CORNERS).toInt(), f(H.REPROJ_RMS),
                if (has(H.POSE_MARGIN)) "%.1fx".format(f(H.POSE_MARGIN)) else "-",
                if (has(H.FOCUS_PCT)) "%.0f%%".format(f(H.FOCUS_PCT)) else "-"))
            sb.append("cam X %.1f  Y %.1f  Z %.1f mm  d %.0f\n".format(
                f(H.CAM_X), f(H.CAM_Y), f(H.CAM_Z), f(H.DISTANCE)))
            sb.append("    roll %.1f  pitch %.1f  yaw %.1f deg\n".format(f(H.ROLL), f(H.PITCH), f(H.YAW)))
        } else {
            sb.append("board: not found (%d corners, %d markers)\n".format(f(H.N_CORNERS).toInt(), f(H.N_MARKERS).toInt()))
        }
        sb.append("K: %s  fx %.0f cx %.0f cy %.0f\n".format(
            messages.optString("intrinsics", "?"), f(H.FX), f(H.CX), f(H.CY)))
        when (f(H.LASER_STATE).toInt()) {
            0 -> sb.append("laser: off")
            1 -> sb.append("laser: none (peak %.0f)".format(f(H.LASER_PEAK)))
            else -> {
                sb.append("laser: %d pts  peak %.0f".format(f(H.LASER_POINTS).toInt(), f(H.LASER_PEAK)))
                if (has(H.LASER_LENGTH)) sb.append("  on board %.0f mm @ %.0f deg".format(f(H.LASER_LENGTH), f(H.LASER_ANGLE)))
                if (f(H.IN_VOLUME) > 0f) sb.append("  in volume %d".format(f(H.IN_VOLUME).toInt()))
            }
        }
        sb.append('\n')
        val plane = when {
            r.flag(H.PLANE_OK) -> "ok"
            r.flag(H.PLANE_STALE) -> "STALE (camera re-calibrated)"
            else -> "none"
        }
        sb.append("plane: %s  cloud %d pts %.1f MB".format(plane, f(H.CLOUD_POINTS).toInt(), f(H.CLOUD_MB)))
        val photoState = f(H.PHOTO_STATE).toInt()
        if (photoState != Photo.OFF) {
            sb.append("\nphotos %d  %s".format(f(H.PHOTO_COUNT).toInt(), photoStateLabel(photoState)))
            if (has(H.CAM_SPEED)) sb.append("  moving %.0f mm/s %.1f deg/s".format(f(H.CAM_SPEED), f(H.CAM_TURN)))
        }
        // the core closed our session (board, camera or calibration changed, the laser plane re-fitted)
        val closedByCore = photoState == Photo.OFF && photoDir != null && f(H.PHOTO_SERIAL).toInt() == photoSerial
        if (expert) when (mode) {
            Mode.CAMERA -> sb.append("\ncalib %d/%d views  last rms %s".format(
                f(H.CALIB_COUNT).toInt(), f(H.CALIB_TARGET).toInt(),
                if (has(H.CALIB_RMS)) "%.2f px".format(f(H.CALIB_RMS)) else "-"))
            Mode.LASER -> sb.append("\nlines %d/%d  spread %.2f  last fit %s".format(
                f(H.PLANE_LINES).toInt(), f(H.PLANE_TARGET).toInt(), f(H.PLANE_SPREAD),
                if (has(H.PLANE_RMS)) "%.2f mm".format(f(H.PLANE_RMS)) else "-"))
            else -> {}
        }
        b.hud.text = sb
        updateRangeHud(r)

        // every read of r is above: what follows can render, which takes a newer frame
        val newCalib = r.flag(H.CALIB_ACTIVE)
        val newPlane = r.flag(H.PLANE_ACTIVE)
        val newScan = r.flag(H.SCAN_ON)
        if (closedByCore) {
            val dir = photoDir
            endPhotos()
            // its files are kept: hand them to Download right away rather than at the next start
            dir?.let { exportClosed(it) }
        }
        if (newCalib != calibActive || newPlane != planeActive || newScan != scanOn) {
            val calibJustEnded = calibActive && !newCalib
            calibActive = newCalib
            planeActive = newPlane
            scanOn = newScan
            // If a new camera calibration was saved, restart VIO so OpenVINS syncs the updated K & dist.
            if (calibJustEnded) {
                val mt = File(filesDir, "live_intrinsics.json").lastModified()
                if (mt > 0L && mt != lastIntrinsicsMtime) {
                    lastIntrinsicsMtime = mt
                    val wasTracking = vio?.tracking == true
                    stopVio()
                    ensureVio {
                        if (wasTracking) vio?.tracking = true
                    }
                    Log.i(TAG, "Reloaded VIO with updated live_intrinsics.json")
                }
            }
            renderButtons()
        }
        if (!expert) renderWizard()
    }

    /**
     * Live working-distance HUD & capture range gate.
     *
     * Measures distance directly from freehand LASER_CAM 3D points (falling back to board
     * distance when no laser is present). When the capture gate is enabled during a scan,
     * out-of-range frames are dropped in FrameProcessor and board cloud accumulation pauses
     * until the camera returns inside [captureMinCm..captureMaxCm].
     */
    private fun updateRangeHud(r: FrameResult) {
        val st = settings ?: return
        val ev = processor.lastRangeEval
        val targetMm = focusTargetMm() ?: 380.0
        val targetCm = (targetMm / 10.0).roundToInt()
        val tolCm = st.captureToleranceCm
        val minCm = (targetCm - tolCm).coerceAtLeast(8)
        val maxCm = targetCm + tolCm
        val gateOn = st.captureRangeEnabled && (laserEnabled() || ev.source == "board")
        val distCm = if (ev.distanceMm.isFinite()) (ev.distanceMm / 10.0).roundToInt() else -1
        val activeCapture = processor.recordingActive || scanOn || rangePausedByApp
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val inStartGrace = nowMs < scanStartGraceUntilMs && !ev.distanceMm.isFinite()

        // Auto-pause when out of focus range and automatically resume as soon as back in range.
        if (gateOn && activeCapture && !inStartGrace) {
            if (!ev.allowCapture) {
                if (!rangePausedByApp || (scanOn && nowMs - lastRangeCmdMs >= 300L)) {
                    rangePausedByApp = true
                    lastRangeCmdMs = nowMs
                    if (scanOn) processor.command(Cmd.SCAN_STOP)
                }
            } else {
                if (rangePausedByApp || (processor.recordingActive && !scanOn && nowMs - lastRangeCmdMs >= 300L)) {
                    rangePausedByApp = false
                    lastRangeCmdMs = nowMs
                    if (!scanOn) processor.command(Cmd.SCAN_START)
                }
            }
        } else if (rangePausedByApp && (!gateOn || inStartGrace)) {
            rangePausedByApp = false
            lastRangeCmdMs = nowMs
            if (!scanOn) processor.command(Cmd.SCAN_START)
        }

        val card = b.rangeCard
        val banner = b.rangeBanner
        val sub = b.rangeSub
        val gauge = b.rangeGauge

        // Hide the Scan range card during calibration or Live Mesh; Live Mesh shows its compact red/green dot pill.
        if (liveMesh.active) {
            card.visibility = View.GONE
            updateLiveMeshDistanceDot(targetMm, tolCm, ev)
            return
        }
        b.liveMeshDistPill.visibility = View.GONE
        updateFusionPreviewBadge()
        if (!expert && wizard.step != Step.FUSION && wizard.step != Step.LASER_ONLY && wizard.step != Step.FOCUS) {
            card.visibility = View.GONE
            return
        }
        val showCard = activeCapture || distCm > 0 || gateOn
        if (!showCard) {
            card.visibility = View.GONE
            return
        }

        val green = Color.parseColor("#22C55E")
        val red = Color.parseColor("#EF4444")
        val amber = Color.parseColor("#F59E0B")
        val slate = Color.parseColor("#94A3B8")

        val accent: Int
        val titleText: String
        when (ev.status) {
            CaptureRangeGate.Status.IN_RANGE -> {
                accent = green
                titleText = if (activeCapture) "● CAPTURING · $distCm cm" else "● IN RANGE · $distCm cm"
            }
            CaptureRangeGate.Status.TOO_CLOSE -> {
                accent = red
                titleText = if (activeCapture && gateOn) {
                    "◀ TOO CLOSE · $distCm cm · PAUSED"
                } else {
                    "◀ TOO CLOSE · $distCm cm (min $minCm)"
                }
            }
            CaptureRangeGate.Status.TOO_FAR -> {
                accent = red
                titleText = if (activeCapture && gateOn) {
                    "▶ TOO FAR · $distCm cm · PAUSED"
                } else {
                    "▶ TOO FAR · $distCm cm (max $maxCm)"
                }
            }
            CaptureRangeGate.Status.UNKNOWN -> {
                accent = if (activeCapture && gateOn && !ev.allowCapture) amber else slate
                titleText = when {
                    activeCapture && gateOn && !ev.allowCapture -> "○ NO TARGET · PAUSED"
                    activeCapture -> "● CAPTURING · aiming…"
                    else -> "Focus $targetCm cm ±$tolCm cm"
                }
            }
        }

        val skipped = processor.rangeSkippedFrames
        val subText = buildString {
            append(if (gateOn) "Focus $targetCm ±$tolCm cm ($minCm–$maxCm)" else "Gate off · Focus $targetCm ±$tolCm cm")
            if (activeCapture && skipped > 0) append(" · $skipped skipped")
            append(" · tap")
        }

        card.visibility = View.VISIBLE
        banner.text = titleText
        banner.setTextColor(accent)
        sub.text = subText

        if (distCm > 0) {
            gauge.visibility = View.VISIBLE
            // Center the gauge on calibrated focus (50% = targetCm; 0% = target - 2*tol; 100% = target + 2*tol).
            val halfSpan = (tolCm * 2.0).coerceAtLeast(10.0)
            val norm = 0.5 + (distCm - targetCm) / (2.0 * halfSpan)
            gauge.progress = (norm * 100.0).roundToInt().coerceIn(2, 100)
            gauge.progressTintList = android.content.res.ColorStateList.valueOf(accent)
        } else {
            gauge.visibility = View.GONE
        }
    }

    /**
     * Compact red-green-red focus distance dot shown during Live Mesh (works with laser ON or OFF
     * by falling back from laser depth to live TSDF center-raycast depth).
     */
    private fun updateLiveMeshDistanceDot(targetMm: Double, tolCm: Int, laserEval: CaptureRangeGate.Eval) {
        val tolMm = tolCm * 10.0
        val minMm = (targetMm - tolMm).coerceAtLeast(80.0)
        val maxMm = targetMm + tolMm
        val rawMm = when {
            laserEval.distanceMm.isFinite() -> laserEval.distanceMm
            liveMesh.liveCenterDepthMm.isFinite() -> liveMesh.liveCenterDepthMm
            else -> Double.NaN
        }
        val src = if (laserEval.distanceMm.isFinite()) laserEval.source else "mesh"
        val ev = liveMeshRangeGate.evaluateDistance(rawMm, src, gateEnabled = true, minMm = minMm, maxMm = maxMm)
        val distCm = if (ev.distanceMm.isFinite()) (ev.distanceMm / 10.0).roundToInt() else -1
        val targetCm = (targetMm / 10.0).roundToInt()

        val dotColor: Int
        val label: String
        when (ev.status) {
            CaptureRangeGate.Status.IN_RANGE -> {
                dotColor = Color.parseColor("#22C55E") // bright green: optimal focus distance
                label = "$distCm cm"
            }
            CaptureRangeGate.Status.TOO_CLOSE -> {
                dotColor = Color.parseColor("#EF4444") // bright red: too close
                label = "Too close · $distCm cm"
            }
            CaptureRangeGate.Status.TOO_FAR -> {
                dotColor = Color.parseColor("#EF4444") // bright red: too far
                label = "Too far · $distCm cm"
            }
            CaptureRangeGate.Status.UNKNOWN -> {
                dotColor = Color.parseColor("#64748B") // neutral grey until surface measured
                label = "Focus $targetCm ±$tolCm cm"
            }
        }
        val dotBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(dotColor)
            setStroke((1.5f * resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.argb(120, 255, 255, 255))
        }
        b.liveMeshDistPill.visibility = View.VISIBLE
        b.liveMeshDistDot.background = dotBg
        b.liveMeshDistText.text = label
    }

    /** Quick modal to adjust the ± tolerance around the calibrated focus distance. */
    private fun showRangeDialog() {
        val st = settings ?: return
        var enabled = st.captureRangeEnabled
        var tolCm = st.captureToleranceCm
        val targetMm = focusTargetMm() ?: 380.0
        val targetCm = (targetMm / 10.0).roundToInt()
        val targetDiopters = 1000.0 / targetMm

        val pad = (18 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
        }
        val focusHeader = TextView(this).apply {
            text = "Calibrated focus: $targetCm cm (%.1f D)".format(targetDiopters)
            textSize = 14f
            setPadding(0, 0, 0, pad / 2)
        }
        val toggle = android.widget.Switch(this).apply {
            text = "Pause capture outside focus band"
            isChecked = enabled
        }
        val tolLabel = TextView(this).apply {
            setPadding(0, pad / 2, 0, 4)
        }
        val tolSeek = android.widget.SeekBar(this).apply {
            max = 36 // 4..40 cm
            progress = (tolCm - 4).coerceIn(0, 36)
        }
        val note = TextView(this).apply {
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
        }
        fun syncLabels() {
            val lo = (targetCm - tolCm).coerceAtLeast(8)
            val hi = targetCm + tolCm
            tolLabel.text = "Allowed tolerance: ±$tolCm cm  ($lo–$hi cm)"
            note.text = if (enabled) {
                "Captures only when the 7-frame smoothed distance is within $lo–$hi cm."
            } else {
                "Gate disabled — all distances are recorded (indicator stays visible)."
            }
        }
        toggle.setOnCheckedChangeListener { _, checked ->
            enabled = checked
            syncLabels()
        }
        tolSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                tolCm = 4 + p
                syncLabels()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        syncLabels()
        layout.addView(focusHeader)
        layout.addView(toggle)
        layout.addView(tolLabel)
        layout.addView(tolSeek)
        layout.addView(note)

        AlertDialog.Builder(this)
            .setTitle("Focus capture range")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                AppSettings.saveCaptureRange(this, enabled, tolCm)
                applySettingsLive()
                renderWizard()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Distance the lens was focused at, mm: the saved calibration focus first, then the lock. */
    private fun focusTargetMm(): Double? {
        val fromCalib = kFocusDiopters
        if (fromCalib.isFinite() && fromCalib > 0.01) return 1000.0 / fromCalib
        val st = settings ?: return null
        if (st.manualFocus && st.focusDiopters > 0.01f) return 1000.0 / st.focusDiopters.toDouble()
        return null
    }

    private fun photoStateLabel(state: Int) = when (state) {
        Photo.WAITING -> "waiting"
        Photo.IN_FLIGHT -> "taking..."
        Photo.HOLD_NO_BOARD -> "due: no board"
        Photo.HOLD_POSE_RMS -> "due: pose rms high"
        Photo.HOLD_FEW_CORNERS -> "due: too few board corners"
        Photo.HOLD_SETTLING -> "due: measuring motion"
        Photo.HOLD_MOVING -> "due: hold steady"
        Photo.NO_PHOTOS -> "scan only"
        else -> "?"
    }

    /**
     * [keepSession]: leaving Scan only pauses the scan and saves its cloud (the
     * guided flow comes back to the same session); otherwise the session ends.
     */
    private fun setMode(m: Mode, keepSession: Boolean = false) {
        // leaving a mode stops what it was doing
        when (mode) {
            Mode.CAMERA -> if (m != Mode.CAMERA && calibCollecting()) processor.command(Cmd.CALIB_STOP)
            Mode.LASER -> if (m != Mode.LASER && planeActive) processor.command(Cmd.PLANE_STOP)
            Mode.SCAN -> if (m != Mode.SCAN) {
                if (keepSession) {
                    if (scanOn) processor.command(Cmd.SCAN_STOP)
                } else {
                    if (scanOn) processor.command(Cmd.SCAN_STOP)
                    if (photoDir != null) stopPhotos()
                }
            }
            Mode.VIEW -> {}
        }
        mode = m
        renderButtons()
        renderStatus()
    }

    private fun calibCollecting() = calibActive

    private fun laserEnabled() = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("laser_enabled", true)

    /** Guided steps or the expert mode toggle, as the settings say. */
    private fun applyInterface() {
        val e = expert
        b.steps.visibility = if (e) View.GONE else View.VISIBLE
        b.stepTitle.visibility = if (e) View.GONE else View.VISIBLE
        b.modes.visibility = if (e) View.VISIBLE else View.GONE
        if (e) {
            b.modes.check(
                when (mode) {
                    Mode.VIEW -> R.id.modeView
                    Mode.CAMERA -> R.id.modeCamera
                    Mode.LASER -> R.id.modeLaser
                    Mode.SCAN -> R.id.modeScan
                }
            )
        }
        renderButtons()
        renderStatus()
    }

    private fun renderButtons() {
        if (!expert) {
            renderWizard()
            return
        }
        val labels: List<String?> = when (mode) {
            Mode.VIEW -> listOf(
                getString(if (laserEnabled()) R.string.act_laser_on else R.string.act_laser_off),
                if (vio?.tracking == true) "Stop VIO" else "Track VIO",
                if (liveMesh.active) "Close Mesh" else "Live Mesh",
                "Pose: " + livePoseMode.label,
            )
            Mode.CAMERA -> listOf(
                getString(if (calibCollecting()) R.string.act_pause else R.string.act_start),
                getString(R.string.act_capture), getString(R.string.act_reset), getString(R.string.act_forget_k),
            )
            Mode.LASER -> listOf(
                getString(if (planeActive) R.string.act_pause else R.string.act_start),
                getString(R.string.act_force), getString(R.string.act_forget_plane), null,
            )
            Mode.SCAN -> listOf(
                getString(if (scanOn) R.string.act_stop else R.string.act_start),
                getString(R.string.act_export), getString(R.string.act_clear),
                getString(if (photoDir != null) R.string.act_photos_on else R.string.act_photos_off),
            )
        }
        listOf<Button>(b.action1, b.action2, b.action3, b.action4).zip(labels).forEach { (btn, label) ->
            btn.visibility = if (label == null) View.INVISIBLE else View.VISIBLE
            btn.isEnabled = true
            btn.text = label ?: ""
        }
    }

    private fun renderStatus() {
        if (!expert) {
            renderWizard()
            return
        }
        val (hint, key) = when (mode) {
            Mode.VIEW -> R.string.hint_view to "intrinsics"
            Mode.CAMERA -> R.string.hint_camera to "calib"
            Mode.LASER -> R.string.hint_laser to "plane"
            Mode.SCAN -> R.string.hint_scan to "scan"
        }
        val keys = if (mode == Mode.SCAN) listOf(key, "photos") else listOf(key)
        val msg = keys.map { messages.optString(it, "") }.filter { it.isNotEmpty() }.joinToString("\n")
        status(if (msg.isEmpty()) getString(hint) else "$msg\n\n${getString(hint)}")
    }

    // ---- guided capture / warm-up feedback ----
    private var captureActive = false
    private var captureBoard = false
    private var captureStartNs = 0L
    private var captureLastNs = 0L
    private var captureTargetS = 30.0
    private var captureQuietS = 0.0
    private var captureSeen = 0
    private var captureFrames = 0
    private var captureTicker: Runnable? = null
    private var warmupActive = false
    private var warmupGatherS = 0.0
    private var pendingScanStart = false
    private var warmupTicker: Runnable? = null
    private var gyroMag = 0f
    private var stillSensors: android.hardware.SensorManager? = null
    private var stillGyroListener: android.hardware.SensorEventListener? = null

    private fun banner(text: String?) {
        val b = findViewById<android.widget.TextView>(R.id.captureBanner) ?: return
        if (text == null) {
            b.visibility = android.view.View.GONE
        } else {
            b.text = text
            b.visibility = android.view.View.VISIBLE
        }
    }

    private fun registerStillSensor(on: Boolean) {
        stillGyroListener?.let { l -> stillSensors?.unregisterListener(l) }
        stillGyroListener = null
        if (!on) return
        val sm = getSystemService(android.hardware.SensorManager::class.java) ?: return
        stillSensors = sm
        val l = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val m = kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                gyroMag = 0.8f * gyroMag + 0.2f * m
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        stillGyroListener = l
        sm.registerListener(l, sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE),
            android.hardware.SensorManager.SENSOR_DELAY_GAME)
    }

    private fun cancelCapture() {
        captureActive = false
        captureTicker?.let { main.removeCallbacks(it) }
        captureTicker = null
        registerStillSensor(false)
        banner(null)
    }

    private fun finishCapture() {
        if (!captureActive) return
        val elapsed = (System.nanoTime() - captureStartNs) / 1e9
        val boardMode = captureBoard
        val quiet = captureQuietS
        val seenPct = if (captureFrames > 0) 100 * captureSeen / captureFrames else 0
        cancelCapture()
        stopTrackerRecorder()
        val msg = if (boardMode)
            "BOARD CAPTURE DONE\n%.1f s recorded · board seen %d%%\nNow: Settings > Calibration > Solve & save".format(elapsed, seenPct)
        else
            "STILL CAPTURE DONE\n%.1f s of quiet recorded\nNow do the board capture".format(quiet)
        banner(msg)
        main.postDelayed({ if (!captureActive) banner(null) }, 6000)
        if (captureReturnToCalib) {
            banner(msg + "\n\npress back to return to Calibration")
        }
        status(if (boardMode) "Board calibration saved (%.1f s, board %d%%)".format(elapsed, seenPct)
               else "Still calibration saved (%.1f s quiet)".format(quiet))
    }

    private fun status(text: String) {
        b.status.text = text
    }

    private fun onAction(i: Int) {
        if (!expert) {
            acts.getOrNull(i)?.takeIf { it.enabled }?.run?.invoke()
            renderWizard()
            return
        }
        val show: (String) -> Unit = { renderStatus() }
        when (mode) {
            Mode.VIEW -> when (i) {
                0 -> {
                    val p = PreferenceManager.getDefaultSharedPreferences(this)
                    p.edit().putBoolean("laser_enabled", !laserEnabled()).apply()
                    applySettingsLive()
                    renderButtons()
                }
                1 -> { toggleVioTracking(); renderButtons() }
                2 -> { if (liveMesh.active) stopLiveMesh() else startLiveMesh(); renderButtons() }
                3 -> { toggleLivePoseMode(); renderButtons() }
            }
            Mode.CAMERA -> when (i) {
                0 -> processor.command(if (calibCollecting()) Cmd.CALIB_STOP else Cmd.CALIB_START, done = show)
                1 -> processor.command(Cmd.CALIB_CAPTURE, done = show)
                2 -> processor.command(Cmd.CALIB_RESET, done = show)
                3 -> processor.command(Cmd.FORGET_INTRINSICS, done = show)
            }
            Mode.LASER -> when (i) {
                0 -> processor.command(if (planeActive) Cmd.PLANE_STOP else Cmd.PLANE_START, done = show)
                1 -> processor.command(Cmd.PLANE_FORCE, done = show)
                2 -> processor.command(Cmd.FORGET_PLANE, done = show)
            }
            Mode.SCAN -> when (i) {
                0 -> processor.command(if (scanOn) Cmd.SCAN_STOP else Cmd.SCAN_START, done = show)
                1 -> {
                    val dir = File(getExternalFilesDir(null), "scans").apply { mkdirs() }
                    val name = "scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".ply"
                    val file = File(dir, name)
                    processor.command(Cmd.EXPORT_PLY, file.absolutePath) { msg ->
                        renderStatus()
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        if (msg.startsWith("exported")) shareScan(file)   // engine.rs export_ply
                    }
                }
                2 -> processor.command(Cmd.CLOUD_CLEAR, done = show)
                3 -> if (photoDir == null) startPhotos() else stopPhotos()
            }
        }
    }

    // ── guided steps ─────────────────────────────────────────────────────────

    private fun readinessOf(r: FrameResult): Readiness {
        val st = settings
        return Readiness(
            // Board & camera calibration steps are removed from the guided strip; saved
            // live_intrinsics.json + vendor intrinsics handle camera geometry automatically.
            boardConfirmed = true,
            focusLocked = info?.manualFocus != true || st?.manualFocus == true,
            calibrated = true,
            planeReady = r.flag(H.PLANE_OK),
        )
    }

    private fun currentReadiness(): Readiness {
        processor.results.latest()?.let { readiness = readinessOf(it) }
        return readiness
    }

    private fun modeFor(s: Step) = when (s) {
        Step.CAMERA -> Mode.CAMERA
        Step.LASER -> Mode.LASER
        Step.FUSION, Step.LASER_ONLY -> Mode.SCAN
        Step.BOARD, Step.FOCUS, Step.SPLAT, Step.DONE -> Mode.VIEW
    }

    private fun openStep(s: Step) {
        if (expert) return
        if ((photoDir != null || splatSession != null) && s != wizard.step) {
            Toast.makeText(this, "Finish or Discard the active capture first", Toast.LENGTH_SHORT).show()
            return
        }
        val rd = currentReadiness()
        if (!wizard.open(s, rd)) {
            Toast.makeText(this, getString(R.string.step_locked, getString(stepLabel(rd.firstOpen))), Toast.LENGTH_SHORT).show()
        }
        syncSplatCameraMode()
        renderWizard()
    }

    private fun syncSplatCameraMode() {
        val st = settings ?: return
        val inSplat = !expert && wizard.step == Step.SPLAT
        camera.forceAutofocus = inSplat
        syncRangeSettings()
        if (inSplat != splatLaserSuppressed) {
            splatLaserSuppressed = inSplat
            processor.configure(nativeConfigForCurrentStep(st, inSplat))
        }
    }

    private fun stepLabel(s: Step): Int = when (s) {
        Step.BOARD -> R.string.step_board
        Step.FOCUS -> R.string.step_focus
        Step.CAMERA -> R.string.step_camera
        Step.LASER -> R.string.step_laser
        Step.FUSION -> R.string.step_fusion
        Step.LASER_ONLY -> R.string.step_laser_only
        Step.SPLAT -> R.string.step_splat
        Step.DONE -> if (lastCaptureKind == Step.DONE) R.string.step_fusion else stepLabel(lastCaptureKind)
    }

    /** The guided panel: step strip, title, what to do and how it goes, the buttons. */
    private fun renderWizard() {
        if (expert) return
        val r = processor.results.latest()
        val rd = r?.let { readinessOf(it) } ?: readiness
        readiness = rd
        val step = wizard.update(rd)
        syncSplatCameraMode()
        if (modeFor(step) != mode) {
            // setMode renders the panel itself, from a newer frame than r
            setMode(modeFor(step), keepSession = true)
            return
        }
        val st = settings ?: return

        STEPS.forEachIndexed { i, s ->
            val current = step == s || (step == Step.DONE && s == lastCaptureKind)
            val mark = when {
                current -> "● "
                rd.done(s) -> "✓ "
                else -> ""
            }
            stepViews[i].setIfChanged(mark + getString(stepLabel(s)))
            stepViews[i].setTextColor(if (current) Color.WHITE else if (rd.done(s)) DONE_COLOR else TODO_COLOR)
        }

        fun f(slot: Int) = r?.get(slot) ?: Float.NaN
        fun msg(key: String) = messages.optString(key, "").let { if (it.isEmpty()) "" else "\n$it" }
        val title: Int
        val body: String
        when (step) {
            Step.BOARD -> {
                title = R.string.title_board
                val board = st.board
                val name = AppSettings.BOARD_PRESETS.firstOrNull { it.board == board }?.label
                    ?: "${board.squaresX}×${board.squaresY} · ${board.squareMm} / ${board.markerMm} mm · dict ${board.dict}"
                val corners = f(H.N_CORNERS).toInt()
                val seen = r?.flag(H.DETECTED) == true
                val live = when {
                    seen -> getString(R.string.board_seen, corners, f(H.REPROJ_RMS))
                    f(H.N_MARKERS) >= 4f -> getString(R.string.board_mismatch, f(H.N_MARKERS).toInt())
                    else -> getString(R.string.board_none)
                }
                body = getString(R.string.body_board, name) + "\n\n" + live
                acts = listOf(
                    Act(getString(R.string.act_board_ok), enabled = seen && corners >= 12) { confirmBoard() },
                    Act(getString(R.string.act_board_change)) { chooseBoard() },
                    null, null,
                )
            }
            Step.FOCUS -> {
                title = R.string.title_focus
                val d = camera.focusDiopters
                val live = if (st.manualFocus) getString(R.string.focus_locked, focusText(st.focusDiopters))
                else getString(
                    R.string.focus_live,
                    getString(if (camera.focusSettled) R.string.focus_settled else R.string.focus_moving),
                    d?.let { focusText(it) } ?: "?",
                )
                val kFocus = messages.optDouble("k_focus").toFloat()
                val calibrated = if (kFocus.isNaN()) "" else "\n" + getString(R.string.focus_calibrated, focusText(kFocus))
                body = getString(R.string.body_focus) + "\n\n" + live + calibrated
                acts = listOf(
                    // not only once settled: some autofocus never reports it on a flat board
                    Act(getString(R.string.act_focus_lock), enabled = d != null && !st.manualFocus) {
                        d?.let { lockFocus(it) }
                    },
                    if (kFocus.isNaN()) null else Act(getString(R.string.act_focus_calibrated)) { lockFocus(kFocus) },
                    if (st.manualFocus) Act(getString(R.string.act_focus_auto)) { unlockFocus() } else null,
                    Act(getString(R.string.act_board_change)) { chooseBoard() },
                )
            }
            Step.CAMERA -> {
                title = R.string.title_camera
                val rms = f(H.CALIB_RMS)
                val focusMoved = r?.flag(H.K_CALIBRATED) == true && r?.flag(H.K_OK) == false
                body = getString(R.string.body_camera) + "\n\n" +
                    getString(R.string.camera_live, f(H.CALIB_COUNT).toInt(), f(H.CALIB_TARGET).toInt(),
                        if (rms.isNaN()) "-" else "%.2f px".format(rms)) + msg("calib") +
                    if (focusMoved) "\n\n" + getString(R.string.camera_focus_moved) else ""
                acts = listOf(
                    Act(getString(if (calibActive) R.string.act_pause else R.string.act_start)) {
                        processor.command(if (calibActive) Cmd.CALIB_STOP else Cmd.CALIB_START)
                    },
                    Act(getString(R.string.act_reset)) { processor.command(Cmd.CALIB_RESET) },
                    if (focusMoved) Act(getString(R.string.act_relock_focus)) { openStep(Step.FOCUS) } else null,
                    null,
                )
            }
            Step.LASER -> {
                title = R.string.title_laser
                val board = st.board
                val boardName = AppSettings.BOARD_PRESETS.firstOrNull { it.board == board }?.label
                    ?: "${board.squaresX}×${board.squaresY} · ${board.squareMm} / ${board.markerMm} mm · dict ${board.dict}"
                val corners = f(H.N_CORNERS).let { if (it.isNaN()) 0 else it.toInt() }
                val markers = f(H.N_MARKERS).let { if (it.isNaN()) 0 else it.toInt() }
                val boardLine = when {
                    r?.flag(H.DETECTED) == true -> "✓ Board ($boardName): $corners corners"
                    markers >= 2 -> "⚠ $markers markers seen, 0 corners — tap Change board ($boardName)"
                    else -> "Board: $boardName · no board in view"
                }
                val rms = f(H.PLANE_RMS)
                val stale = if (r?.flag(H.PLANE_STALE) == true && !planeActive) "\n" + getString(R.string.laser_stale) else ""
                body = getString(R.string.body_laser) + stale + "\n" + boardLine + "\n" +
                    getString(R.string.laser_live, f(H.PLANE_LINES).toInt(), f(H.PLANE_TARGET).toInt(), f(H.PLANE_SPREAD),
                        if (rms.isNaN()) "-" else "%.2f mm".format(rms)) + msg("plane")
                acts = listOf(
                    Act(getString(if (planeActive) R.string.act_pause else R.string.act_start)) {
                        if (planeActive) processor.command(Cmd.PLANE_STOP) else {
                            ensureLaserOn()
                            processor.command(Cmd.PLANE_START)
                        }
                    },
                    Act(getString(R.string.act_force), enabled = planeActive && f(H.PLANE_LINES) >= 3f) {
                        processor.command(Cmd.PLANE_FORCE)
                    },
                    Act(getString(R.string.act_board_change)) { chooseBoard() },
                    null,
                )
            }
            Step.FUSION, Step.LASER_ONLY -> {
                val isLaserOnly = step == Step.LASER_ONLY
                title = if (isLaserOnly) R.string.title_laser_only else R.string.title_fusion
                val open = photoDir != null
                val recFrames = hybridRecorder?.savedFrames ?: 0
                val skipped = processor.rangeSkippedFrames
                val laserPts = f(H.LASER_POINTS).let { if (it.isNaN()) 0 else it.toInt() }
                val vioText = when {
                    vio == null -> "VIO off"
                    vio?.tracking != true -> "VIO paused"
                    vio?.initialized == true -> "SuperVINS OK"
                    else -> "SuperVINS init"
                }
                val targetCm = ((focusTargetMm() ?: 380.0) / 10.0).roundToInt()
                val tolCm = st.captureToleranceCm
                val loCm = (targetCm - tolCm).coerceAtLeast(8)
                val hiCm = targetCm + tolCm
                val rangeLabel = if (st.captureRangeEnabled) "±$tolCm cm" else "Off"
                val capturingNow = scanOn || (processor.recordingActive && !rangePausedByApp)
                val line1 = when {
                    open && rangePausedByApp -> "Paused (out of range · move to $loCm–$hiCm cm to resume) · $recFrames frames ($skipped skipped)"
                    open && capturingNow && isLaserOnly -> "Laser capturing · $recFrames sweeps · $laserPts pts/frame"
                    open && capturingNow -> "Capturing · $recFrames frames · laser $laserPts pts/frame"
                    open -> "Paused · $recFrames frames recorded"
                    isLaserOnly -> getString(R.string.body_laser_only)
                    else -> getString(R.string.body_scan)
                }
                val line2 = "$vioText · Focus $targetCm cm ($loCm–$hiCm cm)"
                body = "$line1\n$line2"
                val trackLabel = if (vio?.tracking == true) "Stop VIO" else "Track VIO"
                val startRes = if (isLaserOnly) R.string.act_start_laser_only else R.string.act_start_fusion
                acts = listOf(
                    Act(getString(if (scanOn || rangePausedByApp || processor.recordingActive) R.string.act_pause else if (open) R.string.act_resume else startRes)) {
                        toggleScan(laserOnly = isLaserOnly)
                    },
                    if (!open) Act("Band $rangeLabel") { showRangeDialog() }
                    else Act(getString(R.string.act_finish), enabled = open) {
                        if (isLaserOnly) finishLaserOnlyScan() else finishScan()
                    },
                    if (!open) Act(if (liveMesh.active) "Close Mesh" else "Live Mesh") {
                        if (liveMesh.active) stopLiveMesh() else startLiveMesh()
                    } else Act(if (fusionPreviewExpanded) "Camera" else "3D Preview") {
                        toggleFusionPreviewExpanded()
                    },
                    if (!open) Act(trackLabel, enabled = vio != null) { toggleVioTracking() }
                    else Act(getString(R.string.act_discard), enabled = open) { discardScan() },
                )
            }
            Step.SPLAT -> {
                title = R.string.title_splat
                val splat = splatSession
                val open = splat != null
                val running = splat != null && !splat.paused
                val count = splat?.savedCount ?: 0
                val maxPhotos = splat?.maxPhotos ?: SplatCaptureSession.DEFAULT_MAX_PHOTOS
                val targetSize = splat?.takeIf { it.lastWidth > 0 }?.let { "${it.lastWidth}×${it.lastHeight}" }
                    ?: info?.let { selectedSplatStillSizes(it).firstOrNull() }?.let { "${it.width}×${it.height}" }
                    ?: "high-res"
                val vioText = when {
                    vio == null -> "VIO off"
                    vio?.tracking != true -> "VIO off (timer)"
                    vio?.initialized == true -> "SuperVINS motion"
                    else -> "SuperVINS init"
                }
                val line1 = when {
                    running -> "Capturing high-res · $count / $maxPhotos photos ($targetSize)"
                    open -> "Paused · $count / $maxPhotos photos ($targetSize)"
                    else -> getString(R.string.body_splat)
                }
                val line2 = "$vioText · $targetSize · Range gate off · Autofocus"
                body = "$line1\n$line2"
                val trackLabel = if (vio?.tracking == true) "Stop VIO" else "Track VIO"
                acts = listOf(
                    Act(getString(if (running) R.string.act_pause else if (open) R.string.act_resume else R.string.act_start_splat)) {
                        toggleSplatCapture()
                    },
                    if (!open) Act("Res $targetSize", enabled = info?.stillSizes?.isNotEmpty() == true) {
                        showSplatResolutionDialog()
                    }
                    else Act(getString(R.string.act_finish), enabled = count >= 3) { finishSplatScan() },
                    if (!open) Act(trackLabel, enabled = vio != null) { toggleVioTracking() }
                    else Act("Snap", enabled = count < maxPhotos) { snapSplatPhoto() },
                    if (!open) null
                    else Act(getString(R.string.act_discard)) { discardSplatScan() },
                )
            }
            Step.DONE -> {
                title = R.string.title_done
                body = copyNote
                when (lastCaptureKind) {
                    Step.SPLAT -> {
                        val dir = splatFinishedDir
                        acts = listOf(
                            dir?.let { Act("Train Splat") { postProcessSession(it, splatOnly = true) } },
                            dir?.let { Act("Export to PC") { exportSplatSessionToPc(it) } },
                            Act(getString(R.string.act_new_scan)) { newScan() },
                            null,
                        )
                    }
                    Step.LASER_ONLY -> {
                        val res = laserOnlyExportResult
                        acts = listOf(
                            res?.stlFile?.let { stl -> Act("Send to Plate") { sendStlToPlate(stl.absolutePath) } },
                            res?.splatName?.let { name -> Act("View Cloud") { openCloudView(name) } },
                            Act(getString(R.string.act_new_scan)) { newScan() },
                            res?.plyFile?.let { ply -> Act(getString(R.string.act_share_ply)) { shareScan(ply) } },
                        )
                    }
                    else -> {
                        val uri = copied?.scanUri
                        val sessionDir = exporting?.takeIf {
                            File(it, "frames.csv").isFile || File(it, "session.json").isFile
                        }
                        acts = listOf(
                            sessionDir?.let { Act("Fuse & Mesh") { postProcessSession(it, splatOnly = false) } },
                            Act(getString(R.string.act_new_scan)) { newScan() },
                            uri?.let { Act(getString(R.string.act_share_ply)) { sharePly(it) } },
                            null,
                        )
                    }
                }
            }
        }
        b.stepTitle.setIfChanged(getString(title))
        b.status.setIfChanged(body)
        listOf<Button>(b.action1, b.action2, b.action3, b.action4).forEachIndexed { i, btn ->
            val a = acts.getOrNull(i)
            // the buttons a step has share the row
            btn.visibility = if (a == null) View.GONE else View.VISIBLE
            btn.setIfChanged(a?.label ?: "")
            btn.isEnabled = a?.enabled == true
        }
    }

    private fun TextView.setIfChanged(s: String) {
        if (text.toString() != s) text = s
    }

    private fun focusText(d: Float) = if (d > 0.01f) "%.1f D (%.0f cm)".format(d, 100f / d) else "infinity"

    private fun pointsText(n: Float) = when {
        n.isNaN() -> "0"
        n >= 1e6f -> "%.1f M".format(n / 1e6f)
        n >= 1e3f -> "%.0f k".format(n / 1e3f)
        else -> n.toInt().toString()
    }

    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(this)

    private fun confirmBoard() {
        val st = settings ?: return
        prefs().edit().putString("board_confirmed", st.board.key).apply()
        settings = AppSettings.load(this)
        wizard.completed(currentReadiness())
    }

    private fun chooseBoard() {
        val presets = AppSettings.BOARD_PRESETS
        val labels = presets.map { it.label } + getString(R.string.board_other)
        AlertDialog.Builder(this)
            .setTitle(R.string.board_pick)
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < presets.size) {
                    AppSettings.saveBoard(this, presets[i].board)
                    applySettingsLive()
                    renderWizard()
                } else {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            .show()
    }

    /** Lock the lens at [diopters] (where autofocus settled, or where the camera was calibrated). */
    private fun lockFocus(diopters: Float) {
        // the Settings slider holds tenths up to 10 D; the lens may stop sooner
        val closest = ((info?.maxDiopters ?: 10f) * 10).toInt().coerceIn(0, 100)
        prefs().edit()
            .putBoolean("manual_focus", true)
            .putInt("focus_tenths", (diopters * 10).roundToInt().coerceIn(0, closest))
            .apply()
        applySettingsLive()
        wizard.completed(currentReadiness())
    }

    private fun unlockFocus() {
        prefs().edit().putBoolean("manual_focus", false).apply()
        applySettingsLive()
    }

    private fun ensureLaserOn() {
        if (laserEnabled()) return
        prefs().edit().putBoolean("laser_enabled", true).apply()
        applySettingsLive()
    }

    /** Start (a new session for the first start), pause or resume a Fusion or Laser-Only scan. */
    private fun toggleScan(laserOnly: Boolean = false) {
        if (scanOn || rangePausedByApp || processor.recordingActive) {
            rangePausedByApp = false
            processor.recordingActive = false
            processor.command(Cmd.SCAN_STOP)
            vio?.warmupDone = false   // the next start warms up again
            renderWizard()
            return
        }
        // every start needs the warm-up before the scan runs
        if (vio != null && vio?.warmupDone != true) {
            pendingCaptureStep = if (laserOnly) Step.LASER_ONLY else Step.FUSION
            pendingScanStart = true
            if (vio?.tracking != true) toggleVioTracking() else startWarmup()
            status("Warm up first: hold still, then move gently")
            return
        }
        performScanStart(laserOnly = laserOnly)
    }

    private var scanStartGraceUntilMs = 0L

    private fun performScanStart(laserOnly: Boolean = false) {
        if (liveMesh.active) stopLiveMesh()
        ensureLaserOn()
        lastCaptureKind = if (laserOnly) Step.LASER_ONLY else Step.FUSION
        scanStartGraceUntilMs = android.os.SystemClock.elapsedRealtime() + 1500L
        if (photoDir != null) {
            rangePausedByApp = false
            processor.recordingActive = true
            processor.command(Cmd.SCAN_START)
            return
        }
        val inf = info ?: return
        // Fusion mode records 640x480 keyframes every 3rd frame (`imageEveryN = 3`).
        // Laser-Only skips JPEG encoding completely (`imageEveryN = 0`) for maximum 20 Hz laser speed.
        val everyN = if (laserOnly) 0 else 3
        startPhotos(photos = false, fresh = true, imageEveryN = everyN) {
            processor.resetRangeGate()
            rangePausedByApp = false
            processor.recordingActive = true
            scanStartGraceUntilMs = android.os.SystemClock.elapsedRealtime() + 1500L
            setFusionPreviewVisible(true, reset = true)
            processor.command(Cmd.SCAN_START)
        }
    }

    /** Close a Fusion session (the core stops the scan and writes its clouds) and copy it to Download. */
    private fun finishScan() {
        val dir = photoDir ?: return
        lastCaptureKind = Step.FUSION
        laserOnlyExportResult = null
        rangePausedByApp = false
        processor.recordingActive = false
        processor.command(Cmd.PHOTOS_STOP) { exportSession(dir) }
        endPhotos()
        copied = null
        exporting = dir
        copyNote = getString(R.string.done_saving)
        wizard.finished()
    }

    /** Close a Laser-Only session and export direct metric PLY + CloudView + 1.0 mm STL immediately. */
    private fun finishLaserOnlyScan() {
        val dir = photoDir ?: return
        lastCaptureKind = Step.LASER_ONLY
        laserOnlyExportResult = null
        rangePausedByApp = false
        processor.recordingActive = false
        processor.command(Cmd.PHOTOS_STOP) {
            Thread {
                val res = runCatching { LaserOnlyExporter.export(dir, filesDir, preferredVoxelMm = 1.0f) }
                runOnUiThread {
                    if (isFinishing || isDestroyed || exporting != dir) return@runOnUiThread
                    res.onSuccess { out ->
                        laserOnlyExportResult = out
                        val stlPart = if (out.triangles > 0) {
                            "${out.triangles} tris (${String.format(Locale.US, "%.1f", out.voxelMm)} mm STL)"
                        } else "point cloud only"
                        copyNote = "Laser-only scan ready: ${out.points} pts from ${out.frames} sweeps (${out.poseSource}) · $stlPart."
                        renderWizard()
                    }.onFailure { err ->
                        copyNote = "Laser-only export failed: ${err.message ?: err.javaClass.simpleName}"
                        renderWizard()
                    }
                }
            }.start()
        }
        endPhotos()
        copied = null
        exporting = dir
        copyNote = "Building metric laser cloud and 1.0 mm STL…"
        wizard.finished()
    }

    // ── High-resolution Splat-Only capture ───────────────────────────────────

    private fun toggleSplatCapture() {
        val cur = splatSession
        if (cur != null) {
            cur.paused = !cur.paused
            if (cur.paused) {
                cur.onStillFailed()
            } else {
                // Trigger an immediate capture on resume when ready.
                camera.captureStill()
            }
            renderWizard()
            return
        }
        startSplatCapture()
    }

    private fun splatStillResolutionPref(): String =
        prefs().getString("splat_still_resolution", "auto") ?: "auto"

    private fun selectedSplatStillSizes(inf: CameraInfo): List<android.util.Size> =
        CameraController.pickSplatStillSizes(inf, maxLongSide = 2048, preferredKey = splatStillResolutionPref())

    private fun showSplatResolutionDialog() {
        val inf = info ?: return
        val sizes = inf.stillSizes.distinctBy { it.width to it.height }
        if (sizes.isEmpty()) {
            Toast.makeText(this, getString(R.string.photos_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        val autoSize = CameraController.pickSplatStillSizes(inf, maxLongSide = 2048).firstOrNull()
        val autoLabel = if (autoSize != null) "Auto (≤2048 px · ${autoSize.width}×${autoSize.height})" else "Auto (≤2048 px)"
        val keys = ArrayList<String>(sizes.size + 1).apply {
            add("auto")
            sizes.forEach { add("${it.width}x${it.height}") }
        }
        val labels = ArrayList<String>(sizes.size + 1).apply {
            add(autoLabel)
            sizes.forEach { sz ->
                val mp = (sz.width.toDouble() * sz.height.toDouble()) / 1_000_000.0
                add(String.format(Locale.US, "%d×%d (%.1f MP)", sz.width, sz.height, mp))
            }
        }
        val currentKey = splatStillResolutionPref()
        val checked = keys.indexOf(currentKey).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle("Splat capture resolution")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dlg, which ->
                val picked = keys[which]
                prefs().edit().putString("splat_still_resolution", picked).apply()
                renderWizard()
                dlg.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startSplatCapture() {
        if (liveMesh.active) stopLiveMesh()
        val inf = info ?: return
        val stillSizes = selectedSplatStillSizes(inf)
        if (stillSizes.isEmpty()) {
            Toast.makeText(this, getString(R.string.photos_unavailable), Toast.LENGTH_LONG).show()
            return
        }
        val id = "splat-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val root = File(getExternalFilesDir(null), "sessions")
        val dir = generateSequence(1) { it + 1 }.map { File(root, if (it == 1) id else "$id-$it") }.first { !it.exists() }
        dir.mkdirs()

        val session = SplatCaptureSession(dir)
        splatSession = session
        lastCaptureKind = Step.SPLAT
        splatFinishedDir = null
        camera.forceAutofocus = true
        camera.setStills(stillSizes)
        setFusionPreviewVisible(true, reset = true)

        processor.splatAutoCaptureHook = { pose ->
            val s = splatSession
            if (s != null && s.shouldAutoCapture(android.os.SystemClock.elapsedRealtime(), pose)) {
                camera.captureStill()
            }
        }
        renderWizard()
    }

    private fun snapSplatPhoto() {
        val s = splatSession ?: return
        if (s.markManualTrigger(android.os.SystemClock.elapsedRealtime())) {
            camera.captureStill()
        }
    }

    private fun finishSplatScan() {
        val s = splatSession ?: return
        splatSession = null
        processor.splatAutoCaptureHook = null
        setFusionPreviewVisible(false)
        camera.setStills(emptyList())

        val camId = info?.id ?: "0"
        val liveIntr = runCatching {
            val f = File(filesDir, "live_intrinsics.json")
            if (f.isFile) f.readText() else null
        }.getOrNull()
        runCatching { s.writeManifest(camId, liveIntr) }

        lastCaptureKind = Step.SPLAT
        splatFinishedDir = s.sessionDir
        splatFinishedCount = s.savedCount
        splatFinishedResolution = if (s.lastWidth > 0) "${s.lastWidth}×${s.lastHeight}" else "high-res"
        copyNote = "Saved $splatFinishedCount high-res photos ($splatFinishedResolution) in ${s.sessionDir.name}.\n" +
            "Tap Train Splat to mask, reconstruct, and train Cross3DGS."
        wizard.finished()
        renderWizard()
    }

    private fun discardSplatScan() {
        val s = splatSession ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.discard_title)
            .setMessage("Delete ${s.savedCount} captured high-res Splat photos?")
            .setPositiveButton(R.string.act_discard) { _, _ ->
                splatSession = null
                processor.splatAutoCaptureHook = null
                setFusionPreviewVisible(false)
                camera.setStills(emptyList())
                Thread { s.sessionDir.deleteRecursively() }.start()
                renderWizard()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportSplatSessionToPc(dir: File) {
        copyNote = "Copying high-res photos to Download/EasyScanner/${dir.name}…"
        renderWizard()
        Thread {
            val res = runCatching { SessionExport.copyToDownloads(this, dir) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                res.onSuccess { c ->
                    copyNote = "Exported ${c.files} files to Download/${c.relativeDir}/.\n" +
                        "Connect over USB to import splat_photos/ into Postshot, COLMAP, Brush, Nerfstudio, or RealityCapture."
                    Toast.makeText(this, "Saved to Download/${c.relativeDir}", Toast.LENGTH_LONG).show()
                }.onFailure { err ->
                    copyNote = "Export to Download failed (${err.message}). Photos stay in ${dir.absolutePath}."
                    Toast.makeText(this, copyNote, Toast.LENGTH_LONG).show()
                }
                renderWizard()
            }
        }.start()
    }

    private fun sendStlToPlate(stlPath: String) {
        val intent = Intent().setClassName(packageName, "com.tomppi.enderslicer.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("com.tomppi.enderslicer.IMPORT_STL_PATH", stlPath)
        runCatching {
            startActivity(intent)
            finish()
        }.onFailure { e ->
            Toast.makeText(this, "Could not open plate: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openCloudView(splatName: String) {
        val intent = Intent().setClassName(packageName, "com.tomppi.enderslicer.easyscan.LaserCloudViewerActivity")
            .putExtra("file", splatName)
        runCatching { startActivity(intent) }.onFailure { e ->
            Toast.makeText(this, "Could not open CloudView: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun discardScan() {
        AlertDialog.Builder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_body)
            .setPositiveButton(R.string.act_discard) { _, _ ->
                val dir = photoDir
                // cleared first, so closing the session writes no cloud just to delete it
                processor.command(Cmd.CLOUD_CLEAR)
                processor.command(Cmd.PHOTOS_STOP) { dir?.let { Thread { it.deleteRecursively() }.start() } }
                endPhotos()
                renderWizard()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun newScan() {
        processor.command(Cmd.CLOUD_CLEAR)
        copied = null
        laserOnlyExportResult = null
        splatFinishedDir = null
        wizard.newScan(currentReadiness(), returnTo = lastCaptureKind)
        syncSplatCameraMode()
        renderWizard()
    }

    private fun exportSession(dir: File) = Thread {
        val res = runCatching { SessionExport.copyToDownloads(this, dir) }
        runOnUiThread {
            // a later Finish owns the Saved step now
            if (isFinishing || isDestroyed || exporting != dir) return@runOnUiThread
            copied = res.getOrNull()
            copyNote = res.fold(
                { savedText(it) },
                // the core leaves no session behind when nothing was captured
                { if (dir.exists()) getString(R.string.done_failed, it.message, dir.absolutePath) else getString(R.string.done_empty) },
            )
            renderWizard()
        }
    }.start()

    private fun savedText(c: SessionExport.Copied): String {
        val parts = listOfNotNull(
            SessionExport.SCAN_PLY.takeIf { it in c.names },
            "laser.ply".takeIf { it in c.names },
            if (c.photos > 0) resources.getQuantityString(R.plurals.photos, c.photos, c.photos) else null,
            "session.json".takeIf { it in c.names },
        )
        return getString(R.string.done_body, c.relativeDir, parts.joinToString(", "), c.files)
    }

    /** The core closed the session mid-scan; its files go to Download now, with a note. */
    private fun exportClosed(dir: File) = Thread {
        if (!dir.exists()) return@Thread
        val res = runCatching { SessionExport.copyToDownloads(this, dir) }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val why = messages.optString("photos", "")
            val note = res.fold({ getString(R.string.closed_saved, it.relativeDir) }, { getString(R.string.closed_kept, dir.name) })
            Toast.makeText(this, if (why.isEmpty()) note else "$note\n$why", Toast.LENGTH_LONG).show()
        }
    }.start()

    /** Sessions that never reached Download (the app was killed, or a scan from before): copy them on a yes. */
    private fun offerPending() {
        if (pendingOffered) return
        pendingOffered = true
        val root = File(getExternalFilesDir(null), "sessions")
        val open = photoDir
        Thread {
            val pending = SessionExport.pending(root, open)
            if (pending.isEmpty()) return@Thread
            val latest = pending.last()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle(R.string.pending_title)
                    .setMessage(getString(R.string.pending_body, pending.size, latest.name, SessionExport.photoCount(latest)))
                    .setPositiveButton(R.string.act_save_now) { _, _ -> copyPending(pending) }
                    .setNegativeButton(R.string.act_later, null)
                    .show()
            }
        }.start()
    }

    private fun copyPending(dirs: List<File>) = Thread {
        val ok = dirs.count { runCatching { SessionExport.copyToDownloads(this, it) }.onFailure { e -> Log.w(TAG, "copy ${it.name}", e) }.isSuccess }
        runOnUiThread { Toast.makeText(this, getString(R.string.pending_saved, ok), Toast.LENGTH_LONG).show() }
    }.start()

    private fun sharePly(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(PLY_MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share_scan)))
    }

    /** Hands the finished laser/fusion or high-res splat session to SplatProto for BA, MVS fusion, STL & 3DGS. */
    private fun postProcessSession(dir: File, splatOnly: Boolean = false) {
        val intent = Intent().setClassName(packageName, "com.tomppi.splatproto.PipelineActivity")
            .putExtra("com.tomppi.splatproto.EASYSCAN_SESSION", dir.absolutePath)
            .putExtra("com.tomppi.splatproto.SPLAT_ONLY", splatOnly)
        runCatching { startActivity(intent) }.onFailure { e ->
            Toast.makeText(this, "Post-process unavailable: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── export and photo sessions ────────────────────────────────────────────

    /**
     * Copy an exported scan to Download/EasyScanner (our own MediaStore entry needs no
     * storage permission) and offer it to other apps; the original stays in the app's files.
     */
    private fun shareScan(file: File) = Thread {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, PLY_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SessionExport.DOWNLOADS_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        var uri: Uri? = null
        try {
            uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore refused the entry")
            contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "copy to Downloads failed", e)
            uri?.let { contentResolver.delete(it, null, null) }
            uri = null
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (uri == null) {
                Toast.makeText(this, getString(R.string.scan_copy_failed), Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            Toast.makeText(this, getString(R.string.scan_saved, "${SessionExport.DOWNLOADS_DIR}/${file.name}"), Toast.LENGTH_LONG).show()
            sharePly(uri)
        }
    }.start()

    /**
     * Open a photo session (orbiter-native's layout) under the app's files and,
     * with [photos], add the still stream to the camera; without, the session
     * only keeps the scan. [fresh]: a new scan (see `engine.rs` PhotosStartArg);
     * [onOpen] runs once the core has opened it.
     */
    private fun startPhotos(
        photos: Boolean = true,
        fresh: Boolean = false,
        imageEveryN: Int = 3,
        onOpen: () -> Unit = {},
    ) {
        val inf = info ?: return
        if (photos && inf.stillSizes.isEmpty()) {
            onError(getString(R.string.photos_unavailable))
            return
        }
        val now = Date()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val root = File(getExternalFilesDir(null), "sessions")
        // the core refuses an existing directory: a second start within the same second gets a suffix
        val dir = generateSequence(1) { it + 1 }.map { File(root, if (it == 1) id else "$id-$it") }.first { !it.exists() }
        photoDir = dir
        val serial = ++photoSerial
        if (photos) camera.setStills(inf.stillSizes)
        val arg = JSONObject().put("dir", dir.absolutePath).put("session_id", dir.name)
            .put("started_utc", utc).put("serial", serial).put("photos", photos).put("fresh", fresh)
        processor.command(Cmd.PHOTOS_START, arg.toString()) { msg ->
            renderStatus()
            // engine.rs open_photos: anything else is a refusal
            if (msg.startsWith("photos on")) {
                if (photoSerial == serial && photoDir != null) {
                    startHybridRecorder(dir, imageEveryN = imageEveryN)
                    onOpen()
                }
            } else if (photoSerial == serial && photoDir != null) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                endPhotos()
            }
        }
        renderButtons()
    }

    private fun stopPhotos() {
        processor.command(Cmd.PHOTOS_STOP) { msg ->
            renderStatus()
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        endPhotos()
    }

    /** Forget the session on this side and give the camera its still stream back. */
    private fun endPhotos() {
        stopHybridRecorder()
        setFusionPreviewVisible(false)
        photoDir = null
        camera.setStills(emptyList())
        renderButtons()
    }

    /** Record the scanner's IMU, VIO and laser streams into the same session as its photographs. */
    private fun startHybridRecorder(dir: File, imageEveryN: Int = 3) {
        stopHybridRecorder()
        val meta = JSONObject()
            .put("kind", if (imageEveryN == 0) "laser_only_scan" else "hybrid_scan")
            .put("recorded_by", "easyscanner")
            .put("session", dir.name)
            .toString(2)
        val rec = TrackerRecorder(this, meta, gateOnScan = false, recordFrames = true,
            imageEveryN = imageEveryN, targetDir = dir)
        runCatching {
            rec.start()
            hybridRecorder = rec
            processor.trackerRecorder = rec
            writeHybridManifest(dir)
        }.onFailure { Log.w(TAG, "hybrid recorder start failed", it) }
    }

    /**
     * Bind the already-existing streams of a hybrid session in one manifest
     * (docs/UNIFIED_CAPTURE_SESSION.md). The photo session and the tracker
     * recorder write the data; this file only names the frames, conventions,
     * calibration provenance and the join rules, so a consumer never has to
     * guess which pose frame or timestamp it is holding.
     */
    private fun writeHybridManifest(dir: File) {
        try {
            val now = Date()
            val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
            val version = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "?"
            val chain = File(filesDir, "openvins-config/kalibr_imucam_chain.yaml")
            val profile = if (chain.exists()) {
                "kalibr_imucam_chain.yaml/" + chain.length() + "/" + chain.lastModified()
            } else ""
            val aw = analysisSize?.width ?: 640
            val ah = analysisSize?.height ?: 480
            val o = JSONObject()
                .put("schema", "easyscanner.hybrid_session.v1")
                .put("session_id", dir.name)
                .put("created_utc", utc)
                .put("app", JSONObject().put("easyscan", version).put("core", "native"))
                .put("frame", JSONObject()
                    .put("name", "board")
                    .put("origin", "board centre")
                    .put("axes", "+x width, +y up printed face, +z out of printed face")
                    .put("units", "mm"))
                .put("analysis", JSONObject()
                    .put("frames", "frames.csv")
                    .put("imu", "imu.csv")
                    .put("corners", "corners.csv")
                    .put("width", aw)
                    .put("height", ah)
                    .put("timebase", "sensor_boottime_ns")
                    .put("board_pose_column", "pose16")
                    .put("vio_pose_column", "vio_pose16")
                    .put("board_pose_convention", "board->camera (camera_point = R * board_point + t)")
                    .put("vio_pose_convention", "world->camera (row-major, metres)"))
                .put("keyframes", JSONObject()
                    .put("jsonl", "photos.jsonl")
                    .put("dir", "photos")
                    .put("pose_frame", "board")
                    .put("pose_convention", "board->camera")
                    .put("timestamp_field", "sensor_timestamp_ns")
                    .put("laser_field", "laser_on"))
                .put("laser", JSONObject()
                    .put("dir", "laser")
                    .put("filename", "f%05d.f32")
                    .put("index", "frames.csv frame column")
                    .put("layout", "float32 x,y,z per point")
                    .put("frame", "analysis camera")
                    .put("units", "mm"))
                .put("calibration", JSONObject()
                    .put("camera_model", "files/live_intrinsics.json")
                    .put("imucam_chain", "files/openvins-config/kalibr_imucam_chain.yaml")
                    .put("laser_plane", "files/laser_plane.json")
                    .put("profile_id", profile))
                .put("mapping", JSONObject()
                    .put("still_to_analysis", "nearest frames.csv row by |sensor_timestamp_ns - ts_ns| <= 50 ms")
                    .put("analysis_to_laser", "laser/f{frame:05d}.f32; zero points means no laser in that frame")
                    .put("laser_to_board", "apply that row's pose16: p_board = R^T (p_cam - t); do not use vio_pose16")
                    .put("laser_to_vio", "apply inverted vio_pose16 only when the session is marker-free"))
            File(dir, "hybrid.json").writeText(o.toString(2))
        } catch (e: Throwable) {
            Log.w(TAG, "hybrid manifest write failed", e)
        }
    }

    private fun stopHybridRecorder() {
        processor.recordingActive = false
        rangePausedByApp = false
        val rec = hybridRecorder ?: return
        hybridRecorder = null
        if (processor.trackerRecorder === rec) processor.trackerRecorder = null
        runCatching { rec.stop() }
    }

    /** Delivered frame rate from sensor timestamps (smoothed). */
    private class FpsMeter {
        @Volatile var fps = 0.0
            private set
        private var last = 0L

        fun tick(ts: Long) {
            if (last != 0L && ts > last) {
                val inst = 1e9 / (ts - last)
                fps = if (fps == 0.0) inst else fps * 0.9 + inst * 0.1
            }
            last = ts
        }
    }

    private companion object {
        const val EXTRA_CALIB_CAPTURE = "calib_capture"
        const val EXTRA_VIO_TUNE = "vio_tune"

        const val TAG = "EasyScanner"
        const val HUD_PERIOD_MS = 100L
        /** Focus band for the working-distance HUD, as a fraction of the focus distance. */
        const val NEAR_RATIO = 0.78
        const val FAR_RATIO = 1.28
        /** No registered PLY type; a generic one keeps MediaStore from renaming the file. */
        const val PLY_MIME = "application/octet-stream"
        val STEPS = listOf(Step.FOCUS, Step.LASER, Step.FUSION, Step.LASER_ONLY, Step.SPLAT)
        val DONE_COLOR = Color.rgb(120, 200, 140)
        val TODO_COLOR = Color.rgb(140, 140, 150)
    }
}
