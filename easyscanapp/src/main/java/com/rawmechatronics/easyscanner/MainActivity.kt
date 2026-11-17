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
    @Volatile private var governor: ResolutionGovernor? = null
    private var settings: AppSettings? = null
    private var mode = Mode.VIEW
    private var messages = JSONObject()
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
    private var readiness = Readiness(boardConfirmed = false, focusLocked = false, calibrated = false, planeReady = false)
    private val stepViews = ArrayList<TextView>()
    private var acts: List<Act?> = emptyList()
    /** The last copy to Download, for the Saved step, and the session it is of. */
    private var copied: SessionExport.Copied? = null
    private var exporting: File? = null
    private var copyNote = ""
    /** Unsaved sessions are offered once per process. */
    private var pendingOffered = false

    private val expert get() = settings?.expertMode == true

    // on a grant the activity resumes next, and onResume opens the camera
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) status(getString(R.string.need_camera))
    }

    /** The camera was (re)opened for the current resume; reset by onPause. */
    private var cameraStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mgr = getSystemService(CameraManager::class.java)
        processor = FrameProcessor(filesDir.absolutePath, this)
        camera = CameraController(
            mgr, processor.handler, processor,
            onError = { msg -> main.post { onError(msg) } },
            onSizeFailed = { size -> main.post { fallBackFrom(size) } },
            onStill = ::onStill,
            onStillFailed = { msg, fatal -> main.post { onStillFailed(msg, fatal) } },
        )
        b.overlay.source = processor.results
        settings = AppSettings.load(this)

        b.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.libraryButton.setOnClickListener { startActivity(Intent(this, LibraryActivity::class.java)) }
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
                setPadding(0, 6, 0, 6)
                textSize = 13f
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
    }

    override fun onResume() {
        super.onResume()
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
        camera.close()
        cameraStarted = false
        super.onPause()
    }

    override fun onDestroy() {
        camera.quit()
        processor.release()
        super.onDestroy()
    }

    /** (Re)read the settings and (re)open the camera with them. */
    private fun startCamera() {
        if (cameraStarted || !b.texture.isAvailable || !hasCameraPermission()) return
        cameraStarted = true
        val st = AppSettings.load(this)
        settings = st
        applyInterface()
        val ids = CameraInfo.backCameraIds(mgr)
        if (ids.isEmpty()) {
            status(getString(R.string.no_camera))
            return
        }
        val inf = CameraInfo.query(mgr, st.cameraId.takeIf { it in ids } ?: ids.first(), st.targetFps, st.maxLongSide)
        info = inf
        val pv = inf.previewSize
        val portrait = inf.sensorOrientation % 180 != 0
        (b.previewFrame.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio =
            if (portrait) "${pv.height}:${pv.width}" else "${pv.width}:${pv.height}"
        b.previewFrame.requestLayout()
        b.overlay.sensorOrientation = inf.sensorOrientation

        processor.configure(st.nativeConfig(inf))
        val sizes = inf.analysisSizes
        governor = if (st.autoResolution) {
            ResolutionGovernor(sizes.map { FrameSize(it.width, it.height) }, st.targetFps, 0)
                .also { it.reset(0, System.nanoTime()) }
        } else null
        camera.open(inf, b.texture.surfaceTexture!!, sizes.first(), st)
        renderButtons()
        offerPending()
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
        camera.setAnalysisSize(sizes[next])
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Settings changed here (focus lock, board, laser): reload them and hand them on. */
    private fun applySettingsLive() {
        val st = AppSettings.load(this)
        settings = st
        camera.applySettings(st)
        processor.configure(st.nativeConfig(info))
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

    /** A still is back (still thread): onto disk, then to the core to be posed and named. */
    private fun onStill(s: Still) {
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
            calibActive = newCalib
            planeActive = newPlane
            scanOn = newScan
            renderButtons()
        }
        if (!expert) renderWizard()
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
            Mode.VIEW -> listOf(getString(if (laserEnabled()) R.string.act_laser_on else R.string.act_laser_off), null, null, null)
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
            Mode.VIEW -> if (i == 0) {
                val p = PreferenceManager.getDefaultSharedPreferences(this)
                p.edit().putBoolean("laser_enabled", !laserEnabled()).apply()
                applySettingsLive()
                renderButtons()
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
            boardConfirmed = st != null && st.boardConfirmed == st.board.key,
            focusLocked = info?.manualFocus != true || st?.manualFocus == true,
            calibrated = r.flag(H.K_OK),
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
        Step.SCAN -> Mode.SCAN
        Step.BOARD, Step.FOCUS, Step.DONE -> Mode.VIEW
    }

    private fun openStep(s: Step) {
        if (expert) return
        val rd = currentReadiness()
        if (!wizard.open(s, rd)) {
            Toast.makeText(this, getString(R.string.step_locked, getString(stepLabel(rd.firstOpen))), Toast.LENGTH_SHORT).show()
        }
        renderWizard()
    }

    private fun stepLabel(s: Step) = when (s) {
        Step.BOARD -> R.string.step_board
        Step.FOCUS -> R.string.step_focus
        Step.CAMERA -> R.string.step_camera
        Step.LASER -> R.string.step_laser
        Step.SCAN, Step.DONE -> R.string.step_scan
    }

    /** The guided panel: step strip, title, what to do and how it goes, the buttons. */
    private fun renderWizard() {
        if (expert) return
        val r = processor.results.latest()
        val rd = r?.let { readinessOf(it) } ?: readiness
        readiness = rd
        val step = wizard.update(rd)
        if (modeFor(step) != mode) {
            // setMode renders the panel itself, from a newer frame than r
            setMode(modeFor(step), keepSession = true)
            return
        }
        val st = settings ?: return

        STEPS.forEachIndexed { i, s ->
            val current = step == s || step == Step.DONE && s == Step.SCAN
            val mark = when {
                current -> "● "
                rd.done(s) -> "✓ "
                else -> "○ "
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
                    null,
                )
            }
            Step.CAMERA -> {
                title = R.string.title_camera
                val rms = f(H.CALIB_RMS)
                body = getString(R.string.body_camera) + "\n\n" +
                    getString(R.string.camera_live, f(H.CALIB_COUNT).toInt(), f(H.CALIB_TARGET).toInt(),
                        if (rms.isNaN()) "-" else "%.2f px".format(rms)) + msg("calib")
                acts = listOf(
                    Act(getString(if (calibActive) R.string.act_pause else R.string.act_start)) {
                        processor.command(if (calibActive) Cmd.CALIB_STOP else Cmd.CALIB_START)
                    },
                    Act(getString(R.string.act_reset)) { processor.command(Cmd.CALIB_RESET) },
                    null, null,
                )
            }
            Step.LASER -> {
                title = R.string.title_laser
                val rms = f(H.PLANE_RMS)
                val stale = if (r?.flag(H.PLANE_STALE) == true && !planeActive) "\n" + getString(R.string.laser_stale) else ""
                body = getString(R.string.body_laser) + stale + "\n\n" +
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
                    null, null,
                )
            }
            Step.SCAN -> {
                title = R.string.title_scan
                val open = photoDir != null
                val photoState = f(H.PHOTO_STATE).toInt()
                val photos = if (!open || photoState == Photo.NO_PHOTOS) getString(R.string.scan_no_photos)
                else getString(R.string.scan_photos, f(H.PHOTO_COUNT).toInt(), photoStateLabel(photoState))
                val intro = getString(R.string.body_scan) +
                    if (st.photos.withScan) " " + getString(R.string.body_scan_photos) else ""
                body = intro + "\n\n" + getString(R.string.scan_live, pointsText(f(H.CLOUD_POINTS)), photos) +
                    msg("scan") + msg("photos")
                acts = listOf(
                    Act(getString(if (scanOn) R.string.act_pause else if (open) R.string.act_resume else R.string.act_start)) {
                        toggleScan()
                    },
                    Act(getString(R.string.act_finish), enabled = open) { finishScan() },
                    Act(getString(R.string.act_discard), enabled = open) { discardScan() },
                    if (open && photoState != Photo.NO_PHOTOS) Act(getString(R.string.act_photo_now)) {
                        processor.command(Cmd.PHOTO_SNAP)
                    } else null,
                )
            }
            Step.DONE -> {
                title = R.string.title_done
                body = copyNote
                val uri = copied?.scanUri
                acts = listOf(
                    Act(getString(R.string.act_new_scan)) { newScan() },
                    uri?.let { Act(getString(R.string.act_share_ply)) { sharePly(it) } },
                    null, null,
                )
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

    /** Start (a new session for the first start), pause or resume the scan. */
    private fun toggleScan() {
        if (scanOn) {
            processor.command(Cmd.SCAN_STOP)
            return
        }
        ensureLaserOn()
        if (photoDir != null) {
            processor.command(Cmd.SCAN_START)
            return
        }
        val inf = info ?: return
        // a fresh session starts from an empty cloud, unless it holds points no
        // session saved; the scan runs only once the session is there
        startPhotos(settings?.photos?.withScan == true && inf.stillSizes.isNotEmpty(), fresh = true) {
            processor.command(Cmd.SCAN_START)
        }
    }

    /** Close the session (the core stops the scan and writes its clouds) and copy it to Download. */
    private fun finishScan() {
        val dir = photoDir ?: return
        processor.command(Cmd.PHOTOS_STOP) { exportSession(dir) }
        endPhotos()
        copied = null
        exporting = dir
        copyNote = getString(R.string.done_saving)
        wizard.finished()
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
        wizard.newScan(currentReadiness())
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
    private fun startPhotos(photos: Boolean = true, fresh: Boolean = false, onOpen: () -> Unit = {}) {
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
                if (photoSerial == serial && photoDir != null) onOpen()
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
        photoDir = null
        camera.setStills(emptyList())
        renderButtons()
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
        const val TAG = "EasyScanner"
        const val HUD_PERIOD_MS = 100L
        /** No registered PLY type; a generic one keeps MediaStore from renaming the file. */
        const val PLY_MIME = "application/octet-stream"
        val STEPS = listOf(Step.BOARD, Step.FOCUS, Step.CAMERA, Step.LASER, Step.SCAN)
        val DONE_COLOR = Color.rgb(120, 200, 140)
        val TODO_COLOR = Color.rgb(140, 140, 150)
    }
}
