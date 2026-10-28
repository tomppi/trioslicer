package com.tomppi.enderslicer.nativebridge

import android.content.Context
import android.system.Os
import java.nio.file.Files
import android.util.Log
import com.tomppi.enderslicer.engine.AssetTreeExtractor
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled CAD engine: build123d on top of OCP, the official OpenCASCADE
 * Python bindings.
 *
 * This is the third engine in the app to run Python, and it deliberately reuses the
 * interpreter the Klipper host already brings rather than adding another. [KlipperEngineService]
 * owns that interpreter (`libklipper_exec.so`); this class starts the *same* binary with a
 * different script, which is why the engine's dependencies - OCP, build123d, numpy, scipy,
 * scikit-learn, py-lib3mf - are the ones already staged under `files/klipper`.
 *
 * What it does not do: slice. It produces exact analytic geometry (STEP) and tessellated
 * meshes (STL); the slicer engines turn those into G-code.
 *
 * ### Why a separate process
 *
 * The CAD engine holds a scene for the life of a connection and executes arbitrary Python
 * from the agent. Keeping it out of the app process means a modelling script that crashes
 * or exhausts memory takes down only the engine - the app survives to report it. The Blender
 * engine is embedded in-process for its own reasons; this one has no need of that.
 *
 * ### Token
 *
 * The engine refuses to serve without a shared secret, so that a co-installed app cannot
 * reach 127.0.0.1:9877 and run Python as this app's uid. [ensureToken] writes one before the
 * process starts; the engine reads it at startup and checks it on every request, `ping`
 * included. A tokenless engine records `"no MCP token loaded; refusing to serve"` and never
 * opens the port.
 */
object CadEngine {

    private const val TAG = "CadEngine"

    /** The engine binds 9877, not Blender's 9876: both may run at once. */
    const val DEFAULT_PORT = 9877

    private const val ENGINE_ASSET = "cad/cad_mcp_slim.py"
    private const val ENGINE_FILE = "cad_mcp_slim.py"
    private const val TOKEN_FILE = "cad_mcp_token.txt"
    private const val STATUS_FILE = "cad_mcp_status.json"
    private const val PORT_FILE = "cad_mcp_port.txt"

    /** Stamped with the package lastUpdateTime: an update re-extracts, a restart does not. */
    private const val EXTRACT_STAMP = ".extracted"

    /** soname -> the name the app actually ships. See [linkPythonLibrary]. */
    private val SONAME_ALIASES = listOf(
        "libpython3.11.so.1.0" to "libpython3.11.so",
    )

    /**
     * Callbacks the app supplies before [start].
     *
     * [onExport] fires once per settled STEP/STL, already deduped - the engine writes through a
     * temporary name and renames into place, so a file appearing here is a whole file.
     */
    /**
     * Fires once per settled model export.
     *
     * **Every format the engine writes, not just the mesh.** STEP, STP, BREP and STL all
     * arrive here, and only STL is a mesh - the rest carry exact geometry and are not
     * parseable as triangles. A handler that assumes STL fails on the first open of a
     * directory that already holds a STEP, so filter on the extension.
     */
    var onExport: ((File) -> Unit)? = null
        set(value) {
            field = value
            // An export that arrived while nothing was listening is handed over now. The view
            // model that owns this handler is cleared when the activity goes away, and an export
            // claimed with no listener used to be lost for the life of the process: the agent
            // reported the file it had written and the user never saw it. BlenderEngine replays
            // its newest; so does this.
            if (value == null) return
            val newest = synchronized(pendingExports) {
                val sorted = pendingExports.sortedBy { it.lastModified() }
                pendingExports.clear()
                sorted.lastOrNull()
            } ?: return
            runCatching { value.invoke(newest) }
                .onFailure { Log.e(TAG, "the handler failed for ${newest.name}", it) }
        }

    /** Exports found before the UI listener attached; replayed by the setter. */
    private val pendingExports = mutableListOf<File>()

