package com.tomppi.enderslicer.ui.klipper

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
import com.tomppi.enderslicer.BuildConfig
import com.tomppi.enderslicer.printer.KlipperMcu
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperViewModel
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
@Composable
internal fun KlipperMachineTab(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    var confirmRestart by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf<String?>(null) }
    var showObjects by remember { mutableStateOf(false) }
    var configDiffers by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { configDiffers = viewModel.configDiffersFromShipped() }

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
                KlipperButton("Start") { viewModel.startHost() }
                KlipperButton("Stop") { viewModel.stopHost() }
                KlipperButton("Restart") { confirmRestart = true }
                KlipperButton("Firmware restart", enabled = state.connected) {
                    viewModel.firmwareRestart()
                }
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
            KlipperValue("Process", state.host.processId.takeIf { it > 0 }?.toString() ?: "-")
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
                KlipperValue("Load average", stats.load?.let { "%.2f".format(it) } ?: "-")
                KlipperValue(
                    "Memory available",
                    stats.memoryAvailable?.let { "%.0f MB".format(it / 1024 / 1024) } ?: "-",
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

        TextFileCard(
            title = "Configuration",
            subtitle = "printer.cfg, as the host is running it",
            text = config,
            onLoad = { scope.launch { config = viewModel.readConfig() } },
            onHide = { config = null },
            actions = {
                // Said rather than changed silently: the file is the user's, and what
                // the app ships is only a starting point for it.
                if (configDiffers) {
                    KlipperNote(
                        "This is not the configuration this version of the app ships. " +
                            "Restoring it keeps the values klippy has saved.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    KlipperButtons {
                        KlipperButton("Restore the shipped default") { confirmRestore = true }
                    }
                }
            },
        )

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

    if (confirmRestore) {
        KlipperConfirmDialog(
            title = "Restore the shipped configuration?",
            text = "The app's own printer.cfg replaces this one. A PID calibration, a Z " +
                "offset and saved mesh profiles are kept; anything edited by hand is not. " +
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

/** One board, with the timing klippy measures against it. */
@Composable
private fun McuCard(mcu: KlipperMcu) {
    KlipperCard(title = mcu.name, subtitle = mcu.version.ifBlank { "no version reported" }) {
        mcu.frequency?.let { KlipperValue("Clock", "%.0f MHz".format(it / 1_000_000)) }
        mcu.awake?.let { KlipperValue("Busy", "%.0f%%".format(it * 100)) }
        mcu.load?.let { KlipperValue("Task average", "%.1f µs".format(it * 1_000_000)) }
        mcu.roundTripSeconds?.let { KlipperValue("Round trip", "%.1f ms".format(it * 1000)) }
        mcu.jitterSeconds?.let { KlipperValue("Jitter", "±%.1f ms".format(it * 1000)) }
        mcu.timeoutSeconds?.let { KlipperValue("Resend after", "%.0f ms".format(it * 1000)) }
        val retransmits = mcu.retransmits ?: 0
        if (retransmits > 0) {
            KlipperValue("Retransmitted", "$retransmits bytes", valueColor = MaterialTheme.colorScheme.error)
        }
    }
}

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
