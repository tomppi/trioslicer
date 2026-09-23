package com.tomppi.enderslicer.nativebridge

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.tomppi.enderslicer.engine.AssetTreeExtractor
import com.tomppi.enderslicer.viewer.StlParser
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-side owner of the embedded Blender MCP engine (arm64-v8a only).
 *
 * Responsibilities (see BLENDER_MCP_INTEGRATION.md section 3):
 *  - first-run materialization of assets/blender/{python,scripts} to
 *    <filesDir>/blender/ (the engine cannot import assets in place),
 *  - starting the engine through BlenderBridge (background mode, MCP socket,
 *    default port 9876) once, idempotently,
 *  - watching <config>/exports/ for new .stl handoffs and forwarding them to
 *    the UI so the app always shows the latest generated model.
 *
 * Export-watch reliability: FileObserver depends on the framework's shared
 * inotify fd registering a watch. On some devices/kernels that registration
 * silently never happens (the fd carries no watches and no event is ever
 * delivered), so the poller below is the AUTHORITATIVE detector: it scans the
 * exports dir every 500 ms and dispatches each new file revision exactly once
 * (deduplicated by path + size + mtime). FileObserver is kept as a latency
 * accelerator only; the same signature dedupe makes the two paths safe to run
 * in parallel.
 */
object BlenderEngine {
    private const val TAG = "BlenderEngine"
    private const val RESOURCES_VERSION = "blender-3.6-resources-v11"
    const val DEFAULT_MCP_PORT = 9876

    /** Scan cadence of the authoritative exports-dir poller. */
    private const val EXPORT_POLL_MS = 500L

    /** How long to wait for an export to become a whole STL: 40 x 250 ms. */
    private const val SETTLE_PROBES = 40
    private const val SETTLE_PROBE_MS = 250L

    @Volatile private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Kept so the keeper's wake-lock lease can be re-armed while the engine works. */
    @Volatile private var appContext: Context? = null

    // Cancels the previous exports watcher when the engine is restarted:
    // without this every stop/start pair left another poller and another
    // FileObserver running for the life of the process.
    private var watcherJob: Job? = null

    // Held so the inotify watch can be released: the accelerators were locals, so
    // every engine restart left another observer (and its thread) watching a
    // directory the poller already covers.
    private var watcherObserver: FileObserver? = null

    private const val TOKEN_FILE = "blender_mcp_token.txt"

    /** Receives every completed STL handoff. Runs on a background scope. */
    @Volatile var onStlExported: ((File) -> Unit)? = null
        set(value) {
            field = value
            // An export written before the listener attached (typically the
            // AI finished a generation while the UI was still starting) still
            // shows up: replay the newest one.
            val newest = synchronized(pendingExports) {
                val sorted = pendingExports.sortedBy { it.lastModified() }
                pendingExports.clear()
                sorted.lastOrNull()
            } ?: return
            synchronized(stateLock) {
                delivered.add(signatureOf(newest))
            }
            value?.invoke(newest)
        }

    /** Signatures (path|size|mtime) already handed to the UI (or recorded while unattached). */
    private val delivered = mutableSetOf<String>()

    /** Signatures currently being settled; prevents duplicate dispatch. */
    private val inFlight = mutableSetOf<String>()

    /**
     * Bumped by every start and by [shutdown]; a startup that finds a different
     * value after a suspension knows it was stopped and leaves the engine stopped.
     */
    @Volatile private var startGeneration = 0

    /** Guards [delivered] and [inFlight]. */
    private val stateLock = Any()

    /** Exports found before the UI listener attached; replayed by the setter. */
    private val pendingExports = mutableListOf<File>()

