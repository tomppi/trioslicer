package com.tomppi.enderslicer.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.SlicerSettings

/**
 * Persistent state for the Printer safety checklist: which checks the user
 * has confirmed for the current machine. Survives restarts.
 */
class PrinterChecklistStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): Set<String> =
        preferences.getStringSet(KEY_DONE, emptySet())?.toSet() ?: emptySet()

    fun save(done: Set<String>) {
        preferences.edit().putStringSet(KEY_DONE, done).apply()
    }

    private companion object {
        const val PREFERENCES = "printer-checklist"
        const val KEY_DONE = "done"
    }
}

/** Stable ids for the printer safety-checklist items. */
object PrinterChecklistIds {
    const val BUILD_VOLUME = "build_volume"
    const val NOZZLE = "nozzle"
    const val HOTEND = "hotend"
    const val GCODE = "gcode"
    const val REMOTE = "remote"

    val ALL: List<String> = listOf(BUILD_VOLUME, NOZZLE, HOTEND, GCODE, REMOTE)
}

/**
 * Full-screen Printer destination: the safety checklist on top, then the
 * machine profile (identity, build volume, extruder, G-code) that drives
 * CuraEngine and the build-plate viewer. See docs/ux-redesign/DESIGN_PROPOSAL.md.
 */
@Composable
internal fun PrinterScreen(
    state: MainUiState,
    checklistDone: Set<String>,
    onChecklistToggle: (String, Boolean) -> Unit,
    onSettings: (String, (SlicerSettings) -> SlicerSettings) -> Unit,
    onResetOverrides: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        SafetyChecklistCard(state, checklistDone, onChecklistToggle)
        MachineSettingsContent(
            state = state,
            onSettings = onSettings,
            onResetOverrides = onResetOverrides,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SafetyChecklistCard(
    state: MainUiState,
    done: Set<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    val settings = state.settings
    val items = listOf(
        ChecklistItem(
            id = PrinterChecklistIds.BUILD_VOLUME,
            title = "Build volume matches your printer",
            subtitle = "%.0f × %.0f × %.0f mm".format(
                settings.machineWidthMm,
                settings.machineDepthMm,
                settings.machineHeightMm,
            ),
        ),
        ChecklistItem(
            id = PrinterChecklistIds.NOZZLE,
            title = "Nozzle %.2f mm installed".format(settings.nozzleSizeMm),
            subtitle = "Matches the machine profile",
        ),
        ChecklistItem(
            id = PrinterChecklistIds.HOTEND,
            title = "Hotend limit matches your firmware",
            subtitle = "Profile hotend %d °C · verify before a critical print".format(settings.nozzleTemperatureC),
        ),
        ChecklistItem(
            id = PrinterChecklistIds.GCODE,
            title = "Start & end G-code verified",
            subtitle = "Bed wait · home · first-layer height",
        ),
        ChecklistItem(
            id = PrinterChecklistIds.REMOTE,
            title = "Remote printing configured",
            subtitle = "OctoPrint setup and controls live in the Print tab",
        ),
    )
    val doneCount = items.count { it.id in done }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Safety checklist", style = MaterialTheme.typography.titleMedium)
                Text(
                    "$doneCount / ${items.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (doneCount == items.size) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                "Confirm each item once for your machine. These checks gate nothing - they are reminders that printer data drives the engine and the viewer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(item.id, item.id !in done) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = item.id in done,
                        onCheckedChange = { checked -> onToggle(item.id, checked) },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            item.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private data class ChecklistItem(
    val id: String,
    val title: String,
    val subtitle: String,
)
