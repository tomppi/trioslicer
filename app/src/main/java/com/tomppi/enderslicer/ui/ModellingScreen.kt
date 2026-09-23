package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.modelling.CameraOwner
import com.tomppi.enderslicer.modelling.ModellingCamera
import com.tomppi.enderslicer.modelling.SceneSummary
import java.io.File

/**
 * The chat takes a little under half the screen.
 *
 * It was a fixed 260 dp strip, which is shorter than one of this agent's answers:
 * the replies here run to several paragraphs, so most of one sat below the fold
 * in a window with room to spare. A share of the height rather than a constant
 * keeps that true on a phone and on an unfolded foldable alike.
 */
private const val ChatHeightFraction = 0.46f
private val ChatHeightMin = 240.dp
private val ChatHeightMax = 560.dp
private val BarPadding = 10.dp

/**
 * Modelling from scratch: one model, one conversation, one camera.
 *
 * Deliberately not the floating [AiChatOverlay]. That one has to stay out of the
 * way because the user paints on the model to aim the agent; here the agent and
 * the user look at the same object through the same camera, so the model gets
 * the room and the chat sits under it.
 *
 * The view is [ModellingPreview] - the engine's own render - rather than the
 * app's GL viewport. One camera, no translation, and the user sees exactly what
 * the agent sees.
 */
@Composable
fun ModellingScreen(
    state: MainUiState,
    messages: List<AiChatMessage>,
    busy: Boolean,
    status: String?,
    owner: CameraOwner,
    /** False while the agent is working: the camera is the agent's until it stops. */
    canTakeCamera: Boolean,
    blenderDir: File,
    onSend: (String) -> Unit,
    onExit: () -> Unit,
    onTakeCamera: () -> Unit,
    onHandBackCamera: () -> Unit,
    onCameraMoved: (ModellingCamera) -> Unit,
    incomingCamera: ModellingCamera?,
    modifier: Modifier = Modifier,
) {
    // Collapsible: the model is the point of the screen, and half of it is a lot
    // to give up when you are only looking.
    var chatExpanded by rememberSaveable { mutableStateOf(true) }
    // The engine's own scene, not the app's plate. The two can hold different
    // models - the app has whatever came out, the engine whatever it was given -
    // so the bar names the one actually on screen here.
    var scene by remember { mutableStateOf<SceneSummary?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val chatHeight = if (chatExpanded) {
        (maxHeight * ChatHeightFraction).coerceIn(ChatHeightMin, ChatHeightMax)
    } else {
        0.dp
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(tonalElevation = 3.dp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                TextButton(onClick = onExit) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Exit")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = scene?.label ?: "Modelling",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { if (owner == CameraOwner.USER) onHandBackCamera() else onTakeCamera() },
                    enabled = owner == CameraOwner.USER || canTakeCamera,
                ) {
                    Text(if (owner == CameraOwner.USER) "Hand back" else "Take camera")
                }
                TextButton(onClick = { chatExpanded = !chatExpanded }) {
                    Text(if (chatExpanded) "Hide chat" else "Chat")
                }
            }
        }

        ModellingPreview(
            blenderDir = blenderDir,
            initialCamera = incomingCamera,
            interactive = owner == CameraOwner.USER,

            onScene = { scene = it },
            onCameraChanged = onCameraMoved,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        if (chatExpanded) Surface(tonalElevation = 6.dp) {
            Column(modifier = Modifier.fillMaxWidth().height(chatHeight)) {
                HorizontalDivider()
                if (owner == CameraOwner.USER) {
                    Text(
                        text = "You have the camera. Send a message to hand it back.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(BarPadding),
                    )
                }
                ChatTranscript(
                    messages = messages,
                    busy = busy,
                    status = status,
                    onSend = onSend,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    }
}

@Composable
private fun ChatTranscript(
    messages: List<AiChatMessage>,
    busy: Boolean,
    status: String?,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()

    LaunchedEffect(messages.size) { scroll.animateScrollTo(scroll.maxValue) }

    Column(modifier = modifier.padding(horizontal = BarPadding)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(vertical = BarPadding),
        ) {
            if (messages.isEmpty()) {
                Text(
                    text = "Describe the part you want, or just say hello. " +
                        "The agent works on the model you can see.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            messages.forEach { message ->
                Text(
                    text = message.text,
                    // bodyMedium, not bodySmall: these replies run to several
                    // paragraphs and bodySmall made them a wall of small print
                    // in a window that has the room.
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = if (message.fromUser) TextAlign.End else TextAlign.Start,
                    color = if (message.fromUser) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = BarPadding),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(if (busy) "Working…" else "Tell the agent what to change") },
                singleLine = false,
                maxLines = 3,
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        onSend(text)
                    }
                }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        onSend(text)
                    }
                },
                enabled = draft.isNotBlank(),
            ) {
                Text("Send")
            }
        }
    }
}
