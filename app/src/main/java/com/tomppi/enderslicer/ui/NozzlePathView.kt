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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.tomppi.enderslicer.engine.GcodeDialect
import com.tomppi.enderslicer.engine.GcodeNozzlePath
import com.tomppi.enderslicer.engine.GcodeNozzlePathParser
import com.tomppi.enderslicer.viewer.NozzlePathSurfaceView
import com.tomppi.enderslicer.viewer.resolveBeadWidthMm
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
import kotlin.math.sqrt

private const val PLAYBACK_TICK_MS = 16L
private const val PLAYBACK_TOTAL_MS = 60_000L
private const val PLAYBACK_MAX_STEP_MS = 2_000L
private const val HOLD_REPEAT_DELAY_MS = 400L
private const val HOLD_REPEAT_INTERVAL_MS = 100L
/** After this long without a ribbon-build progress report the UI says so. */
private const val NOZZLE_BUILD_STALL_MS = 60_000L

private sealed interface NozzlePathLoadState {
    data object Loading : NozzlePathLoadState
    data class Ready(val path: GcodeNozzlePath) : NozzlePathLoadState
    data class Failed(val message: String) : NozzlePathLoadState
}

@Composable
internal fun NozzlePathView(
    gcodePath: String,
    dialect: GcodeDialect = GcodeDialect.CURA,
    beadHeightMm: Double,
    beadLineWidthMm: Double,
    filamentDiameterMm: Double,
    modifier: Modifier = Modifier,
) {
    var parseProgress by remember(gcodePath) { mutableStateOf(0f) }
    val loadState by produceState<NozzlePathLoadState>(NozzlePathLoadState.Loading, gcodePath) {
        value = NozzlePathLoadState.Loading
        parseProgress = 0f
        value = try {
            val startedAt = SystemClock.uptimeMillis()
            val parsed = runInterruptible(Dispatchers.IO) {
                GcodeNozzlePathParser.parse(File(gcodePath), dialect) { fraction ->
                    parseProgress = fraction
                }
            }
            Log.i(
                "NozzlePathView",
                "parsed " + parsed.moveCount + " moves in " + (SystemClock.uptimeMillis() - startedAt) + " ms",
            )
            NozzlePathLoadState.Ready(parsed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e("NozzlePathView", "nozzle-path parse failed", error)
            NozzlePathLoadState.Failed(error.message ?: "Unable to parse nozzle path")
        }
    }

    when (val current = loadState) {
        NozzlePathLoadState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
        is NozzlePathLoadState.Failed -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(current.message, modifier = Modifier.padding(24.dp))
        }
        is NozzlePathLoadState.Ready -> NozzlePathPlayer(
            current.path,
            gcodePath,
            beadHeightMm,
            beadLineWidthMm,
            filamentDiameterMm,
            modifier,
        )
    }
}

@Composable
private fun HoldRepeatButton(
    text: String,
    outlined: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onStep: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, enabled) {
        if (pressed && enabled) {
            delay(HOLD_REPEAT_DELAY_MS)
            while (isActive && pressed && enabled) {
                onStep()
                delay(HOLD_REPEAT_INTERVAL_MS)
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

/**
 * Resolves the displayed bead width for a move the same way the renderer
 * does, so the inspector readout and the 3D geometry always agree: width =
 * deltaE x filament area / length / layer height, clamped to the settings
 * line width; micro segments collapse to zero.
 */
private fun moveBeadWidthMm(
    path: GcodeNozzlePath,
    moveIndex: Int,
    beadHeightMm: Double,
    beadLineWidthMm: Double,
    filamentDiameterMm: Double,
): Double {
    val offset = moveIndex * GcodeNozzlePath.VALUES_PER_MOVE
    val moves = path.moves
    val dx = moves[offset + GcodeNozzlePath.X2] - moves[offset + GcodeNozzlePath.X1]
    val dy = moves[offset + GcodeNozzlePath.Y2] - moves[offset + GcodeNozzlePath.Y1]
    val length = sqrt(dx * dx + dy * dy).toDouble()
    val radius = filamentDiameterMm / 2.0
    val area = (Math.PI * radius * radius).toFloat()
    return resolveBeadWidthMm(
        lengthMm = length.toFloat(),
        deltaE = moves[offset + GcodeNozzlePath.DELTA_E],
        parsedLayerHeight = moves[offset + GcodeNozzlePath.LAYER_HEIGHT],
        layerHeightFallback = beadHeightMm.toFloat(),
        lineWidth = beadLineWidthMm.toFloat(),
        filamentArea = area,
    ).toDouble()
}

@Composable
private fun NozzlePathPlayer(
    path: GcodeNozzlePath,
    artifactKey: String,
    beadHeightMm: Double,
    beadLineWidthMm: Double,
    filamentDiameterMm: Double,
    modifier: Modifier,
) {
    var moveIndex by rememberSaveable(artifactKey) { mutableIntStateOf(0) }
    var playing by rememberSaveable(artifactKey) { mutableStateOf(false) }
    val safeIndex = moveIndex.coerceIn(0, max(path.moveCount - 1, 0))
    val lifecycleOwner = LocalLifecycleOwner.current
    var surfaceView by remember(artifactKey) { mutableStateOf<NozzlePathSurfaceView?>(null) }
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
            val step = max(1, (path.moveCount * PLAYBACK_TICK_MS / PLAYBACK_TOTAL_MS).toInt())
            val perMoveDelay =
                (PLAYBACK_TOTAL_MS / max(path.moveCount, 1)).coerceIn(PLAYBACK_TICK_MS, PLAYBACK_MAX_STEP_MS)
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
            if (SystemClock.uptimeMillis() - lastBuildReportAt > NOZZLE_BUILD_STALL_MS) {
                if (buildStallNote == null) {
                    Log.w(
                        "NozzlePathView",
                        "no build progress for ${NOZZLE_BUILD_STALL_MS / 1000}s (last at ${buildProgress})",
                    )
                    buildStallNote =
                        "No progress for ${NOZZLE_BUILD_STALL_MS / 1000} s — still building or stuck. " +
                            "If the app closes, the device killed it."
                }
                break
            }
        }
    }

    Box(modifier = modifier) {
        val offset = safeIndex * GcodeNozzlePath.VALUES_PER_MOVE
        val sourceIndex = path.sourceMoveIndices[safeIndex]
        val moveLabel = if (path.truncated) {
            "Preview segment ${safeIndex + 1}/${path.moveCount} · source move ${sourceIndex + 1}/${path.sourceMoveCount}"
        } else {
            "Move ${sourceIndex + 1}/${path.sourceMoveCount}"
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
                        NozzlePathSurfaceView(context).also { surfaceView = it }
                    },
                    update = { view ->
                        view.setPath(
                            path,
                            safeIndex,
                            beadHeightMm.toFloat(),
                            beadLineWidthMm.toFloat(),
                            filamentDiameterMm.toFloat(),
                        )
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
                    HoldRepeatButton(
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
                    HoldRepeatButton(
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
                        val bead = moveBeadWidthMm(path, safeIndex, beadHeightMm, beadLineWidthMm, filamentDiameterMm)
                        val flow = bead / beadLineWidthMm
                        Text(
                            moveLabel + " · Z %.3f mm · %.1f mm/s · w %.2f mm · flow %.0f%%".format(
                                path.moves[offset + GcodeNozzlePath.Z2],
                                path.moves[offset + GcodeNozzlePath.SPEED],
                                bead,
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
