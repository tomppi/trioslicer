package com.tomppi.enderslicer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings
import kotlin.math.roundToInt

/** The Plate session's values, in the order the cards show them. */
internal data class SessionValues(
    val layerHeight: SessionValue,
    val infill: SessionValue,
    val adhesion: SessionValue,
    val supports: SessionValue,
)

/**
 * One value of the printing session, and the editors that change it.
 *
 * The value is what the summary already shows. The editors carry the setting
 * keys of whichever engine would slice, so editing on the Plate no longer means
 * a trip to the Settings tab - and a value with two parts to it (an infill
 * density *and* its pattern) carries an editor for each.
 */
internal data class SessionValue(
    val label: String,
    val value: String,
    val detail: String,
    val editors: List<SessionValueEditor>,
)

/** Which of the session's four values an open editor belongs to. */
internal enum class SessionValueSlot {
    LAYER_HEIGHT,
    INFILL,
    SUPPORTS,
    ADHESION,
}

/**
 * The value behind [slot], looked up fresh.
 *
 * A dialog is opened from a tap and stays open while a choice applies at once,
 * so it must render the value from the current state rather than the snapshot
 * the tap captured: an infill pattern change has to move the check mark.
 */
internal operator fun SessionValues.get(slot: SessionValueSlot): SessionValue = when (slot) {
    SessionValueSlot.LAYER_HEIGHT -> layerHeight
    SessionValueSlot.INFILL -> infill
    SessionValueSlot.SUPPORTS -> supports
    SessionValueSlot.ADHESION -> adhesion
}

/** One editor of a value, under the heading it belongs to. */
internal data class SessionValueEditor(
    val section: String,
    val editor: SessionEditor,
)

internal sealed interface SessionEditor {
    /** Something to slide: the value is committed when the finger comes off. */
    data class Number(
        val value: Double,
        val range: ClosedFloatingPointRange<Float>,
        val step: Double,
        val decimals: Int,
        val unit: String,
        val set: (Double) -> Unit,
    ) : SessionEditor

    /** Something to pick: a choice applies at once, so its dialog stays open. */
    data class Choice(
        val options: List<String>,
        val selectedIndex: Int,
        val set: (Int) -> Unit,
    ) : SessionEditor
}

/**
 * The session's editable values for the engine that would slice.
 *
 * Every editor writes through the callbacks the Settings tab already uses, so
 * the two screens cannot drift apart.
 */
