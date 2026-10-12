package com.tomppi.enderslicer.viewer

import android.app.ActivityManager
import android.content.Context
import kotlin.math.max
import kotlin.math.min

/**
 * How much of the nozzle path the preview may put in the vertex buffers, and the sentence that
 * says where the number came from.
 *
 * The geometry lives in GPU buffers now, not in the Java heap, so the old fixed budgets
 * (400,000 Cura / 1,000,000 Prusa moves) are no longer what the phone can take: a device with
 * several GB free can hold the whole path. The capacity is therefore derived from the device,
 * the low-memory profile keeps the old conservative figures for when the device says it is
 * small or the user asks for it, and a driver that refuses the allocation makes the preview
 * halve its band until something fits (see RibbonVboStore and the renderers).
 *
 * Nothing here may make a preview lossier: the move budget only decides how much folding
 * happens, and folding never drops a move.
 */
data class PreviewCapacity(
    val profile: PreviewMemoryProfile,
    /** Parse budget: above this many source moves the parser folds. */
    val moves: Int,
    /** Ribbon windows the GPU buffers may hold. */
    val windows: Int,
    /** Travel lines the GPU buffers may hold. */
    val travels: Int,
    /**
     * Layer-view budget, in stored layer paths. The layer preview keeps every layer's paths in
     * the Java heap (six floats, 24 bytes, against the path view's ten), so the same heap share
     * buys five thirds as many of them; the geometry built from them is one layer's worth.
     */
    val segments: Int = PreviewMemoryPolicy.LOW_MEMORY_SEGMENTS,
    /** Where the number came from, for the panel and the log. */
    val note: String,
) {
    companion object {
        /** What the preview holds before anything has probed the device. */
        val Default = PreviewCapacity(
            profile = PreviewMemoryProfile.LOW_MEMORY,
            moves = 400_000,
            windows = RibbonBand.MAX_WINDOWS,
            travels = RibbonBand.MAX_TRAVELS,
            segments = PreviewMemoryPolicy.LOW_MEMORY_SEGMENTS,
            note = "default profile",
        )
    }
}

/** What the user asked for. AUTO follows the device. */
enum class PreviewMemoryProfile {
    AUTO,
    UNLOCKED,
    LOW_MEMORY,
}

/** How the profile is named where the user chooses it. */
val PreviewMemoryProfile.label: String
    get() = when (this) {
        PreviewMemoryProfile.AUTO -> "Auto"
        PreviewMemoryProfile.UNLOCKED -> "Unlocked"
        PreviewMemoryProfile.LOW_MEMORY -> "Low memory"
    }

/** The next profile a tap moves to: auto, unlocked, low memory, auto. */
fun PreviewMemoryProfile.next(): PreviewMemoryProfile = when (this) {
    PreviewMemoryProfile.AUTO -> PreviewMemoryProfile.UNLOCKED
    PreviewMemoryProfile.UNLOCKED -> PreviewMemoryProfile.LOW_MEMORY
    PreviewMemoryProfile.LOW_MEMORY -> PreviewMemoryProfile.AUTO
}

/** The device facts the capacity comes from: ActivityManager on a phone, a fake in a test. */
internal data class PreviewDeviceMemory(
    val totalBytes: Long,
    val availableBytes: Long,
    val lowRamDevice: Boolean,
    val memoryClassMb: Int,
    val largeMemoryClassMb: Int,
)

/** Derives the preview's capacity from a device profile. Pure, so a fake device can be tested. */
internal object PreviewMemoryPolicy {
    /** The band the low-memory profile holds: the conservative figure that shipped before. */
    const val LOW_MEMORY_WINDOWS = 200_000
    const val LOW_MEMORY_TRAVELS = 200_000

    /** The layer view's conservative figure, the segment cap that shipped before. */
    const val LOW_MEMORY_SEGMENTS = 800_000

    /** What one stored layer path costs the Java heap (six floats). */
    const val LAYER_SEGMENT_BYTES = 24L

    /** Never ask for more than this, whatever the RAM says: 4,000,000 windows are 3.2 GB. */
    const val MAX_WINDOWS = 4_000_000

    /** One ribbon window in the vertex buffers, in bytes. */
    const val WINDOW_BYTES = 792L

    /** The share of free RAM the unlocked preview may ask the GPU for. */
    const val FREE_RAM_SHARE = 0.5

    /** What one stored move costs the Java heap (ten floats). */
    const val MOVE_BYTES = 40L

    /** The share of the app's heap class the stored path may take. */
    const val HEAP_SHARE = 0.25

