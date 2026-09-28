package com.tomppi.enderslicer.ui.klipper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomppi.enderslicer.printer.KlipperConfigFile
import com.tomppi.enderslicer.printer.formatDecimal
import com.tomppi.enderslicer.printer.parseDecimal
import com.tomppi.enderslicer.printer.KlipperMeasurementState
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperResonanceMeasurement
import com.tomppi.enderslicer.printer.KlipperShaper
import com.tomppi.enderslicer.printer.ResonanceAnalysis
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.printer.canMeasureResonances
import com.tomppi.enderslicer.printer.shapers
import kotlinx.coroutines.launch

/**
 * Input shaping: the pattern the steppers are driven with, so that a machine's own ringing
 * cancels itself instead of appearing in the print.
 *
 * Two numbers per axis describe it - a shaper type and the frequency that type is tuned to -
 * and both are set by hand as often as they are measured: the numbers usually come from a
 * ringing test, from a calibration done elsewhere, or from the printer's own tuning, and the
 * effect of changing one is visible in the next print. An accelerometer makes finding them
 * easier and is not required to use them.
 *
 * What this screen cannot read is what the printer is *currently* using: klippy publishes no
 * status for the input shaper at all - its status object is empty - so the values shown here
 * are the ones in the printer's configuration, and the live ones have to be asked for, which
 * the printer answers in the console.
 */
