package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The interface scale, live. Every change is previewed at once, so the app
 * behind this sheet redraws as the thumb moves.
 *
 * The control deliberately does NOT scale with the app. A track that grows as
 * the value rises moves the value under the finger that raised it: dragging
 * right would widen the range beneath the thumb and run away. So the sheet
 * re-provides the density the DEVICE reports, which the app's own scale never
 * touches - see [UiScale.scaled].
 */
@Composable
internal fun UiScaleSheet(
    currentPercent: Int,
    onChange: (percent: Int, commit: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val deviceDensity = remember(context, configuration) {
        Density(context.resources.displayMetrics.density, configuration.fontScale)
    }
    CompositionLocalProvider(LocalDensity provides deviceDensity) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Interface scale", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Draws the whole app - text, controls, spacing and icons - at this size, applied " +
                    "when you let go. Android's own Display size does the same thing system-wide.",
                style = MaterialTheme.typography.bodyMedium,
            )
            UiScaleControl(currentPercent = currentPercent, onChange = onChange)
        }
    }
}

/**
 * The scale's slider, readout and reset without the copy around them: the
 * Settings sheet explains the control, and the first-run scale step sets it
 * against the plate itself. Both drive this one.
 *
 * [live] applies each drag frame to the app behind instead of waiting for the
 * thumb to be released. A live caller must pin this control to the device
 * density, as [UiScaleSheet] and the onboarding step both do: the app resizing
 * under the finger would otherwise move the slider out from under it.
 */
@Composable
internal fun UiScaleControl(
    currentPercent: Int,
    onChange: (percent: Int, commit: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
) {
    // The dragged value lives here, not in the app. Resizing the interface ON
    // every frame re-lays-out the whole tree, and that recomposition cancelled
    // this slider's own drag after the first touch: the value only ever jumped
    // to wherever the finger landed and never followed it. The app resizes once,
    // when the drag ends - the same way Android's own Display size works - and a
    // [live] caller resizes as it goes because this control cannot move: it is
    // drawn at the device's density, and only the app behind it changes.
    var preview by remember { mutableIntStateOf(currentPercent) }
    // What the app ends up at is echoed back here - once per commit, and once
    // per frame for a live caller. Deliberately not a `remember(currentPercent)`:
    // re-remembering this state every frame of a live drag restarts it mid-gesture.
    LaunchedEffect(currentPercent) { preview = currentPercent }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("$preview%", style = MaterialTheme.typography.headlineSmall)
        Slider(
            value = preview.toFloat(),
            onValueChange = {
                preview = it.roundToInt()
                if (live) onChange(preview, false)
            },
            onValueChangeFinished = { onChange(preview, true) },
            valueRange = UiScale.MIN_PERCENT.toFloat()..UiScale.MAX_PERCENT.toFloat(),
            // One stop per percent, so a drag can reach any whole number.
            steps = UiScale.MAX_PERCENT - UiScale.MIN_PERCENT - 1,
        )
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("${UiScale.MIN_PERCENT}%", style = MaterialTheme.typography.bodySmall)
            Text("${UiScale.MAX_PERCENT}%", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = {
                preview = UiScale.DEFAULT_PERCENT
                onChange(UiScale.DEFAULT_PERCENT, true)
            },
            enabled = preview != UiScale.DEFAULT_PERCENT,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset to ${UiScale.DEFAULT_PERCENT}%")
        }
    }
}
