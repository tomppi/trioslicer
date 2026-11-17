package com.rawmechatronics.easyscanner

/** In-process TFLite SuperPoint pair model; the QNN delegate runs it on the NPU when available. */
object SpTflite {
    init { System.loadLibrary("sptflite") }
    external fun create(libDir: String, modelPath: String, fx: Double, fy: Double, cx: Double, cy: Double): Long
    external fun run(handle: Long, gray: ByteArray): FloatArray?
    external fun close(handle: Long)
}
