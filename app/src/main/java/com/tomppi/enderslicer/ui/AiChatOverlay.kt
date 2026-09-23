package com.tomppi.enderslicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.harness.HarnessClient
import kotlin.math.roundToInt

/** One turn in the AI conversation. */
data class AiChatMessage(
    val fromUser: Boolean,
    val text: String,
)

/**
 * Address entry, shown until the harness has been reached once.
 *
 * The chat asks for this itself rather than hiding it in a settings screen,
 * because the first thing anyone does with an unconfigured chat is try to use
 * it. The address field takes the whole URL the launcher prints, launch token
 * included: that token is the only credential the app gets, and the launcher no
 * longer publishes one anywhere a passer-by could read it.
 */
data class ChatSetup(
    val address: String,
    val onAddressChange: (String) -> Unit,
    val onConnect: () -> Unit,
    /** Harness-side working directory; decides which skills the agent can see. */
    val workspace: String = "",
    val onWorkspaceChange: (String) -> Unit = {},
)

private val ExpandedWidth = 300.dp
private val ExpandedHeight = 380.dp
private val BubbleSize = 56.dp

/**
 * Floating AI chat for the Blender engine.
 *
 * Deliberately not an AppBottomSheet. The user paints a region onto the model to
 * show the AI what to work on, so the chat has to stay open and stay out of the
 * way while they do it - a bottom sheet covers the model and dismisses on the
 * first tap outside it. This floats, drags anywhere, and collapses to a bubble
 * so the plate stays usable.
 *
 * Position is intentionally not persisted: a saved offset from a different
 * window size or orientation would land off-screen, so it re-seats itself.
 */
