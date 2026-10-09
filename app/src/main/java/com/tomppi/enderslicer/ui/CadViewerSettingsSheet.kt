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

        CadRenderSizeRow(
            title = "Drag quality",
            description = "The frame size while the view is moving. Smaller keeps a heavy part " +
                "responsive; larger keeps it sharp mid-drag.",
            choices = CadViewerSettings.DRAG_CHOICES,
            selected = settings.dragQuality,
        ) { settings = settings.copy(dragQuality = it) }

        CadRenderSizeRow(
            title = "Still quality",
            description = "The frame size once the camera stops. Full is the display's own size.",
            choices = CadViewerSettings.IDLE_CHOICES,
            selected = settings.idleQuality,
        ) { settings = settings.copy(idleQuality = it) }

        SettingSwitch(
            title = "Shaded surfaces",
            description = "Off draws the wireframe, which shows the far side of an open shell.",
            checked = settings.shaded,
            onChecked = { settings = settings.copy(shaded = it) },
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
private fun CadRenderSizeRow(
    title: String,
    description: String,
    choices: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title)
        Text(description, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice ->
                FilterChip(
                    selected = choice == selected,
                    onClick = { onSelect(choice) },
                    label = { Text(CadViewerSettings.label(choice)) },
                )
            }
        }
    }
}
