package com.tomppi.enderslicer.printer

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.tomppi.enderslicer.data.KlipperMacroLibrary
import com.tomppi.enderslicer.nativebridge.KlipperEngineService
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
 * What a sync found, or what it did.
 *
 * [differences] is what the two configurations disagree about - the preview a screen shows
 * before either direction is chosen - [copied] is what a copy moved, and [error] is why the
 * other host could not be read at all. An empty list and an error are not the same thing: one
 * says the two configurations agree, and the other that nothing could be compared.
 */
internal data class KlipperSyncResult(
    val differences: List<KlipperConfigFile.Difference> = emptyList(),
    val copied: List<KlipperConfigFile.Difference> = emptyList(),
    val error: String? = null,
    val restarted: Boolean = false,
    /**
     * How many lines the two files do not have in common at all.
     *
     * A sync moves the printer's own settings and nothing else, which leaves a real and
     * common state: two files that differ in many lines - each host's serial port, its gcodes
     * directory, its boards, its includes - and in none of the settings this button moves.
     * "Nothing was copied" must not read as "the two files are the same", so the count of
     * lines that differ is carried beside the list of settings that do.
     */
    val lineDifferences: Int? = null,
    /** When each configuration file was last written, so the two dates can be compared. */
    val deviceChangedAtMillis: Long? = null,
    val remoteChangedAtMillis: Long? = null,
)

/**
 * The computer's configuration, adapted for this device, as it would be written.
 *
 * A preview rather than a write: bringing a whole configuration over replaces the one this
 * phone runs, and what that costs - a calibration only the phone had, a print that does not
 * survive the restart - is the user's to agree to rather than the app's to assume. [text] is
 * held so the confirmation writes exactly what was shown.
 */