internal fun sessionValues(
    engine: SlicerEngine,
    summary: PlateSessionSummary,
    state: MainUiState,
    onSettings: (String, (SlicerSettings) -> SlicerSettings) -> Unit,
    onPrusaSettings: (String, (PrusaSliceSettings) -> PrusaSliceSettings) -> Unit,
    onOrcaSettings: (String, (OrcaSliceSettings) -> OrcaSliceSettings) -> Unit,
): SessionValues = when (engine) {
    SlicerEngine.CURA -> SessionValues(
        layerHeight = layerHeightValue(summary) { next ->
            onSettings(SlicerSettings.Keys.LAYER_HEIGHT) { it.copy(layerHeightMm = next) }
        },
        infill = infillValue(
            summary = summary,
            patterns = CURA_INFILL_PATTERNS.map { it.first },
            currentPattern = state.settings.infillPattern,
            setDensity = { next ->
                onSettings(SlicerSettings.Keys.INFILL_DENSITY) { it.copy(infillDensityPercent = next) }
            },
            setPattern = { pattern ->
                onSettings(SlicerSettings.Keys.INFILL_PATTERN) { it.copy(infillPattern = pattern) }
            },
        ),
        adhesion = choiceValue(
            label = "Adhesion",
            options = CURA_ADHESION.map { it.second },
            selected = CURA_ADHESION.indexOfFirst { it.first == state.settings.adhesionType },
            detail = CURA_ADHESION.firstOrNull { it.first == state.settings.adhesionType }?.second.orEmpty(),
        ) { index ->
            onSettings(SlicerSettings.Keys.ADHESION_TYPE) {
                it.copy(adhesionType = CURA_ADHESION[index].first)
            }
        },
        supports = supportsValue(summary) { on ->
            onSettings(SlicerSettings.Keys.SUPPORTS_ENABLED) { it.copy(supportsEnabled = on) }
        },
    )

    SlicerEngine.PRUSA -> SessionValues(
        layerHeight = layerHeightValue(summary) { next ->
            onPrusaSettings(PrusaSliceSettings.Keys.LAYER_HEIGHT) { it.copy(layerHeightMm = next) }
        },
        infill = infillValue(
            summary = summary,
            patterns = PrusaSliceSettings.FILL_PATTERNS,
            currentPattern = state.prusaSettings.fillPattern,
            setDensity = { next ->
                onPrusaSettings(PrusaSliceSettings.Keys.FILL_DENSITY) { it.copy(fillDensityPercent = next) }
            },
            setPattern = { pattern ->
                onPrusaSettings(PrusaSliceSettings.Keys.FILL_PATTERN) { it.copy(fillPattern = pattern) }
            },
        ),
        adhesion = choiceValue(
            label = "Adhesion",
            options = ADHESION_LABELS,
            selected = adhesionSelection(state.prusaSettings.brimWidthMm, state.prusaSettings.skirtLoops),
            detail = summary.adhesion,
        ) { index ->
            onPrusaSettings(PrusaSliceSettings.Keys.BRIM_WIDTH) {
                it.copy(brimWidthMm = adhesionBrimWidth(index, it.brimWidthMm))
            }
            onPrusaSettings(PrusaSliceSettings.Keys.SKIRT_LOOPS) {
                it.copy(skirtLoops = adhesionSkirtLoops(index))
            }
        },
        supports = supportsValue(summary) { on ->
            onPrusaSettings(PrusaSliceSettings.Keys.SUPPORT_MATERIAL) { it.copy(supportMaterial = on) }
        },
    )

    SlicerEngine.ORCA -> SessionValues(
        layerHeight = layerHeightValue(summary) { next ->
            onOrcaSettings(OrcaSliceSettings.Keys.LAYER_HEIGHT) { it.copy(layerHeightMm = next) }
        },
        infill = infillValue(
            summary = summary,
            patterns = OrcaSliceSettings.INFILL_PATTERNS,
            currentPattern = state.orcaSettings.sparseInfillPattern,
            setDensity = { next ->
                onOrcaSettings(OrcaSliceSettings.Keys.SPARSE_INFILL_DENSITY) {
                    it.copy(sparseInfillDensityPercent = next)
                }
            },
            setPattern = { pattern ->
                onOrcaSettings(OrcaSliceSettings.Keys.SPARSE_INFILL_PATTERN) {
                    it.copy(sparseInfillPattern = pattern)
                }
            },
        ),
        adhesion = choiceValue(
            label = "Adhesion",
            options = ADHESION_LABELS,
            selected = adhesionSelection(state.orcaSettings.brimWidthMm, state.orcaSettings.skirtLoops),
            detail = summary.adhesion,
        ) { index ->
            onOrcaSettings(OrcaSliceSettings.Keys.BRIM_WIDTH) {
                it.copy(brimWidthMm = adhesionBrimWidth(index, it.brimWidthMm))
            }
            onOrcaSettings(OrcaSliceSettings.Keys.SKIRT_LOOPS) {
                it.copy(skirtLoops = adhesionSkirtLoops(index))
            }
        },
        supports = supportsValue(summary) { on ->
            onOrcaSettings(OrcaSliceSettings.Keys.SUPPORT_ENABLED) { it.copy(supportEnabled = on) }
        },
    )
}

