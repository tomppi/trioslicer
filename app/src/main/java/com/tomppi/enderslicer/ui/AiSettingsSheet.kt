package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.cadagent.AgentMode
import com.tomppi.enderslicer.cadagent.AgentSettings

/**
 * AI settings: the agent surface's own configuration, out of the CAD document sheet.
 *
 * The in-app agent's endpoint, model, credential and web tools are app-wide now - the same
 * agent drives SolidCraft, the CAD engine, Blender and ScanRuler - so they live where app
 * settings live (More -> Configuration -> AI settings) rather than inside one engine's menu.
 *
 * The harness connection is here for the same reason, and deliberately always editable: the
 * connect form used to exist only while no harness had been reached, which left a connected
 * user no way to paste a new launch URL. The fields prefill from the stored configuration and
 * Connect replaces it.
 */
@Composable
internal fun AiSettingsSheet(
    agentSettings: AgentSettings,
    onAgentModeChange: (AgentMode) -> Unit,
    onAgentSettingsSave: (baseUrl: String, model: String, apiKey: String?) -> Unit,
    onAgentKeyClear: () -> Unit,
    onAgentWebSearchChange: (Boolean) -> Unit,
    onAgentLendViewChange: (Boolean) -> Unit,
    inAppWebNotice: String?,
    authorLine: String,
    harnessAddress: String,
    onHarnessAddressChange: (String) -> Unit,
    harnessWorkspace: String,
    onHarnessWorkspaceChange: (String) -> Unit,
    harnessConnected: Boolean,
    harnessBusy: Boolean,
    harnessInsecure: Boolean,
    harnessStatus: String?,
    onConnectHarness: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EnderSlicerDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(EnderSlicerDimens.Space8))
            Column(modifier = Modifier.weight(1f)) {
                Text("AI settings", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The app's agents - in-app endpoint and the harness on a computer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDismiss) { Text("Done") }
        }
        HorizontalDivider()

        Text("Agent", style = MaterialTheme.typography.titleSmall)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(EnderSlicerDimens.Space8),
                verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
            ) {
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
                if (authorLine.isNotBlank()) {
                    Text(
                        authorLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (agentSettings.mode == AgentMode.IN_APP) {
                    InAppAgentSettings(
                        settings = agentSettings,
                        webNotice = inAppWebNotice,
                        onSave = onAgentSettingsSave,
                        onClearKey = onAgentKeyClear,
                        onWebSearchChange = onAgentWebSearchChange,
                        onLendViewChange = onAgentLendViewChange,
                    )
                }
            }
        }

        Text("Harness - the agent on a computer", style = MaterialTheme.typography.titleSmall)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(EnderSlicerDimens.Space8),
                verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
            ) {
                Text(
                    if (harnessConnected) "Connected - paste a new launch URL to replace it."
                    else "Not connected - paste the launch URL the harness printed with ?token=...",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (harnessConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                OutlinedTextField(
                    value = harnessAddress,
                    onValueChange = onHarnessAddressChange,
                    label = { Text("Launch URL") },
                    placeholder = { Text("https://your-machine.tailnet.ts.net:3080/?token=...") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = harnessWorkspace,
                    onValueChange = onHarnessWorkspaceChange,
                    label = { Text("Workspace on the harness") },
                    placeholder = { Text("/root/src/enderslicercura") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8)) {
                    OutlinedButton(
                        onClick = onConnectHarness,
                        enabled = !harnessBusy && harnessAddress.isNotBlank(),
                    ) { Text(if (harnessConnected) "Reconnect" else "Connect") }
                }
                Text(
                    "The workspace is where the harness finds its skills (occt-engine-rules, " +
                        "cad-engine, ...). The harness agent runs on the computer and reaches " +
                        "this phone's engines through the MCP door.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (harnessInsecure) {
                    Text(
                        "Unencrypted connection: anyone on this network can read this chat and " +
                            "the credential it uses. Use the harness's https address.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                harnessStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Text(
            "The MCP door's own settings - connect line, token and Reachable on the network - " +
                "live in Ask AI -> MCP.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(EnderSlicerDimens.Space8))
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
    onLendViewChange: (Boolean) -> Unit,
) {
    // Unsaved endpoint edits survive a rotation, so typing a base URL and turning the phone
    // does not throw the field away.
    var baseUrl by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var model by rememberSaveable { mutableStateOf(settings.model) }
    // Never the stored value: this holds only what the user is typing in this session, and it is
    // deliberately NOT rememberSaveable - a typed credential must not be handed to the saved
    // instance state (which Android may write to disk for a process death).
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
            placeholder = { Text(if (settings.hasApiKey) settings.keyMask else "sk-...") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8)) {
            OutlinedButton(
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
            "Sealed in the device keystore - never shown, never logged.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Web search & page fetch", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (settings.webSearch) {
                        "The agent may search and read pages through this endpoint."
                    } else {
                        "The agent stays on this phone's tools."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.webSearch,
                onCheckedChange = onWebSearchChange,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Lend the agent my view", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Attach a plate render to a message that carries a face selection.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.lendView,
                onCheckedChange = onLendViewChange,
            )
        }
        if (settings.isCleartextRemote) {
            Text(
                "Plain http: the key and prompts travel unencrypted - use https.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        webNotice?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