    /** Idempotent: boots extraction + engine on first call, no-op afterwards. */
    fun ensureStarted(context: Context) {
        if (started) return
        started = true
        // Startup suspends twice - the one-time resource extraction and the wait for
        // the addon to serve - and a "Stop Blender engine" during either of them used
        // to be undone when the coroutine resumed: it booted the engine after the hop
        // was told the engine was stopped. Each start claims a generation of its own
        // and checks it again after every suspension.
        val generation = ++startGeneration
        val app = context.applicationContext
        appFilesDir = app.filesDir
        appContext = app
        scope.launch {
            runCatching {
                // ensureLoaded() performs the (one-time, background)
                // System.loadLibrary of the 1.3 GB engine so the x86_64
                // emulator build skips cleanly instead of crashing.
                if (!BlenderBridge.ensureLoaded()) {
                    Log.w(TAG, "Blender engine library is not packaged for this ABI; skipping")
                    return@launch
                }
                val configDir = File(app.filesDir, "blender")
                prepareResources(app, configDir)
                if (generation != startGeneration) {
                    Log.i(TAG, "engine was stopped while starting; it stays stopped")
                    return@launch
                }
                File(configDir, "3.6/config/datafiles").mkdirs()
                File(app.filesDir, "blender-home").mkdirs()
                // Tailscale Android runs userspace netstack: inbound TCP to
                // app ports is NOT delivered (verified: SYN to the app socket
                // times out over the tailnet), so binding the tailnet IP only
                // breaks the adb-forward loopback path. Keep localhost.
                val bindHost = "localhost"
                Log.i(TAG, "MCP bind host: " + bindHost)
                // Fail closed. The addon authorizes every request when it finds no
                // token, so starting it without one would leave Python execution as
                // this app's uid open to any co-installed app on the loopback port.
                if (ensureToken(configDir).isNullOrEmpty()) {
                    Log.e(TAG, "no MCP token could be written; refusing to start the Blender engine")
                    started = false
                    return@launch
                }
                if (generation != startGeneration) {
                    Log.i(TAG, "engine was stopped while starting; it stays stopped")
                    return@launch
                }
                val restart = restartFile(configDir)
                restart.delete()
                val parked = BlenderBridge.isRunning()
                BlenderBridge.start(
                    home = app.filesDir.absolutePath + "/blender-home",
                    config = configDir.absolutePath,
                    port = DEFAULT_MCP_PORT,
                    host = bindHost,
                )
                if (parked) {
                    // The thread is already there - either a first boot, or the parked
                    // addon a previous "Stop Blender engine" left behind. Ask it to
                    // serve again instead of trying to start a second Blender.
                    Log.i(TAG, "engine thread already running; asking it to serve")
                    restart.writeText("restart")
                    if (!awaitServing(configDir)) {
                        // The addon could not rebind (its port still held, say).
                        // Re-arm rather than leave the engine silently dead behind a
                        // started=true that nothing ever retries.
                        Log.w(TAG, "engine did not come back; it will be asked again")
                        started = false
                    }
                }
                ensureWatcher(configDir)
            }.onFailure { error ->
                // Re-arm. One transient failure (a full disk during extraction, a
                // start race) used to disable the engine for the life of the
                // process with no way back short of restarting the app.
                Log.e(TAG, "Blender engine startup failed", error)
                started = false
            }
        }
    }

    /**
     * Graceful addon shutdown, called when the main UI is torn down. The
     * engine is also torn down with the process, but this signals the addon
     * to close its socket promptly. Re-arms [ensureStarted] for the next
     * launch in the same process (config-change survival keeps the VM alive;
     * [shutdown] is only reached on explicit finish).
     */
    fun shutdown() {
        startGeneration++
        // The engine runs inside this process, so "stop" can only mean "stop
        // serving": Blender's own teardown calls exit() and would take the app
        // with it. Ask the addon over the socket - the native stop flag it used to
        // set was read by nothing, so this control only appeared to work.
        val stopped = runCatching { requestShutdown() }.getOrDefault(false)
        Log.i(TAG, "engine shutdown requested over the socket: " + stopped)
        BlenderBridge.stop()
        watcherJob?.cancel()
        watcherJob = null
        watcherObserver?.stopWatching()
        watcherObserver = null
        started = false
    }

