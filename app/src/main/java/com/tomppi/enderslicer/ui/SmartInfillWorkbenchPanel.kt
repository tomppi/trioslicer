package com.tomppi.enderslicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.tomppi.enderslicer.smartinfill.FilaSimBoundaryCondition
import com.tomppi.enderslicer.smartinfill.FilaSimConfiguration
import com.tomppi.enderslicer.smartinfill.FilaSimGoal
import com.tomppi.enderslicer.smartinfill.FilaSimMaterialPresets
import com.tomppi.enderslicer.smartinfill.FilaSimOptimization
import com.tomppi.enderslicer.smartinfill.FilaSimOptimizeMode
import com.tomppi.enderslicer.smartinfill.FilaSimOptimizeOptions
import com.tomppi.enderslicer.smartinfill.SmartInfillCondition
import com.tomppi.enderslicer.smartinfill.SmartInfillPackage
import com.tomppi.enderslicer.smartinfill.SmartInfillSummary
import com.tomppi.enderslicer.smartinfill.SmartInfillUiState
import com.tomppi.enderslicer.viewer.DensityRamp

/**
 * The native Smart Infill workflow, floating over the Plate.
 *
 * Deliberately a panel rather than a modal sheet: assigning a boundary
 * condition means tapping the model, so the surface above the panel has to stay
 * live. It carries no scrim and consumes touches only inside its own bounds,
 * which is the same rule the Model tools bar follows.
 *
 * On a foldable or a tablet it is two cards instead of one, one against each
 * side of the screen and the model between them: what acts on the part on the
 * left, what is asked of the optimizer and what it answered on the right. Each
 * card scrolls on its own and both are capped, because the Plate's own session
 * cards share the right-hand edge.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun SmartInfillWorkbenchPanel(
    state: SmartInfillUiState,
    packageValue: SmartInfillPackage?,
    starting: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onAddCondition: (FilaSimBoundaryCondition) -> Unit,
    onArmPicking: (Long) -> Unit,
    onRemoveCondition: (Long) -> Unit,
    onExpandToSurface: (Long) -> Unit,
    onUpdateCondition: (Long, FilaSimBoundaryCondition) -> Unit,
    onSpotSize: (Double) -> Unit,
    onConfiguration: (FilaSimConfiguration) -> Unit,
    onOptions: (FilaSimOptimizeOptions) -> Unit,
    onCheck: () -> Unit,
    onSolve: () -> Unit,
    onOptimize: () -> Unit,
    onStop: () -> Unit,
    onApply: () -> Unit,
    onRemovePackage: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One call per card, so the two-pane and the single-pane layouts cannot
    // drift apart: each pane is this content filtered by its AnalysisPane.
    val analysis: @Composable (AnalysisPane) -> Unit = { pane ->
        AnalysisSection(
            state = state,
            enabled = enabled,
            onAddCondition = onAddCondition,
            onArmPicking = onArmPicking,
            onRemoveCondition = onRemoveCondition,
            onExpandToSurface = onExpandToSurface,
            onUpdateCondition = onUpdateCondition,
            onSpotSize = onSpotSize,
            onConfiguration = onConfiguration,
            onOptions = onOptions,
            onCheck = onCheck,
            onSolve = onSolve,
            onOptimize = onOptimize,
            onStop = onStop,
            onApply = onApply,
            pane = pane,
        )
    }

    // Material's own answer to "is this window wide enough for two panes": the
    // expanded width class, the one the adaptive scaffolds use. A medium window
    // (600..840dp) keeps the single card, because two panes in it read as packed.
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val directive = calculatePaneScaffoldDirective(adaptiveInfo)
    val split = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
    )
    // Where this panel sits in the window, so a fold's bounds - which the
    // directive reports in window pixels - can be turned into a band inside it.
    var panelLeftPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier.onGloballyPositioned { coordinates ->
            panelLeftPx = coordinates.positionInWindow().x
        },
    ) {
        // The split's two columns share one height. It is capped because the
        // Plate's session cards use the upper right and the model has to keep
        // the band above the sheet - that band is where surfaces are picked.
        val paneHeight = (maxHeight * SPLIT_HEIGHT_FRACTION).coerceAtMost(SPLIT_PANE_MAX_HEIGHT)
        val band = splitBand(
            panelLeftPx = panelLeftPx,
            panelWidthPx = with(density) { maxWidth.toPx() },
            fold = directive.excludedBounds.firstOrNull(),
            spacer = directive.horizontalPartitionSpacerSize,
            density = density,
        )
        // With no fold to sit on, the two columns share the width evenly.
        val leadingWidth = band.start ?: ((maxWidth - band.width) / 2)
        val summary = packageValue?.summary
        if (split) {
            SplitPanel(
                title = "Smart Infill · native engine",
                onClose = onClose,
                paneHeight = paneHeight,
                leadingWidth = leadingWidth,
                bandWidth = band.width,
                setup = {
                    if (!state.hasSession && !starting) {
                        EntrySection(enabled = enabled, onStart = onStart)
                    } else if (starting) {
                        PreparingSection()
                    } else {
                        analysis(AnalysisPane.SETUP)
                    }
                },
                run = {
                    PackageSection(
                        summary = summary,
                        enabled = enabled,
                        onRemovePackage = onRemovePackage,
                    )
                    if (state.hasSession && !starting) analysis(AnalysisPane.RUN)
                    MessageSection(state)
                    WorkspaceSection(enabled = enabled, onOpenWorkspace = onOpenWorkspace)
                },
            )
        } else {
            PanelCard(modifier = Modifier, maxHeight = PANE_MAX_HEIGHT) {
                PanelHeader("Smart Infill · native engine", onClose)
                PackageSection(
                    summary = summary,
                    enabled = enabled,
                    onRemovePackage = onRemovePackage,
                )
                if (!state.hasSession && !starting) {
                    EntrySection(enabled = enabled, onStart = onStart)
                } else if (starting) {
                    PreparingSection()
                } else {
                    analysis(AnalysisPane.BOTH)
                }
                MessageSection(state)
                WorkspaceSection(enabled = enabled, onOpenWorkspace = onOpenWorkspace)
            }
        }
    }
}

/** Which half of the analysis one card carries; [BOTH] is the single-pane card. */
private enum class AnalysisPane { SETUP, RUN, BOTH }

