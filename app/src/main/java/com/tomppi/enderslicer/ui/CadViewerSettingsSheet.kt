package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.modelling.CadViewerSettings

/**
 * The CAD viewport's settings.
 *
 * Two render sizes rather than one, because the viewport has two jobs: it has to keep up while a
 * finger is dragging it, and it has to be sharp once the finger stops. One number cannot do both,
 * and which trade is right depends on the phone and on how heavy the part is - so it is the user's
 * number rather than a constant inside the viewport.
 *
 * Everything below the sizes is passed to the engine, where the picture is made. Each one was
 * measured by rendering with it changed and comparing the pixels, because OCCT's rendering path
 * accepts calls it does not honour: the grid, background, projection, edges, view presets,
 * tessellation and anti-aliasing all change the frame; the axis cross changes it by a few hundred
 * pixels in the corner, which is what it should be.
 */
@Composable
internal fun CadViewerSettingsSheet(
    current: CadViewerSettings,
    onSave: (CadViewerSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var settings by remember(current) { mutableStateOf(current) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("CAD viewer", style = MaterialTheme.typography.headlineSmall)
        Text(
            "How this device draws the CAD engine's viewport. Nothing here changes the model.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("Speed")
        CadChoiceRow(
            title = "Drag quality",
            description = "The frame size while the view is moving. Smaller keeps a heavy part " +
                "responsive; larger keeps it sharp mid-drag.",
            choices = CadViewerSettings.DRAG_CHOICES,
            selected = settings.dragQuality,
            label = { CadViewerSettings.label(it) },
        ) { settings = settings.copy(dragQuality = it) }
        CadChoiceRow(
            title = "Still quality",
            description = "The frame size once the camera stops. Full is the display's own size.",
            choices = CadViewerSettings.IDLE_CHOICES,
            selected = settings.idleQuality,
            label = { CadViewerSettings.label(it) },
        ) { settings = settings.copy(idleQuality = it) }

        SectionLabel("Surfaces")
        SettingSwitch(
            title = "Shaded surfaces",
            description = "Off draws the wireframe, which shows the far side of an open shell.",
            checked = settings.shaded,
            onChecked = { settings = settings.copy(shaded = it) },
        )
        SettingSwitch(
            title = "Face edges",
            description = "The dark outline over a shaded surface. Without it a step on a " +
                "plate is invisible from straight above.",
            checked = settings.edges,
            onChecked = { settings = settings.copy(edges = it) },
        )
        CadChoiceRow(
            title = "Tessellation",
            description = "How finely curved surfaces are meshed. Finer is smoother and " +
                "heavier; coarse shows the facets a cylinder is really made of.",
            choices = CadViewerSettings.TESSELLATIONS,
            selected = settings.tessellation,
            label = { CadViewerSettings.word(it) },
        ) { settings = settings.copy(tessellation = it) }

        SectionLabel("Scene")
        SettingSwitch(
            title = "Grid",
            description = "A 1 mm grid on the bed, drawn at real size in the scene rather " +
                "than as a screen backdrop.",
            checked = settings.grid,
            onChecked = { settings = settings.copy(grid = it) },
        )
        if (settings.grid) {
            CadChoiceRow(
                title = "Grid spacing",
                description = "Wider spacing is easier to read on a big part.",
                choices = CadViewerSettings.GRID_STEPS,
                selected = settings.gridStepMm,
                label = { CadViewerSettings.gridLabel(it) },
            ) { settings = settings.copy(gridStepMm = it) }
        }
        SettingSwitch(
            title = "Axis cross",
            description = "The little X-Y-Z marker in the corner of the view.",
            checked = settings.axes,
            onChecked = { settings = settings.copy(axes = it) },
        )

        SectionLabel("Camera")
        CadChoiceRow(
            title = "Projection",
            description = "Orthographic to judge a dimension, perspective to look at a part.",
            choices = CadViewerSettings.PROJECTIONS,
            selected = settings.projection,
            label = { CadViewerSettings.word(it) },
        ) { settings = settings.copy(projection = it) }
        CadChoiceRow(
            title = "Background",
            description = "What is behind the part.",
            choices = CadViewerSettings.BACKGROUNDS,
            selected = settings.background,
            label = { CadViewerSettings.word(it) },
        ) { settings = settings.copy(background = it) }

        SectionLabel("Smoothing")
        SettingSwitch(
            title = "Anti-aliasing",
            description = "Draws the settled frame at twice the size and averages it down, so " +
                "edges are smooth rather than stepped. It is skipped while you are dragging, " +
                "where the cost would be lag.",
            checked = settings.antialiasing,
            onChecked = { settings = settings.copy(antialiasing = it) },
        )
        SettingSwitch(
            title = "Status line",
            description = "The overlay that says what the engine is doing.",
            checked = settings.showStatus,
            onChecked = { settings = settings.copy(showStatus = it) },
        )

        Button(
            onClick = { onSave(settings) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save viewer settings")
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun <T> CadChoiceRow(
    title: String,
    description: String,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title)
        Text(description, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice ->
                FilterChip(
                    selected = choice == selected,
                    onClick = { onSelect(choice) },
                    label = { Text(label(choice)) },
                )
            }
        }
    }
}
