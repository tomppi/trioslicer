package com.tomppi.enderslicer.nativebridge

import android.content.Context
import android.util.Log
import com.tomppi.enderslicer.engine.AssetTreeExtractor
import java.io.File
import java.io.IOException

/**
 * The Klipper host's payload, and the app's only CPython: the 3.11 standard library
 * plus klippy's own modules, unpacked from assets/klipper into files/klipper.
 *
 * One extractor for two engines, deliberately. [KlipperEngineService] runs klippy on
 * this tree, and [CadEngine] borrows it: PYTHONHOME points here because there is no
 * second interpreter in the APK. The copy used to live inside the service, which
 * meant only a printer attach ever paid for it - so a user who opened the CAD screen
 * first watched 533 MB of OCCT extract, and then the engine refused to start because
 * the Python it borrows had never been unpacked. The stamp and the completeness check
 * live here too, so a tree written by one caller is trusted by the other.
 */
internal object KlipperPayload {

    private const val TAG = "KlipperPayload"

    /** Beside the payload rather than inside it; see [ensure]. */
    private const val STAMP = "klipper.extracted"

    /** The interpreter's home. CADEngine points PYTHONHOME at this directory. */
    fun root(context: Context): File = File(context.filesDir, "klipper")

    /** The directory whose presence means "there is a Python here". */
    fun pythonDirectory(context: Context): File = File(root(context), "lib/python3.11")

    fun isExtracted(context: Context): Boolean {
        val stamp = File(context.filesDir, STAMP)
        return stamp.isFile && stamp.readText() == marker(context) && payloadIsComplete(root(context))
    }

    /**
     * Copy assets/klipper into [root] unless the stamp says it is already there.
     *
     * The stamp is written only once the copy has been checked, and a stamp left by
     * an earlier run is only trusted while that check still passes. Writing it
     * unconditionally once turned a failed extraction into a later, unrelated
     * looking error somewhere else entirely.
     *
     * Blocking and [Synchronized]: the Klipper host and the CAD engine can ask in the
     * same frame - a printer attach and the CAD screen, or the first-init screen
     * preparing both - and two copies of this walk would clear each other's tree.
     *
     * @throws IOException when the tree cannot be written or is incomplete after it.
     */
    @Synchronized
    fun ensure(
        context: Context,
        into: File = root(context),
        onProgress: ((AssetTreeExtractor.Progress) -> Unit)? = null,
    ) {
        val stamp = File(context.filesDir, STAMP)
        val marker = marker(context)
        if (stamp.isFile && stamp.readText() == marker && payloadIsComplete(into)) {
            Log.i(TAG, "payload already extracted for this version")
            return
        }
        Log.i(TAG, "extracting klipper payload to ${into.absolutePath}")
        // Failing here rather than carrying on is deliberate: a tree that cannot
        // be cleared cannot be written into either, and every file would fail on
        // its own with a much less obvious error.
        if (into.exists() && !into.deleteRecursively()) {
            throw IOException("could not clear ${into.absolutePath}")
        }
        if (!into.mkdirs() && !into.isDirectory) {
            throw IOException("could not create ${into.absolutePath}")
        }
        AssetTreeExtractor.copyTree(
            assets = context.assets,
            from = "klipper",
            destination = into,
            progress = AssetTreeExtractor.Progress(),
            onProgress = onProgress,
        )
        if (!payloadIsComplete(into)) {
            throw IOException("the Klipper payload is incomplete after extraction")
        }
        stamp.writeText(marker)
        Log.i(TAG, "payload extracted: ${into.walkTopDown().count { it.isFile }} files")
    }

    /**
     * [ensure], reported rather than thrown: null when the payload is there, and
     * otherwise the reason, for a caller that has a screen to put it on.
     */
    fun ensureExtracted(
        context: Context,
        onProgress: ((AssetTreeExtractor.Progress) -> Unit)? = null,
    ): String? = runCatching { ensure(context, onProgress = onProgress) }
        .exceptionOrNull()
        ?.let { error ->
            Log.e(TAG, "could not extract the Klipper payload", error)
            error.message ?: error::class.java.simpleName
        }

    private fun marker(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()

    /** Files whose absence means the payload is not usable. */
    private val SENTINELS = listOf(
        "klippy/klippy.py",
        "klippy/chelper/c_helper.so",
        "klippy/extras/resonance_playback.py",
        "lib/python3.11/encodings/__init__.py",
    )

    /**
     * A tree this size, or the extraction did not finish.
     *
     * The sentinels catch a payload that is wrong; this catches one that is
     * incomplete, which is the shape a copy that half failed leaves behind - and the
     * stamp that says "already extracted" would otherwise freeze it for the life of
     * the installed version.
     */
    private const val MINIMUM_FILES = 800

    private fun payloadIsComplete(root: File): Boolean =
        SENTINELS.all { File(root, it).isFile } && countFiles(root) >= MINIMUM_FILES

    private fun countFiles(root: File): Int {
        val entries = root.listFiles() ?: return 0
        return entries.sumOf { if (it.isDirectory) countFiles(it) else 1 }
    }
}
