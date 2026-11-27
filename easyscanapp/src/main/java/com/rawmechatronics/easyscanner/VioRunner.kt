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
 * Feeds Camera2 YUV frames and IMU samples into the native SuperVINS VIO.
 * SuperPoint + LightGlue (TFLite + QNN HTP delegate) drive the front-end on the NPU,
 * feeding the Ceres sliding-window nonlinear optimizer.
 */
class VioRunner(private val ctx: Context) : SensorEventListener {
    @Volatile private var vio = 0L
    private var sp = 0L
    private var sensors: SensorManager? = null
    private var imuThread: android.os.HandlerThread? = null
    private val imuLock = Any()
    private val interpAccel = FloatArray(3)
    private val accel = FloatArray(3)
    private val gyro = FloatArray(3)
    private val accelPrev = FloatArray(3)
    private var accelTs = 0.0
    private var accelPrevTs = -1.0
    private var gyroTs = 0.0
    private var haveAccel = false
    private var haveGyro = false
    @Volatile private var lastImuTs = 0.0
    private var lastFrameTs = 0.0
    private var lastVisualFrameTs = -1.0
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
    private var camOffMin = Double.POSITIVE_INFINITY
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
    @Volatile var lastSpeedMps = 0.0
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
    /** Packed [count, kp.xy..., desc...] for the last SuperPoint frame. */
    @Volatile var lastFeaturePacked: FloatArray? = null
    /** Sensor timestamp (ns) of [lastFeaturePacked]. */
    @Volatile var lastFeatureTimestampNs: Long = 0L
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
        // Always refresh the app-owned SuperVINS estimator config alongside the Kalibr calibration files.
        try {
            val svOut = File(dir, "supervins_config.yaml")
            ctx.assets.open("supervins/supervins_config.yaml").use { input ->
                svOut.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "supervins_config.yaml copy failed", e)
        }
        syncVioIntrinsics(dir)
        vio = NativeLib.nativeVioCreate(dir.absolutePath)
        if (vio == 0L) {
            Log.e(TAG, "SuperVINS create failed (config/parse error) - marker-free pose disabled")
            return
        }
        // Load XFeat (64-D descriptors, 640x480 grayscale) on QNN HTP NPU.
        var spHandle = 0L
        try {
            val model = AssetCopy.ensure(ctx, "xfeat.tflite", File(ctx.filesDir, "xfeat.tflite"))
            val h = SpTflite.create(ctx.applicationInfo.nativeLibraryDir, model.absolutePath, 0.0, 0.0, 0.0, 0.0)
            if (h != 0L && !(h >= -4L && h <= -1L)) {
                spHandle = h
                Log.i(TAG, "XFeat model: xfeat.tflite (" + model.length() + " bytes)")
            } else {
                Log.w(TAG, "XFeat model create failed (" + h + ")")
            }
        } catch (e: Exception) {
            Log.w(TAG, "XFeat model copy/init failed", e)
        }
        sp = spHandle
        // Extract top 400 XFeat keypoints to feed 400-kp LighterGlue (decoupled from 180 Ceres VIO tracks).
        SpTflite.setFeatureCap(400)
        // Handles are native pointers (Android tags the top byte, so they can look negative);
        // only 0 and the create error codes -1..-4 mean failure.
        if (sp == 0L || (sp >= -4L && sp <= -1L)) {
            Log.e(TAG, "XFeat engine create failed (" + sp + ") - marker-free pose disabled")
            sp = 0L
        }
        // XFeat LighterGlue NPU matcher: enabled by default unless explicitly disabled ("0") in Settings.
        try {
            val flag = File(ctx.filesDir, "lightglue.txt")
            val on = !flag.exists() || flag.readText().trim() != "0"
            if (on) {
                val lg = AssetCopy.ensure(ctx, "lighterglue.tflite", File(ctx.filesDir, "lighterglue.tflite"))
                val rc = NativeLib.nativeVioLightGlueInit(ctx.applicationInfo.nativeLibraryDir, lg.absolutePath)
                val qnn = rc == 0 && NativeLib.nativeVioLightGlueReady()
                NativeLib.nativeVioSetLightGlue(if (qnn) 1 else 0)
                Log.i(TAG, "LighterGlue init=" + rc + " enabled=" + qnn)
            } else {
                NativeLib.nativeVioSetLightGlue(0)
                Log.i(TAG, "LighterGlue disabled by settings")
            }
        } catch (e: Exception) {
            NativeLib.nativeVioSetLightGlue(0)
            Log.w(TAG, "LighterGlue init failed", e)
        }
        // Track-continuity PyrLK bridge for LighterGlue (on by default so single-frame blur
        // doesn't break 10-frame sliding-window tracks; disable via "0" in Settings -> Development).
        try {
            val kf = File(ctx.filesDir, "klt_fallback.txt")
            val on = !kf.exists() || kf.readText().trim() != "0"
            NativeLib.nativeVioSetKltFallback(if (on) 1 else 0)
            Log.i(TAG, "KLT track bridge=" + on)
        } catch (e: Exception) {
            Log.w(TAG, "KLT fallback read failed", e)
        }
        // Optional SuperVINS front-end tuning overrides from files/flood.txt.
        try {
            val f = java.io.File(ctx.filesDir, "flood.txt")
            if (f.exists()) {
                var mt = 180
                var md = 8.0f
                var mf = 400
                var rq = 0
                var lt = 0.10f
                for (line in f.readLines()) {
                    val p = line.split('=')
                    if (p.size == 2) {
                        when (p[0].trim()) {
                            "max_tracks" -> mt = p[1].trim().toIntOrNull() ?: mt
                            "min_dist" -> md = p[1].trim().toFloatOrNull() ?: md
                            "max_features" -> mf = p[1].trim().toIntOrNull() ?: mf
                            "reacq" -> rq = p[1].trim().toIntOrNull() ?: rq
                            "lg_threshold" -> lt = p[1].trim().toFloatOrNull()?.coerceIn(0.01f, 0.9f) ?: lt
                        }
                    }
                }
                NativeLib.nativeVioSetCaps(mt, md)
                SpTflite.setFeatureCap(mf)
                NativeLib.nativeVioSetReacq(rq)
                NativeLib.nativeVioSetLightGlueThreshold(lt, 0.20f)
                Log.i(TAG, "SuperVINS tuning: tracks=" + mt + " min_dist=" + md + " features=" + mf + " reacq=" + rq + " lg=" + lt)
            }
        } catch (e: Exception) {
            Log.w(TAG, "flood.txt read failed", e)
        }
        // Dedicated high-priority IMU thread so 480 Hz IMU events never stall behind SuperPoint/board processing.
        val tImu = android.os.HandlerThread("vio-imu", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
        imuThread = tImu
        val imuHandler = android.os.Handler(tImu.looper)
        sensors = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensors?.let { sm ->
            // Prefer uncalibrated sensors (raw values[0..2]) so Android's HAL bias filter never step-jumps OpenVINS.
            val aSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
                ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gSensor = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
                ?: sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            aSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, imuHandler) }
            gSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, imuHandler) }
            Log.i(TAG, "IMU sensors: accel=" + (aSensor?.stringType ?: "none") + " gyro=" + (gSensor?.stringType ?: "none"))
        }
        Log.i(TAG, "VIO started vio=" + vio + " sp=" + sp)
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(imuLock) {
        val type = event.sensor.type
        val evTs = event.timestamp * 1e-9
        if (type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED || type == Sensor.TYPE_ACCELEROMETER) {
            imuAccelEvents++
            if (haveAccel && evTs > accelTs) {
                accelPrev[0] = accel[0]; accelPrev[1] = accel[1]; accelPrev[2] = accel[2]
                accelPrevTs = accelTs
            }
            accelTs = evTs
            accel[0] = event.values[0]
            accel[1] = event.values[1]
            accel[2] = event.values[2]
            haveAccel = true
            return
        }
        if (type != Sensor.TYPE_GYROSCOPE_UNCALIBRATED && type != Sensor.TYPE_GYROSCOPE) return
        imuGyroEvents++
        gyroTs = evTs
        gyro[0] = event.values[0]
        gyro[1] = event.values[1]
        gyro[2] = event.values[2]
        haveGyro = true
        val vHandle = vio
        if (vHandle == 0L || !haveAccel) return
        val ts = evTs
        if (!(ts > lastImuTs)) { imuRejected++; return }
        // Linearly interpolate accelerometer onto the exact gyro timestamp (eliminates pairing skew).
        interpolateAccelToGyro(accelPrev, accelPrevTs, accel, accelTs, ts, interpAccel)
        lastAccelNorm = Math.sqrt(
            (interpAccel[0] * interpAccel[0] + interpAccel[1] * interpAccel[1] + interpAccel[2] * interpAccel[2]).toDouble()
        )
        imuFed++
        val aStale = Math.abs(ts - accelTs)
        val gStale = 0.0
        accelStaleSum += aStale
        gyroStaleSum += gStale
        if (aStale > staleMax) staleMax = aStale
        if (lastFedTs > 0.0) feedDtSum += (ts - lastFedTs)
        lastFedTs = ts
        statSamples++
        val gw = Math.sqrt((gyro[0] * gyro[0] + gyro[1] * gyro[1] + gyro[2] * gyro[2]).toDouble())
        gyroSum += gw
        if (gw > gyroMax) gyroMax = gw
        NativeLib.nativeVioFeedImu(vHandle, interpAccel[0], interpAccel[1], interpAccel[2], gyro[0], gyro[1], gyro[2], ts)
        lastImuTs = ts
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Feed the current Camera2 image and return its world-to-camera pose, or null if unavailable. */
    fun submitFrame(
        y: ByteArray, u: ByteArray, v: ByteArray,
        yStride: Int, uvStride: Int, uvPixelStride: Int,
        width: Int, height: Int, timestampNs: Long,
    ): DoubleArray? = synchronized(PROCESS_LOCK) {
        if (!tracking) { lastKp = null; return@synchronized null }
        if (vio == 0L || sp == 0L) return@synchronized null
        val timestampSec = timestampNs * 1e-9
        if (!(timestampSec > lastFrameTs)) return@synchronized null  // duplicate/reversed camera frames
        lastFrameTs = timestampSec
        tYConvMs = 0.0; tFeatMs = 0.0; tMaskMs = 0.0; tFeedMs = 0.0

        // SuperVINS sliding-window visual rate (~12 Hz, matching upstream 10 Hz design):
        // 10 window frames span ~0.8-1.0 s for strong SfM parallax and fewer LightGlue hops.
        // Between visual frames, return exact IMU-propagated poses at 30 Hz once initialized.
        if (lastVisualFrameTs > 0.0 && (timestampSec - lastVisualFrameTs) < VISUAL_FRAME_MIN_DT_SEC) {
            return@synchronized if (initialized) cameraPoseAtFrame(timestampSec) else null
        }
        val tStage0 = System.nanoTime()

        val gray = bilinearDownsampleY(y, yStride, width, height, GW, GH)
        tYConvMs = (System.nanoTime() - tStage0) / 1e6
        val tFeat0 = System.nanoTime()
        // XFeat takes 1-channel grayscale natively — no YUV-to-RGB conversion needed.
        val feat = SpTflite.features(sp, gray)
        tFeatMs = (System.nanoTime() - tFeat0) / 1e6
        if (feat == null) {
            if (frameCount % 30 == 0) Log.w(TAG, "features() null sp=" + sp)
            return@synchronized null
        }
        if (feat.isEmpty()) return@synchronized null
        lastFeaturePacked = feat
        lastFeatureTimestampNs = timestampNs
        val n = feat[0].toInt()
        lastDetN = n
        if (n <= 0) return@synchronized null
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
            return@synchronized null
        }
        val kp = FloatArray(nKeep * 2)
        val desc = FloatArray(nKeep * 64)
        var w = 0
        for (i in 0 until n) {
            if (!keep[i]) continue
            kp[w * 2] = feat[1 + i * 2]
            kp[w * 2 + 1] = feat[2 + i * 2]
            System.arraycopy(feat, 1 + n * 2 + i * 64, desc, w * 64, 64)
            w++
        }
        tMaskMs = (System.nanoTime() - tMask0) / 1e6
        lastFeatureCount = nKeep
        lastKp = if (spOverlay) kp else null
        val curImuTs = lastImuTs
        if (curImuTs > 0.0) {
            val lead = curImuTs - timestampSec
            camOffSum += lead
            camOffMin = minOf(camOffMin, lead)
            camOffN++
        }
        val tFeed0 = System.nanoTime()
        if (!NativeLib.nativeVioFeedFrame(vio, gray, timestampSec, kp, desc)) {
            tFeedMs = (System.nanoTime() - tFeed0) / 1e6
            return@synchronized null
        }
        lastVisualFrameTs = timestampSec
        val now = NativeLib.nativeVioInitialized(vio)
        if (now != initialized) {
            initialized = now
            Log.i(TAG, "VIO initialized=" + now)
        }
        val pose = if (now) cameraPoseAtFrame(timestampSec) else null
        if (pose != null && NativeLib.nativeVioGetState(vio, stPos, stVel, stBg, stBa, stQuat)) {
            lastSpeedMps = Math.sqrt(stVel[0] * stVel[0] + stVel[1] * stVel[1] + stVel[2] * stVel[2])
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
                " rej=" + imuRejected + " dt=%.2fms aStale=%.2fms gStale=%.2fms staleMax=%.2fms gyro=%.1f/%.1fdeg imuAheadAtSubmit(mean/min)=%.1f/%.1fms".format(
                    feedDtSum / ns * 1000.0, accelStaleSum / ns * 1000.0, gyroStaleSum / ns * 1000.0, staleMax * 1000.0,
                    Math.toDegrees(gyroSum / ns), Math.toDegrees(gyroMax),
                    if (camOffN > 0) camOffSum / camOffN * 1000.0 else 0.0,
                    (if (camOffN > 0) camOffMin * 1000.0 else 0.0)))
            accelStaleSum = 0.0; gyroStaleSum = 0.0; staleMax = 0.0
            feedDtSum = 0.0; lastFedTs = 0.0; statSamples = 0; imuRejected = 0
            gyroSum = 0.0; gyroMax = 0.0; camOffSum = 0.0; camOffMin = Double.POSITIVE_INFINITY; camOffN = 0
        }
        if (frameCount % 60 == 0) Log.i(TAG, "VIO frames=" + frameCount + " init=" + initialized + " features=" + n +
                " imu: accelEv=" + imuAccelEvents + " gyroEv=" + imuGyroEvents + " fed=" + imuFed +
                " reversed=" + imuRejected + " |a|=" + String.format(java.util.Locale.US, "%.2f", lastAccelNorm))
        pose
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
            val scaled = scaleIntrinsicsForVio(
                o.optDouble("fx", 0.0), o.optDouble("fy", 0.0),
                o.optDouble("cx", 0.0), o.optDouble("cy", 0.0),
                w, h, GW.toDouble(), GH.toDouble(),
            )
            val fx = scaled[0]; val fy = scaled[1]; val cx = scaled[2]; val cy = scaled[3]
            val aspectDiff = abs((w / h) - (GW.toDouble() / GH.toDouble())) / (GW.toDouble() / GH.toDouble())
            if (aspectDiff > 0.01) {
                Log.w(TAG, String.format(java.util.Locale.US, "live_intrinsics aspect %.3f differs from 640x480 (%.0fx%.0f)", w / h, w, h))
            }
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

    /** World-to-camera 4x4 matrix for this camera timestamp, not the latest available pose. */
    private fun cameraPoseAtFrame(timestampSec: Double): DoubleArray? {
        val p = DoubleArray(3)
        val q = DoubleArray(4)
        if (!NativeLib.nativeVioGetPoseAtFrame(vio, timestampSec, p, q)) return null
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
        if (trajectory.size > 4000) trajectory.removeAt(0)
        trajectory.add(floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat()))
        val out = DoubleArray(16)
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
        return out
    }

    /** Laser stripe pixels (analysis-frame x,y pairs) from the latest engine frame. */
    fun setLaserPixels(px: FloatArray, w: Int, h: Int) {
        laserPx = px
        laserW = w
        laserH = h
    }

    /** Feed calibrated camera-frame 3D laser points (mm) into SuperVINS Ceres for tight metric scale locking. */
    fun feedLaserPointsCamMm(timestampNs: Long, xyzMm: FloatArray, count: Int) {
        val vHandle = vio
        if (vHandle == 0L || count < 3 || !tracking) return
        NativeLib.nativeVioFeedLaserPoints(vHandle, timestampNs * 1e-9, xyzMm, count)
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

    /** Path length (sum of camera-position steps) in metres. */
    fun traveledMeters(): Double {
        var sum = 0.0
        var px = 0.0; var py = 0.0; var pz = 0.0
        var have = false
        for (p in trajectory) {
            if (p.size < 3 || !p[0].isFinite() || !p[1].isFinite() || !p[2].isFinite()) continue
            if (have) sum += kotlin.math.sqrt((p[0]-px)*(p[0]-px) + (p[1]-py)*(p[1]-py) + (p[2]-pz)*(p[2]-pz)).toDouble()
            px = p[0].toDouble(); py = p[1].toDouble(); pz = p[2].toDouble(); have = true
        }
        return sum
    }

    /** Straight-line displacement from the first trajectory point, metres. */
    fun netMeters(): Double {
        var first: FloatArray? = null
        var last: FloatArray? = null
        for (p in trajectory) {
            if (p.size < 3 || !p[0].isFinite() || !p[1].isFinite() || !p[2].isFinite()) continue
            if (first == null) first = p
            last = p
        }
        val a = first ?: return 0.0
        val b = last ?: return 0.0
        val dx = (b[0]-a[0]).toDouble(); val dy = (b[1]-a[1]).toDouble(); val dz = (b[2]-a[2]).toDouble()
        return kotlin.math.sqrt(dx*dx + dy*dy + dz*dz)
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
        imuThread?.quitSafely()
        try { imuThread?.join(300) } catch (_: InterruptedException) {}
        imuThread = null
        synchronized(imuLock) {
            val h = vio
            vio = 0L
            if (h != 0L) NativeLib.nativeVioDestroy(h)
        }
        if (sp != 0L) {
            SpTflite.close(sp)
            sp = 0L
        }
        initialized = false
        tracking = false
        lastKp = null
        lastSpeedMps = 0.0
        frameCount = 0
        haveAccel = false
        haveGyro = false
        accelTs = 0.0
        accelPrevTs = -1.0
        gyroTs = 0.0
        lastImuTs = 0.0
        lastFrameTs = 0.0
        lastVisualFrameTs = -1.0
        imuAccelEvents = 0L
        imuGyroEvents = 0L
        imuFed = 0L
        imuRejected = 0L
        lastAccelNorm = 0.0
        released = true
        if (ACTIVE === this) ACTIVE = null
    }

    companion object {
        private val PROCESS_LOCK = Any()
        @Volatile private var ACTIVE: VioRunner? = null
        private const val TAG = "VioRunner"
        const val GW = 640
        const val GH = 480
        private const val VISUAL_FRAME_MIN_DT_SEC = 0.055
        private val CONFIGS = listOf("estimator_config.yaml", "kalibr_imu_chain.yaml", "kalibr_imucam_chain.yaml")

        /** Exact pixel-centre intrinsics scaling matching `easyscan-core/algo/src/geom.rs::Intrinsics::for_frame`. */
        internal fun scaleIntrinsicsForVio(
            fx: Double, fy: Double, cx: Double, cy: Double,
            srcW: Double, srcH: Double, dstW: Double = 640.0, dstH: Double = 480.0,
        ): DoubleArray {
            val sx = dstW / srcW
            val sy = dstH / srcH
            return doubleArrayOf(fx * sx, fy * sy, (cx + 0.5) * sx - 0.5, (cy + 0.5) * sy - 0.5)
        }

        /** Pixel-centred bilinear Y downsample avoiding the asymmetric -0.5 px odd-row shift on 960x720 -> 640x480. */
        internal fun bilinearDownsampleY(
            y: ByteArray, yStride: Int, srcW: Int, srcH: Int, dstW: Int = GW, dstH: Int = GH,
        ): ByteArray {
            val out = ByteArray(dstW * dstH)
            if (srcW == dstW && srcH == dstH) {
                for (yy in 0 until dstH) System.arraycopy(y, yy * yStride, out, yy * dstW, dstW)
                return out
            }
            val scaleX = srcW.toDouble() / dstW
            val scaleY = srcH.toDouble() / dstH
            val x0Lut = IntArray(dstW)
            val x1Lut = IntArray(dstW)
            val wxLut = IntArray(dstW) // fixed-point 0..256
            for (xx in 0 until dstW) {
                val fx = ((xx + 0.5) * scaleX - 0.5).coerceIn(0.0, (srcW - 1).toDouble())
                val x0 = fx.toInt()
                x0Lut[xx] = x0
                x1Lut[xx] = (x0 + 1).coerceAtMost(srcW - 1)
                wxLut[xx] = ((fx - x0) * 256.0).toInt().coerceIn(0, 256)
            }
            for (yy in 0 until dstH) {
                val fy = ((yy + 0.5) * scaleY - 0.5).coerceIn(0.0, (srcH - 1).toDouble())
                val y0 = fy.toInt()
                val y1 = (y0 + 1).coerceAtMost(srcH - 1)
                val wy = ((fy - y0) * 256.0).toInt().coerceIn(0, 256)
                val iwy = 256 - wy
                val row0 = y0 * yStride
                val row1 = y1 * yStride
                val outRow = yy * dstW
                for (xx in 0 until dstW) {
                    val x0 = x0Lut[xx]; val x1 = x1Lut[xx]
                    val wx = wxLut[xx]; val iwx = 256 - wx
                    val v00 = y[row0 + x0].toInt() and 0xff
                    val v10 = y[row0 + x1].toInt() and 0xff
                    val v01 = y[row1 + x0].toInt() and 0xff
                    val v11 = y[row1 + x1].toInt() and 0xff
                    val top = v00 * iwx + v10 * wx
                    val bot = v01 * iwx + v11 * wx
                    out[outRow + xx] = ((top * iwy + bot * wy + 32768) ushr 16).toByte()
                }
            }
            return out
        }

        /** Linearly interpolates accelerometer onto the exact gyro timestamp [ts] with bounded extrapolation. */
        internal fun interpolateAccelToGyro(
            aPrev: FloatArray, tPrev: Double, aCur: FloatArray, tCur: Double, ts: Double, out: FloatArray,
        ) {
            val dt = tCur - tPrev
            if (tPrev > 0.0 && dt > 1e-5 && dt < 0.05 && abs(ts - tCur) < 0.01) {
                val alpha = ((ts - tPrev) / dt).coerceIn(0.0, 1.5).toFloat()
                out[0] = aPrev[0] + (aCur[0] - aPrev[0]) * alpha
                out[1] = aPrev[1] + (aCur[1] - aPrev[1]) * alpha
                out[2] = aPrev[2] + (aCur[2] - aPrev[2]) * alpha
            } else {
                out[0] = aCur[0]; out[1] = aCur[1]; out[2] = aCur[2]
            }
        }
    }
}