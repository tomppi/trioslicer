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
                _state.update { it.copy(connected = false, error = e.message) }
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
        val status = c.query(*WATCHED)
        client = c
        _state.update {
            it.copy(
                connected = true,
                state = info.optString("state", "unknown"),
                stateMessage = info.optString("state_message"),
                error = null,
            ).withStatus(status)
        }
        c.subscribe(*WATCHED)
        Log.i(TAG, "watching ${c.toString().let { _ -> socketPath }} as ${info.optString("state")}")
        while (c.isConnected && currentCoroutineContext().isActive) delay(1000)
    }

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

    // Actions. Each reports failure into the state rather than throwing at the UI.

    fun home() = gcode("G28")
    fun setExtruderTemperature(celsius: Int) = gcode("M104 S$celsius")
    fun setBedTemperature(celsius: Int) = gcode("M140 S$celsius")

    /**
     * Reset the firmware and reload the configuration.
     *
     * The way out of a shutdown, including the one a crashed run leaves behind: the
     * micro-controller stays shut down until it is reset, and the next start of the
     * host cannot configure it while it is.
     */
    fun firmwareRestart() {
        scope.launch(Dispatchers.IO) {
            try {
                client?.firmwareRestart() ?: throw IllegalStateException("not connected")
                _state.update { it.copy(error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun gcode(script: String) {
        scope.launch(Dispatchers.IO) {
            try {
                client?.gcode(script) ?: throw IllegalStateException("not connected")
                _state.update { it.copy(error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    /** Send G-code and wait for it, for callers that show the result. */
    suspend fun gcodeNow(script: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { client?.gcode(script) ?: throw IllegalStateException("not connected") }
            .onFailure { e -> _state.update { it.copy(error = e.message) } }
            .map { }
    }

    private companion object {
        const val TAG = "KlipperPrinter"

        /** How long to wait before trying the host again after a failure. */
        const val RETRY_MS = 3000L

        /** The objects the screen needs. */
        val WATCHED = arrayOf("extruder", "heater_bed", "toolhead")
    }
}
