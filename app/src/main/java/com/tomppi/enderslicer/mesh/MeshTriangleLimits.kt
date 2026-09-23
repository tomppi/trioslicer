package com.tomppi.enderslicer.mesh

import android.content.Context
import java.util.Locale

object MeshTriangleLimits {
    data class Preset(
        val name: String,
        val triangles: Int,
        val description: String,
    )

    const val MIN_TRIANGLES = 100_000
    const val DEFAULT_TRIANGLES = 1_500_000
    const val MAX_TRIANGLES = 8_000_000

    val presets = listOf(
        Preset("Compatible", 1_500_000, "Conservative limit for most Android devices"),
        Preset("High detail", 3_000_000, "Suitable for modern devices with ample memory"),
        Preset("Very high detail", 5_000_000, "Recommended option for a 12 GB phone"),
        Preset("Extreme", 8_000_000, "Experimental; very large meshes may still exhaust the app or WebView heap"),
    )

    @Volatile
    private var activeLimit = DEFAULT_TRIANGLES

    fun initialize(context: Context): Int {
        val stored = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_MESH_TRIANGLES, DEFAULT_TRIANGLES)
        activeLimit = sanitize(stored)
        return activeLimit
    }

    fun current(): Int = activeLimit

    fun save(context: Context, triangles: Int): Int {
        val sanitized = sanitize(triangles)
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MAX_MESH_TRIANGLES, sanitized)
            .apply()
        activeLimit = sanitized
        return sanitized
    }

    fun sanitize(triangles: Int): Int = triangles.coerceIn(MIN_TRIANGLES, MAX_TRIANGLES)

    fun binaryStlBytes(triangles: Int): Long = STL_HEADER_BYTES + sanitize(triangles).toLong() * STL_TRIANGLE_BYTES

    /**
     * STL import is streamed from disk, so this is a storage/abuse guard rather
     * than a heap allocation. The multiplier leaves room for verbose ASCII STL.
     */
    fun maxInputFileBytes(triangles: Int): Long = maxOf(
        LEGACY_MAX_INPUT_BYTES,
        binaryStlBytes(triangles) * ASCII_FILE_ALLOWANCE_MULTIPLIER,
    )

    fun parsedMeshBytes(triangles: Int): Long = sanitize(triangles).toLong() * FLOATS_PER_TRIANGLE * Float.SIZE_BYTES

    /** Rough peak guidance, not a reservation or a hard Android memory prediction. */
    fun estimatedWorkingSetBytes(triangles: Int): Long =
        parsedMeshBytes(triangles) * ESTIMATED_SIMULTANEOUS_MESH_COPIES + binaryStlBytes(triangles) * 2L

    fun formatCount(triangles: Int): String = formatCount(triangles.toLong())

    fun formatCount(triangles: Long): String = String.format(Locale.US, "%,d", triangles)

    fun formatBytes(bytes: Long): String = when {
        bytes >= GIB -> String.format(Locale.US, "%.2f GiB", bytes / GIB.toDouble())
        bytes >= MIB -> String.format(Locale.US, "%.0f MiB", bytes / MIB.toDouble())
        bytes >= KIB -> String.format(Locale.US, "%.0f KiB", bytes / KIB.toDouble())
        else -> "$bytes bytes"
    }

    private const val PREFERENCES_NAME = "enderslicer-state"
    private const val KEY_MAX_MESH_TRIANGLES = "max-mesh-triangles"
    private const val STL_HEADER_BYTES = 84L
    private const val STL_TRIANGLE_BYTES = 50L
    private const val FLOATS_PER_TRIANGLE = 18L
    private const val ESTIMATED_SIMULTANEOUS_MESH_COPIES = 3L
    private const val ASCII_FILE_ALLOWANCE_MULTIPLIER = 4L
    private const val KIB = 1024L
    private const val MIB = 1024L * KIB
    private const val GIB = 1024L * MIB
    private const val LEGACY_MAX_INPUT_BYTES = 160L * MIB
}
