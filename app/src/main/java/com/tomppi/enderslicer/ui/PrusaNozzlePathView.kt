package com.tomppi.enderslicer.ui

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tomppi.enderslicer.engine.PrusaNozzlePath
import com.tomppi.enderslicer.engine.PrusaNozzlePathParser
import com.tomppi.enderslicer.viewer.PrusaNozzlePathSurfaceView
import com.tomppi.enderslicer.viewer.ViewerOrientation
import com.tomppi.enderslicer.viewer.ViewerOrientationMath
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlin.math.max
import kotlin.math.roundToInt

private const val PRUSA_PLAYBACK_TICK_MS = 16L
private const val PRUSA_PLAYBACK_TOTAL_MS = 60_000L
private const val PRUSA_PLAYBACK_MAX_STEP_MS = 2_000L
private const val PRUSA_HOLD_REPEAT_DELAY_MS = 400L
private const val PRUSA_HOLD_REPEAT_INTERVAL_MS = 100L
/** After this long without a ribbon-build progress report the UI says so. */
private const val PRUSA_NOZZLE_BUILD_STALL_MS = 60_000L

private sealed interface PrusaNozzlePathLoadState {
    data object Loading : PrusaNozzlePathLoadState
    data class Ready(val path: PrusaNozzlePath) : PrusaNozzlePathLoadState
    data class Failed(val message: String) : PrusaNozzlePathLoadState
}

