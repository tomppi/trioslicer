package com.rawmechatronics.imucalib

import android.util.Log
import com.rawmechatronics.imucalib.core.PinholeModel
import com.rawmechatronics.imucalib.core.PixelBatchEvaluator

/**
 * GPU implementation of the calibrator's pixel residual/Jacobian batch, backed by
 * an OpenCL kernel (see src/main/cpp/gpu_pixel.cpp). Falls back to the CPU path
 * when the device or app namespace does not expose OpenCL.
 */
class GpuPixelBatch(libDir: String? = null, private val logFile: java.io.File? = null) : PixelBatchEvaluator {
    private var calls = 0
    val status: String
    val available: Boolean

    init {
        status = try {
            nativeInit(libDir ?: "") ?: "native init returned null"
        } catch (e: Throwable) {
            "load failed: " + (e.message ?: e.javaClass.simpleName)
        }
        available = status.startsWith("ok:")
        Log.i(TAG, "GPU_OPENCL status=" + status)
    }

    override fun evaluate(
        coeff: DoubleArray,
        base: IntArray,
        basis: DoubleArray,
        xyz: DoubleArray,
        uv: DoubleArray,
        nObs: Int,
        localCount: Int,
        camera: PinholeModel,
        sigma: Double,
    ): DoubleArray {
        val cam = doubleArrayOf(
            camera.fx, camera.fy, camera.cx, camera.cy,
            camera.k1, camera.k2, camera.p1, camera.p2, sigma,
        )
        val t0 = System.nanoTime()
        val out = try {
            nativeEvaluate(coeff, base, basis, xyz, uv, nObs, localCount, cam, sigma)
        } catch (e: Throwable) {
            log("call " + calls + " n=" + nObs + " threw " + e)
            throw e
        } ?: run {
            log("call " + calls + " n=" + nObs + " returned null (native: " + (runCatching { nativeStatus() }.getOrNull() ?: "?") + ")")
            throw IllegalStateException("GPU pixel batch failed: " + status)
        }
        val ms = (System.nanoTime() - t0) / 1e6
        if (calls < 4 || calls % 10 == 0) {
            log(String.format(java.util.Locale.US, "call %d n=%d ms=%.2f", calls, nObs, ms))
        }
        calls++
        return out
    }

    private fun log(msg: String) {
        try { logFile?.appendText(msg + "\n") } catch (_: Throwable) {}
    }

    private external fun nativeInit(libDir: String): String?
    private external fun nativeStatus(): String?
    private external fun nativeEvaluate(
        coeff: DoubleArray,
        base: IntArray,
        basis: DoubleArray,
        xyz: DoubleArray,
        uv: DoubleArray,
        nObs: Int,
        localCount: Int,
        camera: DoubleArray,
        sigma: Double,
    ): DoubleArray?

    companion object {
        private const val TAG = "ImuCalibGpu"
        init {
            System.loadLibrary("gpu_pixel")
        }
    }
}
