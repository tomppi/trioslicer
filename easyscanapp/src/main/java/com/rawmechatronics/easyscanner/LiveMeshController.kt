package com.rawmechatronics.easyscanner

import android.content.Context
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives live multi-patch DA3 mesh creation and 30 fps metric laser detail refinement
 * inside EasyScanner. [PatchMap] extends the registered surface only after a successful
 * stitch; a rejected view leaves that surface visible and available for a retry.
 */
class LiveMeshController(
    private val ctx: Context,
    private val meshView: LiveMeshView,
    private val onStatus: (String) -> Unit,
    private val onMeshVisible: (Boolean) -> Unit = {},
) {
    enum class PoseMode(val label: String) {
        VIO("VIO"),
        BOARD("Board"),
    }

    enum class DepthSource(val label: String) {
        MVS("MVS"),
        DA3("DA3"),
    }

    private enum class State { IDLE, WAITING_SEED, RUNNING_DA3, WAITING_LASER_SCALE, LIVE_REFINING }
    private enum class ScaleSource(val id: String) { LASER("laser"), OVERLAP("overlap"), FOCUS_DISTANCE("focus_distance") }

    private class PendingPatch(
        val targetMap: PatchMap,
        val generation: Int,
        val rawDepth: FloatArray,
        val rgb518: ByteArray,
        val rawPose: RigidPose,
        val boardPose: RigidPose?,
        val camera: DepthCamera,
        val isInitial: Boolean,
        val features: FloatArray? = null,
        val pairs: ArrayList<Pair<Double, Double>> = ArrayList(),
        val bufferedLaser: ArrayList<Pair<RigidPose, FloatArray>> = ArrayList(),
    )

    private class PendingLaser(
        val targetMap: PatchMap,
        val generation: Int,
        val rawPose: RigidPose,
        val boardPose: RigidPose?,
        val laserCamM: FloatArray,
    )

    private class PendingMvsLaser(
        val tsdfHandle: Long,
        val generation: Int,
        val pose: RigidPose,
        val laserCamM: FloatArray,
    )

    private val main = Handler(Looper.getMainLooper())
    private val npuExec = Executors.newSingleThreadExecutor { r -> Thread(r, "live-mesh-npu") }
    private val rebuildExec = Executors.newSingleThreadExecutor { r -> Thread(r, "live-mesh-build") }
    private val rebuildScheduled = AtomicBoolean(false)
    private val bgPatchInFlight = AtomicBoolean(false)
    private val laserQueue = ConcurrentLinkedQueue<PendingLaser>()
    private val mvsLaserQueue = ConcurrentLinkedQueue<PendingMvsLaser>()
    private val laserDrainScheduled = AtomicBoolean(false)
    private val mvsLaserDrainScheduled = AtomicBoolean(false)
    private val npuSession = Da3NpuRunner.PersistentSession(ctx)

    @Volatile var active = false
        private set
    @Volatile var poseMode: PoseMode = PoseMode.VIO
        private set
    @Volatile var depthSource: DepthSource = DepthSource.MVS
        private set
    @Volatile var autoPatch: Boolean = true
    @Volatile var useTsdf: Boolean = true
    @Volatile var sensorOrientation: Int = 90

    @Volatile private var state = State.IDLE
    @Volatile private var map: PatchMap = newLiveMap()
    @Volatile private var pending: PendingPatch? = null
    @Volatile private var manualPatchRequested = false
    @Volatile private var seedGeneration = 0
    @Volatile private var lastPatchFinishedMs = 0L
    @Volatile private var lastRebuildMs = 0L
    @Volatile private var lastCoverageCheckMs = 0L
    @Volatile private var cachedDist: DoubleArray? = null
    @Volatile private var lastStatusMs = 0L
    @Volatile private var lastIcpSummary = ""
    @Volatile private var metricLaserScaled = false
    @Volatile private var da3WarmedUp = false

    // Native Coarse Plane-Sweep MVS + direct TSDF handles (used when depthSource == DepthSource.MVS).
    @Volatile private var mvsHandle: Long = 0L
    @Volatile private var mvsTsdfHandle: Long = 0L
    @Volatile private var mvsKeyframeCount: Int = 0
    @Volatile private var mvsLastMs: Int = 0
    @Volatile private var mvsHasFramed: Boolean = false
    @Volatile private var mvsHasLaser: Boolean = false
    @Volatile private var mvsLastPose: RigidPose? = null
    /** Live optical-center distance (mm) raycast to the active TSDF mesh (works even with laser OFF). */
    @Volatile var liveCenterDepthMm: Double = Double.NaN
        private set
    @Volatile private var lastRaycastMs: Long = 0L
    private val raycastInFlight = AtomicBoolean(false)
    /** Optional focus-plane distance (metres) used as nominal initial depth when laser is off. */
    @Volatile var nominalDepthM: Double = 0.50
    /** Live VIO velocity supplier (m/s) so diverged VIO pauses auto-patching instead of spawning 0%-overlap submaps. */
    @Volatile var vioSpeedProvider: (() -> Double)? = null
    /** Latest packed SuperPoint features from the VIO front-end. */
    @Volatile var featureProvider: (() -> FloatArray?)? = null
    private var lastGoodPoseCentre: DoubleArray? = null

    fun start(mode: PoseMode) {
        poseMode = mode
        active = true
        cachedDist = loadSavedDistortion()
        if (depthSource == DepthSource.DA3) warmUpDa3IfNeeded()
        resetMap()
    }

    private fun warmUpDa3IfNeeded() {
        if (da3WarmedUp) return
        da3WarmedUp = true
        npuExec.execute {
            runCatching { npuSession.warmUp() }
            // Separate QNN LighterGlue engine for patch matching; 64-D XFeat MNN loop index on CPU.
            runCatching { LoopMatcher.init(ctx) }
            runCatching {
                LoopDb.load("xfeat64")
                LoopDb.clear()
                Log.i(TAG, "loop matcher=" + LoopMatcher.isReady() + " db=" + LoopDb.isLoaded())
            }
        }
    }

    fun setDepthSource(src: DepthSource) {
        if (depthSource == src) return
        depthSource = src
        if (src == DepthSource.DA3 && active) warmUpDa3IfNeeded()
        if (active) {
            postStatus("Switched rough mesh to " + src.label + " — resetting…")
            resetMap()
        }
    }

    fun setMeshMode(tsdf: Boolean) {
        if (useTsdf == tsdf) return
        useTsdf = tsdf
        if (active && map.patchCount() > 0) scheduleRebuild(map, reframe = false)
    }

    fun setPoseMode(mode: PoseMode) {
        if (poseMode == mode) return
        poseMode = mode
        if (active) {
            postStatus("Switched pose to " + mode.label + " — resetting map…")
            resetMap()
        }
    }

    private val nativeRwLock = java.util.concurrent.locks.ReentrantReadWriteLock()
    private val cleanupExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "live-mesh-cleanup").apply { isDaemon = true }
    }

    /** Requests a new overlapping DA3 patch from the current viewpoint (stitched into the map). */
    fun requestAddPatch() {
        if (!active) return
        if (depthSource == DepthSource.MVS) {
            postStatus("MVS captures automatically as you move sideways (~4 cm)")
            return
        }
        if (map.patchCount() == 0) {
            resetMap()
            return
        }
        if (bgPatchInFlight.get()) {
            postStatus("Patch capture already running on NPU…")
            return
        }
        manualPatchRequested = true
        postStatus("Capturing next DA3 patch…")
    }

    /** Clears all patches/submaps and seeds a fresh live mesh. */
    fun resetMap() {
        if (!active) return
        seedGeneration++
        val oldMap = map
        map = newLiveMap()
        releaseNativeAsync(oldMap, takeMvsHandles())
        lastPatchFinishedMs = 0L
        lastCoverageCheckMs = 0L
        pending = null
        laserQueue.clear()
        mvsLaserQueue.clear()
        manualPatchRequested = false
        bgPatchInFlight.set(false)
        lastIcpSummary = ""
        metricLaserScaled = false
        lastGoodPoseCentre = null
        mvsKeyframeCount = 0
        mvsLastMs = 0
        mvsHasFramed = false
        mvsHasLaser = false
        mvsLastPose = null
        liveCenterDepthMm = Double.NaN
        lastRaycastMs = 0L
        meshView.clearMesh()
        main.post { onMeshVisible(false) }
        if (depthSource == DepthSource.MVS && NativeLiveTsdf.available) {
            mvsHandle = NativeLiveTsdf.nativeMvsCreate()
            mvsTsdfHandle = NativeLiveTsdf.nativeCreate(0.004f, 0.016f, 40_000, 1.5)
            state = State.LIVE_REFINING
            postStatus("MVS · waiting for " + poseMode.label + " pose — move gently (~4 cm)…")
        } else {
            state = State.WAITING_SEED
            postStatus("Waiting for " + poseMode.label + " pose + calibrated frame…")
        }
    }

    fun reseed() = resetMap()

    fun stop() {
        active = false
        seedGeneration++
        state = State.IDLE
        pending = null
        laserQueue.clear()
        mvsLaserQueue.clear()
        manualPatchRequested = false
        bgPatchInFlight.set(false)
        val oldMap = map
        map = newLiveMap()
        releaseNativeAsync(oldMap, takeMvsHandles())
        liveCenterDepthMm = Double.NaN
        meshView.clearMesh()
        main.post { onMeshVisible(false) }
    }

    private fun takeMvsHandles(): Pair<Long, Long> {
        val mh = mvsHandle
        val th = mvsTsdfHandle
        mvsHandle = 0L
        mvsTsdfHandle = 0L
        return mh to th
    }

    /**
     * Frees native TSDF/MVS pointers only after acquiring [nativeRwLock.writeLock], guaranteeing
     * that no worker thread (`camera`, `npuExec`, `rebuildExec`, or GL thread) can dereference
     * a destroyed handle, without ever blocking the UI thread.
     */
    private fun releaseNativeAsync(oldMap: PatchMap?, handles: Pair<Long, Long>) {
        val (mh, th) = handles
        if (oldMap == null && mh == 0L && th == 0L) return
        cleanupExec.execute {
            val wl = nativeRwLock.writeLock()
            wl.lock()
            try {
                if (mh != 0L) runCatching { NativeLiveTsdf.nativeMvsDestroy(mh) }
                if (th != 0L) runCatching { NativeLiveTsdf.nativeDestroy(th) }
                runCatching { oldMap?.release() }
            } finally {
                wl.unlock()
            }
        }
    }

    private inline fun <T> withNativeReadLock(block: () -> T): T {
        val rl = nativeRwLock.readLock()
        rl.lock()
        return try {
            block()
        } finally {
            rl.unlock()
        }
    }

    fun hasMesh(): Boolean =
        if (depthSource == DepthSource.MVS) (mvsHasFramed || mvsHasLaser) else map.patchCount() > 0

    /** Rebuild the live TSDF after loop-closure pose corrections. */
    fun applyPoseCorrections(correction: (RigidPose) -> RigidPose): Int {
        if (!active) return 0
        val curMap = map
        val n = curMap.applyPoseCorrections(correction)
        if (n > 0) scheduleRebuild(curMap, reframe = true)
        return n
    }

    /** Called on [FrameProcessor]'s processing thread for every camera frame. */
    fun onFrame(img: Image, r: FrameResult, vioPose: DoubleArray?) {
        if (!active) return
        val curState = state
        if (curState == State.IDLE) return

        val mode = poseMode
        val boardPose = extractBoardPose(r)
        val rawPose = if (mode == PoseMode.VIO) {
            vioPose?.let { RigidPose.fromRowMajor16(it, 1.0) }
        } else boardPose
        val liveCam = extractCamera(r, img.width, img.height)

        if (depthSource == DepthSource.MVS && mvsHandle != 0L && mvsTsdfHandle != 0L) {
            onFrameMvs(img, r, rawPose, liveCam, mode)
            return
        }

        when (curState) {
            State.WAITING_SEED -> {
                if (liveCam == null) {
                    throttledStatus("Live mesh: waiting for calibrated camera K…")
                    return
                }
                if (rawPose == null) {
                    throttledStatus(
                        if (mode == PoseMode.VIO) "Live mesh: waiting for VIO init (track VIO & move gently)…"
                        else "Live mesh: point at calibration board for Board pose…"
                    )
                    return
                }
                val rgb518 = Da3NpuRunner.extractRgb518(img)
                val seedLaser = extractLaserMeters(r)
                val seedFeatures = featureProvider?.invoke()?.copyOf()
                val gen = seedGeneration
                val capturedMap = map
                state = State.RUNNING_DA3
                bgPatchInFlight.set(true)
                postStatus("Running Depth-Anything-3 on NPU (" + mode.label + ")…")
                npuExec.execute {
                    runPatchOnNpu(gen, capturedMap, rgb518, rawPose, boardPose, liveCam, seedLaser, seedFeatures, isInitial = true)
                }
            }
            State.RUNNING_DA3 -> {
                if (rawPose != null && liveCam != null) {
                    val subPose = map.poseInActiveSubmap(rawPose, boardPose)
                    meshView.updateLiveFrame(subPose, liveCam, sensorOrientation, FloatArray(0))
                }
            }
            State.WAITING_LASER_SCALE -> {
                val p = pending ?: return
                if (rawPose == null) {
                    throttledStatus("DA3 ready — waiting for " + mode.label + " pose + laser stripe…")
                    return
                }
                val laserCamM = extractLaserMeters(r)
                if (laserCamM.size >= 3) {
                    val worldLaser = collectScalePairs(p, rawPose, laserCamM)
                    val subPose = map.poseInActiveSubmap(rawPose, boardPose)
                    meshView.updateLiveFrame(subPose, liveCam, sensorOrientation, worldLaser)
                    if (p.pairs.size >= MIN_SCALE_PAIRS) {
                        val scale = AdaptiveDepthMesh.fitScale(p.pairs, MIN_SCALE_PAIRS)
                        if (scale != null && scale > 0.0 && scale.isFinite()) {
                            commitPatch(p, scale, ScaleSource.LASER)
                            return
                        }
                        if (p.pairs.size > 400) p.pairs.subList(0, 200).clear()
                    }
                    throttledStatus("DA3 ready — sweep laser across target to lock scale (" + p.pairs.size + "/" + MIN_SCALE_PAIRS + ")")
                } else {
                    throttledStatus("DA3 ready — turn on laser and aim at target to scale mesh")
                }
            }
            State.LIVE_REFINING -> {
                val curMap = map
                if (rawPose == null) {
                    throttledStatus(mode.label + " pose lost — " + curMap.patchCount() + " patches")
                    return
                }
                // Guard against OpenVINS velocity divergence so we never spam 0%-overlap submaps 50m away.
                if (mode == PoseMode.VIO) {
                    val speed = vioSpeedProvider?.invoke() ?: 0.0
                    val cNow = rawPose.cameraCentre()
                    val prevC = lastGoodPoseCentre
                    val stepJump = if (prevC != null) {
                        val dx = cNow[0] - prevC[0]; val dy = cNow[1] - prevC[1]; val dz = cNow[2] - prevC[2]
                        kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                    } else 0.0
                    if (speed > 1.5 || stepJump > 0.25) {
                        throttledStatus(String.format(
                            Locale.US,
                            "VIO diverged (|v|=%.1f m/s) — tap Reset or switch Pose: Board",
                            speed,
                        ))
                        return
                    }
                    lastGoodPoseCentre = cNow
                }
                val laserCamM = extractLaserMeters(r)
                val subPose = curMap.poseInActiveSubmap(rawPose, boardPose)
                val da3Tsdf = curMap.activeNativeTsdfHandle()
                if (da3Tsdf != 0L) scheduleCenterDepthRaycast(da3Tsdf, seedGeneration, subPose)
                if (laserCamM.size >= 3) {
                    // Transform laser preview lock-free on the camera thread so VIO & preview stay at full FPS,
                    // then enqueue TSDF laser carving onto rebuildExec (never stalls camera or GLThread).
                    val laserWorld = transformLaserToWorld(subPose, laserCamM)
                    meshView.updateLiveFrame(subPose, liveCam, sensorOrientation, laserWorld)
                    enqueueLaserCarving(curMap, seedGeneration, rawPose, boardPose, laserCamM)
                } else {
                    meshView.updateLiveFrame(subPose, liveCam, sensorOrientation, FloatArray(0))
                }

                // Coverage-triggered or manual new DA3 patch (runs asynchronously on NPU without stalling laser).
                val now = SystemClock.elapsedRealtime()
                val wantsManual = manualPatchRequested
                if (liveCam != null && !bgPatchInFlight.get() &&
                    (wantsManual || (autoPatch && now - lastPatchFinishedMs >= AUTO_PATCH_COOLDOWN_MS &&
                        now - lastCoverageCheckMs >= 250L))
                ) {
                    lastCoverageCheckMs = now
                    val shouldRun = if (wantsManual) {
                        manualPatchRequested = false
                        true
                    } else {
                        val cov = curMap.evaluateCoverage(rawPose, boardPose, laserCamM)
                        cov.shouldCapture && (cov.reason == "retry" ||
                            laserCamM.size >= MIN_SCALE_PAIRS * 3 || !metricLaserScaled)
                    }
                    if (shouldRun && bgPatchInFlight.compareAndSet(false, true)) {
                        val rgb518 = Da3NpuRunner.extractRgb518(img)
                        val seedFeatures = featureProvider?.invoke()?.copyOf()
                        val gen = seedGeneration
                        val isManual = wantsManual
                        postStatus("Trying to stitch the next DA3 view…")
                        npuExec.execute {
                            runPatchOnNpu(gen, curMap, rgb518, rawPose, boardPose, liveCam, laserCamM, seedFeatures, isInitial = false, isManual = isManual)
                        }
                    }
                }
            }
            State.IDLE -> {}
        }
    }

    /**
     * Coarse real-time Plane-Sweep Multi-View Stereo driven directly by metric VIO/Board poses.
     * No DA3 NPU inference, no monocular scale guessing, and laser stripes carve directly into
     * the exact same metric TSDF volume with high authority.
     */
    private fun onFrameMvs(
        img: Image,
        r: FrameResult,
        rawPose: RigidPose?,
        liveCam: DepthCamera?,
        mode: PoseMode,
    ) {
        if (liveCam == null) {
            throttledStatus("MVS · waiting for calibrated camera K…")
            return
        }
        if (rawPose == null) {
            throttledStatus(
                if (mode == PoseMode.VIO) "MVS · waiting for VIO init (hold still, then move gently)…"
                else "MVS · point at calibration board for Board pose…"
            )
            return
        }
        if (mode == PoseMode.VIO) {
            val speed = vioSpeedProvider?.invoke() ?: 0.0
            val cNow = rawPose.cameraCentre()
            val prevC = lastGoodPoseCentre
            val stepJump = if (prevC != null) {
                val dx = cNow[0] - prevC[0]; val dy = cNow[1] - prevC[1]; val dz = cNow[2] - prevC[2]
                kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            } else 0.0
            if (speed > 1.5 || stepJump > 0.25) {
                throttledStatus(String.format(
                    Locale.US, "VIO diverged (|v|=%.1f m/s) — tap Reset or switch Pose: Board", speed,
                ))
                return
            }
            lastGoodPoseCentre = cNow
        }

        val mvsH = mvsHandle
        val tsdfH = mvsTsdfHandle
        if (mvsH == 0L || tsdfH == 0L) return
        val gen = seedGeneration
        mvsLastPose = rawPose
        scheduleCenterDepthRaycast(tsdfH, gen, rawPose)

        // 1. Live metric laser overlay + background TSDF carving (zero blocking on camera thread).
        val laserCamM = extractLaserMeters(r)
        if (laserCamM.size >= 3) {
            val laserWorld = transformLaserToWorld(rawPose, laserCamM)
            meshView.updateLiveFrame(rawPose, liveCam, sensorOrientation, laserWorld)
            enqueueMvsLaserCarving(tsdfH, gen, rawPose, laserCamM)
        } else {
            meshView.updateLiveFrame(rawPose, liveCam, sensorOrientation, FloatArray(0))
        }

        // 2. Plane-sweep MVS keyframe capture whenever the camera moves >= 3.2 cm or rotates >= 8 deg.
        val shouldAcceptMvs = !bgPatchInFlight.get() && withNativeReadLock {
            active && seedGeneration == gen && mvsHandle == mvsH &&
                NativeLiveTsdf.nativeMvsShouldAccept(mvsH, rawPose.r, rawPose.t, 0.032, 8.0)
        }
        if (shouldAcceptMvs && bgPatchInFlight.compareAndSet(false, true)) {
            val planes = img.planes
            val gray = ByteArray(160 * 120)
            val rgb = ByteArray(160 * 120 * 3)
            NativeLiveTsdf.nativeMvsDownscaleYuv(
                planes[0].buffer, planes[0].rowStride,
                planes[1].buffer, planes[2].buffer, planes[1].rowStride, planes[1].pixelStride,
                img.width, img.height, gray, rgb,
            )
            val cam11 = doubleArrayOf(
                liveCam.fx, liveCam.fy, liveCam.cx, liveCam.cy,
                liveCam.width.toDouble(), liveCam.height.toDouble(),
                liveCam.k1, liveCam.k2, liveCam.p1, liveCam.p2, liveCam.k3,
            )
            val focusM = nominalDepthM.coerceIn(0.22, 1.10)
            npuExec.execute {
                try {
                    val st = IntArray(4)
                    val ran = withNativeReadLock {
                        if (!active || seedGeneration != gen || mvsHandle != mvsH || mvsTsdfHandle != tsdfH) {
                            false
                        } else {
                            NativeLiveTsdf.nativeMvsPushAndFuse(
                                mvsH, tsdfH, gray, rgb, rawPose.r, rawPose.t, cam11, focusM, st,
                            )
                            true
                        }
                    }
                    if (!ran || !active || seedGeneration != gen || mvsTsdfHandle != tsdfH) return@execute
                    mvsKeyframeCount = st[0]
                    mvsLastMs = st[2]
                    Log.i(TAG, String.format(
                        Locale.US, "MVS kf=%d neighbors=%d validPx=%d elapsed=%dms",
                        st[0], st[3], st[1], st[2],
                    ))
                    if (st[0] <= 1) {
                        postStatus("MVS · 1 kf — move phone sideways (~4 cm) to build rough mesh")
                    } else if (st[1] >= 60) {
                        scheduleMvsRebuild(tsdfH, gen, rawPose, reframe = !mvsHasFramed, force = true)
                    } else if (!mvsHasFramed && !mvsHasLaser) {
                        postStatus(String.format(
                            Locale.US, "MVS · %d kf — move sideways over textured area (%d views)",
                            st[0], st[3],
                        ))
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "MVS keyframe fuse failed", t)
                } finally {
                    bgPatchInFlight.set(false)
                }
            }
        }
    }

    private fun scheduleCenterDepthRaycast(tsdfH: Long, gen: Int, pose: RigidPose) {
        if (tsdfH == 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRaycastMs < 110L) return
        if (!raycastInFlight.compareAndSet(false, true)) return
        lastRaycastMs = now
        rebuildExec.execute {
            try {
                val dM = withNativeReadLock {
                    val live = active && seedGeneration == gen &&
                        (mvsTsdfHandle == tsdfH || map.activeNativeTsdfHandle() == tsdfH)
                    if (!live) Double.NaN
                    else NativeLiveTsdf.nativeRaycastCenterDepthM(tsdfH, pose.r, pose.t).toDouble()
                }
                liveCenterDepthMm = if (dM.isFinite() && dM in 0.06..2.5) dM * 1000.0 else Double.NaN
            } catch (_: Throwable) {
                liveCenterDepthMm = Double.NaN
            } finally {
                raycastInFlight.set(false)
            }
        }
    }

    private fun enqueueMvsLaserCarving(tsdfH: Long, gen: Int, pose: RigidPose, laserCamM: FloatArray) {
        mvsLaserQueue.add(PendingMvsLaser(tsdfH, gen, pose, laserCamM))
        if (!mvsLaserDrainScheduled.compareAndSet(false, true)) return
        rebuildExec.execute {
            try {
                var hits = 0
                var lastPose = pose
                while (true) {
                    val item = mvsLaserQueue.poll() ?: break
                    val n = withNativeReadLock {
                        if (!active || seedGeneration != item.generation || mvsTsdfHandle != item.tsdfHandle) {
                            0
                        } else {
                            NativeLiveTsdf.nativeIntegrateLaser(
                                item.tsdfHandle, item.pose.r, item.pose.t, item.laserCamM, TsdfVolume.LASER_AUTHORITY_WEIGHT,
                            )
                        }
                    }
                    if (n > 0) {
                        hits += n
                        lastPose = item.pose
                    }
                }
                if (hits > 0 && active && seedGeneration == gen && mvsTsdfHandle == tsdfH) {
                    mvsHasLaser = true
                    scheduleMvsRebuild(tsdfH, gen, lastPose, reframe = !mvsHasFramed, force = false)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "MVS laser carving failed", t)
            } finally {
                mvsLaserDrainScheduled.set(false)
            }
        }
    }

    private fun scheduleMvsRebuild(
        tsdfH: Long,
        gen: Int,
        refPose: RigidPose,
        reframe: Boolean,
        force: Boolean,
    ) {
        if (!force) {
            if (!reframe && System.currentTimeMillis() - lastRebuildMs < REBUILD_MIN_PERIOD_MS) return
            if (!rebuildScheduled.compareAndSet(false, true)) return
        }
        rebuildExec.execute {
            try {
                val preTris = withNativeReadLock {
                    if (!active || seedGeneration != gen || mvsTsdfHandle != tsdfH) 0
                    else NativeLiveTsdf.nativePrepareMesh(tsdfH)
                }
                if (preTris <= 0) return@execute
                lastRebuildMs = System.currentTimeMillis()
                val doReframe = reframe || !mvsHasFramed
                mvsHasFramed = true
                val kfs = mvsKeyframeCount
                val ms = mvsLastMs
                main.post { onMeshVisible(true) }
                meshView.updateNativeTsdf(
                    handle = tsdfH,
                    refPose = refPose,
                    reframe = doReframe,
                    nativeLock = nativeRwLock,
                    isValid = { active && seedGeneration == gen && mvsTsdfHandle == tsdfH },
                ) { tris, _, laserBlocks ->
                    postStatus(String.format(
                        Locale.US,
                        "MVS · %s · %d kf · %.1fk tris · %d laser · %dms",
                        poseMode.label, kfs, tris / 1000f, laserBlocks, ms,
                    ))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "MVS mesh rebuild failed", t)
            } finally {
                if (!force) rebuildScheduled.set(false)
            }
        }
    }

    private fun transformLaserToWorld(pose: RigidPose, laserCamM: FloatArray): FloatArray {
        val out = FloatArray(laserCamM.size)
        var w = 0
        var i = 0
        while (i + 2 < laserCamM.size) {
            val lx = laserCamM[i].toDouble()
            val ly = laserCamM[i + 1].toDouble()
            val lz = laserCamM[i + 2].toDouble()
            i += 3
            if (!lx.isFinite() || !ly.isFinite() || !lz.isFinite() || lz <= 0.02 || lz > 20.0) continue
            val wp = pose.cameraToWorld(lx, ly, lz)
            out[w++] = wp[0].toFloat()
            out[w++] = wp[1].toFloat()
            out[w++] = wp[2].toFloat()
        }
        return if (w == out.size) out else out.copyOf(w)
    }

    private fun enqueueLaserCarving(
        curMap: PatchMap,
        gen: Int,
        rawPose: RigidPose,
        boardPose: RigidPose?,
        laserCamM: FloatArray,
    ) {
        laserQueue.add(PendingLaser(curMap, gen, rawPose, boardPose, laserCamM))
        if (!laserDrainScheduled.compareAndSet(false, true)) return
        rebuildExec.execute {
            try {
                var anyAccepted = false
                while (true) {
                    val item = laserQueue.poll() ?: break
                    if (!isCurrent(item.targetMap, item.generation)) continue
                    val (stats, _) = item.targetMap.addLaserFrame(item.rawPose, item.boardPose, item.laserCamM)
                    if (stats.acceptedPoints > 0) anyAccepted = true
                }
                if (anyAccepted && isCurrent(curMap, gen)) {
                    scheduleRebuild(curMap, reframe = false)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "background laser carving failed", t)
            } finally {
                laserDrainScheduled.set(false)
                if (laserQueue.isNotEmpty() && active) {
                    val next = laserQueue.peek()
                    if (next != null && isCurrent(next.targetMap, next.generation) &&
                        laserDrainScheduled.compareAndSet(false, true)
                    ) {
                        rebuildExec.execute {
                            try {
                                var accepted = false
                                while (true) {
                                    val item = laserQueue.poll() ?: break
                                    if (!isCurrent(item.targetMap, item.generation)) continue
                                    if (item.targetMap.addLaserFrame(item.rawPose, item.boardPose, item.laserCamM).first.acceptedPoints > 0) {
                                        accepted = true
                                    }
                                }
                                if (accepted && isCurrent(curMap, gen)) scheduleRebuild(curMap, reframe = false)
                            } finally {
                                laserDrainScheduled.set(false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun runPatchOnNpu(
        gen: Int,
        capturedMap: PatchMap,
        rgb518: ByteArray,
        rawPose: RigidPose,
        boardPose: RigidPose?,
        cam: DepthCamera,
        seedLaserM: FloatArray,
        seedFeatures: FloatArray?,
        isInitial: Boolean,
        isManual: Boolean = false,
    ) {
        try {
            if (!isCurrent(capturedMap, gen)) return
            val t0 = System.nanoTime()
            val rawDepth = runCatching { npuSession.infer(rgb518) }.getOrElse { e ->
                Log.e(TAG, "DA3 live patch failed", e)
                if (isCurrent(capturedMap, gen)) {
                    if (isInitial) state = State.IDLE
                    postStatus("DA3 failed: " + (e.message ?: e.javaClass.simpleName))
                }
                return
            }
            val elapsedMs = (System.nanoTime() - t0) / 1e6
            Log.i(TAG, String.format(Locale.US, "DA3 live inference %.0f ms (initial=%b)", elapsedMs, isInitial))
            if (!isCurrent(capturedMap, gen)) return

            val patch = PendingPatch(capturedMap, gen, rawDepth, rgb518, rawPose, boardPose, cam, isInitial, seedFeatures)
            if (seedLaserM.size >= 3) collectScalePairs(patch, rawPose, seedLaserM)
            val laserScale = AdaptiveDepthMesh.fitScale(patch.pairs, MIN_SCALE_PAIRS)
            if (laserScale != null && laserScale > 0.0 && laserScale.isFinite()) {
                commitPatch(patch, laserScale, ScaleSource.LASER, isManual = isManual)
            } else {
                val attachScale = if (!isInitial) {
                    capturedMap.estimateScaleFromExistingSurface(rawPose, boardPose, cam, rawDepth)
                } else null
                val targetDepth = nominalDepthM.coerceIn(0.25, 1.50)
                val scale = attachScale ?: estimateNominalScale(rawDepth, targetDepth)
                if (scale != null && scale > 0.0 && scale.isFinite()) {
                    val source = if (attachScale != null) ScaleSource.OVERLAP else ScaleSource.FOCUS_DISTANCE
                    commitPatch(patch, scale, source, targetDepth, isManual = isManual)
                } else if (isCurrent(capturedMap, gen)) {
                    if (isInitial) {
                        pending = patch
                        state = State.WAITING_LASER_SCALE
                        postStatus(String.format(Locale.US, "DA3 %.0f ms — aim laser at target to scale mesh", elapsedMs))
                    } else {
                        postStatus("No valid depth scale for this view — kept the existing mesh")
                    }
                }
            }
        } finally {
            if (isCurrent(capturedMap, gen)) {
                lastPatchFinishedMs = SystemClock.elapsedRealtime()
                bgPatchInFlight.set(false)
            }
        }
    }

    private fun collectScalePairs(seed: PendingPatch, framePose: RigidPose, laserCamM: FloatArray): FloatArray {
        val n = laserCamM.size / 3
        val worldOut = FloatArray(n * 3)
        var w = 0
        var i = 0
        while (i + 2 < laserCamM.size) {
            val lx = laserCamM[i].toDouble()
            val ly = laserCamM[i + 1].toDouble()
            val lz = laserCamM[i + 2].toDouble()
            i += 3
            if (!lx.isFinite() || !ly.isFinite() || !lz.isFinite() || lz <= 0.02 || lz > 20.0) continue
            val world = framePose.cameraToWorld(lx, ly, lz)
            worldOut[w * 3] = world[0].toFloat()
            worldOut[w * 3 + 1] = world[1].toFloat()
            worldOut[w * 3 + 2] = world[2].toFloat()
            w++
            val ref = seed.rawPose.worldToCamera(world[0], world[1], world[2])
            val zRef = ref[2]
            if (zRef <= 0.02 || zRef > 20.0) continue
            val uv = seed.camera.project(ref[0], ref[1], zRef) ?: continue
            if (uv[0] < 0.0 || uv[0] >= seed.camera.width || uv[1] < 0.0 || uv[1] >= seed.camera.height) continue
            val d = AdaptiveDepthMesh.sampleDepthBilinear(seed.rawDepth, uv[0], uv[1], seed.camera)
            if (d > 0f && d.isFinite()) seed.pairs.add(d.toDouble() to zRef)
        }
        if (seed.bufferedLaser.size < 30) seed.bufferedLaser.add(framePose to laserCamM)
        return if (w * 3 == worldOut.size) worldOut else worldOut.copyOf(w * 3)
    }

    private fun isCurrent(target: PatchMap, generation: Int): Boolean =
        active && seedGeneration == generation && map === target

    private fun commitPatch(
        patch: PendingPatch,
        scale: Double,
        source: ScaleSource,
        focusDepthM: Double? = null,
        isManual: Boolean = false,
    ) {
        val curMap = patch.targetMap
        if (!isCurrent(curMap, patch.generation)) return
        val detail = if (source == ScaleSource.FOCUS_DISTANCE && focusDepthM != null) {
            String.format(Locale.US, " target=%.2fm", focusDepthM)
        } else ""
        Log.i(TAG, String.format(Locale.US, "DA3 scale=%.4f source=%s%s", scale, source.id, detail))
        val seedLaser = patch.bufferedLaser.firstOrNull()?.second ?: FloatArray(0)
        val metricCandidate = metricLaserScaled || source == ScaleSource.LASER
        val icpCfg = if (metricCandidate) {
            LaserIcp.Config(minOverlapFraction = 0.22, maxMedianResidualMm = 6.0, maxCorrectionTranslationM = 0.08)
        } else {
            // Rough monocular DA3 (laser off) has slight relative curvature error between views.
            LaserIcp.Config(maxCorrDistM = 0.04, maxMedianResidualMm = 12.0, maxCorrectionTranslationM = 0.10)
        }
        // Puzzle rule: skip pasting redundant patches over areas already covered by existing pieces.
        val minNovelty = if (patch.isInitial) 0f else if (isManual) 0.03f else 0.08f
        val outcome = curMap.addPatch(
            rawPose = patch.rawPose,
            boardPose = patch.boardPose,
            camera = patch.camera,
            rawDepth = patch.rawDepth,
            scale = scale,
            offset = 0.0,
            rgb518 = patch.rgb518,
            laserCamM = seedLaser,
            features = patch.features,
            icpConfigOverride = icpCfg,
            minNoveltyFraction = minNovelty,
        )
        if (!isCurrent(curMap, patch.generation)) return
        if (outcome.visualOldId >= 0) {
            val solved = curMap.optimizeAndRebuild()
            Log.i(TAG, "patch pose graph solved=" + solved + " old=" + outcome.visualOldId)
        }
        val patchId = outcome.patchId
        val reg = outcome.registration
        if (reg != null) {
            val label = patchId?.let { "patch #$it" } ?: "patch rejected (kept ${outcome.patchCount})"
            Log.i(TAG, String.format(
                Locale.US,
                "%s submap=%d fused=%b scale=%s overlap=%.0f%% res=%.1f->%.1fmm dT=%.1fmm dR=%.2fdeg reason=%s",
                label, outcome.submapId, outcome.fusedIntoActiveSubmap, source.id,
                reg.overlapFraction * 100.0, reg.initialMedianResidualMm, reg.finalMedianResidualMm,
                reg.correctionTranslationM * 1000.0, reg.correctionRotationDeg, reg.reason,
            ))
        }
        pending = null
        lastPatchFinishedMs = SystemClock.elapsedRealtime()
        if (patchId == null) {
            val reason = reg?.reason ?: "no alignment"
            lastIcpSummary = if (reason.startsWith("already covered")) reason else "stitch rejected: $reason"
            state = State.LIVE_REFINING
            if (reason.startsWith("already covered")) {
                postStatus("Already covered (${outcome.patchCount} pieces) — move to an unscanned side")
            } else {
                postStatus("Piece didn't fit ($reason) — move back toward an edge")
            }
            return
        }

        metricLaserScaled = metricCandidate
        // Only apply the candidate's buffered laser points after that candidate is registered.
        for (k in 1 until patch.bufferedLaser.size) {
            val (fp, lp) = patch.bufferedLaser[k]
            curMap.addLaserFrame(fp, null, lp)
        }
        lastIcpSummary = if (reg == null) "" else String.format(
            Locale.US, "%s %.1fmm", if (reg.usedMarkerAnchor) "Board+ICP" else "ICP", reg.finalMedianResidualMm,
        )
        state = State.LIVE_REFINING
        scheduleRebuild(curMap, reframe = patch.isInitial, committedPatch = true)
    }

    private fun scheduleRebuild(curMap: PatchMap, reframe: Boolean, committedPatch: Boolean = false) {
        // Refinement builds are throttled, but a committed patch always gets an upload request.
        if (!committedPatch) {
            if (!reframe && System.currentTimeMillis() - lastRebuildMs < REBUILD_MIN_PERIOD_MS) return
            if (!rebuildScheduled.compareAndSet(false, true)) return
        }
        rebuildExec.execute {
            try {
                if (!active || map !== curMap) return@execute
                val tsdfOn = useTsdf
                val refPose = curMap.latestPatch()?.pose ?: return@execute
                val modeStr = curMap.motionMode().takeIf { it != "unknown" } ?: "-"
                val icpPart = if (lastIcpSummary.isNotEmpty()) " · " + lastIcpSummary else ""
                val scaleTag = if (metricLaserScaled) "" else " · rough"
                val nativeHandle = if (tsdfOn) curMap.activeNativeTsdfHandle() else 0L
                if (nativeHandle != 0L) {
                    // Off-heap native C++ Marching Cubes -> direct GLES3 GPU VBO upload (0 B Java heap!).
                    val preTris = withNativeReadLock {
                        if (!active || map !== curMap) 0 else curMap.prepareActiveNativeTsdf()
                    }
                    lastRebuildMs = System.currentTimeMillis()
                    if (preTris > 0) {
                        val pCount = curMap.patchCount()
                        val sCount = curMap.submapCount()
                        main.post { onMeshVisible(true) }
                        meshView.updateNativeTsdf(
                            handle = nativeHandle,
                            refPose = refPose,
                            reframe = reframe,
                            nativeLock = nativeRwLock,
                            isValid = { active && map === curMap && curMap.activeNativeTsdfHandle() == nativeHandle },
                        ) { tris, _, laserBlocks ->
                            postStatus(String.format(
                                Locale.US,
                                "%s · GPU-TSDF%s · %s · %dp/%ds · %.1fk tris · %d laser%s",
                                poseMode.label, scaleTag, modeStr, pCount, sCount,
                                tris / 1000f, laserBlocks, icpPart,
                            ))
                        }
                    }
                    return@execute
                }
                val data = curMap.buildActiveSubmapMesh(useTsdf = tsdfOn) ?: return@execute
                lastRebuildMs = System.currentTimeMillis()
                meshView.updateMesh(data, refPose, reframe)
                if (data.triangleCount > 0) main.post { onMeshVisible(true) }
                val kindStr = if (tsdfOn) "TSDF" else "Patch"
                postStatus(String.format(
                    Locale.US,
                    "%s · %s%s · %s · %dp/%ds · %.1fk tris · %d laser%s",
                    poseMode.label, kindStr, scaleTag, modeStr, curMap.patchCount(), curMap.submapCount(),
                    data.triangleCount / 1000f, data.refinedBlocks, icpPart,
                ))
            } catch (t: Throwable) {
                Log.e(TAG, "live mesh rebuild failed", t)
            } finally {
                if (!committedPatch) rebuildScheduled.set(false)
            }
        }
    }

    /** Saves the active submap's refined adaptive mesh in millimetres as a binary PLY. */
    fun saveMeshPly(onDone: (File?, String) -> Unit) {
        if (depthSource == DepthSource.MVS) {
            val tsdfH = mvsTsdfHandle
            if (tsdfH == 0L || (!mvsHasFramed && !mvsHasLaser)) {
                main.post { onDone(null, "No live MVS mesh to save yet") }
                return
            }
            val kfs = mvsKeyframeCount
            rebuildExec.execute {
                val res = runCatching {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val dir = File(ctx.filesDir, "scans").apply { mkdirs() }
                    val file = File(dir, "live-mesh-" + stamp + ".ply")
                    val (tris, st) = withNativeReadLock {
                        check(active && mvsTsdfHandle == tsdfH) { "MVS session closed" }
                        val t = NativeLiveTsdf.nativeWriteMeshPly(tsdfH, file.absolutePath, true)
                        val arr = IntArray(5)
                        NativeLiveTsdf.nativeGetStats(tsdfH, arr)
                        t to arr
                    }
                    require(tris > 0) { "empty MVS TSDF mesh" }
                    file to String.format(
                        Locale.US,
                        "Saved %s (%d kf, %d tris, %d laser blocks)",
                        file.name, kfs, tris, st[1],
                    )
                }
                main.post {
                    res.onSuccess { (file, msg) -> onDone(file, msg) }
                        .onFailure { e -> onDone(null, "Save failed: " + (e.message ?: e.javaClass.simpleName)) }
                }
            }
            return
        }
        val curMap = map
        if (curMap.patchCount() == 0) {
            main.post { onDone(null, "No live mesh to save yet") }
            return
        }
        rebuildExec.execute {
            val res = runCatching {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val dir = File(ctx.filesDir, "scans").apply { mkdirs() }
                val file = File(dir, "live-mesh-" + stamp + ".ply")
                val traj = File(dir, "live-mesh-" + stamp + ".trajectory.txt")
                val trajN = curMap.writeTrajectory(traj)
                if (useTsdf && curMap.activeNativeTsdfHandle() != 0L) {
                    val tris = curMap.writeActiveNativeMeshPly(file, toMillimetres = true)
                    require(tris > 0) { "empty TSDF mesh" }
                    val st = curMap.activeTsdfStats()
                    file to String.format(
                        Locale.US,
                        "Saved %s (%d patches, %d tris, %d laser blocks, %d poses)",
                        file.name, curMap.activeSubmapPatchCount(), tris, st?.laserBlocks ?: 0, trajN,
                    )
                } else {
                    val data = curMap.buildActiveSubmapMesh(useTsdf = useTsdf) ?: error("empty submap")
                    AdaptiveDepthMesh.writeMeshPly(file, data, toMillimetres = true)
                    file to String.format(
                        Locale.US,
                        "Saved %s (%d patches, %d verts, %d tris, %d fine blocks, %d poses)",
                        file.name, curMap.activeSubmapPatchCount(), data.vertexCount, data.triangleCount, data.refinedBlocks, trajN,
                    )
                }
            }
            main.post {
                res.onSuccess { (file, msg) -> onDone(file, msg) }
                    .onFailure { e -> onDone(null, "Save failed: " + (e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun release() {
        stop()
        npuSession.close()
        npuExec.shutdownNow()
        rebuildExec.shutdownNow()
    }

    private fun extractBoardPose(r: FrameResult): RigidPose? {
        var boardPose: RigidPose? = null
        r.forEachLayer { kind, stride, count, offset ->
            if (kind == FrameResult.Layer.POSE && stride == 16 && count >= 1) {
                boardPose = RigidPose.fromFloat16(r.data, offset, 1.0 / 1000.0)
            }
        }
        return boardPose
    }

    private fun extractLaserMeters(r: FrameResult): FloatArray {
        var out = FloatArray(0)
        r.forEachLayer { kind, stride, count, offset ->
            if (kind == FrameResult.Layer.LASER_CAM && stride == 3 && count > 0) {
                val pts = FloatArray(count * 3)
                for (k in 0 until count * 3) pts[k] = r.data[offset + k] / 1000f
                out = pts
            }
        }
        return out
    }

    private fun extractCamera(r: FrameResult, w: Int, h: Int): DepthCamera? {
        val fx = r[FrameResult.H.FX].toDouble()
        val fy = r[FrameResult.H.FY].toDouble()
        val cx = r[FrameResult.H.CX].toDouble()
        val cy = r[FrameResult.H.CY].toDouble()
        if (!fx.isFinite() || !fy.isFinite() || fx <= 1.0 || fy <= 1.0 || w <= 0 || h <= 0) return null
        val d = cachedDist ?: DoubleArray(5)
        return DepthCamera(fx, fy, cx, cy, w, h, d[0], d[1], d[2], d[3], d[4])
    }

    private fun loadSavedDistortion(): DoubleArray {
        val out = DoubleArray(5)
        runCatching {
            val f = File(ctx.filesDir, "live_intrinsics.json")
            if (!f.isFile || f.length() < 20L) return out
            val dist = JSONObject(f.readText()).optJSONArray("dist") ?: return out
            for (i in 0 until minOf(5, dist.length())) out[i] = dist.optDouble(i, 0.0)
        }
        return out
    }

    private fun throttledStatus(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastStatusMs < 350L) return
        lastStatusMs = now
        postStatus(msg)
    }

    private fun postStatus(msg: String) {
        main.post { onStatus(msg) }
    }

    /** Estimates a nominal DA3 depth scale so the centre of the scene sits at [targetDepthM]. */
    private fun estimateNominalScale(rawDepth: FloatArray, targetDepthM: Double): Double? {
        val size = AdaptiveDepthMesh.SIZE
        val margin = size / 5
        val samples = ArrayList<Double>(2000)
        for (y in margin until size - margin step 8) {
            val row = y * size
            for (x in margin until size - margin step 8) {
                val d = rawDepth[row + x].toDouble()
                if (d.isFinite() && d > 1e-4) samples.add(d)
            }
        }
        if (samples.size < 20) return null
        samples.sort()
        val med = samples[samples.size / 2]
        return if (med > 1e-4) targetDepthM / med else null
    }

    companion object {
        private const val TAG = "LiveMeshController"
        private const val MIN_SCALE_PAIRS = 20
        private const val REBUILD_MIN_PERIOD_MS = 250L
        private const val AUTO_PATCH_COOLDOWN_MS = 1400L

        private fun newLiveMap() = PatchMap(registrationFailure = PatchMap.RegistrationFailure.KEEP_ACTIVE_MAP)
    }
}
