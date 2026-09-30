package com.tomppi.enderslicer.ui.klipper

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomppi.enderslicer.BuildConfig
import com.tomppi.enderslicer.printer.KlipperImportResult
import com.tomppi.enderslicer.printer.KlipperMcu
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperSyncResult
import com.tomppi.enderslicer.printer.KlipperSyncSource
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.printer.describeConfigAge
import com.tomppi.enderslicer.printer.endstops
import com.tomppi.enderslicer.printer.mcus
import com.tomppi.enderslicer.printer.saveConfigPending
import com.tomppi.enderslicer.printer.systemStats
import kotlinx.coroutines.launch

/**
 * The machine, the host running it, and everything about the two that is not about a
 * print.
 *
 * This is where a user goes when something is wrong rather than when something is
 * happening: which version of each is running, what the boards are reporting, what the
 * host is doing to this phone, what the endstops say, and the two files that explain
 * almost every failure - the configuration and the log.
 */
/** How often the micro-controller reports its own statistics: basecmd.c's 5000000 us timer. */
private const val MCU_STATS_WINDOW_SECONDS = 5.0

@Composable
internal fun KlipperMachineTab(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    var confirmRestart by remember { mutableStateOf(false) }
    // Import and Restore write this phone's own configuration - its pty, its gcode directory -
    // so they belong to This device. Export, Start and Stop follow whichever host is chosen.
    val onDevice = state.remoteHost == null
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf<String?>(null) }
    var showObjects by remember { mutableStateOf(false) }
    var configDiffers by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<KlipperImportResult?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val configSource by viewModel.configSource.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { configDiffers = viewModel.configDiffersFromShipped() }

    // More than one file, because a printer.cfg that includes others needs them beside
    // it: they are written into the same directory, where klippy resolves them from.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            viewModel.importConfig(uris)
                .onSuccess { result ->
                    importResult = result
                    config = viewModel.readConfig()
                    configDiffers = viewModel.configDiffersFromShipped()
                    // klippy reads its configuration once, at the start: a new one means
                    // a new host, which is also what applies the paths this app wrote in.
                    viewModel.restartHost()
                }
                .onFailure { error -> importError = error.message ?: "the import failed" }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { viewModel.exportConfig(uri) }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KlipperCard(title = "The host", subtitle = if (state.connected) "Running" else "Not reachable") {
            Text(
                text = when {
                    !state.connected -> "Not reachable"
                    state.state == "ready" -> "Ready"
                    state.state == "shutdown" -> "Shutdown"
                    else -> state.state.replaceFirstChar { it.uppercase() }
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (state.stateMessage.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                KlipperNote(state.stateMessage.trim())
            }
            Spacer(Modifier.height(8.dp))
            KlipperButtons {
                // The host is a process this app starts and stops; everything about the
                // printer is downstream of it being up.
                KlipperButton("Start", enabled = onDevice) { viewModel.startHost() }
                KlipperButton("Stop", enabled = onDevice) { viewModel.stopHost() }
                KlipperButton("Restart") { confirmRestart = true }
                KlipperButton("Firmware restart", enabled = state.connected) {
                    viewModel.firmwareRestart()
                }
            }
            if (!onDevice) {
                Spacer(Modifier.height(8.dp))
                KlipperNote(
                    "Start and Stop drive the Klipper host on this phone. On the PC Klipper " +
                        "route the computer runs it, so they are not offered here.",
                )
            }
            if (state.saveConfigPending) {
                Spacer(Modifier.height(8.dp))
                KlipperNote(
                    "klippy has values waiting to be written to the configuration. " +
                        "Saving restarts the host, and the printer is unavailable for a moment.",
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                KlipperButtons {
                    KlipperButton("Save configuration") { viewModel.saveConfig() }
                }
            }
        }

        KlipperCard(title = "Klipper") {
            KlipperValue("Version", state.host.softwareVersion.ifBlank { "-" })
            KlipperValue("Host name", state.host.hostname.ifBlank { "-" })
            KlipperValue("Python", state.host.pythonPath.ifBlank { "-" })
            KlipperValue("CPU", state.host.cpuInfo.ifBlank { "-" })
            KlipperValue("Process ID", state.host.processId.takeIf { it > 0 }?.toString() ?: "-")
            KlipperValue("Configuration", state.host.configFile.ifBlank { "-" })
            KlipperValue("Log", state.host.logFile.ifBlank { "-" })
            Spacer(Modifier.height(4.dp))
            KlipperNote("This app is " + BuildConfig.VERSION_NAME + " and hosts Klipper as a separate process.")
        }

        if (state.mcus.isEmpty()) {
            KlipperCard(title = "Micro-controllers") {
                KlipperNote(
                    if (state.connected) "None reported yet: the printer answers once its board does."
                    else "The host is not reachable.",
                )
            }
        }
        state.mcus.forEach { mcu -> McuCard(mcu) }

        KlipperCard(title = "This device") {
            val stats = state.systemStats
            if (stats == null) {
                KlipperNote("The host has not reported its own load yet.")
            } else {
                // No load average: the host is a phone, os.getloadavg is not there, and the
                // staged statistics.py reports 0.0 - a row that reads "0.00" forever is a
                // wrong number rather than a missing one, so it is not shown at all.
                KlipperValue(
                    "Memory available",
                    // /proc/meminfo counts in kilobytes and klippy passes the number
                    // through unmodified, so this is one division, not two.
                    stats.memoryAvailable?.let { "%.0f MB".format(it / 1024) } ?: "-",
                )
                KlipperValue(
                    "Host CPU time",
                    stats.cpuTime?.let { seconds ->
                        "%d m %d s".format((seconds / 60).toInt(), (seconds % 60).toInt())
                    } ?: "-",
                )
            }
        }

        KlipperCard(title = "Endstops", subtitle = "What the switches say right now") {
            val endstops = state.endstops
            if (endstops.isEmpty()) {
                KlipperNote("Not asked yet. A query reads them where they are, without moving anything.")
            } else {
                endstops.forEach { (name, value) -> KlipperValue(name, value) }
            }
            Spacer(Modifier.height(8.dp))
            KlipperButtons {
                KlipperButton("Query", enabled = state.isReady) { viewModel.queryEndstops() }
            }
        }

        if (!configSource.imported) {
            KlipperCard(title = "Klipper setup", subtitle = "What this app is driving") {
                KlipperNote(
                    "This app runs Klipper itself, so it has to be told what printer it is " +
                        "driving. It ships with the configuration of the machine it was " +
                        "developed on - an Ender-3 V2 with a BLTouch and an Orbiter extruder.",
                )
                Spacer(Modifier.height(8.dp))
                KlipperNote(
                    "If your printer is a different machine, import its printer.cfg before " +
                        "printing. The app points the serial port and the file list at itself " +
                        "and leaves everything else - pins, steps, probe offsets, macros - " +
                        "exactly as your file has it. If it includes other files, choose them " +
                        "in the same step.",
                )
                Spacer(Modifier.height(8.dp))
                KlipperNote(
                    "Once you import a configuration it is yours: this app will not change " +
                        "it again, not even when it ships a fix to its own. You can export it " +
                        "or put the app's configuration back from this screen whenever you " +
                        "want.",
                )
                Spacer(Modifier.height(8.dp))
                KlipperNote(
                    "Your board has to be running Klipper itself, of the same version as the " +
                        "host in this app" +
                        state.host.softwareVersion.takeIf { it.isNotBlank() }
                            ?.let { " ($it)" }.orEmpty() +
                        ": importing a configuration tells the app about your printer, it does " +
                        "not flash your board. A printer still running its stock firmware, or " +
                        "a board flashed with a different Klipper, will not answer.",
                )
                Spacer(Modifier.height(8.dp))
                KlipperButtons {
                    KlipperButton("Import a configuration") { importLauncher.launch(arrayOf("*/*")) }
                    KlipperButton("Export the running one") { exportLauncher.launch("printer.cfg") }
                }
            }
        }

        TextFileCard(
            title = "Configuration",
            subtitle = configSource.describe(),
            text = config,
            onLoad = { scope.launch { config = viewModel.readConfig() } },
            onHide = { config = null },
            actions = {
                // Import and Restore write this phone's own configuration: they belong to
                // This device, and the card says so rather than offering them here.
                if (configDiffers && onDevice) {
                    KlipperNote(
                        "This is not the configuration this version of the app ships. " +
                            "Restoring it keeps the values klippy has saved, and replaces " +
                            "everything else in the file.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                KlipperButtons {
                    if (onDevice) {
                        KlipperButton("Import") { importLauncher.launch(arrayOf("*/*")) }
                    }
                    KlipperButton("Export") { exportLauncher.launch("printer.cfg") }
                    if (onDevice) {
                        KlipperButton("Restore the app's configuration") { confirmRestore = true }
                    }
                }
                if (!onDevice) {
                    Spacer(Modifier.height(8.dp))
                    KlipperNote(
                        "Import and Restore write this phone own configuration. Switch to " +
                            "This device on the Print tab to use them.",
                    )
                }
            },
        )

        SyncCard(viewModel)

        TextFileCard(
            title = "Log",
            subtitle = "The last two hundred lines klippy wrote",
            text = log,
            onLoad = { scope.launch { log = viewModel.readLog() } },
            onHide = { log = null },
        )

        KlipperCard(
            title = "Objects",
            subtitle = state.published.size.toString() + " published by this printer",
        ) {
            KlipperButtons {
                KlipperButton(if (showObjects) "Hide" else "Show") { showObjects = !showObjects }
            }
            if (showObjects) {
                Spacer(Modifier.height(8.dp))
                KlipperNote(state.published.joinToString(", "))
            }
        }

        state.error?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }

    importResult?.let { result ->
        ImportedDialog(result) { importResult = null }
    }
    importError?.let { message ->
        KlipperConfirmDialog(
            title = "That configuration could not be imported",
            text = message + "\n\nThe configuration that was running is untouched.",
            confirmLabel = "Close",
            onConfirm = { importError = null },
            onDismiss = { importError = null },
        )
    }
    if (confirmRestore) {
        KlipperConfirmDialog(
            title = "Restore the configuration this app ships?",
            text = "The app's own printer.cfg replaces the one that is running. A PID " +
                "calibration, a Z offset and saved mesh profiles are kept, and everything " +
                "else in the file is replaced - including a configuration you imported. " +
                "The host restarts to apply it.",
            confirmLabel = "Restore",
            destructive = true,
            onConfirm = {
                scope.launch {
                    if (viewModel.restoreShippedConfig()) {
                        configDiffers = false
                        config = viewModel.readConfig()
                        viewModel.restartHost()
                    }
                }
            },
            onDismiss = { confirmRestore = false },
        )
    }
    if (confirmRestart) {
        KlipperConfirmDialog(
            title = "Restart the host?",
            text = "Klipper is stopped and started again, which takes a few seconds. " +
                "A print in progress is lost.",
            confirmLabel = "Restart",
            onConfirm = { viewModel.restartHost() },
            onDismiss = { confirmRestart = false },
        )
    }
}

/**
 * What happened to the configuration that was imported.
 *
 * Told rather than assumed: an imported file is edited on the way in - the serial port,
 * the file list - and what it asks for that this device cannot supply is the difference
 * between a button that does not work and a user knowing why.
 */
@Composable
private fun ImportedDialog(result: KlipperImportResult, onDismiss: () -> Unit) {
    KlipperConfirmDialog(
        title = result.fileName.ifBlank { "Configuration" } + " is now running",
        text = buildString {
            if (result.companions.isNotEmpty()) {
                append("Also imported: ")
                append(result.companions.joinToString(", "))
                append("\n\n")
            }
            if (result.changes.isNotEmpty()) {
                append("Changed so this device can run it:\n")
                result.changes.forEach { append("• ").append(it).append('\n') }
                append('\n')
            }
            if (result.warnings.isNotEmpty()) {
                append("Worth knowing:\n")
                result.warnings.forEach { append("• ").append(it).append('\n') }
            }
            append("The host has been restarted.")
        },
        confirmLabel = "Close",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    )
}

/** One board, with the timing klippy measures against it. */
@Composable
private fun McuCard(mcu: KlipperMcu) {
    KlipperCard(title = mcu.name, subtitle = mcu.version.ifBlank { "no version reported" }) {
        mcu.frequency?.let { KlipperValue("Clock frequency", "%.0f MHz".format(it / 1_000_000)) }
        // mcu_awake is seconds of the last five-second stats window (basecmd.c asks for the
        // report every 5000000 us), so the duty is that over five - not the number itself.
        mcu.awake?.let { awake ->
            KlipperValue(
                "Board busy",
                "%.0f%% of the last %.0f s".format(
                    awake / MCU_STATS_WINDOW_SECONDS * 100, MCU_STATS_WINDOW_SECONDS,
                ),
            )
        }
        mcu.load?.let { KlipperValue("Average task time", "%.1f µs".format(it * 1_000_000)) }
        mcu.roundTripSeconds?.let { KlipperValue("Round-trip time", "%.1f ms".format(it * 1000)) }
        mcu.jitterSeconds?.let { KlipperValue("Round-trip jitter", "±%.1f ms".format(it * 1000)) }
        mcu.timeoutSeconds?.let { KlipperValue("Retransmit timeout", "%.0f ms".format(it * 1000)) }
        val retransmits = mcu.retransmits ?: 0
        if (retransmits > 0) {
            KlipperValue("Bytes retransmitted", "$retransmits bytes", valueColor = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * Copy the printer's own settings from one host's configuration to the other.
 *
 * The two files describe one machine and must differ only in how each host reaches it, so what
 * is offered here is the part that has to match and has drifted: the calibrations klippy saved,
 * the extruder's own figures, the motion limits. Each host's serial port, gcodes directory,
 * boards and includes belong to it, and are not listed and never moved, whichever direction is
 * chosen.
 */
@Composable
private fun SyncCard(viewModel: KlipperViewModel) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<KlipperSyncResult?>(null) }
    var outcome by remember { mutableStateOf<String?>(null) }
    var direction by remember { mutableStateOf<KlipperSyncSource?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { preview = viewModel.syncDifferences() }

    KlipperCard(
        title = "Sync with the other host",
        subtitle = "The printer's own settings, from one host's printer.cfg to the other's",
        trailing = {
            KlipperButton("Check again", enabled = !busy) {
                scope.launch {
                    outcome = null
                    preview = viewModel.syncDifferences()
                }
            }
        },
    ) {
        outcome?.let { message ->
            KlipperNote(message)
            Spacer(Modifier.height(8.dp))
        }
        val result = preview
        when {
            result == null -> KlipperNote("Reading the other host's configuration…")
            result.error != null -> KlipperNote(
                text = "Nothing could be compared: " + result.error,
                color = MaterialTheme.colorScheme.error,
            )
            else -> {
                // Which file was written last, because "which of these is the right value" is
                // usually "which one did I set more recently" - and a setting carries no date of
                // its own. A file's time is the whole file's: a SAVE_CONFIG rewrites all of it,
                // so a PID tune makes every value in the file look that new.
                val now = System.currentTimeMillis()
                val deviceChanged = result.deviceChangedAtMillis
                val remoteChanged = result.remoteChangedAtMillis
                fun mark(mine: Long?, theirs: Long?) = if (
                    mine != null && theirs != null && mine > theirs
                ) "  \u2014 newer" else ""
                KlipperValue(
                    label = "This device, written",
                    value = describeConfigAge(now, deviceChanged) +
                        mark(deviceChanged, remoteChanged),
                )
                KlipperValue(
                    label = "The other host, written",
                    value = describeConfigAge(now, remoteChanged) +
                        mark(remoteChanged, deviceChanged),
                )
                Spacer(Modifier.height(8.dp))
                if (result.differences.isEmpty()) {
                    KlipperNote(
                        "Nothing differs: the two agree about every setting this copies. Each " +
                            "host's serial port, gcodes directory, boards and includes stay its own.",
                    )
                } else {
                    KlipperNote("This device's value first, the other host's second.")
                    Spacer(Modifier.height(4.dp))
                    result.differences.forEach { difference ->
                        KlipperValue(
                            label = difference.section + " " + difference.option,
                            value = difference.valueA + "  \u2192  " + difference.valueB,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    KlipperButtons {
                        KlipperButton("Copy to the other host", enabled = !busy) {
                            direction = KlipperSyncSource.THE_DEVICE
                        }
                        KlipperButton("Copy from the other host", enabled = !busy) {
                            direction = KlipperSyncSource.THE_REMOTE_HOST
                        }
                    }
                }
            }
        }
    }

    direction?.let { from ->
        val fromComputer = from == KlipperSyncSource.THE_REMOTE_HOST
        KlipperConfirmDialog(
            title = if (fromComputer) {
                "Copy the other host's settings here?"
            } else {
                "Copy this device's settings to the other host?"
            },
            text = if (fromComputer) {
                "The printer's own settings in the computer's printer.cfg replace this " +
                    "device's: the calibrations klippy saved, the extruder's rotation distance " +
                    "and pressure advance, the motion limits. This device's serial port, its " +
                    "gcodes directory and its [include app.cfg] are left exactly as they are. " +
                    "Nothing restarts here by itself: the host reads the new file the next time " +
                    "it is restarted from this screen."
            } else {
                "The printer's own settings in this device's printer.cfg replace the " +
                    "computer's. Everything that describes the computer rather than the " +
                    "printer - its serial port, its gcodes directory, its [mcu rpi] section, " +
                    "its includes - is left exactly as it is. The computer is asked to restart, " +
                    "which a print in progress there does not survive."
            },
            confirmLabel = if (fromComputer) "Copy here" else "Copy there",
            onConfirm = {
                scope.launch {
                    busy = true
                    val result = viewModel.syncConfiguration(from)
                    busy = false
                    outcome = syncOutcome(result, fromComputer)
                    preview = viewModel.syncDifferences()
                }
            },
            onDismiss = { direction = null },
        )
    }
}

/** What a copy did, in the direction it was asked for. */
private fun syncOutcome(result: KlipperSyncResult, fromComputer: Boolean): String = when {
    result.error != null -> "Nothing was copied: " + result.error
    result.copied.isEmpty() -> "Nothing was copied: the two agree already."
    fromComputer -> countCopied(result.copied.size) +
        " into this device's printer.cfg. The host here reads it the next time it restarts."
    result.restarted -> countCopied(result.copied.size) + " to the other host, which has been " +
        "asked to restart."
    else -> countCopied(result.copied.size) + " to the other host. It could not be asked to " +
        "restart, so it is still running the configuration it had."
}

/** "2 settings", or "1 setting", for a sentence that says what a copy moved. */
private fun countCopied(count: Int): String =
    "Copied " + count + (if (count == 1) " setting" else " settings")

/** A file the screen can show: loaded on request, and hidden again the same way. */
@Composable
private fun TextFileCard(
    title: String,
    subtitle: String,
    text: String?,
    onLoad: () -> Unit,
    onHide: () -> Unit,
    actions: (@Composable () -> Unit)? = null,
) {
    KlipperCard(
        title = title,
        subtitle = subtitle,
        trailing = {
            KlipperButton(if (text == null) "Show" else "Hide") {
                if (text == null) onLoad() else onHide()
            }
        },
    ) {
        actions?.invoke()
        if (text == null) {
            KlipperNote("Not loaded.")
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                KlipperMono(text)
            }
        }
    }
}
