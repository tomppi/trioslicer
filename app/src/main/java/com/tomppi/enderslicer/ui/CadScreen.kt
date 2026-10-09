package com.tomppi.enderslicer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs
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
     * Whether to draw the overlay that says what the engine is doing.
     *
     * A viewer setting rather than a constant: the strip sits over the top-left of the picture,
     * and a user who is studying that corner of a part should be able to get it out of the way.
     */
    showStatus: Boolean = true,
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
    /** Degrees from a two-finger twist: the deliberate roll, which a finger cannot ask for. */
    onRotate: (Float) -> Unit,
    onSelect: (Int, Int) -> Unit,
    onViewSize: (Int, Int) -> Unit,
    onResetView: () -> Unit,
    /** A named view: Iso, Front, Top, Right. Re-frames the part as well as turning the camera. */
    onOrientation: (String) -> Unit = {},
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
        // The four views worth a tap. A part is judged from a named direction far more often
        // than from a free angle, and naming them costs one row.
        Surface(tonalElevation = 1.dp) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                listOf("iso" to "Iso", "front" to "Front", "top" to "Top", "right" to "Right")
                    .forEach { (name, label) ->
                        TextButton(onClick = { onOrientation(name) }) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
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
            // One view: the engine's own. The frame is asked for at exactly this size, so
            // what the user taps is the pixel the engine picked at - letterboxing a frame
            // rendered at another size would name the wrong face.
            Box {
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
                            // One finger orbits, two pan and pinch - the gestures the plate's
                            // own viewer taught, so there is nothing to learn.
                            .pointerInput(Unit) {
                                detectOrbitPanZoom(
                                    onOrbit = onOrbit,
                                    onPan = onPan,
                                    onZoom = onZoom,
                                    onRotate = onRotate,
                                )
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

                // What the screen is doing, over the picture rather than above it: a strip
                // that appears and disappears resizes the view, and the view's size is what
                // the frame loop renders at - so a frame in flight changed the size that
                // asked for the next one, and the screen never stopped rendering.
                if (showStatus && (pick != null || framePending)) {
                    Surface(
                        tonalElevation = 2.dp,
                        modifier = Modifier.align(Alignment.TopStart),
                    ) {
                        Text(
                            text = when {
                                pick?.isSomething == true && pick.kind == "face" ->
                                    "Picked face #%d (%.1f mm2) - say what to do with it."
                                        .format(pick.index, pick.areaMm2 ?: 0.0)
                                pick?.isSomething == true ->
                                    "Picked %s #%d - say what to do with it."
                                        .format(pick.kind, pick.index)
                                pick != null ->
                                    "Nothing under that tap. Try again, or turn the part."
                                else -> "Rendering..."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
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

/**
 * One finger orbits; two or more pan by their centroid and pinch to zoom.
 *
 * Not `detectTransformGestures`, which this used to call: that reports the centroid's movement
 * whoever is making it, so a two-finger pan arrives looking exactly like a one-finger drag -
 * pan, zoom 1, rotation 0 - and every one of them was sent to the orbit. The pointer count is
 * the whole difference between the two gestures, and the plate's viewer (ModelSurfaceView)
 * draws the line in the same place.
 *
 * Touch slop is handled the way the framework's own detector handles it: nothing is reported
 * and nothing is consumed until the gesture has travelled further than the slop, which is what
 * lets a tap still reach the picker in the pointerInput beside this one.
 *
 * Pan is passed through as the finger moved. The engine's Pan(+dx) moves the part +dx as well,
 * measured rather than assumed, so the part follows the hand.
 */
private suspend fun PointerInputScope.detectOrbitPanZoom(
    onOrbit: (Float, Float) -> Unit,
    onPan: (Float, Float) -> Unit,
    onZoom: (Float) -> Unit,
    onRotate: (Float) -> Unit,
) {
    awaitEachGesture {
        val touchSlop = viewConfiguration.touchSlop
        var travelled = Offset.Zero
        var pinched = 1f
        var pastTouchSlop = false
        // What this gesture turned out to be. Latched at the first decisive movement.
        var turning = false
        var turningDecided = false
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val pressed = event.changes.filter { it.pressed }
                val count = pressed.size
                val panChange = if (count == 0) Offset.Zero else {
                    pressed.fold(Offset.Zero) { sum, change ->
                        sum + (change.position - change.previousPosition)
                    } / count.toFloat()
                }
                val spanBefore = spanOf(event, useCurrent = false)
                val spanNow = spanOf(event, useCurrent = true)
                val zoomChange = if (spanBefore > 0f && spanNow > 0f) spanNow / spanBefore else 1f
                // The twist: how far the line between the fingers turned. Only meaningful with
                // two fingers down, which is the only time it is used.
                val twistNow = angleOf(event, useCurrent = true)
                val twistBefore = angleOf(event, useCurrent = false)
                val twist = if (twistNow != null && twistBefore != null) {
                    var delta = twistNow - twistBefore
                    while (delta > 180f) delta -= 360f
                    while (delta < -180f) delta += 360f
                    delta
                } else {
                    0f
                }

                if (!pastTouchSlop) {
                    travelled += panChange
                    pinched *= zoomChange
                    // A pure twist travels no distance and changes no span, so it needs its own
                    // way past the slop - otherwise the first degrees of every turn are lost and
                    // a turn on its own would never start at all.
                    if (abs(twist) * maxOf(spanNow, spanBefore) > touchSlop) pastTouchSlop = true
                    val pinchMotion = abs(1f - pinched) * maxOf(spanNow, spanBefore)
                    if (travelled.getDistance() > touchSlop || pinchMotion > touchSlop) {
                        pastTouchSlop = true
                    }
                }
                if (pastTouchSlop) {
                    if (count >= 2) {
                        // Two fingers can pan, pinch and turn, and one gesture does some of all
                        // three - so this decides between them rather than doing all of them at
                        // once. Firing all three was the bug: a turn moves each finger, so
                        // panChange is never zero during one, and the part slid sideways under
                        // the hand while it turned. The slide is what the eye reads, so a twist
                        // looked like a pan and the rotation was invisible.
                        //
                        // Measured the same way the touch slop is - the arc the fingers swept,
                        // in pixels - and the larger motion wins the event.
                        // Decided once per gesture, then kept until the fingers lift.
                        //
                        // Re-deciding every event was why a turn felt insensitive: a real twist
                        // drifts as it turns, so the travel won the event most of the time and
                        // only the odd event rotated - most of a deliberate turn was spent
                        // panning. Once a hand has committed to turning, it is turning.
                        val arc = abs(twist) * maxOf(spanNow, spanBefore)
                        val travel = panChange.getDistance()
                        if (!turningDecided && (arc > touchSlop || travel > touchSlop)) {
                            turning = arc > travel
                            turningDecided = true
                        }
                        if (turning) {
                            if (twist != 0f) onRotate(twist)
                        } else {
                            if (panChange != Offset.Zero) onPan(panChange.x, panChange.y)
                            if (zoomChange != 1f) onZoom(zoomChange)
                        }
                    } else if (panChange != Offset.Zero) {
                        onOrbit(panChange.x, panChange.y)
                    }
                    event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })
    }
}

/** The angle of the line between two pressed pointers, or null when there are not two. */
private fun angleOf(event: PointerEvent, useCurrent: Boolean): Float? {
    val points = event.changes.filter { it.pressed }.map {
        if (useCurrent) it.position else it.previousPosition
    }
    if (points.size < 2) return null
    val dx = points[1].x - points[0].x
    val dy = points[1].y - points[0].y
    if (dx == 0f && dy == 0f) return null
    return kotlin.math.atan2(dy, dx) * 180f / kotlin.math.PI.toFloat()
}

/** Mean distance of the pressed pointers from their centroid, now or a moment ago. */
private fun spanOf(event: PointerEvent, useCurrent: Boolean): Float {
    val points = event.changes.filter { it.pressed }.map {
        if (useCurrent) it.position else it.previousPosition
    }
    if (points.size < 2) return 0f
    val centroid = points.fold(Offset.Zero) { sum, point -> sum + point } / points.size.toFloat()
    return points.fold(0f) { sum, point -> sum + (point - centroid).getDistance() } / points.size
}

