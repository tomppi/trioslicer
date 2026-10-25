package com.tomppi.enderslicer.solidcraft

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The bundled SolidCraft CAD engine: a child process speaking MCP over stdio.
 *
 * SolidCraft (0.22.1) is the parametric CAD kernel this app uses for its live CAD document.
 * It is a PIE executable staged as libsolidcraft_exec.so, and it runs as its own process for
 * the same reason the Python CAD engine does: the engine holds a design for the life of a
 * session, so a crash or a memory blow-up in a boolean takes down only itself, and the app
 * survives to say so.
 *
 * The app is the MCP client ([McpClient]); the engine is the server. The agent that models is
 * outside both: [SolidCraftBridge] exposes the same engine session on 127.0.0.1:9878 behind the
 * same kind of token the app's other two engines use, and every geometry-changing tool call it
 * forwards is reported back here as a [DocumentDelta] so the plate repaints.
 *
 * The engine's files are limited to its --allow-path workspace (files/solidcraft/workspace):
 * designs, opened files, display tessellations and exports all live inside it, and the engine
 * cannot read or write anywhere else. Its own scratch and recovery folders are pointed inside
 * the app's private directories with TMPDIR and SOLIDCRAFT_RECOVERY_DIR.
 */
object SolidCraftEngine {

    private const val TAG = "SolidCraftEngine"

    /** The release this app is pinned to; the fetch script stages the matching binary. */
    const val VERSION = "0.22.1"

    /** The existing engines hold 9876 and 9877; this one is the third. */
    const val DEFAULT_PORT = SolidCraftBridge.DEFAULT_PORT

    private const val ENGINE_LIBRARY = "libsolidcraft_exec.so"
    private const val TOKEN_FILE = "solidcraft_mcp_token.txt"
    private const val START_TIMEOUT_MILLIS = 90_000L
    private const val MAX_LOG_LINES = 200

    private val lock = Any()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var client: McpClient? = null

    @Volatile
    private var session: SolidCraftSession? = null

    @Volatile
    private var bridge: SolidCraftBridge? = null

    @Volatile
    private var bridgeToken: String? = null

    @Volatile
    private var starting = false

    @Volatile
    private var startingAt = 0L

    @Volatile
    private var stopping = false

    @Volatile
    private var failure: String? = null

    @Volatile
    private var toolNames: List<String> = emptyList()

    /** Called on the bridge's thread after a tool call that changed the design. */
    @Volatile
    var onDocumentChanged: ((DocumentDelta) -> Unit)? = null

    private val logLines = ArrayDeque<String>()

    fun home(context: Context): File = File(context.filesDir, "solidcraft")

    fun workspaceRoot(context: Context): File = File(home(context), "workspace")

    fun workspace(context: Context): CadWorkspace = CadWorkspace(workspaceRoot(context))

    fun tokenFile(context: Context): File = File(home(context), TOKEN_FILE)

    fun logFile(context: Context): File = File(home(context), "engine.log")

    fun executable(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, ENGINE_LIBRARY)

    /** The binary is packaged and looks like an engine. */
    fun isAvailable(context: Context): Boolean {
        val exe = executable(context)
        return exe.isFile && exe.length() > 0L
    }

    fun isReady(): Boolean = session != null && process?.isAlive == true

    fun isStarting(): Boolean = starting

    fun isStopping(): Boolean = stopping

    fun bridgePort(): Int = bridge?.boundPort ?: 0

    fun tools(): List<String> = toolNames

    /** The last entries of the engine's stderr, for the diagnostics log. */
    fun recentLog(): List<String> = synchronized(logLines) { logLines.toList() }

    /**
     * The token the agent must present on the bridge.
     *
     * Generated once per install, 32 hex characters with the dashes removed - the same shape
     * and the same file-permission boundary as the Blender (9876) and CAD (9877) engines.
     */
    fun ensureToken(context: Context): String? = runCatching {
        val file = tokenFile(context)
        val existing = file.takeIf { it.isFile }?.readText()?.trim()
        if (!existing.isNullOrEmpty()) {
            existing
        } else {
            val created = UUID.randomUUID().toString().replace("-", "")
            file.parentFile?.mkdirs()
            file.writeText(created)
            created
        }
    }.onFailure { Log.e(TAG, "could not write the engine token", it) }.getOrNull()

