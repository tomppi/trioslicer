package com.tomppi.enderslicer.nativebridge

import android.content.Context
import android.system.Os
import android.util.Log
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
    @Volatile private var watching = false
    @Volatile private var watcher: Thread? = null

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

    fun status(context: Context): String = when {
        !engineExecutable(context).isFile -> "the CAD engine binary is not packaged in this APK"
        !File(context.filesDir, "klipper/lib/python3.11").isDirectory ->
            "the app's Python is not extracted yet"
        !sitePackages(context).isDirectory ->
            "the CAD engine's Python packages are not installed (expected at " +
                "${sitePackages(context).absolutePath})"
        root?.let { File(it, ENGINE_FILE).isFile } != true -> "the CAD engine is not extracted yet"
        // Ahead of "stopped": the engine imports build123d on the way up, which is tens of
        // seconds on a phone, and calling that stopped is both wrong and alarming.
        starting -> "CAD engine starting…"
        running -> "CAD engine ready on port $DEFAULT_PORT"
        else -> "CAD engine stopped"
    }

    /** Where OCP, build123d and their dependencies live, outside the Klipper payload. */
    fun sitePackages(context: Context): File =
        File(context.filesDir, "cad/lib/python3.11/site-packages")

    private fun engineExecutable(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libklipper_exec.so")

    /**
     * Materialize the engine script and its token under `files/cad`.
     *
     * Assets cannot be executed in place, so the script is copied out first. The token is
     * written *before* the process starts, in the same call, because the engine reads it once
     * at startup and refuses to serve if it is missing.
     */
    private fun prepare(context: Context): File? {
        val dir = File(context.filesDir, "cad").apply { mkdirs() }
        if (!extractTree(context, dir)) return null
        if (ensureToken(dir).isNullOrEmpty()) {
            Log.e(TAG, "could not write the engine token; it will refuse to serve")
            return null
        }
        File(dir, PORT_FILE).writeText(DEFAULT_PORT.toString())
        return dir
    }

    /**
     * Copy the engine, its Python packages and its libraries out of the APK.
     *
     * Assets cannot be executed or imported in place, so the whole tree is materialised under
     * `files/cad`. It is ~570 MB across 13,000 files and takes tens of seconds, which is why it
     * is stamped and skipped rather than repeated on every start.
     *
     * Kept separate from the Klipper payload deliberately: that tree is deleted and
     * re-extracted on every update, and this one is large enough that paying for it each
     * install would be worse than paying for it once.
     */
    private fun extractTree(context: Context, home: File): Boolean {
        val stamp = File(home, EXTRACT_STAMP)
        val marker = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrNull() ?: return false

        val present = stamp.isFile && stamp.readText() == marker &&
            File(home, "lib/python3.11/site-packages").isDirectory &&
            File(home, "libexec").isDirectory && File(home, ENGINE_FILE).isFile
        if (present) {
            Log.i(TAG, "CAD tree already extracted for this install")
            return true
        }

        val started = System.currentTimeMillis()
        Log.i(TAG, "extracting the CAD tree to ${home.absolutePath}; this takes a while")
        val ok = runCatching {
            // Only the parts that come from assets are cleared. The token and the status file
            // are this app's, not the APK's, and losing the token on an update would mean
            // regenerating a secret for no reason.
            for (name in listOf("lib", "libexec")) {
                val target = File(home, name)
                if (target.exists() && !target.deleteRecursively()) {
                    throw IOException("could not clear ${target.absolutePath}")
                }
            }
            copyAssetTree(context, "cad/lib", File(home, "lib"))
            copyAssetTree(context, "cad/libexec", File(home, "libexec"))
            copyAssetTree(context, "cad", home, filesOnly = listOf(ENGINE_FILE))
            stamp.writeText(marker)
        }.onFailure {
            Log.e(TAG, "could not extract the CAD tree", it)
        }.isSuccess
        Log.i(TAG, "CAD tree extracted=$ok in ${(System.currentTimeMillis() - started) / 1000}s")
        return ok
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
    ) {
        val input = runCatching { context.assets.open(assetPath) }.getOrNull()
        if (input != null) {
            input.use { stream ->
                target.parentFile?.mkdirs()
                target.outputStream().use { stream.copyTo(it) }
            }
            return
        }
        target.mkdirs()
        if (filesOnly != null) {
            // The engine directory: take the named files and leave the subdirectories to their
            // own calls, so a 13,000-file walk is not repeated to find one script.
            for (name in filesOnly) copyAssetTree(context, "$assetPath/$name", File(target, name))
            return
        }
        for (child in context.assets.list(assetPath).orEmpty()) {
            copyAssetTree(context, "$assetPath/$child", File(target, child))
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
     * `Os.symlink` rather than `java.nio.file.Files`: this is the Android API for it, and the
     * Klipper host links its own copy the same way.
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
                Os.symlink(target.absolutePath, link.absolutePath)
                if (!link.exists()) Log.w(TAG, "$name does not resolve after linking")
            }.onFailure { Log.w(TAG, "could not link $name", it) }
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
        val payload = File(context.filesDir, "klipper")
        if (!File(payload, "lib/python3.11").isDirectory) {
            Log.e(TAG, "the Klipper payload is not extracted yet; the CAD engine has no Python")
            return false
        }
        val home = File(context.filesDir, "cad")
        val sitePackages = File(home, "lib/python3.11/site-packages")
        val libexec = File(home, "libexec")
        linkPythonLibrary(libexec, File(context.applicationInfo.nativeLibraryDir))
        if (!sitePackages.isDirectory) {
            Log.e(TAG, "the CAD dependencies are not installed at ${sitePackages.absolutePath}")
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
                watchExports(context)
            } else {
                Log.e(TAG, "the CAD engine did not report a listening port")
                stop()
            }
            ready
        }.onFailure {
            Log.e(TAG, "could not start the CAD engine", it)
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
            while (watching) {
                val candidates = runCatching {
                    directory.listFiles { file ->
                        file.isFile && file.extension.lowercase() in
                            (EXPORT_EXTENSIONS + RENDER_EXTENSIONS)
                    }?.toList().orEmpty()
                }.getOrDefault(emptyList())
                for (file in candidates) {
                    val signature = signatureOf(file)
                    val isNew = synchronized(stateLock) { delivered.add(signature) }
                    if (!isNew) continue
                    val isRender = file.extension.lowercase() in RENDER_EXTENSIONS
                    Log.i(TAG, (if (isRender) "render ready: " else "export ready: ") +
                        "${file.name} (${file.length()} bytes)")
                    runCatching { if (isRender) onRender?.invoke(file) else onExport?.invoke(file) }
                        .onFailure { Log.e(TAG, "the handler failed for ${file.name}", it) }
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
            if (process?.isAlive != true) return false
            val text = runCatching { statusFile.readText() }.getOrNull()
            if (text != null && text.contains("\"running\": true")) return true
            if (text != null && text.contains("\"running\": false")) {
                // The engine started and gave up - a missing token, or the port taken. It says
                // why in the same file, and the reason is worth having in the log.
                Log.e(TAG, "the CAD engine refused to serve: $text")
                return false
            }
            Thread.sleep(200)
        }
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
