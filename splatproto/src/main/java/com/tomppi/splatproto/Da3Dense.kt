package com.tomppi.splatproto

import android.content.Context
import android.util.Log
import com.rawmechatronics.easyscanner.AdaptiveDepthMesh
import com.rawmechatronics.easyscanner.DepthCamera
import com.rawmechatronics.easyscanner.LaserIcp
import com.rawmechatronics.easyscanner.PatchMap
import com.rawmechatronics.easyscanner.RigidPose
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Coarse depth patches from Depth-Anything-3 run on the Hexagon HTP.
 *
 * Each depth map is monocular and only defined up to scale, so it is scaled against
 * the camera-frame metric laser points (`hybrid/laser/fNNNNN.f32`) and placed into
 * the VIO world using the frame's recorded OpenVINS pose (`vioPose` in `scan.json`,
 * with COLMAP `sparse/0/images.txt` only as fallback when no VIO pose exists).
 */
object Da3Dense {
    private const val TAG = "Da3Dense"
    internal const val SIZE = 518
    private const val CONTEXT = "models/da3_depth_fp16.bin"
    private const val MAX_POINTS = 1_000_000
    internal const val STRIDE = 2
    internal const val GRID = (SIZE + STRIDE - 1) / STRIDE
    private const val MIN_LASER_PAIRS = 20
    internal const val MOTION_PARALLAX_THRESHOLD = 0.04
    private const val MESH_EDGE_JUMP_RATIO = 0.08f

    enum class PoseSource(val wire: String) {
        VIO("vio"),
        BOARD("board"),
        COLMAP("colmap"),
    }

    internal data class Camera(
        val fx: Double, val fy: Double, val cx: Double, val cy: Double,
        val width: Int, val height: Int,
        val k1: Double = 0.0, val k2: Double = 0.0,
        val p1: Double = 0.0, val p2: Double = 0.0, val k3: Double = 0.0,
    ) {
        /**
         * Project a camera-frame point (metres or mm — homogeneous) to distorted pixels,
         * matching `easyscan-core/algo/src/geom.rs::Intrinsics::project`.
         */
        fun project(xc: Double, yc: Double, zc: Double): DoubleArray? {
            if (!xc.isFinite() || !yc.isFinite() || !zc.isFinite() || zc <= 1e-6) return null
            val x = xc / zc
            val y = yc / zc
            val r2 = x * x + y * y
            if (!r2.isFinite() || r2 > 4.0) return null
            val radial = 1.0 + r2 * (k1 + r2 * (k2 + r2 * k3))
            val xd = x * radial + 2.0 * p1 * x * y + p2 * (r2 + 2.0 * x * x)
            val yd = y * radial + p1 * (r2 + 2.0 * y * y) + 2.0 * p2 * x * y
            val u = fx * xd + cx
            val v = fy * yd + cy
            return if (u.isFinite() && v.isFinite()) doubleArrayOf(u, v) else null
        }

        /** Maximum undistorted radius `r` where `r_d(r) = r*(1+k1*r^2+k2*r^4+k3*r^6)` stays strictly increasing. */
        private val maxMonotonicR: Double by lazy {
            if (k1 == 0.0 && k2 == 0.0 && k3 == 0.0) return@lazy 2.0
            var best = 0.0
            var r = 0.01
            while (r <= 2.0) {
                val r2 = r * r
                val drd = 1.0 + r2 * (3.0 * k1 + r2 * (5.0 * k2 + r2 * 7.0 * k3))
                if (drd <= 0.15) break
                best = r
                r += 0.01
            }
            best.coerceAtLeast(0.1)
        }

        /** Distorted radius at [maxMonotonicR]: pixels with `r0 <= maxInvertibleRd` invert exactly. */
        private val maxInvertibleRd: Double by lazy {
            val r = maxMonotonicR
            val r2 = r * r
            r * (1.0 + r2 * (k1 + r2 * (k2 + r2 * k3)))
        }

        /** True when `(u, v)` lies inside the strictly monotonic invertible region of the radial model. */
        fun isInvertible(u: Double, v: Double): Boolean {
            if (k1 == 0.0 && k2 == 0.0 && p1 == 0.0 && p2 == 0.0 && k3 == 0.0) return true
            val x0 = (u - cx) / fx
            val y0 = (v - cy) / fy
            return hypot(x0, y0) <= maxInvertibleRd * 0.98
        }

        /**
         * Undistort pixel `(u, v)` to normalised camera coordinates `(x, y)` (ray `[x, y, 1]`),
         * matching `easyscan-core/algo/src/geom.rs::Intrinsics::undistort` inside the monotonic
         * region and smoothly extrapolating outside polynomial fold-back corners.
         */
        fun undistort(u: Double, v: Double): DoubleArray {
            val x0 = (u - cx) / fx
            val y0 = (v - cy) / fy
            if (k1 == 0.0 && k2 == 0.0 && p1 == 0.0 && p2 == 0.0 && k3 == 0.0) {
                return doubleArrayOf(x0, y0)
            }
            val r0 = hypot(x0, y0)
            val rLim = maxMonotonicR
            val rdLim = maxInvertibleRd
            if (r0 > rdLim && rdLim > 1e-6) {
                // Outside polynomial fold-back: smoothly continue radially from the boundary.
                val r2 = rLim * rLim
                val slope = (1.0 + r2 * (3.0 * k1 + r2 * (5.0 * k2 + r2 * 7.0 * k3))).coerceAtLeast(0.15)
                val rOut = rLim + (r0 - rdLim) / slope
                val s = rOut / r0
                return doubleArrayOf(x0 * s, y0 * s)
            }
            // 2D Gauss-Newton inside the monotonic domain; converges quadratically without corner divergence.
            var x = x0
            var y = y0
            repeat(12) {
                val r2 = x * x + y * y
                val rad = 1.0 + r2 * (k1 + r2 * (k2 + r2 * k3))
                val dRadDr2 = k1 + r2 * (2.0 * k2 + r2 * 3.0 * k3)
                val fxv = x * rad + 2.0 * p1 * x * y + p2 * (r2 + 2.0 * x * x) - x0
                val fyv = y * rad + p1 * (r2 + 2.0 * y * y) + 2.0 * p2 * x * y - y0
                if (fxv * fxv + fyv * fyv < 1e-22) return doubleArrayOf(x, y)
                val j00 = rad + 2.0 * x * x * dRadDr2 + 2.0 * p1 * y + 6.0 * p2 * x
                val j01 = 2.0 * x * y * dRadDr2 + 2.0 * p1 * x + 2.0 * p2 * y
                val j10 = j01
                val j11 = rad + 2.0 * y * y * dRadDr2 + 2.0 * p2 * x + 6.0 * p1 * y
                val det = j00 * j11 - j01 * j10
                if (abs(det) < 1e-12 || !det.isFinite()) return@repeat
                var nx = x - (j11 * fxv - j01 * fyv) / det
                var ny = y - (-j10 * fxv + j00 * fyv) / det
                val nr = hypot(nx, ny)
                if (nr > rLim) { nx *= rLim / nr; ny *= rLim / nr }
                x = nx; y = ny
            }
            return doubleArrayOf(x, y)
        }
    }

    /** World-to-camera pose: x_cam = R * x_world + t. [r] is 3x3 row-major; [t] is metres. */
    internal data class Pose(val r: DoubleArray, val t: DoubleArray) {
        fun cameraToWorld(xc: Double, yc: Double, zc: Double): DoubleArray {
            val dx = xc - t[0]
            val dy = yc - t[1]
            val dz = zc - t[2]
            return doubleArrayOf(
                r[0] * dx + r[3] * dy + r[6] * dz,
                r[1] * dx + r[4] * dy + r[7] * dz,
                r[2] * dx + r[5] * dy + r[8] * dz,
            )
        }

        fun worldToCamera(wx: Double, wy: Double, wz: Double): DoubleArray = doubleArrayOf(
            r[0] * wx + r[1] * wy + r[2] * wz + t[0],
            r[3] * wx + r[4] * wy + r[5] * wz + t[1],
            r[6] * wx + r[7] * wy + r[8] * wz + t[2],
        )

        /** Camera centre in world coordinates: C = -R^T t. */
        fun cameraCentre(): DoubleArray = cameraToWorld(0.0, 0.0, 0.0)
    }

    internal data class PoseSelection(val source: PoseSource, val poses: Map<String, Pose>)

    internal data class Observation(val x: Double, val y: Double, val pointId: Int)

    internal data class Fit(val scale: Double, val offset: Double, val count: Int, val residual: Double)

    data class MotionMode(
        val mode: String,
        val poseCount: Int,
        val translationSpanM: Double,
        val rawTranslationSpanM: Double,
        val medianLaserDepthM: Double,
        val parallax: Double,
    )

    data class PlacementMetrics(
        val frameFile: String,
        val frameIndex: Int,
        val cameraCentreM: DoubleArray,
        val scale: Double,
        val laserTotal: Int,
        val laserMatched: Int,
        val laserDepthMedianM: Double,
        val preRefineMedianErrMm: Double,
        val preRefineP95ErrMm: Double,
        val preRefineMeanSignedErrMm: Double,
        val laserRefinedGridCells: Int,
        val postRefineWorldMedianErrMm: Double,
        val postRefineWorldP95ErrMm: Double,
        val maxReprojErrPx: Double,
        val patchBoundsMinM: FloatArray,
        val patchBoundsMaxM: FloatArray,
        val laserBoundsMinM: FloatArray?,
        val laserBoundsMaxM: FloatArray?,
        val meshVertices: Int,
        val meshFaces: Int,
        val sweepLaserFrames: Int = 0,
        val sweepLaserPoints: Int = 0,
        val sweepLaserOutliers: Int = 0,
        val refinedBlocks: Int = 0,
        val totalBlocks: Int = AdaptiveDepthMesh.COARSE * AdaptiveDepthMesh.COARSE,
        val laserCells: Int = 0,
    )