    /**
     * Starts the engine if it is not up, handshakes, and opens the agent bridge.
     *
     * Blocking: call from a background dispatcher. Returns true only when the engine answered
     * the MCP initialize and the bridge is listening. A failure is kept in [failure] and in
     * [status], never thrown at the caller: every path that starts this engine has a sentence
     * to show.
     */
    fun ensureStarted(context: Context): Boolean = synchronized(lock) {
        if (isReady()) return true
        start(context)
    }

    fun session(): SolidCraftSession? = session

    fun rawClient(): McpClient? = client

    /** The engine's own sentence for the screen, in the order the states matter. */
    fun status(context: Context): String {
        failure?.let { return it }
        return when {
            starting -> "CAD agent starting… " +
                ((System.currentTimeMillis() - startingAt) / 1000) + "s"
            isReady() -> "CAD agent ready on 127.0.0.1:" + (bridge?.boundPort ?: DEFAULT_PORT) +
                " · " + toolNames.size + " MCP tools"
            !isAvailable(context) -> "the SolidCraft engine binary is not packaged in this APK"
            else -> "CAD agent stopped"
        }
    }

    /**
     * A terminal reason the engine cannot be up, or null while it is coming up, up, or merely
     * stopped. A missing binary is terminal; "starting" is not.
     */
    fun problem(context: Context): String? {
        if (!isAvailable(context)) {
            return "the SolidCraft engine binary is not packaged in this APK (expected " +
                ENGINE_LIBRARY + " in the native library directory)"
        }
        if (starting) return null
        return failure
    }

    private fun start(context: Context): Boolean {
        if (stopping) {
            failure = "the CAD agent is stopping; start it again in a moment"
            return false
        }
        starting = true
        startingAt = System.currentTimeMillis()
        failure = null
        try {
            return startEngine(context)
        } catch (error: Throwable) {
            failure = "the SolidCraft engine could not be started: " +
                (error.message ?: error.javaClass.simpleName)
            Log.e(TAG, "start failed", error)
            stopQuietly()
            return false
        } finally {
            starting = false
        }
    }

