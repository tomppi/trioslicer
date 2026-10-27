package com.tomppi.enderslicer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The native BumpMesh studio sheet.
 *
 * The same split rule as the CAD agent sheet: the sheet measures its own width and shows the
 * texture gallery on the left and the settings/apply column on the right when there is room,
 * and one scrolling column below that. It renders [BumpMeshStudioState] and reports every
 * gesture through [BumpMeshStudioActions]; it owns no engine, no file and no undo stack.
 */
internal data class BumpMeshStudioActions(
    val onClose: () -> Unit = {},
    val onSearch: (String) -> Unit = {},
    val onCategory: (String?) -> Unit = {},
    val onTexture: (BumpMeshTextureRef) -> Unit = {},
    val onMapping: (BumpMeshMappingMode) -> Unit = {},
    val onTileMm: (Float) -> Unit = {},
    val onHeightMm: (Float) -> Unit = {},
    val onSmoothing: (Float) -> Unit = {},
    val onRefineMm: (Float) -> Unit = {},
    val onWholeObject: (Boolean) -> Unit = {},
    val onTier: (BumpMeshTier) -> Unit = {},
    val onConfirmTier: () -> Unit = {},
    val onPaintRegion: () -> Unit = {},
    val onRaiseLimit: () -> Unit = {},
    val onApply: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onOpenWebWorkspace: () -> Unit = {},
)

@Composable
internal fun BumpMeshStudioSheet(
    state: BumpMeshStudioState,
    actions: BumpMeshStudioActions,
    /** Non-null while the native host is not part of this build. */
    engineUnavailableReason: String? = null,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                StudioHeader(state, actions)
                HorizontalDivider()
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    StudioPane(title = "Textures", modifier = Modifier.weight(1f)) {
                        TextureGallery(state = state, actions = actions)
                    }
                    VerticalDivider()
                    StudioPane(
                        title = "Settings",
                        modifier = Modifier.weight(1.15f),
                        footer = { ApplyFooter(state, actions, engineUnavailableReason) },
                    ) {
                        StudioSettings(state, actions)
                    }
                }
            }
        } else {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(EnderSlicerDimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        Column(verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4)) {
                            Text("BumpMesh studio", style = MaterialTheme.typography.titleMedium)
                            SourceLine(state)
                        }
                    }
                    TextButton(onClick = actions.onClose) { Text("Done") }
                }
                TextureGallery(state, actions)
                HorizontalDivider()
                StudioSettings(state, actions)
                HorizontalDivider()
                ApplyFooter(state, actions, engineUnavailableReason)
                Text(
                    "Open the full BumpMesh workspace for every control and the web page's own preview.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = actions.onOpenWebWorkspace) {
                    Text("Open the full BumpMesh workspace")
                }
            }
        }
    }
}

@Composable
private fun StudioHeader(state: BumpMeshStudioState, actions: BumpMeshStudioActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EnderSlicerDimens.CardPadding, vertical = EnderSlicerDimens.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4),
        ) {
            Text("BumpMesh studio", style = MaterialTheme.typography.titleMedium)
            SourceLine(state)
        }
        TextButton(onClick = actions.onClose) { Text("Done") }
    }
}

@Composable
private fun SourceLine(state: BumpMeshStudioState) {
    Text(
        if (state.sourceName.isBlank()) {
            "No plate object selected"
        } else {
            state.sourceName + " · " + MeshTriangleLimits.formatCount(state.sourceTriangles) +
                " triangles"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** One pane of the wide studio: a heading, its own scroll, an optional pinned footer. */
@Composable
private fun StudioPane(
    title: String,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxHeight()) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(
                start = EnderSlicerDimens.CardPadding,
                end = EnderSlicerDimens.CardPadding,
                top = EnderSlicerDimens.Space8,
                bottom = EnderSlicerDimens.Space4,
            ),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = EnderSlicerDimens.CardPadding,
                    end = EnderSlicerDimens.CardPadding,
                    top = EnderSlicerDimens.Space4,
                    bottom = EnderSlicerDimens.Space8,
                ),
            verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
            content = content,
        )
        if (footer != null) {
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(EnderSlicerDimens.CardPadding),
            ) {
                footer()
            }
        }
    }
}

