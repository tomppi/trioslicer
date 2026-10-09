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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.data.PlatePreferences

/**
 * The plate's own settings: where several objects go and how they are printed.
 *
 * Sheet content, not the sheet: the caller wraps it, the way every other sheet here is wrapped.
 * Each control applies as it is touched, because arranging is cheap enough to do again and an
 * arrangement the user cannot see until a Save button is one they cannot judge.
 */
@Composable
internal fun MultiObjectSheet(
    preferences: PlatePreferences,
    onPreferences: (PlatePreferences) -> Unit,
    /** Lays the plate out again now, whatever the placement mode says. */
    onArrangeNow: () -> Unit,
    /** How many objects are on the plate, for the explanatory line. */
    objectCount: Int,
    onDismiss: () -> Unit,
) {
    var settings by remember(preferences) { mutableStateOf(preferences) }

    // One place that both shows a change and reports it, so no control can forget one of them.
    fun apply(next: PlatePreferences) {
        settings = next
        onPreferences(next)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Multi-object plate", style = MaterialTheme.typography.headlineSmall)
        Text("Objects on the plate: $objectCount", style = MaterialTheme.typography.titleMedium)
        Text(
            "TrioSlicer arranges the plate itself: none of the three engines arranges for us, and " +
                "both Slic3r forks would happily slice one object through another.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("Placement")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.placement == PlatePreferences.Placement.AUTO,
                onClick = { apply(settings.copy(placement = PlatePreferences.Placement.AUTO)) },
                label = { Text("Auto arrange") },
            )
            FilterChip(
                selected = settings.placement == PlatePreferences.Placement.MANUAL,
                onClick = { apply(settings.copy(placement = PlatePreferences.Placement.MANUAL)) },
                label = { Text("Manual") },
            )
        }
        Text(
            if (settings.placement == PlatePreferences.Placement.AUTO) {
                "Auto arrange finds a free spot and keeps the gap below."
            } else {
                "Manual: an imported object lands where its file says, and you drag it."
            },
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedButton(
            onClick = onArrangeNow,
            // One object has nothing to be arranged around, and the packer says the same.
            enabled = objectCount > 1,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Arrange now")
        }
        Text(
            if (objectCount > 1) {
                "Gives every object its place again right now, whatever the placement mode says."
            } else {
                "One object is already where you put it; arranging needs two or more."
            },
            style = MaterialTheme.typography.labelSmall,
        )

        SectionLabel("Spacing")
        NumberField(
            label = "Gap between objects (mm)",
            value = settings.spacingMm,
            source = "The gap kept between parts and to the bed edge; clamped to " +
                PlatePreferences.MIN_SPACING_MM + "-" + PlatePreferences.MAX_SPACING_MM + " mm.",
        ) { value -> apply(settings.copy(spacingMm = value).sanitized()) }

        SectionLabel("Printing")
        SettingSwitch(
            title = "Print one object at a time",
            description = "Finish each object to its top before the next one starts, instead of " +
                "laying every object down layer by layer.",
            checked = settings.sequential,
            onChecked = { apply(settings.copy(sequential = it)) },
        )
        Text(
            "None of the three engines checks the head clearance for you in this mode: the head, " +
                "the gantry or a fan duct can hit what is already printed, so keep the parts far " +
                "enough apart for the head to pass between them.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        SettingSwitch(
            title = "Label each object in the G-code",
            description = "Writes a marker around each object, so the printer screen can cancel " +
                "one object on its own and the layer list can say which object a move belongs to.",
            checked = settings.objectLabels,
            onChecked = { apply(settings.copy(objectLabels = it)) },
        )

        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
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
