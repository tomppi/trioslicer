package com.rawmechatronics.easyscanner

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.abs

/** Factory intrinsics estimate, at the active-array resolution. */
data class IntrinsicsGuess(
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val dist: DoubleArray, val width: Double, val height: Double,
)

/** What the app needs to know about one back camera. */
data class CameraInfo(
    val id: String,
    val label: String,
    val sensorOrientation: Int,
    /** YUV sizes with the sensor's aspect that reach the target fps, largest first. */
    val analysisSizes: List<Size>,
    /** JPEG sizes with exactly the analysis stream's aspect, largest first: photo keyframes. */
    val stillSizes: List<Size>,
    /** Largest preview with the sensor's aspect: the viewfinder's shape. */
    val previewSize: Size,
    /** SurfaceTexture sizes the camera can stream. */
    val textureSizes: List<Size>,
    val guess: IntrinsicsGuess?,
    val manualFocus: Boolean,
    /** Closest focus, diopters. */
    val maxDiopters: Float,
    val manualSensor: Boolean,
    val exposureRangeNs: Range<Long>?,
    val isoRange: Range<Int>?,
    val aeCompRange: Range<Int>,
    val fpsRanges: Array<Range<Int>>,
    val hasOisOff: Boolean,
    val hasDistortionOff: Boolean,
    val hasStabilizationOff: Boolean,
) {
    /**
     * The preview stream next to an [analysis] stream: the same size when the
     * camera can stream it. Some HALs scale only to the largest stream and cut
     * the smaller ones from its top-left corner (Galaxy A23: a 640x480 YUV next
     * to a 1440x1080 preview held 640x480 pixels of the preview), so a larger
     * preview would leave the analysis with a corner of the view.
     */
    fun previewFor(analysis: Size): Size = if (analysis in textureSizes) analysis else previewSize

    companion object {
        fun backCameraIds(mgr: CameraManager): List<String> = mgr.cameraIdList.filter {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
        }

        fun query(mgr: CameraManager, id: String, targetFps: Int, maxLongSide: Int): CameraInfo {
            val c = mgr.getCameraCharacteristics(id)
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
            val aspect = active.width().toDouble() / active.height()
            val frameNs = 1e9 / targetFps
            // same tolerance as Intrinsics::for_frame, which scales K exactly within it
            fun sameAspect(s: Size) = abs(s.width.toDouble() / s.height - aspect) < 0.005 * aspect

            val yuv = map.getOutputSizes(ImageFormat.YUV_420_888).toList()
            // a target fps no size can reach leaves every size in play (the HUD shows the real fps)
            val reachable = yuv.filter {
                it.width * it.height >= 320 * 240 &&
                    map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, it) <= frameNs * 1.02
            }.ifEmpty { yuv }
            val capped = reachable.filter { maxLongSide <= 0 || maxOf(it.width, it.height) <= maxLongSide }
                .ifEmpty { reachable.sortedBy { it.width * it.height }.take(1) }
            // same aspect as the sensor → every stream is the same crop and K scales exactly
            val analysis = capped.filter(::sameAspect).ifEmpty { capped }
                .sortedByDescending { it.width * it.height }

            // exactly the analysis aspect: Camera2 crops every stream to its own
            // aspect, so only an equal one is the same crop and K scales exactly
            val ref = analysis.first()
            val stills = map.getOutputSizes(ImageFormat.JPEG)
                .filter { it.width.toLong() * ref.height == it.height.toLong() * ref.width }
                .sortedByDescending { it.width * it.height }

            val texture = map.getOutputSizes(SurfaceTexture::class.java).toList()
            val preview = texture.filter { sameAspect(it) && maxOf(it.width, it.height) <= 1920 }
                .maxByOrNull { it.width * it.height }
                ?: analysis.first()

            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val label = buildString {
                append("camera ").append(id)
                focal?.let { append(" · f %.2f mm".format(it)) }
                append(" · ").append(active.width()).append('x').append(active.height())
            }
            return CameraInfo(
                id = id,
                label = label,
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                analysisSizes = analysis,
                stillSizes = stills,
                previewSize = preview,
                textureSizes = texture,
                guess = guess(c),
                manualFocus = minFocus > 0f,
                maxDiopters = minFocus,
                manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
                exposureRangeNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                aeCompRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
                fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray(),
                hasOisOff = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF in
                    (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)),
                hasDistortionOff = CameraMetadata.DISTORTION_CORRECTION_MODE_OFF in
                    (c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES) ?: IntArray(0)),
                hasStabilizationOff = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF in
                    (c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)),
            )
        }

        /**
         * The lens calibration the vendor ships, when there is one; otherwise
         * the focal length over the sensor's physical width. Expressed at the
         * active-array resolution (the streams are scaled from it).
         */
        private fun guess(c: CameraCharacteristics): IntrinsicsGuess? {
            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
            val w = active.width().toDouble()
            val h = active.height().toDouble()
            val k = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            if (k != null && k[0] > 0f && k[1] > 0f) {
                val pre = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) ?: active
                val d = c.get(CameraCharacteristics.LENS_DISTORTION)
                // Android orders (k1, k2, k3, p1, p2); keep the radial terms only
                val dist = if (d != null && d.size >= 3) doubleArrayOf(d[0].toDouble(), d[1].toDouble(), 0.0, 0.0, d[2].toDouble())
                else DoubleArray(5)
                val cx = k[2].toDouble() - (active.left - pre.left)
                val cy = k[3].toDouble() - (active.top - pre.top)
                // some HALs fill only the focal length and leave the principal point
                // at 0, 0 (Samsung Galaxy A23): far off-centre means unset, take the centre
                val centred = abs(cx - w / 2) <= 0.08 * w && abs(cy - h / 2) <= 0.08 * h
                return IntrinsicsGuess(
                    k[0].toDouble(), k[1].toDouble(),
                    if (centred) cx else w / 2, if (centred) cy else h / 2,
                    dist, w, h,
                )
            }
            val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
            val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
            val fpx = f / phys.width * pixels.width
            return IntrinsicsGuess(fpx.toDouble(), fpx.toDouble(), w / 2, h / 2, DoubleArray(5), w, h)
        }
    }
}