    fun resolve(
        profile: PreviewMemoryProfile,
        device: PreviewDeviceMemory,
        conservativeMoves: Int,
        conservativeSegments: Int = LOW_MEMORY_SEGMENTS,
    ): PreviewCapacity {
        if (profile == PreviewMemoryProfile.LOW_MEMORY ||
            (profile == PreviewMemoryProfile.AUTO && device.lowRamDevice)
        ) {
            return PreviewCapacity(
                profile = PreviewMemoryProfile.LOW_MEMORY,
                moves = conservativeMoves,
                windows = LOW_MEMORY_WINDOWS,
                travels = LOW_MEMORY_TRAVELS,
                segments = conservativeSegments,
                note = "low-memory profile",
            )
        }
        val free = device.availableBytes.coerceAtLeast(0L)
        val gpuBytes = (free * FREE_RAM_SHARE).toLong()
        val windows = (gpuBytes / WINDOW_BYTES)
            .coerceIn(LOW_MEMORY_WINDOWS.toLong(), MAX_WINDOWS.toLong())
            .toInt()
        val heapBytes = max(device.largeMemoryClassMb, device.memoryClassMb).toLong() * 1024L * 1024L
        val heapShare = (heapBytes * HEAP_SHARE).toLong()
        val heapMoves = (heapShare / MOVE_BYTES)
            .coerceAtLeast(conservativeMoves.toLong())
            .toInt()
        val label = if (profile == PreviewMemoryProfile.AUTO) "auto" else "unlocked"
        return PreviewCapacity(
            profile = if (profile == PreviewMemoryProfile.AUTO) PreviewMemoryProfile.AUTO else PreviewMemoryProfile.UNLOCKED,
            moves = min(windows, heapMoves),
            windows = windows,
            // A travel line is 56 bytes against a window's 792, so one line per window is a
            // seventh of the ribbon's cost and never the binding budget.
            travels = windows,
            segments = (heapShare / LAYER_SEGMENT_BYTES)
                .coerceAtLeast(conservativeSegments.toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
            note = label + " profile · " + gigabytes(free) + " free",
        )
    }

    /**
     * The layer preview's path budget for this device and the profile the user has stored. Read
     * fresh at parse time, because the storage is built once and the geometry is rebuilt per
     * layer from it.
     */
    fun layerSegments(context: Context): Int {
        val profile = runCatching {
            PreviewMemoryProfile.valueOf(
                com.tomppi.enderslicer.data.AppStateStore(context).restorePreviewMemoryProfile(),
            )
        }.getOrDefault(PreviewMemoryProfile.AUTO)
        return resolve(profile, probePreviewDeviceMemory(context), DEFAULT_CONSERVATIVE_MOVES).segments
    }

    /** The move figure the conservative profiles keep; the parsers own their own defaults. */
    private const val DEFAULT_CONSERVATIVE_MOVES = 400_000

    private fun gigabytes(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f GB", bytes.toDouble() / 1_000_000_000.0)
}

/**
 * The band sizes a driver refusal walks down, and the sentence when nothing fits.
 *
 * Graphics memory is not free RAM: a driver may refuse an allocation the RAM figures said was
 * fine. The preview asks for the whole band first and halves on refusal, remembering what
 * fitted for the session, and only a floor that is refused too ends in a named failure.
 */
internal object BandAllocator {
    /**
     * The window counts to try, from [asked] (capped by [ceiling]) down to [floor], each one
     * half the previous. The floor is always tried, and is never larger than the start.
     */
    fun attempts(asked: Int, floor: Int, ceiling: Int): List<Int> {
        val start = min(asked.coerceAtLeast(floor), max(ceiling, floor))
        val sizes = ArrayList<Int>()
        var size = start
        while (true) {
            sizes.add(size)
            if (size <= floor) break
            size = max(floor, size / 2)
        }
        return sizes
    }

    /** The travel lines that come with [granted] of the [asked] windows, never none. */
    fun travels(travels: Int, asked: Int, granted: Int): Int =
        if (asked <= 0) travels else max(1024, (travels.toLong() * granted / asked).toInt())

    /** What the preview says when the driver refused even the smallest band. */
    fun refusal(asked: Int, floor: Int): String =
        "The device refused the preview's vertex buffers: it refused this path's " + asked +
            " windows and every band down to " + floor
}

/** Reads the device facts the capacity is derived from. */
internal fun probePreviewDeviceMemory(context: Context): PreviewDeviceMemory {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(info)
    val available = if (info.availMem > 0) info.availMem else info.totalMem
    return PreviewDeviceMemory(
        totalBytes = info.totalMem,
        availableBytes = available,
        lowRamDevice = activityManager.isLowRamDevice,
        memoryClassMb = activityManager.memoryClass,
        largeMemoryClassMb = activityManager.largeMemoryClass,
    )
}