@Composable
private fun TextureGallery(state: BumpMeshStudioState, actions: BumpMeshStudioActions) {
    OutlinedTextField(
        value = state.search,
        onValueChange = actions.onSearch,
        label = { Text("Search textures") },
        placeholder = { Text("brick, wood, knurl…") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.textures.isEmpty()) {
        Text(
            "No texture images are bundled in this build.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (state.categories.isNotEmpty()) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
        ) {
            FilterChip(
                selected = state.category == null,
                onClick = { actions.onCategory(null) },
                label = { Text("All") },
            )
            state.categories.forEach { category ->
                FilterChip(
                    selected = state.category == category,
                    onClick = { actions.onCategory(category) },
                    label = { Text(category) },
                )
            }
        }
    }
    val filtered = state.filteredTextures
    Text(
        MeshTriangleLimits.formatCount(filtered.size) + " of " +
            MeshTriangleLimits.formatCount(state.textures.size) + " textures",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (filtered.isEmpty()) {
        Text(
            "No texture matches this search.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // The gallery sizes its own cells from the pane: a 110 dp thumbnail is the smallest that
    // still reads as a texture rather than a smudge, and the cell count follows the width.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 110.dp).toInt().coerceIn(2, 4)
        // One Column inside the Box: a Box stacks its children, and 51 sibling Rows would
        // draw on top of each other (the sheet showed one row of textures, not 51).
        Column(verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8)) {
            filtered.chunked(columns).forEach { rowTextures ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    rowTextures.forEach { texture ->
                        TextureCell(
                            texture = texture,
                            selected = state.texture?.assetPath == texture.assetPath,
                            modifier = Modifier.weight(1f),
                            onClick = { actions.onTexture(texture) },
                        )
                    }
                    repeat(columns - rowTextures.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TextureCell(
    texture: BumpMeshTextureRef,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, texture.assetPath) {
        value = withContext(Dispatchers.IO) {
            BumpMeshTextureCatalog.thumbnail(context, texture)
        }
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(EnderSlicerDimens.Space4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4),
        ) {
            val image = thumbnail
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Box(modifier = Modifier.fillMaxWidth().height(64.dp))
            }
            Text(
                texture.displayName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StudioSettings(state: BumpMeshStudioState, actions: BumpMeshStudioActions) {
    Text("Texture", style = MaterialTheme.typography.titleSmall)
    Text(
        state.texture?.displayName ?: "Pick a texture on the left.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        state.texture?.category?.let { "Category: " + it } ?: "",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text("Mapping", style = MaterialTheme.typography.titleSmall)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
    ) {
        BumpMeshMappingMode.entries.forEach { mode ->
            FilterChip(
                selected = state.mapping == mode,
                onClick = { actions.onMapping(mode) },
                label = { Text(mode.label) },
            )
        }
    }

    SliderRow(
        label = "Tile size",
        valueText = formatStudioValue(state.tileMm) + " mm",
        value = state.tileMm,
        range = 1.0f..100.0f,
        onValue = actions.onTileMm,
        hint = "one texture repeat",
    )
    SliderRow(
        label = "Displacement height",
        valueText = formatStudioValue(state.heightMm) + " mm",
        value = state.heightMm,
        range = 0.1f..3.0f,
        onValue = actions.onHeightMm,
        hint = "0.6 mm is the spike's safe default; heavy displacement can stall slicing",
    )
    SliderRow(
        label = "Smoothing",
        valueText = formatStudioValue(state.smoothing),
        value = state.smoothing,
        range = 0.0f..5.0f,
        onValue = actions.onSmoothing,
    )
    SliderRow(
        label = "Refine length",
        valueText = formatStudioValue(state.refineMm) + " mm",
        value = state.refineMm,
        range = state.refineRange,
        onValue = actions.onRefineMm,
        hint = if (state.tier == BumpMeshTier.EXTREME) {
            "smaller = more triangles; 0.12 mm is the measured-safe floor above 2M"
        } else {
            "smaller = more triangles; this is the size control"
        },
    )

    HorizontalDivider()
    Text("Region", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8)) {
        FilterChip(
            selected = state.wholeObject,
            onClick = { actions.onWholeObject(true) },
            label = { Text("Whole object") },
        )
        FilterChip(
            selected = !state.wholeObject,
            onClick = { actions.onWholeObject(false) },
            label = { Text("Painted region") },
        )
    }
    if (!state.wholeObject) {
        Text(
            if (state.maskStale) {
                "The painted region belonged to a previous mesh; it was cleared with it."
            } else if (state.maskTriangles > 0) {
                MeshTriangleLimits.formatCount(state.maskTriangles) +
                    " painted triangles are the include region."
            } else {
                "No painted region yet - paint the region on the plate first."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.maskTriangles > 0 && !state.maskStale) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        TextButton(onClick = actions.onPaintRegion) { Text("Paint the region") }
        Text(
            state.maskLifecycleNote,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    HorizontalDivider()
    Text("Size", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8)) {
        BumpMeshTier.entries.forEach { tier ->
            FilterChip(
                selected = state.tier == tier,
                onClick = { actions.onTier(tier) },
                label = { Text(tier.label) },
            )
        }
    }
    Text(
        state.tier.shortDescription,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.extremeNeedsConfirm) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(EnderSlicerDimens.Space8),
                verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4),
            ) {
                Text(
                    "Extreme can take minutes to apply and needs the app's 8,000,000-triangle " +
                        "limit; the spike peaked at 5.13 GiB of WebView memory for 4.79M output.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = actions.onConfirmTier) { Text("Confirm Extreme tier") }
            }
        }
    }

    Text(
        "Estimated result: " + MeshTriangleLimits.formatCount(state.estimate) + " triangles",
        style = MaterialTheme.typography.bodyMedium,
    )
    state.estimateWarning?.let { warning ->
        Text(
            warning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    HorizontalDivider()
    Text("Mesh triangle limit", style = MaterialTheme.typography.titleSmall)
    Text(state.limitCheck.sentence, style = MaterialTheme.typography.bodySmall)
    if (!state.limitCheck.satisfied) {
        Button(onClick = actions.onRaiseLimit, modifier = Modifier.fillMaxWidth()) {
            Text("Raise limit to " + MeshTriangleLimits.formatCount(state.limitCheck.required))
        }
        Text(
            "The limit is the app's own import cap; the studio refuses rather than returning a " +
                "silently capped mesh.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    state.error?.let { error ->
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    state.result?.let { result ->
        Text(result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    state.repairWarning?.let { warning ->
        Text(warning, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
    hint: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value, onValueChange = onValue, valueRange = range)
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The apply row: the pinned footer of the wide sheet, the last block of the compact column. */
@Composable
private fun ApplyFooter(
    state: BumpMeshStudioState,
    actions: BumpMeshStudioActions,
    engineUnavailableReason: String?,
) {
    when {
        state.applying -> {
            Column(verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8)) {
                Text(state.progressLabel, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = actions.onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
        engineUnavailableReason != null -> {
            Text(
                engineUnavailableReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = actions.onApply, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("Apply")
            }
        }
        else -> {
            state.applyBlockedReason?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = actions.onApply,
                enabled = state.canApply,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Apply to " + (if (state.wholeObject) "whole object" else "painted region"),
                )
            }
            Text(
                "Push and hold the model to undo a texture from the side rail.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatStudioValue(value: Float): String =
    if (value >= 10f) String.format(java.util.Locale.US, "%.1f", value)
    else String.format(java.util.Locale.US, "%.2f", value)
