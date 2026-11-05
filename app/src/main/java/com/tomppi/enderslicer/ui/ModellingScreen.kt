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

/**
 * Modelling from scratch: one model, one camera.
 *
 * The one chat is the floating [AiChatOverlay]: this screen gives the room to
 * the model and its camera, and the screen's Ask AI button opens that window over
 * it. One composer and one conversation for the whole app - the engine menu in
 * the window selects this engine.
 *
 * The view is [ModellingPreview] - the engine's own render - rather than the
 * app's GL viewport. One camera, no translation, and the user sees exactly what
 * the agent sees.
 */
@Composable
fun ModellingScreen(
    state: MainUiState,
    owner: CameraOwner,
    /** False while the agent is working: the camera is the agent's until it stops. */
    canTakeCamera: Boolean,
    /** Whether an agent is actually connected, for the caption under the view. */
    agentConnected: Boolean,
    blenderDir: File,
    /** Opens the floating Ask AI chat - the one chat surface. */
    onOpenChat: () -> Unit = {},
    onExit: () -> Unit,
    onTakeCamera: () -> Unit,
    onHandBackCamera: () -> Unit,
    onCameraMoved: (ModellingCamera) -> Unit,
    incomingCamera: ModellingCamera?,
    modifier: Modifier = Modifier,
) {
    // The engine's own scene, not the app's plate. The two can hold different
    // models - the app has whatever came out, the engine whatever it was given -
    // so the bar names the one actually on screen here.
    var scene by remember { mutableStateOf<SceneSummary?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
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
                TextButton(onClick = onOpenChat) {
                    Text("Ask AI")
                }
            }
        }

        ModellingPreview(
            blenderDir = blenderDir,
            initialCamera = incomingCamera,
            interactive = owner == CameraOwner.USER,
            agentConnected = agentConnected,

            onScene = { scene = it },
            onCameraChanged = onCameraMoved,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

    }
    }
}
