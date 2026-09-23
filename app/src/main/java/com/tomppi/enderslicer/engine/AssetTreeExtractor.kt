package com.tomppi.enderslicer.engine

import android.content.res.AssetManager
import java.io.File

/**
 * Recursive "asset tree -> files dir" copier shared by engine resource
 * installers (PrusaSlicer presets, Blender python/scripts). Files are written
 * next to their parent; empty directories inside the asset tree are tolerated.
 */
internal object AssetTreeExtractor {
    fun copyTree(assets: AssetManager, from: String, destination: File) {
        val children = runCatching { assets.list(from) }.getOrNull() ?: emptyArray()
        if (children.isEmpty()) {
            try {
                assets.open(from).use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
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
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                target.mkdirs()
                copyTree(assets, source, target)
            }
        }
    }
}
