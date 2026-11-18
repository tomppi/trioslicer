package com.rawmechatronics.easyscanner

import android.media.ImageReader

import java.nio.ByteBuffer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * The processing thread. Owns the native engine handle; camera frames,
 * configuration and commands all run here, in order, so the engine needs no
 * locks. Results go to the UI through a [TripleBuffer]: the camera never
 * waits for drawing and drawing never waits for the camera.
 */
class FrameProcessor(filesDir: String, private val listener: Listener) : ImageReader.OnImageAvailableListener {

    interface Listener {
        /** A new result is in [results] (called on the processing thread). */
        fun onResult(r: FrameResult)

        /** Status lines changed (JSON from the core), on the main thread. */
        fun onMessages(json: String)

        /** The core wants a photo keyframe now (called on the processing thread). */
        fun onPhotoDue()

        fun onError(msg: String)
    }

    private val thread = HandlerThread("es-proc", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    val results = TripleBuffer { FrameResult() }
    /** Optional offline tracker development recorder (Settings -> Development). */
    @Volatile var trackerRecorder: TrackerRecorder? = null
    @Volatile var vio: VioRunner? = null
    /** When true the engine takes its camera pose from the VIO instead of the board. */
    @Volatile var useVioPose = false

    private val vioPose = DoubleArray(16)
    private val vioK = DoubleArray(11)
    private var handle = 0L
    private var loggedW = 0
    private var loggedH = 0

    init {
        handler.post {
            try {
                handle = NativeLib.nativeCreate(filesDir)
            } catch (e: RuntimeException) {
                report("native core failed to start: ${e.message}")
            }
        }
    }

    fun configure(json: String) = handler.post {
        if (handle == 0L) return@post
        try {
            NativeLib.nativeConfigure(handle, json)
            pullMessages()
        } catch (e: RuntimeException) {
            report(e.message ?: "configure failed")
        }
    }

    fun command(cmd: Cmd, arg: String? = null, done: (String) -> Unit = {}) = handler.post {
        if (handle == 0L) return@post
        val msg = try {
            NativeLib.nativeCommand(handle, cmd.code, arg)
        } catch (e: RuntimeException) {
            e.message ?: "command failed"
        }
        pullMessages()
        main.post { done(msg) }
    }

    override fun onImageAvailable(reader: ImageReader) {
        val img = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null // reader closed by a resolution switch
        } ?: return
        try {
            if (handle == 0L) return
            val p = img.planes
            if (img.width != loggedW || img.height != loggedH) {
                loggedW = img.width
                loggedH = img.height
                Log.i(TAG, "frames ${img.width}x${img.height}: y stride ${p[0].rowStride}, " +
                    "uv stride ${p[1].rowStride} pixel stride ${p[1].pixelStride}")
            }
            val out = results.writeBuffer()
            val t0 = System.nanoTime()
            var arPose: DoubleArray? = null
            val vr = vio
            if (vr != null && vr.tracking) {
                try {
                    val yDup = p[0].buffer.duplicate()
                    yDup.rewind()
                    val yBytes = ByteArray(yDup.remaining())
                    yDup.get(yBytes)
                    val ts = img.timestamp * 1e-9
                    val inited = vr.submitFrame(yBytes, p[0].rowStride, img.width, img.height, ts)
                    if (inited && useVioPose && vr.cameraPose(vioPose)) {
                        val sc = img.width / 4080.0
                        vioK[0] = 2755.14624023 * sc
                        vioK[1] = 2752.69726562 * sc
                        vioK[2] = 2048.50415039 * sc
                        vioK[3] = 1543.37402344 * sc
                        vioK[4] = img.width.toDouble()
                        vioK[5] = img.height.toDouble()
                        for (i in 6..10) vioK[i] = 0.0
                        arPose = vioPose
                    }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "VIO frame failed", e)
                }
            }
            var n = if (arPose != null) NativeLib.nativeProcessAr(
                handle,
                p[0].buffer, p[0].rowStride,
                p[1].buffer, p[2].buffer, p[1].rowStride, p[1].pixelStride,
                img.width, img.height, img.timestamp, arPose, vioK, 1.0, out.data,
            ) else NativeLib.nativeProcess(
                handle,
                p[0].buffer, p[0].rowStride,
                p[1].buffer, p[2].buffer, p[1].rowStride, p[1].pixelStride,
                img.width, img.height, img.timestamp, out.data,
            )
            out.procMs = (System.nanoTime() - t0) / 1e6
            if (n < 0) {
                // the frame was processed but its layers did not fit: grow for next time
                out.data = FloatArray(-n * 2)
                n = 0
            }
            out.length = n
            out.timestampNs = img.timestamp
            if (n == 0) return
            vio?.let { v ->
                var px: FloatArray? = null
                out.forEachLayer { kind, stride, count, offset ->
                    if (kind == FrameResult.Layer.LASER_RAW && stride == 2 && count > 0) {
                        px = FloatArray(count * 2)
                        System.arraycopy(out.data, offset, px!!, 0, count * 2)
                    }
                }
                val lp = px
                if (lp != null) v.setLaserPixels(lp, img.width, img.height)
            }
            val ev = out.events()
            val vioPoseToRecord = if (vio?.initialized == true) {
                val vp = DoubleArray(16)
                if (vio?.cameraPose(vp) == true) vp else null
            } else null
            trackerRecorder?.capture(img, out, out.procMs, vioPoseToRecord)
            results.publish()
            if (ev and FrameResult.Ev.PHOTO_DUE != 0) listener.onPhotoDue()
            listener.onResult(out)
            if (ev and FrameResult.Ev.MESSAGES != 0) pullMessages()
        } catch (e: RuntimeException) {
            Log.e(TAG, "frame failed", e)
            report(e.message ?: "frame failed")
        } finally {
            img.close()
        }
    }

    private fun pullMessages() {
        val json = try {
            NativeLib.nativeMessages(handle)
        } catch (e: RuntimeException) {
            return
        }
        main.post { listener.onMessages(json) }
    }

    private fun report(msg: String) = main.post { listener.onError(msg) }

    /** Process an ARCore camera frame with its pose and intrinsics, on the processing thread. */
    fun processArFrame(
        y: ByteBuffer, yStride: Int,
        u: ByteBuffer, v: ByteBuffer, uvStride: Int, uvPixelStride: Int,
        width: Int, height: Int, timestampNs: Long,
        pose: DoubleArray, intrinsics: DoubleArray, upSign: Double,
    ) = handler.post {
        if (handle == 0L) return@post
        try {
            val out = results.writeBuffer()
            val t0 = System.nanoTime()
            var n = NativeLib.nativeProcessAr(
                handle, y, yStride, u, v, uvStride, uvPixelStride,
                width, height, timestampNs, pose, intrinsics, upSign, out.data,
            )
            out.procMs = (System.nanoTime() - t0) / 1e6
            if (n < 0) {
                out.data = FloatArray(-n * 2)
                n = 0
            }
            out.length = n
            out.timestampNs = timestampNs
            if (n == 0) return@post
            results.publish()
            listener.onResult(out)
            if (out.events() and FrameResult.Ev.MESSAGES != 0) pullMessages()
        } catch (e: RuntimeException) {
            Log.e(TAG, "AR frame failed", e)
            report(e.message ?: "AR frame failed")
        }
    }

    fun release() {
        handler.post {
            if (handle != 0L) NativeLib.nativeDestroy(handle)
            handle = 0L
        }
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "EasyScanner"
    }
}
