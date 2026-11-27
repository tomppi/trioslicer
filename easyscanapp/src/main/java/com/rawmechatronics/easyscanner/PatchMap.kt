package com.rawmechatronics.easyscanner

import android.util.Log
import java.io.File
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Phase 3 multi-patch map: coverage-triggered single-frame DA3 patches, per-patch
 * metric laser scaling, marker + VIO + laser ICP registration, and separate submaps
 * whenever overlap or residual is too weak to fuse safely (`docs/NORTH_STAR.md`).
 *
 * Never forces all frames into one global scale and never subsamples laser points.
 */
class PatchMap(
    val coverageConfig: CoverageConfig = CoverageConfig(),
    val icpConfig: LaserIcp.Config = LaserIcp.Config(),
    val tsdfVoxelSizeM: Float = TsdfVolume.DEFAULT_VOXEL_SIZE_M,
    private val registrationFailure: RegistrationFailure = RegistrationFailure.START_NEW_SUBMAP,
) {
    enum class RegistrationFailure { START_NEW_SUBMAP, KEEP_ACTIVE_MAP }

    data class CoverageConfig(
        /** Trigger a new patch when translation exceeds `translationRatio * medianLaserDepthM`. */
        val translationRatio: Double = 0.11,
        /** Lower floor on translation trigger (metres) so handheld jitter never spams patches. */
        val minTranslationM: Double = 0.05,
        /** Trigger a new patch when camera rotation exceeds this angle (degrees). */
        val rotationDeg: Double = 20.0,
        /** Trigger a new patch when laser coverage of the active submap drops below this fraction. */
        val minLaserCoverage: Double = 0.70,
        /** Parallax (`translationSpan / medianLaserDepth`) below this stays in single-patch tilting mode. */
        val motionParallaxThreshold: Double = 0.04,
        /** Safety cap on patches per submap to bound mobile GPU memory. */
        val maxPatchesPerSubmap: Int = 24,
    )

    data class CoverageStatus(
        val shouldCapture: Boolean,
        val reason: String,
        val motionMode: String,
        val parallax: Double,
        val translationFromNearestM: Double,
        val rotationFromNearestDeg: Double,
        val laserCoverage: Double,
    )

    data class PatchEntry(
        val id: Int,
        val submapId: Int,
        val mesh: AdaptiveDepthMesh,
        val rawPose: RigidPose,
        val boardPose: RigidPose?,
        val registration: LaserIcp.Result?,
        // Raw inputs retained so a loop-closure pose graph can rebuild the
        // submap TSDF instead of only correcting an exported point cloud.
        val rawDepth: FloatArray = FloatArray(0),
        val rgb518: ByteArray = ByteArray(0),
        val camera: DepthCamera? = null,
        val scale: Double = 1.0,
        val offset: Double = 0.0,
        val validMask: BooleanArray? = null,
        val laserFrames: MutableList<Pair<RigidPose, FloatArray>> = mutableListOf(),
        val featureKp: FloatArray = FloatArray(0),
        val featureDesc: FloatArray = FloatArray(0),
        val featureCount: Int = 0,
        var dbId: Int = -1,
    ) {
        /** Current world->camera pose after any loop correction. */
        var pose: RigidPose = mesh.refPose
        /** Pose-graph edge to an earlier patch, when a visual link was found. */
        var visualOldId: Int = -1
        var visualRel: RigidPose? = null
    }

    class Submap(val id: Int, voxelSizeM: Float) {
        val patches = ArrayList<PatchEntry>()
        val tsdf = if (NativeLiveTsdf.available) {
            TsdfVolume(voxelSizeM = 0.003f, maxBlocks = 40_000, maxIntegrateDepthM = 2.0)
        } else {
            TsdfVolume(voxelSizeM = voxelSizeM)
        }
        /** Optional 6-DoF marker-board anchor in this submap's world frame. */
        var anchorWorldToCam: RigidPose? = null
        var anchorBoardToCam: RigidPose? = null
        /** Maps `submap_world -> raw_vio_world` so `compose(rawPose, vioCorrection)` gives submap pose. */
        var submapToRawVio: RigidPose = IDENTITY_POSE
    }

    data class AddPatchOutcome(
        /** Null when an attempted registration left the active mesh unchanged. */
        val patchId: Int?,
        val submapId: Int,
        val fusedIntoActiveSubmap: Boolean,
        val createdNewSubmap: Boolean,
        val registration: LaserIcp.Result?,
        val patchCount: Int,
        val submapCount: Int,
        val visualOldId: Int = -1,
        val visualRel: RigidPose? = null,
    )

    private class PoseState(
        val anchorWorldToCam: RigidPose?,
        val anchorBoardToCam: RigidPose?,
        val submapToRawVio: RigidPose,
    )
    private val lock = Any()
    private val submaps = ArrayList<Submap>()
    private var activeSubmapIndex = -1
    @Volatile private var activePoseState: PoseState? = null
    @Volatile private var cachedPatchCount = 0
    @Volatile private var cachedSubmapCount = 0
    private var nextPatchId = 0
    private var lastRejectedView: RigidPose? = null

    // Live motion-mode tracker (camera centres in raw VIO/Board world, metres).
    private val observedCentres = ArrayList<DoubleArray>()
    private val laserDepthSamples = ArrayList<Double>()
    private var medianLaserDepthM = 0.5
    private var lastMotionMode = "unknown"
    private var lastParallax = Double.NaN

    fun patchCount(): Int = cachedPatchCount
    fun submapCount(): Int = cachedSubmapCount
    fun activeSubmapId(): Int = synchronized(lock) { if (activeSubmapIndex >= 0) submaps[activeSubmapIndex].id else -1 }
    fun motionMode(): String = synchronized(lock) { lastMotionMode }
    fun parallax(): Double = synchronized(lock) { lastParallax }
    fun activeSubmapPatchCount(): Int = synchronized(lock) {
        if (activeSubmapIndex >= 0) submaps[activeSubmapIndex].patches.size else 0
    }
    fun latestPatch(): PatchEntry? = synchronized(lock) {
        if (activeSubmapIndex < 0) null else submaps[activeSubmapIndex].patches.lastOrNull()
    }
    fun allSubmapsSnapshot(): List<Pair<Int, List<PatchEntry>>> = synchronized(lock) {
        submaps.map { it.id to it.patches.toList() }
    }
    fun activeTsdfStats(): TsdfVolume.Stats? = synchronized(lock) {
        if (activeSubmapIndex < 0) null else submaps[activeSubmapIndex].tsdf.stats()
    }
    fun activeNativeTsdfHandle(): Long = synchronized(lock) {
        if (activeSubmapIndex < 0) 0L else submaps[activeSubmapIndex].tsdf.nativeHandle()
    }
    fun prepareActiveNativeTsdf(): Int {
        val sub = synchronized(lock) { if (activeSubmapIndex < 0) null else submaps[activeSubmapIndex] } ?: return 0
        return sub.tsdf.prepareNativeMesh()
    }
    fun writeActiveNativeMeshPly(file: java.io.File, toMillimetres: Boolean): Int {
        val sub = synchronized(lock) { if (activeSubmapIndex < 0) null else submaps[activeSubmapIndex] } ?: return -1
        return sub.tsdf.writeNativeMeshPly(file, toMillimetres)
    }
    fun release() = synchronized(lock) {
        for (s in submaps) s.tsdf.release()
        submaps.clear()
        activeSubmapIndex = -1
        activePoseState = null
        cachedPatchCount = 0
        cachedSubmapCount = 0
        lastRejectedView = null
    }

    /**
     * Estimates the DA3 relative depth scale for a new viewpoint by projecting the
     * existing submap's surface into the new camera via its initial pose. Lets new
     * laser-off DA3 patches attach flush onto the existing mesh's exact scale.
     */
    fun estimateScaleFromExistingSurface(
        rawPose: RigidPose,
        boardPose: RigidPose?,
        camera: DepthCamera,
        rawDepth: FloatArray,
    ): Double? {
        val sub = synchronized(lock) { if (activeSubmapIndex < 0) null else submaps[activeSubmapIndex] } ?: return null
        val initPose = poseInActiveSubmap(rawPose, boardPose)
        val samplePos: FloatArray = sub.tsdf.sampleSurfaceForIcp(4000)?.first ?: run {
            val latest = synchronized(lock) { sub.patches.lastOrNull() } ?: return null
            latest.mesh.buildMesh().positions
        }
        val n = samplePos.size / 3
        if (n < 25) return null
        val stride = max(1, n / 4000)
        val pairs = ArrayList<Pair<Double, Double>>(512)
        var i = 0
        while (i < n) {
            val o = i * 3
            val camPt = initPose.worldToCamera(
                samplePos[o].toDouble(), samplePos[o + 1].toDouble(), samplePos[o + 2].toDouble(),
            )
            val zc = camPt[2]
            if (zc > 0.08 && zc < 3.0) {
                val uv = camera.project(camPt[0], camPt[1], zc)
                if (uv != null && uv[0] >= 0.0 && uv[0] < camera.width && uv[1] >= 0.0 && uv[1] < camera.height) {
                    val d = AdaptiveDepthMesh.sampleDepthBilinear(rawDepth, uv[0], uv[1], camera)
                    if (d > 0f && d.isFinite()) pairs.add(d.toDouble() to zc)
                }
            }
            i += stride
        }
        return AdaptiveDepthMesh.fitScale(pairs, minimum = 25)
    }

    /** Lock-free prediction of a frame's pose in the active submap's world frame (safe on 30 fps camera thread). */
    fun poseInActiveSubmap(rawPose: RigidPose, boardPose: RigidPose? = null): RigidPose {
        val st = activePoseState ?: return rawPose
        if (boardPose != null && st.anchorWorldToCam != null && st.anchorBoardToCam != null) {
            return LaserIcp.poseFromSharedBoard(st.anchorWorldToCam, st.anchorBoardToCam, boardPose)
        }
        return LaserIcp.compose(rawPose, st.submapToRawVio)
    }

    private fun publishPoseStateLocked() {
        cachedPatchCount = submaps.sumOf { it.patches.size }
        cachedSubmapCount = submaps.size
        activePoseState = if (activeSubmapIndex < 0) null else {
            val sub = submaps[activeSubmapIndex]
            PoseState(sub.anchorWorldToCam, sub.anchorBoardToCam, sub.submapToRawVio)
        }
    }

    /** Records a live camera pose and optional laser depth samples to maintain motion mode. */
    private fun recordMotionLocked(rawPose: RigidPose, laserCamM: FloatArray) {
        val c = rawPose.cameraCentre()
        if (c[0].isFinite() && c[1].isFinite() && c[2].isFinite()) {
            val prev = observedCentres.lastOrNull()
            if (prev == null || dist3(prev, c) > 0.002) {
                if (observedCentres.size >= 400) observedCentres.removeAt(0)
                observedCentres.add(c)
            }
        }
        if (laserCamM.size >= 3) {
            var i = 2
            while (i < laserCamM.size) {
                val z = laserCamM[i].toDouble()
                if (z.isFinite() && z > 0.05 && z < 10.0) {
                    if (laserDepthSamples.size >= 600) laserDepthSamples.removeAt(0)
                    laserDepthSamples.add(z)
                }
                i += 15 // sample every 5th point for fast median depth tracking (never drops laser geometry)
            }
            if (laserDepthSamples.isNotEmpty()) {
                val s = laserDepthSamples.sorted()
                medianLaserDepthM = s[s.size / 2]
            }
        }
        if (observedCentres.size >= 2 && medianLaserDepthM > 0.05) {
            val span = robustSpan(observedCentres)
            lastParallax = span / medianLaserDepthM
            lastMotionMode = if (lastParallax < coverageConfig.motionParallaxThreshold) "tilting" else "moving"
        }
    }

    /**
     * Evaluates whether the live view has left existing coverage enough to trigger
     * a new single-frame DA3 patch (`NORTH_STAR.md` patch-stitching rules).
     */
    fun evaluateCoverage(
        rawPose: RigidPose,
        boardPose: RigidPose?,
        laserCamM: FloatArray,
    ): CoverageStatus = synchronized(lock) {
        recordMotionLocked(rawPose, laserCamM)
        if (activeSubmapIndex < 0 || submaps[activeSubmapIndex].patches.isEmpty()) {
            return@synchronized CoverageStatus(true, "initial", lastMotionMode, lastParallax, 0.0, 0.0, 0.0)
        }
        val sub = submaps[activeSubmapIndex]
        if (sub.patches.size >= coverageConfig.maxPatchesPerSubmap) {
            return@synchronized CoverageStatus(false, "cap", lastMotionMode, lastParallax, 0.0, 0.0, 1.0)
        }
        val curPose = poseInActiveSubmap(rawPose, boardPose)
        var minTrans = Double.POSITIVE_INFINITY
        var minRot = Double.POSITIVE_INFINITY
        for (p in sub.patches) {
            val dt = LaserIcp.translationDeltaM(curPose, p.pose)
            val dr = LaserIcp.rotationDeltaDeg(curPose, p.pose)
            if (dt < minTrans) minTrans = dt
            if (dr < minRot) minRot = dr
        }

        // Fraction of current laser points that project inside any existing patch in this submap.
        var laserTotal = 0
        var laserCovered = 0
        if (laserCamM.size >= 3) {
            var i = 0
            while (i + 2 < laserCamM.size) {
                val lx = laserCamM[i].toDouble()
                val ly = laserCamM[i + 1].toDouble()
                val lz = laserCamM[i + 2].toDouble()
                i += 3
                if (!lx.isFinite() || !ly.isFinite() || !lz.isFinite() || lz <= 0.02 || lz > 20.0) continue
                laserTotal++
                val w = curPose.cameraToWorld(lx, ly, lz)
                var insideAny = false
                for (p in sub.patches) {
                    val ref = p.pose.worldToCamera(w[0], w[1], w[2])
                    if (ref[2] <= 0.02 || ref[2] > 20.0) continue
                    val uv = p.mesh.camera.project(ref[0], ref[1], ref[2]) ?: continue
                    if (uv[0] >= 0.0 && uv[0] < p.mesh.camera.width && uv[1] >= 0.0 && uv[1] < p.mesh.camera.height) {
                        insideAny = true; break
                    }
                }
                if (insideAny) laserCovered++
            }
        }
        val coverage = if (laserTotal > 0) laserCovered.toDouble() / laserTotal else 1.0
        val transThreshold = max(coverageConfig.minTranslationM, coverageConfig.translationRatio * medianLaserDepthM)

        lastRejectedView?.let { rejected ->
            // An overlapping return view can recover a rejected stitch even if nominal coverage
            // says it is already covered. Do not retry the same failed viewpoint automatically.
            val moved = LaserIcp.translationDeltaM(curPose, rejected) >= coverageConfig.minTranslationM ||
                LaserIcp.rotationDeltaDeg(curPose, rejected) >= 4.0
            return@synchronized CoverageStatus(moved, if (moved) "retry" else "retry-wait",
                lastMotionMode, lastParallax, minTrans, minRot, coverage)
        }

        // In rotation-only tilting mode with good laser coverage, stay on 1 patch.
        if (lastMotionMode == "tilting" && coverage >= coverageConfig.minLaserCoverage) {
            return@synchronized CoverageStatus(false, "tilting-covered", lastMotionMode, lastParallax, minTrans, minRot, coverage)
        }
        val triggerReason = when {
            minTrans > transThreshold -> "translation"
            minRot > coverageConfig.rotationDeg -> "rotation"
            laserTotal >= 15 && coverage < coverageConfig.minLaserCoverage -> "coverage"
            else -> ""
        }
        CoverageStatus(
            shouldCapture = triggerReason.isNotEmpty(),
            reason = triggerReason.ifEmpty { "covered" },
            motionMode = lastMotionMode,
            parallax = lastParallax,
            translationFromNearestM = minTrans,
            rotationFromNearestDeg = minRot,
            laserCoverage = coverage,
        )
    }

    /**
     * Registers a newly scaled single-frame DA3 patch against the active submap using
     * marker-board PnP (when shared) + VIO initial pose + 6-DoF Laser ICP.
     * A passing registration extends the active submap. On failure, offline callers can
     * start a separate submap; live callers can keep their accumulated map for the next attempt.
     */
    fun addPatch(
        rawPose: RigidPose,
        boardPose: RigidPose?,
        camera: DepthCamera,
        rawDepth: FloatArray,
        scale: Double,
        offset: Double = 0.0,
        rgb518: ByteArray,
        laserCamM: FloatArray,
        validMask518: BooleanArray? = null,
        icpConfigOverride: LaserIcp.Config? = null,
        features: FloatArray? = null,
        minNoveltyFraction: Float = 0.0f,
    ): AddPatchOutcome {
        val featCount = features?.getOrNull(0)?.toInt()?.coerceAtLeast(0) ?: 0
        val featKp = if (featCount > 0 && features != null && features.size >= 1 + featCount * 2)
            features.copyOfRange(1, 1 + featCount * 2) else FloatArray(0)
        val featDesc = if (featCount > 0 && features != null && features.size >= 1 + featCount * 2 + featCount * 64)
            features.copyOfRange(1 + featCount * 2, 1 + featCount * 2 + featCount * 64) else FloatArray(0)
        val activeSub: Submap?
        val existingPatches: List<PatchEntry>
        val anchorW: RigidPose?
        val anchorB: RigidPose?
        val runningCorr: RigidPose
        synchronized(lock) {
            recordMotionLocked(rawPose, laserCamM)
            if (activeSubmapIndex < 0) {
                activeSub = null; existingPatches = emptyList()
                anchorW = null; anchorB = null; runningCorr = IDENTITY_POSE
            } else {
                val s = submaps[activeSubmapIndex]
                activeSub = s; existingPatches = s.patches.toList()
                anchorW = s.anchorWorldToCam; anchorB = s.anchorBoardToCam; runningCorr = s.submapToRawVio
            }
        }

        if (activeSub == null) {
            // Heavy mesh + TSDF work runs outside PatchMap.lock so the 30 fps camera thread never stalls.
            val sub = Submap(0, tsdfVoxelSizeM)
            val mesh = AdaptiveDepthMesh(rawPose, camera, rawDepth, scale, offset, rgb518, validMask518)
            sub.tsdf.integrateDepthMap(rawPose, camera, rawDepth, scale, offset, rgb518, validMask518)
            if (laserCamM.size >= 3) {
                mesh.addLaserFrame(rawPose, laserCamM)
                sub.tsdf.integrateLaserFrame(rawPose, laserCamM)
            }
            if (boardPose != null) {
                sub.anchorWorldToCam = rawPose
                sub.anchorBoardToCam = boardPose
            }
            synchronized(lock) {
                val entry = PatchEntry(
                    nextPatchId++, sub.id, mesh, rawPose, boardPose, null,
                    // dbId is assigned after the patch is added below
                    rawDepth.copyOf(), rgb518.copyOf(), camera, scale, offset, validMask518?.copyOf(),
                    if (laserCamM.size >= 3) mutableListOf(rawPose to laserCamM.copyOf()) else mutableListOf(),
                    featKp, featDesc, featCount,
                )
                sub.patches.add(entry)
                registerDb(entry)
                submaps.add(sub)
                activeSubmapIndex = 0
                publishPoseStateLocked()
                return AddPatchOutcome(
                    patchId = entry.id, submapId = sub.id, fusedIntoActiveSubmap = true,
                    createdNewSubmap = true, registration = null,
                    patchCount = submaps.sumOf { it.patches.size }, submapCount = submaps.size,
                )
            }
        }

        val cfg = icpConfigOverride ?: icpConfig
        val usedMarker = boardPose != null && anchorW != null && anchorB != null
        val loopCandidates = loopCandidates(featDesc, featCount)
        logI("patch search db=" + LoopDb.isLoaded() + " candidates=" + loopCandidates.size + " features=" + featCount)
        val visualLink = visualPose(featKp, featDesc, featCount, camera, rawPose, loopCandidates)
        val visualPose = visualLink?.pose
        val initPose = visualPose ?: if (usedMarker) {
            LaserIcp.poseFromSharedBoard(anchorW, anchorB, boardPose)
        } else {
            LaserIcp.compose(rawPose, runningCorr)
        }

        // Run ICP outside PatchMap.lock so camera frames & VIO never block.
        val target = buildSubmapSurface(activeSub, existingPatches, cfg.maxCorrDistM)
        val queryCamM = buildRegistrationQuery(camera, rawDepth, scale, offset, laserCamM, validMask518)
        val reg = LaserIcp.align(target, queryCamM, initPose, cfg, usedMarkerAnchor = usedMarker)

        if (reg.accepted) {
            if (minNoveltyFraction > 0f) {
                val novelty = activeSub.tsdf.estimateNoveltyFraction(
                    reg.refinedPose, camera, rawDepth, scale, offset, validMask518,
                )
                if (novelty < minNoveltyFraction) {
                    val skippedReg = reg.copy(
                        accepted = false,
                        reason = String.format(java.util.Locale.US, "already covered (%.0f%% new)", novelty * 100f),
                    )
                    return synchronized(lock) {
                        lastRejectedView = null
                        AddPatchOutcome(
                            patchId = null, submapId = activeSub.id, fusedIntoActiveSubmap = false,
                            createdNewSubmap = false, registration = skippedReg,
                            patchCount = cachedPatchCount, submapCount = cachedSubmapCount,
                        )
                    }
                }
            }
            val placedMesh = AdaptiveDepthMesh(reg.refinedPose, camera, rawDepth, scale, offset, rgb518, validMask518)
            activeSub.tsdf.integrateDepthMap(reg.refinedPose, camera, rawDepth, scale, offset, rgb518, validMask518)
            if (laserCamM.size >= 3) {
                placedMesh.addLaserFrame(reg.refinedPose, laserCamM)
                activeSub.tsdf.integrateLaserFrame(reg.refinedPose, laserCamM)
            }
            synchronized(lock) {
                activeSub.submapToRawVio = LaserIcp.compose(LaserIcp.invert(rawPose), reg.refinedPose)
                if (boardPose != null && activeSub.anchorBoardToCam == null) {
                    activeSub.anchorWorldToCam = reg.refinedPose
                    activeSub.anchorBoardToCam = boardPose
                }
                val entry = PatchEntry(
                    nextPatchId++, activeSub.id, placedMesh, rawPose, boardPose, reg,
                    rawDepth.copyOf(), rgb518.copyOf(), camera, scale, offset, validMask518?.copyOf(),
                    if (laserCamM.size >= 3) mutableListOf(reg.refinedPose to laserCamM.copyOf()) else mutableListOf(),
                    featKp, featDesc, featCount,
                )
                entry.visualOldId = visualLink?.oldId ?: -1
                entry.visualRel = visualLink?.rel
                activeSub.patches.add(entry)
                registerDb(entry)
                lastRejectedView = null
                publishPoseStateLocked()
                return AddPatchOutcome(
                    patchId = entry.id, submapId = activeSub.id, fusedIntoActiveSubmap = true,
                    createdNewSubmap = false, registration = reg,
                    patchCount = submaps.sumOf { it.patches.size }, submapCount = submaps.size,
                    visualOldId = entry.visualOldId, visualRel = entry.visualRel,
                )
            }
        }

        if (registrationFailure == RegistrationFailure.KEEP_ACTIVE_MAP) {
            return synchronized(lock) {
                lastRejectedView = initPose
                AddPatchOutcome(
                    patchId = null, submapId = activeSub.id, fusedIntoActiveSubmap = false,
                    createdNewSubmap = false, registration = reg,
                    patchCount = submaps.sumOf { it.patches.size }, submapCount = submaps.size,
                )
            }
        }

        // Offline reconstruction preserves unregistered areas as separate submaps.
        val newSubId = synchronized(lock) { submaps.size }
        val newSub = Submap(newSubId, tsdfVoxelSizeM)
        val newMesh = AdaptiveDepthMesh(rawPose, camera, rawDepth, scale, offset, rgb518, validMask518)
        newSub.tsdf.integrateDepthMap(rawPose, camera, rawDepth, scale, offset, rgb518, validMask518)
        if (laserCamM.size >= 3) {
            newMesh.addLaserFrame(rawPose, laserCamM)
            newSub.tsdf.integrateLaserFrame(rawPose, laserCamM)
        }
        if (boardPose != null) {
            newSub.anchorWorldToCam = rawPose
            newSub.anchorBoardToCam = boardPose
        }
        synchronized(lock) {
            // Release TSDF memory of older inactive submaps beyond 2 so memory stays bounded.
            if (submaps.size >= 2) {
                for (i in 0 until submaps.size - 1) submaps[i].tsdf.release()
            }
            val entry = PatchEntry(
                nextPatchId++, newSub.id, newMesh, rawPose, boardPose, reg,
                rawDepth.copyOf(), rgb518.copyOf(), camera, scale, offset, validMask518?.copyOf(),
                if (laserCamM.size >= 3) mutableListOf(rawPose to laserCamM.copyOf()) else mutableListOf(),
                featKp, featDesc, featCount,
            )
            entry.visualOldId = visualLink?.oldId ?: -1
            entry.visualRel = visualLink?.rel
            newSub.patches.add(entry)
            registerDb(entry)
            submaps.add(newSub)
            activeSubmapIndex = submaps.lastIndex
            publishPoseStateLocked()
            return AddPatchOutcome(
                patchId = entry.id, submapId = newSub.id, fusedIntoActiveSubmap = false,
                createdNewSubmap = true, registration = reg,
                patchCount = submaps.sumOf { it.patches.size }, submapCount = submaps.size,
                visualOldId = entry.visualOldId, visualRel = entry.visualRel,
            )
        }
    }

    /**
     * Feeds a live camera-frame laser stripe into the active submap's overlapping patches.
     * Never subsamples or caps laser points. Runs heavy TSDF & heightfield updates outside
     * [lock] so camera/GL/NPU threads never block on laser carving.
     */
    fun addLaserFrame(
        rawPose: RigidPose,
        boardPose: RigidPose?,
        laserCamM: FloatArray,
    ): Pair<AdaptiveDepthMesh.LaserStats, FloatArray> {
        val sub: Submap
        val framePose: RigidPose
        val candidates: List<PatchEntry>
        synchronized(lock) {
            recordMotionLocked(rawPose, laserCamM)
            if (activeSubmapIndex < 0 || submaps[activeSubmapIndex].patches.isEmpty()) {
                return AdaptiveDepthMesh.LaserStats(0, 0, 0, 0, 0, 0) to FloatArray(0)
            }
            sub = submaps[activeSubmapIndex]
            framePose = poseInActiveSubmap(rawPose, boardPose)
            // In GPU-TSDF mode update the single nearest heightfield patch (TSDF handles the full volume);
            // in JVM fallback mode update up to 3 nearest overlapping patches.
            val maxCand = if (sub.tsdf.isNative) 1 else 3
            candidates = if (sub.patches.size <= maxCand) sub.patches.toList()
            else sub.patches.sortedBy { LaserIcp.translationDeltaM(framePose, it.pose) }.take(maxCand)
        }

        var accepted = 0
        var outliers = 0
        var oob = 0
        var newBlocks = 0
        var latestWorld = FloatArray(0)
        for (p in candidates) {
            synchronized(p.laserFrames) {
                if (p.laserFrames.size < 600) p.laserFrames.add(framePose to laserCamM.copyOf())
            }
            val (st, worldPts) = p.mesh.addLaserFrame(framePose, laserCamM)
            accepted += st.acceptedPoints
            outliers += st.rejectedOutliers
            oob += st.rejectedOutOfBounds
            newBlocks += st.newlyRefinedBlocks
            if (worldPts.size > latestWorld.size) latestWorld = worldPts
        }
        // Carve every accepted metric laser point into the volumetric TSDF (high authority weight).
        if (accepted > 0) {
            sub.tsdf.integrateLaserFrame(framePose, laserCamM)
        }
        val (totBlocks, totCells) = synchronized(lock) {
            val patches = if (activeSubmapIndex >= 0 && submaps[activeSubmapIndex] === sub) sub.patches else emptyList()
            patches.sumOf { it.mesh.refinedBlockCount() } to patches.sumOf { it.mesh.laserCellCount() }
        }
        return AdaptiveDepthMesh.LaserStats(accepted, outliers, oob, newBlocks, totBlocks, totCells) to latestWorld
    }

    /** Apply a rigid world correction to every patch in a submap and rebuild its TSDF. */
    private fun applyGlobalLoopCorrection(sub: Submap, correction: RigidPose) {
        val patches = synchronized(lock) { sub.patches.toList() }
        val inv = LaserIcp.invert(correction)
        sub.tsdf.clear()
        for (p in patches) {
            p.pose = LaserIcp.compose(p.pose, inv)
            if (p.rawDepth.isEmpty() || p.camera == null) continue
            sub.tsdf.integrateDepthMap(p.pose, p.camera, p.rawDepth, p.scale, p.offset, p.rgb518, p.validMask)
            for ((laserPose, points) in p.laserFrames) {
                if (points.size >= 3) sub.tsdf.integrateLaserFrame(LaserIcp.compose(laserPose, inv), points)
            }
        }
    }

    private fun logI(msg: String) {
        try { Log.i("PatchMap", msg) } catch (_: Throwable) { }
    }

    /** Write the corrected active-submap camera trajectory in TUM format. */
    fun writeTrajectory(file: File): Int {
        val patches: List<PatchEntry>
        synchronized(lock) {
            if (activeSubmapIndex < 0) return 0
            patches = submaps[activeSubmapIndex].patches.sortedBy { it.id }
        }
        if (patches.isEmpty()) return 0
        file.bufferedWriter().use { w ->
            w.write("# id tx ty tz qx qy qz qw\n")
            for (p in patches) {
                val c = p.pose.cameraToWorld(0.0, 0.0, 0.0)
                val r = p.pose.r
                val r00 = r[0]; val r01 = r[1]; val r02 = r[2]
                val r10 = r[3]; val r11 = r[4]; val r12 = r[5]
                val r20 = r[6]; val r21 = r[7]; val r22 = r[8]
                // camera-to-world rotation = transpose(world-to-camera)
                val c00 = r00; val c10 = r01; val c20 = r02
                val c01 = r10; val c11 = r11; val c21 = r12
                val c02 = r20; val c12 = r21; val c22 = r22
                val tr = c00 + c11 + c22
                val qx: Double; val qy: Double; val qz: Double; val qw: Double
                if (tr > 0.0) {
                    val s = Math.sqrt(tr + 1.0) * 2.0
                    qw = 0.25 * s; qx = (c21 - c12) / s; qy = (c02 - c20) / s; qz = (c10 - c01) / s
                } else if (c00 > c11 && c00 > c22) {
                    val s = Math.sqrt(1.0 + c00 - c11 - c22) * 2.0
                    qw = (c21 - c12) / s; qx = 0.25 * s; qy = (c01 + c10) / s; qz = (c02 + c20) / s
                } else if (c11 > c22) {
                    val s = Math.sqrt(1.0 + c11 - c00 - c22) * 2.0
                    qw = (c02 - c20) / s; qx = (c01 + c10) / s; qy = 0.25 * s; qz = (c12 + c21) / s
                } else {
                    val s = Math.sqrt(1.0 + c22 - c00 - c11) * 2.0
                    qw = (c10 - c01) / s; qx = (c02 + c20) / s; qy = (c12 + c21) / s; qz = 0.25 * s
                }
                w.write(String.format(java.util.Locale.US, "%.6f %.6f %.6f %.6f %.6f %.6f %.6f %.6f%n",
                    p.id.toDouble(), c[0], c[1], c[2], qx, qy, qz, qw))
            }
        }
        return patches.size
    }

    /**
     * Solve the active patch chain with odometry + visual-link edges, then
     * rebuild the TSDF from the corrected poses. This is the live-mesh pose graph.
     */
    fun optimizeAndRebuild(): Int {
        val sub: Submap
        val patches: List<PatchEntry>
        synchronized(lock) {
            if (activeSubmapIndex < 0) return 0
            sub = submaps[activeSubmapIndex]
            patches = sub.patches.toList()
        }
        if (patches.size < 2) return 0
        val index = HashMap<Int, Int>()
        patches.forEachIndexed { i, p -> index[p.id] = i }
        val edges = ArrayList<PatchPoseGraph.Edge>()
        for (i in 0 until patches.size - 1) {
            val a = patches[i]; val b = patches[i + 1]
            edges.add(PatchPoseGraph.Edge(i, i + 1, LaserIcp.compose(b.pose, LaserIcp.invert(a.pose)), 1.0))
        }
        for (i in patches.indices) {
            val p = patches[i]
            val rel = p.visualRel ?: continue
            val j = index[p.visualOldId] ?: continue
            if (j < i) edges.add(PatchPoseGraph.Edge(j, i, rel, 0.5))
        }
        if (edges.size < patches.size - 1) return 0
        logI("pose graph nodes=" + patches.size + " edges=" + edges.size + " visual=" + patches.count { it.visualOldId >= 0 })
        val solved = PatchPoseGraph.optimize(patches.map { it.pose }, edges)
        sub.tsdf.clear()
        for (i in patches.indices) {
            val p = patches[i]
            val old = p.pose
            val newPose = solved[i]
            p.pose = newPose
            if (p.rawDepth.isEmpty() || p.camera == null) continue
            sub.tsdf.integrateDepthMap(p.pose, p.camera, p.rawDepth, p.scale, p.offset, p.rgb518, p.validMask)
            val invOld = LaserIcp.invert(old)
            for (k in p.laserFrames.indices) {
                val (laserPose, points) = p.laserFrames[k]
                if (points.size >= 3) {
                    val attached = LaserIcp.compose(LaserIcp.compose(laserPose, invOld), newPose)
                    p.laserFrames[k] = attached to points
                    sub.tsdf.integrateLaserFrame(attached, points)
                }
            }
        }
        synchronized(lock) {
            val latest = patches.last()
            sub.submapToRawVio = LaserIcp.compose(LaserIcp.invert(latest.rawPose), latest.pose)
            publishPoseStateLocked()
        }
        return patches.size
    }

    /**
     * Re-integrates the active submap TSDF after loop-closure pose corrections.
     * Each retained patch is re-projected with [correction] applied to both its
     * DA3 depth frame and every laser frame it received. This is the live-mesh
     * counterpart of correcting the exported PLY: the mesh itself is rebuilt,
     * not just a point cloud copy.
     */
    fun applyPoseCorrections(correction: (RigidPose) -> RigidPose): Int {
        val sub: Submap
        val patches: List<PatchEntry>
        synchronized(lock) {
            if (activeSubmapIndex < 0) return 0
            sub = submaps[activeSubmapIndex]
            patches = sub.patches.toList()
        }
        sub.tsdf.clear()
        var applied = 0
        for (p in patches) {
            if (p.rawDepth.isEmpty() || p.camera == null) continue
            val pose = correction(p.rawPose)
            sub.tsdf.integrateDepthMap(pose, p.camera, p.rawDepth, p.scale, p.offset, p.rgb518, p.validMask)
            for ((laserPose, points) in p.laserFrames) {
                if (points.size >= 3) sub.tsdf.integrateLaserFrame(correction(laserPose), points)
            }
            applied++
        }
        return applied
    }

    /**
     * LightGlue + PnP relative pose between a new patch and an older overlapping
     * patch. The old patch's DA3 depth supplies metric 3D points; the new patch's
     * 2D observations give solvePnPRansac the transform old-camera -> new-camera.
     * Returns a world->new-camera pose in the same world frame as the patches.
     */
    /** Add a committed patch to the DBoW3 database for future loop candidates. */
    private fun registerDb(entry: PatchEntry) {
        if (LoopDb.isLoaded() && entry.featureCount >= 12) {
            entry.dbId = try { LoopDb.add(entry.id, entry.featureDesc, entry.featureCount) } catch (_: Throwable) { -1 }
        }
    }

    private fun loopCandidates(desc: FloatArray, n: Int): List<PatchEntry> {
        if (!LoopDb.isLoaded() || n < 12) return emptyList()
        val byId = HashMap<Int, PatchEntry>()
        synchronized(lock) {
            for (s in submaps) for (p in s.patches) if (p.dbId >= 0) byId[p.dbId] = p
        }
        if (byId.isEmpty()) return emptyList()
        return try {
            LoopDb.query(desc, n, 8).mapNotNull { byId[it.first] }
        } catch (_: Throwable) { emptyList() }
    }

    private data class VisualLink(val pose: RigidPose, val oldId: Int, val rel: RigidPose)

    private fun visualPose(
        kpNew: FloatArray, descNew: FloatArray, nNew: Int,
        camera: DepthCamera, rawPose: RigidPose, loopCandidates: List<PatchEntry>,
    ): VisualLink? {
        if (nNew < 12 || descNew.isEmpty() || !LoopMatcher.isReady()) return null
        if (!OpenCVLoader.initLocal()) return null
        val sub: Submap
        val candidates: List<PatchEntry>
        synchronized(lock) {
            if (activeSubmapIndex < 0) return null
            sub = submaps[activeSubmapIndex]
            candidates = if (loopCandidates.isNotEmpty()) loopCandidates
            else sub.patches.takeLast(6).reversed().filter { it.featureCount >= 12 }
        }
        if (candidates.isEmpty()) return null
        for (old in candidates) {
            val pairs = try {
                LoopMatcher.match(kpNew, descNew, old.featureKp, old.featureDesc)
            } catch (_: Throwable) { null } ?: continue
            val count = pairs.getOrNull(0) ?: 0
            if (count < 8) continue
            val objP = ArrayList<Point3>()
            val imgP = ArrayList<Point>()
            val oldCam = old.mesh.camera
            val oldSx = oldCam.width.toDouble() / VioRunner.GW
            val oldSy = oldCam.height.toDouble() / VioRunner.GH
            val newSx = camera.width.toDouble() / VioRunner.GW
            val newSy = camera.height.toDouble() / VioRunner.GH
            for (i in 0 until count) {
                val a = pairs[1 + i * 2]
                val b = pairs[2 + i * 2]
                if (a < 0 || b < 0 || a >= nNew || b >= old.featureCount) continue
                // SuperPoint keypoints live in 640x480 feature coordinates; scale to each patch's camera resolution.
                val oldU = (old.featureKp[b * 2].toDouble() + 0.5) * oldSx - 0.5
                val oldV = (old.featureKp[b * 2 + 1].toDouble() + 0.5) * oldSy - 0.5
                val newU = (kpNew[a * 2].toDouble() + 0.5) * newSx - 0.5
                val newV = (kpNew[a * 2 + 1].toDouble() + 0.5) * newSy - 0.5
                val x3 = old.mesh.metricPointAt(oldU, oldV) ?: continue
                val uv = camera.undistort(newU, newV)
                objP.add(Point3(x3[0], x3[1], x3[2]))
                imgP.add(Point(uv[0], uv[1]))
            }
            if (objP.size < 8) continue
            try {
                val obj = MatOfPoint3f().apply { fromArray(*objP.toTypedArray()) }
                val img = MatOfPoint2f().apply { fromArray(*imgP.toTypedArray()) }
                val k = Mat.eye(3, 3, CvType.CV_64F)
                val dist = MatOfDouble()
                val rvec = Mat()
                val tvec = Mat()
                val inliers = Mat()
                val ok = Calib3d.solvePnPRansac(obj, img, k, dist, rvec, tvec, false, 100, 0.005f, 0.99, inliers)
                if (!ok || inliers.rows() < 8) continue
                val r = Mat()
                Calib3d.Rodrigues(rvec, r)
                val rd = DoubleArray(9)
                r.get(0, 0, rd)
                val td = DoubleArray(3)
                tvec.get(0, 0, td)
                val rel = RigidPose(rd, td)
                val pose = LaserIcp.compose(rel, old.pose)
                // Reject a visual pose that disagrees wildly with the VIO prior.
                val cNew = pose.cameraCentre()
                val cRaw = rawPose.cameraCentre()
                val dx = cNew[0] - cRaw[0]; val dy = cNew[1] - cRaw[1]; val dz = cNew[2] - cRaw[2]
                if (kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) > 0.75) continue
                logI("visual link old=" + old.id + " inliers=" + inliers.rows() + " pairs=" + count)
                return VisualLink(pose, old.id, rel)
            } catch (_: Throwable) {
                continue
            }
        }
        return null
    }

    /**
     * Builds a combined mesh for the active submap.
     * - [useTsdf] = true (Phase 4 default): extracts the volumetric Marching Cubes TSDF mesh.
     * - [useTsdf] = false: merges the 2.5D adaptive heightfield patches.
     */
    fun buildActiveSubmapMesh(useTsdf: Boolean = true): AdaptiveDepthMesh.MeshData? {
        val sub: Submap
        val patchesCopy: List<PatchEntry>
        synchronized(lock) {
            if (activeSubmapIndex < 0) return null
            sub = submaps[activeSubmapIndex]
            patchesCopy = sub.patches.toList()
        }
        if (patchesCopy.isEmpty()) return null
        if (useTsdf) {
            val tsdfMesh = sub.tsdf.extractMesh()
            if (tsdfMesh.triangleCount > 0) return tsdfMesh
        }
        if (patchesCopy.size == 1) return patchesCopy[0].mesh.buildMesh()
        return mergePatches(patchesCopy)
    }

    private fun mergePatches(patches: List<PatchEntry>): AdaptiveDepthMesh.MeshData {
        val parts = patches.map { it.mesh.buildMesh() }
        // Puzzle rule for Patch mode: track 10 mm voxels already occupied by earlier pieces
        // so later overlapping DA3 triangles never paste a second sheet over existing geometry.
        val occupiedVoxels = HashSet<Long>()
        val voxelM = 0.010
        fun vKey(x: Float, y: Float, z: Float): Long =
            (floor(x / voxelM).toLong() and 0x1fffffL) or
                ((floor(y / voxelM).toLong() and 0x1fffffL) shl 21) or
                ((floor(z / voxelM).toLong() and 0x1fffffL) shl 42)

        val totalV = parts.sumOf { it.vertexCount }
        val totalI = parts.sumOf { it.indices.size }
        val pos = FloatArray(totalV * 3)
        val norm = FloatArray(totalV * 3)
        val col = ByteArray(totalV * 3)
        val refW = FloatArray(totalV)
        val idxOut = IntArray(totalI)
        var vOff = 0
        var iOff = 0
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY

        for ((partIdx, part) in parts.withIndex()) {
            val suppressed = BooleanArray(part.vertexCount)
            val newKeys = ArrayList<Long>(part.vertexCount)
            for (k in 0 until part.vertexCount) {
                val o = k * 3
                val x = part.positions[o]; val y = part.positions[o + 1]; val z = part.positions[o + 2]
                val key = vKey(x, y, z)
                if (partIdx > 0 && part.refinedWeight[k] < 0.5f && occupiedVoxels.contains(key)) {
                    suppressed[k] = true
                } else {
                    newKeys.add(key)
                }
            }
            occupiedVoxels.addAll(newKeys)
            System.arraycopy(part.positions, 0, pos, vOff * 3, part.vertexCount * 3)
            System.arraycopy(part.normals, 0, norm, vOff * 3, part.vertexCount * 3)
            System.arraycopy(part.colors, 0, col, vOff * 3, part.vertexCount * 3)
            System.arraycopy(part.refinedWeight, 0, refW, vOff, part.vertexCount)
            if (part.vertexCount > 0) {
                minX = min(minX, part.boundsMin[0]); minY = min(minY, part.boundsMin[1]); minZ = min(minZ, part.boundsMin[2])
                maxX = max(maxX, part.boundsMax[0]); maxY = max(maxY, part.boundsMax[1]); maxZ = max(maxZ, part.boundsMax[2])
            }
            var t = 0
            while (t + 2 < part.indices.size) {
                val a = part.indices[t]; val b = part.indices[t + 1]; val c = part.indices[t + 2]
                t += 3
                if (suppressed[a] && suppressed[b] && suppressed[c]) continue
                idxOut[iOff++] = a + vOff
                idxOut[iOff++] = b + vOff
                idxOut[iOff++] = c + vOff
            }
            vOff += part.vertexCount
        }
        return AdaptiveDepthMesh.MeshData(
            positions = pos,
            normals = norm,
            colors = col,
            refinedWeight = refW,
            vertexCount = vOff,
            indices = if (iOff == idxOut.size) idxOut else idxOut.copyOf(iOff),
            refinedBlocks = parts.sumOf { it.refinedBlocks },
            totalBlocks = parts.sumOf { it.totalBlocks },
            laserCells = parts.sumOf { it.laserCells },
            totalLaserHits = parts.sumOf { it.totalLaserHits },
            boundsMin = if (vOff > 0) floatArrayOf(minX, minY, minZ) else FloatArray(3),
            boundsMax = if (vOff > 0) floatArrayOf(maxX, maxY, maxZ) else FloatArray(3),
        )
    }

    private fun buildSubmapSurface(sub: Submap, patches: List<PatchEntry>, cellSizeM: Double): LaserIcp.SpatialSurface {
        val surf = LaserIcp.SpatialSurface(cellSizeM)
        val nativeSample = sub.tsdf.sampleSurfaceForIcp(6000)
        if (nativeSample != null && nativeSample.first.size >= 75) {
            surf.addPoints(nativeSample.first, nativeSample.second)
            return surf
        }
        for (p in patches) {
            val m = p.mesh.buildMesh()
            // Keep all laser-refined vertices (stride 1) and a compact sample of coarse vertices.
            val stride = max(1, m.vertexCount / 12_000)
            val selPos = ArrayList<Float>(min(m.vertexCount, 12_000) * 3)
            val selNorm = ArrayList<Float>(min(m.vertexCount, 12_000) * 3)
            for (k in 0 until m.vertexCount) {
                if (m.refinedWeight[k] >= 0.5f || k % stride == 0) {
                    val o = k * 3
                    selPos.add(m.positions[o]); selPos.add(m.positions[o + 1]); selPos.add(m.positions[o + 2])
                    selNorm.add(m.normals[o]); selNorm.add(m.normals[o + 1]); selNorm.add(m.normals[o + 2])
                }
            }
            surf.addPoints(
                FloatArray(selPos.size) { selPos[it] },
                FloatArray(selNorm.size) { selNorm[it] },
            )
        }
        return surf
    }

    private fun buildRegistrationQuery(
        camera: DepthCamera,
        rawDepth: FloatArray,
        scale: Double,
        offset: Double,
        laserCamM: FloatArray,
        validMask518: BooleanArray?,
    ): FloatArray {
        // Always include every valid laser point from the new patch; supplement with a
        // stride-16 DA3 surface grid so ICP has full 3D geometric constraints.
        val pts = ArrayList<Float>(laserCamM.size + 32 * 32 * 3)
        var i = 0
        while (i + 2 < laserCamM.size) {
            val x = laserCamM[i]; val y = laserCamM[i + 1]; val z = laserCamM[i + 2]
            i += 3
            if (x.isFinite() && y.isFinite() && z.isFinite() && z > 0.02f && z < 20f) {
                pts.add(x); pts.add(y); pts.add(z)
            }
        }
        val step = 16
        for (y in step / 2 until AdaptiveDepthMesh.SIZE step step) {
            val vf = (y + 0.5) * camera.height / AdaptiveDepthMesh.SIZE
            for (x in step / 2 until AdaptiveDepthMesh.SIZE step step) {
                val idx = y * AdaptiveDepthMesh.SIZE + x
                if (validMask518 != null && !validMask518[idx]) continue
                val d = rawDepth[idx]
                if (!(d > 0f) || !d.isFinite()) continue
                val z = d * scale + offset
                if (z <= 0.02 || z > 20.0) continue
                val uf = (x + 0.5) * camera.width / AdaptiveDepthMesh.SIZE
                val ray = camera.undistort(uf, vf)
                pts.add((ray[0] * z).toFloat())
                pts.add((ray[1] * z).toFloat())
                pts.add(z.toFloat())
            }
        }
        return FloatArray(pts.size) { pts[it] }
    }

    private fun robustSpan(centres: List<DoubleArray>): Double {
        if (centres.size < 2) return 0.0
        val inliers = if (centres.size < 8) {
            centres
        } else {
            val mx = median(centres.map { it[0] })
            val my = median(centres.map { it[1] })
            val mz = median(centres.map { it[2] })
            val med = doubleArrayOf(mx, my, mz)
            val radii = centres.map { dist3(it, med) }
            val medR = median(radii)
            val mad = median(radii.map { abs(it - medR) }).coerceAtLeast(0.005)
            val cut = medR + 3.5 * mad
            centres.filterIndexed { i, _ -> radii[i] <= cut }.let { if (it.size >= 2) it else centres }
        }
        val pairs = ArrayList<Double>(inliers.size * (inliers.size - 1) / 2)
        for (i in inliers.indices) for (j in i + 1 until inliers.size) pairs.add(dist3(inliers[i], inliers[j]))
        pairs.sort()
        return if (inliers.size < 8) pairs.last() else pairs[((pairs.size - 1) * 0.95).roundToInt()]
    }

    private fun dist3(a: DoubleArray, b: DoubleArray): Double {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return s[((s.size - 1) * 0.5).roundToInt()]
    }

    companion object {
        private val IDENTITY_POSE = RigidPose(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            doubleArrayOf(0.0, 0.0, 0.0),
        )
    }
}