internal data class KlipperAdoptPreview(
    val fileName: String = KlipperHostFiles.CONFIG,
    /** What would be written to this phone: the computer's file, adapted, in full. */
    val text: String = "",
    /** The computer's own file as it was read, so a write can refuse one that moved on. */
    val sourceText: String = "",
    /** The files it includes, by their paths under the computer's config root. */
    val companions: Map<String, String> = emptyMap(),
    /** What had to change so this device could run it. */
    val changes: List<String> = emptyList(),
    /** What it asks for that this device cannot supply. */
    val warnings: List<String> = emptyList(),
    /**
     * What this phone has saved and would lose, one line per value.
     *
     * A whole file replaces a whole file, and the saved block goes with it: the probe offset
     * this phone measured, its PID terms, its mesh profiles exist only in that block. Empty
     * when the phone has saved nothing, which is also a fact the confirmation can state.
     */
    val savedValuesLost: List<String> = emptyList(),
    /** Why there is no preview at all. */
    val error: String? = null,
)

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

    /** What the playback module prints as it sweeps. */
    private val SWEEP_LINE = Regex("Testing frequency\\s+\\d+\\s*Hz")

    /** How long to let klippy reach ready before asking its boards for their timing again. */
    private val MCU_RECHECK_MS = 2_000L

    /**
     * How hard the camera list is tried before the state says it could not be read.
     *
     * A few attempts with a pause, never a loop: the seconds after a cold start are exactly
     * when a network is not up yet, and three tries cover the common case while an unreadable
     * list still ends with the truth on the screen and a Check again beside it.
     */
    private val WEBCAM_READ_TRIES = 3
    private val WEBCAM_READ_PAUSE_MS = 1_500L
    private val _consoleLines = MutableStateFlow<List<KlipperConsoleLine>>(emptyList())

    /**
     * When the printer announced the start of a resonance sweep, or null if it has not.
     *
     * The playback module prints "Testing frequency N Hz" as it sweeps, and the first of
     * those is the moment the sweep began - on the same wall clock the console stamps every
     * line with. That makes it a far better anchor than listening for the machine to shake:
     * the first seconds of a gentle sweep are close to the noise floor, and a start detected
     * late moves every frequency in the result down by that lateness times the sweep rate -
     * which was measured at seven seconds, and fourteen hertz, on a real run.
     */
    fun sweepStartMillis(sinceMillis: Long): Long? = _consoleLines.value
        .firstOrNull { line -> line.atMillis >= sinceMillis && SWEEP_LINE.containsMatchIn(line.text) }
        ?.atMillis

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

    /**
     * Which connection the state belongs to; see [KlipperConnectionGeneration].
     *
     * A reply or a push from a connection that is no longer current is dropped rather
     * than written: the host it describes is not the host the app is pointed at any more.
     */
    private val connection = KlipperConnectionGeneration()

    /** The print that is running, so that its record knows when it started. */
    private var startedAt = 0L
    private var startedFile = ""
    private var lastPrintState: String? = null
    private var client: KlipperClient? = null

    /** Which host the app is pointed at, and what it takes to reach it. */
    private val hostChoice = KlipperHostChoiceStore(application)

    /** The app's own host, when that is the one in use. */
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
        // Nothing in flight belongs to the state any more: the connection is being taken
        // down on purpose.
        connection.next()
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
                // Only the host this app owns dies with its socket. A host across the
                // network is a different machine: the phone losing its websocket says
                // nothing about the printer, which is very often still printing, and
                // announcing the print as interrupted is a lie the user acts on - it
                // also left a bogus history record and reset the state the next snapshot
                // is read against. The runCatching is only so a preference that cannot
                // be read does not swallow the reconnect; on that failure the
                // conservative answer is "no print was lost here", and a print that
                // really did die locally is reported by the reconnect's first snapshot.
                val lostWithThisHost = runCatching {
                    KlipperPrintLoss.lostWithTheHost(hostChoice.load())
                }.getOrDefault(false)
                if (lostWithThisHost) recordPrintLostWithTheHost(e.message)
                _state.update {
                    it.copy(connected = false, error = e.message, hostLogTail = readHostLogTail())
                }
            }
            delay(RETRY_MS)
        }
    }

    /**
     * A print that was running when the host went away is over, and says so.
     *
     * Three things kill the host this app owns: a klippy crash, the app's own wedge detector,
     * and a printer re-attach. The new process starts with no file and print_stats in standby,
     * so the transition the history and the notification are built on - to complete, cancelled
     * or error - never happens. Half a part is left on the bed and, if the phone is in a
     * pocket, nothing at all is said about it.
     *
     * Recording it as interrupted is the honest version: the print did not finish, was not
     * cancelled, and the reason is whatever the connection said when it went.
     *
     * Two callers, and neither is a guess. A drop on the local route calls it directly,
     * because there the socket dying is the print dying - and that call clears
     * [lastPrintState], so the reconnect cannot report the same loss twice. A reconnect calls
     * it when the host came back with no print at all while the app still believed one was
     * running ([believedRunning]); that is a host that restarted, and it is the one case the
     * app has to name for itself. A host with a print, or with a state klippy names for
     * itself, is never touched from here.
     */
    private fun recordPrintLostWithTheHost(reason: String?, believedRunning: Boolean = false) {
        val current = _state.value
        val wasRunning = believedRunning || current.isPrinting || current.isPaused ||
            lastPrintState == "printing" || lastPrintState == "paused"
        if (!wasRunning) return
        val name = startedFile.ifBlank { current.printFileName.orEmpty() }
        _history.value = printHistory.append(
            KlipperPrintRecord(
                fileName = name,
                startedAtMillis = startedAt.takeIf { it > 0 }
                    ?: (System.currentTimeMillis() - ((current.printDurationSeconds ?: 0.0) * 1000).toLong()),
                durationSeconds = current.printDurationSeconds ?: 0.0,
                filamentMillimetres = current.printFilamentUsed ?: 0.0,
                outcome = "interrupted",
                layers = current.printLayers?.current,
            ),
        )
        startedAt = 0L
        startedFile = ""
        lastPrintState = "standby"
        KlipperNotifications.printInterrupted(application, name.takeIf { it.isNotBlank() }, reason)
    }

    /** Connect, take a snapshot, subscribe, then hold the connection until it drops. */
    private suspend fun follow() {
        //
        // Which host this is, is decided here and nowhere else: klippy inside the phone over its
        // unix socket, or klippy on a computer over Moonraker. Everything downstream - the
        // screens, the protocol, the framing - is the same either way, which is what the
        // transport is for.
        //
        val choice = hostChoice.load()
        if (!choice.isUsable) {
            _state.update { it.copy(connected = false, error = "No host is set for the PC route") }
            error("no Klipper host is set")
        }
        // This connection's own number. Everything below checks it before writing to the
        // state, so a reply from the host the app has just left cannot land on the host it
        // is pointed at now.
        val mine = connection.next()
        // And the state starts from no printer, naming the host being connected to: a tab
        // must never be able to render one host's print under another host's name while
        // the new connection is still coming up.
        _state.update { it.withoutHost(choice) }
        val c = KlipperClient(choice.transportFor(socketPath), choice.label)
        // The session owns its client. However this ends - the host going away, a request
        // that fails, the app switching hosts - the socket it opened is closed here rather
        // than left for the next connection to replace. Abandoned, it was a socket nobody
        // closed: the host saw it die with no close frame long afterwards, and every
        // reconnect leaked one.
        try {
            session(c = c, choice = choice, mine = mine)
        } finally {
            runCatching { c.close() }
            if (client === c) client = null
        }
    }

    /**
     * One connection's whole life: connect, take the snapshot, subscribe, then hold it until
     * it drops. Every write it makes to the state is checked against [mine], so a reply from
     * a connection the app has since left is dropped rather than shown.
     */
    private suspend fun session(c: KlipperClient, choice: KlipperHostChoice, mine: Long) {
        // A push from the connection being replaced is dropped rather than applied: the
        // callback belongs to the client, and the client outlives its turn as the current
        // one by however long a socket takes to notice it was closed.
        c.onNotification = { message ->
            if (connection.isCurrent(mine)) applyNotification(message)
        }
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
        // What the app believed about the print before this connection was made. The drop
        // that ended the last one deliberately left that belief alone on the remote route,
        // and the first snapshot is what settles it - see [KlipperPrintLoss].
        val printWasRunning = lastPrintState == "printing" || lastPrintState == "paused"
        updateIfCurrent(mine) { previous ->
            val next = previous.copy(
                connected = true,
                remoteHost = choice.host.trim().takeIf { choice.isRemote },
                hostLabel = choice.label,
                state = info.optString("state", "unknown"),
                stateMessage = info.optString("state_message"),
                error = null,
                host = KlipperHost.from(info),
                published = published,
            ).withStatus(snapshot)
            // The same notification a push would post: a print that ended while the phone
            // was disconnected is news, and this is the first the app hears of it.
            reportPrintEnd(previous, next)
            next
        }
        // The first snapshot goes through the same transitions a push does. A print that
        // ended while the phone was away is reported here, once, with klippy's own state
        // and message - and a print that is still going says nothing, which is the point.
        if (connection.isCurrent(mine)) {
            recordPrintIfItEnded()
            if (KlipperPrintLoss.afterReconnect(
                    printWasRunning = printWasRunning,
                    printState = _state.value.printState,
                    printFileName = _state.value.printFileName,
                ) == KlipperPrintLoss.AfterReconnect.HostRestarted
            ) {
                recordPrintLostWithTheHost(
                    "the host restarted while the print was running",
                    believedRunning = true,
                )
            }
        }
        // The host's cameras, asked for once the connection is up - never before it. Before
        // it, the first seconds of a cold start (a tailnet not up yet, a Moonraker still
        // coming up) turned a request that failed into "this host has no cameras", and the
        // empty answer stood for the whole session. Every session asks again, so a reconnect
        // heals it, and the read itself tries a few times before it will say it could not ask.
        scope.launch { readWebcams(choice, mine) }
        Log.i(TAG, "watching " + choice.label + " as " + info.optString("state") +
            "; following " + wanted.size + " of " + published.size + " objects")
        runCatching { c.gcodeHelp() }.onSuccess { help -> _commands.value = help }
        // The micro-controller objects grow. A subscription that asks for every field has
        // klippy freeze that object's key list when the connection is made, and last_stats is
        // only published once the ready-time stats timer has run - so a connection made in the
        // window before that would never hear about the timing at all, and the Dashboard's
        // host link card and the per-board timing would stay empty for the whole session.
        // Asking once more, after the printer has had time to come up, fills in what was not
        // there when the connection opened.
        val boards = published.filter { it == "mcu" || it.startsWith("mcu ") }
        if (boards.isNotEmpty()) {
            delay(MCU_RECHECK_MS)
            runCatching { c.query(*boards.toTypedArray()) }.getOrNull()?.let { status ->
                updateIfCurrent(mine) { previous ->
                    val next = previous.withStatus(status)
                    reportPrintEnd(previous, next)
                    next
                }
            }
        }
        // A print that has stopped says so out loud as well as on the screen: the user is
        // usually not looking, and the reason klippy gives is the thing they need.
        //
        // klippy has no state notification on the wire, so the state that decides
        // whether the screen is allowed to print is asked for rather than waited on.
        var sinceStateCheck = 0
        while (c.isConnected && currentCoroutineContext().isActive) {
            delay(1000)
            if (++sinceStateCheck >= STATE_POLL_SECONDS) {
                sinceStateCheck = 0
                runCatching { c.info() }.getOrNull()?.let { info ->
                    updateIfCurrent(mine) {
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
        // Moonraker's own push, not klippy's: the host's camera list changed. A camera plugged
        // in, or a [webcam] section edited, is news while the app is connected - the list used
        // to be read once per session and never again, so it needed a relaunch to appear.
        if (message.optString("method") == WEBCAMS_CHANGED_METHOD) {
            refreshWebcams()
            return
        }
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
            _state.update { previous ->
                val next = previous.withStatus(status)
                reportPrintEnd(previous, next)
                next
            }
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

    /** A fan the configuration named itself. */
    fun setGenericFan(name: String, speed: Double) =
        send(KlipperScripts.genericFan(name, speed))

    /**
     * The toolhead's limits, live.
     *
     * The way to calm a print that is shaking without stopping it, and to speed one up that
     * is being held back. The change lasts until the host restarts and re-reads printer.cfg.
     */
    fun setVelocityLimits(
        maxVelocity: Double? = null,
        maxAccel: Double? = null,
        squareCornerVelocity: Double? = null,
    ) = send(KlipperScripts.velocityLimit(maxVelocity, maxAccel, squareCornerVelocity))

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

    /** Play the resonance sweep on one axis, for the phone's own sensor to measure. */
    fun playResonances(
        axis: String,
        freqStart: Double,
        freqEnd: Double,
        hzPerSec: Double,
        accelPerHz: Double = KlipperScripts.STANDARD_ACCEL_PER_HZ,
    ) = send(KlipperScripts.playResonances(axis, freqStart, freqEnd, hzPerSec, accelPerHz))

    /** Ask the printer what shaping it is using; it answers in the console. */
    fun reportShapers() = send("SET_INPUT_SHAPER")

    /** Write shaping into the printer's own configuration, where a restart finds it. */
    internal suspend fun saveShapers(settings: List<KlipperConfigFile.ShaperSetting>): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = readHostConfig() ?: run {
                    reportConfigWriteFailure("the input shaper")
                    return@runCatching false
                }
                val ok = writeHostConfig(KlipperConfigFile.withInputShaper(text, settings))
                if (!ok) reportConfigWriteFailure("the input shaper")
                ok
            }.getOrDefault(false)
        }

    /**
     * The extruder calibration: mark the filament, push a known length, measure.
     *
     * The one number in this printer's chain that decides whether what the slicer asks for is
     * what comes out, and the configuration's own comment asks for the measurement. Klippy
     * holds the value in memory when it is set, so it is written into the file here as well -
     * SAVE_CONFIG would not, and the next restart would put the old figure back.
     */
    fun extrudeForCalibration(lengthMm: Double) =
        send(KlipperScripts.extrudeForCalibration(lengthMm))

    /** Set the extruder's rotation distance, live. */
    fun applyRotationDistance(distance: Double) =
        send(KlipperScripts.rotationDistance("extruder", distance))

    /** Ask klippy what it is using; it answers in the console. */
    fun askRotationDistance() = send(KlipperScripts.rotationDistance("extruder"))

    /**
     * Write pressure advance into printer.cfg, where the next start reads it.
     *
     * SET_PRESSURE_ADVANCE changes the value klippy is using and nothing else: its command sets
     * the value and calls set_rollover_info, and never marks the configuration as needing a save
     * (kinematics/extruder.py:93-104). So SAVE_CONFIG has nothing to write for it, and the next
     * restart quietly brings the old figure back.
     *
     * The file is the only thing that keeps it - which is why tuning it during a print, the way
     * it is normally tuned, ends with the printer forgetting what was tuned.
     */
    internal suspend fun savePressureAdvance(advance: Double, smoothTime: Double? = null): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = readHostConfig() ?: run {
                    reportConfigWriteFailure("the pressure advance")
                    return@runCatching false
                }
                var written = KlipperConfigFile.withOption(
                    text,
                    "extruder",
                    "pressure_advance",
                    String.format(java.util.Locale.ROOT, "%.4f", advance),
                )
                smoothTime?.let { time ->
                    written = KlipperConfigFile.withOption(
                        written,
                        "extruder",
                        "pressure_advance_smooth_time",
                        String.format(java.util.Locale.ROOT, "%.3f", time),
                    )
                }
                if (written == text) return@runCatching false
                val ok = writeHostConfig(written)
                if (!ok) reportConfigWriteFailure("the pressure advance")
                ok
            }.getOrDefault(false)
        }

    /** Write the rotation distance into printer.cfg, where the next start reads it. */
    internal suspend fun saveRotationDistance(distance: Double): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = readHostConfig() ?: run {
                    reportConfigWriteFailure("the rotation distance")
                    return@runCatching false
                }
                val written = KlipperConfigFile.withOption(
                    text,
                    "extruder",
                    "rotation_distance",
                    String.format(java.util.Locale.ROOT, "%.3f", distance),
                )
                if (written == text) return@runCatching false
                val ok = writeHostConfig(written)
                if (!ok) reportConfigWriteFailure("the rotation distance")
                ok
            }.getOrDefault(false)
        }

    /**
     * Write the mesh's probe count into printer.cfg, where the next start reads it.
     *
     * A configuration option rather than a command: klippy builds the probe grid from it when
     * the file is read, so this is the only way to change it and the change waits for the
     * restart that follows a configuration write. The mesh area is a separate pair of options
     * and is left as it is.
     */
    internal suspend fun saveMeshProbeCount(points: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = readHostConfig() ?: run {
                    reportConfigWriteFailure("the mesh probe count")
                    return@runCatching false
                }
                val written = KlipperConfigFile.withOption(
                    text,
                    "bed_mesh",
                    "probe_count",
                    "$points,$points",
                )
                if (written == text) return@runCatching false
                val ok = writeHostConfig(written)
                if (!ok) reportConfigWriteFailure("the mesh probe count")
                ok
            }.getOrDefault(false)
        }

    /**
     * What the two hosts' configurations disagree about.
     *
     * Both are read whichever host the app is driving: this phone's own file is in the app's
     * storage, and the computer's is read from Moonraker at the host stored for it. Either
     * being unreadable is [KlipperSyncResult.error] rather than an empty list, because
     * "nothing differs" and "nothing could be compared" must not look the same on screen.
     */
    internal suspend fun syncDifferences(): KlipperSyncResult = withContext(Dispatchers.IO) {
        runCatching {
            when (val sources = syncSources()) {
                is SyncSources.Failed -> KlipperSyncResult(error = sources.reason)
                is SyncSources.Both -> KlipperSyncResult(
                    differences = KlipperConfigFile.differences(sources.device, sources.remote),
                    lineDifferences = KlipperConfigFile.lineDifferences(
                        sources.device,
                        sources.remote,
                    ),
                    deviceChangedAtMillis = sources.deviceChangedAtMillis,
                    remoteChangedAtMillis = sources.remoteChangedAtMillis,
                )
            }
        }.getOrElse { error ->
            KlipperSyncResult(error = error.message ?: "the configurations could not be read")
        }
    }

    /**
     * Copy the printer's own settings from this phone's configuration to the computer's.
     *
     * One direction only, and it is the one nothing else can do: what klippy saved here - the
     * probe offset, the PID terms, a mesh profile - exists in this phone's file and nowhere
     * else, and the printer is on this phone's cable. The other direction used to copy those
     * same values into the phone, which is what left a phone host able to start only if its
     * file already described the printer; a configuration reaches this phone whole now, by
     * [adoptComputerConfig].
     *
     * The two configurations describe one machine and have to agree about it: the calibrations
     * klippy saved, the extruder's own figures, the motion limits. They have to differ in how
     * each host reaches the machine - the serial port, the gcode directory, the [mcu] sections,
     * the includes - and none of that is ever touched. [KlipperConfigFile.withSynced] is the
     * whole of that rule.
     *
     * A configuration written for the computer is followed by a restart there, because it is
     * not the running one until the computer reads it again.
     */
    internal suspend fun copySettingsToComputer(): KlipperSyncResult =
        withContext(Dispatchers.IO) {
            runCatching {
                when (val sources = syncSources()) {
                    is SyncSources.Failed -> KlipperSyncResult(error = sources.reason)
                    is SyncSources.Both -> {
                        val target = sources.remote
                        val source = sources.device
                        // In the preview's order: the phone's value first, the computer's
                        // second. Only the count is shown today, but a list rendered in the
                        // other order would read as the arrow pointing the wrong way.
                        val differences = KlipperConfigFile.differences(source, target)
                        val synced = KlipperConfigFile.withSynced(target, source)
                        // Every outcome carries both files' times, so the card can say which one
                        // was written last.
                        val outcome = if (synced == target) {
                            KlipperSyncResult(differences = differences)
                        } else {
                                // Read when the card was prepared, written now: a SAVE_CONFIG on
                                // the computer, an edit in Fluidd or another screen of this app
                                // in between would be silently overwritten by a whole-file
                                // replace. Reading it once more is the check that costs a read.
                                val now = when (val read = sources.files.readConfig()) {
                                    is MoonrakerFiles.ConfigRead.Found -> read.text
                                    MoonrakerFiles.ConfigRead.Missing -> ""
                                    MoonrakerFiles.ConfigRead.Unreachable ->
                                        throw IllegalStateException(
                                            "PC Klipper stopped answering before it could be written to",
                                        )
                                }
                                if (now != sources.remote) {
                                    throw IllegalStateException(
                                        "PC Klipper's configuration changed while this was being " +
                                            "prepared; look at the differences again and repeat",
                                    )
                                }
                            KlipperSyncResult(
                                differences = differences,
                                copied = differences,
                                restarted = writeRemoteConfig(sources.files, synced, sources.remote),
                            )
                        }
                        outcome.copy(
                            lineDifferences = KlipperConfigFile.lineDifferences(
                                sources.device,
                                sources.remote,
                            ),
                            deviceChangedAtMillis = sources.deviceChangedAtMillis,
                            remoteChangedAtMillis = sources.remoteChangedAtMillis,
                        )
                    }
                }
            }.getOrElse { error ->
                KlipperSyncResult(error = error.message ?: "the configuration could not be written")
            }
        }

    /**
     * Read the computer's configuration and work out what this device would need changed.
     *
     * The whole file is brought, not the whitelist a sync moves: the point is that a user who
     * runs the printer from a computer can run the same printer from the phone without
     * hand-editing anything, and that means the pins, the kinematics, the probe, the macros -
     * everything the computer's file has - with only the parts that describe the computer
     * itself replaced. [KlipperConfigFile.forDevice] is that rule, and it is the same one the
     * file-picker import uses; this only saves the user from finding the file on the phone.
     *
     * Nothing is written here.
     */
    internal suspend fun previewAdoptingComputerConfig(): KlipperAdoptPreview =
        withContext(Dispatchers.IO) {
            runCatching {
                val files = computerFiles() ?: throw IllegalStateException(
                    "no computer is set: choose PC Klipper on the Print tab and give its address first",
                )
                val main = when (val read = files.readConfig()) {
                    is MoonrakerFiles.ConfigRead.Found -> read.text
                    MoonrakerFiles.ConfigRead.Missing ->
                        throw IllegalStateException("PC Klipper has no printer.cfg to copy")
                    MoonrakerFiles.ConfigRead.Unreachable ->
                        throw IllegalStateException("PC Klipper did not answer")
                }
                adoptPreview(main, fetchIncludes(files, main))
            }.getOrElse { error ->
                KlipperAdoptPreview(
                    error = error.message ?: "PC Klipper's configuration could not be read",
                )
            }
        }

    /**
     * Write the previewed configuration over this phone's own, and keep what it replaced.
     *
     * The computer is asked for its file once more first: a SAVE_CONFIG over there, or an edit
     * in Fluidd, between the preview and the confirmation would otherwise be silently
     * overwritten by a whole-file replace - and the preview the user agreed to would not be
     * what was written. A file that changed is a refusal with something to do about it, not a
     * write.
     *
     * The files the configuration includes are written before the configuration itself, so
     * there is no moment in which klippy could be started against a file whose includes are
     * not there yet. The configuration the app shipped, or the one that was running, is kept
     * as printer.cfg.previous; the source marker makes this file the user's, so no app update
     * ever replaces it with the shipped template.
     */
    internal suspend fun adoptComputerConfig(preview: KlipperAdoptPreview): Result<KlipperImportResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val files = computerFiles() ?: throw IllegalStateException("no computer is set")
                val now = when (val read = files.readConfig()) {
                    is MoonrakerFiles.ConfigRead.Found -> read.text
                    MoonrakerFiles.ConfigRead.Missing -> ""
                    MoonrakerFiles.ConfigRead.Unreachable -> throw IllegalStateException(
                        "PC Klipper stopped answering before it could be written",
                    )
                }
                if (now != preview.sourceText) {
                    throw IllegalStateException(
                        "PC Klipper's configuration changed while this was being prepared; " +
                            "check the changes again and repeat",
                    )
                }
                val directory = KlipperHostFiles.directory(application.filesDir).apply { mkdirs() }
                preview.companions.forEach { (path, text) -> writeCompanion(directory, path, text) }
                writeDeviceConfig(preview.text)
                writeConfigSource(
                    KlipperConfigSource(
                        imported = true,
                        name = "PC Klipper's " + preview.fileName,
                        atMillis = System.currentTimeMillis(),
                    ),
                )
                KlipperImportResult(
                    fileName = preview.fileName,
                    changes = preview.changes,
                    warnings = preview.warnings,
                    companions = preview.companions.keys.toList(),
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message) }
            }
        }

    /**
     * The computer's configuration, adapted: the text that would be written and what changed.
     *
     * The saved block is the computer's and comes with it: adopting a configuration is taking
     * that printer's own figures - the probe's offset, the PID terms, the shaper frequencies -
     * not this phone's idea of them.
     */
    private fun adoptPreview(main: String, companions: Map<String, String>): KlipperAdoptPreview {
        // The app's own two files are written by the host start whatever the configuration
        // says, so an include naming them is satisfied even though the computer's file does
        // not bring them.
        val available = companions.keys.toMutableSet()
        available += KlipperHostFiles.APP_CONFIG
        available += KlipperHostFiles.KAMP_SETTINGS
        val rewrite = KlipperConfigFile.forDevice(
            text = main,
            serialPath = KlipperHostFiles.pty(application.filesDir).absolutePath,
            gcodeDirectory = gcodeDirectory().absolutePath,
            availableFiles = available,
        )
        return KlipperAdoptPreview(
            text = KlipperConfigFile.withHostIncludes(
                KlipperConfigFile.withSavedValues(rewrite.text, main),
            ),
            sourceText = main,
            companions = companions,
            changes = rewrite.changes,
            warnings = rewrite.warnings,
            savedValuesLost = deviceConfigText()?.let(KlipperConfigFile::savedValues).orEmpty(),
        )
    }

    /**
     * Every file the computer's configuration includes, fetched, by path under its config root.
     *
     * The closure rather than the first level: KAMP_Settings.cfg includes its macros by
     * relative path, and a configuration that arrived without them would have those includes
     * commented out by forDevice - the app telling the user it could not bring macros it had
     * just brought. A file that cannot be fetched is left out and named in the warnings, which
     * is where the user sees it.
     */
    private fun fetchIncludes(files: MoonrakerFiles, main: String): Map<String, String> =
        KlipperConfigFile.includeClosure(main) { path -> files.configText(path) }

    /**
     * One of the files a configuration includes, written where it is looked for.
     *
     * The name is a path under the config root, so a file in a subdirectory keeps its place -
     * an include that says ./KAMP/Line_Purge.cfg is resolved from the directory of the file
     * that names it, and a companion flattened into the root would not be found there.
     */
    private fun writeCompanion(directory: File, path: String, text: String) {
        val safe = KlipperConfigFile.companionPath(path) ?: return
        val file = File(directory, safe.split('/').joinToString("/") { safeFileName(it) })
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    /**
     * Read the chosen host's cameras and put them in the state, or say they could not be read.
     *
     * Bounded: a few attempts with a pause, never a loop. A failure is written as null - "the
     * host has not said" - unless cameras are already known, because one failed request is not
     * the host having unplugged its camera. Every write is checked against [token], so a list
     * that arrives after the app has switched hosts is dropped rather than shown.
     */
    private suspend fun readWebcams(choice: KlipperHostChoice, token: Long) {
        if (!choice.isRemote) {
            // The app's own host has no Moonraker to ask, so an empty list is the fact about
            // it rather than a failure to ask - and the card says there is no camera here.
            updateIfCurrent(token) { it.copy(webcams = emptyList()) }
            return
        }
        val webcams = MoonrakerWebcams(
            host = choice.host.trim(),
            port = choice.port,
            apiKey = choice.apiKey.takeIf { it.isNotBlank() },
        )
        var read: List<KlipperWebcam>? = null
        for (attempt in 0 until WEBCAM_READ_TRIES) {
            if (!connection.isCurrent(token)) return
            read = withContext(Dispatchers.IO) { webcams.list() }
            if (read != null) break
            if (attempt < WEBCAM_READ_TRIES - 1) delay(WEBCAM_READ_PAUSE_MS)
        }
        updateIfCurrent(token) { current ->
            when {
                read != null -> current.copy(webcams = read)
                // Nothing known yet: the honest answer is that the host has not said.
                current.webcams.isNullOrEmpty() -> current.copy(webcams = null)
                else -> current
            }
        }
    }

    /**
     * Ask the host for its cameras again, now.
     *
     * What the camera card's Check again does, and what Moonraker's own webcams-changed push
     * does: the list is not a constant of the connection, and a camera configured or plugged
     * in after the app connected used to need a relaunch to appear.
     */
    fun refreshWebcams() {
        val choice = hostChoice.load()
        val token = connection.currentToken()
        scope.launch { readWebcams(choice, token) }
    }

    /**
     * Write to the state only while [token] is still the current connection.
     *
     * The check is made twice - once before the update and once inside it - because a state
     * update can run its lambda more than once and the connection can be replaced in between.
     * A write from a connection that is not the current one is dropped: the host it describes
     * is not the host the app is pointed at any more.
     */
    private inline fun updateIfCurrent(
        token: Long,
        update: (KlipperPrinterState) -> KlipperPrinterState,
    ) {
        if (!connection.isCurrent(token)) return
        _state.update { current -> if (connection.isCurrent(token)) update(current) else current }
    }

    /**
     * One frame from a camera, fetched when a screen asks for one.
     *
     * On demand rather than pushed: the screens that show a picture show one frame a second
     * while they are open, and a stream the app is not looking at is bandwidth spent on
     * nothing.
     */
    suspend fun webcamSnapshot(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val choice = hostChoice.load()
        if (!choice.isRemote) return@withContext null
        MoonrakerWebcams(
            host = choice.host.trim(),
            port = choice.port,
            apiKey = choice.apiKey.takeIf { it.isNotBlank() },
        ).snapshot(url)
    }

    /** Tell the phone how a print ended, once, at the moment it stops being a print. */
    private fun reportPrintEnd(previous: KlipperPrinterState, next: KlipperPrinterState) {
        if (!KlipperNotifications.endedBetween(previous.printState, next.printState)) return
        KlipperNotifications.printEnded(
            context = application,
            fileName = next.printFileName,
            printState = next.printState.orEmpty(),
            message = next.printMessage,
        )
    }

    /**
     * Add the starter macros this printer does not have, and say which were added.
     *
     * The configuration is the user's, so this adds and never rewrites: a macro that is
     * already defined is left alone whatever it says, which is why the missing list is worked
     * out first. The file is backed up as previous.printer.cfg, as every config write here is.
     */
    internal suspend fun addStarterMacros(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val text = readHostConfig() ?: run {

                reportConfigWriteFailure("the starter macros")

                return@runCatching emptyList()

            }
            val missing = KlipperMacroLibrary.missingFrom(text)
            if (missing.isEmpty()) return@runCatching emptyList()
            val written = writeHostConfig(
                KlipperConfigFile.withSections(text, missing.map { it.section }),
            )
            if (!written) {

                reportConfigWriteFailure("the starter macros")

                return@runCatching emptyList()

            }
            missing.map { it.name }
        }.getOrDefault(emptyList())
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

    /**
     * Steppers on, by name.
     *
     * M17 does not exist in klippy - it answered "Unknown command" while the console showed it
     * as sent, so the button did nothing at all. The names come from the printer's own
     * stepper_enable status, which knows every enable line in its configuration.
     */
    fun enableMotors() {
        val steppers = _state.value.obj("stepper_enable")?.optJSONObject("steppers")
            ?.keys()?.asSequence()?.toList().orEmpty()
        if (steppers.isEmpty()) return
        send(KlipperScripts.enableSteppers(steppers))
    }

    /** Leave one of the file's objects out of the print that is running. */
    fun excludeObject(name: String) = send("EXCLUDE_OBJECT NAME=" + KlipperScripts.quoted(name))

    /** Write klippy's saved values into the config file; it restarts to apply them. */
    fun saveConfig() = send("SAVE_CONFIG")

    /**
     * Drop the connection, so the watch loop makes a new one.
     *
     * The host is chosen before a connection is opened, so a user who changes it is owed a
     * connection to the new one rather than a wait for the old one to fail.
     */
    fun reconnect() {
        // Whatever is in flight belongs to the host being left, and it is not the current
        // connection from this moment: a reply already on its way is dropped rather than
        // written onto the new host's state.
        connection.next()
        runCatching { client?.close() }
        client = null
        // The print the app was following belonged to the host being left, and so did the
        // belief that one was running. Leaving it standing would let the new host's first
        // failure report the OLD host's print as interrupted - which is the same leak one
        // step further on: the app has to forget a print when it stops watching the machine
        // that was printing it.
        startedAt = 0L
        startedFile = ""
        lastPrintState = null
        // And the state itself starts again describing no printer, naming the host being
        // connected to. Clearing only "connected" and "error" left the previous host's
        // print, numbers and identity on screen - which is how the Device tab came to show
        // the PC's cancelled print with "The Klipper host on <pc>" above it.
        _state.update { it.withoutHost(hostChoice.load()) }
    }

    /**
     * Restart the host that is doing the printing.
     *
     * On this device that means the service: klippy is a process the app started and can stop.
     * On a computer it means asking klippy to restart itself, which is a G-code command - and
     * the one that applies a SAVE_CONFIG on that side, where the app cannot reach the service
     * at all. Both leave the printer in the same place; only the asking differs.
     */
    fun restartHost() {
        if (hostChoice.load().isRemote) {
            send("RESTART")
            return
        }
        KlipperEngineService.restart(application)
    }

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
                val fileName = KlipperPrint.fileName(name)
                val source = File(sourcePath)
                //
                // Where the file has to be is the only difference between the two routes: the
                // app's own host reads a directory in this app's storage, and a host on the
                // network has to be sent the file. The command that prints it is klippy's own
                // either way, because either way the name is one the host already knows.
                //
                val remote = remoteFiles()
                if (remote != null) {
                    if (!remote.upload(source, fileName)) {
                        throw IllegalStateException("the host refused the upload of $fileName")
                    }
                } else {
                    copyForPrinting(source, File(gcodeDirectory(), fileName))
                }
                // The machine's live state is cleared before the file is loaded, not by the
                // file: klippy keeps the Z offset and the M220/M221 factors across prints, and
                // the start G-code of a file the user already has will not have been told to.
                sendScript(KlipperScripts.resetLiveOverrides())
                val command = "SDCARD_PRINT_FILE FILENAME=" + KlipperScripts.quoted(fileName)
                append(command, KlipperConsoleLine.Source.SENT)
                if (client == null) {
                    // The upload got through and the connection went under it. The file is on
                    // the host, so this is worth one reconnect rather than an error that reads
                    // like the host refused something it actually accepted.
                    reconnect()
                }
                val connected = client ?: throw IllegalStateException(
                    "the file is on the printer's host, but the connection dropped - print " +
                        "it from Files, which does not upload it again",
                )
                connected.gcodeAsync(command)
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
        remoteFiles()?.list() ?: KlipperGcodeFiles.list(gcodeDirectory())
    }

    /**
     * Put a file where the printer will read it, or leave nothing behind.
     *
     * A plain copy is what this used to be, and it has two ways to go wrong that look like
     * something else: a copy that fails partway leaves a truncated .gcode in the directory the
     * file list reads - which then prints as far as it goes and is reported as a finished
     * print, because klippy treats the end of the file as the end of the job - and a full disk
     * fails at some arbitrary point inside the copy rather than before it.
     *
     * So: room checked first, written under a name the file list does not read, length checked,
     * then renamed into place. The temporary is deleted whichever way it goes.
     */
    private fun copyForPrinting(source: File, target: File) {
        val room = target.parentFile?.usableSpace ?: Long.MAX_VALUE
        if (source.length() > 0 && room < source.length() + UPLOAD_SPARE_BYTES) {
            throw IllegalStateException(
                "not enough room for ${target.name}: " +
                    "${source.length() / 1024} KB needed, ${room / 1024} KB free",
            )
        }
        val partial = File(target.parentFile, target.name + UPLOAD_SUFFIX)
        try {
            source.copyTo(partial, overwrite = true)
            if (partial.length() != source.length()) {
                throw IllegalStateException("the copy of ${target.name} was incomplete")
            }
            if (!partial.renameTo(target)) {
                throw IllegalStateException("could not put ${target.name} in place")
            }
        } finally {
            // Renamed away on success, so this only fires on a failure.
            if (partial.exists()) partial.delete()
        }
    }

    /** One script, to the printer and to the console the user reads. */
    private suspend fun sendScript(script: String) {
        append(script, KlipperConsoleLine.Source.SENT)
        // Off the main thread. gcodeAsync writes to the socket, and a write that actually
        // reaches the socket raises NetworkOnMainThreadException - but only when the buffer
        // happens to flush, which is why starting a print worked most of the time and
        // crashed the rest. Both callers reach here from a UI coroutine; wrapping the one
        // place they share is what protects the next caller too.
        withContext(Dispatchers.IO) {
            client?.gcodeAsync(script) ?: throw IllegalStateException("not connected")
        }
    }

    /** Start one of those files printing, by the name the printer knows it by. */
    suspend fun printGcodeFile(name: String) {
        val fileName = KlipperPrint.fileName(name)
        sendScript(KlipperScripts.resetLiveOverrides())
        append("SDCARD_PRINT_FILE FILENAME=" + KlipperScripts.quoted(fileName), KlipperConsoleLine.Source.SENT)
        withContext(Dispatchers.IO) {
            runCatching {
                client?.gcodeAsync("SDCARD_PRINT_FILE FILENAME=" + KlipperScripts.quoted(fileName))
                    ?: throw IllegalStateException("not connected")
            }.onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    /** Delete one of those files. Confined to the printer's own directory. */
    suspend fun deleteGcodeFile(name: String): Boolean = withContext(Dispatchers.IO) {
        remoteFiles()?.let { files -> return@withContext files.delete(KlipperPrint.fileName(name)) }
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
        remoteFiles()?.let { files ->
            val encoded = files.metadata(KlipperPrint.fileName(name))?.thumbnail ?: return@withContext null
            return@withContext runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        runCatching {
            val file = File(gcodeDirectory(), KlipperPrint.fileName(name))
            val encoded = KlipperGcodeFiles.read(file).thumbnail ?: return@runCatching null
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /**
     * The file API of the chosen host, or null when the host is this device.
     *
     * Every file operation goes through this: the app's own storage and a computer's are two
     * different places, and the only thing that decides which is the host choice.
     */
    private fun remoteFiles(): MoonrakerFiles? {
        if (!hostChoice.load().isRemote) return null
        return computerFiles()
    }

    /**
     * The computer's file API, whichever host the app is driving.
     *
     * remoteFiles is "the host in use, if it is the computer": every write follows the host
     * choice. Adopting the computer's configuration is the one read that must not, because it
     * runs on the Phone Klipper route - the file is being brought here - and the computer's
     * host, port and key are still stored from the last time it was chosen.
     */
    private fun computerFiles(): MoonrakerFiles? {
        val choice = hostChoice.load()
        if (choice.host.isBlank()) return null
        return MoonrakerFiles(
            host = choice.host.trim(),
            port = choice.port,
            apiKey = choice.apiKey.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The chosen host's configuration, as text.
     *
     * Three writers in this class change the printer's own configuration - shaping, the
     * extruder's rotation distance, the starter macros - and where that configuration *is*
     * depends on which host is printing. Both were this app's own file for one release, which
     * is why a rotation distance measured while driving the computer landed on the phone:
     * saved, confirmed by the app, and nowhere near the printer.
     */
    private suspend fun readHostConfig(): String? = withContext(Dispatchers.IO) {
        val remote = remoteFiles()
        resolveConfigText(
            remoteConfigured = remote != null,
            deviceText = { deviceConfigText() },
            remoteText = { remote?.configText() },
        )
    }

    /**
     * A configuration write that did not happen must not read like one with nothing to change.
     *
     * Every caller discarded the Boolean: a refused or failed write looked exactly like a file
     * that was already in order, so the screen said nothing and the value was silently not saved.
     */
    private fun reportConfigWriteFailure(what: String) {
        _state.update {
            it.copy(error = "$what was not written: the configuration of the host in use was not updated")
        }
    }

    /** This device's own configuration, or null when it has none yet. */
    private fun deviceConfigText(): String? =
        KlipperHostFiles.config(application.filesDir).takeIf { it.isFile }?.readText()

    /**
     * Write a configuration where the host will read it, and make the host read it.
     *
     * The device route keeps the file beside the app, as it always has. The computer route
     * sends it to the machine that is holding the printer - Moonraker's config root is the
     * directory klippy starts from - keeps what was there as previous.printer.cfg, and asks
     * for a restart, because a written configuration is not the running one until it does.
     */
    private suspend fun writeHostConfig(text: String): Boolean = withContext(Dispatchers.IO) {
        val remote = remoteFiles()
        if (remote == null) {
            writeDeviceConfig(text)
            return@withContext true
        }
        // Ask the same host this write is aimed at for its configuration. A host with none yet
        // is a host to write to; one that cannot be asked is not, and refusing there is what
        // stops a failed read becoming this device's file on the computer with a restart
        // behind it.
        val before = when (val read = remote.readConfig()) {
            is MoonrakerFiles.ConfigRead.Found -> read.text
            MoonrakerFiles.ConfigRead.Missing -> null
            MoonrakerFiles.ConfigRead.Unreachable -> {
                reportConfigWriteFailure("the configuration")
                return@withContext false
            }
        }
        if (!before.isNullOrBlank()) {
            // A name of its own per write: two writers sharing cacheDir/printer.cfg could upload
            // each other's configuration, with a restart behind it.
            val previous = File.createTempFile("previous", ".printer.cfg", application.cacheDir)
            previous.writeText(before)
            // Kept on the host as well: a backup that only exists on the phone cannot be
            // restored there by hand, which is the point of keeping it beside the file.
            remote.uploadConfig(previous, name = "previous.printer.cfg")
            previous.delete()
        }
        val staged = File.createTempFile("staged", ".printer.cfg", application.cacheDir)
        staged.writeText(text)
        val written = remote.uploadConfig(staged)
        // Moonraker's own endpoint rather than the RESTART command down the connection: a
        // configuration can be written for a host this app is not driving - which is exactly
        // when a broken one needs replacing - and then there is no connection to send it down.
        // This is MoonrakerFiles.restart()'s own reasoning, and why it exists.
        if (written) remote.restart()
        staged.delete()
        written
    }

    /**
     * Write this phone's own configuration, keeping the one it replaces beside it.
     *
     * Nothing is restarted for it. klippy here is a process this app starts and the screen the
     * file was written from is where a restart is asked for, and the host may not be running at
     * all - a sync that started it would be doing something nobody asked for.
     */
    private fun writeDeviceConfig(text: String) {
        KlipperHostFiles.directory(application.filesDir).mkdirs()
        val config = KlipperHostFiles.config(application.filesDir)
        if (config.isFile) {
            config.copyTo(KlipperHostFiles.previous(application.filesDir), overwrite = true)
        }
        config.writeText(text)
    }

    /**
     * The two configurations a sync works on, or the reason one of them is not readable.
     */
    private sealed interface SyncSources {
        data class Both(
            val device: String,
            val remote: String,
            val files: MoonrakerFiles,
            val deviceChangedAtMillis: Long?,
            val remoteChangedAtMillis: Long?,
        ) : SyncSources

        data class Failed(val reason: String) : SyncSources
    }

    /**
     * Read both hosts' configurations.
     *
     * The computer's is looked for at the host stored for it rather than at the host in use: a
     * sync works on both configurations whichever route is printing, and the computer the app
     * is not driving is still the one holding the other half of the printer's file. A missing
     * device file or an unanswered computer is a reason rather than an empty list of
     * differences, because a card that says the two agree and a card that could not look must
     * not read the same.
     */
    private suspend fun syncSources(): SyncSources {
        val device = KlipperHostFiles.config(application.filesDir).takeIf { it.isFile }?.readText()
            ?: return SyncSources.Failed(
                "Phone Klipper has no printer.cfg yet: start it once here, or import one",
            )
        val files = remoteHostFiles() ?: return SyncSources.Failed(
            "no PC Klipper is set yet: choose PC Klipper on the Print tab and connect it",
        )
        // readConfig, not configText: a computer with no configuration yet is a computer to
        // send this one to. Collapsing that into null made the card say the computer did not
        // answer, and with no differences to show there were no buttons to press either - a
        // dead end on the very first sync a new host needs.
        val remote = when (val read = files.readConfig()) {
            is MoonrakerFiles.ConfigRead.Found -> read.text
            MoonrakerFiles.ConfigRead.Missing -> ""
            MoonrakerFiles.ConfigRead.Unreachable -> return SyncSources.Failed(
                "PC Klipper at " + hostChoice.load().host.trim() +
                    " did not answer with its configuration",
            )
        }
        // This device's own file carries its time on disk. The other host is asked, because a
        // clock the app cannot see is the one it cannot report.
        val deviceChanged = KlipperHostFiles.config(application.filesDir).lastModified()
            .takeIf { it > 0L }
        return SyncSources.Both(
            device = device,
            remote = remote,
            files = files,
            deviceChangedAtMillis = deviceChanged,
            remoteChangedAtMillis = files.configModified(),
        )
    }

    /**
     * The computer's file API, whether or not it is the host the app is driving.
     *
     * Deliberately not [remoteFiles], which answers only for the host in use. The computer's
     * name is in the preferences even while this phone's own host is the one printing, and a
     * sync is about both configurations rather than about the one being driven.
     */
    private fun remoteHostFiles(): MoonrakerFiles? {
        val choice = hostChoice.load()
        if (choice.host.isBlank()) return null
        return MoonrakerFiles(
            host = choice.host.trim(),
            port = choice.port,
            apiKey = choice.apiKey.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Put a configuration on the computer and ask it to read it again.
     *
     * The two steps [writeHostConfig] takes for the host in use, written out because a sync can
     * be aimed at the computer while the app is driving the phone - and then the app's own file
     * and the app's own service are not the things being written to. The file being replaced is
     * kept on the computer beside it, as every other configuration write here keeps it.
     *
     * Answers whether the computer took the restart, which is what makes the written file the
     * running configuration: a file the host has not read back is not a sync.
     */
    private suspend fun writeRemoteConfig(
        files: MoonrakerFiles,
        text: String,
        before: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (before.isNotBlank()) {
            // A name of its own per write, and deleted afterwards: these are staging files, not
            // a cache, and a write that fails should not leave them behind either.
            val previous = File.createTempFile("previous", ".printer.cfg", application.cacheDir)
            try {
                previous.writeText(before)
                // Not discarded: a backup that silently did not happen is the thing a backup
                // exists to prevent.
                if (!files.uploadConfig(previous, name = "previous.printer.cfg")) {
                    reportConfigWriteFailure("the previous configuration")
                }
            } finally {
                previous.delete()
            }
        }
        val staged = File.createTempFile("staged", ".printer.cfg", application.cacheDir)
        try {
            staged.writeText(text)
            if (!files.uploadConfig(staged)) {
                throw IllegalStateException("PC Klipper refused the configuration")
            }
        // Its own endpoint rather than RESTART down the connection: the computer being written
        // to is not necessarily the one the app is connected to.
            // A restart ends a print on that computer, and the app may not be driving it. Ask.
            // An unknown state is not a reason to refuse; a known print is.
            val state = files.printState()?.lowercase()
            if (state == "printing" || state == "paused") {
                throw IllegalStateException(
                    "PC Klipper is " + state + ": syncing would restart it and end the print",
                )
            }
            files.restart()
        } finally {
            staged.delete()
        }
    }

    /**
     * A Marlin-only command in a sliced file, if its start G-code has one.
     *
     * Not the file's `;FLAVOR:` line, which says which G-code dialect the file is written in
     * rather than which firmware it is for: Orca and Prusa write `;FLAVOR:Marlin` with their
     * Klipper flavour selected, because Klipper reads Marlin G-code. Reading it as the firmware
     * warned about files that had been sliced correctly.
     *
     * What actually goes wrong is narrow, and worth naming exactly. `G29 L0` and `G29 A` are
     * Marlin's UBL commands. On a Klipper host `G29` is whichever macro the printer defines,
     * and the usual one homes and meshes - so the print homes twice and probes the bed twice
     * before its first layer, the second probe measuring a bed the first one has touched.
     */
    suspend fun marlinOnlyStartCommand(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            File(path).useLines { lines -> marlinOnlyStartCommand(lines) }
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
    /** The configuration of the host being driven, which is the one the card is about. */
    suspend fun readConfigFile(): String? = readHostConfig()

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
        // The shipped configuration is this device's own starting point. Comparing a computer's
        // file against it would offer a restore that writes the wrong machine's configuration.
        if (hostChoice.load().isRemote) return@withContext false
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
            if (hostChoice.load().isRemote) {
            return@withContext Result.failure(
                IllegalStateException(
                    "importing rewrites the configuration for this phone, so it only applies to " +
                        "Phone Klipper: switch to it on the Print tab first",
                ),
            )
        }
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
                // The names on disk, not the picked ones: safeFileName drops characters, and
                // an include believed present but written under another name stops klippy starting.
                availableFiles = companions.map { safeFileName(it.first) }.toSet(),
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
            val text = readHostConfig() ?: return@runCatching false
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
        // This writes the app's own shipped configuration, which names this phone's pty and its
        // gcode directory: it belongs to the device route and to no other.
        if (hostChoice.load().isRemote) return@withContext false
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

    /*
     * There was a refreshShippedConfigIfUntouched() here, called on every connection, which
     * rewrote the printer's configuration with the app's own whenever the two differed -
     * keeping only klippy's saved block. Its rule was "this file is the app's until somebody
     * imports one", and that rule was wrong in the way that matters: a user who edits the
     * seeded configuration is not importing anything, so the app took their edit for
     * staleness and put its defaults back.
     *
     * It cost exactly that on a real printer. Input shaping was saved with a measured Y
     * frequency of 44.3 Hz; the file was written correctly, and then the next connection
     * replaced it with the shipped 35.2 and a copy of the old file that agreed, because it
     * had been made from the same stale text. The saved block survived, which is why the
     * probe offset calibrated the same evening is still there and the shaper value is not -
     * a good illustration of how narrow the escape was.
     *
     * The configuration is now written once, when it is seeded, and after that only by the
     * user: the app keeps to its own parts of it - the include line, and the serial path and
     * gcode directory the service resolves at every start - and never rewrites the whole
     * file on a rule about who it thinks owns it.
     */

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
        /** Room left over after an upload, so a full disk is not discovered mid-copy. */
        private const val UPLOAD_SPARE_BYTES = 1L * 1024 * 1024

        /**
         * What a copy in progress is called.
         *
         * The file list reads *.gcode, and this does not end that way, so a partial file is
         * never offered as something to print.
         */
        private const val UPLOAD_SUFFIX = ".uploading"

        const val TAG = "KlipperPrinter"

        /** How long to wait before trying the host again after a failure. */
        const val RETRY_MS = 3000L

        /** How much of the host's log the screen is shown when it stops answering. */
        const val LOG_TAIL_LINES = 6

        /** How often the merged state is written down, so the screen can be checked. */
        const val STATUS_LOG_MS = 30_000L

        /** How often klippy is asked for its own state, which it never pushes. */
        const val STATE_POLL_SECONDS = 5

        /**
         * Moonraker's own push that its camera list changed.
         *
         * Not a klippy method: it arrives on the same socket, the dialect passes it through,
         * and the answer to it is to read the list again rather than to touch the printer.
         */
        const val WEBCAMS_CHANGED_METHOD = "notify_webcams_changed"

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

/**
 * The text a configuration operation works from.
 *
 * A configured computer is the only source when it is configured: if its configuration cannot be
 * be read the answer is null, never this device's own file. Returning that file was how a phone's
 * configuration once reached the computer, with a restart to follow it.
 */
internal fun resolveConfigText(
    remoteConfigured: Boolean,
    deviceText: () -> String?,
    remoteText: () -> String?,
): String? = if (remoteConfigured) remoteText() else deviceText()