    /**
     * Fires once per settled render - the engine's own picture of the model.
     *
     * This is the user's view of the work: the CAD engine has a GL viewer and no window, so
     * it renders offscreen and writes the image through the same atomic handoff it uses for
     * STEP and STL.
     */
    var onRender: ((File) -> Unit)? = null

    @Volatile private var process: Process? = null
    @Volatile private var root: File? = null
    @Volatile private var running = false
    @Volatile private var starting = false

    /** When the current start attempt began, so "starting…" can count the seconds. */
    @Volatile private var startingAt: Long = 0L
    @Volatile private var watching = false
    @Volatile private var watcher: Thread? = null

    /**
     * The extraction in flight, or null.
     *
     * The screen polls [status] once a second, so this is what turns a first use from
     * a still sentence into a number that moves: files, megabytes and the seconds it
     * has been going. Read and written from different threads, hence the volatiles.
     */
    @Volatile private var extraction: Extraction? = null

    /**
     * Why the engine is not up, when the reason is known.
     *
     * Set by a failed start or a failed extraction and cleared by the next attempt.
     * Without it the screen falls back to a dead-end sentence with no exit - which is
     * exactly how a missing Python read as a frozen "extracting" for minutes.
     */
    @Volatile private var failure: String? = null

    /**
     * Serialises everything that writes under files/cad or files/klipper.
     *
     * The CAD menu, a restored screen and the first-init screen can all ask in the same
     * minute, and two copies of the tree would clear each other's directories.
     */
    private val extractLock = Any()

    /**
     * One extraction, with the numbers [status] reports while it runs.
     *
     * [report] is the first-init screen's callback, when the run was started from there;
     * the same counter feeds both, so the row and the caption can never disagree.
     */
    private class Extraction(
        val what: String,
        private val report: ((AssetTreeExtractor.Progress) -> Unit)? = null,
    ) {
        private val progress = AssetTreeExtractor.Progress()
        private val startedAt: Long = System.currentTimeMillis()

        val files: Int get() = progress.files
        val bytes: Long get() = progress.bytes

        fun counted(written: Long) {
            progress.counted(written)
            report?.invoke(progress)
        }

        /** For a copier that reports running totals rather than per-file deltas. */
        fun synced(totalFiles: Int, totalBytes: Long) {
            progress.synced(totalFiles, totalBytes)
            report?.invoke(progress)
        }

        fun sentence(): String {
            val seconds = (System.currentTimeMillis() - startedAt) / 1000
            val megabytes = progress.bytes / (1024.0 * 1024.0)
            return "Extracting " + what + ": " + "%,d".format(progress.files) + " files, " +
                "%.0f".format(megabytes) + " MB so far, " + seconds + "s"
        }
    }

    /** Signatures (path|size|mtime) already handed to the app. */
    private val delivered = mutableSetOf<String>()
    private val stateLock = Any()

    /** How often the export directory is scanned. */
    private const val EXPORT_POLL_MS = 500L

    /** Model files, handed to [onExport]. The .part temporaries the engine writes are not. */
    private val EXPORT_EXTENSIONS = setOf("step", "stp", "stl", "brep")

    /** Renders, handed to [onRender] - they are pictures, not models, and the app does
     *  different things with them: one goes to the slicer, the other to the screen. */
    private val RENDER_EXTENSIONS = setOf("png")

    /** The engine's directory, once [start] has resolved it. */
    fun directory(): File? = root

    /** Where the engine writes finished STEP/STL for the app to pick up. */
    fun exportsDirectory(context: Context): File =
        File(context.filesDir, "cad/exports").apply { mkdirs() }

    fun tokenFile(configDir: File): File = File(configDir, TOKEN_FILE)

    /**
     * The engine's shared secret: generated once, then reused for the install.
     *
     * The same shape as [BlenderEngine.ensureToken] - a UUID with the dashes removed, so 32
     * hex characters - because both engines are checked by the same kind of comparison and
     * there is no reason for them to differ.
     */
    fun ensureToken(configDir: File): String? = runCatching {
        val file = tokenFile(configDir)
        val existing = file.takeIf { it.isFile }?.readText()?.trim()
        if (!existing.isNullOrEmpty()) {
            existing
        } else {
            val created = UUID.randomUUID().toString().replace("-", "")
            file.parentFile?.mkdirs()
            file.writeText(created)
            created
        }
    }.getOrNull()

