package com.tomppi.enderslicer.ui.klipper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.printer.KlipperPrint
import com.tomppi.enderslicer.printer.KlipperFileLimits
import com.tomppi.enderslicer.printer.parseDecimal
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.ui.platform.LocalContext
import java.io.File
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.printer.displayMessage
import com.tomppi.enderslicer.printer.excludedObjects
import com.tomppi.enderslicer.printer.extrudeFactor
import com.tomppi.enderslicer.printer.fanSpeed
import com.tomppi.enderslicer.printer.heaters
import com.tomppi.enderslicer.printer.plateObjects
import com.tomppi.enderslicer.printer.printFilamentUsed
import com.tomppi.enderslicer.printer.printLayers
import com.tomppi.enderslicer.printer.orDash
import com.tomppi.enderslicer.printer.speedFactor
import com.tomppi.enderslicer.printer.speeds
import com.tomppi.enderslicer.printer.zOffset
import com.tomppi.enderslicer.ui.formatPrintTime
import kotlin.math.roundToInt

/**
 * What the machine is doing, and everything that changes it while it does.
 *
 * The screen a print is watched on: the job with its own numbers, the three factors a
 * user adjusts mid-print, the temperatures at a glance, where the head is, and the two
 * things that stop it. The panels that are about setting the machine up rather than
 * watching it have their own screens, and this one links to nothing - a dashboard that
 * is also a menu is how a user ends up hunting for the button they just used.
 */
@Composable
internal fun KlipperDashboardTab(
    state: KlipperPrinterState,
    viewModel: KlipperViewModel,
    localGcodePath: String?,
    suggestedFileName: String,
) {
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmExclude by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HostCard(state, viewModel)
        JobCard(
            state = state,
            viewModel = viewModel,
            localGcodePath = localGcodePath,
            suggestedFileName = suggestedFileName,
            onCancel = { confirmCancel = true },
        )
        if (state.connected) {
            AdjustmentsCard(state, viewModel)
            TemperaturesCard(state, viewModel)
            ExcludedObjectsCard(state, onExclude = { confirmExclude = it })
        }
        LimitsCard(state, localGcodePath, viewModel)
        PositionCard(state, viewModel)
        TimingCard(state)
        DangerCard(state, onEmergencyStop = { confirmStop = true })
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

    if (confirmCancel) {
        KlipperConfirmDialog(
            title = "Stop this print?",
            text = "The printer stops where it is and the print cannot be resumed. " +
                // This printer's own CANCEL_PRINT turns the heaters off and parks the head,
                // and an imported configuration may do anything - so the app states what it
                // knows: the printer decides.
                "The printer's own CANCEL_PRINT decides what happens next. The " +
                    "configuration this app ships turns the heaters off, re-homes and parks " +
                    "the head.",
            confirmLabel = "Stop the print",
            destructive = true,
            onConfirm = { viewModel.cancelPrint() },
            onDismiss = { confirmCancel = false },
        )
    }
    if (confirmStop) {
        KlipperConfirmDialog(
            title = "Emergency stop?",
            text = "The micro-controller is halted immediately and the printer shuts down. " +
                "It needs a firmware restart before it will move again.",
            confirmLabel = "Emergency stop",
            destructive = true,
            onConfirm = { viewModel.emergencyStop() },
            onDismiss = { confirmStop = false },
        )
    }
    confirmExclude?.let { name ->
        KlipperConfirmDialog(
            title = "Skip $name?",
            text = "This object is left out of the print that is running. The rest of the " +
                "plate is printed as sliced.",
            confirmLabel = "Skip it",
            onConfirm = { viewModel.excludeObject(name) },
            onDismiss = { confirmExclude = null },
        )
    }
}

