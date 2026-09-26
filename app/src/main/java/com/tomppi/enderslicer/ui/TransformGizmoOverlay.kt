package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.viewer.TransformGizmoMode
import kotlin.math.roundToInt

/** How the scale slider steps, and its range. */
private const val SCALE_STEP_PERCENT = 5
private const val SCALE_MIN_PERCENT = 10
private const val SCALE_MAX_PERCENT = 300
private const val SCALE_DEFAULT_PERCENT = 100

/**
 * The three transform modes, floating where the finger asked for them.
 *
 * Long-pressing the model puts this up: Rotate, Move and Scale, with the value
 * the current drag is producing and a slider for the one transform that has no
 * natural gesture. It stays small and out of the way, because the thing being
 * transformed is underneath it.
 */
@Composable
internal fun TransformGizmoOverlay(
    mode: TransformGizmoMode,
    readout: String?,
    scalePercent: Int,
    onMode: (TransformGizmoMode) -> Unit,
    onScalePercent: (Int) -> Unit,
    onScaleFinished: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.widthIn(min = 250.dp, max = 320.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeButton("Rotate", mode == TransformGizmoMode.ROTATE) { onMode(TransformGizmoMode.ROTATE) }
                ModeButton("Move", mode == TransformGizmoMode.MOVE) { onMode(TransformGizmoMode.MOVE) }
                ModeButton("Scale", mode == TransformGizmoMode.SCALE) { onMode(TransformGizmoMode.SCALE) }
                TextButton(onClick = onDone) { Text("Done") }
            }

            Text(
                text = readout ?: hintFor(mode),
                style = MaterialTheme.typography.labelSmall,
                color = if (readout != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            if (mode == TransformGizmoMode.SCALE) {
                // The slider snaps; the field is for a percentage someone already
                // knows. They drive the same value, so neither can disagree.
                var scaleText by rememberSaveable { mutableStateOf(scalePercent.toString()) }
                val typed = scaleText.toIntOrNull()
                val typedInRange = typed != null && typed in SCALE_MIN_PERCENT..SCALE_MAX_PERCENT
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = scaleText,
                        onValueChange = { text ->
                            scaleText = text.filter(Char::isDigit).take(3)
                            scaleText.toIntOrNull()
                                ?.takeIf { it in SCALE_MIN_PERCENT..SCALE_MAX_PERCENT }
                                ?.let(onScalePercent)
                        },
                        singleLine = true,
                        suffix = { Text("%") },
                        isError = !typedInRange,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (typedInRange) {
                                    onScalePercent(typed)
                                    onScaleFinished()
                                    scaleText = SCALE_DEFAULT_PERCENT.toString()
                                }
                            },
                        ),
                        modifier = Modifier.width(104.dp),
                    )
                    Slider(
                        value = scalePercent.toFloat(),
                        onValueChange = { value ->
                            val rounded = value.roundToInt()
                            onScalePercent(rounded)
                            scaleText = rounded.toString()
                        },
                        onValueChangeFinished = onScaleFinished,
                        valueRange = SCALE_MIN_PERCENT.toFloat()..SCALE_MAX_PERCENT.toFloat(),
                        // Snaps: a percentage the user can read back, not a float.
                        steps = (SCALE_MAX_PERCENT - SCALE_MIN_PERCENT) / SCALE_STEP_PERCENT - 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = "Snaps in " + SCALE_STEP_PERCENT + "% steps, about the model's base. " +
                        "Type a percentage or slide it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

private fun hintFor(mode: TransformGizmoMode): String = when (mode) {
    TransformGizmoMode.ROTATE ->
        "Drag sideways to turn the model, up and down to tip it. Snaps to 15 degrees."
    TransformGizmoMode.MOVE ->
        "Drag the model across the plate. Two fingers still orbit and zoom."
    TransformGizmoMode.SCALE ->
        "Slide to resize the model."
    TransformGizmoMode.NONE -> ""
}
