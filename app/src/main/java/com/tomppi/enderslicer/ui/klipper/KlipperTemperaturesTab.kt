package com.tomppi.enderslicer.ui.klipper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomppi.enderslicer.printer.KlipperHeater
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.genericFans
import com.tomppi.enderslicer.printer.KlipperTemperatureSample
import com.tomppi.enderslicer.printer.KlipperViewModel
import com.tomppi.enderslicer.printer.heaters
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Every temperature the printer reports, and the controls for the ones that take one.
 *
 * The chart is drawn from a log kept at one sample a second: a heater settling at its
 * target, a bed overshooting, or a hotend that cannot hold temperature while filament
 * goes through it are all shapes, and none of them can be seen in a number that is only
 * ever the value now.
 *
 * The sensors a printer has are its own configuration, so this screen shows whatever it
 * reports rather than a hotend and a bed: a chamber sensor, a board sensor and a second
 * extruder all appear on their own.
 */
@Composable
internal fun KlipperTemperaturesTab(state: KlipperPrinterState, viewModel: KlipperViewModel) {
    var pidFor by remember { mutableStateOf<KlipperHeater?>(null) }
    // Collected here rather than passed in: the chart is the only reader, and a
    // reading a second should not rebuild the screens that are not showing it.
    val history by viewModel.temperatures.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TemperatureChartCard(state.heaters, history)
        if (state.heaters.isEmpty()) {
            KlipperCard(title = "Temperatures") {
                KlipperNote(
                    if (state.connected) "This printer has not reported a temperature yet."
                    else "The host is not reachable, so there is nothing to read.",
                )
            }
        }
        state.heaters.forEach { heater ->
            HeaterCard(
                heater = heater,
                enabled = state.connected,
                onSet = { viewModel.setHeaterTemperature(heater.name, it) },
                onCalibrate = { pidFor = heater },
            )
        }
        if (state.heaters.any { it.isHeater }) {
            //
            // Fans the configuration named itself. They were subscribed to and never shown,
            // so a fan on the machine could only be driven from the console - which is the
            // opposite of what this app promises about the sections in a printer.cfg.
            //
            state.genericFans.forEach { (name, speed) ->
                KlipperCard(title = name, subtitle = "Fan the printer names itself") {
                    KlipperSlider(
                        label = "Speed",
                        value = ((speed ?: 0.0) * 100).toFloat().coerceIn(0f, 100f),
                        range = 0f..100f,
                        valueText = "%.0f%%".format((speed ?: 0.0) * 100),
                        onSet = { percent -> viewModel.setGenericFan(name, percent / 100.0) },
                    )
                }
            }

            KlipperCard(title = "All of them") {
                KlipperButtons {
                    KlipperButton("Cool everything down") { viewModel.coolDown() }
                }
            }
        }
    }

    pidFor?.let { heater ->
        PidDialog(
            heater = heater,
            onRun = { target ->
                viewModel.calibratePid(heater.name, target)
                pidFor = null
            },
            onDismiss = { pidFor = null },
        )
    }
}

/** A label and a reading, the shape both this screen and the dashboard use. */
@Composable
internal fun HeaterReadoutRow(heater: KlipperHeater) {
    val reading = heater.temperature?.let { "%.1f °C".format(it) } ?: "-"
    val target = heater.target?.takeIf { it > 0.0 }?.let { " → %.0f °C".format(it) }.orEmpty()
    val power = heater.power?.takeIf { it > 0.0 }?.let { "  (%.0f%%)".format(it * 100) }.orEmpty()
    KlipperValue(heater.label, reading + target + power)
}

/** One heater or sensor, with its presets and its own calibration. */
@Composable
private fun HeaterCard(
    heater: KlipperHeater,
    enabled: Boolean,
    onSet: (Int) -> Unit,
    onCalibrate: () -> Unit,
) {
    var typed by remember(heater.name) { mutableStateOf("") }
    KlipperCard(
        title = heater.label,
        subtitle = heater.name,
    ) {
        HeaterReadoutRow(heater)
        if (!heater.isHeater) {
            KlipperNote("A sensor: it reports a temperature and takes no target.")
            return@KlipperCard
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KlipperNumberField(
                label = "Target",
                value = typed,
                onValueChange = { typed = it.filter { character -> character.isDigit() }.take(3) },
                suffix = "°C",
                enabled = enabled,
            )
            KlipperButton(
                text = "Set",
                enabled = enabled && typed.isNotBlank(),
                onClick = { typed.toIntOrNull()?.let(onSet) },
            )
        }
        Spacer(Modifier.height(4.dp))
        KlipperButtons {
            KlipperButton("Off", enabled = enabled) { onSet(0) }
            // The two the app already offered, plus the one most printers end up using.
            val hot = heater.name != "heater_bed"
            KlipperButton("PLA", enabled = enabled) { onSet(if (hot) 200 else 60) }
            KlipperButton("PETG", enabled = enabled) { onSet(if (hot) 240 else 80) }
            KlipperButton("ABS", enabled = enabled) { onSet(if (hot) 250 else 100) }
            if (heater.name == "extruder" || heater.name == "heater_bed") {
                KlipperButton("Tune PID", enabled = enabled, onClick = onCalibrate)
            }
        }
    }
}