/** Whether the host is up, and what it said if it is not. */
@Composable
private fun HostCard(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    KlipperCard(title = "Printer", subtitle = "The Klipper host running in this app") {
        Text(
            text = when {
                !state.connected -> "Host not reachable"
                state.state == "ready" -> "Ready"
                state.state == "shutdown" -> "Shutdown"
                else -> state.state.replaceFirstChar { it.uppercase() }
            },
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                state.isReady -> READY_GREEN
                state.state == "shutdown" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (state.stateMessage.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.stateMessage.trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!state.connected) {
            Spacer(Modifier.height(12.dp))
            KlipperButtons {
                // The host is normally started by the printer being plugged in; this is
                // for when it is already plugged in, or was started before the app.
                KlipperButton("Start the printer host") { viewModel.startHost() }
            }
            state.hostLogTail?.let { tail ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "The host stopped with:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KlipperMono(tail, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * The print the printer is running, or the one it could run.
 *
 * A sliced file is handed over by copying it into the directory the host's virtual SD
 * card reads, which is inside this app's own storage - there is no upload step and
 * nothing to configure.
 */
@Composable
private fun JobCard(
    state: KlipperPrinterState,
    viewModel: KlipperViewModel,
    localGcodePath: String?,
    suggestedFileName: String,
    onCancel: () -> Unit,
) {
    val printing = state.printFileName != null && (state.isPrinting || state.isPaused)
    KlipperCard(
        title = "Print",
        subtitle = if (printing) state.printFileName else null,
    ) {
        // A print that failed says so here, whether or not it is still "printing": klippy
        // reports the reason in print_stats.message, and without this the screen showed a
        // print that had simply stopped.
        val failed = state.printState == "error" || state.printState == "cancelled"
        if (failed) {
            Text(
                text = if (state.printState == "error") "The print stopped" else "The print was cancelled",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            state.printMessage?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (printing) {
            Text(
                text = state.printState.orEmpty().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.isPaused) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.displayMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            state.printProgress?.let { progress ->
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                KlipperValue("Done", "%.1f%%".format(progress * 100))
            }
            val seconds = state.printDurationSeconds
            if (seconds != null) {
                KlipperValue("Printing for", formatPrintTime(seconds.roundToInt()))
                // Only worth showing once there is enough of the print to extrapolate
                // from: a minute in, the estimate is the noise in the progress figure.
                val progress = state.printProgress
                if (progress != null && progress > 0.02 && state.isPrinting) {
                    val remaining = seconds / progress - seconds
                    KlipperValue("Left", formatPrintTime(remaining.roundToInt()))
                }
            }
            state.printLayers?.let { layers ->
                KlipperValue("Layer", "${layers.current} of ${layers.total}")
            }
            filamentMetres(state)?.let { metres ->
                KlipperValue("Filament", "%.2f m".format(metres))
            }
            Spacer(Modifier.height(8.dp))
            KlipperButtons {
                if (state.isPaused) {
                    KlipperButton("Resume") { viewModel.resumePrint() }
                } else {
                    KlipperButton("Pause", enabled = state.isPrinting) { viewModel.pausePrint() }
                }
                KlipperButton("Stop", onClick = onCancel)
            }
        } else {
            val path = localGcodePath
            if (path == null) {
                KlipperNote("Nothing sliced yet. Slice a model and it appears here.")
            } else {
                KlipperValue("Ready to print", KlipperPrint.fileName(suggestedFileName))
                Spacer(Modifier.height(8.dp))
                KlipperButtons {
                    KlipperButton(
                        text = "Print this file",
                        enabled = state.isReady,
                        onClick = { viewModel.printFile(path, suggestedFileName) },
                    )
                }
                if (!state.isReady) {
                    Spacer(Modifier.height(4.dp))
                    KlipperNote("The printer is not ready, so this is held back until it is.")
                }
            }
            state.printState?.takeIf { it.isNotBlank() && it != "standby" }?.let { last ->
                Spacer(Modifier.height(4.dp))
                KlipperNote("Last print: $last")
            }
        }
    }
}

/**
 * The speeds in play, under the slider that only asks for them.
 *
 * A percentage is not a speed, and the number wanted when a print looks slow is in
 * millimetres per second: what the file asked for, what the override makes of it, and what the
 * toolhead is measured to be doing. The limits sit underneath, because they are the reason the
 * third is often below the second - a printer at 200% stops getting faster once its own
 * max_velocity is reached.
 */
@Composable
private fun SpeedReadings(state: KlipperPrinterState) {
    val speeds = state.speeds
    KlipperValue(
        label = "Doing now",
        value = speeds.live.orDash() + " mm/s" +
            (speeds.filament?.takeIf { it > 0.01 }
                ?.let { " · " + it.orDash(2) + " mm/s of filament" } ?: ""),
    )
    KlipperValue(
        label = "The file asks for",
        value = speeds.askedFor.orDash() + " mm/s · " +
            speeds.askedFor?.times(60.0).orDash(0) + " mm/min",
    )
    KlipperValue(
        label = "With the override",
        value = speeds.effective.orDash() + " mm/s · " + speeds.effectivePerMinute.orDash(0) +
            " mm/min",
    )
    if (speeds.clamped) {
        KlipperNote(
            "That is past this printer's own limit of " + speeds.maxVelocity.orDash() +
                " mm/s, so the moves are clamped to it - which is why more override stops " +
                "making the print faster.",
        )
    }
    KlipperValue(
        label = "Its limits",
        value = speeds.maxVelocity.orDash() + " mm/s · " + speeds.maxAccel.orDash(0) +
            " mm/s² · corners " + speeds.cornerVelocity.orDash() + " mm/s",
    )
}

/** The three factors a print is adjusted by while it runs. */
@Composable
private fun AdjustmentsCard(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    KlipperCard(title = "Adjustments") {
        KlipperSlider(
            label = "Part fan",
            value = ((state.fanSpeed ?: 0.0) * 100).toFloat().coerceIn(0f, 100f),
            range = 0f..100f,
            valueText = "%.0f%%".format((state.fanSpeed ?: 0.0) * 100),
            onSet = { viewModel.setFan(it.roundToInt()) },
        )
        KlipperSlider(
            label = "Speed",
            value = ((state.speedFactor ?: 1.0) * 100).toFloat().coerceIn(SPEED_RANGE),
            range = SPEED_RANGE,
            valueText = "%.0f%%".format((state.speedFactor ?: 1.0) * 100),
            steps = 17,
            onSet = { viewModel.setSpeedFactor(it.roundToInt()) },
        )
        SpeedReadings(state)
        KlipperSlider(
            label = "Flow",
            value = ((state.extrudeFactor ?: 1.0) * 100).toFloat().coerceIn(FLOW_RANGE),
            range = FLOW_RANGE,
            valueText = "%.0f%%".format((state.extrudeFactor ?: 1.0) * 100),
            steps = 9,
            onSet = { viewModel.setExtrudeFactor(it.roundToInt()) },
        )
        Spacer(Modifier.height(4.dp))
        KlipperButtons {
            KlipperButton("Reset to 100%") {
                viewModel.setSpeedFactor(100)
                viewModel.setExtrudeFactor(100)
            }
            KlipperButton("Fan off") { viewModel.setFan(0) }
        }
    }
}

/** The temperatures, with the two presets most prints use. */
@Composable
private fun TemperaturesCard(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    KlipperCard(
        title = "Temperatures",
        trailing = { KlipperButton("Cool down") { viewModel.coolDown() } },
    ) {
        if (state.heaters.isEmpty()) {
            KlipperNote("The printer has not reported a temperature yet.")
            return@KlipperCard
        }
        state.heaters.forEach { heater ->
            HeaterReadoutRow(heater)
            if (heater.isHeater) {
                KlipperButtons {
                    KlipperButton("Off") { viewModel.setHeaterTemperature(heater.name, 0) }
                    KlipperButton("PLA") {
                        viewModel.setHeaterTemperature(heater.name, if (heater.name == "heater_bed") 60 else 200)
                    }
                    KlipperButton("PETG") {
                        viewModel.setHeaterTemperature(heater.name, if (heater.name == "heater_bed") 80 else 240)
                    }
                }
            }
        }
    }
}

/**
 * What the file asks the machine for, against what the machine allows.
 *
 * The two figures are set in different places and neither screen mentions the other: the
 * profile's machine limits become M204 at the head of the file, the printer's own max_accel and
 * max_velocity are in printer.cfg. A file that asks for less than the machine can do prints
 * slowly and says nothing about it, which is worth an answer on the screen where the print is
 * started. Marlin's own limit lines are named for the same reason: klippy has no M201, M203 or
 * M205, so a file that appears to set a ceiling is really setting nothing.
 */
@Composable
private fun LimitsCard(state: KlipperPrinterState, localGcodePath: String?, viewModel: KlipperViewModel) {
    val file = localGcodePath?.let(::File)
    val limits = remember(state.speeds, localGcodePath) { KlipperFileLimits.of(state, file) } ?: return
    KlipperCard(title = "Limits", subtitle = "This file, and this printer") {
        KlipperValue(
            "File asks for",
            limits.askedAccel?.let { "%.0f mm/s²".format(it) } ?: "no acceleration figure",
        )
        KlipperValue(
            "Printer allows",
            limits.allowedAccel?.let { "%.0f mm/s²".format(it) } ?: "-",
        )
        limits.allowedVelocity?.let { KlipperValue("Top speed", "%.0f mm/s".format(it)) }
        when {
            limits.accelerationIsTheFilesOwn -> {
                Spacer(Modifier.height(4.dp))
                KlipperNote(
                    "The print will run at the file's figure, not the printer's - the lower of " +
                        "the two is what a move uses. The machine limits in the slicer profile " +
                        "are where it comes from.",
                )
            }
            limits.accelerationIsClampedByThePrinter -> {
                Spacer(Modifier.height(4.dp))
                KlipperNote("The file asks for more than the printer allows, so the printer holds it to its own figure.")
            }
        }
        //
        // The printer's own limits, changeable while it prints: the way to calm a print that is
        // shaking itself apart, or to let one run faster than the configuration allows, without
        // stopping it, editing printer.cfg and restarting the host. An empty field is left alone,
        // which is what SET_VELOCITY_LIMIT does with a parameter it is not given.
        //
        var accel by rememberSaveable { mutableStateOf("") }
        var velocity by rememberSaveable { mutableStateOf("") }
        var corner by rememberSaveable { mutableStateOf("") }
        val newAccel = parseDecimal(accel)
        val newVelocity = parseDecimal(velocity)
        val newCorner = parseDecimal(corner)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KlipperNumberField(
                label = "Accel",
                value = accel,
                onValueChange = { typed -> accel = limitDigits(typed) },
                suffix = "mm/s²",
                enabled = state.isReady,
            )
            KlipperNumberField(
                label = "Top speed",
                value = velocity,
                onValueChange = { typed -> velocity = limitDigits(typed) },
                suffix = "mm/s",
                enabled = state.isReady,
            )
            KlipperNumberField(
                label = "Corner",
                value = corner,
                onValueChange = { typed -> corner = limitDigits(typed) },
                suffix = "mm/s",
                enabled = state.isReady,
            )
        }
        KlipperButtons {
            KlipperButton(
                text = "Apply limits",
                enabled = state.isReady && (newAccel != null || newVelocity != null || newCorner != null),
            ) {
                viewModel.setVelocityLimits(
                    maxVelocity = newVelocity,
                    maxAccel = newAccel,
                    squareCornerVelocity = newCorner,
                )
            }
        }
        KlipperNote(
            "Only the values you fill in change, and they last until the host restarts - which " +
                "re-reads printer.cfg. Lowering Accel is what settles a print that is ringing or " +
                "shaking; the file's own M204 still applies underneath it.",
        )

        if (limits.ignoredMarlinLimits.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            KlipperNote(
                "This file also carries " + limits.ignoredMarlinLimits.joinToString(", ") +
                    ". Those are Marlin's commands and klippy has none of them: it answers " +
                    "\"Unknown command\" and moves at the printer's own limits instead.",
            )
        }
    }
}

/**
 * What can be left out of the print that is running.
 *
 * Only during a print with [exclude_object] configured, and only for the objects the
 * file declared: klippy refuses a name it has never been told about, which is a
 * mistake worth not being able to make.
 */
@Composable
private fun ExcludedObjectsCard(state: KlipperPrinterState, onExclude: (String) -> Unit) {
    val objects = state.plateObjects
    // Also while paused: pausing to look at the plate and leave out the part that failed is
    // the workflow this card exists for, and the command works in either state.
    if (objects.isEmpty() || !(state.isPrinting || state.isPaused)) return
    val excluded = state.excludedObjects
    KlipperCard(
        title = "Objects on the plate",
        subtitle = "Tap one to leave it out of this print",
    ) {
        KlipperButtons {
            objects.forEach { name ->
                val skipped = name in excluded
                KlipperButton(
                    text = if (skipped) "\u2717 $name" else name,
                    enabled = !skipped,
                    onClick = { onExclude(name) },
                )
            }
        }
    }
}

/** Where the head is, and whether it knows. */
@Composable
private fun PositionCard(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    KlipperCard(title = "Position") {
        val position = state.position
        KlipperValue(
            "X Y Z",
            if (position.size < 3) "-"
            else "%.1f  %.1f  %.1f".format(position[0], position[1], position[2]),
        )
        KlipperValue("Homing", if (state.isHomed) "Homed" else "Not homed")
        state.zOffset?.let { offset -> KlipperValue("Z offset", "%.3f mm".format(offset)) }
        //
        // Babystepping, on the screen a first layer is watched from: the same Z_ADJUST the Z
        // probe screen sends, so a layer that is too close can be walked away from the bed
        // without leaving the print to find another screen. The offset lasts until the host
        // restarts - the Z probe screen is where a good one becomes the probe's own zero.
        //
        if (state.isHomed) {
            Spacer(Modifier.height(8.dp))
            KlipperButtons {
                KlipperButton("−0.05") { viewModel.adjustZOffset(-0.05, true) }
                KlipperButton("−0.01") { viewModel.adjustZOffset(-0.01, true) }
                KlipperButton("+0.01") { viewModel.adjustZOffset(0.01, true) }
                KlipperButton("+0.05") { viewModel.adjustZOffset(0.05, true) }
            }
            KlipperButtons {
                KlipperButton("Reset the offset") { viewModel.resetZOffset() }
            }
            KlipperNote(
                "Each nudge moves the head as it prints: down if the layer is not being " +
                    "squashed onto the plate, up if it is being dragged through. Reset puts it " +
                    "back to the probe's zero.",
            )
        }
    }
}

/** The host device's own battery and temperature, as the system reports them. */
private data class DeviceReading(val percent: Int?, val temperatureCelsius: Double?)

/**
 * Read the battery from the sticky broadcast.
 *
 * ACTION_BATTERY_CHANGED can be read without registering a receiver - the system keeps the last
 * one - so a screen that redraws every second may as well ask rather than listen.
 */
private fun readBattery(context: Context): DeviceReading? {
    val intent = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull() ?: return null
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
    val celsius = if (tenths != Int.MIN_VALUE) tenths / 10.0 else null
    if (percent == null && celsius == null) return null
    return DeviceReading(percent, celsius)
}

/**
 * The host link's margins, which is what decides whether this device can drive the
 * printer rather than the printer waiting on it.
 *
 * Shown only once klippy has written a stats line, which needs a micro-controller on
 * the other end: before that there is nothing to report and a row of dashes would be
 * worse than an absent card.
 */
@Composable
private fun TimingCard(state: KlipperPrinterState) {
    val timing = state.timing ?: return
    KlipperCard(title = "Host link") {
        //
        // The phone is the host, so its own health belongs beside the timing it explains: a
        // device that is hot enough to throttle its processor shows up here first, as stalls
        // and a shrinking lookahead, and the cause is worth naming before the symptoms are
        // hunted for in the printer.
        //
        val context = LocalContext.current
        val battery = remember(timing) { readBattery(context) }
        battery?.let { reading ->
            val hot = reading.temperatureCelsius?.let { it >= 42.0 } == true
            KlipperValue(
                label = "This device",
                value = listOfNotNull(
                    reading.percent?.let { "$it %" },
                    reading.temperatureCelsius?.let { "%.1f °C".format(it) },
                ).joinToString("  "),
                valueColor = if (hot) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            if (hot) {
                KlipperNote(
                    "It is running hot, and a phone that throttles its processor shows it in " +
                        "the link first: stalls, and a lookahead that will not stay up.",
                )
            }
        }
        // klippy's own buffer_time, against the marks it acts on.
        state.lookaheadSeconds?.let { lookahead ->
            KlipperValue(
                label = "Lookahead",
                value = "%.2f s".format(lookahead),
                // Judged by the state, which knows an idle printer has a negative
                // lookahead by construction and is not starving. Comparing the raw
                // figure here painted a ready machine red.
                valueColor = if (state.lookaheadIsHealthy) Color.Unspecified
                else MaterialTheme.colorScheme.error,
            )
        }
        val stalls = state.printStalls ?: 0
        if (stalls > 0) {
            KlipperValue("Stalled", "$stalls times", valueColor = MaterialTheme.colorScheme.error)
        }
        timing.roundTripSeconds?.let { KlipperValue("Round trip", "%.1f ms".format(it * 1000)) }
        timing.jitterSeconds?.let { KlipperValue("Jitter", "±%.1f ms".format(it * 1000)) }
        timing.retransmitTimeoutSeconds?.let { KlipperValue("Resend after", "%.0f ms".format(it * 1000)) }
        timing.headroom?.let { KlipperValue("Headroom", "%.0f×".format(it)) }
        // Seconds awake in the five-second window the board reports over, so a duty cycle.
        timing.mcuAwake?.let { awake ->
            KlipperValue("Board busy", "%.0f%%".format(awake / 5.0 * 100))
        }
        val retransmits = timing.retransmittedBytes ?: 0
        if (retransmits > 0) {
            KlipperValue(
                "Retransmitted",
                "$retransmits bytes",
                valueColor = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The one action that has to be asked about first. */
@Composable
private fun DangerCard(state: KlipperPrinterState, onEmergencyStop: () -> Unit) {
    KlipperCard(title = "If something is wrong") {
        KlipperNote(
            "An emergency stop halts the micro-controller at once. Use it when the " +
                "machine is doing something it should not, not to end a print.",
        )
        Spacer(Modifier.height(8.dp))
        KlipperButtons {
            KlipperButton("Emergency stop", onClick = onEmergencyStop)
        }
    }
}

/** A field that takes digits and a decimal mark and nothing else. */
private fun limitDigits(typed: String): String =
    typed.filter { it.isDigit() || it == '.' || it == ',' }.take(7)

/** Filament used, in metres, which is how a spool is measured. */
private fun filamentMetres(state: KlipperPrinterState): Double? =
    state.printFilamentUsed?.div(1000.0)?.takeIf { it > 0.0 }

/** The two factors' own limits, which are Mainsail's. */
private val SPEED_RANGE = 20f..200f
private val FLOW_RANGE = 50f..150f

/** The green the app already uses for "this is fine". */
private val READY_GREEN = Color(0xFF2E7D32)
