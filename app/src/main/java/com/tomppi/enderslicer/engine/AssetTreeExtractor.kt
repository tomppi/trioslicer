package com.tomppi.enderslicer.engine

import android.content.res.AssetManager
import java.io.File

/**
 * Recursive "asset tree -> files dir" copier shared by engine resource
 * installers (PrusaSlicer presets, Blender python/scripts). Files are written
 * next to their parent; empty directories inside the asset tree are tolerated.
 *
 * A copy this size takes real time - the Blender payload is 140 MB over 4,900
 * files and the Orca profiles are 80 MB over 12,800 - so a caller that has a
 * screen can pass [Progress] and [onProgress] and show that it is alive. The
 * callback fires once per file copied; a caller putting it on a UI is expected
 * to throttle, and [Progress.bytes] is what makes the throttle meaningful.
 */
object AssetTreeExtractor {

    /**
     * Files copied and bytes written so far.
     *
     * Volatile: the copying thread writes these and a screen reads them, and the whole
     * point of the numbers is that they move while the work does.
     */
    class Progress {
        @Volatile var files: Int = 0
            private set
        @Volatile var bytes: Long = 0L
            private set

        fun counted(written: Long) {
            files++
            bytes += written
        }

        /** For a copier whose progress this one mirrors, which reports running totals. */
        fun synced(totalFiles: Int, totalBytes: Long) {
            files = totalFiles
            bytes = totalBytes
        }
    }

    fun copyTree(
        assets: AssetManager,
        from: String,
        destination: File,
        progress: Progress? = null,
        onProgress: ((Progress) -> Unit)? = null,
    ) {
        val children = runCatching { assets.list(from) }.getOrNull() ?: emptyArray()
        if (children.isEmpty()) {
            try {
                assets.open(from).use { input ->
                    destination.outputStream().use { output ->
                        val written = input.copyTo(output)
                        progress?.counted(written)
                    }
                }
                progress?.let { onProgress?.invoke(it) }
            } catch (_: Exception) {
                // Empty directories within the asset tree; ignore.
            }
            return
        }
        for (child in children) {
            val source = "$from/$child"
            val target = File(destination, child)
            val nested = runCatching { assets.list(source) }.getOrNull()
            if (nested.isNullOrEmpty()) {
                target.parentFile?.mkdirs()
                assets.open(source).use { input ->
                    target.outputStream().use { output ->
                        val written = input.copyTo(output)
                        progress?.counted(written)
                    }
                }
                progress?.let { onProgress?.invoke(it) }
            } else {
                target.mkdirs()
                copyTree(assets, source, target, progress, onProgress)
            }
        }
    }
}