/**
 * The split's two columns, tagged so a test can measure them: one against each
 * side of the plate, both the same height, meeting at the divider.
 */
internal const val SETUP_PANE_TAG = "smart-infill-setup-pane"
internal const val RUN_PANE_TAG = "smart-infill-run-pane"

/**
 * The band the split keeps between its columns: how wide it is, and - when the
 * device reports a fold the layout has to avoid - where inside the panel it
 * starts. A null [start] means there is nothing to sit on and the columns share
 * the width evenly around a centred band.
 */
internal data class SplitBand(val width: Dp, val start: Dp?)

/**
 * Works out that band from the directive's excluded bounds, which arrive in
 * window pixels: the panel's own left edge is what turns them into an offset
 * inside the panel, so the two columns sit either side of the crease instead of
 * across it. A fold that is missing, outside the panel or wide enough to leave a
 * sliver of a column is ignored, and the directive's spacer is used instead.
 */
internal fun splitBand(
    panelLeftPx: Float,
    panelWidthPx: Float,
    fold: Rect?,
    spacer: Dp,
    density: Density,
): SplitBand {
    val widthPx = fold?.width ?: 0f
    val usable = fold != null &&
        panelLeftPx > 0f &&
        widthPx > 0f &&
        widthPx < panelWidthPx / 2f
    if (!usable) return SplitBand(spacer, null)
    val start = with(density) { (fold!!.left - panelLeftPx).toDp() }
    val width = with(density) { widthPx.toDp() }
    val panelWidth = with(density) { panelWidthPx.toDp() }
    if (start < 0.dp || start + width > panelWidth) return SplitBand(spacer, null)
    return SplitBand(width, start)
}

/**
 * The phone card's ceiling, the plate width that splits the workflow in two, and
 * the share of the plate's height - with a ceiling on it - that the split's
 * columns get. Both are chosen so the model keeps the band above the sheet, and
 * that band is where its surfaces are picked.
 */
private val PANE_MAX_HEIGHT = 440.dp
private val SPLIT_PANE_MAX_HEIGHT = 560.dp
private const val SPLIT_HEIGHT_FRACTION = 0.55f

/**
 * One floating card of the workflow: the panel's frame, its own scroll, and the
 * height ceiling that keeps it clear of the Plate's session cards.
 */
