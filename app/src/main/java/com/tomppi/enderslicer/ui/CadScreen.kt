package com.tomppi.enderslicer.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Modelling with the CAD engine: exact geometry, real dimensions, STEP in and out.
 *
 * A separate screen from [ModellingScreen] rather than a mode of it. That one is built
 * around a camera the user and the agent share, which only Blender can supply - its engine
 * renders, and what is on screen is that render. The CAD engine produces geometry and
 * files rather than pixels, so there is no camera to hand over and none of that machinery
 * applies here.
 *
 * What the screen does carry over is the shape of the conversation: one model, one
 * conversation, the chat under the view rather than floating over it.
 */
@Composable
fun CadScreen(
    messages: List<AiChatMessage>,
    busy: Boolean,
    status: String?,
    /** Whether an agent is actually connected, for the caption under the view. */
    agentConnected: Boolean,
    /** What the engine itself is doing - starting, ready, or why it is not. */
    engineStatus: String,
    /** Where finished STEP and STL land, shown so the user knows where to look. */
    exportsPath: String,
    /**
     * The engine's most recent render, or null before it has drawn one.
     *
     * This is the engine's own picture of its own scene, the way the modelling screen shows
     * Blender's - the difference is only that this engine has no window and renders
     * offscreen, so the picture arrives as a file rather than as a texture.
     */
    render: File?,
    onSend: (String) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chatExpanded by rememberSaveable { mutableStateOf(true) }

    Column(modifier = modifier.fillMaxSize()) {
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
                    text = "CAD",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { chatExpanded = !chatExpanded }) {
                    Text(if (chatExpanded) "Hide chat" else "Chat")
                }
            }
        }

        // The view. Until the engine renders, this says what is true rather than showing
        // an empty box: whether the engine is up, and where the parts it writes will land.
        Surface(
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val bitmap = remember(render?.absolutePath) {
                render?.let { file ->
                    runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "The CAD engine's render of the model",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                )
            } else Box(contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        text = engineStatus,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = if (agentConnected) {
                            "An agent is connected. Ask for a part and it is built here."
                        } else {
                            "No agent is connected yet."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "Exports land in\n$exportsPath",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (chatExpanded) {
            Surface(tonalElevation = 6.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                ) {
                    HorizontalDivider()
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