    /**
     * Waits for the addon to serve again after a restart request.
     *
     * A connect is the only honest signal: the addon may have parked because a
     * shutdown was asked for, and it answers nothing until its socket is back.
     * A failure logs whatever the addon wrote to its status file.
     */
    private suspend fun awaitServing(configDir: File, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val up = runCatching {
                java.net.Socket().use { probe ->
                    probe.connect(java.net.InetSocketAddress("127.0.0.1", DEFAULT_MCP_PORT), 500)
                }
                true
            }.getOrDefault(false)
            if (up) return true
            delay(250)
        }
        runCatching {
            File(configDir, "scripts/startup/blender_mcp_status.json")
                .takeIf { it.isFile }?.readText()
        }.getOrNull()?.let { Log.w(TAG, "engine status: " + it.take(200)) }
        return false
    }

    /**
     * Tells the keeper the engine is about to work, which re-arms its wake-lock
     * lease. The service has no other view of engine activity - its command loop
     * runs on the engine's own thread - so without this the lease could lapse
     * under a command that arrived after it.
     */
    fun keepAwake() {
        val app = appContext ?: return
        runCatching { BlenderEngineService.start(app) }
    }

    /** The engine's shared secret: generated once, then reused for the install. */
    fun ensureToken(configDir: File): String? = runCatching {
        val file = tokenFile(configDir)
        val existing = file.takeIf { it.isFile }?.readText()?.trim()
        if (!existing.isNullOrEmpty()) {
            existing
        } else {
            val created = java.util.UUID.randomUUID().toString().replace("-", "")
            file.parentFile?.mkdirs()
            file.writeText(created)
            created
        }
    }.getOrNull()

    fun tokenFile(configDir: File): File = File(configDir, "scripts/startup/" + TOKEN_FILE)

    private fun restartFile(configDir: File): File =
        File(configDir, "scripts/startup/blender_mcp_restart.txt")

    private var appFilesDir: File = File("/")

    /**
     * Sends the MCP shutdown command directly: the addon replies, closes the
     * socket and parks. Deliberately not routed through the modelling client,
     * which the modelling screen owns.
     */
    private fun requestShutdown(): Boolean {
        val token = tokenFile(File(appFilesDir, "blender"))
        java.net.Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(java.net.InetSocketAddress("127.0.0.1", DEFAULT_MCP_PORT), 2_000)
            socket.soTimeout = 5_000
            val body = org.json.JSONObject()
                .put("type", "shutdown")
                .put("params", org.json.JSONObject())
            token.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { body.put("token", it) }
            socket.getOutputStream().apply {
                write(body.toString().toByteArray(Charsets.UTF_8))
                flush()
            }
            return socket.getInputStream().read(ByteArray(4_096)) > 0
        }
    }

    /** First-run extraction with a version marker (around 480 MB, once). */
    private suspend fun prepareResources(context: Context, configDir: File) {
        withContext(Dispatchers.IO) {
            val marker = File(configDir, ".resources-version")
            if (marker.isFile && marker.readText() == RESOURCES_VERSION &&
                File(configDir, "python/lib/python3.11").isDirectory &&
                File(configDir, "scripts/startup/start_blender_mcp.py").isFile
            ) {
                return@withContext
            }
            try {
                configDir.deleteRecursively()
                configDir.mkdirs()
                AssetTreeExtractor.copyTree(context.assets, "blender/python", File(configDir, "python"))
                AssetTreeExtractor.copyTree(context.assets, "blender/scripts", File(configDir, "scripts"))
                marker.writeText(RESOURCES_VERSION)
                Log.i(TAG, "blender resources materialized to " + configDir.absolutePath)
            } catch (error: Throwable) {
                configDir.deleteRecursively()
                throw error
            }
        }
    }

    private fun ensureWatcher(configDir: File) {
        // One watcher per engine run. The previous one is cancelled here instead of
        // being left to poll (and hold a FileObserver watch) for the life of the
        // process every time the engine is stopped and started again.
        watcherJob?.cancel()
        watcherObserver?.stopWatching()
        watcherObserver = null
        val exports = File(configDir, "exports")
        exports.mkdirs()
        var newest: File? = null
        runCatching {
            // Everything already here is history from a previous run, not a new
            // arrival. [delivered] lives in memory, so without this the first
            // poll of every launch treats the whole backlog as freshly exported
            // and hands the UI one model after another before it settles.
            val existing = exports
                .listFiles { f -> f.isFile && f.name.endsWith(".stl", ignoreCase = true) }
                ?.sortedBy { it.lastModified() }
                .orEmpty()
            newest = existing.lastOrNull()
            synchronized(stateLock) {
                // Everything already here is history from a previous run, not a new
                // arrival - except the newest, which may be the export the user is
                // still waiting for. That one is deliberately left unclaimed and
                // goes through the same claim path as any other below, so a restart
                // inside one process cannot hand the same model over twice.
                existing.dropLast(1).forEach { delivered.add(signatureOf(it)) }
            }
            val observer = object : FileObserver(
                exports.absolutePath,
                FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || !path.endsWith(".stl", ignoreCase = true)) return
                    exportReady(File(exports, path))
                }
            }
            observer.startWatching()
            watcherObserver = observer
            Log.i(TAG, "watching exports dir " + exports.absolutePath)
        }.onFailure { error -> Log.e(TAG, "export watch failed", error) }

        watcherJob = scope.launch {
            // The export that was waiting when the engine came up is claimed and
            // handed over exactly like a fresh one: directly or through the pending
            // replay the listener setter already implements.
            newest?.let { exportReady(it) }
            Log.i(TAG, "polling exports dir " + exports.absolutePath)
            while (isActive) {
                try {
                    exports.listFiles { f -> f.isFile && f.name.endsWith(".stl", ignoreCase = true) }
                        ?.forEach { file -> exportReady(file) }
                } catch (error: Throwable) {
                    Log.e(TAG, "export poll failed", error)
                }
                delay(EXPORT_POLL_MS)
            }
        }
    }

    /** Marks an export revision delivered so it is dispatched exactly once. */
    private fun claimDelivered(file: File): Boolean = synchronized(stateLock) {
        val signature = signatureOf(file)
        if (delivered.contains(signature)) false else {
            delivered.add(signature)
            true
        }
    }

    private fun signatureOf(file: File): String =
        "${file.absolutePath}|${file.length()}|${file.lastModified()}"

    /** Dispatches a candidate export; deduped across poller and FileObserver. */
    private fun exportReady(file: File) {
        val signature = signatureOf(file)
        synchronized(stateLock) {
            if (delivered.contains(signature) || !inFlight.add(signature)) return
        }
        scope.launch {
            var complete = false
            try {
                complete = settle(file)
            } catch (error: Throwable) {
                Log.e(TAG, "export settle failed for " + file.name, error)
            }
            synchronized(stateLock) {
                inFlight.remove(signature)
                inFlight.remove(signatureOf(file))
            }
            // Still incomplete: leave it UNCLAIMED so the next poll (or the
            // CLOSE_WRITE event) picks the finished file up. Claiming here would
            // import a truncated mesh once and never revisit it.
            if (!complete) return@launch
            // Claim BEFORE invoking the listener: a failed parse is a
            // one-shot delivery, never a poll-loop retry storm.
            if (!claimDelivered(file)) return@launch
            // Decided under the same lock the listener setter drains with: the
            // listener can attach between a plain read and the queueing below, and
            // an export queued after that drain would stay queued forever - it is
            // already claimed, so the poller will not offer it again.
            val listener = synchronized(pendingExports) {
                val attached = onStlExported
                if (attached == null && pendingExports.none { it.absolutePath == file.absolutePath }) {
                    pendingExports.add(file)
                }
                attached
            }
            listener?.invoke(file)
        }
    }

    /**
     * Waits until the export is a whole STL, and reports whether it is.
     *
     * Waiting for the size to hold still - which is what this used to do - is
     * not enough. A writer that pauses for a fraction of a second mid-file
     * looks settled, so the handoff imported meshes cut off part-way through a
     * triangle, and staged a copy of each one: eleven revisions of a single
     * export, seven of them truncated, 486 MB.
     *
     * An STL states its own completeness, so wait for that instead of guessing
     * from timing. Returning false leaves the file unclaimed and the poller
     * simply tries again.
     */
    private suspend fun settle(file: File): Boolean {
        repeat(SETTLE_PROBES) {
            if (StlParser.isComplete(file)) return true
            delay(SETTLE_PROBE_MS)
        }
        return StlParser.isComplete(file)
    }
}