@Composable
internal fun KlipperShapingTab(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    val scope = rememberCoroutineScope()
    val configured = state.shapers
    // rememberSaveable rather than remember, and seeded only once: these fields hold what the
    // user took from a measurement, and a tab switch used to throw that away and re-derive the
    // configured value - which the next save then wrote. The seed is the printer's own value,
    // used when the field has never been filled.
    var xType by rememberSaveable { mutableStateOf(ShaperEdit.seedType(configured, "x")) }
    var xFrequency by rememberSaveable { mutableStateOf(ShaperEdit.seedFrequency(configured, "x")) }
    var xDamping by rememberSaveable { mutableStateOf(ShaperEdit.seedDamping(configured, "x")) }
    var yType by rememberSaveable { mutableStateOf(ShaperEdit.seedType(configured, "y")) }
    var yFrequency by rememberSaveable { mutableStateOf(ShaperEdit.seedFrequency(configured, "y")) }
    var yDamping by rememberSaveable { mutableStateOf(ShaperEdit.seedDamping(configured, "y")) }
    val x = ShaperEdit(xType, xFrequency, xDamping)
    val y = ShaperEdit(yType, yFrequency, yDamping)
    fun editX(edit: ShaperEdit) {
        xType = edit.type; xFrequency = edit.frequencyText; xDamping = edit.dampingText
    }
    fun editY(edit: ShaperEdit) {
        yType = edit.type; yFrequency = edit.frequencyText; yDamping = edit.dampingText
    }
    var confirmSave by rememberSaveable { mutableStateOf(false) }
    var measureAxis by rememberSaveable { mutableStateOf("x") }
    var onToolhead by rememberSaveable { mutableStateOf(false) }
    // The carriage on X, the bed on Y: what the axis actually has to move, which is what the
    // phone's own weight has to be measured against.
    var movingMassX by rememberSaveable { mutableStateOf("350") }
    // Not the stock glass bed's figure: this printer has a magnetic sheet with a spring-steel
    // PEI plate on it, about 250 g where the glass is about 550 g, plus the carriage. The
    // correction is the square root of a ratio, so being 300 g out moves the answer by about
    // a tenth - which is the size of the disagreement the screen already warns about.
    var movingMassY by rememberSaveable { mutableStateOf("400") }
    val movingMass = if (measureAxis == "y") movingMassY else movingMassX
    fun setMovingMass(value: String) {
        if (measureAxis == "y") movingMassY = value else movingMassX = value
    }
    // From the view model rather than from here: a sweep takes a minute, and a result held by
    // the screen is a result that disappears when the screen does.
    val measurementState by viewModel.measurement.collectAsStateWithLifecycle()
    val measurementHistory by viewModel.measurementHistory.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MeasurementCard(measurementState, measurementHistory, viewModel) { axis, frequency ->
            // Written with a dot so the app can read its own value back; shown to a Finnish
            // user as 89,8, the field would have parsed as nothing and Apply stayed disabled.
            val text = formatDecimal(frequency, 1)
            if (axis == "Y") editY(y.copy(frequencyText = text)) else editX(x.copy(frequencyText = text))
        }

        KlipperCard(title = "Input shaping", subtitle = "Why the printer is set up this way") {
            KlipperNote(
                "A move that stops at a corner rings, and the ringing prints as ripples beside " +
                    "the corner. Input shaping drives the steppers with a pattern that cancels " +
                    "that ring: a type, and the frequency to cancel. Wrong values make the " +
                    "ripples worse, so change one axis at a time and print something with " +
                    "corners.",
            )
        }

        ShaperCard(
            axis = "X",
            edit = x,
            // Not while a sweep is running: the sweep disables shaping and restores what it
            // stashed when it finishes, so an apply now would be undone a minute later with
            // nothing on screen to say so.
            enabled = state.isReady && measurementState !is KlipperMeasurementState.Measuring,
            onEdit = ::editX,
            onApply = { edit ->
                edit.frequency()?.let { frequency ->
                    viewModel.applyShaper("x", edit.type, frequency, edit.damping())
                }
            },
        )
        ShaperCard(
            axis = "Y",
            edit = y,
            enabled = state.isReady && measurementState !is KlipperMeasurementState.Measuring,
            onEdit = ::editY,
            onApply = { edit ->
                edit.frequency()?.let { frequency ->
                    viewModel.applyShaper("y", edit.type, frequency, edit.damping())
                }
            },
        )

        KlipperCard(title = "Saving it") {
            KlipperNote(
                "Applying changes what the printer is doing now; the host forgets it when it " +
                    "restarts. Saving writes both axes into [input_shaper] in printer.cfg, " +
                    "which is where a restart reads them from.",
            )
            Spacer(Modifier.height(8.dp))
            KlipperButtons {
                KlipperButton(
                    text = "Save and restart",
                    enabled = x.frequency() != null && y.frequency() != null,
                    onClick = { confirmSave = true },
                )
                KlipperButton("Ask the printer", enabled = state.connected) {
                    viewModel.reportShapers()
                }
            }
            Spacer(Modifier.height(8.dp))
            KlipperNote(
                "Ask the printer sends SET_INPUT_SHAPER with no values, which makes klippy " +
                    "report what it is using - including anything set since the last restart. " +
                    "The answer appears in the console.",
            )
        }

        KlipperCard(
            title = "Measure with this phone",
            subtitle = "The accelerometer the printer has not got",
        ) {
            KlipperNote(
                "Put the phone on the gantry for X, or on the base for Y. Not on the toolhead " +
                    "and not on the bed: there its own weight would change the resonance it is " +
                    "trying to measure. The gantry is the best of them, being the rail the " +
                    "toolhead reacts against and heavy enough that 253 grams barely moves it.",
            )
            KlipperNote(
                "The printer then plays Klipper's own sweep while the phone records itself, " +
                    "and what comes back is where the machine answers. Measure two or three " +
                    "times from different places: the frequency that keeps coming back is the " +
                    "machine's, and the rest is where the phone was standing.",
            )
            Spacer(Modifier.height(8.dp))
            KlipperButtons {
                listOf("x", "y").forEach { axis ->
                    KlipperButton(
                        text = if (axis == measureAxis) "• Axis " + axis.uppercase() else "Axis " + axis.uppercase(),
                        onClick = { measureAxis = axis },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            KlipperButtons {
                KlipperButton(
                    text = if (onToolhead) "• Phone on the moving part" else "Phone on the moving part",
                    onClick = { onToolhead = !onToolhead },
                )
            }
            if (onToolhead) {
                KlipperNote(
                    "The phone rides the carriage, so its weight lowers the frequency it is " +
                        "measuring, and the sweep is run at half strength so that the belts are " +
                        "not asked for more force than they have ever carried. Both are " +
                        "corrected for below, from the moving mass.",
                )
                KlipperNumberField(
                    label = "Moving mass",
                    value = movingMass,
                    onValueChange = { typed ->
                        setMovingMass(typed.filter { it.isDigit() }.take(4))
                    },
                    suffix = "g",
                )
                KlipperNote(
                    if (measureAxis == "y") {
                        "The bed: plate, heater, carriage, wheels and whatever sits on top. An " +
                            "Ender-3 V2 shipped with a glass plate of about 550 g, where a " +
                            "magnetic sheet and a spring steel PEI plate come to roughly 250 - " +
                            "so a lighter bed than stock, and a phone on it very nearly puts " +
                            "the weight back. Weigh the parts, or measure twice with a known " +
                            "weight added to the bed and let the machine work its own mass out."
                    } else {
                        "Carriage, hotend, extruder, duct and probe: about 350 g with an Orbiter " +
                            "v2, against roughly 210 on a stock Ender-3 V2, which had no motor on " +
                            "the carriage at all. Weigh the parts if you can - the correction is " +
                            "only as good as this number."
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            KlipperButtons {
                KlipperButton(
                    text = if (measurementState is KlipperMeasurementState.Measuring) {
                        "Measuring…"
                    } else {
                        "Measure axis " + measureAxis.uppercase()
                    },
                    // Not while a print is running: the sweep drives the toolhead to the middle
                    // of the travel and holds ACCEL at the sweep maximum for a minute, so it
                    // has no business interleaving with a print that is under way.
                    enabled = state.isReady &&
                        state.isHomed &&
                        !state.isPrinting &&
                        measurementState !is KlipperMeasurementState.Measuring,
                    onClick = {
                        // The run belongs to the view model: leaving this screen does not
                        // cancel it, and coming back finds it where it got to.
                        viewModel.measureResonances(
                            axis = measureAxis,
                            freqStart = SWEEP_START_HZ,
                            freqEnd = SWEEP_END_HZ,
                            hzPerSec = SWEEP_HZ_PER_SEC,
                            onToolhead = onToolhead,
                            // Bounded, because 0 g means the correction divides by zero:
                            // every frequency came back infinite, "Use ..." wrote the word
                            // "Infinity" into the field, and that field cannot be parsed.
                            movingMassGrams = parseDecimal(movingMass)
                                ?.takeIf { it in 50.0..5000.0 }
                                ?: 350.0,
                        )
                    },
                )
            }
            KlipperNote(
                "About " + SWEEP_SECONDS + " seconds, and the numbers appear at the top of " +
                    "this screen when they are ready - they stay there, so you can go and " +
                    "watch the sweep from another tab while it runs.",
            )
        }

        if (!state.canMeasureResonances) {
            // Not "this printer cannot be measured": the card above does exactly that, with the
            // phone. What this printer lacks is the wired accelerometer Klipper's own
            // calibration wants.
            KlipperCard(title = "Finding the numbers", subtitle = "No accelerometer wired to it") {
                KlipperNote(
                    "Klipper can measure the frequencies itself, with SHAPER_CALIBRATE - but " +
                        "that needs an accelerometer wired to the board and a [resonance_tester] " +
                        "section, and this printer has neither. The phone above is the way round " +
                        "it. The other way is a ringing test by eye: print a tall shape fast, " +
                        "measure the distance between the ripples it leaves, and divide the " +
                        "speed by that distance - the result is the frequency to put here.",
                )
            }
        }
    }

    if (confirmSave) {
        KlipperConfirmDialog(
            title = "Save input shaping?",
            text = "Both axes are written into printer.cfg and the host restarts to read " +
                "them. The printer is unavailable for a few seconds, and a print in progress " +
                "is lost.",
            confirmLabel = "Save and restart",
            onConfirm = {
                scope.launch {
                    val saved = viewModel.saveShapers(
                        listOf(
                            KlipperConfigFile.ShaperSetting("x", x.type, x.frequency() ?: 0.0, x.damping()),
                            KlipperConfigFile.ShaperSetting("y", y.type, y.frequency() ?: 0.0, y.damping()),
                        ),
                    )
                    if (saved) viewModel.restartHost()
                }
            },
            onDismiss = { confirmSave = false },
        )
    }
}

/** One axis: what it is set to, and the fields that change it. */
@Composable
private fun ShaperCard(
    axis: String,
    edit: ShaperEdit,
    enabled: Boolean,
    onEdit: (ShaperEdit) -> Unit,
    onApply: (ShaperEdit) -> Unit,
) {
    KlipperCard(title = "Axis $axis") {
        KlipperNote(
            "Each type has a lowest frequency that means anything: zv from 21 Hz, mzv from " +
                "23, zvd and ei from 29, 2hump_ei from 39, 3hump_ei from 48.",
        )
        Spacer(Modifier.height(8.dp))
        // The selected one is marked rather than styled: a chip that looks different is a
        // chip that has to be read anyway.
        KlipperButtons {
            SHAPER_TYPES.forEach { type ->
                KlipperButton(
                    text = if (type == edit.type) "• $type" else type,
                    onClick = { onEdit(edit.copy(type = type)) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KlipperNumberField(
                label = "Frequency",
                value = edit.frequencyText,
                onValueChange = { typed ->
                            onEdit(
                                edit.copy(
                                    frequencyText = typed
                                        .filter { it.isDigit() || it == '.' || it == ',' }
                                        .take(6),
                                ),
                            )
                        },
                suffix = "Hz",
                enabled = enabled,
            )
            KlipperNumberField(
                label = "Damping",
                value = edit.dampingText,
                onValueChange = { typed ->
                            onEdit(
                                edit.copy(
                                    dampingText = typed
                                        .filter { it.isDigit() || it == '.' || it == ',' }
                                        .take(6),
                                ),
                            )
                        },
                enabled = enabled,
            )
        }
        Spacer(Modifier.height(8.dp))
        KlipperButtons {
            KlipperButton(
                text = "Apply to axis $axis",
                enabled = enabled && edit.frequency() != null,
                onClick = { onApply(edit) },
            )
        }
        edit.frequency()?.let { frequency ->
            val minimum = SHAPER_MINIMUM[edit.type]
            if (minimum != null && frequency < minimum) {
                Spacer(Modifier.height(4.dp))
                KlipperNote(
                    "Below " + minimum + " Hz this shaper stops helping: it would push the " +
                        "ringing rather than cancel it.",
                )
            }
        }
    }
}

/**
 * The measurement, wherever it has got to.
 *
 * At the top of the screen rather than inside the card that starts it: the run takes a minute,
 * and the answer to "where do the numbers appear" has to be somewhere a user will look without
 * being told.
 */
@Composable
private fun MeasurementCard(
    state: KlipperMeasurementState,
    history: List<KlipperResonanceMeasurement>,
    viewModel: KlipperViewModel,
    onUse: (String, Double) -> Unit,
) {
    when (state) {
        is KlipperMeasurementState.Idle -> Unit
        is KlipperMeasurementState.Measuring -> KlipperCard(
            title = "Measuring axis " + state.axis,
            subtitle = "%.0f of %.0f seconds".format(state.elapsedSeconds, state.totalSeconds),
        ) {
            LinearProgressIndicator(
                progress = { state.progress.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            KlipperNote(
                "Leave the phone where it is: the sweep starts as a slow sway and ends as a " +
                    "buzz, and the recording is what is being measured.",
            )
        }
        is KlipperMeasurementState.Failed -> KlipperCard(
            title = "Measuring axis " + state.axis,
            subtitle = "Nothing was measured",
        ) {
            KlipperNote(state.message, color = MaterialTheme.colorScheme.error)
        }
        is KlipperMeasurementState.Done -> {
            val axis = state.measurement.axis
            val runs = history.filter { it.axis == axis }
            val agreed = ResonanceAnalysis.agreeing(runs.map { it.peaks })
            KlipperCard(
                title = "What axis " + axis + " answered at",
                subtitle = "Measured with " + state.measurement.sensorName,
            ) {
                MeasurementResult(state.measurement) { frequency -> onUse(axis, frequency) }
                if (runs.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    KlipperNote(
                        "Across " + runs.size + " measurements, newest last: where the phone " +
                            "is standing decides which peaks are loud, so what matters is the " +
                            "frequency that keeps coming back.",
                    )
                    if (agreed.isEmpty()) {
                        KlipperNote(
                            "Nothing has come back yet: move the phone somewhere else on the " +
                                "base and measure again.",
                        )
                    } else {
                        agreed.forEach { peak ->
                            KlipperValue(
                                label = "%.1f Hz".format(peak.frequencyHz),
                                value = "in " + peak.seenIn + " of " + peak.ofRuns,
                            )
                        }
                        KlipperButtons {
                            agreed.forEach { peak ->
                                KlipperButton("Use %.1f Hz".format(peak.frequencyHz)) {
                                    onUse(axis, peak.frequencyHz)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    KlipperButtons {
                        KlipperButton("Forget these measurements") { viewModel.clearMeasurements() }
                    }
                }
            }
        }
    }
}

/** What the phone heard, and the frequencies it is offering to try. */
@Composable
private fun MeasurementResult(
    measurement: KlipperResonanceMeasurement,
    onUse: (Double) -> Unit,
) {
    KlipperValue("Sampled at", "%.0f Hz".format(measurement.sampleRateHz))
    KlipperValue("Machine heard at", "%.1f s into the recording".format(measurement.movedAt))
    KlipperValue("Sweep start from", measurement.startFrom)
    if (measurement.onToolhead) {
        KlipperValue(
            label = "Phone on the moving part",
            value = "%.0f g on %.0f g moves each frequency up by %.0f%%".format(
                253.0, measurement.movingMassGrams,
                (measurement.massCorrection - 1.0) * 100.0,
            ),
        )
        KlipperNote(
            "The frequencies below have been corrected for the phone's own weight, which is " +
                "an estimate from a single-mass model - the mode is not only " +
                (if (measurement.axis == "Y") "the bed" else "the toolhead") +
                " on the belt. Treat it as a place to start, not as a calibration.",
        )
    }
    if (measurement.saturated) {
        KlipperNote(
            "The phone's sensor reached its own limit during this recording, so the numbers " +
                "are clipped and should not be used: run the sweep with the phone lower down " +
                "the machine.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (measurement.peaks.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        KlipperNote(
            "The sweep was recorded but nothing stood out of it: either the machine did not " +
                "move much, or the phone was not on it. Try the other axis, and put the " +
                "phone flat on the base.",
        )
        return
    }
    Spacer(Modifier.height(8.dp))
    KlipperNote("What the machine answered at, strongest first:")
    measurement.peaks.forEach { peak ->
        KlipperValue(
            label = "%.1f Hz".format(peak.frequencyHz),
            value = "%.0f× the rest".format(peak.signalToNoise),
        )
    }
    Spacer(Modifier.height(8.dp))
    KlipperButtons {
        measurement.peaks.forEach { peak ->
            KlipperButton("Use %.1f Hz".format(peak.frequencyHz)) { onUse(peak.frequencyHz) }
        }
    }
    Spacer(Modifier.height(8.dp))
    KlipperNote(
        "Putting one in the frequency field does not apply it: set the type you want, apply, " +
            "print something with corners, and judge it by the ripples.",
    )
}

/** What a user is editing for one axis, before it is applied. */
private data class ShaperEdit(
    val type: String,
    val frequencyText: String,
    val dampingText: String,
) {
    fun frequency(): Double? = parseDecimal(frequencyText)?.takeIf { it > 0.0 }

    /**
     * The damping ratio, which is bounded above by arithmetic rather than by taste.
     *
     * klippy accepts up to and including 1.0, and every shaper it has divides by
     * sqrt(1 - zeta^2) - so 1.0 is a division by zero. Sent, it runs inside a G-code handler
     * and klippy shuts the printer down ("Internal error on command"); written into the
     * configuration, it stops the host from starting at all, and the file has to be edited by
     * hand before the printer works again. 0.9 is the highest value that means anything and
     * it is the highest this will send.
     */
    fun damping(): Double? = parseDecimal(dampingText)?.takeIf { it > 0.0 && it <= MAX_DAMPING_RATIO }

    companion object {
        fun seedType(shapers: List<KlipperShaper>, axis: String): String =
            shapers.firstOrNull { it.axis == axis }?.type?.takeIf { it.isNotBlank() } ?: "mzv"

        fun seedFrequency(shapers: List<KlipperShaper>, axis: String): String =
            shapers.firstOrNull { it.axis == axis }?.frequency?.let { formatDecimal(it, 1) }.orEmpty()

        fun seedDamping(shapers: List<KlipperShaper>, axis: String): String =
            shapers.firstOrNull { it.axis == axis }?.dampingRatio
                ?.let { formatDecimal(it, 3) }
                .orEmpty()

        fun of(shaper: KlipperShaper?): ShaperEdit = ShaperEdit(
            type = shaper?.type?.takeIf { it.isNotBlank() } ?: "mzv",
            frequencyText = shaper?.frequency?.let { formatDecimal(it, 1) }.orEmpty(),
            dampingText = shaper?.dampingRatio?.let { formatDecimal(it, 3) }.orEmpty(),
        )
    }
}

/**
 * The sweep the phone measures with: Klipper's band, run at twice its rate.
 *
 * The band is where an Ender-class machine rings and where every shaper Klipper has is
 * useful; the rate is the generator's own maximum, which turns Klipper's 130 second sweep
 * into 50. The analysis is handed the same three numbers, and gets its frequency axis from
 * them rather than from anything the printer told it.
 */
private const val MAX_DAMPING_RATIO = 0.9

private const val SWEEP_START_HZ = 20.0
private const val SWEEP_END_HZ = 120.0
private const val SWEEP_HZ_PER_SEC = 2.0
private const val SWEEP_SECONDS = 50

/** The shapers Klipper implements, from shaper_defs.py. */
private val SHAPER_TYPES = listOf("zv", "mzv", "zvd", "ei", "2hump_ei", "3hump_ei")

/** The frequency below which each of them stops being useful, also from shaper_defs.py. */
private val SHAPER_MINIMUM = mapOf(
    "zv" to 21,
    "mzv" to 23,
    "zvd" to 29,
    "ei" to 29,
    "2hump_ei" to 39,
    "3hump_ei" to 48,
)
