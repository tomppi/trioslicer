package com.rawmechatronics.easyscanner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * ARCore-pose prototype: same laser engine as MainActivity, but the camera pose
 * comes from ARCore instead of the ChArUco board. No board is needed during a
 * scan; only the saved laser-plane calibration is used.
 */
class ArActivity : Activity(), GLSurfaceView.Renderer, FrameProcessor.Listener {

    companion object {
        private const val TAG = "EasyScannerAr"
        private const val REQUEST_CAMERA = 7
        /** Synthetic scan volume (mm) used when there is no board to measure it. */
        private const val DEFAULT_BOX_MM = 400.0
        private const val FLOOR_CLEAR_MM = 10.0
        private const val PREFS = "es_ar"
        private const val PREF_BOX_OFFSET = "box_offset_mm"
        private val AR_CONFIG = """
            {"camera":{"id":"0"},
             "board":{"squares_x":8,"squares_y":8,"square_mm":SQUARE_MM,"marker_mm":MARKER_MM,"dict":5},
             "volume":{"height_mm":HEIGHT_MM,"margin_mm":20.0},
             "draw":{"grid":false,"axes":false,"corners":false,"volume":true,"cloud":true},
             "laser":{"enabled":true,"color":"red","thresh":12,"min_sat":40,"without_board":true},
             "calib_views":64,"plane_views":10,"max_cloud_points":6000000}
        """.trimIndent()
    /** The real printed board (4x6 / 45 mm / 33.3 mm / DICT_5X5_100), used while
     *  fitting the laser plane in ARCore camera coordinates. */
    private val AR_PLANE_CONFIG = """
        {"camera":{"id":"0"},
         "board":{"squares_x":4,"squares_y":6,"square_mm":45.0,"marker_mm":33.3,"dict":5},
         "volume":{"height_mm":0.0,"margin_mm":20.0},
         "draw":{"grid":false,"axes":false,"corners":false,"volume":false,"cloud":false},
         "laser":{"enabled":true,"color":"red","thresh":12,"min_sat":40,"without_board":true},
         "calib_views":64,"plane_views":10,"max_cloud_points":6000000}
    """.trimIndent()

    }


    /** The scan config with the current box size substituted in. */
    private fun arConfig(): String {
        val sq = boxMm / 8.0
        val mk = sq * 0.74
        return AR_CONFIG
            .replace("SQUARE_MM", sq.toString())
            .replace("MARKER_MM", mk.toString())
            .replace("HEIGHT_MM", boxHeightMm.toString())
    }

    private fun changeBox(deltaMm: Double) {
        boxMm = (boxMm + deltaMm).coerceIn(200.0, 1200.0)
        boxHeightMm = boxMm
        processor?.configure(arConfig())
        processor?.command(Cmd.AR_MODE, "1")
        pointCount = 0
        setHint("Capture box: " + boxMm.toInt() + " mm wide, " + boxHeightMm.toInt() + " mm tall.", 3000)
    }
    private lateinit var surface: GLSurfaceView
    private lateinit var status: TextView
    private var session: Session? = null
    private var processor: FrameProcessor? = null
    private var installRequested = false
    private var resumed = false

    /** Anchor point on a detected plane: the origin of the scan frame. */
    private var anchor: FloatArray? = null
    private var scanOn = false
    private var pointCount = 0

