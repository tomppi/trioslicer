package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.engine.EnginePreparation
import kotlinx.coroutines.delay

/**
 * The first init: prepare every engine on purpose, instead of meeting each one's first use.
 *
 * Every payload in this app is unpacked lazily - the CAD tree is 533 MB, Blender's Python
 * 140 MB, Orca's profiles 80 MB - so without this a new install pays for them one surprise
 * at a time, with minutes of silence and no way to tell working from stuck. The rows are
 * the whole point: each one ends ready, or failed with the reason, and none of them is
 * allowed to sit on a sentence that never changes.
 *
 * Shown as the last onboarding step and afterwards from Settings, because a user may add an
 * engine later or want to retry a failure. It is not a blocking step: preparing can be
 * hundreds of megabytes and minutes of work, and the app is usable without it - every
 * engine's own screen now says what it is waiting for and where to prepare it.
 */
@Composable
internal fun EngineSetupScreen(
    rows: List<EnginePreparation.Row>,
    selected: Set<EnginePreparation.Engine>,
    busy: Boolean,
    freeBytes: Long,
    onPrepare: () -> Unit,
    onToggle: (EnginePreparation.Engine) -> Unit,
    onSelectAll: () -> Unit,
    /** Null where there is nowhere to skip to - the Settings entry, which has Back. */
    onSkip: (() -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** What this run is, in one line, because onboarding and Settings open the same screen. */
    intro: String = "Every engine unpacks its files the first time it is used. Doing it " +
        "here, once, means the first slice and the first CAD model do not have to wait for it.",
) {
    val needed = EnginePreparation.neededBytes(selected)
    val enoughSpace = needed <= freeBytes
    // Ticks while a run is in flight, so a row's elapsed seconds move even between two
    // file callbacks. The rule is that nothing on this screen may look frozen.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(busy) {
        while (busy) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(tonalElevation = 3.dp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                TextButton(onClick = onBack) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (onSkip == null) "Back" else "Close")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (onSkip == null) "Engines" else "Step 3 of 3",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("Prepare the engines", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = intro,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(12.dp),
                ) {
                    Text(
                        text = if (needed == 0L) {
                            "Nothing to unpack for the engines you have ticked."
                        } else {
                            "Needs about " + sizeLabel(needed) + " · " +
                                sizeLabel(freeBytes) + " free on this device"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!enoughSpace) {
                        Text(
                            text = "There is not enough free space for this selection. " +
                                "Untick an engine, or free some space first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = onSelectAll, enabled = !busy) {
                        Text("Select all engines")
                    }
                }
            }

            rows.forEach { row ->
                EnginePrepRow(
                    row = row,
                    checked = row.engine in selected,
                    enabled = !busy,
                    now = now,
                    onToggle = { onToggle(row.engine) },
                )
            }

            if (busy) {
                Text(
                    text = "Preparing the ticked engines, one at a time. This ends with every " +
                        "one of them ready or a reason it is not.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Button(
                onClick = onPrepare,
                enabled = !busy && selected.isNotEmpty() && enoughSpace,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "Preparing…" else "Prepare selected engines")
            }
            if (onSkip != null) {
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Skip for now")
                }
                if (busy) {
                    Text(
                        text = "Skipping does not cancel the run; it finishes in the background.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Optional: your first slice or CAD model will unpack what it " +
                            "needs instead, and will say so while it does.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EnginePrepRow(
    row: EnginePreparation.Row,
    checked: Boolean,
    enabled: Boolean,
    now: Long,
    onToggle: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                // A row that is being written is not a row to untick: the run has already
                // started on it, and the tick would be a claim about the next run.
                enabled = enabled && row.phase != EnginePreparation.Phase.RUNNING,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(row.engine.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = row.engine.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = phaseLine(row, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (row.phase) {
                        EnginePreparation.Phase.READY -> MaterialTheme.colorScheme.primary
                        EnginePreparation.Phase.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** What one row is doing, always with the numbers that prove it is doing something. */
private fun phaseLine(row: EnginePreparation.Row, now: Long): String = when (row.phase) {
    EnginePreparation.Phase.PENDING ->
        if (row.engine.needsBytes == 0L) "Nothing to extract"
        else "Not prepared yet · about " + sizeLabel(row.engine.needsBytes)
    EnginePreparation.Phase.RUNNING ->
        "Extracting: " + "%,d".format(row.files) + " files, " +
            "%.0f".format(row.bytes / (1024.0 * 1024.0)) + " MB, " +
            row.elapsedSeconds(now) + "s"
    EnginePreparation.Phase.READY -> "Ready"
    EnginePreparation.Phase.FAILED -> "Failed: " + (row.reason ?: "no reason reported")
}

/** MB below a gigabyte, GB above it: the number a user can hold in their head. */
internal fun sizeLabel(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    else -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
}

/** The selection as one saveable string: a Set is not a Bundle value, an id list is. */
internal fun encodeEngineSelection(selected: Set<EnginePreparation.Engine>): String =
    selected.joinToString(",") { it.id }

internal fun decodeEngineSelection(encoded: String): Set<EnginePreparation.Engine> =
    encoded.split(',')
        .mapNotNull { id -> EnginePreparation.Engine.entries.firstOrNull { it.id == id } }
        .toSet()
