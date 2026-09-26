package com.tomppi.enderslicer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.profile.CuraComputedSettings
import com.tomppi.enderslicer.profile.CuraComputedSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Model tools groups. The resting bar shows one button per group, and a
 * group's options open one at a time in a compact floating panel above it.
 */
private enum class ModelToolsGroup(val label: String) {
    TRANSFORM("Transform"),
    ACTIONS("Actions"),
    SUPPORT_PAINT("Support paint"),
}

/** The Transform panel's collapsible sub-sections: one is open at a time. */
private enum class TransformSection { POSITION, ROTATE, SCALE, VALUES, AUDIT }

/**
 * Model tools as a compact floating overlay over the Plate: a single row of
 * group buttons and, while a group is open, that group's panel directly
 * above it.
 *
 * Neither card is modal. Both are content-sized Cards inside the plate Box
 * with no scrim and no full-size touch surface, so they only consume input
 * inside their own bounds and one-finger orbit, two-finger pan and pinch
 * still reach the viewer underneath while a group is open. The panel is
 * capped at half the plate height with verticalScroll and at a phone-ish
 * width, and its resting state is a few collapsible rows rather than a wall
 * of controls.
 *
 * [openGroup] is only "which panel is showing", not a second collapse
 * state: hiding the whole overlay still goes through [onClose], the plate's
 * modelToolsOpen flag and the existing BackHandler. Tapping the group that
 * is already open closes its panel, and only one panel can exist at a time.
 */
@Composable
fun ModelToolsOverlay(
    state: MainUiState,
    expandedLayout: Boolean,
    plateHeight: Dp,
    onMove: (Double, Double, Double) -> Unit,
    onRotate: (ModelPlacement.Axis, Double) -> Unit,
    onScale: (Double) -> Unit,
    onDropToBed: () -> Unit,
    onLayFlat: () -> Unit,
    onReset: () -> Unit,
    onApplyImportedTransform: () -> Unit,
    onOpenSupportPaintUi: () -> Unit,
    onBrushRadius: (Double) -> Unit,
    onClearPaint: () -> Unit,
    dragMove: Boolean,
    onToggleDragMove: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var openGroup by rememberSaveable { mutableStateOf<ModelToolsGroup?>(null) }
    Column(
        modifier = modifier,
        horizontalAlignment = if (expandedLayout) Alignment.CenterHorizontally else Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        openGroup?.let { group ->
            ModelToolsGroupPanel(
                group = group,
                state = state,
                expandedLayout = expandedLayout,
                plateHeight = plateHeight,
                onMove = onMove,
                onRotate = onRotate,
                onScale = onScale,
                onDropToBed = onDropToBed,
                onLayFlat = onLayFlat,
                onReset = onReset,
                onApplyImportedTransform = onApplyImportedTransform,
                onOpenSupportPaintUi = onOpenSupportPaintUi,
                onBrushRadius = onBrushRadius,
                onClearPaint = onClearPaint,
                dragMove = dragMove,
                onToggleDragMove = onToggleDragMove,
            )
        }
        ModelToolsBar(
            expandedLayout = expandedLayout,
            activeGroup = openGroup,
            onGroup = { group -> openGroup = if (openGroup == group) null else group },
            onClose = onClose,
        )
    }
}

/**
 * The resting state: one slim row of small buttons, one per group, plus a
 * compact close affordance that hides the whole overlay. The open group is
 * highlighted so the panel above is attributable to it.
 *
 * Material3 buttons are 40dp of content inside a 48dp touch target, so the
 * card wraps to a single 48dp row. The expanded layout gets a little more
 * horizontal padding around the labels; the content itself is shared.
 */
