package com.tomppi.splatproto

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Processing settings chosen for the phone's memory and cores. */
data class Processing(
    val name: String, val featureMaxSide: Int, val maxFeatures: Int, val denseMaxSide: Int, val maxDenseImages: Int,
) {
    fun asOptions(markerSizeMm: Int = 0) = intArrayOf(1, featureMaxSide, maxFeatures, denseMaxSide, maxDenseImages, markerSizeMm)
}

/** What the app found out about the phone, in report order. */
data class DeviceReport(val sections: List<Pair<String, List<Pair<String, String>>>>, val processing: Processing) {
    fun asText(): String = buildString {
        append("Parallax ${BuildConfigCompat.versionName} device report\n")
        for ((title, rows) in sections) {
            append("\n## ").append(title).append('\n')
            for ((k, v) in rows) append("- ").append(k).append(": ").append(v).append('\n')
        }
    }
}

/**
 * Detects the hardware and OS through public APIs only. Root is detected and reported but
 * never used: everything the app needs is reachable without it.
 */
object DeviceProbe {
    @Volatile private var cached: DeviceReport? = null

    fun report(ctx: Context): DeviceReport = cached ?: collect(ctx).also { cached = it }

    /** Identity fields for scan.json; computed once (it spawns getprop). */
    fun identity(): Map<String, String> = identity

    private val identity: Map<String, String> by lazy { mapOf(
        "device" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
        "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "rom" to rom(),
        "soc" to soc(),
    ) }

    fun processing(ctx: Context): Processing {
        val mem = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val gb = mem.totalMem / 1e9
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            // Phones report less than their nominal RAM (a 6 GB phone shows about 5.6 GB).
            gb >= 5.0 && cores >= 8 -> Processing("full", 1600, 4000, 960, 40)
            gb >= 3.0 -> Processing("balanced", 1280, 3000, 800, 30)
            else -> Processing("light", 1024, 2500, 640, 20)
        }
    }

    private fun collect(ctx: Context): DeviceReport {
        val sections = mutableListOf<Pair<String, List<Pair<String, String>>>>()
        sections += "Phone" to listOf(
            "Model" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}",
            "ROM" to rom(),
            "Root" to root(),
        )
        val mem = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val processing = processing(ctx)
        sections += "Compute" to listOf(
            "SoC" to soc(),
            "CPU" to cpuClusters(),
            "ISA" to runCatching { NativeCore.cpuFeatures() }.getOrDefault("?"),
            "RAM" to "%.1f GB".format(mem.totalMem / 1e9),
            "GPU (OpenGL ES)" to glRenderer(),
            "GPU (OpenCL)" to runCatching { NativeCore.openclSummary() }.getOrDefault("?"),
            "Processing profile" to "${processing.name}: features at ${processing.featureMaxSide} px, dense at " +
                "${processing.denseMaxSide} px, up to ${processing.maxDenseImages} dense views",
        )
        sections += "Cameras" to cameras(ctx)
        return DeviceReport(sections, processing)
    }

    // ---- OS -----------------------------------------------------------------------------

    private fun prop(key: String): String = runCatching {
        val p = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(2, TimeUnit.SECONDS)
        out
    }.getOrDefault("")

    fun rom(): String {
        val lineage = prop("ro.lineage.version")
        val name = when {
            lineage.isNotEmpty() -> "LineageOS $lineage"
            prop("ro.build.version.oneui").isNotEmpty() -> "Samsung One UI ${prop("ro.build.version.oneui")}"
            prop("ro.e.version").isNotEmpty() -> "/e/OS ${prop("ro.e.version")}"
            prop("ro.modversion").isNotEmpty() -> prop("ro.modversion")
            else -> Build.DISPLAY
        }
        return "$name (${Build.TYPE}, ${Build.TAGS})"
    }

    /** Presence of an `su` binary; Parallax never runs it. */
    private fun root(): String {
        val paths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system_ext/bin/su", "/su/bin/su",
            "/debug_ramdisk/su", "/data/adb/magisk", "/data/adb/ksu")
        val found = paths.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }
        return if (found != null) "detected ($found), not needed or used" else "not detected (not needed)"
    }

    // ---- Compute ------------------------------------------------------------------------

    fun soc(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL != Build.UNKNOWN) {
            return "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim()
        }
        val platform = prop("ro.board.platform").ifEmpty { Build.HARDWARE }
        return platform
    }

    private fun cpuClusters(): String {
        val n = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until n).map { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }
                .getOrDefault(0L)
        }
        // cpuinfo_max_freq is in kHz; emulators report placeholders, so ignore anything under 100 MHz.
        if (freqs.none { it >= 100_000L }) return "$n cores"
        val groups = freqs.filter { it >= 100_000L }.groupingBy { it }.eachCount().toSortedMap(reverseOrder())
        return "$n cores: " + groups.entries.joinToString(" + ") { (f, c) -> "$c× %.2f GHz".format(f / 1e6) }
    }

    /** GL_RENDERER from a throwaway off-screen OpenGL ES 3 context. */
    private fun glRenderer(): String = runCatching {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(dpy, ver, 0, ver, 1)
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40 /* ES3 */, EGL14.EGL_SURFACE_TYPE,
            EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0)
        val ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
        val s = "${GLES30.glGetString(GLES30.GL_RENDERER)}, ${GLES30.glGetString(GLES30.GL_VERSION)}"
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(dpy, surf)
        EGL14.eglDestroyContext(dpy, ctx)
        s
    }.getOrDefault("?")

    // ---- Cameras ------------------------------------------------------------------------

    private fun level(c: CameraCharacteristics) = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "?"
    }

    private fun cameras(ctx: Context): List<Pair<String, String>> {
        val manager = ctx.getSystemService(CameraManager::class.java)
        val rows = mutableListOf<Pair<String, String>>()
        rows += "Camera IDs" to runCatching { manager.cameraIdList.joinToString() }.getOrDefault("?")
        val lenses = runCatching { CameraProbe.lenses(manager) }.getOrDefault(emptyList())
        for (l in lenses) {
            val c = l.chars
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
            val logical = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
            val maxYuv = l.yuvSizes().maxByOrNull { it.width * it.height }
            val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
            val af = (c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
            val torch = if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val lv = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                    if (lv > 1) "torch ($lv levels)" else "torch"
                } else "torch"
            } else "no flash"
            val parts = listOfNotNull(
                level(c),
                "%.1f mm (%d mm equiv.)".format(l.focalMm, l.equivalentMm.roundToInt()),
                maxYuv?.let { "YUV up to ${it.width}×${it.height}" },
                if (c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) != null) "factory calibration" else "no factory calibration",
                if (l.position != null) "lens position known" else null,
                if (ois) "OIS" else null,
                if (af) "autofocus" else "fixed focus",
                torch,
                if (logical) "logical multi-camera (${c.physicalCameraIds.joinToString()})" else null,
            )
            rows += "Rear ${l.key}" to parts.joinToString(", ")
        }
        val modes = CameraProbe.modes(lenses)
        rows += "Capture modes" to modes.joinToString { m ->
            m.label + if (m is CaptureMode.Rig) " (metric, if the HAL accepts both streams)" else ""
        }.ifEmpty { "none" }
        return rows
    }
}