private fun layerHeightValue(summary: PlateSessionSummary, set: (Double) -> Unit): SessionValue =
    SessionValue(
        label = "Layer height",
        value = "%.2f".format(summary.layerHeightMm),
        detail = "mm",
        editors = listOf(
            SessionValueEditor(
                section = "",
                editor = SessionEditor.Number(
                    value = summary.layerHeightMm,
                    // A height outside the usual band keeps its own value: opening
                    // the editor must not drag the setting back into range.
                    range = minOf(0.05f, summary.layerHeightMm.toFloat())..
                        maxOf(0.6f, summary.layerHeightMm.toFloat()),
                    step = 0.01,
                    decimals = 2,
                    unit = "mm",
                    set = set,
                ),
            ),
        ),
    )

/** Density and pattern are one line of the card and two settings behind it. */
private fun infillValue(
    summary: PlateSessionSummary,
    patterns: List<String>,
    currentPattern: String,
    setDensity: (Double) -> Unit,
    setPattern: (String) -> Unit,
): SessionValue = SessionValue(
    label = "Infill density",
    value = "%.0f%%".format(summary.infillPercent),
    detail = summary.infillPattern,
    editors = listOf(
        SessionValueEditor(
            section = "Density",
            editor = SessionEditor.Number(
                value = summary.infillPercent,
                range = 0f..100f,
                step = 1.0,
                decimals = 0,
                unit = "%",
                set = setDensity,
            ),
        ),
        SessionValueEditor(
            section = "Pattern",
            editor = SessionEditor.Choice(
                options = patterns.map { infillPatternLabel(it) },
                selectedIndex = patterns.indexOf(currentPattern),
                set = { index -> setPattern(patterns[index]) },
            ),
        ),
    ),
)

private fun choiceValue(
    label: String,
    options: List<String>,
    selected: Int,
    detail: String,
    set: (Int) -> Unit,
): SessionValue = SessionValue(
    label = label,
    value = options.getOrElse(selected) { options.first() },
    detail = detail,
    editors = listOf(
        SessionValueEditor(
            section = "",
            editor = SessionEditor.Choice(options = options, selectedIndex = selected, set = set),
        ),
    ),
)

private fun supportsValue(summary: PlateSessionSummary, set: (Boolean) -> Unit): SessionValue =
    SessionValue(
        label = "Supports",
        value = if (summary.supportsEnabled) "On" else "Off",
        // Nothing to add when they are off: "Off none" says it twice.
        detail = if (summary.supportsEnabled) summary.supportsDetail else "",
        editors = listOf(
            SessionValueEditor(
                section = "",
                editor = SessionEditor.Choice(
                    options = listOf("Off", "On"),
                    selectedIndex = if (summary.supportsEnabled) 1 else 0,
                    set = { index -> set(index == 1) },
                ),
            ),
        ),
    )

/** Cura's own infill patterns, in the order the Settings tab offers them. */
private val CURA_INFILL_PATTERNS = listOf(
    "grid" to "Grid",
    "lines" to "Lines",
    "triangles" to "Triangles",
    "trihexagon" to "Tri-hexagon",
    "cubic" to "Cubic",
    "cubicsubdiv" to "Cubic subdivision",
    // Cura's value for the pattern shown as "Octet" is tetrahedral; "octet" is
    // not a value the engine knows.
    "tetrahedral" to "Octet",
    "quarter_cubic" to "Quarter cubic",
    "concentric" to "Concentric",
    "zigzag" to "Zig zag",
    "cross" to "Cross",
    "cross_3d" to "Cross 3D",
    "gyroid" to "Gyroid",
    "lightning" to "Lightning",
)

/** Cura's build-plate adhesion, in the order the Settings tab offers it. */
private val CURA_ADHESION = listOf(
    "none" to "None",
    "skirt" to "Skirt",
    "brim" to "Brim",
    "raft" to "Raft",
)

/**
 * The adhesion Prusa and Orca settings can express: a brim width and a skirt
 * loop count rather than Cura's named type.
 */
