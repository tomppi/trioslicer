package com.tomppi.splatproto

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** JNI facade over the C++ core in app/src/main/cpp. */
object NativeCore {
    init {
        System.loadLibrary("opencv_java4")
        System.loadLibrary("parallaxcore")
    }

    fun interface Progress {
        /** Return false to cancel. Called on the reconstruction thread. */
        fun onProgress(stage: String, fraction: Float): Boolean
    }

    external fun buildInfo(): String

    /** ISA extensions reported by the kernel, e.g. "arm64 neon fp16 dotprod ... (ARMv8.2+)". */
    external fun cpuFeatures(): String

    /** The GPU behind the vendor OpenCL driver, or why apps cannot reach it. */
    external fun openclSummary(): String

    external fun trackerReset()

    external fun trackerMarkKeyframe()

    /**
     * Draws the frame into [preview] (rotated clockwise by [rotation]) and, when [track] is set,
     * runs the keyframe tracker. [out] receives
     * `[progress, sharpness, meanY, darkRatio, clipRatio, keyframe, tracked, sharp]`; [pts] receives
     * per point `x, y, anchorX, anchorY` normalised in the sensor frame (anchor = at the last keyframe). Returns the number of points.
     */
    external fun trackFrame(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, width: Int, height: Int,
        yStride: Int, uvStride: Int, uvPixelStride: Int,
        preview: Bitmap?, rotation: Int, track: Boolean, out: FloatArray, pts: FloatArray,
    ): Int

    external fun saveJpeg(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, width: Int, height: Int,
        yStride: Int, uvStride: Int, uvPixelStride: Int, path: String, quality: Int,
    ): Boolean

    /** Runs the whole pipeline; blocks for minutes. Returns the report as JSON. */
    external fun reconstruct(
        cameras: DoubleArray, cameraFlags: IntArray,
        images: Array<String>, imageCams: IntArray, imageRig: IntArray, imageUp: FloatArray,
        /** Per image: time (s), rolling-shutter readout (s), gyro x y z (rad/s, camera axes), has gyro (0/1). */
        imageMotion: DoubleArray,
        outDir: String, imageRoot: String, options: IntArray, progress: Progress,
    ): String
}
