package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.solidcraft.SelectedFace

/**
 * The chip that names the face the plate is tinting, over the plate.
 *
 * It is the human's half of the selection: the face itself is amber in the renderer, and this
 * says whose face it is - body, face name and index, surface, area, and which way it faces from
 * where the user is looking - with **Clear** one tap away. It never edits anything: the plate and
 * the engine hold the selection, and the next message carries it to the agent.
 */
@Composable
internal fun FaceSelectHud(
    face: SelectedFace?,
    picking: Boolean,
    onClear: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.widthIn(max = 460.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // The same warm amber the renderer tints the face with, so the chip and the
                // picture are recognisably about the same thing.
                Text("●", color = FACE_TINT_AMBER, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (face == null) "Select a face" else "Selected face",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (face != null) {
                Text(face.hudLine(), style = MaterialTheme.typography.bodySmall)
                Text(
                    if (picking) "Tap another face to change it." else "Tap Select to pick a different face.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onClear) { Text("Clear") }
                    TextButton(onClick = onDone) { Text("Done") }
                }
            } else {
                Text(
                    "Tap a face on the plate: the plate tints it amber, the engine selects it, " +
                        "and the next message to the agent names it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

/** The selection's amber, matching the mesh shader's FACE_TINT_COLOR. */
internal val FACE_TINT_AMBER = Color(0xFFFFC733)
