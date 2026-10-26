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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.agent.AgentBridgeCopy
import com.tomppi.enderslicer.agent.AgentBridgeLine
import com.tomppi.enderslicer.agent.AgentBridges
import com.tomppi.enderslicer.agent.AgentBridgeTarget
import com.tomppi.enderslicer.agent.AgentBridgeTokens
import com.tomppi.enderslicer.agent.BridgeLinkState
import com.tomppi.enderslicer.agent.BridgeNetworkPreference
import com.tomppi.enderslicer.cadagent.AgentMode
import com.tomppi.enderslicer.cadagent.AgentSettings
import com.tomppi.enderslicer.solidcraft.CadDocument
import com.tomppi.enderslicer.solidcraft.SolidCraftAuthorship
import com.tomppi.enderslicer.solidcraft.SolidCraftEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    /**
     * Which agent drives the document, and - for the in-app one - the endpoint it calls.
     *
     * The API key is not in here: [AgentSettings] has no field for it, so nothing this sheet
     * composes can render or log the credential. [AgentSettings.keyMask] is the only thing about
     * it that reaches the screen.
     */
    agentSettings: AgentSettings,
    /** False when the harness has not been reached, so the harness path cannot send yet. */
    chatConfigured: Boolean,
    chatBusy: Boolean,
    chatStatus: String?,
    /** Switches the author between this phone's own agent and the harness on a computer. */
    onAgentModeChange: (AgentMode) -> Unit,
    /**
     * Saves the in-app endpoint. [apiKey] null keeps the stored key, which is what lets the
     * field stay empty and the saved key stay unreadable.
     */
    onAgentSettingsSave: (baseUrl: String, model: String, apiKey: String?) -> Unit,
    onAgentKeyClear: () -> Unit,
    /** Switches the in-app agent's web search and page fetch on or off. */
    onAgentWebSearchChange: (Boolean) -> Unit,
    /**
     * The one-author rule, polled here so the sheet always names who is driving and pauses the
     * in-app composer while an external agent is attached.
     */
    authorship: SolidCraftAuthorship,
    /** The **Take over** confirmation: closes the live external connections. */
    onTakeOver: () -> Unit,
    /**
     * One sentence about the endpoint's web routes once the agent has learned them, or null
     * while they are untested/available. Shown so a missing search is named, never silent.
     */
    inAppWebNotice: String?,
    /** Cancels the in-app agent's current turn (the harness path has its own Stop). */
    onCancelAgent: () -> Unit,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onCloseDocument: () -> Unit,
    /** Re-derives the plate from the document's current shape (the way out of a stale plate). */
    onRebuild: () -> Unit,
    onCopyToken: () -> Unit,
    /** Turns the network bind on or off; the setting is persisted by the engine's owner. */
    onNetworkReachableChange: (Boolean) -> Unit,
    /**
     * Copies one row's ready-to-paste line (address AND that engine's token). The token read and
     * the clipboard write sit behind the sheet's biometric gate in the caller; the token is
     * never a value this sheet renders.
     */
    onCopyConnectLine: (AgentBridgeTarget, String) -> Unit,
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
    // Who is allowed to edit the document right now. The rule lives in SolidCraftAuthorship;
    // the sheet only renders the snapshot, so the screen can never disagree with the guard.
    var authorState by remember { mutableStateOf(authorship.state()) }
    var confirmTakeOver by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            engineStatus = SolidCraftEngine.status(context)
            ready = SolidCraftEngine.isReady()
            starting = SolidCraftEngine.isStarting()
            authorState = authorship.state()
            delay(400)
        }
    }
    // The app's three agent bridges, published together by the switch. Off is the loopback
    // reach they have always had; on republishes Blender (9876), the Python CAD engine (9877)
    // and SolidCraft (9878) on this device's network address. The rows are read from the app's
    // publisher, so their state is the sockets' real state, not what the switch asked for.
    var networkEnabled by remember { mutableStateOf(BridgeNetworkPreference.isEnabled(context)) }
    var bridgeLines by remember { mutableStateOf(AgentBridges.lines()) }
    // Which engines have written their token file yet. A row may only copy a line that carries
    // a token, so an engine that has never started offers no Copy and says why. This is the
    // only token state the sheet holds: the token itself is read after the fingerprint prompt,
    // on the copy path, and never enters composition or the rendered model.
    var tokenPorts by remember { mutableStateOf(emptySet<Int>()) }
    LaunchedEffect(networkEnabled) {
        if (!networkEnabled) {
            bridgeLines = AgentBridges.lines()
            tokenPorts = withContext(Dispatchers.IO) { AgentBridgeTokens.available(context) }
            return@LaunchedEffect
        }
        while (true) {
            // Cheap: the publisher follows the device's address on its own and keeps the
            // state; this only copies the current rows and token availability for the sheet.
            val snapshot = withContext(Dispatchers.IO) {
                AgentBridges.lines() to AgentBridgeTokens.available(context)
            }
            bridgeLines = snapshot.first
            tokenPorts = snapshot.second
            delay(1_000)
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
        // How an agent reaches this app's engines. Blender's MCP addon, the Python CAD engine
        // and SolidCraft all bind 127.0.0.1; this switch has the app republish all three on
        // the device's network address at their own ports, so a laptop on the same network has
        // a line to paste and needs nothing else but the token for the engine it drives.
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(EnderSlicerDimens.Space8)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Reachable on the network", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (networkEnabled) "On · this device's address" else "Off · this device only",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = networkEnabled,
                        onCheckedChange = { wanted ->
                            networkEnabled = wanted
                            onNetworkReachableChange(wanted)
                        },
                    )
                }
                if (!networkEnabled) {
                    Text(
                        "Off - this device only. The Blender MCP addon (9876), the CAD engine " +
                            "(9877) and SolidCraft (9878) listen on 127.0.0.1 inside this phone; " +
                            "another device reaches them only through an adb forward or a tailnet " +
                            "serve rule. Copy the token for the engine you are driving and give it " +
                            "to the agent together with the skill.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val noAddress = bridgeLines.firstOrNull { it.state == BridgeLinkState.NO_ADDRESS }
                    if (noAddress != null) {
                        Text(
                            noAddress.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        bridgeLines.forEach { line ->
                            BridgeLineRow(
                                line = line,
                                tokenReady = line.target.port in tokenPorts,
                                onCopy = onCopyConnectLine,
                            )
                        }
                    }
                    Text(
                        "Turning this on publishes Blender's, the CAD engine's and SolidCraft's " +
                            "MCP ports on this device's network address. Any device that can reach " +
                            "an address and has that engine's token can drive it: every engine's " +
                            "own token check is unchanged, the forwarder adds no authentication of " +
                            "its own and never logs message content.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Copy puts ONE line on the clipboard - the address and that engine's own " +
                            "token - so one fingerprint prompt and one paste are enough. The token " +
                            "is never shown on this screen, and a row says so when its engine has " +
                            "not been started yet and the token does not exist.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Turning it off closes every published listener again; the engines keep " +
                            "running.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // The token-only copy is the fallback hand-over: it is the only way to copy a token
        // when no published row can offer the one-line form - the switch is off, or there is
        // no network address. While a row IS copying address + token together, this button
        // would be a second, weaker path to the same credential, so it is hidden then.
        val rowsCarryToken = networkEnabled && bridgeLines.any { line ->
            line.line != null && line.address != null && line.target.port in tokenPorts
        }
        Row(horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8)) {
            if (!rowsCarryToken) {
                OutlinedButton(onClick = onCopyToken, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(EnderSlicerDimens.Space4))
                    Text("Copy MCP token")
                }
            }
            OutlinedButton(
                onClick = onOpenFile,
                enabled = document == null,
                modifier = if (rowsCarryToken) Modifier.fillMaxWidth() else Modifier.weight(1f),
            ) {
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
        // Which agent authors the document, said plainly. In-app is the default: the model
        // endpoint is called straight from this phone, so nothing has to be running on a desk.
        // The harness stays as the advanced path - an agent on a computer that reaches this
        // phone's engine bridge.
        Row(horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8)) {
            AgentMode.entries.forEach { mode ->
                FilterChip(
                    selected = agentSettings.mode == mode,
                    onClick = { onAgentModeChange(mode) },
                    label = { Text(mode.label) },
                )
            }
        }
        Text(
            agentSettings.drivingSentence(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The one line that is always true, in every state: who is the document's author.
        Text(
            authorState.authorLine,
            style = MaterialTheme.typography.labelSmall,
            color = if (authorState.inAppPaused) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (agentSettings.mode == AgentMode.IN_APP) {
            InAppAgentSettings(
                settings = agentSettings,
                webNotice = inAppWebNotice,
                onSave = onAgentSettingsSave,
                onClearKey = onAgentKeyClear,
                onWebSearchChange = onAgentWebSearchChange,
            )
        }
        // An external agent is attached to this document: the in-app agent is paused, and Take
        // over is the one action offered. It closes the external connection, and the confirmation
        // says the outbound agent has to reconnect.
        val pausedByExternal = agentSettings.mode == AgentMode.IN_APP && authorState.inAppPaused
        if (pausedByExternal) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(EnderSlicerDimens.Space8)) {
                    Text(authorState.pausedLine, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Only one agent may author the document. The external agent is connected " +
                            "to the engine bridge; take the document back to ask the in-app agent " +
                            "for a change.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { confirmTakeOver = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Take over") }
                }
            }
        }
        // Both agents write the same transcript, so the conversation the user sees does not
        // change with the choice of author.
        // Tool rows belong to the floating chat's transcript; this compact box shows the prose
        // exchanges only, so it keeps reading as a conversation.
        val prose = messages.filter { it.tool == null }
        if (prose.isNotEmpty()) {
            prose.takeLast(6).forEach { message ->
                Text(
                    (if (message.fromUser) "You: " else "Agent: ") + message.text.take(400),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        chatStatus?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        val canSend = when {
            chatBusy -> false
            // The in-app composer is disabled while an external agent is attached: the paused
            // block above is the only action then, and the authorship object refuses a turn
            // even if a stale screen tried to send one.
            agentSettings.mode == AgentMode.IN_APP -> agentSettings.isConfigured && authorState.canInAppEdit
            else -> chatConfigured
        }
        if (!chatBusy && !canSend && !pausedByExternal) {
            Text(
                if (agentSettings.mode == AgentMode.IN_APP) {
                    "Set the endpoint above and this box talks to it directly - the phone is " +
                        "the whole agent. The key is sealed in the device keystore and is never " +
                        "shown again."
                } else {
                    "The harness is not connected. Open it from the sparkle button and connect " +
                        "it first; this box then sends to the same CAD conversation."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if ((canSend || chatBusy) && !pausedByExternal) {
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Ask for a change") },
                    maxLines = 3,
                    enabled = !chatBusy,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(EnderSlicerDimens.Space4))
                if (chatBusy) {
                    OutlinedButton(onClick = onCancelAgent) { Text("Cancel") }
                    Spacer(Modifier.width(EnderSlicerDimens.Space4))
                }
                Button(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            onAsk(text)
                            draft = ""
                        }
                    },
                    enabled = !chatBusy && draft.isNotBlank(),
                ) { Text(if (chatBusy) "Working…" else "Send") }
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
    // Take over is destructive to the external agent's session, not to the document: its
    // connection ends and it has to reconnect before it can edit again.
    if (confirmTakeOver) {
        AlertDialog(
            onDismissRequest = { confirmTakeOver = false },
            title = { Text("Take the document back?") },
            text = {
                Text(
                    "This closes the external agent's connection to this document. The outbound " +
                        "agent has to reconnect before it can edit again; the in-app agent can " +
                        "be used immediately.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmTakeOver = false
                        onTakeOver()
                    },
                ) { Text("Take over") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTakeOver = false }) { Text("Cancel") }
            },
        )
    }
}


/**
 * The in-app agent's endpoint: base URL, model, the one secret and the web-tools switch.
 *
 * The key field is never bound to the stored key - it only ever holds what the user is typing
 * now - and the saved key is reported only as [AgentSettings.keyMask]. Saving with the field
 * empty keeps the stored key, which is what makes "replace" possible without ever reading the
 * old one back into a text field.
 *
 * The web switch is here because search and fetch are not the engine's tools: they call the same
 * endpoint with the same credential, and this is where the user decides whether the agent may
 * leave the phone for the web at all.
 */
@Composable
private fun InAppAgentSettings(
    settings: AgentSettings,
    webNotice: String?,
    onSave: (baseUrl: String, model: String, apiKey: String?) -> Unit,
    onClearKey: () -> Unit,
    onWebSearchChange: (Boolean) -> Unit,
) {
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var model by remember { mutableStateOf(settings.model) }
    // Never the stored value: this holds only what the user is typing in this session.
    var key by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4),
    ) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            placeholder = { Text("https://api.openai.com/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            placeholder = { Text("gpt-4o-mini") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = {
                Text(
                    if (settings.hasApiKey) "API key (saved - type a new one to replace)"
                    else "API key (optional if the endpoint needs none)",
                )
            },
            placeholder = { Text(if (settings.hasApiKey) settings.keyMask else "sk-…") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8)) {
            Button(
                onClick = {
                    onSave(baseUrl, model, key.trim().takeIf { it.isNotEmpty() })
                    key = ""
                },
                enabled = baseUrl.isNotBlank() && model.isNotBlank(),
            ) { Text("Save endpoint") }
            if (settings.hasApiKey) {
                TextButton(onClick = onClearKey) { Text("Clear key") }
            }
        }
        Text(
            "Key: " + settings.keyMask + " · sealed with this device's keystore, never shown " +
                "again, never logged and never part of the chat transcript.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Web search & page fetch", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (settings.webSearch) {
                        "On · the agent can search and read pages through this endpoint, with " +
                            "this same key; their text arrives marked as untrusted data."
                    } else {
                        "Off · the agent stays on the phone and builds from your own words."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.webSearch, onCheckedChange = onWebSearchChange)
        }
        webNotice?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        if (settings.isCleartextRemote) {
            Text(
                "This is plain http to another machine: the key and every prompt travel " +
                    "unencrypted. Use https unless this is your own network.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * One published bridge in the sheet: its name and port, the line to paste, and the state of
 * the socket behind it - "app bridge up" and "engine not running" are different problems.
 *
 * Copy is offered only once that engine has written its token, because the line it puts on the
 * clipboard carries the token too (after the fingerprint prompt) - never a line without one.
 */
@Composable
private fun BridgeLineRow(
    line: AgentBridgeLine,
    tokenReady: Boolean,
    onCopy: (AgentBridgeTarget, String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = EnderSlicerDimens.Space8, top = EnderSlicerDimens.Space4, bottom = EnderSlicerDimens.Space4),
            ) {
                Text(
                    line.target.name + " · " + line.target.port,
                    style = MaterialTheme.typography.labelLarge,
                )
                line.line?.let { address ->
                    Text(
                        address,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    if (tokenReady) line.status else AgentBridgeCopy.MISSING_TOKEN_REASON,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        !tokenReady -> MaterialTheme.colorScheme.onSurfaceVariant
                        line.state == BridgeLinkState.UP -> MaterialTheme.colorScheme.primary
                        line.state == BridgeLinkState.ENGINE_NOT_RUNNING ->
                            MaterialTheme.colorScheme.onSurfaceVariant
                        line.state == BridgeLinkState.NO_ADDRESS ||
                            line.state == BridgeLinkState.REBINDING -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            val address = line.address
            if (address != null && line.line != null) {
                TextButton(onClick = { onCopy(line.target, address) }, enabled = tokenReady) {
                    Text("Copy")
                }
            }
        }
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