@Composable
private fun PanelCard(
    modifier: Modifier = Modifier,
    maxHeight: Dp = PANE_MAX_HEIGHT,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/**
 * The foldable's frame: one sheet across the plate, one header, and the workflow
 * cut down the middle into two columns of the same height that meet at a
 * divider. It is deliberately a split view rather than two floating cards - the
 * two halves are one workspace, and the divider is what says so.
 */
@Composable
private fun SplitPanel(
    title: String,
    onClose: () -> Unit,
    paneHeight: Dp,
    leadingWidth: Dp,
    bandWidth: Dp,
    setup: @Composable ColumnScope.() -> Unit,
    run: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                PanelHeader(title, onClose)
            }
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth().height(paneHeight)) {
                SplitPane(
                    title = "Setup",
                    modifier = Modifier.width(leadingWidth).testTag(SETUP_PANE_TAG),
                    content = setup,
                )
                // The band is the device's fold when there is one to sit on, and
                // the directive's own spacer otherwise - with the divider down
                // its middle either way, so the split always reads as two halves.
                Box(modifier = Modifier.width(bandWidth).fillMaxHeight()) {
                    VerticalDivider(modifier = Modifier.align(Alignment.Center))
                }
                SplitPane(
                    title = "Run and results",
                    modifier = Modifier.weight(1f).testTag(RUN_PANE_TAG),
                    content = run,
                )
            }
        }
    }
}

/** One column of the split: its own label, its own scroll, its own full height. */
@Composable
private fun SplitPane(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxHeight()) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun PanelHeader(title: String, onClose: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        onClose?.let { close -> TextButton(onClick = close) { Text("Close") } }
    }
}

/** The workflow's entry point, and the wait before the session exists. */
@Composable
private fun EntrySection(enabled: Boolean, onStart: () -> Unit) {
    Text(
        "Analyze this model's loads in the app: pick the surfaces the part rests on and " +
            "the ones the load acts on, then optimize the infill density for them.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = onStart, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("Open the analysis")
    }
}

@Composable
private fun PreparingSection() {
    Text("Preparing the model for the analysis…", style = MaterialTheme.typography.bodySmall)
}

/** What the slice would use right now, and the way to take it back off. */
@Composable
private fun PackageSection(
    summary: SmartInfillSummary?,
    enabled: Boolean,
    onRemovePackage: () -> Unit,
) {
    if (summary == null) return
    Text("Active in the slice", style = MaterialTheme.typography.titleSmall)
    Text(
        "${summary.sourceName} · ${summary.mode} ${summary.pattern} · base " +
            "${percent(summary.baseDensityPercent)} · modifiers " +
            summary.modifierDensitiesPercent.joinToString { item -> item.toString() + "%" },
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "${summary.perimeters} walls × ${fixed(summary.lineWidthMm)} mm · " +
            "${summary.topBottomLayers} top/bottom layers · " +
            "${fixed(summary.layerHeightMm)} mm layers",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = onRemovePackage, enabled = enabled) { Text("Remove modifiers") }
}

