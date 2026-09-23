package com.tomppi.enderslicer.smartinfill

import android.util.Log

/**
 * JNI bridge to the native filaSim engine (libfilasim_jni.so, arm64-v8a).
 *
 * The engine is built from the same pinned upstream source the bundled
 * WebAssembly workspace was built from, so a package produced here is the
 * package the WebView produced — see native/filasim/README.md.
 *
 * Two handles: the session owns the mesh, grid, boundary conditions and
 * results, and is used by one thread at a time; the control handle carries only
 * the cancellation flag and the progress snapshot, so the UI thread can poll
 * and cancel while a solve or optimize runs on a worker.
 */
internal object FilaSimNative {
    private const val TAG = "FilaSimNative"
    private const val LIBRARY = "filasim_jni"

    @Volatile
    private var loadState = 0

    @Volatile
    private var loaded = false

    /** Loads the engine library once; safe from any thread. */
    fun ensureLoaded(): Boolean {
        if (loadState != 0) return loaded
        synchronized(this) {
            if (loadState != 0) return loaded
            loadState = 1
            try {
                System.loadLibrary(LIBRARY)
                loaded = true
            } catch (error: UnsatisfiedLinkError) {
                loadState = 2
                Log.w(
                    TAG,
                    "libfilasim_jni.so unavailable in this ABI; native Smart Infill disabled (" +
                        error.message + ")",
                )
            }
        }
        return loaded
    }

    /** False when the engine library is not packaged for this ABI. */
    val isAvailable: Boolean get() = loadState != 0 && loaded

    external fun nativeVersion(): String

    external fun createSession(bytes: ByteArray, name: String): Long

    external fun createControl(session: Long): Long

    external fun destroySession(session: Long)

    external fun destroyControl(control: Long)

    external fun sessionInfo(session: Long): String

    external fun configuration(session: Long): String

    external fun originalPositions(session: Long): FloatArray

    external fun patchOfOriginalTriangle(session: Long): IntArray

    external fun originalTrianglesOfPatch(session: Long, patch: Int): IntArray

    external fun regionAround(session: Long, triangle: Int, radiusMm: Double): IntArray

    external fun surfaceBins(session: Long): IntArray

    external fun patchCount(session: Long): Int

    external fun clearBoundaryConditions(session: Long)

    external fun addBoundaryCondition(session: Long, json: String): Int

    external fun configure(session: Long, json: String)

    external fun checkSetup(session: Long): String

    external fun solve(session: Long): String

    external fun optimize(session: Long, options: String): String

    external fun progress(control: Long): String

    external fun cancel(control: Long)

    external fun regionCount(session: Long): Int

    external fun regionDensity(session: Long, index: Int): Double

    external fun regionPositions(session: Long, index: Int): FloatArray

    external fun regionIndices(session: Long, index: Int): IntArray

    external fun resultSummary(session: Long): String

    external fun isSolidMode(session: Long): Boolean

    external fun exportModifierZip(session: Long): ByteArray

    external fun exportSolidStl(session: Long): ByteArray
}