/**
 * The last five minutes, one line per temperature.
 *
 * Drawn rather than plotted by a library: the whole chart is a few hundred line
 * segments and no dependency, and a printer screen is not the place to add one.
 */
@Composable
private fun TemperatureChartCard(heaters: List<KlipperHeater>, history: List<KlipperTemperatureSample>) {
    val names = heaters.map { it.name }
    val ceiling = chartCeiling(history)
    KlipperCard(
        title = "Last five minutes",
        subtitle = "0 to %.0f °C, one reading a second".format(ceiling),
    ) {
        if (history.size < 2) {
            KlipperNote("Collecting readings; the chart appears after a few seconds.")
            return@KlipperCard
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
            val inset = 6.dp.toPx()
            val left = inset
            val right = size.width - inset
            val top = inset
            val bottom = size.height - inset
            val width = right - left
            val height = bottom - top
            val grid = Color.Gray.copy(alpha = 0.25f)

            // A line every 50 degrees, so a value can be read off without labels.
            var step = 50.0
            while (step < ceiling) {
                val y = bottom - height * (step / ceiling).toFloat()
                drawLine(grid, Offset(left, y), Offset(right, y), strokeWidth = 1f)
                step += 50.0
            }

            val first = history.first().atMillis
            val span = (history.last().atMillis - first).coerceAtLeast(1L)
            val series = names.ifEmpty { history.last().temperatures.keys.sorted() }
            series.forEachIndexed { index, name ->
                val path = Path()
                var started = false
                history.forEach { sample ->
                    val value = sample.temperatures[name] ?: return@forEach
                    val x = left + width * ((sample.atMillis - first).toFloat() / span)
                    val y = bottom - height * (value.toFloat() / ceiling).toFloat().coerceIn(0f, 1f)
                    if (started) path.lineTo(x, y) else { path.moveTo(x, y); started = true }
                }
                if (started) {
                    drawPath(
                        path = path,
                        color = SERIES_COLOURS[index % SERIES_COLOURS.size],
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        names.forEachIndexed { index, name ->
            val current = history.lastOrNull()?.temperatures?.get(name)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(SERIES_COLOURS[index % SERIES_COLOURS.size], CircleShape),
                )
                Text(
                    text = heaters.getOrNull(index)?.label ?: name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    text = current?.let { "%.1f °C".format(it) } ?: "-",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The top of the chart: above the hottest reading so far, and never above 250. */
private fun chartCeiling(history: List<KlipperTemperatureSample>): Double {
    val hottest = history.maxOfOrNull { sample -> sample.temperatures.values.maxOrNull() ?: 0.0 } ?: 0.0
    return max(250.0, ceil((hottest + 10) / 50.0) * 50)
}

/**
 * PID tuning, which is the one calibration a user starts from a temperature screen.
 *
 * It runs for minutes with the heater cycling, and it ends by asking to be saved: the
 * values live in the config file, so they survive only if that is written out.
 */
@Composable
private fun PidDialog(heater: KlipperHeater, onRun: (Int) -> Unit, onDismiss: () -> Unit) {
    var target by remember(heater.name) {
        mutableStateOf(
            when {
                heater.target != null && heater.target > 0.0 -> heater.target.roundToInt().toString()
                heater.name == "heater_bed" -> "60"
                else -> "200"
            },
        )
    }
    KlipperCard(title = "Tune " + heater.label, subtitle = "PID calibration") {
        KlipperNote(
            "The heater cycles around the target for a few minutes; the printer must be " +
                "at room temperature and not printing. Run it, then save the result.",
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            KlipperNumberField(
                label = "Target",
                value = target,
                onValueChange = { typed -> target = typed.filter { it.isDigit() }.take(3) },
                suffix = "°C",
            )
            KlipperButton(
                text = "Start",
                enabled = target.toIntOrNull() != null,
                onClick = { target.toIntOrNull()?.let(onRun) },
            )
            KlipperButton("Cancel", onClick = onDismiss)
        }
        Spacer(Modifier.height(8.dp))
        KlipperNote(
            "When it finishes, use Machine → Save configuration, or the new values are " +
                "lost the next time the host restarts.",
        )
    }
}

/** Six colours, in the order temperatures are listed: hotend, bed, then the rest. */
private val SERIES_COLOURS = listOf(
    Color(0xFFE53935),
    Color(0xFF1E88E5),
    Color(0xFF43A047),
    Color(0xFFFB8C00),
    Color(0xFF8E24AA),
    Color(0xFF00897B),
)