/** A JPEG still and the sensor timestamp of its exposure. */
class Still(val jpeg: ByteArray, val timestampNs: Long, val width: Int, val height: Int)

/**
 * Camera2 pipeline: a hardware-composited preview plus a YUV ImageReader at
 * the analysis size, both in one session, and, while photo keyframes are on,
 * a JPEG reader for stills. Runs on its own thread; the reader's frames are
 * delivered on the processor's thread. Changing the analysis size or turning
 * stills on/off rebuilds the session, nothing else.
 *
 * A still is captured in one request together with the preview and the
 * analysis stream, so the analysis frame of the same exposure (same sensor
 * timestamp) carries its board pose.
 */
class CameraController(
    private val mgr: CameraManager,
    private val imageHandler: Handler,
    private val imageListener: ImageReader.OnImageAvailableListener,
    private val onError: (String) -> Unit,
    /** The camera could not stream this analysis size (called on the camera thread). */
    private val onSizeFailed: (Size) -> Unit,
    /** A still arrived (called on the still thread). */
    private val onStill: (Still) -> Unit,
    /**
     * A still could not be taken (camera thread); `fatal` = no still stream
     * can be had at all, so asking again is pointless.
     */
    private val onStillFailed: (msg: String, fatal: Boolean) -> Unit,
) {
    private val thread = HandlerThread("es-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val stillThread = HandlerThread("es-still").apply { start() }
    private val stillHandler = Handler(stillThread.looper)
    // after quit() the looper is gone; run late callbacks inline so a device
    // that opens after close still gets closed
    private val executor = Executor { if (!handler.post(it)) it.run() }

    /** Bumped by every open/close; callbacks of an older open are stale. */
    @Volatile private var generation = 0

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var stillReader: ImageReader? = null
    /** Still sizes to try, largest first; empty = no stills. */
    private var stillSizes: List<Size> = emptyList()
    /** A still was asked for while the session was being rebuilt: take it once configured. */
    private var stillWanted = false
    private var texture: SurfaceTexture? = null
    private var preview: Surface? = null
    private var info: CameraInfo? = null
    private var settings: AppSettings? = null
    private var analysisSize: Size? = null

    /** Lens focus of the last frame (diopters), for locking it where autofocus settled. */
    @Volatile var focusDiopters: Float? = null
        private set
    /** Autofocus had settled (or the focus is manual) at [focusDiopters]. */
    @Volatile var focusSettled = false
        private set

    private val frameCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val af = result.get(CaptureResult.CONTROL_AF_STATE)
            focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            focusSettled = request.get(CaptureRequest.CONTROL_AF_MODE) == CameraMetadata.CONTROL_AF_MODE_OFF ||
                af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        }
    }

    @SuppressLint("MissingPermission") // the activity checks CAMERA before opening
    fun open(info: CameraInfo, texture: SurfaceTexture, analysis: Size, settings: AppSettings) = handler.post {
        closeNow()
        val gen = generation
        this.info = info
        this.settings = settings
        this.analysisSize = analysis
        this.texture = texture
        preview = Surface(texture)
        try {
            mgr.openCamera(info.id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (gen != generation) {
                        d.close() // closed (or reopened) while this open was pending
                        return
                    }
                    device = d
                    startSession()
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    if (device === d) device = null
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (device === d) device = null
                    onError("camera error $error")
                }
            })
        } catch (e: Exception) {
            onError("cannot open camera: ${e.message}")
        }
    }

    /** Switch the analysis stream to [size] (rebuilds the session). */
    fun setAnalysisSize(size: Size) = handler.post {
        if (size == analysisSize) return@post
        analysisSize = size
        startSession()
    }

    /**
     * Stream stills at the first of [sizes] the camera accepts next to the
     * analysis stream (largest first), or none for an empty list. Rebuilds
     * the session.
     */
    fun setStills(sizes: List<Size>) = handler.post {
        if (sizes == stillSizes) return@post
        stillSizes = sizes
        stillWanted = false
        startSession()
    }

    /** Take one still with the current focus and exposure. */
    fun captureStill() = handler.post { takeStill() }

    private fun takeStill() {
        if (stillSizes.isEmpty()) {
            onStillFailed("no still stream", false)
            return
        }
        val s = session
        val sr = stillReader
        if (s == null || sr == null) {
            stillWanted = true // the session is being (re)built with the still stream
            return
        }
        try {
            val b = request(CameraDevice.TEMPLATE_STILL_CAPTURE)
            if (b == null) {
                onStillFailed("camera not ready", false)
                return
            }
            b.addTarget(sr.surface)
            // raw sensor orientation: the intrinsics are in it
            b.set(CaptureRequest.JPEG_ORIENTATION, 0)
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    onStillFailed("still capture failed (reason ${failure.reason})", false)
                }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "still capture failed", e)
            onStillFailed("still capture failed: ${e.message}", false)
        }
    }

    /** New focus / exposure / fps settings on the running session. */
    fun applySettings(s: AppSettings) = handler.post {
        settings = s
        session?.let { repeat(it) }
    }

    fun close() = handler.post { closeNow() }

    fun quit() {
        close()
        thread.quitSafely()
        stillThread.quitSafely()
    }

    private fun startSession() {
        val d = device ?: return
        val size = analysisSize ?: return
        val surface = preview ?: return
        val inf = info ?: return
        session?.close()
        session = null
        retireReader()
        inf.previewFor(size).let { texture?.setDefaultBufferSize(it.width, it.height) }
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener(imageListener, imageHandler)
        reader = r
        val stillSize = stillSizes.firstOrNull()
        val sr = stillSize?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.JPEG, 2) }
        sr?.setOnImageAvailableListener(::deliverStill, stillHandler)
        stillReader = sr
        val outputs = listOfNotNull(OutputConfiguration(surface), OutputConfiguration(r.surface),
            sr?.let { OutputConfiguration(it.surface) })
        val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (reader !== r) {
                        s.close()
                        return
                    }
                    session = s
                    repeat(s)
                    if (stillWanted && sr != null) {
                        stillWanted = false
                        takeStill()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (reader !== r) return
                    if (stillSize == null) {
                        onSizeFailed(size)
                        return
                    }
                    // the stills are what the camera refused: one size smaller, else none
                    stillSizes = stillSizes.drop(1)
                    if (stillSizes.isEmpty()) {
                        stillWanted = false
                        onStillFailed("the camera cannot add photos to a ${size.width}x${size.height} analysis stream", true)
                    }
                    startSession()
                }
            })
        try {
            d.createCaptureSession(cfg)
        } catch (e: Exception) {
            onError("cannot configure camera: ${e.message}")
        }
    }

    private fun repeat(s: CameraCaptureSession) {
        try {
            val b = request(CameraDevice.TEMPLATE_RECORD) ?: return
            s.setRepeatingRequest(b.build(), frameCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "repeating request failed", e)
            onError("camera request failed: ${e.message}")
        }
    }

    /** A request to the preview and the analysis stream with the current focus / exposure / fps. */
    private fun request(template: Int): CaptureRequest.Builder? {
        val d = device ?: return null
        val r = reader ?: return null
        val inf = info ?: return null
        val st = settings ?: return null
        return d.createCaptureRequest(template).also { b ->
            preview?.let { b.addTarget(it) }
            b.addTarget(r.surface)
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            pickFpsRange(inf.fpsRanges, st.targetFps)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            // geometry must stay fixed for the calibration to hold
            if (inf.hasStabilizationOff) {
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            if (inf.hasOisOff) {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }
            if (inf.hasDistortionOff) {
                b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
            }
            if (st.manualFocus && inf.manualFocus) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, st.focusDiopters.coerceIn(0f, inf.maxDiopters))
            } else {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            if (st.manualExposure && inf.manualSensor) {
                val exp = (st.exposureUs * 1000L).let { e -> inf.exposureRangeNs?.clamp(e) ?: e }
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, inf.isoRange?.clamp(st.iso) ?: st.iso)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exp, 1_000_000_000L / st.targetFps))
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, inf.aeCompRange.clamp(st.exposureComp))
            }
        }
    }

    private fun deliverStill(r: ImageReader) {
        val img = try {
            r.acquireNextImage()
        } catch (e: IllegalStateException) {
            null // reader closed by a session rebuild
        } ?: return
        try {
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            onStill(Still(bytes, img.timestamp, img.width, img.height))
        } finally {
            img.close()
        }
    }

    /** Close the old readers on their threads, after any image they are still handling. */
    private fun retireReader() {
        stillReader?.let { old ->
            stillReader = null
            old.setOnImageAvailableListener(null, null)
            stillHandler.post { old.close() }
        }
        val old = reader ?: return
        reader = null
        old.setOnImageAvailableListener(null, null)
        imageHandler.post { old.close() }
    }

    private fun closeNow() {
        generation++
        session?.close()
        session = null
        device?.close()
        device = null
        retireReader()
        preview?.release()
        preview = null
        texture = null
    }

    companion object {
        private const val TAG = "EasyScanner"

        /** A steady `[fps, fps]` when offered, else the closest range reaching fps. */
        fun pickFpsRange(ranges: Array<Range<Int>>, fps: Int): Range<Int>? =
            ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
                ?: ranges.filter { it.upper >= fps }.minWithOrNull(compareBy({ it.upper }, { -it.lower }))
                ?: ranges.maxByOrNull { it.upper }
    }
}
