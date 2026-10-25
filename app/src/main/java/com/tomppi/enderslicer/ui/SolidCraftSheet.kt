package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.solidcraft.CadDocument
import com.tomppi.enderslicer.solidcraft.SolidCraftEngine
import kotlinx.coroutines.delay

/**
 * The CAD agent sheet: what a SolidCraft CAD document is, and the whole human surface on it.
 *
 * A live CAD document is the agent's work and the human is a spectator. This sheet is therefore
 * the only place a human can *ask* for a change, and the only place that says what the document
 * is: the plate's left rail otherwise offers Slice and Export and nothing that edits. The
 * document's own controls - orbit, zoom, pan and double-tap reset - are the model view's, and
 * they keep working while the agent is mid-edit because a repaint never moves the camera.
 */
@Composable
internal fun SolidCraftSheet(
    document: CadDocument?,
    messages: List<AiChatMessage>,
    /** False when the harness has not been reached, so the chat cannot be used yet. */
    chatConfigured: Boolean,
    chatBusy: Boolean,
    chatStatus: String?,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onCloseDocument: () -> Unit,
    /** Re-derives the plate from the document's current shape (the way out of a stale plate). */
    onRebuild: () -> Unit,
    onCopyToken: () -> Unit,
    onExport: (format: String) -> Unit,
    onOpenFile: () -> Unit,
    onAsk: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // The engine's status is a sentence that changes while it starts and stops; poll while the
    // sheet is on screen so the first line is the truth, not what it was when the sheet opened.
    var engineStatus by remember { mutableStateOf(SolidCraftEngine.status(context)) }
    var ready by remember { mutableStateOf(SolidCraftEngine.isReady()) }
    var starting by remember { mutableStateOf(SolidCraftEngine.isStarting()) }
    LaunchedEffect(Unit) {
        while (true) {
            engineStatus = SolidCraftEngine.status(context)
            ready = SolidCraftEngine.isReady()
            starting = SolidCraftEngine.isStarting()
            delay(400)
        }
    }
    var draft by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(EnderSlicerDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
    ) {
        Text("CAD agent", style = MaterialTheme.typography.titleMedium)
        Text(
            "SolidCraft runs as the app's own CAD engine. The document is the engine's, and " +
                "the agent is its only author: you can look at it, slice it and export it, and " +
                "ask for changes here. Nothing on the plate edits it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(EnderSlicerDimens.Space8)) {
                Text(
                    if (starting) engineStatus else "Engine: " + engineStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ready) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                document?.let { live ->
                    Text(
                        "Document: " + live.name + " · " + live.bodyCount + " bodies · " +
                            "%,d".format(live.triangleCount) + " triangles on screen" +
                            (live.lastEditMillis?.let { " · last edit " + it + " ms" } ?: "") +
                            if (live.updating) " · changing…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (live.derivedStale) {
                        Text(
                            live.staleReason ?: "the plate holds a split made from the previous shape",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (live.sliceExportAtMillis > 0L) {
                        Text(
                            "Last Slice export: " + "%,d".format(live.sliceTriangles) +
                                " triangles, " +
                                ((System.currentTimeMillis() - live.sliceExportAtMillis) / 1000)
                                    .coerceAtLeast(0) + "s ago",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8)) {
            Button(
                onClick = onConnect,
                enabled = !ready && !starting,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (starting) "Connecting…" else if (ready) "Connected" else "Connect agent")
            }
            OutlinedButton(
                onClick = onStop,
                enabled = ready || starting,
                modifier = Modifier.weight(1f),
            ) { Text("Stop engine") }
        }
        Text(
            "The agent drives the engine over MCP on 127.0.0.1:" + SolidCraftEngine.DEFAULT_PORT +
                ". Copy the token and give it to the agent together with the skill; its " +
                "geometry changes appear on the plate as they happen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8)) {
            OutlinedButton(onClick = onCopyToken, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(EnderSlicerDimens.Space4))
                Text("Copy MCP token")
            }
            OutlinedButton(onClick = onOpenFile, enabled = document == null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(EnderSlicerDimens.Space4))
                Text("Open CAD file")
            }
        }
        if (document == null) {
            Text(
                "Open a STEP, IGES, 3MF, OBJ or glTF file as a CAD document. It stays the " +
                    "engine's document - the plate shows its display tessellation, and the " +
                    "STL is only made when you press Slice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (document?.derivedStale == true) {
            Button(onClick = onRebuild, modifier = Modifier.fillMaxWidth()) {
                Text("Rebuild plate from the document")
            }
            Text(
                "The split or snap layout on the plate was made from the document's previous " +
                    "shape. Rebuilding takes the current shape and drops that layout; the " +
                    "document itself is untouched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        Text("Export the document", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8)) {
            listOf("step", "stl", "3mf").forEach { format ->
                OutlinedButton(
                    onClick = { onExport(format) },
                    enabled = document != null && ready,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(format.uppercase())
                }
            }
        }
        Text(
            "This writes the engine's document as a file and leaves it unchanged. Slice " +
                "exports a fresh STL only when the document has changed since the last slice; " +
                "an unchanged document reuses that STL and the status line says so.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Text("Ask the agent to change it", style = MaterialTheme.typography.titleSmall)
        if (!chatConfigured) {
            Text(
                "The AI chat is not connected. Open it from the sparkle button and connect the " +
                    "harness first; then this box sends to the same CAD conversation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            messages.takeLast(4).forEach { message ->
                Text(
                    (if (message.fromUser) "You: " else "Agent: ") + message.text.take(300),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            chatStatus?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Ask for a change") },
                    maxLines = 3,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(EnderSlicerDimens.Space4))
                Button(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            onAsk(text)
                            draft = ""
                        }
                    },
                    enabled = !chatBusy && draft.isNotBlank(),
                ) { Text(if (chatBusy) "…" else "Send") }
            }
        }
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8)) {
            OutlinedButton(
                onClick = onCloseDocument,
                enabled = document != null,
                modifier = Modifier.weight(1f),
            ) { Text("Close document") }
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Done") }
        }
        Text(
            "Closing takes the document off the plate; its design file stays in the " +
                "SolidCraft workspace. The agent sheet is the only destructive control a " +
                "spectator has.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The plate's CAD badge: what the viewport is showing and whether the agent is working.
 *
 * Small and out of the way on purpose - the document itself is the subject, and this is the
 * only sign on the plate that the plate is not a mesh. Tapping it opens the agent sheet, which
 * is where a change can be asked for; the badge itself offers no verb that edits anything.
 */
@Composable
internal fun SolidCraftPlateBadge(
    document: CadDocument,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var ready by remember { mutableStateOf(SolidCraftEngine.isReady()) }
    LaunchedEffect(Unit) {
        while (true) {
            ready = SolidCraftEngine.isReady()
            delay(500)
        }
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
        tonalElevation = 4.dp,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    AppIcons.Sparkle,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(EnderSlicerDimens.Space6))
                Text(
                    "CAD · " + document.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
            Text(
                when {
                    document.updating -> "changing…"
                    ready -> "live · agent editing"
                    else -> "agent offline · tap to connect"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (document.updating || ready) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                document.bodyCount.toString() + " bodies · " +
                    "%,d".format(document.triangleCount) + " triangles",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

