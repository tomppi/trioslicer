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
import android.hardware.usb.UsbManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.printer.KlipperPrint
import com.tomppi.enderslicer.printer.PrinterUsb
import com.tomppi.enderslicer.printer.isMaterialHeater
import com.tomppi.enderslicer.printer.KlipperFileLimits
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.tomppi.enderslicer.printer.KlipperWebcam
import kotlinx.coroutines.delay
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.ContentScale

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
        // The camera, above the numbers, because a picture of the thing is the first check
        // anybody makes. The card is drawn whether or not the host has published a camera: it
        // used to be emitted only when one existed, so the card appeared once Moonraker answered
        // and everything below it moved up - on a screen that is read while it updates.
        KlipperCameraCard(state.webcams.firstOrNull(), viewModel)

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
    val context = LocalContext.current
    // The local USB bus only says anything about the printer when this device IS
    // the host. On the PC Klipper route the printer is attached to that PC, and
    // asking this phone's bus put "No printer is attached" directly above a host
    // that was running and driving one.
    //
    // When it does apply: with no printer attached, klippy reports the
    // configuration error it was always going to report - "Option 'restart_method'
    // is not valid in section 'mcu'", "Printer is halted" - which names a config
    // problem where the cause is an absent machine, and sends the user looking for
    // a mistake they did not make. The app can tell the two apart, so it does.
    // Assuming attached on failure: a USB query that cannot answer must not
    // produce a claim about the printer.
    val remoteHost = state.remoteHost
    val printerAttached = remember(remoteHost, state.connected, state.state) {
        if (remoteHost != null) {
            true
        } else {
            runCatching {
                val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                manager != null && PrinterUsb.findDrivers(manager).isNotEmpty()
            }.getOrDefault(true)
        }
    }
    KlipperCard(
        title = "Printer",
        // The card describes whichever host the app is driving. It said "running in
        // this app" even when the header above it read "Connected to 100.65.211.17".
        subtitle = if (remoteHost == null) {
            "The Klipper host running in this app"
        } else {
            "The Klipper host on $remoteHost"
        },
    ) {
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
        if (!printerAttached) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "No printer is attached, so there is nothing for the host to drive yet. " +
                    "Plug one in and it is picked up; what follows is what klippy reports with " +
                    "no board to talk to, not a mistake in your configuration.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.stateMessage.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.stateMessage.trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.remoteHost == null) {
            Spacer(Modifier.height(12.dp))
            KlipperButtons {
                // "Connected" here means this app's own klippy is answering, not that
                // a printer is attached - so it is also the only sign the host is up.
                // The start button used to be the whole block, gated on NOT being
                // connected, so the moment the host answered the control vanished and
                // the only way to stop it was to force-stop the app.
                if (state.connected) {
                    KlipperButton("Stop the printer host") { viewModel.stopHost() }
                } else {
                    // The host is normally started by the printer being plugged in; this
                    // is for when it is already plugged in, or was started before the app.
                    KlipperButton("Start the printer host") { viewModel.startHost() }
                }
            }
            // Only with something to show, and only once it has stopped: the label
            // says the host stopped with this, which is not true while it is up. It
            // used to be printed whenever the tail existed, and a blank tail rendered
            // as "The host stopped with:" and then nothing - a reason that read as
            // lost rather than one that was never written.
            if (!state.connected) {
                state.hostLogTail?.takeIf { it.isNotBlank() }?.let { tail ->
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
            // Every row here is drawn whether or not its value is known, with a dash when it is
            // not. A row that disappears takes its height with it, and on this screen that moves
            // the camera and every card below - while the values themselves come and go with each
            // status update.
            val progress = state.printProgress
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            KlipperValue(
                "Print progress",
                progress?.let { "%.1f%% of the file".format(it * 100) } ?: "—",
            )
            val seconds = state.printDurationSeconds
            KlipperValue("Print time so far", seconds?.let { formatPrintTime(it.roundToInt()) } ?: "—")
            // The estimate is only worth stating once there is enough of the print to extrapolate
            // from: a minute in, it is the noise in the progress figure - so it shows a dash.
            val knownProgress = state.printProgress
            val remaining = if (seconds != null && knownProgress != null && knownProgress > 0.02 && state.isPrinting) {
                seconds / knownProgress - seconds
            } else {
                null
            }
            KlipperValue("Time left", remaining?.let { formatPrintTime(it.roundToInt()) } ?: "—")
            KlipperValue(
                "Layer",
                state.printLayers?.let { "${it.current} of ${it.total}" } ?: "—",
            )
            KlipperValue("Filament used", filamentMetres(state)?.let { "%.2f m".format(it) } ?: "—")
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
                //
                // A start block with Marlin's UBL commands in it will home and mesh twice here.
                //
                // The file's `;FLAVOR:` line is not the signal: Orca and Prusa write
                // `;FLAVOR:Marlin` with their Klipper flavour selected, because Klipper reads
                // Marlin G-code - reading it as the firmware warned about files that were
                // sliced correctly. G29 and M420 do not have that excuse.
                //
                var marlinCommand by remember(path) { mutableStateOf<String?>(null) }
                LaunchedEffect(path) { marlinCommand = viewModel.marlinOnlyStartCommand(path) }
                KlipperValue("Ready to print", KlipperPrint.fileName(suggestedFileName))
                marlinCommand?.let { command ->
                    Spacer(Modifier.height(4.dp))
                    KlipperNote(
                        "This file's start G-code has " + command + " in it, which is " +
                            "Marlin's bed levelling. On this printer G29 is the printer's own " +
                            "macro, which homes and meshes again - so the print will home and " +
                            "probe twice. Slice it with the Klipper flavour, whose start " +
                            "script uses BED_MESH_CALIBRATE instead.",
                    )
                }
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
 * Every figure says which quantity it is, because two of these are velocities and one is an
 * acceleration, and the units alone do not say so to anyone who has not memorised them. An
 * earlier version put them in shared rows - "1500.0 mm/s · 90000 mm/min" and "300.0 mm/s ·
 * 3000 mm/s² · corners 5.0 mm/s" - which is three different quantities run together, and left
 * the reader to work out which was which.
 *
 * The velocity limit sits under the override because it is the reason the override stops
 * helping: a printer at 200% stops getting faster once its own max_velocity is reached.
 */
@Composable
private fun SpeedReadings(state: KlipperPrinterState) {
    val speeds = state.speeds
    KlipperValue(
        label = "Velocity now",
        value = speeds.live.orDash() + " mm/s, measured",
    )
    speeds.filament?.takeIf { it > 0.01 }?.let { filament ->
        KlipperValue(
            label = "Filament velocity now",
            value = filament.orDash(2) + " mm/s of filament",
        )
    }
    KlipperValue(
        label = "Velocity the file asks for",
        value = speeds.askedPerSecond.orDash() + " mm/s",
    )
    KlipperValue(
        label = "Velocity with the override",
        value = speeds.effectivePerSecond.orDash() + " mm/s",
    )
    KlipperValue(
        label = "Feedrate the file asks for",
        value = speeds.askedPerMinute.orDash(0) + " mm/min, which is what the F in the file says",
    )
    KlipperValue(
        label = "Velocity limit",
        value = speeds.maxVelocity.orDash() + " mm/s",
    )
    KlipperValue(
        label = "Acceleration limit",
        value = speeds.maxAccel.orDash(0) + " mm/s²",
    )
    KlipperValue(
        label = "Corner velocity",
        value = speeds.cornerVelocity.orDash() + " mm/s",
    )
    if (speeds.clamped) {
        KlipperNote(
            "The override asks for more than the velocity limit of " + speeds.maxVelocity.orDash() +
                " mm/s, so the moves are clamped to it - which is why more override stops " +
                "making the print faster.",
        )
    }
}

/** The three factors a print is adjusted by while it runs. */
@Composable
private fun AdjustmentsCard(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    KlipperCard(title = "Adjustments") {
        KlipperSlider(
            label = "Part fan speed",
            value = ((state.fanSpeed ?: 0.0) * 100).toFloat().coerceIn(0f, 100f),
            range = 0f..100f,
            valueText = "%.0f%% of full speed".format((state.fanSpeed ?: 0.0) * 100),
            onSet = { viewModel.setFan(it.roundToInt()) },
        )
        KlipperSlider(
            label = "Print speed factor (M220)",
            value = ((state.speedFactor ?: 1.0) * 100).toFloat().coerceIn(SPEED_RANGE),
            range = SPEED_RANGE,
            valueText = "%.0f%% of the file's speed".format((state.speedFactor ?: 1.0) * 100),
            steps = 17,
            onSet = { viewModel.setSpeedFactor(it.roundToInt()) },
        )
        SpeedReadings(state)
        KlipperSlider(
            label = "Flow factor (M221)",
            value = ((state.extrudeFactor ?: 1.0) * 100).toFloat().coerceIn(FLOW_RANGE),
            range = FLOW_RANGE,
            valueText = "%.0f%% of the file's extrusion".format((state.extrudeFactor ?: 1.0) * 100),
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
                // Off for anything with a target; material figures only where they mean something.
                KlipperButtons {
                    KlipperButton("Off") { viewModel.setHeaterTemperature(heater.name, 0) }
                    if (isMaterialHeater(heater.name)) {
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
}

/**
 * The print camera, when the host has one.
 *
 * A frame a second while this screen is open, and nothing at all when it is not: the request
 * stops with the composition, so a phone in a pocket is not downloading pictures of a printer.
 * The stream itself is left alone - a multipart response would need a decoder to draw, and one
 * frame a second is a first layer you can watch.
 */
@Composable
private fun KlipperCameraCard(camera: KlipperWebcam?, viewModel: KlipperViewModel) {
    var frame by remember(camera?.snapshotUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(camera?.snapshotUrl) {
        val url = camera?.snapshotUrl ?: return@LaunchedEffect
        while (true) {
            viewModel.webcamSnapshot(url)?.let { bytes ->
                frame = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
            delay(CAMERA_REFRESH_MS)
        }
    }
    KlipperCard(title = camera?.name ?: "Camera", subtitle = "The print camera") {
        // One shape for every state - no camera, no frame yet, or a frame - because a card that
        // grows when the first picture arrives moves the whole dashboard underneath it. The frame
        // is cropped into that shape rather than stretched.
        val picture = frame
        when {
            camera == null -> Box(modifier = Modifier.fillMaxWidth().aspectRatio(CAMERA_ASPECT)) {
                KlipperNote("No camera is configured on this host.")
            }
            picture == null -> Box(modifier = Modifier.fillMaxWidth().aspectRatio(CAMERA_ASPECT)) {
                KlipperNote("Waiting for a frame from the camera.")
            }
            else -> Image(
                bitmap = picture,
                contentDescription = "The printer, from its camera",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(CAMERA_ASPECT),
            )
        }
    }
}

/** The camera card's shape, held whatever the frame's own proportions are. */
private const val CAMERA_ASPECT = 16f / 9f

/** A frame a second: enough to watch a first layer, cheap enough to leave open. */
private const val CAMERA_REFRESH_MS = 1_000L

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
            "Acceleration the file asks for",
            limits.askedAccel?.let { "%.0f mm/s²".format(it) } ?: "none set in the file",
        )
        KlipperValue(
            "Acceleration the printer allows",
            limits.allowedAccel?.let { "%.0f mm/s²".format(it) } ?: "-",
        )
        limits.allowedVelocity?.let {
            KlipperValue("Velocity the printer allows", "%.0f mm/s".format(it))
        }
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
                label = "Acceleration limit",
                value = accel,
                onValueChange = { typed -> accel = limitDigits(typed) },
                suffix = "mm/s²",
                enabled = state.isReady,
            )
            KlipperNumberField(
                label = "Velocity limit",
                value = velocity,
                onValueChange = { typed -> velocity = limitDigits(typed) },
                suffix = "mm/s",
                enabled = state.isReady,
            )
            KlipperNumberField(
                label = "Corner velocity",
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
                "re-reads printer.cfg. Lowering the acceleration limit is what settles a print " +
                "that is ringing or shaking; the file's own M204 still applies underneath it.",
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
        if (position.size < 3) {
            KlipperValue("Toolhead position", "-")
        } else {
            KlipperValue("X position", "%.1f mm".format(position[0]))
            KlipperValue("Y position", "%.1f mm".format(position[1]))
            KlipperValue("Z position", "%.1f mm".format(position[2]))
        }
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
        // Not when the host is a computer: the phone's battery and temperature are then its
        // own business, and the card is about the link to a printer this device is not driving.
        val context = LocalContext.current
        val battery = if (state.remoteHost == null) remember(timing) { readBattery(context) } else null
        battery?.let { reading ->
            val hot = reading.temperatureCelsius?.let { it >= 42.0 } == true
            reading.percent?.let { percent ->
                KlipperValue("Battery charge", "$percent% of full")
            }
            reading.temperatureCelsius?.let { celsius ->
                KlipperValue(
                    label = "Battery temperature",
                    value = "%.1f °C".format(celsius),
                    valueColor = if (hot) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
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
                label = "Move queue lookahead",
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
            KlipperValue("Move queue stalls", "$stalls times", valueColor = MaterialTheme.colorScheme.error)
        }
        timing.roundTripSeconds?.let { KlipperValue("Round-trip time", "%.1f ms".format(it * 1000)) }
        timing.jitterSeconds?.let { KlipperValue("Round-trip jitter", "±%.1f ms".format(it * 1000)) }
        timing.retransmitTimeoutSeconds?.let { KlipperValue("Retransmit timeout", "%.0f ms".format(it * 1000)) }
        timing.headroom?.let { KlipperValue("Serial buffer headroom", "%.0f×".format(it)) }
        // Seconds awake in the five-second window the board reports over, so a duty cycle.
        timing.mcuAwake?.let { awake ->
            KlipperValue("Board busy", "%.0f%% of the last 5 s".format(awake / 5.0 * 100))
        }
        val retransmits = timing.retransmittedBytes ?: 0
        if (retransmits > 0) {
            KlipperValue(
                "Bytes retransmitted",
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