@Composable
private fun MessageSection(state: SmartInfillUiState) {
    state.error?.let { message ->
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    state.notice?.let { message ->
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WorkspaceSection(enabled: Boolean, onOpenWorkspace: () -> Unit) {
    TextButton(onClick = onOpenWorkspace, enabled = enabled) {
        Text("filaSim workspace · thermal, annealing, build simulation")
    }
}

/**
 * The analysis, in the order the workflow runs: what acts on the part, then
 * what is asked of the optimizer and what came back. A single-pane card takes
 * both halves ([AnalysisPane.BOTH]); the foldable's two cards take one half
 * each, so the phone and the fold cannot drift apart section by section.
 */
@Composable
private fun AnalysisSection(
    state: SmartInfillUiState,
    enabled: Boolean,
    onAddCondition: (FilaSimBoundaryCondition) -> Unit,
    onArmPicking: (Long) -> Unit,
    onRemoveCondition: (Long) -> Unit,
    onExpandToSurface: (Long) -> Unit,
    onUpdateCondition: (Long, FilaSimBoundaryCondition) -> Unit,
    onSpotSize: (Double) -> Unit,
    onConfiguration: (FilaSimConfiguration) -> Unit,
    onOptions: (FilaSimOptimizeOptions) -> Unit,
    onCheck: () -> Unit,
    onSolve: () -> Unit,
    onOptimize: () -> Unit,
    onStop: () -> Unit,
    onApply: () -> Unit,
    pane: AnalysisPane,
) {
    if (pane != AnalysisPane.RUN) {
        SetupSections(
            state = state,
            enabled = enabled,
            onAddCondition = onAddCondition,
            onArmPicking = onArmPicking,
            onRemoveCondition = onRemoveCondition,
            onExpandToSurface = onExpandToSurface,
            onUpdateCondition = onUpdateCondition,
            onSpotSize = onSpotSize,
            onConfiguration = onConfiguration,
        )
    }
    if (pane != AnalysisPane.SETUP) {
        RunSections(
            state = state,
            enabled = enabled,
            onConfiguration = onConfiguration,
            onOptions = onOptions,
            onCheck = onCheck,
            onSolve = onSolve,
            onOptimize = onOptimize,
            onStop = onStop,
            onApply = onApply,
        )
    }
}

/** The model, the surfaces it rests on and carries load on, and its material. */
@Composable
private fun SetupSections(
    state: SmartInfillUiState,
    enabled: Boolean,
    onAddCondition: (FilaSimBoundaryCondition) -> Unit,
    onArmPicking: (Long) -> Unit,
    onRemoveCondition: (Long) -> Unit,
    onExpandToSurface: (Long) -> Unit,
    onUpdateCondition: (Long, FilaSimBoundaryCondition) -> Unit,
    onSpotSize: (Double) -> Unit,
    onConfiguration: (FilaSimConfiguration) -> Unit,
) {
    val configuration = state.configuration
    val info = state.info
    // A running solve or optimize owns the native session; the panel's edits
    // and picks would race it (the controller refuses them too).
    val actionsEnabled = enabled && !state.isBusy

    Text(
        state.modelName,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (info != null) {
        Text(
            "${info.originalTriangles} triangles · ${info.patches} surfaces · grid " +
                "${info.nx}×${info.ny}×${info.nz} at ${fixed(info.cellSizeMm)} mm" +
                if (info.bodies > 1) " · ${info.bodies} separate bodies" else "",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Text("Boundary conditions", style = MaterialTheme.typography.titleSmall)
    if (state.conditions.isEmpty()) {
        Text(
            "Add the supports the part rests on, then the load it must carry.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            "On the model: green rests on the bed, orange carries the load, yellow is the " +
                "surface being picked.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    state.conditions.forEach { entry ->
        key(entry.id) {
            ConditionRow(
                entry = entry,
                enabled = actionsEnabled,
                onArmPicking = onArmPicking,
                onRemove = onRemoveCondition,
                onExpand = onExpandToSurface,
                onUpdate = onUpdateCondition,
            )
        }
    }
    state.pickingCondition?.let { entry ->
        Text(
            "Tap the surface that " + entry.label.lowercase() + " acts on — dragging still orbits " +
                "the model.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onAddCondition(FilaSimBoundaryCondition.Fixed(IntArray(0))) },
            enabled = actionsEnabled,
        ) { Text("Fixed") }
        OutlinedButton(
            onClick = {
                onAddCondition(FilaSimBoundaryCondition.Elastic(IntArray(0), stiffnessNPerMm3 = 50.0))
            },
            enabled = actionsEnabled,
        ) { Text("Elastic") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                onAddCondition(FilaSimBoundaryCondition.Force(IntArray(0), listOf(0.0, 0.0, -100.0)))
            },
            enabled = actionsEnabled,
        ) { Text("Force 100 N ↓") }
        OutlinedButton(
            onClick = { onAddCondition(FilaSimBoundaryCondition.Pressure(IntArray(0), 1.0)) },
            enabled = actionsEnabled,
        ) { Text("Pressure 1 MPa") }
    }
    // The rest of the boundary-condition kinds the engine takes, scrolled so the
    // row stays one line on a phone.
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { onAddCondition(FilaSimBoundaryCondition.Frictionless(IntArray(0))) },
            enabled = actionsEnabled,
        ) { Text("Frictionless") }
        OutlinedButton(
            onClick = {
                onAddCondition(
                    FilaSimBoundaryCondition.Moment(IntArray(0), listOf(0.0, 0.0, -100.0)),
                )
            },
            enabled = actionsEnabled,
        ) { Text("Moment 100 N·mm") }
        OutlinedButton(
            onClick = {
                onAddCondition(
                    FilaSimBoundaryCondition.Bearing(IntArray(0), listOf(100.0, 0.0, 0.0)),
                )
            },
            enabled = actionsEnabled,
        ) { Text("Bearing 100 N") }
        OutlinedButton(
            onClick = {
                onAddCondition(
                    FilaSimBoundaryCondition.Displacement(
                        triangles = IntArray(0),
                        axes = listOf(false, false, true),
                        vector = listOf(0.0, 0.0, -0.1),
                    ),
                )
            },
            enabled = actionsEnabled,
        ) { Text("Press 0.1 mm") }
        OutlinedButton(
            onClick = {
                onAddCondition(
                    FilaSimBoundaryCondition.Cylindrical(IntArray(0), listOf(true, true, true)),
                )
            },
            enabled = actionsEnabled,
        ) { Text("Cylindrical") }
        OutlinedButton(
            onClick = {
                onAddCondition(
                    FilaSimBoundaryCondition.Mass(
                        triangles = IntArray(0),
                        point = listOf(0.0, 0.0, 0.0),
                        massTonnes = 100e-6,
                    ),
                )
            },
            enabled = actionsEnabled,
        ) { Text("Mass 100 g") }
    }

    Text("Selection", style = MaterialTheme.typography.titleSmall)
    NumberRow(
        label = "Tap radius mm",
        value = state.spotSizeMm,
        format = { fixed(it) },
        onValue = onSpotSize,
    )
    Text(
        "A tap takes the surface within this radius of your finger — pick twice to add a " +
            "second pad. \"Face\" widens a condition to the whole flat surface it sits on.",
        style = MaterialTheme.typography.bodySmall,
    )

    Text("Material", style = MaterialTheme.typography.titleSmall)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilaSimMaterialPresets.ALL.forEach { preset ->
            ModeButton(
                label = preset.name,
                selected = preset.matches(configuration),
                enabled = actionsEnabled,
            ) {
                onConfiguration(preset.applyTo(configuration))
            }
        }
    }
    NumberRow(
        enabled = actionsEnabled,
        label = "Young's modulus MPa",
        value = configuration.youngsModulusMpa ?: 0.0,
        format = { fixed(it, 0) },
        onValue = { onConfiguration(configuration.copy(youngsModulusMpa = it)) },
    )
    NumberRow(
        enabled = actionsEnabled,
        label = "Poisson ratio",
        value = configuration.poisson ?: 0.0,
        format = { fixed(it) },
        onValue = { onConfiguration(configuration.copy(poisson = it.coerceIn(0.0, 0.48))) },
    )
    NumberRow(
        enabled = actionsEnabled,
        label = "Density g/cm³",
        value = configuration.densityGramsPerCm3 ?: 0.0,
        format = { fixed(it) },
        onValue = { onConfiguration(configuration.copy(densityGramsPerCm3 = it)) },
    )
    NumberRow(
        enabled = actionsEnabled,
        label = "Tensile strength MPa",
        value = configuration.strengthMpa ?: 0.0,
        format = { fixed(it, 1) },
        onValue = { onConfiguration(configuration.copy(strengthMpa = it)) },
    )
    NumberRow(
        enabled = actionsEnabled,
        label = "Layer strength MPa",
        value = configuration.layerStrengthMpa ?: 0.0,
        format = { fixed(it, 1) },
        onValue = { onConfiguration(configuration.copy(layerStrengthMpa = it)) },
    )
    NumberRow(
        enabled = actionsEnabled,
        label = "Shear strength MPa",
        value = configuration.shearStrengthMpa ?: 0.0,
        format = { fixed(it, 1) },
        onValue = { onConfiguration(configuration.copy(shearStrengthMpa = it)) },
    )
    ToggleRow(
        label = "Score layer shear",
        enabled = actionsEnabled,
        checked = configuration.layerShearOn ?: true,
        onChecked = { onConfiguration(configuration.copy(layerShearOn = it)) },
    )
    ToggleRow(
        label = "Self-weight (gravity)",
        enabled = actionsEnabled,
        checked = configuration.accelerationMmPerS2?.any { component -> component != 0.0 } == true,
        onChecked = { on ->
            onConfiguration(
                configuration.copy(
                    accelerationMmPerS2 = if (on) {
                        listOf(0.0, 0.0, -ONE_G_MM_PER_S2)
                    } else {
                        listOf(0.0, 0.0, 0.0)
                    },
                ),
            )
        },
    )
}

/** What the optimizer is asked for, the run itself, and what came back. */
@Composable
private fun RunSections(
    state: SmartInfillUiState,
    enabled: Boolean,
    onConfiguration: (FilaSimConfiguration) -> Unit,
    onOptions: (FilaSimOptimizeOptions) -> Unit,
    onCheck: () -> Unit,
    onSolve: () -> Unit,
    onOptimize: () -> Unit,
    onStop: () -> Unit,
    onApply: () -> Unit,
) {
    val options = state.options
    val configuration = state.configuration
    // A running solve or optimize owns the native session and the inputs it
    // started with; its rows would edit values that run is already using (the
    // controller refuses those edits too).
    val rowsEnabled = enabled && !state.isBusy

    Text("Goal", style = MaterialTheme.typography.titleSmall)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeButton("Stiffest", options.goal == FilaSimGoal.BUDGET, enabled = rowsEnabled) {
            onOptions(options.copy(goal = FilaSimGoal.BUDGET))
        }
        ModeButton("Match uniform", options.goal == FilaSimGoal.MATCH, enabled = rowsEnabled) {
            onOptions(options.copy(goal = FilaSimGoal.MATCH))
        }
        ModeButton("Safety factor", options.goal == FilaSimGoal.STRENGTH, enabled = rowsEnabled) {
            onOptions(options.copy(goal = FilaSimGoal.STRENGTH))
        }
    }
    Text(
        when (options.goal) {
            FilaSimGoal.BUDGET -> "Stiffest design the infill budget allows."
            FilaSimGoal.MATCH -> "Lightest design as stiff as a uniform print at the same mean infill."
            FilaSimGoal.STRENGTH -> "Lightest design that reaches the safety factor on the whole part."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (options.goal == FilaSimGoal.STRENGTH) {
        NumberRow(
            enabled = rowsEnabled,
            label = "Safety factor target",
            value = options.safetyFactorTarget,
            format = { fixed(it, 1) },
            onValue = { onOptions(options.copy(safetyFactorTarget = it.coerceIn(1.0, 20.0))) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton("Both", options.safetyFactorMeasure == "both", enabled = rowsEnabled) {
                onOptions(options.copy(safetyFactorMeasure = "both"))
            }
            ModeButton("Material", options.safetyFactorMeasure == "material", enabled = rowsEnabled) {
                onOptions(options.copy(safetyFactorMeasure = "material"))
            }
            ModeButton("Layer", options.safetyFactorMeasure == "layer", enabled = rowsEnabled) {
                onOptions(options.copy(safetyFactorMeasure = "layer"))
            }
        }
        Text(
            "Both scores the in-layer and the layer-adhesion criterion; a metal or a resin has no " +
                "layer direction, so score those against Material.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    ToggleRow(
        enabled = rowsEnabled,
        label = "Self-supporting infill",
        checked = options.selfSupporting,
        onChecked = { onOptions(options.copy(selfSupporting = it)) },
    )
    if (options.selfSupporting) {
        NumberRow(
            enabled = rowsEnabled,
            label = "Overhang limit °",
            value = options.overhangDegrees,
            format = { fixed(it, 0) },
            onValue = { onOptions(options.copy(overhangDegrees = it.coerceIn(20.0, 80.0))) },
        )
    }

    Text("Print assumptions and budget", style = MaterialTheme.typography.titleSmall)
    NumberRow(
        enabled = rowsEnabled,
        label = "Infill budget %",
        value = options.budgetPercent,
        format = { fixed(it) },
        onValue = { onOptions(options.copy(budgetPercent = it.coerceIn(5.0, 100.0))) },
    )
    NumberRow(
        enabled = rowsEnabled,
        label = "Perimeters",
        value = options.perimeters.toDouble(),
        format = { whole(it) },
        onValue = { onOptions(options.copy(perimeters = it.toInt().coerceIn(1, 8))) },
    )
    NumberRow(
        enabled = rowsEnabled,
        label = "Line width mm",
        value = options.lineWidthMm,
        format = { fixed(it) },
        onValue = { onOptions(options.copy(lineWidthMm = it.coerceIn(0.1, 1.5))) },
    )
    NumberRow(
        enabled = rowsEnabled,
        label = "Layer height mm",
        value = options.layerHeightMm,
        format = { fixed(it) },
        onValue = { onOptions(options.copy(layerHeightMm = it.coerceIn(0.04, 0.6))) },
    )
    NumberRow(
        enabled = rowsEnabled,
        label = "Top/bottom layers",
        value = options.topBottomLayers.toDouble(),
        format = { whole(it) },
        onValue = { onOptions(options.copy(topBottomLayers = it.toInt().coerceIn(0, 20))) },
    )
    NumberRow(
        enabled = rowsEnabled,
        label = "Grid target cells",
        value = (configuration.targetCells ?: 0).toDouble(),
        format = { fixed(it, 0) },
        onValue = {
            onConfiguration(
                configuration.copy(targetCells = it.toInt().coerceIn(20_000, 4_000_000)),
            )
        },
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeButton("Graded", options.mode == FilaSimOptimizeMode.GRADED, enabled = rowsEnabled) {
            onOptions(options.copy(mode = FilaSimOptimizeMode.GRADED))
        }
        ModeButton("Binary", options.mode == FilaSimOptimizeMode.BINARY, enabled = rowsEnabled) {
            onOptions(options.copy(mode = FilaSimOptimizeMode.BINARY))
        }
        ModeButton("Part Topo", options.mode == FilaSimOptimizeMode.SOLID_TOPOLOGY, enabled = rowsEnabled) {
            onOptions(options.copy(mode = FilaSimOptimizeMode.SOLID_TOPOLOGY))
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCheck, enabled = enabled && state.canRun) { Text("Check") }
        OutlinedButton(onClick = onSolve, enabled = enabled && state.canRun) { Text("Solve") }
        Button(onClick = onOptimize, enabled = enabled && state.canRun, modifier = Modifier.weight(1f)) {
            Text("Optimize")
        }
    }

    // The Check and Solve buttons produce readouts, not just errors: show what
    // they found, because "nothing happened" is the worst possible answer for a
    // setup that cannot be optimized.
    state.check?.let { report ->
        Text(
            buildString {
                append("Setup check: ")
                if (report.ok) {
                    append("ok — ")
                    append(
                        if (report.islandCount == 1) {
                            "one connected body"
                        } else {
                            report.islandCount.toString() + " bodies"
                        },
                    )
                    append(", constrained and loaded")
                } else {
                    val free = report.components.count { component -> !component.constrained }
                    val unloaded = report.components.count { component -> !component.hasLoads }
                    append("cannot be optimized yet — ")
                    if (free > 0) {
                        append(free.toString() + " of " + report.components.size + " bodies can move freely")
                    }
                    if (free > 0 && unloaded > 0) append(" and ")
                    if (unloaded > 0) append(unloaded.toString() + " carries no load")
                    append("; add the supports and the load")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (report.ok) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
    state.solve?.let { report ->
        Text(
            "Solve: " + report.iterations + " iterations, " +
                (if (report.converged) "converged" else "stopped early") +
                " · max displacement " + fixed(report.maxDisplacementMm, 3) + " mm",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (state.isBusy) {
        val progress = state.progress
        LinearProgressIndicator(
            progress = { progress?.fraction ?: 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            buildString {
                append(progress?.phase?.replace('_', ' ') ?: "working")
                if (progress != null && progress.maxIterations > 0) {
                    append(" · iteration ${progress.iteration}/${progress.maxIterations}")
                }
                if (progress != null && progress.pass > 1) {
                    append(" · pass ${progress.pass}/${progress.passes}")
                }
            },
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
    }

    state.optimization?.let { result ->
        ResultSection(result)
        Button(onClick = onApply, enabled = enabled && !state.isBusy, modifier = Modifier.fillMaxWidth()) {
            Text(if (result.solid) "Replace the model with this body" else "Use these density modifiers")
        }
    }
}

/**
 * One boundary condition: what it does, the surface it acts on, and the values
 * it carries. Every value is editable here — a load is a vector, not a preset,
 * and the title above it is rebuilt from these fields.
 */
@Composable
private fun ConditionRow(
    entry: SmartInfillCondition,
    enabled: Boolean,
    onArmPicking: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onExpand: (Long) -> Unit,
    onUpdate: (Long, FilaSimBoundaryCondition) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (entry.triangleCount > 0) {
                        "${entry.triangleCount} triangles"
                    } else {
                        "tap the model to choose its surface"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { onArmPicking(entry.id) }, enabled = enabled) { Text("Pick") }
            TextButton(
                onClick = { onExpand(entry.id) },
                enabled = enabled && entry.triangleCount > 0,
            ) { Text("Face") }
            TextButton(onClick = { onRemove(entry.id) }, enabled = enabled) { Text("Delete") }
        }
        ConditionParameters(entry.condition) { updated -> onUpdate(entry.id, updated) }
    }
}

/** The editable values of a condition; kinds with nothing to tune show nothing. */
@Composable
private fun ConditionParameters(
    condition: FilaSimBoundaryCondition,
    onUpdate: (FilaSimBoundaryCondition) -> Unit,
) {
    when (condition) {
        is FilaSimBoundaryCondition.Force ->
            VectorRow("N", condition.vector) { onUpdate(condition.withVector(it)) }

        is FilaSimBoundaryCondition.Moment ->
            VectorRow("N·mm", condition.vector) { onUpdate(condition.withVector(it)) }

        is FilaSimBoundaryCondition.Bearing ->
            VectorRow("N", condition.vector) { onUpdate(condition.withVector(it)) }

        is FilaSimBoundaryCondition.Displacement ->
            VectorRow("mm", condition.vector) { onUpdate(condition.withVector(it)) }

        is FilaSimBoundaryCondition.Pressure -> NumberRow(
            label = "Pressure MPa",
            value = condition.mpa,
            format = { fixed(it) },
            onValue = { onUpdate(condition.withScalar(it)) },
        )

        is FilaSimBoundaryCondition.Elastic -> NumberRow(
            label = "Bedding N/mm³",
            value = condition.stiffnessNPerMm3,
            format = { fixed(it) },
            onValue = { onUpdate(condition.withScalar(it)) },
        )

        is FilaSimBoundaryCondition.Mass -> NumberRow(
            label = "Mass g",
            value = condition.massTonnes * 1e6,
            format = { fixed(it) },
            onValue = { onUpdate(condition.withScalar(it / 1e6)) },
        )

        else -> Unit
    }
}

/** The three components of a vector condition, one field per axis. */
@Composable
private fun VectorRow(
    unit: String,
    vector: List<Double>,
    onUpdate: (List<Double>) -> Unit,
) {
    val values = List(3) { index -> vector.getOrElse(index) { 0.0 } }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEachIndexed { index, value ->
            NumberRow(
                label = AXIS_LABELS[index] + " " + unit,
                value = value,
                format = { fixed(it) },
                onValue = { changed ->
                    onUpdate(values.toMutableList().also { it[index] = changed })
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ResultSection(result: FilaSimOptimization) {
    Text("Result", style = MaterialTheme.typography.titleSmall)
    if (result.regions.isNotEmpty()) {
        Text(
            "Modifier regions: " + result.regions.joinToString { percent(it.densityPercent) },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (result.bins.isNotEmpty()) {
        Text(
            "Infill by density — the regions are drawn on the model in these colours:",
            style = MaterialTheme.typography.bodySmall,
        )
        val cells = result.bins.sumOf { bin -> bin.cells }.coerceAtLeast(1)
        result.bins.filter { bin -> bin.cells > 0 }.forEach { bin ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(densityColor(bin.densityPercent / 100.0)),
                )
                Text(
                    "  " + percent(bin.densityPercent) + " · " + bin.cells + " cells (" +
                        percent(bin.cells * 100.0 / cells) + " of the optimized volume)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    Text(
        "Mean infill ${percent(result.meanInfillPercent)} (target ${percent(result.targetInfillPercent)}) · " +
            "mass ${fixed(result.massGrams)} g vs ${fixed(result.massSolidGrams)} g solid",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Max displacement ${fixed(result.maxDisplacementMm, 3)} mm · " +
            "uniform ${fixed(result.uniformMaxDisplacementMm, 3)} mm · " +
            "solid ${fixed(result.solidMaxDisplacementMm, 3)} mm",
        style = MaterialTheme.typography.bodySmall,
    )
    result.safetyFactorAchieved?.let { achieved ->
        val target = result.safetyFactorTarget
        Text(
            buildString {
                append("Safety factor ${fixed(achieved)}")
                if (target != null) append(" against a target of ${fixed(target, 1)}")
                if (result.safetyFactorFeasible == false) append(" — not reachable at this budget")
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        "${result.iterations} iterations · " + if (result.converged) "converged" else "stopped early",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
    }
}

/**
 * A compact numeric row. The engine clamps every field again on its side, so
 * these bounds only keep the keyboard honest.
 */
@Composable
private fun NumberRow(
    label: String,
    value: Double,
    format: (Double) -> String,
    onValue: (Double) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf(format(value)) }
    LaunchedEffect(value, focused) {
        if (!focused) text = format(value)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            parseNumberInput(input)?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            // Every keystroke commits a value and the row re-renders from it: while
            // the field has focus it has to keep the keystrokes, or typing "3,5"
            // rewrites the "3" to "3.00" and the rest lands on top of that. Losing
            // focus shows the committed value in its canonical form again.
            .onFocusChanged { focusState ->
                focused = focusState.isFocused
                if (!focusState.isFocused) text = format(value)
            },
    )
}

/** A labelled switch row, for the settings that are a yes or a no. */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

/**
 * Parses a numeric field, accepting the comma decimal separator a German or
 * French keyboard offers.
 *
 * Infinity and NaN are refused rather than passed on: `toDoubleOrNull` accepts
 * the words "NaN" and "Infinity", and org.json throws on a non-finite number, so
 * typing "NaN" into a field would otherwise crash the app when the settings are
 * serialized. A refused keystroke simply leaves the previous value in place.
 */
internal fun parseNumberInput(input: String): Double? =
    input.replace(',', '.').toDoubleOrNull()?.takeIf { value -> value.isFinite() }

/** Standard gravity in the engine's units (mm/s²), the self-weight load. */
private const val ONE_G_MM_PER_S2 = 9806.65

private val AXIS_LABELS = listOf("X", "Y", "Z")

/** The tint the viewer gives a region of this density (one ramp, shared). */
private fun densityColor(densityFraction: Double): Color {
    val rgb = DensityRamp.color(densityFraction)
    return Color(rgb[0], rgb[1], rgb[2])
}

private fun fixed(value: Double, decimals: Int = 2): String = "%.${decimals}f".format(value)

private fun whole(value: Double): String = value.toInt().toString()

private fun percent(value: Double): String = "%.1f".format(value) + "%"