@Composable
private fun ModelToolsBar(
    expandedLayout: Boolean,
    activeGroup: ModelToolsGroup?,
    onGroup: (ModelToolsGroup) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentPadding = PaddingValues(horizontal = if (expandedLayout) 16.dp else 8.dp)
    Card(modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ModelToolsGroup.entries.forEach { group ->
                OutlinedButton(
                    onClick = { onGroup(group) },
                    contentPadding = contentPadding,
                    colors = if (group == activeGroup) {
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                ) {
                    Text(group.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Hide model tools")
            }
        }
    }
}

/**
 * One group's options in a compact floating panel: content-sized, capped at
 * half the plate height (scrolling past that) and at a phone-ish width so it
 * never grows into the full-screen sheet it replaced.
 */
@Composable
private fun ModelToolsGroupPanel(
    group: ModelToolsGroup,
    state: MainUiState,
    expandedLayout: Boolean,
    plateHeight: Dp,
    onMove: (Double, Double, Double) -> Unit,
    onRotate: (ModelPlacement.Axis, Double) -> Unit,
    onScale: (Double) -> Unit,
    onDropToBed: () -> Unit,
    onLayFlat: () -> Unit,
    onReset: () -> Unit,
    onApplyImportedTransform: () -> Unit,
    onOpenSupportPaintUi: () -> Unit,
    onBrushRadius: (Double) -> Unit,
    onClearPaint: () -> Unit,
    dragMove: Boolean,
    onToggleDragMove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.widthIn(min = 260.dp, max = if (expandedLayout) 420.dp else 340.dp),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = plateHeight * 0.5f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(group.label, style = MaterialTheme.typography.titleMedium)
            when (group) {
                ModelToolsGroup.TRANSFORM -> ModelTransformTools(
                    state = state,
                    onMove = onMove,
                    onRotate = onRotate,
                    onScale = onScale,
                    onApplyImportedTransform = onApplyImportedTransform,
                    dragMove = dragMove,
                    onToggleDragMove = onToggleDragMove,
                )

                ModelToolsGroup.ACTIONS -> ModelActionTools(
                    onLayFlat = onLayFlat,
                    onDropToBed = onDropToBed,
                    onReset = onReset,
                )

                ModelToolsGroup.SUPPORT_PAINT -> ModelSupportPaintTools(
                    state = state,
                    onOpenSupportPaintUi = onOpenSupportPaintUi,
                    onBrushRadius = onBrushRadius,
                    onClearPaint = onClearPaint,
                )
            }
        }
    }
}

/**
 * Transform group: position, rotation and scale as collapsible sub-sections
 * with a one-line summary each, plus the imported Cura scene transform and
 * the read-only values derived from the engine profile that used to sit at
 * the bottom of the old menu.
 */