    /**
     * Whether the engine is up and serving.
     *
     * The yes/no companion to [status], which answers with a sentence for the user. Callers that
     * need a decision must ask this instead of comparing that sentence to a word - they did,
     * against "ready", while the sentence is "CAD engine ready on port 9877", so both the
     * viewport's retry policy and the viewer settings' re-send were dead code.
     */
    fun isReady(context: Context): Boolean =
        engineExecutable(context).isFile &&
            KlipperPayload.pythonDirectory(context).isDirectory &&
            sitePackages(context).isDirectory &&
            root?.let { File(it, ENGINE_FILE).isFile } == true &&
            running

    fun status(context: Context): String {
        // The extraction first, whatever else is true: it is the one state with a number
        // that moves, and it is what a first use is actually doing. A sentence that does
        // not advance while 533 MB is being written is what this screen got wrong.
        extraction?.let { return it.sentence() }
        failure?.let { return it }
        return when {
            // Ahead of "stopped": the engine imports build123d on the way up, which is tens of
            // seconds on a phone, and calling that stopped is both wrong and alarming. The
            // elapsed seconds are there because that import is the one long silence left, and
            // a number that moves is the difference between waiting and looking dead.
            starting -> "CAD engine starting… " +
                ((System.currentTimeMillis() - startingAt) / 1000) + "s"
            running -> "CAD engine ready on port $DEFAULT_PORT"
            // Named as the dependency it is: CAD has no interpreter of its own, so the
            // Klipper payload is not an unrelated engine the user has to discover - opening
            // this screen prepares it, with the extraction progress shown right here.
            !KlipperPayload.pythonDirectory(context).isDirectory ->
                "the CAD engine needs the Klipper payload (the app's only Python); " +
                    "opening CAD extracts it"
            !sitePackages(context).isDirectory ->
                "the CAD engine's Python packages are not installed (expected at " +
                    "${sitePackages(context).absolutePath})"
            root?.let { File(it, ENGINE_FILE).isFile } != true -> "the CAD engine is not extracted yet"
            else -> "CAD engine stopped"
        }
    }

    /**
     * Why the engine will never be up, or null when it is coming up, up, or merely stopped.
     *
     * Only terminal answers belong here. A missing tree is not one of them - the CAD path
     * extracts it - and neither is a missing Python, which the path extracts too; the only
     * failures are one that has already happened (in [failure]) and a binary that is not
     * packaged at all. The viewport uses this to decide between waiting and giving up, so a
     * "not yet" leaking into it would stop the first frame of a healthy first run.
     */
    fun problem(context: Context): String? {
        if (!engineExecutable(context).isFile) {
            return "the CAD engine binary is not packaged in this APK"
        }
        // Work in flight is not a problem; it is the answer.
        if (extraction != null || starting) return null
        return failure
    }

    /** True while something is being unpacked or started, for a spinner that is honest. */
    fun isWorking(): Boolean = extraction != null || starting

    /**
     * Whether the engine's own tree is unpacked for this install.
     *
     * The borrowed Python is deliberately not part of this answer: the first-init screen
     * has a row for it, and folding two trees into one yes/no would hide which of them is
     * missing.
     */
    fun isExtracted(context: Context): Boolean {
        val home = File(context.filesDir, "cad")
        val marker = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrNull() ?: return false
        val stamp = File(home, EXTRACT_STAMP)
        return stamp.isFile && stamp.readText() == marker &&
            File(home, "lib/python3.11/site-packages").isDirectory &&
            File(home, "libexec").isDirectory && File(home, ENGINE_FILE).isFile
    }

    /** Where OCP, build123d and their dependencies live, outside the Klipper payload. */
    fun sitePackages(context: Context): File =
        File(context.filesDir, "cad/lib/python3.11/site-packages")

