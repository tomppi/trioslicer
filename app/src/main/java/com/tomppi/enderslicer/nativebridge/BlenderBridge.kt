package com.tomppi.enderslicer.nativebridge

import android.util.Log

/**
 * BlenderBridge -- JNI surface for libblender_exec.so (Blender 3.6 engine,
 * built from the epai android_arm64 harness; see native/blender/README.md).
 *
 * Start/stop the embedded Blender engine (background mode + MCP addon). The
 * MCP socket server (default port 9876) is started automatically inside the
 * engine by assets/blender/scripts/startup/start_blender_mcp.py; the AI MCP
 * server (external) connects to it. The app only loads STLs from the
 * configured shared export dir.
 *
 * The engine library is arm64-v8a only and ~1.3 GB, so it is loaded lazily on
 * a background thread (never in Application.onCreate). On other ABIs the load
 * fails and this bridge degrades to a no-op instead of crashing the process,
 * so the x86_64 emulator build still runs (without the Blender engine).
 */
object BlenderBridge {
    private const val TAG = "BlenderBridge"

    @Volatile private var loadState = 0 // 0 = untried, 1 = loading/loaded, 2 = failed
    @Volatile private var loaded = false

    /**
     * Three of the engine's NEEDED entries name their library WITHOUT the lib
     * prefix - "hdTiny.so", "sdrGlslfx.so", "usdShaders.so" - and that name is
     * also each one's SONAME.
     *
     * The Android package manager extracts only `lib*.so` from an APK's
     * `lib/<abi>/`, so the copies the build ships under those exact names reach
     * the APK and never reach the app's native library directory. dlopen then
     * fails with 'library "hdTiny.so" not found: needed by libblender_exec.so',
     * which this bridge reported as 'not packaged for this ABI' - so the engine
     * could not start on any device, on the one ABI it is built for.
     *
     * Loading each dependency through its lib-prefixed FILE name first registers
     * it under its SONAME, and the linker resolves the engine's NEEDED entries
     * from the libraries already loaded. Order matters: dependencies first.
     */
    private val DEPENDENCIES = listOf("hdTiny", "sdrGlslfx", "usdShaders")

    /**
     * Loads the engine library exactly once if it is present for this ABI.
     * Safe to call from any thread; no-op after the first attempt.
     */
    fun ensureLoaded(): Boolean {
        if (loadState != 0) return loaded
        synchronized(this) {
            if (loadState != 0) return loaded
            loadState = 1
            try {
                DEPENDENCIES.forEach { dependency ->
                    runCatching { System.loadLibrary(dependency) }.onFailure { error ->
                        // Not fatal on its own: the engine's own load reports which
                        // name it could not resolve, and that message is the useful one.
                        Log.w(TAG, "Blender dependency $dependency did not load (" + error.message + ")")
                    }
                }
                System.loadLibrary("blender_exec")
                loaded = true
            } catch (error: UnsatisfiedLinkError) {
                loadState = 2
                Log.w(TAG, "libblender_exec.so unavailable in this ABI; Blender MCP engine disabled (" + error.message + ")")
            }
        }
        return loaded
    }

    /** False when the engine library is not packaged for this ABI. */
    fun isAvailable(): Boolean = loadState != 0 && loaded

    external fun nativeBlenderStart(
        home: String, config: String, scripts: String,
        python: String, datafiles: String, host: String, port: Int
    ): Boolean

    external fun nativeBlenderIsRunning(): Boolean
    external fun nativeBlenderStop()

    /**
     * Start Blender in the background. Paths:
     *  - home: blender home (engine writes temp/cache here)
     *  - config: assets extraction root (contains python/, scripts/, VERSION/config)
     *  - scripts: <config>/scripts (BLENDER_SYSTEM_SCRIPTS)
     *  - python: <config>/python (PYTHONHOME)
     *  - datafiles: <config>/3.6/config/datafiles (BLENDER_SYSTEM_DATAFILES)
     *  - port: MCP socket port the addon listens on
     */
    fun start(home: String, config: String, port: Int = 9876, host: String = "localhost") {
        if (!ensureLoaded()) return
        val running = runCatching { nativeBlenderIsRunning() }.getOrDefault(false)
        if (running) {
            Log.i(TAG, "started=true port=$port host=$host (already running)")
            return
        }
        val scripts = "$config/scripts"
        val python = "$config/python"
        val datafiles = "$config/3.6/config/datafiles"
        val ok = runCatching { nativeBlenderStart(home, config, scripts, python, datafiles, host, port) }
            .getOrElse { error ->
                Log.e(TAG, "native start failed", error)
                false
            }
        Log.i(TAG, "started=$ok port=$port host=$host")
    }

    /**
     * True while the engine thread is alive.
     *
     * A parked engine - the addon waiting for a restart request after a
     * `shutdown` - counts as alive: the thread is there, only its socket is
     * closed, and starting again means asking it to serve rather than loading a
     * second copy of Blender into the process.
     */
    fun isRunning(): Boolean =
        runCatching { ensureLoaded() && nativeBlenderIsRunning() }.getOrDefault(false)

    fun stop() {
        if (!ensureLoaded()) return
        runCatching { nativeBlenderStop() }
            .onFailure { Log.e(TAG, "native stop failed", it) }
        Log.i(TAG, "stop requested")
    }
}