    // GL
    private var program = 0
    private var textureId = 0
    private var uTex = 0
    private var quadCoords: FloatBuffer = FloatBuffer.allocate(8)
    private var quadTexCoords: FloatBuffer = FloatBuffer.allocate(8)
    private var viewTx = 0f
    private var viewTy = 0f
    private var tapPending: FloatArray? = null
    private var frames = 0
    private var hint = ""
    private var hintUntil = 0L
    private var calibratingPlane = false
    private var overlay: LaserOverlayView? = null
    private var lastRaw: FloatArray = FloatArray(0)
    private var lastIn: FloatArray = FloatArray(0)
    private var lastOut: FloatArray = FloatArray(0)
    private var lastVol: FloatArray = FloatArray(0)
    private var moveMode = false
    private var suppressTap = false
    private var grabbedAxis = -1
    private val gizmoSegs = arrayOf(FloatArray(0), FloatArray(0), FloatArray(0))
    private var grabStartAnchor: FloatArray? = null
    private var grabDownX = 0f
    private var grabDownY = 0f
    private var grabAxisWorld = floatArrayOf(1f, 0f, 0f)
    private var grabPxPerMm = 1f
    private var lastK: DoubleArray = DoubleArray(0)
    private var yaw = 0.0
    private var rotating = false
    private var rotateStartYaw = 0.0
    private var rotateStartAngle = 0f
    private var ringSegs: FloatArray = FloatArray(0)
    private var anchorScreenX = 0f
    private var anchorScreenY = 0f
    private var boxMm = DEFAULT_BOX_MM
    private var boxHeightMm = DEFAULT_BOX_MM * 0.75
    private var placeHerePending = false
    private var boxOffsetMm = 10f
    private var spSelfTestStarted = false
    private var loggedTex = false
    private val io = Executors.newSingleThreadExecutor()
    private var kfDir: File? = null
    private var kfIndex = 0
    private var lastKfMs = 0L
    private var lastProcessNs = 0L
    private var lastGlFrameNs = 0L
    private var glFrames = 0
    private var glAccumMs = 0.0
    private var resWindowStart = 0L
    private var resWindowCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setRenderer(this@ArActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        val gestures = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: android.view.MotionEvent) {
                moveMode = !moveMode
                grabbedAxis = -1
                suppressTap = true
                if (!moveMode) {
                    runOnUiThread { overlay?.updateGizmo(FloatArray(0), FloatArray(0), FloatArray(0)) }
                    setHint("Move mode off.", 3000)
                } else {
                    setHint("Move mode: drag an arrow to move, or twist the yellow ring to rotate. Long-press again to finish.", 8000)
                }
            }
        })
        boxOffsetMm = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(PREF_BOX_OFFSET, 10f)
        surface.setOnTouchListener { v, e ->
            gestures.onTouchEvent(e)
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (moveMode) {
                        if (nearRing(e.x, e.y)) {
                            rotating = true
                            rotateStartYaw = yaw
                            rotateStartAngle = kotlin.math.atan2(e.y - anchorScreenY, e.x - anchorScreenX)
                        } else {
                            grabAxis(e.x, e.y)
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!moveMode) {
                        // camera gestures only
                    } else if (rotating) {
                        val ang = kotlin.math.atan2(e.y - anchorScreenY, e.x - anchorScreenX)
                        var d = ang - rotateStartAngle
                        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
                        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
                        yaw = rotateStartYaw - d
                        pointCount = 0
                    } else if (grabbedAxis >= 0) {
                        dragAxis(e.x, e.y)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (moveMode) {
                        grabbedAxis = -1
                        rotating = false
                    } else if (!suppressTap) {
                        tapPending = floatArrayOf(e.x, e.y)
                    }
                    suppressTap = false
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    grabbedAxis = -1
                    rotating = false
                    true
                }
                else -> true
            }
        }
        status = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xAA000000.toInt())
            textSize = 13f
            setPadding(24, 24, 24, 24)
            text = "Looking for ARCore…"
        }
        val root = FrameLayout(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        overlay = LaserOverlayView(this).also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 4, 16, 4)
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
            bar.addView(this)
        }
        fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            textSize = 11f
            minimumWidth = 0
            minimumHeight = 0
            setPadding(18, 6, 18, 6)
            setOnClickListener { action() }
            bar.addView(this)
        }
        button("CAL PLANE") { startPlaneCalibration() }
        smallButton("BOX-") { changeBox(-100.0) }
        smallButton("BOX+") { changeBox(100.0) }
        smallButton("SETTINGS") { showBoxSettings() }
        smallButton("BOX HERE") {
            placeHerePending = true
            setHint("Hold the phone where the box should stand; it will drop onto the table beneath it.", 5000)
        }
        button("Clear origin") { setAnchor(null) }
        button("Scan") { toggleScan() }
        button("Save PLY") { exportPly() }
        smallButton("SAVE SPLAT") { exportSplat() }
        val barScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xCC000000.toInt())
            val bottomInset = (28 * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, bottomInset)
            addView(
                bar,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(barScroll, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    // ── lifecycle ──────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
                null -> Unit
            }
            if (session == null) session = Session(this).also { configureSession(it) }
            session?.resume()
            if (processor == null) {
                processor = FrameProcessor(filesDir.absolutePath, this)
                processor?.configure(arConfig())
            }
            processor?.command(Cmd.AR_MODE, "1")
            resumed = true
            surface.onResume()
            status.text = "Move the phone. Tap the table next to the object to set the scan origin."
            if (!spSelfTestStarted) {
                spSelfTestStarted = true
                Thread {
                    try {
                        val kf = newestKeyframe()
                        if (kf == null) {
                            Log.i(TAG, "sp selftest: no keyframe yet")
                        } else {
                            Log.i(TAG, "sp selftest on " + kf.absolutePath + ": " + SuperPointRunner.runOnJpeg(this, kf))
                        }
                    } catch (ex: Exception) {
                        Log.e(TAG, "sp selftest failed", ex)
                    }
                }.start()
            }
        } catch (e: UnavailableArcoreNotInstalledException) {
            status.text = "ARCore is not installed (Google Play Services for AR)."
        } catch (e: UnavailableApkTooOldException) {
            status.text = "ARCore needs an update."
        } catch (e: UnavailableSdkTooOldException) {
            status.text = "ARCore SDK too old for this device."
        } catch (e: UnavailableDeviceNotCompatibleException) {
            status.text = "This device is not ARCore compatible."
        } catch (e: Exception) {
            Log.e(TAG, "AR session failed", e)
            status.text = "AR session failed: ${e.message}"
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        if (scanOn) {
            scanOn = false
            processor?.command(Cmd.SCAN_STOP)
        }
        surface.onPause()
        session?.pause()
        processor?.release()
        processor = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) onResume()
    }

    private fun configureSession(s: Session) {
        val config = Config(s)
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        config.focusMode = Config.FocusMode.AUTO
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        config.depthMode = try {
            Config.DepthMode.AUTOMATIC
        } catch (ex: Exception) {
            Config.DepthMode.DISABLED
        }
        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
        s.configure(config)
        val filter = CameraConfigFilter(s).apply {
            setTargetFps(java.util.EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_60, CameraConfig.TargetFps.TARGET_FPS_30))
            setDepthSensorUsage(java.util.EnumSet.of(CameraConfig.DepthSensorUsage.DO_NOT_USE))
            setFacingDirection(CameraConfig.FacingDirection.BACK)
        }
        val configs = s.getSupportedCameraConfigs(filter)
        val best = configs.maxWithOrNull(
            compareBy<CameraConfig> { it.fpsRange.upper }
                .thenBy { it.imageSize.width * it.imageSize.height }
        )
        if (best != null) s.cameraConfig = best
        Log.i(TAG, "camera fps=" + (best?.fpsRange?.upper ?: -1) + " configs=" + configs.size)
        for (cc in configs) {
            Log.i(TAG, "  camera option " + cc.imageSize.width + "x" + cc.imageSize.height +
                " fps=" + cc.fpsRange.lower + "-" + cc.fpsRange.upper)
        }
        Log.i(TAG, "camera config: ${best?.imageSize} intrinsics ok")
    }

    // ── UI ─────────────────────────────────────────────────────────────────

    private fun onSurfaceTouch(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) tapPending = floatArrayOf(e.x, e.y)
        return true
    }

    private fun copyLayer(r: FrameResult, kind: Int): FloatArray {
        var out = FloatArray(0)
        r.forEachLayer { k, stride, count, offset ->
            if (k == kind) {
                out = FloatArray(count * stride)
                System.arraycopy(r.data, offset, out, 0, out.size)
            }
        }
        return out
    }

    private fun mapToView(frame: Frame, src: FloatArray): FloatArray {
        if (src.isEmpty()) return src
        val n = src.size
        val inBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        inBuf.put(src).position(0)
        val outBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        return try {
            frame.transformCoordinates2d(com.google.ar.core.Coordinates2d.IMAGE_PIXELS, inBuf, com.google.ar.core.Coordinates2d.VIEW, outBuf)
            outBuf.position(0)
            val res = FloatArray(n)
            outBuf.get(res)
            res
        } catch (ex: Exception) {
            FloatArray(0)
        }
    }

    private fun pushOverlay(frame: Frame) {
        val o = overlay ?: return
        val raw = mapToView(frame, lastRaw)
        val ins = mapToView(frame, lastIn)
        val outs = mapToView(frame, lastOut)
        val vol = mapToView(frame, lastVol)
        runOnUiThread { o.update(raw, ins, outs, vol) }
    }

    private fun axisWorld(axis: Int): FloatArray {
        val c = kotlin.math.cos(yaw).toFloat()
        val sn = kotlin.math.sin(yaw).toFloat()
        return when (axis) {
            0 -> floatArrayOf(c, 0f, -sn)       // scan-frame +X, yawed about up
            1 -> floatArrayOf(-sn, 0f, -c)      // scan-frame +Y
            else -> floatArrayOf(0f, 1f, 0f)    // up
        }
    }

    /** Project world points (metres, x,y,z triples) to view pixels, or empty. */
    private fun projectWorld(frame: Frame, k: DoubleArray, pts: FloatArray): FloatArray {
        if (k.size < 4) return FloatArray(0)
        val m = FloatArray(16)
        frame.camera.pose.toMatrix(m, 0)
        val c0 = doubleArrayOf(m[0].toDouble(), m[1].toDouble(), m[2].toDouble())
        val c1 = doubleArrayOf(-m[4].toDouble(), -m[5].toDouble(), -m[6].toDouble())
        val c2 = doubleArrayOf(-m[8].toDouble(), -m[9].toDouble(), -m[10].toDouble())
        val camX = m[12].toDouble()
        val camY = m[13].toDouble()
        val camZ = m[14].toDouble()
        val n = pts.size / 3
        val img = FloatArray(n * 2)
        for (i in 0 until n) {
            val dx = pts[i * 3].toDouble() - camX
            val dy = pts[i * 3 + 1].toDouble() - camY
            val dz = pts[i * 3 + 2].toDouble() - camZ
            val xc = c0[0] * dx + c0[1] * dy + c0[2] * dz
            val yc = c1[0] * dx + c1[1] * dy + c1[2] * dz
            val zc = c2[0] * dx + c2[1] * dy + c2[2] * dz
            if (zc <= 0.01) return FloatArray(0)
            img[i * 2] = (k[0] * xc / zc + k[2]).toFloat()
            img[i * 2 + 1] = (k[1] * yc / zc + k[3]).toFloat()
        }
        return mapToView(frame, img)
    }

    /** Recompute the three move arrows around the capture box centre. */
    private fun updateGizmo(frame: Frame) {
        val a = anchor ?: return
        val len = 0.12f
        val ax = axisWorld(0)
        val ay = axisWorld(1)
        val az = axisWorld(2)
        val pts = floatArrayOf(
            a[0], a[1], a[2], a[0] + ax[0] * len, a[1] + ax[1] * len, a[2] + ax[2] * len,
            a[0], a[1], a[2], a[0] + ay[0] * len, a[1] + ay[1] * len, a[2] + ay[2] * len,
            a[0], a[1], a[2], a[0] + az[0] * len, a[1] + az[1] * len, a[2] + az[2] * len,
        )
        val v = projectWorld(frame, lastK, pts)
        val o = overlay
        if (v.size < 12 || o == null) {
            runOnUiThread { o?.updateGizmo(FloatArray(0), FloatArray(0), FloatArray(0)) }
            return
        }
        gizmoSegs[0] = floatArrayOf(v[0], v[1], v[2], v[3])
        gizmoSegs[1] = floatArrayOf(v[4], v[5], v[6], v[7])
        gizmoSegs[2] = floatArrayOf(v[8], v[9], v[10], v[11])
        val rw = FloatArray(37 * 3)
        val radius = (boxMm / 1000.0).toFloat() * 0.7f
        for (k in 0..36) {
            val ang = 2.0 * Math.PI * k / 36.0
            val ca = kotlin.math.cos(ang).toFloat()
            val sa = kotlin.math.sin(ang).toFloat()
            rw[k * 3] = a[0] + (ax[0] * ca + ay[0] * sa) * radius
            rw[k * 3 + 1] = a[1] + (ax[1] * ca + ay[1] * sa) * radius
            rw[k * 3 + 2] = a[2] + (ax[2] * ca + ay[2] * sa) * radius
        }
        val rv = projectWorld(frame, lastK, rw)
        if (rv.size >= 72) {
            val segs = FloatArray(36 * 4)
            for (k in 0 until 36) {
                segs[k * 4] = rv[k * 2]
                segs[k * 4 + 1] = rv[k * 2 + 1]
                segs[k * 4 + 2] = rv[(k + 1) * 2]
                segs[k * 4 + 3] = rv[(k + 1) * 2 + 1]
            }
            ringSegs = segs
            anchorScreenX = v[0]
            anchorScreenY = v[1]
            runOnUiThread { o.updateRing(ringSegs) }
        } else {
            ringSegs = FloatArray(0)
            runOnUiThread { o.updateRing(FloatArray(0)) }
        }
        runOnUiThread { o.updateGizmo(gizmoSegs[0], gizmoSegs[1], gizmoSegs[2]) }
    }

    private fun segmentDistance(px: Float, py: Float, s: FloatArray): Float {
        if (s.size < 4) return Float.MAX_VALUE
        val dx = s[2] - s[0]
        val dy = s[3] - s[1]
        val l2 = dx * dx + dy * dy
        if (l2 < 1f) return Float.MAX_VALUE
        var t = ((px - s[0]) * dx + (py - s[1]) * dy) / l2
        t = t.coerceIn(0f, 1f)
        val qx = s[0] + t * dx
        val qy = s[1] + t * dy
        val ex = px - qx
        val ey = py - qy
        return kotlin.math.sqrt(ex * ex + ey * ey)
    }

    private fun nearRing(x: Float, y: Float): Boolean {
        val segs = ringSegs
        if (segs.size < 8) return false
        var i = 0
        while (i + 3 < segs.size) {
            if (segmentDistance(x, y, floatArrayOf(segs[i], segs[i + 1], segs[i + 2], segs[i + 3])) < 55f) return true
            i += 4
        }
        return false
    }

    private fun grabAxis(x: Float, y: Float) {
        val a = anchor ?: return
        var best = -1
        var bestDist = 90f
        for (axis in 0..2) {
            val d = segmentDistance(x, y, gizmoSegs[axis])
            if (d < bestDist) {
                bestDist = d
                best = axis
            }
        }
        if (best < 0) return
        grabbedAxis = best
        grabStartAnchor = a.copyOf()
        grabDownX = x
        grabDownY = y
        grabAxisWorld = axisWorld(best)
        val s = gizmoSegs[best]
        val px = kotlin.math.sqrt((s[2] - s[0]) * (s[2] - s[0]) + (s[3] - s[1]) * (s[3] - s[1]))
        grabPxPerMm = if (px > 8f) px / 120f else 1f
        setHint("Moving along " + listOf("X", "Y", "Z")[best] + " - release to drop.", 3000)
    }

    private fun dragAxis(x: Float, y: Float) {
        val start = grabStartAnchor ?: return
        val s = gizmoSegs[grabbedAxis]
        if (s.size < 4) return
        val dx = s[2] - s[0]
        val dy = s[3] - s[1]
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len
        val along = (x - grabDownX) * ux + (y - grabDownY) * uy
        val mm = along / grabPxPerMm
        val d = grabAxisWorld
        val k = mm / 1000f
        anchor = floatArrayOf(start[0] + d[0] * k, start[1] + d[1] * k, start[2] + d[2] * k)
        pointCount = 0
    }

    /** Height of the nearest horizontal plane under a point, or null. */
    private fun planeYUnder(x: Float, z: Float): Float? {
        var best: Float? = null
        var bestD = Float.MAX_VALUE
        try {
            for (pl in session?.getAllTrackables(Plane::class.java) ?: emptyList<Plane>()) {
                if (pl.trackingState != TrackingState.TRACKING) continue
                if (pl.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
                val c = pl.centerPose.translation
                val d = (c[0] - x) * (c[0] - x) + (c[2] - z) * (c[2] - z)
                if (d < bestD) {
                    bestD = d
                    best = c[1]
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "plane scan failed: " + ex.message)
        }
        return best
    }

    /** Drop the box onto the horizontal plane directly under the phone and
     *  align its axes with the direction the phone is facing. */
    private fun placeBoxUnderPhone(frame: Frame) {
        val cam = frame.camera.pose.translation
        var planeY: Float? = null
        try {
            for (pl in session?.getAllTrackables(Plane::class.java) ?: emptyList<Plane>()) {
                if (pl.trackingState != TrackingState.TRACKING) continue
                if (pl.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
                // nearest horizontal plane under the phone: the object normally
                // stands on the surface you are pointing at. The SETTINGS offset
                // sinks the floor below it so the model base is not clipped.
                val y = pl.centerPose.translation[1]
                if (y < cam[1] && cam[1] - y < 1.2f && (planeY == null || y > planeY!!)) planeY = y
            }
        } catch (ex: Exception) {
            Log.w(TAG, "plane scan failed: " + ex.message)
        }
        val y = planeY
        if (y == null) {
            setHint("No table under the phone: point it at the table for a second, then press BOX HERE.")
            return
        }
        val floorY = y - boxOffsetMm / 1000f
        val m = FloatArray(16)
        frame.camera.pose.toMatrix(m, 0)
        // camera forward is -Z of the GL camera frame; its heading sets the box yaw
        yaw = kotlin.math.atan2(m[10].toDouble(), -m[8].toDouble())
        anchor = floatArrayOf(cam[0], floorY, cam[2])
        processor?.command(Cmd.CLOUD_CLEAR)
        pointCount = 0
        setHint("Box placed: its floor is 10 mm below the table so the model base is not clipped. Drag/arrows to fine-tune.", 6000)
    }

    private fun newestKeyframe(): File? {
        val base = getExternalFilesDir(null) ?: return null
        val dirs = base.listFiles { f -> f.isDirectory && f.name.startsWith("kf-") } ?: return null
        val dir = dirs.maxByOrNull { it.lastModified() } ?: return null
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun showBoxSettings() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(boxOffsetMm.toInt().toString())
            setSelection(text.length)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Box placement")
            .setMessage("How far below the surface under the phone should the box floor sit? (mm)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                boxOffsetMm = input.text.toString().toFloatOrNull()?.coerceIn(0f, 300f) ?: boxOffsetMm
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(PREF_BOX_OFFSET, boxOffsetMm).apply()
                setHint("BOX HERE floor offset: " + boxOffsetMm.toInt() + " mm below the surface.", 4000)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startPlaneCalibration() {
        val p = processor ?: return
        calibratingPlane = true
        pointCount = 0
        p.configure(AR_PLANE_CONFIG)
        p.command(Cmd.AR_MODE, "1")
        p.command(Cmd.PLANE_START)
        setHint("Point at the ChArUco board with the laser line crossing it; tilt it slowly.", 8000)
    }

    private fun finishPlaneCalibration() {
        if (!calibratingPlane) return
        calibratingPlane = false
        processor?.configure(arConfig())
        processor?.command(Cmd.AR_MODE, "1")
        setHint("Laser plane saved - tap the table, then scan.", 8000)
    }

    private fun setHint(text: String, ms: Long = 4000) {
        hint = text
        hintUntil = System.currentTimeMillis() + ms
        runOnUiThread { status.text = text }
    }

    private fun setAnchor(p: FloatArray?) {
        anchor = p
        processor?.command(Cmd.CLOUD_CLEAR)
        pointCount = 0
        if (p == null) {
            // no more frames are processed without an anchor: wipe the drawn
            // layers here too, otherwise the last box freezes in the viewfinder
            lastRaw = FloatArray(0)
            lastIn = FloatArray(0)
            lastOut = FloatArray(0)
            lastVol = FloatArray(0)
            runOnUiThread {
                overlay?.clear()
                overlay?.updateGizmo(FloatArray(0), FloatArray(0), FloatArray(0))
            }
            setHint("Origin cleared.")
        } else {
            setHint("Origin set. Point the laser and press Scan.")
        }
    }

    private fun toggleScan() {
        if (anchor == null) {
            Toast.makeText(this, "Tap the table to set the origin first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!scanOn) {
            kfDir = File(getExternalFilesDir(null), "kf-" + System.currentTimeMillis()).apply { mkdirs() }
            kfIndex = 0
            lastKfMs = 0L
            processor?.command(Cmd.CLOUD_CLEAR)
            processor?.command(Cmd.SCAN_START) { msg -> runOnUiThread { status.text = msg } }
            scanOn = true
        } else {
            scanOn = false
            processor?.command(Cmd.SCAN_STOP) { msg -> runOnUiThread { status.text = msg } }
            Log.i(TAG, "keyframes: " + kfIndex + " in " + kfDir)
            val dir = kfDir
            if (dir != null) {
                Thread {
                    try {
                        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
                            ?.sortedBy { it.name } ?: emptyList()
                        if (files.size >= 2) {
                            val msg = LoopCheck.relocalisation(this, files.first(), files.last())
                            Log.i(TAG, "sp loop " + files.first().name + ".." + files.last().name + ": " + msg)
                        }
                    } catch (ex: Exception) {
                        Log.e(TAG, "sp loop failed", ex)
                    }
                }.start()
            }
        }
    }

    private fun exportSplat() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val ply = File(dir, "ar-scan-$stamp.ply")
        val splat = File(dir, "ar-scan-$stamp.splat")
        processor?.command(Cmd.EXPORT_PLY, ply.absolutePath) { msg ->
            val n = try {
                SplatExporter.convert(ply, splat)
            } catch (ex: Exception) {
                -1
            }
            runOnUiThread {
                status.text = if (n > 0) "splat $n pts -> $splat" else "splat export failed: $msg"
            }
        }
    }

    private fun exportPly() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "ar-scan-$stamp.ply")
        processor?.command(Cmd.EXPORT_PLY, file.absolutePath) { msg ->
            runOnUiThread { status.text = msg }
        }
    }

    // ── FrameProcessor.Listener ────────────────────────────────────────────

    override fun onResult(r: FrameResult) {
        lastRaw = copyLayer(r, FrameResult.Layer.LASER_RAW)
        lastIn = copyLayer(r, FrameResult.Layer.LASER_IN)
        lastOut = copyLayer(r, FrameResult.Layer.LASER_OUT)
        lastVol = copyLayer(r, FrameResult.Layer.VOLUME)
        pointCount = r[FrameResult.H.CLOUD_POINTS].toInt()
        if (frames % 30 == 0) {
            val now2 = System.nanoTime()
            resWindowCount++
            if (resWindowStart == 0L) resWindowStart = now2
            var resRate = 0.0
            if (now2 - resWindowStart >= 2000000000L) {
                resRate = resWindowCount * 1e9 / (now2 - resWindowStart)
                resWindowCount = 0
                resWindowStart = now2
            }
            Log.i(TAG, "res laser=" + r[FrameResult.H.LASER_POINTS].toInt() +
                " inv=" + r[FrameResult.H.IN_VOLUME].toInt() +
                " roi=" + String.format("%.2f", r[FrameResult.H.LASER_ROI_FRAC]) +
                " cloud=" + pointCount +
                " proc_ms=" + String.format("%.1f", r.procMs) +
                " engine_ms=" + String.format("%.1f", r[FrameResult.H.T_TOTAL_MS].toDouble()) +
                " laser_ms=" + String.format("%.1f", r[FrameResult.H.T_LASER_MS].toDouble()) +
                " res_hz=" + String.format("%.1f", resRate))
        }
        if (!scanOn && pointCount > 0) {
            // command results already carry a message; keep the counter visible
        }
        runOnUiThread {
            if (scanOn) status.text = "$pointCount points · scanning"
        }
    }

    override fun onMessages(json: String) {
        Log.i(TAG, "messages: " + json)
        try {
            val o = org.json.JSONObject(json)
            val planeMsg = o.optString("plane")
            if (calibratingPlane && planeMsg.isNotEmpty()) {
                setHint(planeMsg, 6000)
                if (planeMsg.contains("SAVED plane")) finishPlaneCalibration()
            }
            val scanMsg = o.optString("scan")
            if (scanMsg.isNotEmpty()) setHint(scanMsg, 2500)
        } catch (_: Exception) {
        }
    }

    override fun onPhotoDue() = Unit

    override fun onError(msg: String) {
        Log.e(TAG, msg)
        runOnUiThread { status.text = msg }
    }

    // ── GL renderer ────────────────────────────────────────────────────────

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        Log.i(TAG, "GL vendor=" + GLES20.glGetString(GLES20.GL_VENDOR) + " renderer=" + GLES20.glGetString(GLES20.GL_RENDERER))
        program = buildProgram()
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        session?.setCameraTextureName(textureId)
        quadCoords = directFloat(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        quadTexCoords = directFloat(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewTx = width.toFloat()
        viewTy = height.toFloat()
        try {
            @Suppress("DEPRECATION")
            val rot = windowManager.defaultDisplay.rotation
            session?.setDisplayGeometry(rot, width, height)
        } catch (ex: Exception) {
            Log.w(TAG, "setDisplayGeometry failed: " + ex.message)
        }
    }

    override fun onDrawFrame(unused: GL10?) {
        if (!resumed) return
        val glNow = System.nanoTime()
        if (lastGlFrameNs != 0L) {
            glAccumMs += (glNow - lastGlFrameNs) / 1e6
            glFrames++
        }
        lastGlFrameNs = glNow
        val s = session
        if (s == null) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            return
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        s.setCameraTextureName(textureId)
        val frame = try {
            s.update()
        } catch (ex: Exception) {
            Log.w(TAG, "update failed: " + ex.message)
            return
        }
        drawBackground(frame)
        frames++
        val state = frame.camera.trackingState
        tapPending?.let { tap ->
            if (state == TrackingState.TRACKING) {
                tapPending = null
                placeAnchor(frame, tap[0], tap[1])
            } else {
                setHint("Wait for AR tracking, then tap the table again.")
            }
        }
        if (placeHerePending) {
            if (state == TrackingState.TRACKING) {
                placeHerePending = false
                placeBoxUnderPhone(frame)
            } else {
                placeHerePending = false
                setHint("Wait for AR tracking, then press BOX HERE again.")
            }
        }
        if (state == TrackingState.TRACKING && anchor != null &&
            System.nanoTime() - lastProcessNs >= 30000000L
        ) {
            lastProcessNs = System.nanoTime()
            processFrame(frame)
        }
        if (frames % 2 == 0 && state == TrackingState.TRACKING) pushOverlay(frame)
        if (moveMode && state == TrackingState.TRACKING) updateGizmo(frame)
        if (frames % 6 == 0) {
            val now = System.currentTimeMillis()
            val text = if (now < hintUntil) {
                hint
            } else {
                var text = "track=" + state + "  points=" + pointCount
                val a = anchor
                if (moveMode && a != null) {
                    val py = planeYUnder(a[0], a[2])
                    if (py != null) {
                        text += "  floor=" + ((a[1] - py) * 1000).toInt() + "mm"
                    }
                }
                text
            }
            runOnUiThread { status.text = text }
        }
        if (glFrames >= 90) {
            Log.i(TAG, "gl fps=" + String.format("%.1f", glFrames * 1000.0 / glAccumMs) +
                " frame_ms=" + String.format("%.1f", glAccumMs / glFrames))
            glFrames = 0
            glAccumMs = 0.0
        }
    }

    private fun drawBackground(frame: Frame) {
        GLES20.glUseProgram(program)
        quadCoords.position(0)
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadCoords,
            Coordinates2d.TEXTURE_NORMALIZED, quadTexCoords,
        )
        quadCoords.position(0)
        quadTexCoords.position(0)
        if (!loggedTex) {
            val arr = FloatArray(8)
            quadTexCoords.get(arr)
            quadTexCoords.position(0)
            Log.i(TAG, "texcoords " + arr.joinToString(","))
            loggedTex = true
        }
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    /** RGBA of the framebuffer at (x,y) in GL coordinates (origin bottom-left). */
    private fun readPixel(x: Int, y: Int): String {
        val b = java.nio.ByteBuffer.allocateDirect(4)
        GLES20.glReadPixels(x, y, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
        return String.format("%d,%d,%d", b.get(0).toInt() and 0xff, b.get(1).toInt() and 0xff, b.get(2).toInt() and 0xff)
    }

    /** Diagnostics: camera image size and mean luma (sampled Y plane), or why it failed. */
    private fun cameraStats(frame: Frame): String {
        val image = try {
            frame.acquireCameraImage()
        } catch (ex: Exception) {
            return "no image (" + (ex.message ?: "error") + ")"
        }
        try {
            val y = image.planes[0]
            val buf = y.buffer.duplicate()
            buf.rewind()
            val w = image.width
            val h = image.height
            var sum = 0L
            var n = 0
            val row = ByteArray(w)
            var yPos = 0
            var lines = 0
            while (lines < h && buf.remaining() >= w) {
                buf.position(yPos)
                buf.get(row, 0, w)
                for (v in row) { sum += (v.toInt() and 0xff); n++ }
                yPos += y.rowStride
                lines += 4
            }
            val mean = if (n > 0) sum.toDouble() / n else -1.0
            return w.toString() + "x" + h + " luma=" + String.format("%.0f", mean)
        } catch (ex: Exception) {
            return "stats failed"
        } finally {
            image.close()
        }
    }

    private fun placeAnchor(frame: Frame, x: Float, y: Float) {
        val hits = try {
            frame.hitTest(x, y)
        } catch (e: Exception) {
            return
        }
        // Prefer a horizontal, upward-facing plane (a table); then any plane; a depth
        // point is the last resort because it may be the object itself.
        val horizontal = hits.firstOrNull { h ->
            val tr = h.trackable
            tr is Plane && tr.trackingState == TrackingState.TRACKING &&
                tr.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        }
        val anyPlane = hits.firstOrNull { h ->
            val tr = h.trackable
            tr is Plane && tr.trackingState == TrackingState.TRACKING
        }
        val depthHit = hits.firstOrNull { h -> h.trackable is DepthPoint }
        val hit = horizontal ?: anyPlane ?: depthHit
        val t = hit?.hitPose?.translation
        if (t == null) {
            setHint("No surface under the tap: move the phone slowly over the table, then tap again.")
            return
        }
        if (hit !== horizontal) setHint("Origin set, but not on the table: tap the empty table next to the object for best results.", 6000)
        // drop the box floor a little below the tapped surface so the model base is not clipped
        setAnchor(floatArrayOf(t[0], t[1] - (boxOffsetMm / 1000f), t[2]))
    }

    private fun processFrame(frame: Frame) {
        val processor = processor ?: return
        val a = anchor ?: return
        val image = try {
            frame.acquireCameraImage()
        } catch (e: Exception) {
            return
        }
        try {
            val planes = image.planes
            if (planes.size < 3) return
            val y = copyDirect(planes[0].buffer)
            val u = copyDirect(planes[1].buffer)
            val v = copyDirect(planes[2].buffer)
            val k = intrinsics(frame, image.width, image.height)
            lastK = k
            val pose = boardToCamera(frame, a)
            val now = System.currentTimeMillis()
            if (scanOn && now - lastKfMs > 500) {
                lastKfMs = now
                val dir = kfDir
                if (dir != null) {
                    val planesCopy = arrayOf(
                        copyDirect(planes[0].buffer), copyDirect(planes[1].buffer), copyDirect(planes[2].buffer),
                    )
                    val strides = intArrayOf(planes[0].rowStride, planes[1].rowStride, planes[1].pixelStride)
                    val w = image.width
                    val h = image.height
                    val idx = kfIndex++
                    val poseCopy = pose.copyOf()
                    val kCopy = k.copyOf()
                    val ts = image.timestamp
                    io.execute { saveKeyframe(dir, idx, planesCopy, strides, w, h, ts, poseCopy, kCopy) }
                }
            }
            processor.processArFrame(
                y, planes[0].rowStride,
                u, v, planes[1].rowStride, planes[1].pixelStride,
                image.width, image.height, image.timestamp, pose, k, 1.0,
            )
        } finally {
            image.close()
        }
    }

    /** ARCore intrinsics re-expressed for the CPU image size, no distortion. */
    private fun intrinsics(frame: Frame, w: Int, h: Int): DoubleArray {
        val i = frame.camera.imageIntrinsics
        val dims = i.getImageDimensions()
        val iw = dims[0]
        val ih = dims[1]
        val sx = if (iw > 0) w.toDouble() / iw else 1.0
        val sy = if (ih > 0) h.toDouble() / ih else 1.0
        val fx = i.focalLength[0].toDouble() * sx
        val fy = i.focalLength[1].toDouble() * sy
        val cx = i.principalPoint[0].toDouble() * sx
        val cy = i.principalPoint[1].toDouble() * sy
        return doubleArrayOf(fx, fy, cx, cy, w.toDouble(), h.toDouble(), 0.0, 0.0, 0.0, 0.0, 0.0)
    }

    /**
     * Scan-frame -> camera (OpenCV convention) for this frame. The scan frame is
     * anchored on a horizontal ARCore plane, Z up (gravity), centred on the anchor.
     */
    private fun boardToCamera(frame: Frame, anchor: FloatArray): DoubleArray {
        val m = FloatArray(16)
        frame.camera.pose.toMatrix(m, 0) // column-major, ARCore (GL) camera axes
        // world<-camera with camera axes converted to OpenCV: x same, y and z flipped
        val c0 = doubleArrayOf(m[0].toDouble(), m[1].toDouble(), m[2].toDouble())
        val c1 = doubleArrayOf(-m[4].toDouble(), -m[5].toDouble(), -m[6].toDouble())
        val c2 = doubleArrayOf(-m[8].toDouble(), -m[9].toDouble(), -m[10].toDouble())
        // ARCore world is in METRES; the engine works in millimetres.
        val tw = doubleArrayOf(m[12].toDouble() * 1000.0, m[13].toDouble() * 1000.0, m[14].toDouble() * 1000.0)
        // camera<-world = (world<-camera)^T
        // camera<-world is the TRANSPOSE of world<-camera: row i is camera axis i.
        val r00 = c0[0]; val r01 = c0[1]; val r02 = c0[2]
        val r10 = c1[0]; val r11 = c1[1]; val r12 = c1[2]
        val r20 = c2[0]; val r21 = c2[1]; val r22 = c2[2]
        val tx = -(r00 * tw[0] + r01 * tw[1] + r02 * tw[2])
        val ty = -(r10 * tw[0] + r11 * tw[1] + r12 * tw[2])
        val tz = -(r20 * tw[0] + r21 * tw[1] + r22 * tw[2])
        // scan frame S in world, yawed about gravity: its axes are
        // x = (c,0,-s), y = (-s,0,-c), z = (0,1,0)
        val cY = kotlin.math.cos(yaw)
        val sY = kotlin.math.sin(yaw)
        val half = boxMm / 2.0
        // t_world_S = anchor - M * [half, half, 0], all in mm.
        val swx = anchor[0].toDouble() * 1000.0 - half * (cY - sY)
        val swy = anchor[1].toDouble() * 1000.0
        val swz = anchor[2].toDouble() * 1000.0 + half * (sY + cY)
        // R_cam_S = R_cw * M
        val s0x = r00 * cY + r02 * -sY
        val s0y = r10 * cY + r12 * -sY
        val s0z = r20 * cY + r22 * -sY
        val s1x = r00 * -sY + r02 * -cY
        val s1y = r10 * -sY + r12 * -cY
        val s1z = r20 * -sY + r22 * -cY
        val s2x = r01
        val s2y = r11
        val s2z = r21
        // t_cam_S = t_cam_world + R_cw * t_world_S
        val ex = tx + r00 * swx + r01 * swy + r02 * swz
        val ey = ty + r10 * swx + r11 * swy + r12 * swz
        val ez = tz + r20 * swx + r21 * swy + r22 * swz
        return doubleArrayOf(
            s0x, s1x, s2x, ex,
            s0y, s1y, s2y, ey,
            s0z, s1z, s2z, ez,
            0.0, 0.0, 0.0, 1.0,
        )
    }

    /** Convert one ARCore YUV_420_888 frame to a JPEG and append its pose row. */
    private fun saveKeyframe(
        dir: File,
        index: Int,
        planes: Array<ByteBuffer>,
        strides: IntArray,
        w: Int,
        h: Int,
        ts: Long,
        pose: DoubleArray,
        k: DoubleArray,
    ) {
        try {
            val yStride = strides[0]
            val uvStride = strides[1]
            val uvPixel = strides[2]
            val nv21 = ByteArray(w * h + w * h / 2)
            var p = 0
            for (row in 0 until h) {
                planes[0].position(row * yStride)
                planes[0].get(nv21, p, w)
                p += w
            }
            for (row in 0 until h / 2) {
                val vBase = row * uvStride
                for (col in 0 until w / 2) {
                    val vi = vBase + col * uvPixel
                    planes[2].position(vi)
                    nv21[p++] = planes[2].get()
                    planes[1].position(vi)
                    nv21[p++] = planes[1].get()
                }
            }
            val file = File(dir, String.format("kf_%04d.jpg", index))
            FileOutputStream(file).use { out ->
                YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 90, out)
            }
            val row = org.json.JSONObject().apply {
                put("i", index)
                put("t", ts)
                put("file", file.name)
                put("pose", org.json.JSONArray(pose.toList()))
                put("k", org.json.JSONArray(k.toList()))
            }
            File(dir, "keyframes.jsonl").appendText(row.toString() + System.lineSeparator())
        } catch (ex: Exception) {
            Log.w(TAG, "keyframe " + index + " failed: " + ex.message)
        }
    }

    private fun copyDirect(src: ByteBuffer): ByteBuffer {
        val dup = src.duplicate()
        dup.rewind()
        val out = ByteBuffer.allocateDirect(dup.remaining()).order(ByteOrder.nativeOrder())
        out.put(dup)
        out.rewind()
        return out
    }

    private fun directFloat(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            rewind()
        }

    private fun buildProgram(): Int {
        val vs = """
            attribute vec2 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                vTex = aTex;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTex);
            }
        """.trimIndent()
        fun compile(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "shader: " + GLES20.glGetShaderInfoLog(sh))
            return sh
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }
}
