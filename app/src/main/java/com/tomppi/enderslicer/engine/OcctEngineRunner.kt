package com.tomppi.enderslicer.engine

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Runs the bundled OpenCASCADE console for Android.
 *
 * Packaged as "libocct_exec.so" in the APK's arm64-v8a jniLibs and executed directly, like the
 * Cura, PrusaSlicer and Orca engines. This one is not a slicer: it is a CAD kernel, and its job
 * is to turn the formats a slicer cannot read - STEP and IGES, which carry analytic geometry
 * rather than triangles - into a triangle mesh the existing pipeline already handles.
 *
 * That keeps the app's own rule intact: one model format downstream. The 3MF import does the
 * same thing for the same reason.
 *
 * Commands the binary understands (see occt_exec.cpp):
 *   info <in>                              solids/faces/edges, volume, bounding box
 *   convert <in> <out.stl> [deviation]     STEP or IGES -> tessellated STL
 *   export <in.stl> <out.step|out.iges>    mesh -> BREP
 *   boolean <cut|fuse|common> <a> <b> <out.stl>
 *   fillet <r> | chamfer <d> | shell <t> | draft <deg>  <in> <out.stl>
 *   box <x> <y> <z> <out.step>
 */
class OcctEngineRunner(private val context: Context) {

    private val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
    private val executable = File(nativeDirectory, ENGINE_LIBRARY_NAME)

    fun isAvailable(): Boolean = executable.isFile && executable.length() > 0L

    fun status(): String = when {
        !executable.exists() -> "OpenCASCADE is not packaged in this APK"
        !executable.isFile -> "OpenCASCADE package path is invalid"
        executable.length() == 0L -> "OpenCASCADE package is empty"
        else -> "OpenCASCADE ${OCCT_VERSION} ready"
    }

    /**
     * Tessellates [source] into [output] as binary STL.
     *
     * Blocking; call from a background dispatcher. Returns true only when the engine exited 0
     * *and* produced a non-empty file - a zero exit with no output is the failure mode worth
     * guarding, because the engine can report a read problem and still exit cleanly.
     */
    fun convertToStl(
        source: File,
        output: File,
        log: File,
        deviation: Double = DEFAULT_DEVIATION,
        onProgress: (Int) -> Unit = {},
    ): Boolean {
        if (!isAvailable()) return false
        val workingDirectory = output.parentFile ?: return false
        val exitCode = run(
            args = listOf("convert", source.absolutePath, output.absolutePath, deviation.toString()),
            log = log,
            workingDirectory = workingDirectory,
            onProgress = onProgress,
        )
        return exitCode == 0 && output.isFile && output.length() > 0L
    }

    /**
     * Runs any occt_exec command. The engine is a scripted CAD kernel, not a fixed converter:
     * a caller can build a model out of profile files the way a parametric script would -
     * extrude, revolve, boolean, fillet, chamfer, shell, draft, transform, export.
     *
     * Blocking; returns the exit code, or null if it timed out or the engine is absent.
     */
    fun run(
        args: List<String>,
        log: File,
        workingDirectory: File,
        onProgress: (Int) -> Unit = {},
    ): Int? {
        if (!isAvailable()) return null
        workingDirectory.mkdirs()
        val processBuilder = ProcessBuilder(listOf(executable.absolutePath) + args)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] = nativeDirectory.absolutePath
                environment()["TMPDIR"] = workingDirectory.absolutePath
                environment()["HOME"] = context.filesDir.absolutePath
            }
        return OwnedProcessRunner.runStreaming(
            start = processBuilder::start,
            log = log,
            appendToLog = true,
            parseProgress = { line -> PROGRESS_LINE.find(line.trim())?.groupValues?.get(1)?.toIntOrNull() },
            onProgress = onProgress,
            timeout = PROCESS_TIMEOUT_SECONDS,
            unit = TimeUnit.SECONDS,
        )
    }

    /** Profile file -> solid. One "x y" per line; the kernel closes the outline. */
    fun extrude(profile: File, heightMm: Double, output: File, log: File): Boolean =
        run(listOf("extrude", profile.absolutePath, heightMm.toString(), output.absolutePath), log, output.parentFile ?: return false) == 0 &&
            output.isFile && output.length() > 0L

    /** cut | fuse | common */
    fun boolean(op: String, a: File, b: File, output: File, log: File): Boolean =
        run(listOf("boolean", op, a.absolutePath, b.absolutePath, output.absolutePath), log, output.parentFile ?: return false) == 0 &&
            output.isFile && output.length() > 0L

    fun fillet(radiusMm: Double, source: File, output: File, log: File): Boolean =
        run(listOf("fillet", radiusMm.toString(), source.absolutePath, output.absolutePath), log, output.parentFile ?: return false) == 0 &&
            output.isFile && output.length() > 0L

    suspend fun convertToStlAsync(
        source: File,
        output: File,
        log: File,
        deviation: Double = DEFAULT_DEVIATION,
        onProgress: (Int) -> Unit = {},
    ): Boolean = runInterruptible(Dispatchers.IO) {
        convertToStl(source, output, log, deviation, onProgress)
    }

    companion object {
        const val ENGINE_LIBRARY_NAME = "libocct_exec.so"
        const val OCCT_VERSION = "7.6.0"

        /** Linear deflection in mm. 0.05 is finer than a 0.4 mm nozzle can express. */
        const val DEFAULT_DEVIATION = 0.05

        /**
         * OCCT reads these; the slicers do not. Anything here is converted to STL first rather
         * than handed to an engine that would reject it.
         */
        val CAD_EXTENSIONS = setOf("step", "stp", "iges", "igs")

        private const val PROCESS_TIMEOUT_SECONDS = 120L
        private val PROGRESS_LINE = Regex("""^(\d+)\s*=>""")

        fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

        fun isCadFile(name: String): Boolean = extensionOf(name) in CAD_EXTENSIONS
    }
}
