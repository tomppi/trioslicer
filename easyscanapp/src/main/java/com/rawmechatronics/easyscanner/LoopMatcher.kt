package com.rawmechatronics.easyscanner

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Loop-closure matcher: the same 100-keypoint LightGlue model the VIO tracker
 * uses, exposed for keyframe-to-keyframe loop verification. Runs on the phone's
 * CPU-only, separate from the VIO NPU engine so patch matching never blocks
 * or reinitialises VIO's matcher.
 */
object LoopMatcher {
    private const val TAG = "LoopMatcher"

    @Volatile private var ready = false

    fun init(ctx: Context): Boolean {
        if (ready) return true
        return try {
            val model = AssetCopy.ensure(ctx, "lighterglue.tflite", File(ctx.filesDir, "lighterglue-loop.tflite"))
            val rc = NativeLib.nativeLoopLightGlueInit(ctx.applicationInfo.nativeLibraryDir, model.absolutePath)
            ready = rc == 0 && NativeLib.nativeLoopLightGlueReady()
            Log.i(TAG, "init rc=" + rc + " ready=" + ready)
            ready
        } catch (t: Throwable) {
            Log.e(TAG, "init failed", t)
            ready = false
            false
        }
    }

    fun isReady(): Boolean = ready

    /** Returns [a0,b0,a1,b1,...] pairs into the two keypoint arrays, or null. */
    fun match(kp0: FloatArray, desc0: FloatArray, kp1: FloatArray, desc1: FloatArray): IntArray? {
        if (!ready) return null
        return try {
            NativeLib.nativeLoopLightGlueMatch(kp0, desc0, kp1, desc1)
        } catch (t: Throwable) {
            Log.e(TAG, "match failed", t)
            null
        }
    }
}