    private fun startEngine(context: Context): Boolean {
        val exe = executable(context)
        if (!exe.isFile || exe.length() == 0L) {
            failure = "the SolidCraft engine binary is not packaged in this APK (expected " +
                ENGINE_LIBRARY + " in the native library directory)"
            Log.e(TAG, "engine missing at " + exe.absolutePath)
            return false
        }
        val workspace = workspace(context).ensureDirectories()
        val scratch = File(context.cacheDir, "solidcraft-tmp").apply { mkdirs() }
        val recovery = File(home(context), "recovery").apply { mkdirs() }
        val token = ensureToken(context)
        if (token.isNullOrEmpty()) {
            failure = "the SolidCraft engine's token could not be written, so its agent bridge " +
                "would accept anyone; the engine was not started"
            return false
        }
        val nativeDirectory = context.applicationInfo.nativeLibraryDir
        val builder = ProcessBuilder(
            exe.absolutePath,
            "mcp",
            "--headless",
            "--allow-path",
            workspace.root.absolutePath,
        )
        builder.directory(workspace.root)
        builder.redirectErrorStream(false)
        builder.environment().apply {
            put("TMPDIR", scratch.absolutePath)
            put("HOME", context.filesDir.absolutePath)
            put("SOLIDCRAFT_RECOVERY_DIR", recovery.absolutePath)
            // Explicitly empty, not inherited and not the app's native library directory. The
            // engine needs nothing from that directory (its only NEEDED entries are system
            // libs), and pointing the linker at it changes which namespace libandroid.so is
            // resolved in: the engine then pulls libandroid_runtime outside the zygote and dies
            // with "cannot locate symbol register_android_functions". Measured on the dev
            // phone: the same binary runs from a shell with an empty path and fails with the
            // app's directory on it.
            put("LD_LIBRARY_PATH", "")
            put("SOLIDCRAFT_GPU", "cpu")
        }
        log("starting the SolidCraft engine: " + builder.command().joinToString(" "))
        Log.i(TAG, "starting the SolidCraft engine: " + builder.command().joinToString(" "))
        val started = builder.start()
        process = started
        drainToLog(started)
        thread(name = "solidcraft-engine-exit", isDaemon = true) {
            val code = runCatching { started.waitFor() }.getOrDefault(-1)
            if (!stopping && process === started) {
                Log.w(TAG, "the SolidCraft engine exited with " + code)
                failure = "the SolidCraft engine exited (" + code + ")"
                session = null
                client = null
            }
        }
        val transport = StreamMcpTransport(started.inputStream, started.outputStream)
        val raw = McpClient(transport) { message -> log(message) }.start()
        val live = SolidCraftSession(raw)
        val initialized = live.initialize(START_TIMEOUT_MILLIS)
        client = raw
        session = live
        toolNames = runCatching { live.listTools() }.getOrDefault(emptyList())
        log(
            "handshake: protocol " + (initialized.optString("protocolVersion").ifBlank { "?" }) +
                " · " + (live.serverName ?: "?") + " " + (live.serverVersion ?: "?") +
                " · " + toolNames.size + " tools",
        )
        // The bridge is the agent's door. A bridge that cannot bind must not take the engine
        // with it: the app itself still uses the session for exports and slicing.
        val liveBridge = SolidCraftBridge(
            token = token,
            forwarder = object : McpForwarder {
                override fun request(method: String, params: JSONObject, timeoutMillis: Long): JSONObject =
                    raw.request(method, params, timeoutMillis)

                override fun notify(method: String, params: JSONObject) = raw.notify(method, params)
            },
            onDocumentChanged = { delta ->
                onDocumentChanged?.invoke(delta)
            },
            log = { message -> log(message) },
        )
        try {
            liveBridge.start()
            bridge = liveBridge
            bridgeToken = token
        } catch (error: Throwable) {
            log("the CAD agent bridge could not listen on " + DEFAULT_PORT + ": " + error.message)
            failure = "the SolidCraft engine is running, but its agent bridge could not listen " +
                "on 127.0.0.1:" + DEFAULT_PORT + ": " + (error.message ?: "unknown reason")
            runCatching { liveBridge.close() }
            // Keep the engine: it is usable by the app alone.
        }
        return true
    }

    private fun drainToLog(process: Process) {
        thread(name = "solidcraft-engine-stderr", isDaemon = true) {
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    log("stderr: " + line)
                }
            } catch (_: Throwable) {
                // A closed stream is the process ending.
            }
        }
    }

    /** The engine's stderr and lifecycle, to logcat and to the sheet's diagnostic ring. */
    private fun log(message: String) {
        Log.i(TAG, message)
        synchronized(logLines) {
            logLines.addLast(message)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        }
    }

    /** Stops the engine and its bridge. Safe when it is not running. */
    fun stop() {
        synchronized(lock) {
            stopping = true
            try {
                runCatching { bridge?.close() }
                bridge = null
                bridgeToken = null
                runCatching { client?.close() }
                client = null
                session = null
                toolNames = emptyList()
                process?.let { running ->
                    runCatching {
                        running.destroy()
                        if (!running.waitFor(3, TimeUnit.SECONDS)) {
                            running.destroyForcibly()
                            running.waitFor(2, TimeUnit.SECONDS)
                        }
                    }.onFailure { Log.w(TAG, "the SolidCraft engine did not stop cleanly", it) }
                }
                process = null
                failure = null
            } finally {
                stopping = false
            }
        }
        Log.i(TAG, "SolidCraft engine stopped")
    }

    private fun stopQuietly() {
        runCatching { bridge?.close() }
        bridge = null
        runCatching { client?.close() }
        client = null
        session = null
        runCatching { process?.destroyForcibly() }
        process = null
    }
}