@Composable
internal fun PrusaNozzlePathView(
    gcodePath: String,
    beadLineWidthMm: Double,
    modifier: Modifier = Modifier,
) {
    var parseProgress by remember(gcodePath) { mutableStateOf(0f) }
    val loadState by produceState<PrusaNozzlePathLoadState>(PrusaNozzlePathLoadState.Loading, gcodePath) {
        value = PrusaNozzlePathLoadState.Loading
        parseProgress = 0f
        value = try {
            val startedAt = SystemClock.uptimeMillis()
            val parsed = runInterruptible(Dispatchers.IO) {
                PrusaNozzlePathParser.parse(File(gcodePath)) { fraction ->
                    parseProgress = fraction
                }
            }
            Log.i(
                "PrusaNozzlePathView",
                "parsed " + parsed.moveCount + " moves, " + parsed.layerCount + " layers in " +
                    (SystemClock.uptimeMillis() - startedAt) + " ms",
            )
            PrusaNozzlePathLoadState.Ready(parsed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e("PrusaNozzlePathView", "prusa nozzle-path parse failed", error)
            PrusaNozzlePathLoadState.Failed(error.message ?: "Unable to parse nozzle path")
        }
    }

    when (val current = loadState) {
        PrusaNozzlePathLoadState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(240.dp),
            ) {
                LinearProgressIndicator(progress = { parseProgress }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Parsing G-code… ${(parseProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        is PrusaNozzlePathLoadState.Failed -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(current.message, modifier = Modifier.padding(24.dp))
        }
        is PrusaNozzlePathLoadState.Ready -> PrusaNozzlePathPlayer(
            current.path,
            gcodePath,
            beadLineWidthMm,
            modifier,
        )
    }
}

@Composable
private fun PrusaHoldRepeatButton(
    text: String,
    outlined: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onStep: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, enabled) {
        if (pressed && enabled) {
            delay(PRUSA_HOLD_REPEAT_DELAY_MS)
            while (isActive && pressed && enabled) {
                onStep()
                delay(PRUSA_HOLD_REPEAT_INTERVAL_MS)
            }
        }
    }
    val interactionModifier = modifier.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            pressed = true
            try {
                onStep()
                waitForUpOrCancellation()
            } finally {
                pressed = false
            }
        }
    }
    if (outlined) {
        OutlinedButton(
            onClick = { },
            enabled = enabled,
            modifier = interactionModifier,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        ) { Text(text, style = MaterialTheme.typography.labelMedium) }
    } else {
        Button(
            onClick = { },
            enabled = enabled,
            modifier = interactionModifier,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        ) { Text(text, style = MaterialTheme.typography.labelMedium) }
    }
}
@Composable
private fun PrusaNozzlePathPlayer(
    path: PrusaNozzlePath,
    artifactKey: String,
    beadLineWidthMm: Double,
    modifier: Modifier,
) {
    var moveIndex by rememberSaveable(artifactKey) { mutableIntStateOf(0) }
    var playing by rememberSaveable(artifactKey) { mutableStateOf(false) }
    val safeIndex = moveIndex.coerceIn(0, max(path.moveCount - 1, 0))
    val lifecycleOwner = LocalLifecycleOwner.current
    var surfaceView by remember(artifactKey) { mutableStateOf<PrusaNozzlePathSurfaceView?>(null) }
    var orientation by remember(artifactKey) { mutableStateOf<ViewerOrientation?>(null) }
    var showTravels by rememberSaveable(artifactKey) { mutableStateOf(true) }
    var orthographic by rememberSaveable(artifactKey) { mutableStateOf(false) }
    var zoomLevel by remember(artifactKey) { mutableStateOf(1f) }
    // Ribbon build state: the GL thread builds the whole path before the first
    // frame, so surface a live bar (or an explicit failure) instead of silence.
    var buildProgress by remember(artifactKey) { mutableStateOf<Float?>(null) }
    var buildDone by remember(artifactKey) { mutableStateOf(false) }
    var buildError by remember(artifactKey) { mutableStateOf<String?>(null) }
    var lastBuildReportAt by remember(artifactKey) { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var buildStallNote by remember(artifactKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(surfaceView) {
        surfaceView?.let { orientation = it.currentOrientation() }
    }

    DisposableEffect(lifecycleOwner, surfaceView) {
        val view = surfaceView
        if (view == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> view.onResume()
                    Lifecycle.Event.ON_PAUSE,
                    Lifecycle.Event.ON_STOP,
                    Lifecycle.Event.ON_DESTROY,
                    -> view.onPause()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                view.onResume()
            }
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                view.onPause()
            }
        }
    }

    LaunchedEffect(playing, artifactKey, path.moveCount) {
        while (playing && isActive) {
            if (moveIndex >= path.moveCount - 1) {
                playing = false
                break
            }
            val step = max(1, (path.moveCount * PRUSA_PLAYBACK_TICK_MS / PRUSA_PLAYBACK_TOTAL_MS).toInt())
            val perMoveDelay =
                (PRUSA_PLAYBACK_TOTAL_MS / max(path.moveCount, 1)).coerceIn(PRUSA_PLAYBACK_TICK_MS, PRUSA_PLAYBACK_MAX_STEP_MS)
            moveIndex = (moveIndex + step).coerceAtMost(path.moveCount - 1)
            delay(perMoveDelay)
        }
    }

    // Watchdog: a build no longer reporting progress is either still working
    // on a very large path or truly stuck. Say which in the UI; if the process
    // is killed, logcat keeps the last reported state for diagnosis.
    LaunchedEffect(buildProgress, buildDone, artifactKey) {
        while (!buildDone && isActive) {
            delay(5_000)
            if (SystemClock.uptimeMillis() - lastBuildReportAt > PRUSA_NOZZLE_BUILD_STALL_MS) {
                if (buildStallNote == null) {
                    Log.w(
                        "PrusaNozzlePathView",
                        "no build progress for ${PRUSA_NOZZLE_BUILD_STALL_MS / 1000}s (last at ${buildProgress})",
                    )
                    buildStallNote =
                        "No progress for ${PRUSA_NOZZLE_BUILD_STALL_MS / 1000} s — still building or stuck. " +
                            "If the app closes, the device killed it."
                }
                break
            }
        }
    }

    Box(modifier = modifier) {
        val offset = safeIndex * PrusaNozzlePath.VALUES_PER_MOVE
        val sourceIndex = path.sourceMoveIndices[safeIndex]
        val moveLabel = if (path.truncated) {
            "Preview segment " + (safeIndex + 1) + "/" + path.moveCount + " · source move " + (sourceIndex + 1) + "/" + path.sourceMoveCount
        } else {
            "Move " + (sourceIndex + 1) + "/" + path.sourceMoveCount
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PrusaNozzlePathSurfaceView(context).also { surfaceView = it }
                    },
                    update = { view ->
                        view.setPath(path, safeIndex)
                        view.onOrientationChanged = { orientation = it }
                        view.onZoomChanged = { zoomLevel = it }
                        view.onMovePicked = { picked ->
                            playing = false
                            moveIndex = picked.coerceIn(0, path.moveCount - 1)
                        }
                        view.onBuildProgress = { fraction ->
                            buildProgress = fraction
                            lastBuildReportAt = SystemClock.uptimeMillis()
                        }
                        view.onBuildFinished = { error ->
                            buildDone = true
                            buildError = error
                        }
                        view.showTravels = showTravels
                        view.orthographic = orthographic
                    },
                    onRelease = { view ->
                        view.onPause()
                        if (surfaceView === view) surfaceView = null
                    },
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                ) {
                    orientation?.let { value ->
                        OrientationGizmo(
                            yawDegrees = value.yawDegrees,
                            pitchDegrees = value.pitchDegrees,
                            cameraElevation = ViewerOrientationMath.PATH_VIEW_ELEVATION,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                if (!buildDone || buildError != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .width(240.dp),
                        ) {
                            val fraction = buildProgress
                            if (fraction == null) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = buildError
                                    ?: buildStallNote
                                    ?: "Building preview… ${((fraction ?: 0f) * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (buildError != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Slider(
                            value = safeIndex.toFloat(),
                            onValueChange = {
                                playing = false
                                moveIndex = it.roundToInt().coerceIn(0, path.moveCount - 1)
                            },
                            valueRange = 0f..max(path.moveCount - 1, 1).toFloat(),
                            enabled = path.moveCount > 1,
                            modifier = Modifier.weight(1f),
                        )
                        PrusaHoldRepeatButton(
                            text = "Prev",
                            outlined = true,
                            enabled = safeIndex > 0,
                            modifier = Modifier.height(32.dp),
                            onStep = {
                                playing = false
                                moveIndex = (moveIndex - 1).coerceAtLeast(0)
                            },
                        )
                        if (playing) {
                            Button(
                                onClick = { playing = false },
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            ) { Text("Pause", style = MaterialTheme.typography.labelMedium) }
                        } else {
                            Button(
                                onClick = {
                                    if (moveIndex >= path.moveCount - 1) moveIndex = 0
                                    playing = true
                                },
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            ) { Text("Play", style = MaterialTheme.typography.labelMedium) }
                        }
                        PrusaHoldRepeatButton(
                            text = "Next",
                            outlined = true,
                            enabled = safeIndex < path.moveCount - 1,
                            modifier = Modifier.height(32.dp),
                            onStep = {
                                playing = false
                                moveIndex = (moveIndex + 1).coerceAtMost(path.moveCount - 1)
                            },
                        )
                        OutlinedButton(
                            onClick = { surfaceView?.resetView() },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) { Text("Fit", style = MaterialTheme.typography.labelMedium) }
                        OutlinedButton(
                            onClick = { orthographic = !orthographic },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) { Text(if (orthographic) "Persp" else "Ortho", style = MaterialTheme.typography.labelMedium) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Travel moves", style = MaterialTheme.typography.labelMedium)
                        Switch(
                            checked = showTravels,
                            onCheckedChange = { showTravels = it },
                            modifier = Modifier.scale(0.7f),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            "%.1f×".format(zoomLevel),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val width = path.moves[offset + PrusaNozzlePath.WIDTH].toDouble()
                        val flow = width / beadLineWidthMm
                        Text(
                            moveLabel + " · Z %.3f mm · %.1f mm/s · w %.2f mm · flow %.0f%%".format(
                                path.moves[offset + PrusaNozzlePath.Z2],
                                path.moves[offset + PrusaNozzlePath.SPEED],
                                width,
                                flow * 100.0,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}