    /**
     * Re-creates the interpreter's soname aliases after the CAD tree was extracted or rewritten.
     *
     * [ensureExtracted] deletes and rewrites files/cad, so a link made before an extraction is
     * gone afterwards. Anything that borrows this Python without starting the CAD engine (the
     * Mesh-to-STEP converter is the first) must call this after extraction and before starting
     * the interpreter, or the linker cannot find libpython3.11.so.1.0 on a fresh install.
     *
     * Cheap (an unlink and a symlink) and safe to call on every start; the aliases point into
     * the install directory, which changes on every app update.
     *
     * @return true when libpython3.11.so.1.0 is a symlink after the call.
     */
    fun ensurePythonLink(context: Context): Boolean {
        val libexec = File(context.filesDir, "cad/libexec")
        linkPythonLibrary(libexec, File(context.applicationInfo.nativeLibraryDir))
        return Files.isSymbolicLink(File(libexec, "libpython3.11.so.1.0").toPath())
    }

    private fun engineExecutable(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libklipper_exec.so")

    /**
     * Everything the engine needs on disk, without starting it.
     *
     * Null on success, otherwise why it failed. This is the one entry point that both the
     * CAD screen and the first-init screen call, so a tree prepared by one is found by the
     * other through the same stamps.
     *
     * The borrowed Python comes first: it is the smaller tree, the one a failure is most
     * likely to be about, and there is nothing to gain by unpacking 533 MB of OCCT for an
     * interpreter that is not there.
     */
    fun ensureExtracted(
        context: Context,
        onProgress: ((AssetTreeExtractor.Progress) -> Unit)? = null,
    ): String? = synchronized(extractLock) {
        val dir = File(context.filesDir, "cad").apply { mkdirs() }
        val python = ensurePython(context, onProgress)
        if (python != null) {
            failure = python
            return@synchronized python
        }
        val tree = ensureTree(context, dir, onProgress)
        if (tree != null) {
            failure = tree
            return@synchronized tree
        }
        // Resolved here rather than only when the process starts: a prepared-but-not-started
        // engine is still "extracted", and the screen reads this to say so.
        root = dir
        failure = null
        null
    }

    /**
     * The interpreter's own tree: CPython's standard library and klippy's modules,
     * extracted from assets/klipper.
     *
     * It lives outside `files/cad` because it is not the CAD engine's alone - the Klipper
     * host runs on it too - so it keeps Klipper's own stamp and completeness check, and a
     * tree either engine wrote is trusted by the other.
     */
    private fun ensurePython(
        context: Context,
        onProgress: ((AssetTreeExtractor.Progress) -> Unit)? = null,
    ): String? {
        if (KlipperPayload.isExtracted(context)) return null
        val progress = Extraction("the CAD engine's Python (the interpreter it borrows)", onProgress)
        extraction = progress
        Log.i(TAG, "the app's Python is not extracted; extracting the Klipper payload the CAD engine borrows")
        return try {
            KlipperPayload.ensureExtracted(context) { progress.synced(it.files, it.bytes) }
        } finally {
            extraction = null
        }
    }

    private fun prepare(context: Context): File? {
        val dir = File(context.filesDir, "cad").apply { mkdirs() }
        if (ensureExtracted(context) != null) return null
        if (ensureToken(dir).isNullOrEmpty()) {
            Log.e(TAG, "could not write the engine token; it will refuse to serve")
            failure = "the CAD engine's token could not be written"
            return null
        }
        File(dir, PORT_FILE).writeText(DEFAULT_PORT.toString())
        return dir
    }

    /**
     * Copy the engine, its Python packages and its libraries out of the APK.
     *
     * Assets cannot be executed or imported in place, so the whole tree is materialised under
     * `files/cad`. It is 533 MB across 10,716 files and takes seconds to tens of seconds, which
     * is why it is stamped and skipped rather than repeated on every start.
     *
     * Kept separate from the Klipper payload deliberately: that tree is deleted and
     * re-extracted on every update, and this one is large enough that paying for it each
     * install would be worse than paying for it once.
     *
     * @return null when the tree is in place, otherwise why it is not.
     */
    private fun ensureTree(
        context: Context,
        home: File,
        onProgress: ((AssetTreeExtractor.Progress) -> Unit)? = null,
    ): String? {
        val stamp = File(home, EXTRACT_STAMP)
        val marker = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrNull() ?: return "the app's package information could not be read"

        val present = stamp.isFile && stamp.readText() == marker && isExtracted(context)
        if (present) {
            Log.i(TAG, "CAD tree already extracted for this install")
            return null
        }

        val started = System.currentTimeMillis()
        Log.i(TAG, "extracting the CAD tree to ${home.absolutePath}; this takes a while")
        val progress = Extraction("the CAD engine's files (OCCT and build123d)", onProgress)
        extraction = progress
        try {
            val written = runCatching {
                // Only the parts that come from assets are cleared. The token and the status file
                // are this app's, not the APK's, and losing the token on an update would mean
                // regenerating a secret for no reason.
                for (name in listOf("lib", "libexec")) {
                    val target = File(home, name)
                    if (target.exists() && !target.deleteRecursively()) {
                        throw IOException("could not clear ${target.absolutePath}")
                    }
                }
                copyAssetTree(context, "cad/lib", File(home, "lib"), progress = progress)
                copyAssetTree(context, "cad/libexec", File(home, "libexec"), progress = progress)
                copyAssetTree(context, "cad", home, filesOnly = listOf(ENGINE_FILE), progress = progress)
                stamp.writeText(marker)
            }
            Log.i(
                TAG,
                "CAD tree extracted=" + written.isSuccess + " in " +
                    "${(System.currentTimeMillis() - started) / 1000}s",
            )
            written.exceptionOrNull()?.let {
                Log.e(TAG, "could not extract the CAD tree", it)
                return "the CAD engine's files could not be extracted: " +
                    (it.message ?: it::class.java.simpleName)
            }
            return null
        } finally {
            extraction = null
        }
    }

    /**
     * Recursive asset copy.
     *
     * Whether a path is a file or a directory is decided by trying to open it:
     * [android.content.res.AssetManager.list] returns null for a file rather than an empty
     * array, so inferring from it misreads leaves and ends up opening a directory. The open is
     * the only step allowed to fail quietly - once it has succeeded the copy must not, or a
     * real write error is misread as "this is a directory" and the walk reports success
     * having copied nothing. [KlipperEngineService] learned both of those the hard way.
     */
    private fun copyAssetTree(
        context: Context,
        assetPath: String,
        target: File,
        filesOnly: List<String>? = null,
        progress: Extraction? = null,
    ) {
        val input = runCatching { context.assets.open(assetPath) }.getOrNull()
        if (input != null) {
            input.use { stream ->
                target.parentFile?.mkdirs()
                val written = target.outputStream().use { stream.copyTo(it) }
                progress?.counted(written)
            }
            return
        }
        target.mkdirs()
        if (filesOnly != null) {
            // The engine directory: take the named files and leave the subdirectories to their
            // own calls, so a 10,000-file walk is not repeated to find one script.
            for (name in filesOnly) {
                copyAssetTree(context, "$assetPath/$name", File(target, name), progress = progress)
            }
            return
        }
        for (child in context.assets.list(assetPath).orEmpty()) {
            copyAssetTree(context, "$assetPath/$child", File(target, child), progress = progress)
        }
    }

    /**
     * Create the soname aliases the interpreter and its libraries ask for by name.
     *
     * Two names are needed that an APK cannot ship directly:
     *
     *  - `libpython3.11.so.1.0`. The library is packaged as `libpython3.11.so` but is linked
     *    against by its soname, and Android only extracts `lib*.so` from an APK's lib
     *    directory - a file literally named `.so.1.0` would never be unpacked.
     *
     * A `libz.so` alias pointing at the app's `libzlib.so` was tried and removed: it sits
     * first on LD_LIBRARY_PATH, so it shadowed the *system* zlib - which is newer and
     * exports `crc32_z`, and which `libEGL` needs. The result was that OCP could not be
     * imported at all: `dlopen failed: cannot locate symbol "crc32_z" referenced by
     * /system/lib64/libEGL.so`. The system already provides `libz.so`, so nothing is needed.
     *
     * `Os.symlink` is the Android API for this and the Klipper host links its own copy the same
     * way; `java.nio.file.Files.createSymbolicLink` (the same syscall, API 26+) is the fallback
     * so the link path is exercised by JVM unit tests, where `Os` is not implemented.
     *
     * Both links point into the install directory, which changes on every update, so they are
     * rebuilt on every start rather than left to be discovered as stale. A stale one is worse
     * than an absent one: `exists()` follows it and reports false, `symlink` then fails with
     * EEXIST, and the engine cannot start at all after an update.
     */
    private fun linkPythonLibrary(libexec: File, nativeDir: File) {
        if (!libexec.mkdirs() && !libexec.isDirectory) {
            Log.w(TAG, "could not create ${libexec.absolutePath}")
            return
        }
        for ((name, real) in SONAME_ALIASES) {
            runCatching {
                val target = File(nativeDir, real)
                if (!target.isFile) {
                    Log.w(TAG, "no $real in ${nativeDir.absolutePath}; cannot link $name")
                    return@runCatching
                }
                val link = File(libexec, name)
                // delete() returns false for a link that was never there, which is the same
                // outcome as deleting one - and it removes a stale link that exists() cannot
                // see, because exists() follows it.
                link.delete()
                createSymbolicLink(target, link)
                if (!link.exists()) Log.w(TAG, "$name does not resolve after linking")
            }.onFailure { Log.w(TAG, "could not link $name", it) }
        }
    }

    /** One symlink(2) call; [Os] on Android, java.nio.file.Files where the JVM has no Os. */
    private fun createSymbolicLink(target: File, link: File) {
        runCatching { Os.symlink(target.absolutePath, link.absolutePath) }
        // Robolectric's Os is a no-op rather than an error, so check the result; on Android
        // this only runs if Os did not create the link.
        if (!Files.isSymbolicLink(link.toPath())) {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        }
    }

    /**
     * Start the engine and wait until it is listening.
     *
     * Returns true when the port is open. Blocking: call from a background dispatcher.
     */
    fun start(context: Context): Boolean {
        if (running) return true
        starting = true
        startingAt = System.currentTimeMillis()
        // The previous attempt's reason is history the moment a new one begins: while this
        // one runs, the screen must read the extraction and the start, not the last failure.
        failure = null
        try {
            return startEngine(context)
        } finally {
            // Cleared however it leaves, including by exception: a flag left set would have
            // the screen saying "starting" forever with nothing coming.
            starting = false
        }
    }

    private fun startEngine(context: Context): Boolean {

        val dir = prepare(context) ?: return false
        // The status file describes a process, not a screen. A stale one answers for a launch
        // that has not happened yet: a start that failed left "running": false, and an engine
        // that has since been killed left "running": true. awaitListening() believes either -
        // giving up milliseconds after the launch, or reporting a port nothing is serving -
        // and the first of those bricks the screen until the app's data is cleared. Clear the
        // file first, so what is read is only ever what this process just wrote.
        File(dir, STATUS_FILE).delete()
        val exe = engineExecutable(context)
        if (!exe.isFile) {
            Log.e(TAG, "interpreter missing at ${exe.absolutePath}")
            failure = "the CAD engine binary is not packaged in this APK"
            return false
        }

        // The interpreter is the Klipper payload's - it is the app's only CPython - so
        // PYTHONHOME points at the tree holding lib/python3.11 and libpython3.11.so.
        //
        // The CAD dependencies do NOT live there. That tree is deleted and re-extracted
        // whenever the app is updated (KlipperEngineService.extractPayload compares a
        // marker against the package's lastUpdateTime and clears the tree on a mismatch),
        // so anything put inside it is lost on the next install. OCP and build123d are
        // hundreds of megabytes that took a cross-compile each to produce; they live under
        // files/cad/, which nothing else manages, and reach the interpreter as PYTHONPATH.
        val payload = KlipperPayload.root(context)
        if (!KlipperPayload.pythonDirectory(context).isDirectory) {
            Log.e(TAG, "the Klipper payload is not extracted yet; the CAD engine has no Python")
            failure = "the CAD engine's Python is not extracted and could not be extracted"
            return false
        }
        val home = File(context.filesDir, "cad")
        val sitePackages = File(home, "lib/python3.11/site-packages")
        val libexec = File(home, "libexec")
        ensurePythonLink(context)
        if (!sitePackages.isDirectory) {
            Log.e(TAG, "the CAD dependencies are not installed at ${sitePackages.absolutePath}")
            failure = "the CAD engine's Python packages are not installed"
            return false
        }

        val pb = ProcessBuilder(exe.absolutePath, File(dir, ENGINE_FILE).absolutePath)
        pb.directory(dir)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["PYTHONHOME"] = payload.absolutePath
        // The payload's own site-packages stays on the path - klippy's dependencies are
        // there - and the CAD tree is added in front of it.
        env["PYTHONPATH"] = "${sitePackages.absolutePath}:${payload.absolutePath}/lib/python3.11/site-packages"
        env["LD_LIBRARY_PATH"] =
            "${libexec.absolutePath}:${File(payload, "libexec").absolutePath}:" +
            context.applicationInfo.nativeLibraryDir
        env["PYTHONDONTWRITEBYTECODE"] = "1"

        return runCatching {
            Log.i(TAG, "starting the CAD engine: ${pb.command().joinToString(" ")}")
            val started = pb.start()
            process = started
            root = dir
            drainToLog(started)
            val ready = awaitListening(File(dir, STATUS_FILE), 60_000)
            running = ready
            if (ready) {
                failure = null
                watchExports(context)
            } else {
                Log.e(TAG, "the CAD engine did not report a listening port")
                if (failure == null) {
                    failure = "the CAD engine did not report a listening port within 60s"
                }
                stop()
            }
            ready
        }.onFailure {
            Log.e(TAG, "could not start the CAD engine", it)
            failure = "the CAD engine could not be started: " +
                (it.message ?: it::class.java.simpleName)
        }.getOrDefault(false)
    }

    /**
     * Watch the export directory and tell the app about each new STEP/STL.
     *
     * Polling rather than FileObserver, and unlike [BlenderEngine] there is no observer kept
     * as a latency accelerator: the CAD engine publishes every file through a temporary name
     * and an atomic rename, so a file appearing here is already whole and the poll interval
     * is the only latency there is. Deduped by path + size + mtime, the same signature
     * [BlenderEngine] uses, so a rewrite of the same path re-fires only when it really
     * changed.
     *
     * Safe to call more than once; the second call is a no-op.
     */
    fun watchExports(context: Context) {
        if (watcher != null) return
        val directory = exportsDirectory(context)
        val thread = Thread {
            Log.i(TAG, "watching ${directory.absolutePath} for exports")
            // Everything already here is history from a previous run. [delivered] lives in
            // memory, so without this the first poll hands the app every file the engine ever
            // exported: invisible while an import replaced the model on the plate, because the
            // last one simply won, and not invisible now that each import adds an object.
            // This is the same claim the Blender watcher makes for its own directory.
            runCatching {
                directory.listFiles { file ->
                    file.isFile && !file.name.contains("-part") &&
                        file.extension.lowercase() in (EXPORT_EXTENSIONS + RENDER_EXTENSIONS)
                }?.forEach { file ->
                    synchronized(stateLock) { delivered.add(signatureOf(file)) }
                }
            }
            while (watching) {
                val candidates = runCatching {
                    directory.listFiles { file ->
                        // "-part" is the engine's staging name, and it is only ever a
                        // half-written file on its way to a real one. It is staged a directory
                        // down now, so this is the guard for an engine that predates that - a
                        // 0-byte "viewport-part.png" reported as a render twice a second is
                        // noise the user cannot act on.
                        file.isFile && !file.name.contains("-part") &&
                            file.extension.lowercase() in (EXPORT_EXTENSIONS + RENDER_EXTENSIONS)
                    }?.toList().orEmpty()
                }.getOrDefault(emptyList())
                for (file in candidates) {
                    val signature = signatureOf(file)
                    val isNew = synchronized(stateLock) { delivered.add(signature) }
                    if (!isNew) continue
                    val isRender = file.extension.lowercase() in RENDER_EXTENSIONS
                    Log.i(TAG, (if (isRender) "render ready: " else "export ready: ") +
                        "${file.name} (${file.length()} bytes)")
                    if (isRender) {
                        runCatching { onRender?.invoke(file) }
                            .onFailure { Log.e(TAG, "the handler failed for ${file.name}", it) }
                    } else {
                        // Decided under the same lock the setter drains with, so a listener that
                        // attaches between the read and the queueing cannot miss the file: either
                        // it is attached here and gets it, or it is queued and the setter takes it.
                        val listener = synchronized(pendingExports) {
                            val attached = onExport
                            if (attached == null && pendingExports.none { it.absolutePath == file.absolutePath }) {
                                pendingExports.add(file)
                            }
                            attached
                        }
                        listener?.let { handler ->
                            runCatching { handler(file) }
                                .onFailure { Log.e(TAG, "the handler failed for ${file.name}", it) }
                        }
                    }
                }
                // Forget signatures whose file is gone, so the directory cannot grow a
                // set of names nobody can act on for the life of the process.
                val present = candidates.map(::signatureOf).toSet()
                synchronized(stateLock) { delivered.retainAll(present) }
                Thread.sleep(EXPORT_POLL_MS)
            }
        }.apply { isDaemon = true; name = "cad-export-watch" }
        watcher = thread
        watching = true
        thread.start()
    }

    fun stopWatching() {
        watching = false
        watcher = null
        synchronized(stateLock) { delivered.clear() }
    }

    private fun signatureOf(file: File): String =
        "${file.absolutePath}|${file.length()}|${file.lastModified()}"

    fun stop() {
        stopWatching()
        running = false
        val current = process ?: return
        process = null
        runCatching {
            current.destroy()
            if (!current.waitFor(3, TimeUnit.SECONDS)) {
                current.destroyForcibly()
                current.waitFor(3, TimeUnit.SECONDS)
            }
        }.onFailure { Log.w(TAG, "the CAD engine did not stop cleanly", it) }
    }

    /**
     * Wait for the engine's status file to report a listening port.
     *
     * The status file is written atomically by the engine, so a partial read is not possible.
     * Its absence just means "not yet", which is why this polls rather than reading once.
     */
    private fun awaitListening(statusFile: File, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) {
                failure = "the CAD engine exited before it started serving"
                return false
            }
            val text = runCatching { statusFile.readText() }.getOrNull()
            if (text != null && text.contains("\"running\": true")) return true
            if (text != null && text.contains("\"running\": false")) {
                // The engine started and gave up - a missing token, or the port taken. It says
                // why in the same file, and the reason is worth having in the log and on screen.
                Log.e(TAG, "the CAD engine refused to serve: $text")
                failure = "the CAD engine refused to serve: " +
                    text.replace('\n', ' ').trim().take(160)
                return false
            }
            Thread.sleep(200)
        }
        failure = "the CAD engine did not report a listening port within " +
            "${timeoutMillis / 1000}s"
        return false
    }

    /**
     * Keep the engine's stdout/stderr moving.
     *
     * A child that fills its pipe blocks on the next write, so an unread stream is a hang
     * waiting for enough output. The engine is chatty at import time.
     */
    private fun drainToLog(process: Process) {
        val stream = process.inputStream
        Thread {
            runCatching {
                stream.bufferedReader().forEachLine { line -> Log.i(TAG, line) }
            }
        }.apply { isDaemon = true; name = "cad-engine-log" }.start()
        // A waiter thread rather than Process.onExit(): that is a Java 9 API and Android's
        // java.lang.Process does not carry it.
        Thread {
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            running = false
            Log.i(TAG, "the CAD engine exited with $code")
        }.apply { isDaemon = true; name = "cad-engine-exit" }.start()
    }
}
