package com.rawmechatronics.easyscanner

import java.nio.ByteBuffer

/**
 * JNI surface of the Rust core (`core/android/src/lib.rs`). Every call for one
 * handle must come from the same thread — [FrameProcessor]'s processing thread.
 */
internal object NativeLib {
    init {
        System.loadLibrary("easyscanner_core_raw")
        System.loadLibrary("openvins_jni")
    }

    @JvmStatic external fun nativeVioSetCaps(maxTracks: Int, minDist: Float)
    @JvmStatic external fun nativeVioSetReacq(enabled: Int)
    @JvmStatic external fun nativeVioCreate(configDir: String): Long
    @JvmStatic external fun nativeVioDestroy(handle: Long)
    @JvmStatic external fun nativeVioSetFeatures(handle: Long, camId: Int, timestampSec: Double, kp: FloatArray, desc: FloatArray, n: Int)
    @JvmStatic external fun nativeVioFeedImu(handle: Long, ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float, timestampSec: Double)
    @JvmStatic external fun nativeVioFeedFrame(handle: Long, gray: ByteArray, width: Int, height: Int, timestampSec: Double): Boolean
    @JvmStatic external fun nativeVioInitialized(handle: Long): Boolean
    @JvmStatic external fun nativeVioInitReadyToMove(handle: Long): Boolean
    @JvmStatic external fun nativeVioGetPose(handle: Long, position: DoubleArray, quaternion: DoubleArray): Boolean

    @JvmStatic external fun nativeCreate(filesDir: String): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeConfigure(handle: Long, json: String)
    @JvmStatic external fun nativeCommand(handle: Long, code: Int, arg: String?): String
    @JvmStatic external fun nativeMessages(handle: Long): String

    /** Process one YUV frame with an external (ARCore) camera pose + intrinsics. */
    @JvmStatic external fun nativeProcessAr(
        handle: Long,
        y: ByteBuffer, yStride: Int,
        u: ByteBuffer, v: ByteBuffer, uvStride: Int, uvPixelStride: Int,
        width: Int, height: Int, timestampNs: Long,
        pose: DoubleArray, intrinsics: DoubleArray, upSign: Double,
        out: FloatArray,
    ): Int

    /**
     * Process one YUV_420_888 frame in place ([timestampNs]: its sensor
     * timestamp). Returns the number of floats written to [out], or `-needed`
     * when [out] is too small.
     */
    @JvmStatic external fun nativeProcess(
        handle: Long,
        y: ByteBuffer, yStride: Int,
        u: ByteBuffer, v: ByteBuffer, uvStride: Int, uvPixelStride: Int,
        width: Int, height: Int, timestampNs: Long,
        out: FloatArray,
    ): Int
}

/** Engine commands; codes mirror `engine::cmd`. */
enum class Cmd(val code: Int) {
    CALIB_START(1), CALIB_STOP(2), CALIB_CAPTURE(3), CALIB_RESET(4),
    PLANE_START(5), PLANE_STOP(6), PLANE_FORCE(7),
    SCAN_START(8), SCAN_STOP(9), CLOUD_CLEAR(10), EXPORT_PLY(11),
    FORGET_INTRINSICS(12), FORGET_PLANE(13),
    PHOTOS_START(14), PHOTOS_STOP(15), PHOTO_SNAP(16), PHOTO_ADD(17), PHOTO_FAILED(18),
    CHECKPOINT(19), PHOTOS_OFF(20), AR_MODE(21),
}