@Composable
private fun ModelTransformTools(
    state: MainUiState,
    onMove: (Double, Double, Double) -> Unit,
    onRotate: (ModelPlacement.Axis, Double) -> Unit,
    onScale: (Double) -> Unit,
    onApplyImportedTransform: () -> Unit,
    dragMove: Boolean,
    onToggleDragMove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val placement = state.modelPlacement
    var xText by rememberSaveable(placement) { mutableStateOf(placement?.centerXmm?.formatPosition().orEmpty()) }
    var yText by rememberSaveable(placement) { mutableStateOf(placement?.centerYmm?.formatPosition().orEmpty()) }
    var zText by rememberSaveable(placement) { mutableStateOf(placement?.baseZmm?.formatPosition().orEmpty()) }
    var scaleText by rememberSaveable(placement) { mutableStateOf("100") }
    // The placement stores a matrix, not Euler angles, so the Rotate summary
    // tallies the rotations applied from this panel instead of pretending to
    // decompose it.
    var rotatedX by rememberSaveable { mutableStateOf(0.0) }
    var rotatedY by rememberSaveable { mutableStateOf(0.0) }
    var rotatedZ by rememberSaveable { mutableStateOf(0.0) }
    var openSection by rememberSaveable { mutableStateOf<TransformSection?>(null) }
    val computedSnapshot by produceState<CuraComputedSnapshot?>(
        initialValue = null,
        state.engineProfile,
        state.settings,
        state.startGcode,
        state.endGcode,
    ) {
        val profile = state.engineProfile
        value = if (profile == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                runCatching {
                    CuraComputedSettings.resolve(
                        profile = profile,
                        printer = state.printer,
                        settings = state.settings,
                        startGcode = state.startGcode,
                        endGcode = state.endGcode,
                    )
                }.getOrNull()
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (placement == null || state.mesh == null) {
            Text("Import an STL before changing model placement.")
            return@Column
        }

        // Dragging is the one placement gesture that wants the plate itself, so
        // it is a mode rather than a field: while it is on, a single finger moves
        // the model and two fingers still work the camera.
        if (dragMove) {
            Button(onClick = onToggleDragMove, modifier = Modifier.fillMaxWidth()) {
                Text("Moving with finger - tap to stop")
            }
            Text(
                "One finger drags the model across the plate. Two fingers still orbit, pan and zoom, " +
                    "and the move is applied when you let go.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedButton(onClick = onToggleDragMove, modifier = Modifier.fillMaxWidth()) {
                Text("Move with finger")
            }
        }

        fun toggle(section: TransformSection) {
            openSection = if (openSection == section) null else section
        }

        val rotate: (ModelPlacement.Axis, Double) -> Unit = { axis, amount ->
            when (axis) {
                ModelPlacement.Axis.X -> rotatedX += amount
                ModelPlacement.Axis.Y -> rotatedY += amount
                ModelPlacement.Axis.Z -> rotatedZ += amount
            }
            onRotate(axis, amount)
        }

        TransformSectionHeader(
            text = "Position: ${xText.ifBlank { "-" }}, ${yText.ifBlank { "-" }}, ${zText.ifBlank { "-" }}",
            expanded = openSection == TransformSection.POSITION,
            onToggle = { toggle(TransformSection.POSITION) },
        ) {
            Text(placement.source, style = MaterialTheme.typography.bodySmall)
            Text(
                "X and Y are the model bounds center. Z is the lowest point of the transformed model.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PositionField("Center X (mm)", xText, { xText = it }, Modifier.weight(1f))
                PositionField("Center Y (mm)", yText, { yText = it }, Modifier.weight(1f))
                PositionField("Base Z (mm)", zText, { zText = it }, Modifier.weight(1f))
            }
            CompactButtonSizing {
                Button(
                    onClick = {
                        // An empty field keeps the model where it already is on that axis
                        // rather than silently refusing the whole move.
                        val x = parsePosition(xText) ?: placement.centerXmm
                        val y = parsePosition(yText) ?: placement.centerYmm
                        val z = parsePosition(zText) ?: placement.baseZmm
                        onMove(x, y, z)
                    },
                    contentPadding = CompactButtonPadding,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CompactButtonHeight),
                ) { Text("Apply position") }
            }
        }

        TransformSectionHeader(
            text = "Rotate: ${rotatedX.formatPosition()}, ${rotatedY.formatPosition()}, ${rotatedZ.formatPosition()}",
            expanded = openSection == TransformSection.ROTATE,
            onToggle = { toggle(TransformSection.ROTATE) },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                ModelPlacement.Axis.entries.forEach { axis ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            axis.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            modifier = Modifier.width(16.dp),
                        )
                        RotateButton(axis, -90.0, rotate, Modifier.weight(1f))
                        RotateButton(axis, -5.0, rotate, Modifier.weight(1f))
                        RotateButton(axis, 5.0, rotate, Modifier.weight(1f))
                        RotateButton(axis, 90.0, rotate, Modifier.weight(1f))
                        RotateButton(axis, -1.0, rotate, Modifier.weight(1f), fine = true)
                        RotateButton(axis, 1.0, rotate, Modifier.weight(1f), fine = true)
                    }
                }
            }
        }

        TransformSectionHeader(
            text = "Scale: ${scaleText.ifBlank { "-" }}%",
            expanded = openSection == TransformSection.SCALE,
            onToggle = { toggle(TransformSection.SCALE) },
        ) {
            Text(
                "Multiplies the current size around its position. 100% keeps the model unchanged; 200% doubles it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf("50", "75", "125", "150").chunked(2).forEach { presets ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    presets.forEach { preset ->
                        val presetModifier = Modifier.weight(1f)
                        CompactButtonSizing {
                            OutlinedButton(
                                onClick = { scaleText = preset },
                                contentPadding = CompactButtonPadding,
                                modifier = presetModifier.height(CompactButtonHeight),
                            ) { Text("$preset%") }
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                // The field keeps its 56dp height while the compact button does not.
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                PositionField("Scale (%)", scaleText, { scaleText = it }, Modifier.weight(1f))
                val applyModifier = Modifier.weight(1f)
                CompactButtonSizing {
                    Button(
                        onClick = {
                            val percent = parsePosition(scaleText)
                            if (percent == null || !percent.isFinite() || percent < 1.0 || percent > 1000.0) {
                                return@Button
                            }
                            onScale(percent)
                        },
                        contentPadding = CompactButtonPadding,
                        modifier = applyModifier.height(CompactButtonHeight),
                    ) { Text("Apply scale") }
                }
            }
        }

        if (state.importedSceneTransformAvailable) {
            HorizontalDivider()
            Text(
                "Imported Cura scene transform${state.importedSceneModelName?.let { " for $it" }.orEmpty()}",
                style = MaterialTheme.typography.titleSmall,
            )
            OutlinedButton(onClick = onApplyImportedTransform, modifier = Modifier.fillMaxWidth()) {
                Text("Apply imported Cura transform")
            }
        }

        computedSnapshot?.let { snapshot ->
            if (snapshot.values.isNotEmpty()) {
                TransformSectionHeader(
                    text = "Computed Cura values: ${snapshot.values.size}",
                    expanded = openSection == TransformSection.VALUES,
                    onToggle = { toggle(TransformSection.VALUES) },
                ) {
                    Text(
                        "${snapshot.expressionCount} formulas resolved in ${snapshot.passes} passes. These values are read-only and recalculate when their source settings change.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    snapshot.values.forEach { computed ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${computed.label}: ${computed.value}", style = MaterialTheme.typography.bodyMedium)
                            Text(computed.key, style = MaterialTheme.typography.labelSmall)
                            Text(computed.source, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        if (state.warnings.isNotEmpty()) {
            TransformSectionHeader(
                text = "Cura compatibility audit: ${state.warnings.size} warnings",
                expanded = openSection == TransformSection.AUDIT,
                onToggle = { toggle(TransformSection.AUDIT) },
            ) {
                state.warnings.forEach { warning ->
                    Text("• $warning", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * One collapsible Transform sub-section: a full-width summarised row that
 * toggles the controls underneath. Collapsed is the default, so the panel
 * rests at a few rows instead of the whole transform form.
 */
@Composable
private fun TransformSectionHeader(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/**
 * One rotate step for one axis. The six steps of an axis share a single row,
 * so the label is pinned to one line and clipped rather than wrapped.
 *
 * [fine] marks the two 1° steps: the same compact size and the same action,
 * but a borderless, muted button, so at the end of the row they do not read as
 * two more coarse steps. Material3's outlined border is already the faint
 * outlineVariant token, so dropping the outline is what actually separates the
 * two groups.
 */
@Composable
private fun RotateButton(
    axis: ModelPlacement.Axis,
    amount: Double,
    onRotate: (ModelPlacement.Axis, Double) -> Unit,
    modifier: Modifier = Modifier,
    fine: Boolean = false,
) {
    val label: @Composable () -> Unit = {
        Text(
            "${if (amount > 0) "+" else ""}${amount.toInt()}°",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
        )
    }
    CompactButtonSizing {
        if (fine) {
            TextButton(
                onClick = { onRotate(axis, amount) },
                contentPadding = CompactButtonPadding,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = modifier.height(CompactButtonHeight),
                content = { label() },
            )
        } else {
            OutlinedButton(
                onClick = { onRotate(axis, amount) },
                contentPadding = CompactButtonPadding,
                modifier = modifier.height(CompactButtonHeight),
                content = { label() },
            )
        }
    }
}

/** Actions group: the whole-model operations that used to sit mid-menu. */
@Composable
private fun ModelActionTools(
    onDropToBed: () -> Unit,
    onLayFlat: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onLayFlat, modifier = Modifier.fillMaxWidth()) {
            Text("Lay flat on largest face")
        }
        OutlinedButton(onClick = onDropToBed, modifier = Modifier.fillMaxWidth()) {
            Text("Drop to build plate")
        }
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Reset and center model")
        }
    }
}

/** Support paint group: brush radius, entering paint mode and clearing paint. */
@Composable
private fun ModelSupportPaintTools(
    state: MainUiState,
    onOpenSupportPaintUi: () -> Unit,
    onBrushRadius: (Double) -> Unit,
    onClearPaint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var brushText by rememberSaveable(state.supportPaint.brushRadiusMm) {
        mutableStateOf(state.supportPaint.brushRadiusMm.formatPosition())
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Set the brush radius and tap Apply brush to open the paint controls. Tap Draw or Block, then drag on the model to paint supports (green) or blockers (red); tap Erase to remove paint. Use two fingers to rotate and zoom the camera.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PositionField("Brush radius (mm)", brushText, { brushText = it }, Modifier.weight(1f))
            Button(
                onClick = {
                    parsePosition(brushText)?.let(onBrushRadius)
                    onOpenSupportPaintUi()
                },
                modifier = Modifier.weight(1f),
            ) { Text("Apply brush") }
        }
        if (!state.supportPaint.isEmpty) {
            Text(
                "${state.supportPaint.enforcerTriangles.size} support + ${state.supportPaint.blockerTriangles.size} block triangles painted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onClearPaint, modifier = Modifier.fillMaxWidth()) {
            Text("Clear painted supports")
        }
    }
}

/**
 * The Transform panel's compact button metrics.
 *
 * Material3 gives a button 40dp of content height and then enforces a 48dp
 * minimum interactive size, so an unmodified button takes a whole 48dp row -
 * more than the panel can spend on a row that has to hold six of them next to
 * an axis label.
 */
private val CompactButtonHeight = 32.dp

/** Compact horizontal button padding for rows that share the width. */
private val CompactButtonPadding = PaddingValues(horizontal = 3.dp)

/**
 * Runs [content] with Material3's minimum interactive size turned off, so a
 * button measures exactly the height it is given instead of being centred in a
 * 48dp cell. Only the compact buttons are wrapped: at 32dp they give up the
 * extra touch target Material reserves around a smaller visual.
 */
@Composable
private fun CompactButtonSizing(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp, content = content)
}

@Composable
private fun PositionField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            // A comma is accepted as the decimal mark: these fields are typed on
            // keyboards in locales that use one, and the value is normalised when
            // it is parsed.
            if (candidate.length <= 16 && candidate.all { it.isDigit() || it in ".-+," }) {
                onValueChange(candidate)
            }
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

/**
 * Parses a position field, accepting a comma as the decimal mark.
 *
 * Returns null for an empty field, which the callers treat as "leave it alone"
 * rather than as zero: a field the user has cleared must not move the model.
 */
private fun parsePosition(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()

/**
 * Formats a position for the field.
 *
 * Locale.ROOT, never the device locale: this text is parsed again by
 * [parsePosition], and in a comma-decimal locale the prefill ("12,5") could not
 * be edited at all - every keystroke that left the comma in place was rejected,
 * and the parser would not read it either.
 */
private fun Double.formatPosition(): String =
    java.text.NumberFormat.getNumberInstance(java.util.Locale.ROOT).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 3
        isGroupingUsed = false
    }.format(this)