@Composable
fun AiChatOverlay(
    messages: List<AiChatMessage>,
    onSend: (String) -> Unit,
    onUploadImage: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Stops the running turn and the work it started. Offered while [busy]. */
    onStop: () -> Unit = {},
    /** Non-null until the harness address is known; replaces the composer. */
    setup: ChatSetup? = null,
    /** True while a request is in flight, so the composer can say so. */
    busy: Boolean = false,
    /** Last transport outcome, shown under the composer. */
    status: String? = null,
    /** True while the session travels over plain HTTP to another machine. */
    insecure: Boolean = false,
) {
    var collapsed by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var placed by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val scroll = rememberScrollState()

    BoxWithConstraints(modifier = modifier) {
        val panelWidth = if (collapsed) BubbleSize else ExpandedWidth
        val panelHeight = if (collapsed) BubbleSize else ExpandedHeight
        val widthPx = with(density) { panelWidth.toPx() }
        val heightPx = with(density) { panelHeight.toPx() }
        val boundsX = (constraints.maxWidth - widthPx).coerceAtLeast(0f)
        val boundsY = (constraints.maxHeight - heightPx).coerceAtLeast(0f)

        fun clamp(candidate: Offset) = Offset(
            candidate.x.coerceIn(0f, boundsX),
            candidate.y.coerceIn(0f, boundsY),
        )

        // Seat it right of centre, clear of the top bar, once the bounds are known.
        if (!placed && boundsX > 0f) {
            LaunchedEffect(boundsX, boundsY) {
                offset = Offset(boundsX, boundsY * 0.25f)
                placed = true
            }
        }
        // A rotation can leave a stale offset outside the new bounds.
        LaunchedEffect(boundsX, boundsY) { offset = clamp(offset) }

        LaunchedEffect(messages.size) { scroll.animateScrollTo(scroll.maxValue) }

        val dragHandle = Modifier.pointerInput(boundsX, boundsY) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                offset = clamp(offset + dragAmount)
            }
        }

        if (collapsed) {
            Surface(
                onClick = { collapsed = false },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .size(BubbleSize)
                    .then(dragHandle),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = AppIcons.Sparkle,
                        contentDescription = "Open AI chat",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            return@BoxWithConstraints
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .width(panelWidth)
                .height(panelHeight),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header doubles as the drag handle.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(dragHandle)
                        .padding(
                            start = EnderSlicerDimens.CardPadding,
                            end = EnderSlicerDimens.Space4,
                            top = EnderSlicerDimens.Space6,
                            bottom = EnderSlicerDimens.Space6,
                        ),
                ) {
                    Icon(
                        imageVector = AppIcons.Sparkle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(EnderSlicerDimens.Space8))
                    Text(
                        text = "Ask AI",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { collapsed = true }) {
                        Icon(
                            imageVector = AppIcons.Minimise,
                            contentDescription = "Hide chat",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // Stays up for as long as the session does: the setup-time
                // warning is gone the moment the chat is in use, which is when
                // every prompt and photo is actually sent.
                if (insecure) {
                    Text(
                        text = "Unencrypted connection: anyone on this network can read " +
                            "this chat and the credentials it uses.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = EnderSlicerDimens.CardPadding,
                                end = EnderSlicerDimens.CardPadding,
                                top = EnderSlicerDimens.Space4,
                            ),
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(EnderSlicerDimens.CardPadding),
                ) {
                    if (setup != null) {
                        Text(
                            text = "Paste the launch URL your harness printed - the " +
                                "https://your-machine.your-tailnet.ts.net address with " +
                                "?token=... on the end. It is the only copy of that token.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = setup.address,
                            onValueChange = setup.onAddressChange,
                            label = { Text("Launch URL") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { setup.onConnect() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // Everything this chat sends - the launch token, the
                        // session cookie and any photo uploaded with a prompt -
                        // travels in the clear over plain HTTP to another machine,
                        // and the cookie is good for 30 days. The address is still
                        // accepted, because a LAN or Tailscale harness is a
                        // legitimate answer, but not without being asked.
                        if (HarnessClient.isUnencryptedRemote(setup.address)) {
                            Text(
                                "Not HTTPS: the launch token, the session cookie and " +
                                    "anything you send would travel unencrypted. You will " +
                                    "be asked to confirm before connecting.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedTextField(
                            value = setup.workspace,
                            onValueChange = setup.onWorkspaceChange,
                            label = { Text("Workspace on the harness") },
                            supportingText = {
                                Text("Where your skills live. Without it the agent " +
                                    "cannot see them, however the prompt names them.")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = setup.onConnect,
                            enabled = setup.address.isNotBlank() && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (busy) "Connecting…" else "Connect") }
                        status?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        if (messages.isEmpty()) {
                            Text(
                                text = "Paint on the model to show me what to add or change, " +
                                    "then ask. I can also build a new model from an image.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        messages.forEach { message -> AiChatBubble(message) }
                        if (busy) {
                            Text(
                                text = "Thinking…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Surfaced here as well as during setup: "still running
                        // after 20 minutes" and "stopped" are only useful if
                        // they are visible once the chat is in use.
                        status?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (setup != null) return@Column
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = EnderSlicerDimens.Space8,
                            end = EnderSlicerDimens.Space8,
                            top = EnderSlicerDimens.Space8,
                        ),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Ask about the model") },
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (draft.isNotBlank()) {
                                    onSend(draft.trim())
                                    draft = ""
                                }
                            },
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(EnderSlicerDimens.Space4))
                    FilledIconButton(
                        onClick = {
                            if (draft.isNotBlank()) {
                                onSend(draft.trim())
                                draft = ""
                            }
                        },
                        enabled = draft.isNotBlank(),
                    ) {
                        Icon(AppIcons.Send, contentDescription = "Send")
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = EnderSlicerDimens.Space8,
                            end = EnderSlicerDimens.Space8,
                            bottom = EnderSlicerDimens.Space8,
                        ),
                ) {
                    TextButton(
                        onClick = onUploadImage,
                        contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                    ) {
                        Icon(
                            imageVector = AppIcons.ImageUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(EnderSlicerDimens.Space6))
                        Text("Build from image")
                    }
                    // Only while something is running. Cancelling stops the
                    // agent, not the processes it started, so this is the one
                    // control that ends work in progress.
                    if (busy) {
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = onStop,
                            contentPadding = PaddingValues(horizontal = EnderSlicerDimens.Space8),
                        ) { Text("Stop") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiChatBubble(message: AiChatMessage) {
    val alignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    val container = if (message.fromUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = if (message.fromUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = container,
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer,
                modifier = Modifier.padding(EnderSlicerDimens.Space8),
            )
        }
    }
}
