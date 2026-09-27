package com.tomppi.enderslicer.printer

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
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
 *
 * What is asked for is decided at every connection from the printer's own object list,
 * so a sensor or a fan added to its configuration shows up in the tabs without this
 * app being told about it.
 */
class KlipperPrinterRepository(
    private val application: Application,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(KlipperPrinterState())
    val state: StateFlow<KlipperPrinterState> = _state.asStateFlow()

    private val console = KlipperConsole()
    private val _consoleLines = MutableStateFlow<List<KlipperConsoleLine>>(emptyList())

    /** The console's scrollback, oldest first, ready to render. */
    val consoleLines: StateFlow<List<KlipperConsoleLine>> = _consoleLines.asStateFlow()

    private val _commands = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Every G-code command this printer answers, with klippy's own description.
     *
     * Asked for once per connection: the list is the printer's, and a printer's
     * commands change when its configuration does - which is to say when the host
     * restarts, which is when this is asked again.
     */
    val commands: StateFlow<Map<String, String>> = _commands.asStateFlow()

    private val _configSource = MutableStateFlow(KlipperConfigSource())

    /** Where the running configuration came from: this app, or the person using it. */
    val configSource: StateFlow<KlipperConfigSource> = _configSource.asStateFlow()

    private val temperatureLog = KlipperTemperatureLog()
    private val _temperatures = MutableStateFlow<List<KlipperTemperatureSample>>(emptyList())

    /** One reading a second for the last five minutes, which the chart is drawn from. */
    val temperatures: StateFlow<List<KlipperTemperatureSample>> = _temperatures.asStateFlow()

    private val printHistory = KlipperPrintHistory(File(application.filesDir, HISTORY_FILE))
    private val _history = MutableStateFlow<List<KlipperPrintRecord>>(emptyList())

    /** Prints this app has run, newest first. */
    val history: StateFlow<List<KlipperPrintRecord>> = _history.asStateFlow()

    private var watchJob: Job? = null

    /** The print that is running, so that its record knows when it started. */
    private var startedAt = 0L
    private var startedFile = ""
    private var lastPrintState: String? = null
    private var client: KlipperClient? = null

    private val socketPath: String
        get() = File(application.filesDir, "klippy.sock").absolutePath

    init {
        // Read once, at construction: it is a few kilobytes of JSON, and the screen
        // that shows it should not have to wait for a disk read to render.
        _history.value = printHistory.load()
        _configSource.value = readConfigSource()
    }

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
                // Before watching: a configuration this app seeded and has since improved
                // is the app's to keep current, and klippy picks it up at its next start
                // rather than being rewritten under a running host.
                refreshShippedConfigIfUntouched()
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
        // Before anything else, and again on every connection: the subscription belongs
        // to this connection, so a reconnect that forgot it would leave a console that
        // silently stopped showing what the printer said.
        runCatching { c.subscribeOutput() }
        val info = c.info()
        val published = runCatching { c.listObjects() }.getOrDefault(emptyList())
        val wanted = KlipperWatch.forPrinter(published)
        // Subscribed first, and its reply used as the snapshot: the alternative - query
        // then subscribe - has a gap in which a change is neither seen nor pushed, and
        // a value that only changes once would stay wrong on the screen.
        val snapshot = c.subscribe(*wanted.toTypedArray())
        client = c
        _state.update {
            it.copy(
                connected = true,
                state = info.optString("state", "unknown"),
                stateMessage = info.optString("state_message"),
                error = null,
                host = KlipperHost.from(info),
                published = published,
            ).withStatus(snapshot)
        }
        Log.i(TAG, "watching $socketPath as " + info.optString("state") +
            "; following " + wanted.size + " of " + published.size + " objects")
        runCatching { c.gcodeHelp() }.onSuccess { help -> _commands.value = help }
        // klippy has no state notification on the wire, so the state that decides
        // whether the screen is allowed to print is asked for rather than waited on.
        var sinceStateCheck = 0
        while (c.isConnected && currentCoroutineContext().isActive) {
            delay(1000)
            if (++sinceStateCheck >= STATE_POLL_SECONDS) {
                sinceStateCheck = 0
                runCatching { c.info() }.getOrNull()?.let { info ->
                    _state.update {
                        it.copy(
                            state = info.optString("state", it.state),
                            stateMessage = info.optString("state_message", it.stateMessage),
                        )
                    }
                }
            }
        }
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

    /** When the repository last wrote what it had merged. */
    private var lastStatusLog = 0L

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
        // A command sent without waiting for its reply has no waiter, so klippy's error
        // for it arrives here rather than at a caller. Before this was read, a macro
        // that failed was a macro that did nothing at all.
        KlipperProtocol.errorReply(message)?.let { text ->
            append(text, KlipperConsoleLine.Source.ERROR)
            _state.update { it.copy(error = text) }
            return
        }
        KlipperProtocol.gcodeResponse(message)?.let { text ->
            append(
                text,
                if (text.startsWith("!!")) KlipperConsoleLine.Source.ERROR
                else KlipperConsoleLine.Source.OUTPUT,
            )
            return
        }
        KlipperProtocol.statusUpdate(message)?.let { status ->
            _state.update { it.withStatus(status) }
            // Sampled once a second rather than kept per notification: the chart
            // wants the heater, not the socket.
            temperatureLog.record(System.currentTimeMillis(), _state.value.heaters)?.let { samples ->
                _temperatures.value = samples
            }
            recordPrintIfItEnded()
            // The screen and the log disagree about whether temperatures arrive, and
            // there is no way to tell which is lying from the outside. This says what
            // the state actually holds, bounded so a print does not fill the log.
            val now = System.currentTimeMillis()
            if (now - lastStatusLog >= STATUS_LOG_MS) {
                lastStatusLog = now
                val merged = _state.value
                Log.i(TAG, "status: extruder=" + merged.extruderTemperature + "C bed=" +
                    merged.bedTemperature + "C printer=" + merged.state + " print=" +
                    merged.printState + " objects=" + status.length())
            }
        }
    }

    /**
     * Write down a print when it ends.
     *
     * klippy reports the state of the print that is happening and then the state of
     * the next one; nothing in its API remembers the last one. The transition is
     * therefore the only moment the numbers exist: the duration, the filament, how
     * it ended.
     */
    private fun recordPrintIfItEnded() {
        val current = _state.value
        val printState = current.printState
        if (printState == lastPrintState) return
        val previous = lastPrintState
        lastPrintState = printState
        when {
            // Paused and back is the same print, and klippy reports "printing" again
            // when it resumes - so only a state that is not a resume starts one.
            printState == "printing" && previous != "paused" -> {
                startedAt = System.currentTimeMillis()
                startedFile = current.printFileName.orEmpty()
            }
            printState == "complete" || printState == "cancelled" || printState == "error" -> {
                val finished = startedAt > 0
                _history.value = printHistory.append(
                    KlipperPrintRecord(
                        fileName = startedFile.ifBlank { current.printFileName.orEmpty() },
                        startedAtMillis = startedAt.takeIf { finished }
                            ?: (System.currentTimeMillis() - ((current.printDurationSeconds ?: 0.0) * 1000).toLong()),
                        durationSeconds = current.printDurationSeconds ?: 0.0,
                        filamentMillimetres = current.printFilamentUsed ?: 0.0,
                        outcome = printState,
                        layers = current.printLayers?.current,
                    ),
                )
                startedAt = 0L
                startedFile = ""
            }
        }
    }

    /** Forget every print this app has recorded. */
    fun clearHistory() {
        _history.value = printHistory.clear()
    }

    /** Write a line to the console's scrollback and hand the screen the new list. */
    private fun append(text: String, source: KlipperConsoleLine.Source) {
        _consoleLines.value = console.add(text, source, System.currentTimeMillis())
    }

    fun clearConsole() {
        console.clear()
        _consoleLines.value = emptyList()
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

    /**
     * Run something the user asked for, in the console as well as on the printer.
     *
     * Everything the tabs do goes through here, so the console is a record of what the
     * app did and not only of what was typed into it. Without that, a button that moved
     * the machine to the wrong place leaves nothing behind to look at.
     */
    fun send(script: String) {
        val text = script.trim()
        if (text.isEmpty()) return
        append(text, KlipperConsoleLine.Source.SENT)
        command(text)
    }

    fun home(axes: String = "") = send(KlipperScripts.home(axes))

    fun jog(axis: String, distance: Double, feedrate: Int = DEFAULT_JOG_FEEDRATE) =
        send(KlipperScripts.jog(axis, distance, feedrate))

    fun adjustZOffset(delta: Double, move: Boolean) = send(KlipperScripts.zOffset(delta, move))

    fun resetZOffset() = send("SET_GCODE_OFFSET Z=0")

    /** The part fan, as a percentage of full speed. */
    fun setFan(percent: Int) = send(KlipperScripts.fan(percent))

    /** M220: print speed, as a percentage of what the file asks for. */
    fun setSpeedFactor(percent: Int) = send(KlipperScripts.speedFactor(percent))

    /** M221: extrusion, as a percentage of what the file asks for. */
    fun setExtrudeFactor(percent: Int) = send(KlipperScripts.extrudeFactor(percent))

    /**
     * Push filament through, or pull it back.
     *
     * Relative extrusion inside a saved state, for the same reason as a jog: the file's
     * own absolute/relative choice is left exactly as it was.
     */
    fun extrude(lengthMm: Double, feedrate: Int = DEFAULT_EXTRUDE_FEEDRATE) =
        send(KlipperScripts.extrude(lengthMm, feedrate))

    fun setPressureAdvance(advance: Double) = send(KlipperScripts.pressureAdvance(advance))

    fun setRetraction(length: Double, speed: Double) =
        send(KlipperScripts.retraction(length, speed))

    /** Run one of the printer's own macros, exactly as its config defines it. */
    fun runMacro(name: String) = send(name)

    /** PID-tune a heater, which needs the printer to be at temperature and idle. */
    fun calibratePid(heater: String, target: Int) =
        send(KlipperScripts.pidCalibrate(heater, target))

    fun calibrateMesh() = send("BED_MESH_CALIBRATE")

    /**
     * Set one axis of input shaping, live.
     *
     * The printer acts on it immediately and forgets it at the next restart: what
     * survives is the [input_shaper] section of its configuration, which is what
     * [saveShapers] writes. Nothing here needs an accelerometer - the values can be
     * set from a ringing test or from whatever the printer was tuned with - and the
     * effect of a wrong value is visible in the print, which is how most people tune
     * it.
     */
    fun applyShaper(axis: String, type: String, frequency: Double, dampingRatio: Double?) =
        send(KlipperScripts.inputShaper(axis, type, frequency, dampingRatio))

    /** Ask the printer what shaping it is using; it answers in the console. */
    fun reportShapers() = send("SET_INPUT_SHAPER")

    /** Write shaping into the printer's own configuration, where a restart finds it. */
    internal suspend fun saveShapers(settings: List<KlipperConfigFile.ShaperSetting>): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val config = KlipperHostFiles.config(application.filesDir)
                if (!config.isFile) return@runCatching false
                config.copyTo(KlipperHostFiles.previous(application.filesDir), overwrite = true)
                config.writeText(KlipperConfigFile.withInputShaper(config.readText(), settings))
                true
            }.getOrDefault(false)
        }

    /** Load, save or remove a saved mesh profile: LOAD, SAVE, REMOVE. */
    fun meshProfile(action: String, name: String) = send(KlipperScripts.meshProfile(action, name))

    fun queryEndstops() = send("QUERY_ENDSTOPS")

    /**
     * Start calibrating the probe: klippy probes the bed and then waits, above it, for
     * someone to bring the nozzle down onto a piece of paper with [testZ].
     *
     * This is the number that decides the first layer - and the one the app had no way
     * of setting at all until the Z probe screen existed.
     */
    fun calibrateProbe() = send("PROBE_CALIBRATE")

    /**
     * Move the nozzle by a distance while calibrating, and let klippy track it.
     *
     * TESTZ rather than a move: klippy is in its manual-probe state, where a Z move is
     * how the offset being calibrated is measured. Sending G1 here would move the head
     * without telling the calibration what happened.
     */
    fun testZ(delta: Double) = send("TESTZ Z=" + KlipperScripts.offset(delta))

    /** Take the position the paper was found at as the probe's offset. */
    fun acceptProbeCalibration() = send("ACCEPT")

    /** Give up on a calibration without changing anything. */
    fun abortProbeCalibration() = send("ABORT")

    fun disableMotors() = send("M84")

    fun enableMotors() = send("M17")

    /** Leave one of the file's objects out of the print that is running. */
    fun excludeObject(name: String) = send("EXCLUDE_OBJECT NAME=$name")

    /** Write klippy's saved values into the config file; it restarts to apply them. */
    fun saveConfig() = send("SAVE_CONFIG")

    /**
     * Reset the firmware and reload the configuration.
     *
     * The way out of a shutdown, including the one a crashed run leaves behind: the
     * micro-controller stays shut down until it is reset, and the next start of the
     * host cannot configure it while it is.
     */
    fun firmwareRestart() = command("FIRMWARE_RESTART")

    /**
     * Stop everything now.
     *
     * Its own API call rather than "M112" in the queue: G-code is processed in order,
     * and an emergency is the one thing that cannot wait behind what is already there.
     */
    fun emergencyStop() {
        append("EMERGENCY STOP", KlipperConsoleLine.Source.SENT)
        scope.launch(Dispatchers.IO) {
            try {
                client?.emergencyStop() ?: throw IllegalStateException("not connected")
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    // The config's own macros rather than the bare pause_resume commands: they are
    // what park the head and lift it before a pause on this printer.
    fun pausePrint() = send("PAUSE")
    fun resumePrint() = send("RESUME")
    fun cancelPrint() = send("CANCEL_PRINT")

    /** Heat the hotend, or turn it off with 0. */
    fun setExtruderTemperature(celsius: Int) = send(KlipperScripts.hotend(celsius))

    /** Heat the bed, or turn it off with 0. */
    fun setBedTemperature(celsius: Int) = send(KlipperScripts.bed(celsius))

    /** Turn everything off at once, which is what a user means by "cool down". */
    fun coolDown() = send(KlipperScripts.coolDown())

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
                val directory = gcodeDirectory()
                val fileName = KlipperPrint.fileName(name)
                File(sourcePath).copyTo(File(directory, fileName), overwrite = true)
                append("SDCARD_PRINT_FILE FILENAME=$fileName", KlipperConsoleLine.Source.SENT)
                client?.gcodeAsync("SDCARD_PRINT_FILE FILENAME=$fileName")
                    ?: throw IllegalStateException("not connected")
                fileName
            }.onFailure { e -> _state.update { it.copy(error = e.message) } }
        }

    /**
     * The files on the printer's virtual SD card, newest first.
     *
     * [virtual_sdcard] is pointed at a directory inside this app's own storage, so
     * this is the app reading its own files rather than asking klippy for a listing
     * it does not offer.
     */
    suspend fun listGcodeFiles(): List<KlipperGcodeFile> = withContext(Dispatchers.IO) {
        KlipperGcodeFiles.list(gcodeDirectory())
    }

    /** Start one of those files printing, by the name the printer knows it by. */
    suspend fun printGcodeFile(name: String) {
        val fileName = KlipperPrint.fileName(name)
        append("SDCARD_PRINT_FILE FILENAME=$fileName", KlipperConsoleLine.Source.SENT)
        withContext(Dispatchers.IO) {
            runCatching {
                client?.gcodeAsync("SDCARD_PRINT_FILE FILENAME=$fileName")
                    ?: throw IllegalStateException("not connected")
            }.onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    /** Delete one of those files. Confined to the printer's own directory. */
    suspend fun deleteGcodeFile(name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val directory = gcodeDirectory().canonicalFile
            val target = File(directory, KlipperPrint.fileName(name)).canonicalFile
            if (target.parentFile != directory) return@runCatching false
            target.isFile && target.delete()
        }.getOrDefault(false)
    }

    /**
     * One file's thumbnail, decoded for the screen that shows it.
     *
     * Decoded here rather than in the interface: it is a base64 PNG from a comment in
     * a file, which means a disk read, a decode and an allocation, and none of those
     * belong in a composable.
     */
    suspend fun fileThumbnail(name: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(gcodeDirectory(), KlipperPrint.fileName(name))
            val encoded = KlipperGcodeFiles.read(file).thumbnail ?: return@runCatching null
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /** The directory [virtual_sdcard] reads, created if it is not there yet. */
    private fun gcodeDirectory(): File = KlipperHostFiles.gcodes(application.filesDir).apply { mkdirs() }

    /**
     * The configuration the host is running with, as text.
     *
     * Read from the file rather than asked of klippy, which publishes the settings it
     * parsed and not the file they came from - comments and all, which is what a person
     * editing it is looking at.
     */
    suspend fun readConfigFile(): String? = withContext(Dispatchers.IO) {
        runCatching {
            KlipperHostFiles.config(application.filesDir).takeIf { it.isFile }?.readText()
        }.getOrNull()
    }

    /** Where the running configuration came from, as the service left it. */
    private fun readConfigSource(): KlipperConfigSource = runCatching {
        KlipperHostFiles.source(application.filesDir).takeIf { it.isFile }?.readText()
            ?.let(KlipperConfigSource::from) ?: KlipperConfigSource.SHIPPED
    }.getOrDefault(KlipperConfigSource.SHIPPED)

    private fun writeConfigSource(source: KlipperConfigSource) {
        runCatching {
            KlipperHostFiles.directory(application.filesDir).mkdirs()
            KlipperHostFiles.source(application.filesDir).writeText(source.toText())
        }
        _configSource.value = source
    }

    /**
     * True when the running configuration is not the one this app ships.
     *
     * Compared without klippy's own saved block, which is meant to differ: what is being
     * asked is whether the hand-written part - pins, kinematics, limits - has moved on
     * from the version the app carries. It is what the Configuration card shows a restore
     * button for, and it stays true for as long as a configuration somebody brought is
     * the one running.
     */
    suspend fun configDiffersFromShipped(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val running = KlipperHostFiles.config(application.filesDir).takeIf { it.isFile }
                ?.readText() ?: return@runCatching false
            val shipped = KlipperHostFiles.shipped(application.filesDir).takeIf { it.isFile }
                ?.readText() ?: return@runCatching false
            KlipperConfigFile.differsFromShipped(running, shipped)
        }.getOrDefault(false)
    }

    /**
     * Bring a configuration of the user's own: their printer, as their printer is set up.
     *
     * The app's own parts of it are put in on the way - the serial port it can actually
     * open, the gcode directory it actually writes - because a configuration written for
     * a host names paths that do not exist on a phone, and dropping it in unchanged looks
     * exactly like a printer that is not there.
     *
     * What the file already had saved comes with it, so importing the configuration that
     * has been running a printer does not cost a PID calibration or a mesh profile. The
     * file that was running before is kept beside it, and the app records that the
     * configuration is now the user's: it will not be rewritten again.
     */
    suspend fun importConfig(uris: List<Uri>): Result<KlipperImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (uris.isEmpty()) throw IllegalArgumentException("no file was chosen")
            val files = uris.map { uri ->
                val name = fileNameOf(uri)
                name to (readText(uri) ?: throw IllegalStateException("could not read $name"))
            }
            // printer.cfg is the configuration; anything else came along to satisfy its
            // includes and is written beside it, where klippy resolves them from.
            val mainIndex = files.indexOfFirst { it.first.equals(KlipperHostFiles.CONFIG, true) }
                .takeIf { it >= 0 } ?: 0
            val main = files[mainIndex]
            val companions = files.filterIndexed { index, _ -> index != mainIndex }

            val directory = KlipperHostFiles.directory(application.filesDir).apply { mkdirs() }
            companions.forEach { (name, text) -> File(directory, safeFileName(name)).writeText(text) }

            val rewrite = KlipperConfigFile.forDevice(
                text = main.second,
                serialPath = KlipperHostFiles.pty(application.filesDir).absolutePath,
                gcodeDirectory = gcodeDirectory().absolutePath,
                availableFiles = companions.map { it.first }.toSet(),
            )
            val config = KlipperHostFiles.config(application.filesDir)
            if (config.isFile) config.copyTo(KlipperHostFiles.previous(application.filesDir), overwrite = true)
            config.writeText(KlipperConfigFile.withSavedValues(rewrite.text, main.second))
            writeConfigSource(
                KlipperConfigSource(imported = true, name = main.first, atMillis = System.currentTimeMillis()),
            )
            KlipperImportResult(
                fileName = main.first,
                changes = rewrite.changes,
                warnings = rewrite.warnings,
                companions = companions.map { it.first },
            )
        }.onFailure { error -> _state.update { it.copy(error = error.message) } }
    }

    /** Write the running configuration out where the user asked for it. */
    suspend fun exportConfig(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val text = KlipperHostFiles.config(application.filesDir).takeIf { it.isFile }
                ?.readText() ?: return@runCatching false
            application.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(text)
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    /**
     * Put the shipped configuration back, keeping what the printer has already saved.
     *
     * The way an update's fixes reach a printer whose configuration was seeded before
     * them, and the way back from a configuration - imported or edited - that the printer
     * will not start with. The saved block is carried over, because losing a PID
     * calibration or a mesh profile to a button that promised a way back would be the
     * opposite of what it promised. It does not change where the configuration came from:
     * a user who brought their own and restored the app's has the app's again.
     */
    suspend fun restoreShippedConfig(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val config = KlipperHostFiles.config(application.filesDir)
            val shipped = KlipperHostFiles.shipped(application.filesDir)
            if (!shipped.isFile) return@runCatching false
            val existing = config.takeIf { it.isFile }?.readText().orEmpty()
            if (config.isFile) config.copyTo(KlipperHostFiles.previous(application.filesDir), overwrite = true)
            config.writeText(KlipperConfigFile.withSavedValues(shipped.readText(), existing))
            writeConfigSource(KlipperConfigSource.SHIPPED)
            true
        }.getOrDefault(false)
    }

    /**
     * Keep a configuration this app seeded current with the one it ships.
     *
     * Runs once per start, and only while the configuration is still the app's own: a
     * configuration somebody brought is theirs, and this is the difference between an app
     * that fixes its own defaults and one that quietly edits a user's printer. The saved
     * block comes across, so this cannot cost a calibration.
     */
    private suspend fun refreshShippedConfigIfUntouched() {
        withContext(Dispatchers.IO) {
            runCatching {
                if (_configSource.value.imported) return@runCatching
                val config = KlipperHostFiles.config(application.filesDir)
                if (!config.isFile) return@runCatching
                val shipped = KlipperHostFiles.shipped(application.filesDir).takeIf { it.isFile }
                    ?: return@runCatching
                val running = config.readText()
                if (!KlipperConfigFile.differsFromShipped(running, shipped.readText())) return@runCatching
                config.writeText(KlipperConfigFile.withSavedValues(shipped.readText(), running))
                Log.i(TAG, "refreshed the printer configuration this app seeds")
            }
        }
    }

    /** The name a file has on the device it was picked from. */
    private fun fileNameOf(uri: Uri): String = runCatching {
        application.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()

    /** A file name that can only name a file: what is picked is not always named nicely. */
    private fun safeFileName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').filter { character ->
            character.isLetterOrDigit() || character in "-_. "
        }.takeIf { it.isNotBlank() } ?: "include.cfg"

    private fun readText(uri: Uri): String? = runCatching {
        application.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    /** The last [lines] lines of klippy's own log, oldest first. */
    suspend fun readHostLog(lines: Int = LOG_LINES): String? = withContext(Dispatchers.IO) {
        runCatching {
            val log = File(application.filesDir, "klippy.log")
            if (!log.isFile) return@runCatching null
            log.readLines().filter { it.isNotBlank() }.takeLast(lines).joinToString("\n")
        }.getOrNull()
    }

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

        /** How often the merged state is written down, so the screen can be checked. */
        const val STATUS_LOG_MS = 30_000L

        /** How often klippy is asked for its own state, which it never pushes. */
        const val STATE_POLL_SECONDS = 5

        /** Where the prints this app has run are written down. */
        const val HISTORY_FILE = "print-history.json"


        /** How much of klippy's log the Machine screen shows. */
        const val LOG_LINES = 200

        /** A nudge, as a feedrate in mm per minute. */
        const val DEFAULT_JOG_FEEDRATE = 3000

        /** Slow enough to watch, fast enough not to wait: 2 mm/s. */
        const val DEFAULT_EXTRUDE_FEEDRATE = 120
    }
}