internal val ADHESION_LABELS = listOf("None", "Skirt", "Brim")

/** Which of [ADHESION_LABELS] those two numbers add up to. */
internal fun adhesionSelection(brimWidthMm: Double, skirtLoops: Int): Int = when {
    brimWidthMm > 0.0 -> 2
    skirtLoops > 0 -> 1
    else -> 0
}

/** A brim keeps the width it already had; a fresh one starts at 5 mm. */
internal fun adhesionBrimWidth(selection: Int, currentBrimWidthMm: Double): Double = when (selection) {
    2 -> if (currentBrimWidthMm > 0.0) currentBrimWidthMm else 5.0
    else -> 0.0
}

internal fun adhesionSkirtLoops(selection: Int): Int = if (selection == 1) 1 else 0

/**
 * The same value for the narrow rail: label, then value, stacked.
 *
 * There is no room for a row at this width - the rail is 80dp, the width
 * Material gives its own destinations - so the label sits above the value
 * instead of beside it.
 */
@Composable
internal fun SessionValueTile(
    value: SessionValue,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                horizontal = EnderSlicerDimens.Space8,
                vertical = EnderSlicerDimens.Space4,
            ),
    ) {
        Text(
            value.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value.value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (value.detail.isNotEmpty()) {
            Text(
                value.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The editors for one session value: a slider for a number, a list for a choice,
 * under their own heading when a value has more than one of them.
 *
 * A choice applies at once and keeps the dialog open, so the plate behind it
 * shows what the change did; a slider commits when the finger comes off.
 */
@Composable
internal fun SessionValueDialog(value: SessionValue, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(value.label) },
        text = {
            // Cura offers fourteen infill patterns: the list has to scroll, or
            // the last of them cannot be reached at all.
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .testTag(SESSION_EDITOR_TAG),
            ) {
                value.editors.forEach { entry ->
                    if (entry.section.isNotEmpty()) {
                        Text(
                            entry.section,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when (val editor = entry.editor) {
                        is SessionEditor.Number -> NumberEditor(editor)
                        is SessionEditor.Choice -> ChoiceEditor(editor)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun NumberEditor(editor: SessionEditor.Number) {
    // Keyed by the committed value, not by the editor: the editor is rebuilt on every
    // recomposition of the rail (its set lambda is a new instance each time), so
    // keying on it would reset an in-progress drag whenever anything else emitted.
    var current by remember(editor.value) { mutableStateOf(editor.value) }

    fun commit(next: Double) {
        val clamped = next.coerceIn(
            editor.range.start.toDouble(),
            editor.range.endInclusive.toDouble(),
        )
        current = clamped
        editor.set(clamped)
    }

    Column {
        Text(
            text = numberText(current, editor.decimals) + " " + editor.unit,
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { commit(current - editor.step) }) { Text("−") }
            Slider(
                value = current.toFloat(),
                onValueChange = { current = it.toDouble() },
                onValueChangeFinished = { editor.set(current) },
                valueRange = editor.range,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { commit(current + editor.step) }) { Text("+") }
        }
        Text(
            numberText(editor.range.start.toDouble(), editor.decimals) + " – " +
                numberText(editor.range.endInclusive.toDouble(), editor.decimals) + " " + editor.unit,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChoiceEditor(editor: SessionEditor.Choice) {
    Column {
        editor.options.forEachIndexed { index, label ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editor.set(index) }
                    .padding(vertical = EnderSlicerDimens.Space6),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (index == editor.selectedIndex) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Selected",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (index != editor.options.lastIndex) HorizontalDivider()
        }
    }
}

private fun numberText(value: Double, decimals: Int): String =
    if (decimals == 0) value.roundToInt().toString() else "%.${decimals}f".format(value)

/** Test tag for the editor's body, so a JVM test can find it. */
internal const val SESSION_EDITOR_TAG = "session-value-editor"
