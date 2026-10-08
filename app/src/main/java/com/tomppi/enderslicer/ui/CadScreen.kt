package com.tomppi.enderslicer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import com.tomppi.enderslicer.modelling.CadPick
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
     * The engine's most recent frame, or null before the first one arrives.
     *
     * This is the engine's own viewport: its camera, its scene, its render. The app does not
     * draw the model at all, which is what makes this one view rather than two - there is no
     * second camera to keep in step and no coordinate frame to translate a tap through.
     */
    frame: Bitmap?,
    /**
     * The most recent model the engine exported, when it exported an STL.
     *
     * A render is a picture of the part from one angle, and the engine chose that
     * angle. The mesh is the part itself: the user can turn it, and see the side the
     * render did not show - which is the side they are usually asking about.
     */
    /** The last surface the user picked, so the screen can say what is armed. */
    pick: CadPick?,
    /** True while the engine is rendering a frame, so the view can show it is working. */
    framePending: Boolean,
    onOrbit: (Float, Float) -> Unit,
    onPan: (Float, Float) -> Unit,
    onZoom: (Float) -> Unit,
    onSelect: (Int, Int) -> Unit,
    onViewSize: (Int, Int) -> Unit,
    onResetView: () -> Unit,
    onPickUsed: () -> Unit,
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
                TextButton(onClick = onResetView) {
                    Text("Reset view")
                }
                TextButton(onClick = { chatExpanded = !chatExpanded }) {
                    Text(if (chatExpanded) "Hide chat" else "Chat")
                }
            }
        }

        if (pick != null || framePending) {
            Surface(tonalElevation = 2.dp) {
                Text(
                    text = when {
                        pick?.isSomething == true && pick.kind == "face" ->
                            "Picked face #%d (%.1f mm2) - say what to do with it."
                                .format(pick.index, pick.areaMm2 ?: 0.0)
                        pick?.isSomething == true ->
                            "Picked %s #%d - say what to do with it.".format(pick.kind, pick.index)
                        pick != null -> "Nothing under that tap. Try again, or turn the part."
                        else -> "Rendering..."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
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
            // One view: the engine's own. The frame is asked for at exactly this size, so
            // what the user taps is the pixel the engine picked at - letterboxing a frame
            // rendered at another size would name the wrong face.
            val shown = frame
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = "The CAD engine's view of the model",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { onViewSize(it.width, it.height) }
                        // A tap is a pick, not a mode: there is nothing else a tap could
                        // sensibly mean on a view the engine owns.
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                onSelect(offset.x.toInt(), offset.y.toInt())
                            }
                        }
                        // One finger orbits, two pinch and pan - the gestures the plate and
                        // the Blender preview already taught, so there is nothing to learn.
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (zoom != 1f) onZoom(zoom)
                                if (pan.x != 0f || pan.y != 0f) onOrbit(pan.x, pan.y)
                            }
                        },
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