    data class Result(
        val points: Int,
        val poseSource: PoseSource,
        val singleFrame: Boolean,
        val outFile: File,
        val overlayFile: File?,
        val meshFile: File?,
        val motionMode: MotionMode,
        val placement: PlacementMetrics?,
    )

    internal class SingleFramePatch(
        val positions: FloatArray,
        val colors: ByteArray,
        val vertexCount: Int,
        val faces: IntArray,
        val laserWorld: List<FloatArray>,
        val placement: PlacementMetrics,
    )

    private data class Frame(
        val name: String,
        val frameIndex: Int,
        val cameraIndex: Int,
        val pose: Pose,
        val observations: List<Observation>,
        val depth: FloatArray,
        val laser: File?,
        var scale: Double = 0.0,
        var offset: Double = 0.0,
    )

    fun densify(
        context: Context,
        scan: Scan,
        onProgress: (Float, String) -> Unit,
        onLog: (String) -> Unit,
        maxFrames: Int = 0,
        preferVio: Boolean = true,
        preferBoard: Boolean = false,
    ): Int = densifyDetailed(context, scan, onProgress, onLog, maxFrames, preferVio, preferBoard).points

    fun densifyDetailed(
        context: Context,
        scan: Scan,
        onProgress: (Float, String) -> Unit,
        onLog: (String) -> Unit,
        maxFrames: Int = 0,
        preferVio: Boolean = true,
        preferBoard: Boolean = false,
    ): Result {
        require(OpenCVLoader.initLocal()) { "OpenCV failed to load" }
        val work = File(context.filesDir, "da3work")
        work.deleteRecursively()
        val inputs = File(work, "inputs").apply { mkdirs() }
        val outputDir = File(work, "out").apply { mkdirs() }

        val scanJson = JSONObject(scan.meta.readText())
        val cameras = parseCameras(scanJson)
        val images = parseImageList(scanJson)
        val (laserByFile, frameIndexByFile, allLaserFiles) = loadHybridLaserMap(scan)

        val poseSelection = selectPoses(scan, preferVio = preferVio, preferBoard = preferBoard)
        val poseSource = poseSelection.source
        val poses = poseSelection.poses
        val sparseDir = File(scan.dir, "sparse/0")
        val observationMap = if (poseSource == PoseSource.COLMAP) parseObservations(File(sparseDir, "images.txt")) else HashMap()
        val points = if (poseSource == PoseSource.COLMAP) parsePoints(File(sparseDir, "points3D.txt")) else HashMap()

        val registeredAll = images.filter { poses.containsKey(it.first) }
        if (registeredAll.isEmpty()) {
            error(when (poseSource) {
                PoseSource.VIO -> "no frame has a valid VIO pose"
                PoseSource.BOARD -> "no frame has a valid board pose"
                PoseSource.COLMAP -> "no usable pose source (no VIO poses and no sparse/0/images.txt)"
            })
        }
        val motionMode = computeMotionMode(poses.values.toList(), allLaserFiles)
        onLog(String.format(Locale.US,
            "da3: motionMode=%s poses=%d span=%.4fm (raw=%.4fm) medianLaserZ=%.4fm parallax=%.4f",
            motionMode.mode, motionMode.poseCount, motionMode.translationSpanM,
            motionMode.rawTranslationSpanM, motionMode.medianLaserDepthM, motionMode.parallax))

        val selected = if (maxFrames == 0) {
            selectCoverageKeyframes(registeredAll, poses, laserByFile, motionMode)
        } else {
            selectFrames(registeredAll, laserByFile, maxFrames)
        }
        onLog("da3: " + selected.size + "/" + registeredAll.size + " coverage keyframes, pose source " +
            poseSource.name + ", laser files " + laserByFile.size + ", sparse points " + points.size)
        val anyLaserForSelected = selected.any { laserByFile[it.first]?.let { f -> f.isFile && f.length() >= 12L } == true }
        if (poseSource == PoseSource.COLMAP && !anyLaserForSelected && points.isEmpty()) {
            error("no usable sparse model or laser frames to scale depth")
        }

        onProgress(0.05f, "Preparing depth inputs")
        val listFile = File(work, "list.txt")
        val listings = StringBuilder()
        val used = ArrayList<Pair<String, Int>>()
        for ((name, cameraIndex) in selected) {
            val bgr = Imgcodecs.imread(
                File(scan.imagesDir, name).absolutePath,
                Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION,
            )
            if (bgr.empty()) continue
            val small = Mat()
            Imgproc.resize(bgr, small, Size(SIZE.toDouble(), SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val rgb = Mat()
            Imgproc.cvtColor(small, rgb, Imgproc.COLOR_BGR2RGB)
            small.release()
            bgr.release()
            val bytes = ByteArray(SIZE * SIZE * 3)
            rgb.get(0, 0, bytes)
            rgb.release()
            val buffer = ByteBuffer.allocate(SIZE * SIZE * 3 * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (c in 0 until 3) for (p in 0 until SIZE * SIZE) {
                buffer.putShort(halfLookup[bytes[p * 3 + c].toInt().and(0xff)])
            }
            val input = File(inputs, "f" + used.size + ".raw")
            input.writeBytes(buffer.array())
            listings.append(input.absolutePath).append('\n')
            used.add(name to cameraIndex)
        }
        if (used.isEmpty()) error("not enough frames could be prepared")
        listFile.writeText(listings.toString())

        onProgress(0.2f, "Depth for " + used.size + " frames (NPU)")
        val code = NpuMasker.runQnn(context, CONTEXT, listFile, outputDir)
        if (code != 0) error("qnn-net-run failed with code " + code)
        onLog("da3: inference returned for " + used.size + " frames")

        onProgress(0.45f, "Scaling depth with laser")
        val frames = ArrayList<Frame>()
        used.forEachIndexed { index, (name, cameraIndex) ->
            val pose = poses[name] ?: return@forEachIndexed
            val depthFile = File(outputDir, "Result_" + index + "/depth_estimates_native.raw")
            if (!depthFile.isFile || depthFile.length() < SIZE * SIZE * 2) {
                onLog("da3: frame " + name + " has no depth output")
                return@forEachIndexed
            }
            val depth = readHalfDepth(depthFile)
            frames.add(Frame(
                name = name,
                frameIndex = frameIndexByFile[name] ?: index,
                cameraIndex = cameraIndex,
                pose = pose,
                observations = observationMap[name].orEmpty(),
                depth = depth,
                laser = laserByFile[name],
            ))
        }
        if (frames.isEmpty()) error("no depth maps were produced")

        // Fit metric depth per frame from the frame's own laser line (falling back to
        // COLMAP sparse observations only when running on COLMAP without laser).
        val accepted = BooleanArray(frames.size)
        frames.forEachIndexed { index, frame ->
            val camera = cameras[frame.cameraIndex] ?: return@forEachIndexed
            val laserFit = fitScaleLaser(frame.depth, frame.laser, camera)
            val sparseFit = if (laserFit == null && poseSource == PoseSource.COLMAP) {
                fitScale(frame.depth, frame.pose, frame.observations, camera, points)
            } else null
            val fit = laserFit ?: sparseFit
            if (fit != null && fit.scale > 0.0 && fit.scale.isFinite() && fit.offset.isFinite()) {
                frame.scale = fit.scale
                frame.offset = fit.offset
                accepted[index] = true
            }
            if (index % 16 == 0 || frames.size == 1) {
                onLog("da3: " + frame.name + " laser=" +
                    (laserFit?.let { String.format(Locale.US, "%.4f (n=%d, res=%.1fmm)", it.scale, it.count, it.residual * 1000.0) } ?: "none") +
                    " sparse=" +
                    (sparseFit?.let { String.format(Locale.US, "%.4f (n=%d)", it.scale, it.count) } ?: "none"))
            }
            onProgress(0.45f + 0.1f * (index + 1) / frames.size, "Scaling frame " + (index + 1) + "/" + frames.size)
        }
        val valid = frames.filterIndexed { index, _ -> accepted[index] }
        if (valid.isEmpty()) error("no frame had a usable laser depth fit")
        val medianScale = valid.map { it.scale }.sorted()[valid.size / 2]
        val medianOffset = valid.map { it.offset }.sorted()[valid.size / 2]
        var rejected = 0
        frames.forEachIndexed { index, frame ->
            if (!accepted[index]) {
                frame.scale = medianScale
                frame.offset = medianOffset
                rejected++
            }
        }
        onLog(String.format(Locale.US, "da3: accepted %d frames, rejected %d, median scale %.4f",
            valid.size, rejected, medianScale))

        val singleFrame = maxFrames == 1 || frames.size == 1
        if (singleFrame) {
            onProgress(0.6f, "Building single-frame patch")
            val frame = frames.first()
            val camera = cameras[frame.cameraIndex] ?: error("missing camera " + frame.cameraIndex)
            val maskMat = runCatching {
                Imgcodecs.imread(File(scan.dir, "masks/" + frame.name + ".png").absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
            }.getOrNull()
            val hasMask = maskMat != null && !maskMat.empty()
            val bgr = Imgcodecs.imread(
                File(scan.imagesDir, frame.name).absolutePath,
                Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION,
            )
            if (bgr.empty()) {
                maskMat?.release()
                error("could not read image " + frame.name)
            }
            val laserCamM = readLaserPointsMeters(frame.laser)
            val rgb518 = ByteArray(SIZE * SIZE * 3)
            val validMask518 = BooleanArray(SIZE * SIZE) { true }
            val patch = try {
                val smallBgr = Mat()
                Imgproc.resize(bgr, smallBgr, Size(SIZE.toDouble(), SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                val smallRgb = Mat()
                Imgproc.cvtColor(smallBgr, smallRgb, Imgproc.COLOR_BGR2RGB)
                smallRgb.get(0, 0, rgb518)
                smallBgr.release()
                smallRgb.release()
                if (hasMask) {
                    for (y in 0 until SIZE) {
                        val vi = ((y + 0.5) * camera.height / SIZE).toInt().coerceIn(0, camera.height - 1)
                        for (x in 0 until SIZE) {
                            val ui = ((x + 0.5) * camera.width / SIZE).toInt().coerceIn(0, camera.width - 1)
                            if (maskMat.get(vi, ui)[0] < 128.0) validMask518[y * SIZE + x] = false
                        }
                    }
                }
                buildSingleFramePatch(
                    frameFile = frame.name,
                    frameIndex = frame.frameIndex,
                    depth = frame.depth,
                    scale = frame.scale,
                    offset = frame.offset,
                    pose = frame.pose,
                    camera = camera,
                    laserCamM = laserCamM,
                    isMaskedOut = { ui, vi -> hasMask && maskMat.get(vi, ui)[0] < 128.0 },
                    sampleRgb = { ui, vi ->
                        val c = bgr.get(vi, ui)
                        if (c == null) null else intArrayOf(
                            c[2].toInt().coerceIn(0, 255),
                            c[1].toInt().coerceIn(0, 255),
                            c[0].toInt().coerceIn(0, 255),
                        )
                    },
                )
            } finally {
                bgr.release()
                maskMat?.release()
            }
            if (patch.vertexCount == 0) error("no points survived the mask")

            // Refine the single-frame DA3 mesh with the full metric laser sweep across the capture.
            onProgress(0.75f, "Refining DA3 mesh with laser sweep")
            val depthCam = DepthCamera(
                camera.fx, camera.fy, camera.cx, camera.cy, camera.width, camera.height,
                camera.k1, camera.k2, camera.p1, camera.p2, camera.k3,
            )
            val refRigid = RigidPose(frame.pose.r, frame.pose.t)
            val adaptive = AdaptiveDepthMesh(
                refPose = refRigid,
                camera = depthCam,
                rawDepth = frame.depth,
                scale = frame.scale,
                offset = frame.offset,
                rgb518 = rgb518,
                validMask518 = validMask518,
            )
            val sweepFrames = loadLaserSweep(scan, poseSource)
            val sweepLaserWorld = ArrayList<Float>()
            var sweepFramesAccepted = 0
            var sweepPtsAccepted = 0
            var sweepOutliers = 0
            if (sweepFrames.isNotEmpty()) {
                for (sf in sweepFrames) {
                    val pts = readLaserPointsMeters(sf.laserFile)
                    if (pts.size < 3) continue
                    val (st, worldPts) = adaptive.addLaserFrame(sf.pose, pts)
                    if (st.acceptedPoints > 0) {
                        sweepFramesAccepted++
                        sweepPtsAccepted += st.acceptedPoints
                        for (v in worldPts) sweepLaserWorld.add(v)
                    }
                    sweepOutliers += st.rejectedOutliers
                }
            } else if (laserCamM.size >= 3) {
                val (st, worldPts) = adaptive.addLaserFrame(refRigid, laserCamM)
                if (st.acceptedPoints > 0) {
                    sweepFramesAccepted = 1
                    sweepPtsAccepted = st.acceptedPoints
                    for (v in worldPts) sweepLaserWorld.add(v)
                }
                sweepOutliers += st.rejectedOutliers
            }
            val adaptiveMesh = adaptive.buildMesh()
            val outCount = if (adaptiveMesh.vertexCount > 0) adaptiveMesh.vertexCount else patch.vertexCount
            val outPos = if (adaptiveMesh.vertexCount > 0) adaptiveMesh.positions else patch.positions
            val outCol = if (adaptiveMesh.vertexCount > 0) adaptiveMesh.colors else patch.colors

            val outFile = File(scan.dir, "dense_da3_first.ply")
            writePly(outFile, outPos, outCol, outCount)

            val overlayFile = File(scan.dir, "dense_da3_first_overlay.ply")
            val laserOverlayCount = sweepLaserWorld.size / 3
            val wroteOverlay = if (laserOverlayCount > 0) {
                val total = outCount + laserOverlayCount
                val p2 = FloatArray(total * 3)
                val c2 = ByteArray(total * 3)
                System.arraycopy(outPos, 0, p2, 0, outCount * 3)
                System.arraycopy(outCol, 0, c2, 0, outCount * 3)
                var k = outCount
                var li = 0
                while (li + 2 < sweepLaserWorld.size) {
                    p2[k * 3] = sweepLaserWorld[li]
                    p2[k * 3 + 1] = sweepLaserWorld[li + 1]
                    p2[k * 3 + 2] = sweepLaserWorld[li + 2]
                    c2[k * 3] = 255.toByte(); c2[k * 3 + 1] = 40.toByte(); c2[k * 3 + 2] = 40.toByte()
                    k++; li += 3
                }
                writePly(overlayFile, p2, c2, total)
                true
            } else {
                if (overlayFile.isFile) overlayFile.delete()
                false
            }

            val meshFile = File(scan.dir, "dense_da3_first_mesh.ply")
            val wroteMesh = if (adaptiveMesh.triangleCount > 0) {
                AdaptiveDepthMesh.writeMeshPly(meshFile, adaptiveMesh, toMillimetres = false)
                true
            } else {
                if (meshFile.isFile) meshFile.delete()
                false
            }

            val pm = patch.placement.copy(
                meshVertices = adaptiveMesh.vertexCount,
                meshFaces = adaptiveMesh.triangleCount,
                sweepLaserFrames = sweepFramesAccepted,
                sweepLaserPoints = sweepPtsAccepted,
                sweepLaserOutliers = sweepOutliers,
                refinedBlocks = adaptiveMesh.refinedBlocks,
                totalBlocks = adaptiveMesh.totalBlocks,
                laserCells = adaptiveMesh.laserCells,
            )
            onLog(String.format(Locale.US,
                "da3: single-frame placement pose=%s frame=%s(#%d) cam=(%.3f,%.3f,%.3f)m " +
                    "scale=%.4f laser=%d/%d preErr=%.1fmm(p95=%.1fmm) " +
                    "sweepFrames=%d sweepPts=%d outliers=%d refinedBlocks=%d/%d laserCells=%d " +
                    "reprojMax=%.4fpx verts=%d faces=%d",
                poseSource.wire, pm.frameFile, pm.frameIndex,
                pm.cameraCentreM[0], pm.cameraCentreM[1], pm.cameraCentreM[2],
                pm.scale, pm.laserMatched, pm.laserTotal,
                pm.preRefineMedianErrMm, pm.preRefineP95ErrMm,
                pm.sweepLaserFrames, pm.sweepLaserPoints, pm.sweepLaserOutliers,
                pm.refinedBlocks, pm.totalBlocks, pm.laserCells,
                pm.maxReprojErrPx, pm.meshVertices, pm.meshFaces))

            writeDa3Report(scan.report, poseSource, true, motionMode, pm, outCount)
            work.deleteRecursively()
            onProgress(1f, "Single-frame depth ready")
            return Result(
                points = outCount,
                poseSource = poseSource,
                singleFrame = true,
                outFile = outFile,
                overlayFile = if (wroteOverlay) overlayFile else null,
                meshFile = if (wroteMesh) meshFile else null,
                motionMode = motionMode,
                placement = pm,
            )
        }

        // Phase 3 multi-patch map: per-patch laser scaling + marker/VIO/LaserIcp stitching + submaps.
        onProgress(0.6f, "Registering " + frames.size + " patches")
        val boardByFile = selectPoses(scan, preferVio = false, preferBoard = true).let {
            if (it.source == PoseSource.BOARD) it.poses else emptyMap()
        }
        val patchMap = PatchMap()
        var fusedPatches = 0
        val icpResidualsMm = ArrayList<Double>()
        frames.forEachIndexed { idx, frame ->
            val camera = cameras[frame.cameraIndex] ?: return@forEachIndexed
            val maskMat = runCatching {
                Imgcodecs.imread(File(scan.dir, "masks/" + frame.name + ".png").absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
            }.getOrNull()
            val hasMask = maskMat != null && !maskMat.empty()
            val bgr = Imgcodecs.imread(
                File(scan.imagesDir, frame.name).absolutePath,
                Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION,
            )
            if (bgr.empty()) { maskMat?.release(); return@forEachIndexed }
            val rgb518 = ByteArray(SIZE * SIZE * 3)
            val validMask518 = BooleanArray(SIZE * SIZE) { true }
            try {
                val smallBgr = Mat()
                Imgproc.resize(bgr, smallBgr, Size(SIZE.toDouble(), SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                val smallRgb = Mat()
                Imgproc.cvtColor(smallBgr, smallRgb, Imgproc.COLOR_BGR2RGB)
                smallRgb.get(0, 0, rgb518)
                smallBgr.release(); smallRgb.release()
                if (hasMask) {
                    for (y in 0 until SIZE) {
                        val vi = ((y + 0.5) * camera.height / SIZE).toInt().coerceIn(0, camera.height - 1)
                        for (x in 0 until SIZE) {
                            val ui = ((x + 0.5) * camera.width / SIZE).toInt().coerceIn(0, camera.width - 1)
                            if (maskMat.get(vi, ui)[0] < 128.0) validMask518[y * SIZE + x] = false
                        }
                    }
                }
            } finally {
                bgr.release(); maskMat?.release()
            }
            val depthCam = DepthCamera(
                camera.fx, camera.fy, camera.cx, camera.cy, camera.width, camera.height,
                camera.k1, camera.k2, camera.p1, camera.p2, camera.k3,
            )
            val bp = boardByFile[frame.name]?.let { RigidPose(it.r, it.t) }
            val outcome = patchMap.addPatch(
                rawPose = RigidPose(frame.pose.r, frame.pose.t),
                boardPose = bp,
                camera = depthCam,
                rawDepth = frame.depth,
                scale = frame.scale,
                offset = frame.offset,
                rgb518 = rgb518,
                laserCamM = readLaserPointsMeters(frame.laser),
                validMask518 = validMask518,
            )
            if (outcome.fusedIntoActiveSubmap) fusedPatches++
            outcome.registration?.let { reg ->
                if (reg.finalMedianResidualMm.isFinite()) icpResidualsMm.add(reg.finalMedianResidualMm)
                onLog(String.format(Locale.US,
                    "da3: patch %s -> submap=%d fused=%b overlap=%.0f%% res=%.1f->%.1fmm reason=%s",
                    frame.name, outcome.submapId, outcome.fusedIntoActiveSubmap,
                    reg.overlapFraction * 100.0, reg.initialMedianResidualMm, reg.finalMedianResidualMm, reg.reason))
            }
            onProgress(0.6f + 0.25f * (idx + 1) / frames.size, "Patch " + (idx + 1) + "/" + frames.size)
        }

        // Sweep all captured laser frames over the registered PatchMap to refine detail.
        onProgress(0.86f, "Refining patch map with laser sweep")
        val sweepFrames = loadLaserSweep(scan, poseSource)
        var sweepAccepted = 0
        for (sf in sweepFrames) {
            val pts = readLaserPointsMeters(sf.laserFile)
            if (pts.size < 3) continue
            val (st, _) = patchMap.addLaserFrame(sf.pose, null, pts)
            sweepAccepted += st.acceptedPoints
        }

        val combined = patchMap.buildActiveSubmapMesh(useTsdf = true) ?: error("no points survived the patch map")
        val outFile = File(scan.dir, "dense_da3.ply")
        writePly(outFile, combined.positions, combined.colors, combined.vertexCount)
        val meshFile = File(scan.dir, "dense_da3_mesh.ply")
        val wroteMesh = if (combined.triangleCount > 0) {
            AdaptiveDepthMesh.writeMeshPly(meshFile, combined, toMillimetres = false)
            true
        } else false

        val mapPm = PlacementMetrics(
            frameFile = frames.first().name,
            frameIndex = frames.first().frameIndex,
            cameraCentreM = frames.first().pose.cameraCentre(),
            scale = medianScale,
            laserTotal = sweepAccepted,
            laserMatched = sweepAccepted,
            laserDepthMedianM = motionMode.medianLaserDepthM,
            preRefineMedianErrMm = if (icpResidualsMm.isNotEmpty()) median(icpResidualsMm) else Double.NaN,
            preRefineP95ErrMm = if (icpResidualsMm.isNotEmpty()) percentile(icpResidualsMm, 0.95) else Double.NaN,
            preRefineMeanSignedErrMm = Double.NaN,
            laserRefinedGridCells = combined.laserCells,
            postRefineWorldMedianErrMm = if (icpResidualsMm.isNotEmpty()) median(icpResidualsMm) else Double.NaN,
            postRefineWorldP95ErrMm = if (icpResidualsMm.isNotEmpty()) percentile(icpResidualsMm, 0.95) else Double.NaN,
            maxReprojErrPx = 0.0,
            patchBoundsMinM = combined.boundsMin,
            patchBoundsMaxM = combined.boundsMax,
            laserBoundsMinM = null,
            laserBoundsMaxM = null,
            meshVertices = combined.vertexCount,
            meshFaces = combined.triangleCount,
            sweepLaserFrames = sweepFrames.size,
            sweepLaserPoints = sweepAccepted,
            refinedBlocks = combined.refinedBlocks,
            totalBlocks = combined.totalBlocks,
            laserCells = combined.laserCells,
        )
        writeDa3Report(
            scan.report, poseSource, false, motionMode, mapPm, combined.vertexCount,
            patchCount = patchMap.patchCount(), submapCount = patchMap.submapCount(), fusedPatches = fusedPatches,
        )
        work.deleteRecursively()
        onLog(String.format(Locale.US,
            "da3: patch map ready patches=%d submaps=%d fused=%d verts=%d faces=%d fineBlocks=%d sweepLaserPts=%d",
            patchMap.patchCount(), patchMap.submapCount(), fusedPatches,
            combined.vertexCount, combined.triangleCount, combined.refinedBlocks, sweepAccepted))
        onProgress(1f, "Patch map ready")
        return Result(
            points = combined.vertexCount,
            poseSource = poseSource,
            singleFrame = false,
            outFile = outFile,
            overlayFile = null,
            meshFile = if (wroteMesh) meshFile else null,
            motionMode = motionMode,
            placement = mapPm,
        )
    }

    // --------------------------------------------------------- pose & frame selection

    internal fun parseCameras(scanJson: JSONObject): HashMap<Int, Camera> {
        val cameras = HashMap<Int, Camera>()
        val cameraArray = scanJson.getJSONArray("cameras")
        for (i in 0 until cameraArray.length()) {
            val c = cameraArray.getJSONObject(i)
            cameras[c.getInt("index")] = Camera(
                fx = c.getDouble("fx"),
                fy = c.getDouble("fy"),
                cx = c.getDouble("cx"),
                cy = c.getDouble("cy"),
                width = c.getInt("width"),
                height = c.getInt("height"),
                k1 = c.optDouble("k1", 0.0),
                k2 = c.optDouble("k2", 0.0),
                p1 = c.optDouble("p1", 0.0),
                p2 = c.optDouble("p2", 0.0),
                k3 = c.optDouble("k3", 0.0),
            )
        }
        return cameras
    }

    internal fun parseImageList(scanJson: JSONObject): List<Pair<String, Int>> {
        val images = ArrayList<Pair<String, Int>>()
        val imageArray = scanJson.getJSONArray("images")
        for (i in 0 until imageArray.length()) {
            val im = imageArray.getJSONObject(i)
            images.add(im.getString("file") to im.optInt("camera", 0))
        }
        return images
    }

    /** Validates a 4x4 row-major rigid transform (world -> camera). */
    internal fun parsePose16(v: DoubleArray, translationScale: Double = 1.0): Pose? {
        if (v.size < 16) return null
        for (i in 0 until 12) if (!v[i].isFinite()) return null
        val r = doubleArrayOf(v[0], v[1], v[2], v[4], v[5], v[6], v[8], v[9], v[10])
        val det = r[0] * (r[4] * r[8] - r[5] * r[7]) -
            r[1] * (r[3] * r[8] - r[5] * r[6]) +
            r[2] * (r[3] * r[7] - r[4] * r[6])
        if (!det.isFinite() || det < 0.95 || det > 1.05) return null
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                val dot = r[i] * r[j] + r[i + 3] * r[j + 3] + r[i + 6] * r[j + 6]
                val expected = if (i == j) 1.0 else 0.0
                if (abs(dot - expected) > 0.05) return null
            }
        }
        val t = doubleArrayOf(v[3] * translationScale, v[7] * translationScale, v[11] * translationScale)
        return Pose(r, t)
    }

    private fun parsePose16String(raw: String, translationScale: Double = 1.0): Pose? {
        if (raw.isBlank()) return null
        val values = raw.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
        if (values.size < 16) return null
        return parsePose16(DoubleArray(16) { values[it] }, translationScale)
    }

    /**
     * Selects the active pose source for DA3.
     * Default priority: recorded VIO pose (`scan.json` / `hybrid/hybrid.json`) -> COLMAP `images.txt`.
     * Board pose (`board_pose16`) is only chosen ahead of VIO when [preferBoard] is explicitly true.
     */
    internal fun selectPoses(
        scan: Scan,
        preferVio: Boolean = true,
        preferBoard: Boolean = false,
    ): PoseSelection {
        val vioPoses = LinkedHashMap<String, Pose>()
        runCatching {
            scan.load()?.images?.forEach { im ->
                val vp = im.vioPose ?: return@forEach
                parsePose16(vp, 1.0)?.let { vioPoses[im.file] = it }
            }
        }
        val boardPoses = LinkedHashMap<String, Pose>()
        val hybridFile = File(scan.dir, "hybrid/hybrid.json")
        if (hybridFile.isFile) {
            runCatching {
                val hybrid = JSONObject(hybridFile.readText())
                val fileFrame = HashMap<String, Int>()
                hybrid.optJSONArray("images")?.let { arr ->
                    for (k in 0 until arr.length()) {
                        val row = arr.optJSONObject(k) ?: continue
                        val file = row.optString("file", "")
                        if (file.isEmpty()) continue
                        val frame = row.optInt("frame", -1)
                        if (frame >= 0) fileFrame[file] = frame
                        if (!vioPoses.containsKey(file)) {
                            parsePose16String(row.optString("vio_pose16", ""), 1.0)?.let { vioPoses[file] = it }
                        }
                        parsePose16String(row.optString("board_pose16", ""), 1.0 / 1000.0)?.let {
                            boardPoses[file] = it
                        }
                    }
                }
                val boardByFrame = HashMap<Int, Pose>()
                hybrid.optJSONArray("laser_frames")?.let { arr ->
                    for (k in 0 until arr.length()) {
                        val row = arr.optJSONObject(k) ?: continue
                        val frame = row.optInt("frame", -1)
                        if (frame < 0) continue
                        parsePose16String(row.optString("board_pose16", ""), 1.0 / 1000.0)?.let {
                            boardByFrame[frame] = it
                        }
                    }
                }
                for ((file, frame) in fileFrame) {
                    if (!boardPoses.containsKey(file)) {
                        boardByFrame[frame]?.let { boardPoses[file] = it }
                    }
                }
            }
        }
        val colmapPoses = parsePoses(File(scan.dir, "sparse/0/images.txt"))
        return when {
            preferBoard && boardPoses.isNotEmpty() -> PoseSelection(PoseSource.BOARD, boardPoses)
            preferVio && vioPoses.isNotEmpty() -> PoseSelection(PoseSource.VIO, vioPoses)
            colmapPoses.isNotEmpty() -> PoseSelection(PoseSource.COLMAP, colmapPoses)
            vioPoses.isNotEmpty() -> PoseSelection(PoseSource.VIO, vioPoses)
            boardPoses.isNotEmpty() -> PoseSelection(PoseSource.BOARD, boardPoses)
            else -> PoseSelection(PoseSource.COLMAP, colmapPoses)
        }
    }

    internal data class HybridLaserMap(
        val laserByFile: Map<String, File>,
        val frameIndexByFile: Map<String, Int>,
        val allLaserFiles: List<File>,
    )

    internal fun loadHybridLaserMap(scan: Scan): HybridLaserMap {
        val laserByFile = HashMap<String, File>()
        val frameIndexByFile = HashMap<String, Int>()
        val laserByFrame = HashMap<Int, File>()
        val allLaser = LinkedHashSet<File>()
        val hybridFile = File(scan.dir, "hybrid/hybrid.json")
        if (hybridFile.isFile) {
            runCatching {
                val hybrid = JSONObject(hybridFile.readText())
                hybrid.optJSONArray("laser_frames")?.let { a ->
                    for (k in 0 until a.length()) {
                        val row = a.optJSONObject(k) ?: continue
                        val frame = row.optInt("frame", -1)
                        val rel = row.optString("laser", "")
                        if (frame >= 0 && rel.isNotEmpty()) {
                            val f = File(scan.dir, rel)
                            laserByFrame[frame] = f
                            if (f.isFile && f.length() >= 12L) allLaser.add(f)
                        }
                    }
                }
                hybrid.optJSONArray("images")?.let { a ->
                    for (k in 0 until a.length()) {
                        val row = a.optJSONObject(k) ?: continue
                        val file = row.optString("file", "")
                        if (file.isEmpty()) continue
                        val frame = row.optInt("frame", -1)
                        if (frame >= 0) frameIndexByFile[file] = frame
                        val rel = row.optString("laser", "")
                        val resolved = when {
                            rel.isNotEmpty() -> File(scan.dir, rel)
                            frame >= 0 -> laserByFrame[frame]
                            else -> null
                        }
                        if (resolved != null) {
                            laserByFile[file] = resolved
                            if (resolved.isFile && resolved.length() >= 12L) allLaser.add(resolved)
                        }
                    }
                }
            }
        }
        return HybridLaserMap(laserByFile, frameIndexByFile, allLaser.toList())
    }

    internal data class LaserSweepFrame(val frameIndex: Int, val pose: RigidPose, val laserFile: File)

    /**
     * Loads every laser frame in `hybrid/hybrid.json` (`laser_frames[]`) paired with its
     * recorded pose in the active [poseSource] frame so the full laser sweep refines the
     * single-frame DA3 mesh.
     */
    internal fun loadLaserSweep(scan: Scan, poseSource: PoseSource): List<LaserSweepFrame> {
        if (poseSource == PoseSource.COLMAP) return emptyList()
        val hybridFile = File(scan.dir, "hybrid/hybrid.json")
        if (!hybridFile.isFile) return emptyList()
        val out = ArrayList<LaserSweepFrame>()
        runCatching {
            val hybrid = JSONObject(hybridFile.readText())
            val arr = hybrid.optJSONArray("laser_frames") ?: hybrid.optJSONArray("images") ?: return@runCatching
            val key = if (poseSource == PoseSource.BOARD) "board_pose16" else "vio_pose16"
            val tScale = if (poseSource == PoseSource.BOARD) 1.0 / 1000.0 else 1.0
            for (k in 0 until arr.length()) {
                val row = arr.optJSONObject(k) ?: continue
                val rel = row.optString("laser", "")
                if (rel.isEmpty()) continue
                val f = File(scan.dir, rel)
                if (!f.isFile || f.length() < 12L) continue
                val p = parsePose16String(row.optString(key, ""), tScale) ?: continue
                out.add(LaserSweepFrame(row.optInt("frame", k), RigidPose(p.r, p.t), f))
            }
        }
        return out
    }

    /**
     * Chooses the frames to run DA3 on. In single-frame mode ([maxFrames] == 1),
     * prefers the first registered frame that carries enough laser points to scale
     * the depth map cleanly.
     */
    internal fun selectFrames(
        registered: List<Pair<String, Int>>,
        laserByFile: Map<String, File>,
        maxFrames: Int,
    ): List<Pair<String, Int>> {
        if (registered.isEmpty()) return emptyList()
        if (maxFrames == 1) {
            val strong = registered.firstOrNull { (name, _) ->
                val f = laserByFile[name]
                f != null && f.isFile && f.length() >= MIN_LASER_PAIRS * 12L
            }
            val any = strong ?: registered.firstOrNull { (name, _) ->
                val f = laserByFile[name]
                f != null && f.isFile && f.length() >= 12L
            }
            return listOf(any ?: registered.first())
        }
        return if (maxFrames > 0) registered.take(maxFrames) else registered
    }

    /**
     * Phase 3 coverage-based keyframe selection (`NORTH_STAR.md`):
     * - Tilting (`parallax < 0.04`): returns 1 strong-laser frame so the full laser sweep refines one patch.
     * - Moving (`parallax >= 0.04`): picks keyframes whenever translation exceeds `0.05 * medianLaserDepth`
     *   or rotation exceeds `12°`, up to [maxKeyframes].
     */
    internal fun selectCoverageKeyframes(
        registered: List<Pair<String, Int>>,
        poses: Map<String, Pose>,
        laserByFile: Map<String, File>,
        motionMode: MotionMode,
        maxKeyframes: Int = 16,
    ): List<Pair<String, Int>> {
        if (registered.isEmpty()) return emptyList()
        val first = selectFrames(registered, laserByFile, 1).first()
        if (motionMode.mode == "tilting") return listOf(first)
        val transThresh = max(0.015, 0.05 * motionMode.medianLaserDepthM.coerceAtLeast(0.3))
        val rotThreshDeg = 12.0
        val chosen = ArrayList<Pair<String, Int>>()
        val chosenPoses = ArrayList<RigidPose>()
        chosen.add(first)
        poses[first.first]?.let { chosenPoses.add(RigidPose(it.r, it.t)) }
        for (item in registered) {
            if (chosen.size >= maxKeyframes) break
            if (item.first == first.first) continue
            val lf = laserByFile[item.first]
            if (laserByFile.isNotEmpty() && (lf == null || !lf.isFile || lf.length() < MIN_LASER_PAIRS * 12L)) continue
            val p = poses[item.first] ?: continue
            val rp = RigidPose(p.r, p.t)
            val minT = chosenPoses.minOfOrNull { LaserIcp.translationDeltaM(rp, it) } ?: Double.POSITIVE_INFINITY
            val minR = chosenPoses.minOfOrNull { LaserIcp.rotationDeltaDeg(rp, it) } ?: Double.POSITIVE_INFINITY
            if (minT > transThresh || minR > rotThreshDeg) {
                chosen.add(item)
                chosenPoses.add(rp)
            }
        }
        return chosen
    }

    // --------------------------------------------------------- motion mode

    /** Computes translation span, median laser depth, and parallax ratio (`NORTH_STAR.md`). */
    internal fun computeMotionMode(poses: List<Pose>, laserFiles: List<File>): MotionMode {
        val depths = ArrayList<Double>()
        for (f in laserFiles) {
            val pts = readLaserPointsMeters(f)
            var i = 2
            while (i < pts.size) {
                val z = pts[i].toDouble()
                if (z.isFinite() && z > 0.0 && z <= 20.0) depths.add(z)
                i += 3
            }
        }
        val medianDepth = if (depths.isNotEmpty()) median(depths) else 0.0
        return computeMotionModeFromSamples(poses, medianDepth)
    }

    internal fun computeMotionModeFromSamples(poses: List<Pose>, medianLaserDepthM: Double): MotionMode {
        if (poses.size < 2) {
            return MotionMode("unknown", poses.size, 0.0, 0.0, medianLaserDepthM, Double.NaN)
        }
        val centres = poses.map { it.cameraCentre() }
        val rawMax = maxPairwiseDistance(centres)
        val robustSpan = robustTranslationSpan(centres)
        if (medianLaserDepthM <= 1e-6 || !medianLaserDepthM.isFinite()) {
            return MotionMode("unknown", poses.size, robustSpan, rawMax, 0.0, Double.NaN)
        }
        val parallax = robustSpan / medianLaserDepthM
        val mode = if (parallax < MOTION_PARALLAX_THRESHOLD) "tilting" else "moving"
        return MotionMode(mode, poses.size, robustSpan, rawMax, medianLaserDepthM, parallax)
    }

    private fun maxPairwiseDistance(centres: List<DoubleArray>): Double {
        var best = 0.0
        for (i in centres.indices) {
            val a = centres[i]
            for (j in i + 1 until centres.size) {
                val b = centres[j]
                val d = dist3(a, b)
                if (d > best) best = d
            }
        }
        return best
    }

    private fun robustTranslationSpan(centres: List<DoubleArray>): Double {
        if (centres.size < 2) return 0.0
        val mx = median(centres.map { it[0] })
        val my = median(centres.map { it[1] })
        val mz = median(centres.map { it[2] })
        val medCentre = doubleArrayOf(mx, my, mz)
        val radii = centres.map { dist3(it, medCentre) }
        val medR = median(radii)
        val mad = median(radii.map { abs(it - medR) }).coerceAtLeast(0.005)
        val cutoff = medR + 3.5 * mad
        val inliers = centres.filterIndexed { i, _ -> radii[i] <= cutoff }.let {
            if (it.size >= 2) it else centres
        }
        val pairs = ArrayList<Double>(inliers.size * (inliers.size - 1) / 2)
        for (i in inliers.indices) {
            val a = inliers[i]
            for (j in i + 1 until inliers.size) {
                pairs.add(dist3(a, inliers[j]))
            }
        }
        pairs.sort()
        return if (inliers.size < 5) pairs.last() else pairs[((pairs.size - 1) * 0.95).roundToInt()]
    }

    private fun dist3(a: DoubleArray, b: DoubleArray): Double {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // --------------------------------------------------------- depth sampling & scaling

    internal fun readLaserPointsMeters(file: File?): FloatArray {
        if (file == null || !file.isFile || file.length() < 12L) return FloatArray(0)
        val raw = file.readBytes()
        val count = raw.size / 12
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(count * 3)
        var w = 0
        for (i in 0 until count) {
            val x = bb.getFloat() / 1000f
            val y = bb.getFloat() / 1000f
            val z = bb.getFloat() / 1000f
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() || z <= 0f || z > 20f) continue
            out[w++] = x; out[w++] = y; out[w++] = z
        }
        return if (w == out.size) out else out.copyOf(w)
    }

    /** Bilinear sample of the 518x518 DA3 depth grid at distorted pixel coordinates `(u, v)`. */
    internal fun sampleDepthBilinear(depth: FloatArray, u: Double, v: Double, camera: Camera): Float {
        val gx = u * SIZE / camera.width - 0.5
        val gy = v * SIZE / camera.height - 0.5
        val x0 = Math.floor(gx).toInt().coerceIn(0, SIZE - 1)
        val y0 = Math.floor(gy).toInt().coerceIn(0, SIZE - 1)
        val x1 = (x0 + 1).coerceIn(0, SIZE - 1)
        val y1 = (y0 + 1).coerceIn(0, SIZE - 1)
        val fx = (gx - x0).coerceIn(0.0, 1.0)
        val fy = (gy - y0).coerceIn(0.0, 1.0)
        var sum = 0.0
        var wSum = 0.0
        fun acc(ix: Int, iy: Int, w: Double) {
            val d = depth[iy * SIZE + ix]
            if (d > 0f && d.isFinite()) {
                sum += d * w
                wSum += w
            }
        }
        acc(x0, y0, (1.0 - fx) * (1.0 - fy))
        acc(x1, y0, fx * (1.0 - fy))
        acc(x0, y1, (1.0 - fx) * fy)
        acc(x1, y1, fx * fy)
        return if (wSum > 0.0) (sum / wSum).toFloat() else Float.NaN
    }

    /** DA3-depth / metric-depth pairs from this frame's laser line (metres). */
    internal fun laserPairs(depth: FloatArray, laserCamM: FloatArray, camera: Camera): List<Pair<Double, Double>> {
        if (laserCamM.size < 3) return emptyList()
        val pairs = ArrayList<Pair<Double, Double>>(laserCamM.size / 3)
        var i = 0
        while (i + 2 < laserCamM.size) {
            val x = laserCamM[i].toDouble()
            val y = laserCamM[i + 1].toDouble()
            val z = laserCamM[i + 2].toDouble()
            i += 3
            if (z <= 0.0 || z > 20.0) continue
            val uv = camera.project(x, y, z) ?: continue
            if (uv[0] < 0.0 || uv[0] >= camera.width || uv[1] < 0.0 || uv[1] >= camera.height) continue
            val d = sampleDepthBilinear(depth, uv[0], uv[1], camera)
            if (!(d > 0f) || !d.isFinite()) continue
            pairs.add(d.toDouble() to z)
        }
        return pairs
    }

    internal fun fitScaleLaser(depth: FloatArray, laser: File?, camera: Camera): Fit? =
        fitAffine(laserPairs(depth, readLaserPointsMeters(laser), camera), MIN_LASER_PAIRS)

    private fun fitScale(
        depth: FloatArray,
        pose: Pose,
        observations: List<Observation>,
        camera: Camera,
        points: HashMap<Int, DoubleArray>,
    ): Fit? {
        val pairs = ArrayList<Pair<Double, Double>>()
        for (observation in observations) {
            val point = points[observation.pointId] ?: continue
            val d = sampleDepthBilinear(depth, observation.x, observation.y, camera)
            if (!(d > 0f) || !d.isFinite()) continue
            val r = pose.r
            val t = pose.t
            val z = r[6] * point[0] + r[7] * point[1] + r[8] * point[2] + t[2]
            if (z <= 0.0) continue
            pairs.add(d.toDouble() to z)
        }
        return fitAffine(pairs, 6)
    }

    /** Robust metric-depth / DA3-depth slope; the offset is not observable here. */
    internal fun fitAffine(pairs: List<Pair<Double, Double>>, minimum: Int): Fit? {
        if (pairs.size < minimum) return null
        var current = pairs
        var fit: Fit? = null
        repeat(4) {
            var xx = 0.0
            var xy = 0.0
            for ((x, y) in current) {
                xx += x * x; xy += x * y
            }
            if (xx < 1e-12) return null
            val a = xy / xx
            if (!a.isFinite() || a <= 0.0) return null
            val residuals = current.map { abs(it.second - a * it.first) }
            fit = Fit(a, 0.0, current.size, residuals.average())
            val sorted = residuals.sorted()
            val mad = sorted[sorted.size / 2].coerceAtLeast(1e-6)
            val filtered = current.filterIndexed { index, _ -> residuals[index] <= 2.5 * mad }
            if (filtered.size < minimum || filtered.size == current.size) return fit
            current = filtered
        }
        return fit
    }

    // --------------------------------------------------------- single-frame patch & verification

    /**
     * Builds a single-frame coarse mesh + point patch in the VIO world frame,
     * refines the sampled grid cells directly with the frame's camera-frame laser
     * points, and measures pre/post refinement alignment and reprojection error.
     */
    internal fun buildSingleFramePatch(
        frameFile: String,
        frameIndex: Int,
        depth: FloatArray,
        scale: Double,
        offset: Double,
        pose: Pose,
        camera: Camera,
        laserCamM: FloatArray,
        isMaskedOut: (ui: Int, vi: Int) -> Boolean,
        sampleRgb: (ui: Int, vi: Int) -> IntArray?,
    ): SingleFramePatch {
        // 1. Pre-refinement laser alignment on the raw scaled DA3 depth map.
        val preAbsErrMm = ArrayList<Double>()
        val preSignedErrMm = ArrayList<Double>()
        val laserDepthsM = ArrayList<Double>()
        val laserTotal = laserCamM.size / 3
        run {
            var i = 0
            while (i + 2 < laserCamM.size) {
                val lx = laserCamM[i].toDouble()
                val ly = laserCamM[i + 1].toDouble()
                val lz = laserCamM[i + 2].toDouble()
                i += 3
                if (lz <= 0.0 || lz > 20.0) continue
                laserDepthsM.add(lz)
                val uv = camera.project(lx, ly, lz) ?: continue
                if (uv[0] < 0.0 || uv[0] >= camera.width || uv[1] < 0.0 || uv[1] >= camera.height) continue
                val d = sampleDepthBilinear(depth, uv[0], uv[1], camera)
                if (!(d > 0f) || !d.isFinite()) continue
                val predZ = d * scale + offset
                val diffMm = (predZ - lz) * 1000.0
                preSignedErrMm.add(diffMm)
                preAbsErrMm.add(abs(diffMm))
            }
        }

        // 2. Populate the STRIDE-sampled metric depth grid, then refine hit grid cells
        //    directly from the frame's metric laser points (so STRIDE never drops 75% of them).
        val grid = GRID
        val metricDepth = FloatArray(grid * grid) { Float.NaN }
        for (gy in 0 until grid) {
            val y = gy * STRIDE
            if (y >= SIZE) continue
            for (gx in 0 until grid) {
                val x = gx * STRIDE
                if (x >= SIZE) continue
                val d = depth[y * SIZE + x]
                if (!(d > 0f) || !d.isFinite()) continue
                val z = d * scale + offset
                if (z > 0.0 && z <= 1e4) metricDepth[gy * grid + gx] = z.toFloat()
            }
        }
        val laserSum = DoubleArray(grid * grid)
        val laserCount = IntArray(grid * grid)
        val laserGridCells = ArrayList<Pair<Int, FloatArray>>(laserTotal)
        val laserWorld = ArrayList<FloatArray>(laserTotal)
        var lMinX = Float.POSITIVE_INFINITY; var lMinY = Float.POSITIVE_INFINITY; var lMinZ = Float.POSITIVE_INFINITY
        var lMaxX = Float.NEGATIVE_INFINITY; var lMaxY = Float.NEGATIVE_INFINITY; var lMaxZ = Float.NEGATIVE_INFINITY
        run {
            var i = 0
            while (i + 2 < laserCamM.size) {
                val lx = laserCamM[i].toDouble()
                val ly = laserCamM[i + 1].toDouble()
                val lz = laserCamM[i + 2].toDouble()
                i += 3
                if (lz <= 0.0 || lz > 20.0) continue
                val w = pose.cameraToWorld(lx, ly, lz)
                if (!w[0].isFinite() || !w[1].isFinite() || !w[2].isFinite()) continue
                val wf = floatArrayOf(w[0].toFloat(), w[1].toFloat(), w[2].toFloat())
                laserWorld.add(wf)
                lMinX = min(lMinX, wf[0]); lMinY = min(lMinY, wf[1]); lMinZ = min(lMinZ, wf[2])
                lMaxX = max(lMaxX, wf[0]); lMaxY = max(lMaxY, wf[1]); lMaxZ = max(lMaxZ, wf[2])
                val uv = camera.project(lx, ly, lz) ?: continue
                if (uv[0] < 0.0 || uv[0] >= camera.width || uv[1] < 0.0 || uv[1] >= camera.height) continue
                val gx = ((uv[0] * SIZE / camera.width - 0.5) / STRIDE).roundToInt().coerceIn(0, grid - 1)
                val gy = ((uv[1] * SIZE / camera.height - 0.5) / STRIDE).roundToInt().coerceIn(0, grid - 1)
                val idx = gy * grid + gx
                laserSum[idx] += lz
                laserCount[idx]++
                laserGridCells.add(idx to wf)
            }
        }
        var refinedCells = 0
        for (idx in laserCount.indices) {
            if (laserCount[idx] > 0) {
                metricDepth[idx] = (laserSum[idx] / laserCount[idx]).toFloat()
                refinedCells++
            }
        }

        // 3. Back-project the refined metric grid into VIO world coordinates.
        val pos = FloatArray(grid * grid * 3)
        val col = ByteArray(grid * grid * 3)
        val vertIndex = IntArray(grid * grid) { -1 }
        val vertDepth = FloatArray(grid * grid) { Float.NaN }
        var vertices = 0
        var pMinX = Float.POSITIVE_INFINITY; var pMinY = Float.POSITIVE_INFINITY; var pMinZ = Float.POSITIVE_INFINITY
        var pMaxX = Float.NEGATIVE_INFINITY; var pMaxY = Float.NEGATIVE_INFINITY; var pMaxZ = Float.NEGATIVE_INFINITY
        var maxReprojErrPx = 0.0
        for (gy in 0 until grid) {
            val y = gy * STRIDE
            if (y >= SIZE) continue
            val vf = (y + 0.5) * camera.height / SIZE
            val vi = vf.toInt().coerceIn(0, camera.height - 1)
            for (gx in 0 until grid) {
                val x = gx * STRIDE
                if (x >= SIZE) continue
                val idx = gy * grid + gx
                val z = metricDepth[idx].toDouble()
                if (!z.isFinite() || z <= 0.0 || z > 1e4) continue
                val uf = (x + 0.5) * camera.width / SIZE
                val ui = uf.toInt().coerceIn(0, camera.width - 1)
                if (isMaskedOut(ui, vi)) continue
                val rgb = sampleRgb(ui, vi) ?: continue
                val ray = camera.undistort(uf, vf)
                val w = pose.cameraToWorld(ray[0] * z, ray[1] * z, z)
                if (!w[0].isFinite() || !w[1].isFinite() || !w[2].isFinite()) continue
                // Spot-check forward reprojection inside the invertible lens domain to verify pose + distortion.
                if ((gx and 15) == 0 && (gy and 15) == 0 && camera.isInvertible(uf, vf)) {
                    val camBack = pose.worldToCamera(w[0], w[1], w[2])
                    val uvBack = camera.project(camBack[0], camBack[1], camBack[2])
                    if (uvBack != null) {
                        val err = hypot(uvBack[0] - uf, uvBack[1] - vf)
                        if (err > maxReprojErrPx) maxReprojErrPx = err
                    }
                }
                val k = vertices++
                vertIndex[idx] = k
                vertDepth[idx] = z.toFloat()
                val wx = w[0].toFloat(); val wy = w[1].toFloat(); val wz = w[2].toFloat()
                pos[k * 3] = wx; pos[k * 3 + 1] = wy; pos[k * 3 + 2] = wz
                col[k * 3] = rgb[0].coerceIn(0, 255).toByte()
                col[k * 3 + 1] = rgb[1].coerceIn(0, 255).toByte()
                col[k * 3 + 2] = rgb[2].coerceIn(0, 255).toByte()
                pMinX = min(pMinX, wx); pMinY = min(pMinY, wy); pMinZ = min(pMinZ, wz)
                pMaxX = max(pMaxX, wx); pMaxY = max(pMaxY, wy); pMaxZ = max(pMaxZ, wz)
            }
        }

        // 4. Triangulate adjacent cells whose depths stay continuous on all four quad edges.
        val faceList = ArrayList<Int>(vertices * 6)
        for (gy in 0 until grid - 1) {
            for (gx in 0 until grid - 1) {
                val i00 = gy * grid + gx
                val i10 = i00 + 1
                val i01 = i00 + grid
                val i11 = i01 + 1
                val a = vertIndex[i00]; val b = vertIndex[i10]
                val c = vertIndex[i01]; val d = vertIndex[i11]
                if (a < 0 || b < 0 || c < 0 || d < 0) continue
                val da = vertDepth[i00]; val db = vertDepth[i10]
                val dc = vertDepth[i01]; val dd = vertDepth[i11]
                val minD = min(min(da, db), min(dc, dd))
                val limit = MESH_EDGE_JUMP_RATIO * minD
                if (abs(da - db) > limit || abs(da - dc) > limit ||
                    abs(db - dd) > limit || abs(dc - dd) > limit) continue
                faceList.add(a); faceList.add(b); faceList.add(d)
                faceList.add(a); faceList.add(d); faceList.add(c)
            }
        }
        val faces = IntArray(faceList.size) { faceList[it] }

        // 5. Post-refinement world-space distance between each laser point and its hit patch vertex.
        val postWorldErrMm = ArrayList<Double>(laserGridCells.size)
        for ((cellIdx, lw) in laserGridCells) {
            val k = vertIndex[cellIdx]
            if (k < 0) continue
            val dx = pos[k * 3] - lw[0]
            val dy = pos[k * 3 + 1] - lw[1]
            val dz = pos[k * 3 + 2] - lw[2]
            postWorldErrMm.add(sqrt((dx * dx + dy * dy + dz * dz).toDouble()) * 1000.0)
        }

        val placement = PlacementMetrics(
            frameFile = frameFile,
            frameIndex = frameIndex,
            cameraCentreM = pose.cameraCentre(),
            scale = scale,
            laserTotal = laserTotal,
            laserMatched = preAbsErrMm.size,
            laserDepthMedianM = if (laserDepthsM.isNotEmpty()) median(laserDepthsM) else 0.0,
            preRefineMedianErrMm = if (preAbsErrMm.isNotEmpty()) median(preAbsErrMm) else Double.NaN,
            preRefineP95ErrMm = if (preAbsErrMm.isNotEmpty()) percentile(preAbsErrMm, 0.95) else Double.NaN,
            preRefineMeanSignedErrMm = if (preSignedErrMm.isNotEmpty()) preSignedErrMm.average() else Double.NaN,
            laserRefinedGridCells = refinedCells,
            postRefineWorldMedianErrMm = if (postWorldErrMm.isNotEmpty()) median(postWorldErrMm) else Double.NaN,
            postRefineWorldP95ErrMm = if (postWorldErrMm.isNotEmpty()) percentile(postWorldErrMm, 0.95) else Double.NaN,
            maxReprojErrPx = maxReprojErrPx,
            patchBoundsMinM = if (vertices > 0) floatArrayOf(pMinX, pMinY, pMinZ) else FloatArray(3),
            patchBoundsMaxM = if (vertices > 0) floatArrayOf(pMaxX, pMaxY, pMaxZ) else FloatArray(3),
            laserBoundsMinM = if (laserWorld.isNotEmpty()) floatArrayOf(lMinX, lMinY, lMinZ) else null,
            laserBoundsMaxM = if (laserWorld.isNotEmpty()) floatArrayOf(lMaxX, lMaxY, lMaxZ) else null,
            meshVertices = vertices,
            meshFaces = faces.size / 3,
        )

        return SingleFramePatch(
            positions = pos.copyOf(vertices * 3),
            colors = col.copyOf(vertices * 3),
            vertexCount = vertices,
            faces = faces,
            laserWorld = laserWorld,
            placement = placement,
        )
    }

    // --------------------------------------------------------- report.json

    /** Non-destructively merges DA3 pose source, motion mode, and placement metrics into `report.json`. */
    internal fun writeDa3Report(
        reportFile: File,
        poseSource: PoseSource,
        singleFrame: Boolean,
        motionMode: MotionMode,
        placement: PlacementMetrics?,
        patchPoints: Int,
        patchCount: Int = 1,
        submapCount: Int = 1,
        fusedPatches: Int = 1,
    ) {
        val root = runCatching {
            if (reportFile.isFile) JSONObject(reportFile.readText()) else JSONObject()
        }.getOrElse { JSONObject() }
        root.put("motionMode", motionMode.mode)
        if (motionMode.parallax.isFinite()) root.put("parallax", motionMode.parallax)
        val da3 = JSONObject()
            .put("poseSource", poseSource.wire)
            .put("singleFrame", singleFrame)
            .put("patchPoints", patchPoints)
            .put("patchCount", patchCount)
            .put("submapCount", submapCount)
            .put("fusedPatches", fusedPatches)
            .put("motionMode", motionMode.mode)
            .put("poseCount", motionMode.poseCount)
            .put("translationSpanM", motionMode.translationSpanM)
            .put("rawTranslationSpanM", motionMode.rawTranslationSpanM)
            .put("medianLaserDepthM", motionMode.medianLaserDepthM)
        if (motionMode.parallax.isFinite()) da3.put("parallax", motionMode.parallax)
        if (placement != null) {
            da3.put("frameFile", placement.frameFile)
                .put("frameIndex", placement.frameIndex)
                .put("cameraCentreM", jsonDoubles(placement.cameraCentreM))
                .put("scale", placement.scale)
                .put("laserTotal", placement.laserTotal)
                .put("laserMatched", placement.laserMatched)
                .put("laserDepthMedianM", placement.laserDepthMedianM)
                .put("laserRefinedGridCells", placement.laserRefinedGridCells)
                .put("maxReprojErrPx", placement.maxReprojErrPx)
                .put("meshVertices", placement.meshVertices)
                .put("meshFaces", placement.meshFaces)
                .put("sweepLaserFrames", placement.sweepLaserFrames)
                .put("sweepLaserPoints", placement.sweepLaserPoints)
                .put("sweepLaserOutliers", placement.sweepLaserOutliers)
                .put("refinedBlocks", placement.refinedBlocks)
                .put("totalBlocks", placement.totalBlocks)
                .put("laserCells", placement.laserCells)
                .put("patchBoundsMinM", jsonFloats(placement.patchBoundsMinM))
                .put("patchBoundsMaxM", jsonFloats(placement.patchBoundsMaxM))
            if (placement.preRefineMedianErrMm.isFinite()) {
                da3.put("preRefineMedianErrMm", placement.preRefineMedianErrMm)
                da3.put("preRefineP95ErrMm", placement.preRefineP95ErrMm)
                da3.put("preRefineMeanSignedErrMm", placement.preRefineMeanSignedErrMm)
            }
            if (placement.postRefineWorldMedianErrMm.isFinite()) {
                da3.put("postRefineWorldMedianErrMm", placement.postRefineWorldMedianErrMm)
                da3.put("postRefineWorldP95ErrMm", placement.postRefineWorldP95ErrMm)
            }
            placement.laserBoundsMinM?.let { da3.put("laserBoundsMinM", jsonFloats(it)) }
            placement.laserBoundsMaxM?.let { da3.put("laserBoundsMaxM", jsonFloats(it)) }
        }
        root.put("da3", da3)
        val tmp = File(reportFile.parentFile, reportFile.name + ".tmp")
        tmp.writeText(root.toString(2))
        if (!tmp.renameTo(reportFile)) {
            reportFile.writeText(root.toString(2))
            tmp.delete()
        }
    }

    private fun jsonDoubles(a: DoubleArray): JSONArray = JSONArray().apply { a.forEach { put(it) } }
    private fun jsonFloats(a: FloatArray): JSONArray = JSONArray().apply { a.forEach { put(it.toDouble()) } }

    // --------------------------------------------------------- voxel fusion & PLY I/O

    /** Averages overlapping patches into a small voxel grid; removes duplicate surfaces. */
    private fun fuseVoxels(
        positions: FloatArray,
        colors: ByteArray,
        count: Int,
        voxel: Float,
    ): Pair<FloatArray, ByteArray> {
        val cells = HashMap<Long, FloatArray>(count)
        for (i in 0 until count) {
            val x = positions[i * 3]
            val y = positions[i * 3 + 1]
            val z = positions[i * 3 + 2]
            val ix = Math.floor((x / voxel).toDouble()).toInt()
            val iy = Math.floor((y / voxel).toDouble()).toInt()
            val iz = Math.floor((z / voxel).toDouble()).toInt()
            val key = (ix.toLong() and 0x1fffffL) or
                ((iy.toLong() and 0x1fffffL) shl 21) or
                ((iz.toLong() and 0x1fffffL) shl 42)
            val cell = cells.getOrPut(key) { FloatArray(7) }
            cell[0] += x; cell[1] += y; cell[2] += z
            cell[3] += (colors[i * 3].toInt() and 0xff)
            cell[4] += (colors[i * 3 + 1].toInt() and 0xff)
            cell[5] += (colors[i * 3 + 2].toInt() and 0xff)
            cell[6] += 1f
        }
        val outPos = FloatArray(cells.size * 3)
        val outCol = ByteArray(cells.size * 3)
        var k = 0
        for (cell in cells.values) {
            val n = cell[6].coerceAtLeast(1f)
            outPos[k * 3] = cell[0] / n
            outPos[k * 3 + 1] = cell[1] / n
            outPos[k * 3 + 2] = cell[2] / n
            outCol[k * 3] = (cell[3] / n).toInt().coerceIn(0, 255).toByte()
            outCol[k * 3 + 1] = (cell[4] / n).toInt().coerceIn(0, 255).toByte()
            outCol[k * 3 + 2] = (cell[5] / n).toInt().coerceIn(0, 255).toByte()
            k++
        }
        return outPos to outCol
    }

    private fun readHalfDepth(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val depth = FloatArray(SIZE * SIZE)
        for (i in depth.indices) {
            val bits = buffer.short.toInt() and 0xffff
            depth[i] = halfToFloat(bits)
        }
        return depth
    }

    internal fun writePly(file: File, positions: FloatArray, colors: ByteArray, count: Int) {
        FileOutputStream(file).use { out ->
            val header = "ply\nformat binary_little_endian 1.0\n" +
                "comment Parallax DA3 dense\n" +
                "element vertex " + count + "\n" +
                "property float x\nproperty float y\nproperty float z\n" +
                "property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            val buffer = ByteBuffer.allocate(count * 15).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                buffer.putFloat(positions[i * 3])
                buffer.putFloat(positions[i * 3 + 1])
                buffer.putFloat(positions[i * 3 + 2])
                buffer.put(colors[i * 3])
                buffer.put(colors[i * 3 + 1])
                buffer.put(colors[i * 3 + 2])
            }
            out.write(buffer.array())
        }
    }

    internal fun writeMeshPly(file: File, positions: FloatArray, colors: ByteArray, vertices: Int, faces: IntArray) {
        val faceCount = faces.size / 3
        FileOutputStream(file).use { out ->
            val header = "ply\nformat binary_little_endian 1.0\n" +
                "comment Parallax DA3 single-frame mesh\n" +
                "element vertex " + vertices + "\n" +
                "property float x\nproperty float y\nproperty float z\n" +
                "property uchar red\nproperty uchar green\nproperty uchar blue\n" +
                "element face " + faceCount + "\n" +
                "property list uchar int vertex_indices\nend_header\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            val vb = ByteBuffer.allocate(vertices * 15).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until vertices) {
                vb.putFloat(positions[k * 3]); vb.putFloat(positions[k * 3 + 1]); vb.putFloat(positions[k * 3 + 2])
                vb.put(colors[k * 3]); vb.put(colors[k * 3 + 1]); vb.put(colors[k * 3 + 2])
            }
            out.write(vb.array())
            val fb = ByteBuffer.allocate(faceCount * 13).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until faceCount) {
                fb.put(3)
                fb.putInt(faces[i * 3]); fb.putInt(faces[i * 3 + 1]); fb.putInt(faces[i * 3 + 2])
            }
            out.write(fb.array())
        }
    }

    // --------------------------------------------------------- COLMAP fallback parsers

    internal fun parsePoses(file: File): HashMap<String, Pose> {
        val result = HashMap<String, Pose>()
        if (!file.isFile) return result
        val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        var i = 0
        while (i < lines.size) {
            val tokens = lines[i].trim().split(Regex("\\s+"))
            if (tokens.size >= 10 && tokens[0].toIntOrNull() != null) {
                val name = tokens[9]
                val qw = tokens[1].toDouble(); val qx = tokens[2].toDouble()
                val qy = tokens[3].toDouble(); val qz = tokens[4].toDouble()
                val tx = tokens[5].toDouble(); val ty = tokens[6].toDouble(); val tz = tokens[7].toDouble()
                val norm = sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
                if (norm > 1e-12) {
                    val w = qw / norm; val x = qx / norm; val y = qy / norm; val z = qz / norm
                    val r = DoubleArray(9)
                    r[0] = 1 - 2 * (y * y + z * z)
                    r[1] = 2 * (x * y - w * z)
                    r[2] = 2 * (x * z + w * y)
                    r[3] = 2 * (x * y + w * z)
                    r[4] = 1 - 2 * (x * x + z * z)
                    r[5] = 2 * (y * z - w * x)
                    r[6] = 2 * (x * z - w * y)
                    r[7] = 2 * (y * z + w * x)
                    r[8] = 1 - 2 * (x * x + y * y)
                    result[name] = Pose(r, doubleArrayOf(tx, ty, tz))
                }
                i += 2
            } else {
                i++
            }
        }
        return result
    }

    private fun parseObservations(file: File): HashMap<String, List<Observation>> {
        val result = HashMap<String, List<Observation>>()
        if (!file.isFile) return result
        val lines = file.readLines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("#")) { i++; continue }
            val tokens = line.split(Regex("\\s+"))
            if (tokens.size >= 10 && tokens[0].toIntOrNull() != null) {
                val name = tokens[9]
                val list = ArrayList<Observation>()
                if (i + 1 < lines.size) {
                    val points = lines[i + 1].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    var k = 0
                    while (k + 2 < points.size) {
                        val x = points[k].toDoubleOrNull()
                        val y = points[k + 1].toDoubleOrNull()
                        val id = points[k + 2].toIntOrNull()
                        if (x != null && y != null && id != null && id >= 0) list.add(Observation(x, y, id))
                        k += 3
                    }
                }
                result[name] = list
                i += 2
            } else {
                i++
            }
        }
        return result
    }

    private fun parsePoints(file: File): HashMap<Int, DoubleArray> {
        val result = HashMap<Int, DoubleArray>()
        if (!file.isFile) return result
        for (line in file.readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.size < 8) continue
            val id = tokens[0].toIntOrNull() ?: continue
            val x = tokens[1].toDoubleOrNull() ?: continue
            val y = tokens[2].toDoubleOrNull() ?: continue
            val z = tokens[3].toDoubleOrNull() ?: continue
            result[id] = doubleArrayOf(x, y, z)
        }
        return result
    }

    // --------------------------------------------------------- stats & fp16

    private fun median(values: List<Double>): Double = percentile(values, 0.5)

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p.coerceIn(0.0, 1.0)).roundToInt()
        return sorted[idx]
    }

    private val halfLookup: ShortArray = ShortArray(256) { floatToHalf(it / 255f) }

    private fun floatToHalf(value: Float): Short {
        val bits = java.lang.Float.floatToIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        val exponent = (bits ushr 23) and 0xff
        var mantissa = bits and 0x7fffff
        if (exponent == 0xff) return (sign or 0x7c00 or (if (mantissa != 0) 0x200 else 0)).toShort()
        var e = exponent - 127 + 15
        if (e >= 0x1f) return (sign or 0x7c00).toShort()
        if (e <= 0) {
            if (e < -10) return sign.toShort()
            mantissa = (mantissa or 0x800000) ushr (1 - e)
            return (sign or (mantissa ushr 13)).toShort()
        }
        mantissa = (mantissa + 0x1000) ushr 13
        return (sign or (e shl 10) or mantissa).toShort()
    }

    private fun halfToFloat(bits: Int): Float {
        val sign = if (bits and 0x8000 != 0) -1f else 1f
        val exponent = (bits ushr 10) and 0x1f
        val mantissa = bits and 0x3ff
        return sign * when {
            exponent == 0 -> mantissa.toFloat() * 5.9604645e-8f
            exponent == 0x1f -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> (1f + mantissa.toFloat() / 1024f) * Math.pow(2.0, (exponent - 15).toDouble()).toFloat()
        }
    }
}
