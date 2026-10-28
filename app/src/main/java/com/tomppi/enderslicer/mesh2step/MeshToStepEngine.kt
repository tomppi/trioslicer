package com.tomppi.enderslicer.mesh2step

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tomppi.enderslicer.engine.AssetTreeExtractor
import com.tomppi.enderslicer.nativebridge.CadEngine
import com.tomppi.enderslicer.nativebridge.KlipperPayload
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Mesh-Mix-S2C ("Manifold Mesh Mix") on the phone: mesh to editable STEP.
 *
 * The converter is the bundled Python engine in assets/mesh2step (upstream v1.6.0 plus
 * TrioSlicer's launcher and OCP 7.9 compatibility shim). It runs on the app's only CPython -
 * the same Klipper payload interpreter CadEngine uses - with the existing CAD payload's
 * OCP/numpy/scipy on PYTHONPATH. Nothing is rebuilt and no second interpreter is added.
 *
 * The class owns one conversion at a time: convert is blocking (call it from a background
 * dispatcher), reports the engine's own phase strings and a monotonic fraction through
 * onProgress, and throws ConversionException with a sentence meant for the user when there is
 * no STEP file. A conversion that produced a STEP file with advisory findings returns normally
 * and lists them in Result.warnings. cancel is safe from any thread; close is idempotent and
 * safe from ViewModel.onCleared.
 */
class MeshToStepEngine(private val context: Context) : Closeable {

    /** The engine's preset choices; AUTO lets it decide from the mesh. */
    enum class Preset(internal val engineArgument: String) {
        AUTO("auto"),
        CAD("cad"),
        SMOOTH("smooth"),
        PRECISE("precise"),
        EXACT("exact"),
    }

    /**
     * One conversion request. Only sourceStl is required; the defaults are the app's
     * recommendation for a mechanical part.
     *
     * quality: 0 = fastest, 1 = best quality; passed to the engine as --quality.
     * unit: the mesh's units (mm, cm, m, in, um, ft); STEP output is in millimetres.
     * tolerance: optional absolute fit tolerance in the mesh's units.
     * timeoutMillis: wall-clock bound; the process is killed when it expires.
     * onLog: optional engine log lines (delivered on the main thread, like progress).
     */
    data class Request(
        val sourceStl: File,
        val preset: Preset = Preset.AUTO,
        val quality: Float = 0.7f,
        val unit: String = "mm",
        val tolerance: Double? = null,
        val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        val onLog: ((String) -> Unit)? = null,
    )

    /** One progress update: the engine's own phase string and a monotonic 0..1 fraction. */
    data class Progress(val phase: String, val fraction: Float)

    /** The STEP file the engine wrote into the app's cache, plus its report. */
    data class Result(
        val step: File,
        val preset: String,
        val seconds: Double,
        val fileSize: Long,
        val faces: Int,
        val solids: Int,
        val valid: Boolean,
        val warnings: List<String>,
        val log: List<String>,
        val shells: Int = 0,
        val edges: Int = 0,
        val volume: Double = 0.0,
        val inputTriangles: Int = 0,
    )

    /**
     * No STEP file. The message is written for the user; kind lets the UI distinguish a
     * cancellation or timeout from a conversion failure without parsing the sentence.
     */
    class ConversionException(
        message: String,
        val kind: Kind = Kind.FAILED,
        cause: Throwable? = null,
    ) : Exception(message, cause) {

        enum class Kind {
            ENGINE_MISSING,
            PAYLOAD_MISSING,
            BUSY,
            IO,
            PROCESS_DIED,
            TIMEOUT,
            CANCELLED,
            OUTPUT_MISSING,
            ENGINE_ERROR,
            FAILED,
        }
    }

    private val conversionLock = Any()
    private val mainHandler: Handler? = runCatching {
        Looper.getMainLooper()?.let { Handler(it) }
    }.getOrNull()

    @Volatile
    private var preparing = false

    @Volatile
    private var runner: MeshToStepRunner? = null

    @Volatile
    private var closed = false

    /** True while a conversion (including its preparation) is in flight. */
    val isRunning: Boolean
        get() = synchronized(conversionLock) { preparing || runner != null }

    /**
     * Convert one STL to STEP. Blocking; call from a background dispatcher.
     *
     * Throws ConversionException when there is no STEP file; the run directory and any partial
     * output are deleted in that case. On success the file lives under the app cache in a
     * mesh2step run directory and is the caller's to copy before close().
     */
    fun convert(request: Request, onProgress: ((Progress) -> Unit)? = null): Result {
        if (closed) {
            throw ConversionException(
                "the mesh-to-STEP converter has been closed",
                ConversionException.Kind.IO,
            )
        }
        synchronized(conversionLock) {
            if (preparing || runner != null) {
                throw ConversionException(
                    "a mesh-to-STEP conversion is already running",
                    ConversionException.Kind.BUSY,
                )
            }
            preparing = true
        }
        try {
            return convertPrepared(request, onProgress, request.onLog)
        } finally {
            synchronized(conversionLock) { preparing = false }
            pruneRunDirectories()
        }
    }

    /** Stops the running conversion if there is one; safe from any thread and twice. */
    fun cancel() {
        synchronized(conversionLock) { runner?.cancel() }
    }

    /**
     * Cancel and remove the cache. Idempotent; after this convert refuses with a named
     * exception. Safe from ViewModel.onCleared.
     */
    override fun close() {
        closed = true
        cancel()
        runCatching { cacheRoot().deleteRecursively() }
    }

    // ---------------------------------------------------------------- conversion

    private fun convertPrepared(
        request: Request,
        onProgress: ((Progress) -> Unit)?,
        onLog: ((String) -> Unit)?,
    ): Result {
        val input = request.sourceStl
        if (!input.isFile || !input.canRead()) {
            throw ConversionException(
                "the mesh file could not be read: " + input.name,
                ConversionException.Kind.IO,
            )
        }
        if (request.unit !in MeshToStepProtocol.UNITS) {
            throw ConversionException(
                "the mesh unit is not supported: " + request.unit,
                ConversionException.Kind.IO,
            )
        }
        val interpreter = File(context.applicationInfo.nativeLibraryDir, "libklipper_exec.so")
        if (!interpreter.isFile) {
            throw ConversionException(
                "the mesh-to-STEP converter's Python interpreter is not packaged in this app",
                ConversionException.Kind.ENGINE_MISSING,
            )
        }

        report(onProgress, Progress("Preparing the converter", 0f))
        val engineDirectory = ensureEngineAssets()
        val python = ensurePythonPayload(onProgress)

        val runDirectory = createRunDirectory()
        var keep = false
        var active: MeshToStepRunner? = null
        try {
            val output = File(runDirectory, MeshToStepProtocol.outputName(input))
            val command = ArrayList<String>(16)
            command += interpreter.absolutePath
            command += File(engineDirectory, LAUNCHER).absolutePath
            command += MeshToStepProtocol.convertArguments(request, input, output)
            val environment = launchEnvironment(engineDirectory, python, runDirectory)

            val engineLog = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            var lastFraction = 0f
            val progress: (MeshToStepProtocol.Event) -> Unit = { event ->
                when (event) {
                    is MeshToStepProtocol.Event.Stage -> {
                        lastFraction = maxOf(lastFraction, event.fraction)
                        report(onProgress, Progress(event.stage, lastFraction))
                    }
                    is MeshToStepProtocol.Event.Progress -> {
                        lastFraction = maxOf(lastFraction, event.fraction)
                        report(onProgress, Progress(event.stage, lastFraction))
                    }
                    is MeshToStepProtocol.Event.Log -> {
                        appendCapped(engineLog, event.message)
                        dispatch { onLog?.invoke(event.message) }
                    }
                    is MeshToStepProtocol.Event.Warning -> {
                        appendCapped(engineLog, "warning: " + event.message)
                        appendCapped(warnings, event.message)
                        dispatch { onLog?.invoke(event.message) }
                    }
                    else -> {}
                }
            }

            active = MeshToStepRunner(
                command = command,
                workingDirectory = engineDirectory,
                environment = environment,
                timeoutMillis = request.timeoutMillis.coerceAtLeast(MINIMUM_TIMEOUT_MILLIS),
            )
            synchronized(conversionLock) { runner = active }
            Log.i(TAG, "converting " + input.name + " with preset " + request.preset)

            val outcome = active.run(progress)
            if (outcome.cancelled) {
                throw ConversionException(
                    "the mesh-to-STEP conversion was cancelled",
                    ConversionException.Kind.CANCELLED,
                )
            }
            if (outcome.timedOut) {
                throw ConversionException(
                    "the mesh-to-STEP conversion did not finish within " +
                        durationText(request.timeoutMillis) +
                        " (last step: " + outcome.lastPhase + "); lower the quality or try the CAD preset",
                    ConversionException.Kind.TIMEOUT,
                )
            }
            outcome.error?.let { error ->
                throw ConversionException(
                    "the mesh-to-STEP conversion failed: " + MeshToStepProtocol.errorSentence(error),
                    ConversionException.Kind.ENGINE_ERROR,
                )
            }
            val resultEvent = outcome.result ?: throw ConversionException(
                noResultSentence(outcome),
                ConversionException.Kind.PROCESS_DIED,
            )
            val reported = resultEvent.optString("output")
            val step = File(reported.ifBlank { output.absolutePath })
            if (!step.isFile || step.length() == 0L) {
                throw ConversionException(
                    "the mesh-to-STEP converter reported success but did not write a STEP file",
                    ConversionException.Kind.OUTPUT_MISSING,
                )
            }

            val allWarnings = (warnings + MeshToStepProtocol.warnings(resultEvent)).distinct()
            val result = Result(
                step = step,
                preset = resultEvent.optString("preset"),
                seconds = resultEvent.optDouble("seconds", Double.NaN),
                fileSize = if (resultEvent.has("file_size")) resultEvent.optLong("file_size") else step.length(),
                faces = resultEvent.optInt("faces", 0),
                solids = resultEvent.optInt("solids", 0),
                valid = resultEvent.optBoolean("valid", false),
                warnings = allWarnings,
                log = (engineLog + outcome.log).distinct().takeLast(LOG_KEEP),
                shells = resultEvent.optInt("shells", 0),
                edges = resultEvent.optInt("edges", 0),
                volume = resultEvent.optDouble("volume", 0.0),
                inputTriangles = resultEvent.optJSONObject("input")?.optInt("input_triangles", 0) ?: 0,
            )
            keep = true
            return result
        } catch (error: ConversionException) {
            throw error
        } catch (error: Exception) {
            throw ConversionException(
                "the mesh-to-STEP conversion failed unexpectedly: " +
                    (error.message ?: error.javaClass.simpleName),
                ConversionException.Kind.FAILED,
                error,
            )
        } finally {
            synchronized(conversionLock) { if (runner === active) runner = null }
            runCatching { active?.close() }
            if (!keep) runCatching { runDirectory.deleteRecursively() }
        }
    }

    // ---------------------------------------------------------------- preparation

    /**
     * Copies assets/mesh2step into files/mesh2step for this install.
     *
     * The stamp is the package's lastUpdateTime, so an install replaces stale engine code and
     * a re-run of the same install is a cheap no-op. The tree is small (about 0.6 MB of Python)
     * and a missing sentinel is reported as a packaging failure rather than as a Python error.
     */
    private fun ensureEngineAssets(): File {
        val destination = File(context.filesDir, ENGINE_DIR)
        val stamp = File(context.filesDir, STAMP)
        val marker = updateMarker()
        if (stamp.isFile &&
            runCatching { stamp.readText() }.getOrNull() == marker &&
            engineAssetsComplete(destination)
        ) {
            return destination
        }
        if (destination.exists() && !destination.deleteRecursively()) {
            throw ConversionException(
                "the mesh-to-STEP converter files could not be replaced",
                ConversionException.Kind.IO,
            )
        }
        if (!destination.mkdirs() && !destination.isDirectory) {
            throw ConversionException(
                "the mesh-to-STEP converter files could not be unpacked",
                ConversionException.Kind.IO,
            )
        }
        try {
            AssetTreeExtractor.copyTree(
                assets = context.assets,
                from = ENGINE_ASSET_DIR,
                destination = destination,
            )
        } catch (error: Exception) {
            throw ConversionException(
                "the mesh-to-STEP converter files could not be unpacked: " +
                    (error.message ?: error.javaClass.simpleName),
                ConversionException.Kind.IO,
                error,
            )
        }
        if (!engineAssetsComplete(destination)) {
            throw ConversionException(
                "the mesh-to-STEP converter files are not packaged in this APK",
                ConversionException.Kind.ENGINE_MISSING,
            )
        }
        runCatching { stamp.writeText(marker) }
        return destination
    }

    private fun engineAssetsComplete(directory: File): Boolean =
        ENGINE_SENTINELS.all { File(directory, it).isFile } &&
            runCatching {
                directory.walkTopDown().count { it.isFile } >= MINIMUM_ENGINE_FILES
            }.getOrDefault(false)

    private fun updateMarker(): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrNull() ?: "0"

    /**
     * Makes sure the app's CPython and the CAD payload that owns OCP/numpy/scipy are on disk.
     *
     * Both are extracted by their existing owners (KlipperPayload and CadEngine); this class
     * only borrows them, so a tree another screen already prepared is found through the same
     * stamps and costs nothing here.
     */
    private fun ensurePythonPayload(onProgress: ((Progress) -> Unit)?): PythonPayload {
        val home = KlipperPayload.root(context)
        val cadSite = CadEngine.sitePackages(context)
        val payloadSite = File(home, "lib/python3.11/site-packages")
        if (!KlipperPayload.pythonDirectory(context).isDirectory || !cadSite.isDirectory) {
            report(onProgress, Progress("Preparing the Python CAD payload", 0f))
            val error = CadEngine.ensureExtracted(context) { extraction ->
                report(
                    onProgress,
                    Progress("Preparing the Python CAD payload (" + extraction.files + " files)", 0f),
                )
            }
            if (error != null) {
                throw ConversionException(
                    "the app's Python CAD payload is not ready, so the mesh-to-STEP converter " +
                        "cannot start: " + error,
                    ConversionException.Kind.PAYLOAD_MISSING,
                )
            }
        }
        return PythonPayload(home, cadSite, payloadSite)
    }

    private data class PythonPayload(
        val home: File,
        val cadSite: File,
        val payloadSite: File,
    )

    private fun launchEnvironment(
        engineDirectory: File,
        python: PythonPayload,
        runDirectory: File,
    ): Map<String, String> {
        val nativeLibraryDirectory = context.applicationInfo.nativeLibraryDir
        val cadLibexec = File(context.filesDir, "cad/libexec")
        val temporary = File(runDirectory, "tmp").apply { mkdirs() }
        return mapOf(
            "PYTHONHOME" to python.home.absolutePath,
            "PYTHONPATH" to (
                engineDirectory.absolutePath + ":" + python.cadSite.absolutePath + ":" +
                    python.payloadSite.absolutePath
                ),
            "LD_LIBRARY_PATH" to (
                File(python.home, "libexec").absolutePath + ":" + cadLibexec.absolutePath + ":" +
                    nativeLibraryDirectory
                ),
            "PYTHONDONTWRITEBYTECODE" to "1",
            "PYTHONUNBUFFERED" to "1",
            "TMPDIR" to temporary.absolutePath,
            "HOME" to runDirectory.absolutePath,
        )
    }

    private fun createRunDirectory(): File {
        val root = cacheRoot().apply { mkdirs() }
        val directory = File(root, UUID.randomUUID().toString())
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw ConversionException(
                "the converter's working directory could not be created",
                ConversionException.Kind.IO,
            )
        }
        return directory
    }

    private fun cacheRoot(): File = File(context.cacheDir, ENGINE_DIR)

    private fun pruneRunDirectories() {
        runCatching {
            val root = cacheRoot()
            val runs = root.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            runs.drop(KEEP_RUNS).forEach { it.deleteRecursively() }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun report(onProgress: ((Progress) -> Unit)?, progress: Progress) {
        if (onProgress == null) return
        dispatch { onProgress(progress) }
    }

    /** Delivers on the main thread when there is one, so the UI can update state directly. */
    private fun dispatch(block: () -> Unit) {
        val handler = mainHandler
        if (handler != null && Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(block)
        } else {
            block()
        }
    }

    private fun appendCapped(target: MutableList<String>, line: String) {
        if (target.size < LOG_KEEP) target.add(line)
    }

    private fun noResultSentence(outcome: MeshToStepRunner.Outcome): String {
        val detail = outcome.log.lastOrNull { it.isNotBlank() }
            ?.take(200)
            ?.replace('\n', ' ')
        val base = if (outcome.exitCode == 0) {
            "the mesh-to-STEP converter exited without reporting a result"
        } else {
            "the mesh-to-STEP converter stopped unexpectedly (exit code " + outcome.exitCode + ")"
        }
        return base + " (last step: " + outcome.lastPhase + ")" + (detail?.let { ": " + it } ?: "")
    }

    private fun durationText(millis: Long): String =
        if (millis < 60_000L) {
            (millis / 1000L).coerceAtLeast(1L).toString() + "s"
        } else {
            (millis / 60_000L).toString() + " min"
        }

    private companion object {
        const val TAG = "MeshToStep"
        const val ENGINE_ASSET_DIR = "mesh2step"
        const val ENGINE_DIR = "mesh2step"
        const val STAMP = "mesh2step.extracted"
        const val LAUNCHER = "launcher.py"
        const val MINIMUM_ENGINE_FILES = 26
        const val KEEP_RUNS = 4
        const val LOG_KEEP = 200
        const val MINIMUM_TIMEOUT_MILLIS = 5_000L

        /** Wall-clock bound for one conversion; Request.timeoutMillis overrides it. */
        const val DEFAULT_TIMEOUT_MILLIS = 15L * 60L * 1000L

        val ENGINE_SENTINELS = listOf(
            "launcher.py",
            "ocp_compat.py",
            "mmm_engine.py",
            "VERSION",
            "mmm/__init__.py",
            "mmm/pipeline.py",
            "mmm/stepio.py",
            "mmm/cli.py",
        )
    }
}
