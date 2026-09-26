package com.tomppi.enderslicer.printer

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Watches the printer this device is driving, through klippy's API.
 *
 * The socket belongs to the host process the service runs; this connects to it as an
 * ordinary client, which klippy supports several of, so the UI and the service do not
 * need to talk to each other at all.
 *
 * Subscriptions rather than polling: klippy pushes the objects asked for as
 * notify_status_update whenever they change, which for a temperature is constantly
 * and for a position is when it moves. The first snapshot is a query, because a
 * notification only ever carries what changed.
 */
class KlipperPrinterRepository(
    private val application: Application,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(KlipperPrinterState())
    val state: StateFlow<KlipperPrinterState> = _state.asStateFlow()

    private var watchJob: Job? = null
    private var client: KlipperClient? = null

    private val socketPath: String
        get() = File(application.filesDir, "klippy.sock").absolutePath

    /** Start watching, and keep watching across restarts of the host. Idempotent. */
    fun start() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch(Dispatchers.IO) { watch() }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        client?.close()
        client = null
        _state.update { it.copy(connected = false) }
    }

    private suspend fun watch() {
        while (currentCoroutineContext().isActive) {
            try {
                follow()
            } catch (e: Exception) {
                client?.close()
                client = null
                _state.update {
                    it.copy(connected = false, error = e.message, hostLogTail = readHostLogTail())
                }
            }
            delay(RETRY_MS)
        }
    }

    /** Connect, take a snapshot, subscribe, then hold the connection until it drops. */
    private suspend fun follow() {
        val c = KlipperClient(socketPath)
        c.onNotification = ::applyNotification
        c.connect()
        val info = c.info()
        // Subscribed first, and its reply used as the snapshot: the alternative - query
        // then subscribe - has a gap in which a change is neither seen nor pushed, and
        // a value that only changes once would stay wrong on the screen.
        val snapshot = c.subscribe(*WATCHED)
        client = c
        _state.update {
            it.copy(
                connected = true,
                state = info.optString("state", "unknown"),
                stateMessage = info.optString("state_message"),
                error = null,
            ).withStatus(snapshot)
        }
        Log.i(TAG, "watching ${c.toString().let { _ -> socketPath }} as ${info.optString("state")}")
        while (c.isConnected && currentCoroutineContext().isActive) delay(1000)
    }

    /**
     * The last few lines klippy wrote before it stopped answering.
     *
     * An exited host cannot be asked anything, and its log is the only account of
     * why - a shut-down micro-controller, a bad option, a missing file. Trimmed to
     * something a screen can show.
     */
    private fun readHostLogTail(maxLines: Int = LOG_TAIL_LINES): String? = runCatching {
        val log = File(application.filesDir, "klippy.log")
        if (!log.isFile) return null
        val lines = log.readLines().filter { it.isNotBlank() }
        val interesting = lines.takeLast(maxLines)
        interesting.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }.getOrNull()

    private fun applyNotification(message: JSONObject) {
        when (KlipperProtocol.lifecycle(message)) {
            "notify_klippy_ready" -> _state.update {
                it.copy(state = "ready", stateMessage = "", error = null)
            }
            "notify_klippy_shutdown" -> _state.update {
                it.copy(state = "shutdown", stateMessage = "klippy shut down")
            }
            "notify_klippy_disconnected" -> _state.update {
                it.copy(connected = false, error = "klippy disconnected")
            }
        }
        KlipperProtocol.statusUpdate(message)?.let { status ->
            _state.update { it.withStatus(status) }
        }
    }

    // Actions.
    //
    // Every one of them is sent without waiting for its reply, and the state is what
    // reports the result. klippy answers a script when it finishes, and "when it
    // finishes" for a print is hours: a UI that waited would show a timeout on a
    // command that worked, which is exactly what the Home button did.

    /** Send G-code and let the status subscription report what happened. */
    fun command(script: String) {
        scope.launch(Dispatchers.IO) {
            try {
                client?.gcodeAsync(script) ?: throw IllegalStateException("not connected")
                _state.update { it.copy(error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun home() = command("G28")
    fun setExtruderTemperature(celsius: Int) = command("M104 S$celsius")
    fun setBedTemperature(celsius: Int) = command("M140 S$celsius")

    // The config's own macros rather than the bare pause_resume commands: they are
    // what park the head and lift it before a pause on this printer.
    fun pausePrint() = command("PAUSE")
    fun resumePrint() = command("RESUME")
    fun cancelPrint() = command("CANCEL_PRINT")

    /**
     * Copy a sliced file into the host's virtual SD card and start it printing.
     *
     * No upload protocol is needed and none is used: [virtual_sdcard] reads from a
     * directory inside this app's own storage, so starting a print is a file copy and
     * a command. That is the whole reason the config points it there.
     */
    suspend fun printFile(sourcePath: String, name: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val directory = File(application.filesDir, KlipperPrint.GCODE_DIR)
                    .apply { mkdirs() }
                val fileName = KlipperPrint.fileName(name)
                File(sourcePath).copyTo(File(directory, fileName), overwrite = true)
                client?.gcodeAsync("SDCARD_PRINT_FILE FILENAME=$fileName")
                    ?: throw IllegalStateException("not connected")
                fileName
            }.onFailure { e -> _state.update { it.copy(error = e.message) } }
        }

    /**
     * Reset the firmware and reload the configuration.
     *
     * The way out of a shutdown, including the one a crashed run leaves behind: the
     * micro-controller stays shut down until it is reset, and the next start of the
     * host cannot configure it while it is.
     */
    fun firmwareRestart() = command("FIRMWARE_RESTART")

    /** Send G-code and wait for it, for callers that show the result themselves. */
    suspend fun gcodeNow(script: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { client?.gcode(script) ?: throw IllegalStateException("not connected") }
            .onFailure { e -> _state.update { it.copy(error = e.message) } }
            .map { }
    }

    // Internal rather than private so a test can subscribe to exactly what the app
    // subscribes to: the list drifting from what the screen reads is a bug that no
    // fixture in this repository would catch.
    internal companion object {
        const val TAG = "KlipperPrinter"

        /** How long to wait before trying the host again after a failure. */
        const val RETRY_MS = 3000L

        /** How much of the host's log the screen is shown when it stops answering. */
        const val LOG_TAIL_LINES = 6

        /** The objects the screen needs: the machine, and any print on it. */
        val WATCHED = arrayOf(
            "extruder", "heater_bed", "toolhead", "print_stats", "virtual_sdcard", "mcu",
        )
    }
}
