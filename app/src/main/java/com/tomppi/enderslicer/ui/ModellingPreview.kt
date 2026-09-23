package com.tomppi.enderslicer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.modelling.CameraOwner
import com.tomppi.enderslicer.modelling.EnginePreviewClient
import com.tomppi.enderslicer.modelling.ModellingCamera
import com.tomppi.enderslicer.modelling.SceneSummary
import com.tomppi.enderslicer.nativebridge.BlenderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Rendered at the size the view actually occupies, so the frame is as sharp as
 * the screen and no sharper. Workbench is a GPU rasteriser, so its cost is fill
 * rate on an Adreno 740 rather than the tens of milliseconds per frame Cycles
 * spends per sample. Measured warm on the default scene:
 *
 *   256 -> 19 ms, 384 -> 31 ms, 512 -> 56 ms, 640 -> 66 ms, 768 -> 80 ms
 *
 * which is why the two ends differ: a full-size frame is sharp but far too slow
 * to drag, and a small one drags smoothly but looks soft.
 */
private const val MaxPreviewEdge = 1280
private const val MinPreviewEdge = 128
/** Longest edge while a finger is down: about 40 ms a frame. */
private const val InteractiveEdge = 512
/** How long after the last gesture frame the full-resolution one is asked for. */
private const val SettleMs = 220L

private const val DegreesPerPixel = 0.35f
private const val MinDistanceMm = 0.2f
private const val MaxDistanceMm = 20_000f

/**
 * Puts the camera at a distance that frames a sphere of [radius].
 *
 * A 42-degree vertical field of view covers `2 * d * tan(21)` at distance d, so
 * `d = r / tan(21)` puts the sphere's edge exactly on the frame; the extra gives
 * it a little air. Without this the orbit distance is a guess, and a guess is
 * wrong by orders of magnitude between a 2-unit default cube and a 200 mm plate.
 */
private const val FramingFactor = 2.9f

/**
 * The engine's own view of the model.
 *
 * There is no second camera here. The app asks the engine to point its camera
 * and render, and shows the resulting frame, so what the user sees is exactly
 * what the agent sees - same scene, same camera, same shading. Orbiting sends a
 * new camera and asks for another frame.
 *
 * Frames are coalesced rather than queued: only the newest camera is ever
 * waiting, so a fast drag skips intermediate views instead of building a backlog
 * of renders that arrive after the finger has moved on. Rendering runs on the
 * engine's main thread - the same thread the agent's commands run on - so
 * falling behind would also mean starving the agent.
 */
@Composable
fun ModellingPreview(
    blenderDir: File,
    initialCamera: ModellingCamera?,
    /** False while the agent owns the camera: gestures are refused, not queued. */
    interactive: Boolean,
    /** What the engine is holding, so the bar can name the real thing. */
    onScene: (SceneSummary) -> Unit = {},
    onCameraChanged: (ModellingCamera) -> Unit,
    modifier: Modifier = Modifier,
) {
    val client = remember { EnginePreviewClient(tokenFile = BlenderEngine.tokenFile(blenderDir)) }
    DisposableEffect(client) { onDispose { client.close() } }

    var yaw by remember { mutableStateOf(initialCamera?.yawDeg ?: -28f) }
    var pitch by remember { mutableStateOf(initialCamera?.pitchDeg ?: 22f) }
    // Replaced by a framing derived from the engine's scene as soon as we know it.
    var distance by remember { mutableStateOf(initialCamera?.distanceMm ?: 0f) }
    var fov by remember { mutableStateOf(initialCamera?.fovDeg ?: ModellingCamera.DEFAULT_FOV_DEGREES) }
    var target by remember {
        mutableStateOf(
            floatArrayOf(
                initialCamera?.targetX ?: 0f,
                initialCamera?.targetY ?: 0f,
                initialCamera?.targetZ ?: 0f,
            ),
        )
    }

    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var viewSize by remember { mutableStateOf(IntSize(512, 512)) }
    // The rendered frame size, captured from the view's *width*.
    //
    // Recomputing it from the whole box made collapsing the chat re-render at a
    // different shape, which moved the camera: the same model, framed
    // differently, because a panel was hidden. The chat only changes the box's
    // height, so keying on the width is what keeps the picture still.
    var renderWidth by remember { mutableStateOf(0) }
    var renderHeight by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>("Looking at the engine's scene...") }
    var interacting by remember { mutableStateOf(false) }
    val lastGestureAt = remember { AtomicLong(0L) }
    // Bumped when the picture must be rendered again at the same camera - the
    // settle after a gesture, or a new view size. Writing an equal camera back
    // into [requested] is invisible to snapshot state and to the render loop's
    // own comparison, so both of those used to be no-ops.
    var renderNonce by remember { mutableStateOf(0) }

    /** The frame the app renders at rest: what the agent is told to match. */
    fun settledSize(): Pair<Int, Int> = Pair(
        renderWidth.coerceAtLeast(MinPreviewEdge),
        renderHeight.coerceAtLeast(MinPreviewEdge),
    )

    /** The frame to render now - the same shape, coarser while a finger is down. */
    fun frameSize(): Pair<Int, Int> {
        val (width, height) = settledSize()
        if (!interacting) return width to height
        val scale = (InteractiveEdge.toFloat() / maxOf(width, height)).coerceAtMost(1f)
        return Pair(
            (width * scale).roundToInt().coerceAtLeast(MinPreviewEdge),
            (height * scale).roundToInt().coerceAtLeast(MinPreviewEdge),
        )
    }

    fun snapshot(): ModellingCamera {
        // The *settled* size, never the interactive one: the agent is being told
        // what the user's view actually is, and during a drag that is a temporary
        // 512-pixel compromise, not the picture on the screen.
        val (width, height) = settledSize()
        return ModellingCamera(
            yawDeg = yaw,
            pitchDeg = pitch,
            distanceMm = distance,
            targetX = target[0],
            targetY = target[1],
            targetZ = target[2],
            width = width,
            height = height,
            owner = if (interactive) CameraOwner.USER else CameraOwner.AGENT,
        )
    }

    val requested = remember { mutableStateOf<ModellingCamera?>(null) }



    /**
     * Millimetres, on the plane through the target, of a point [pixel] from the
     * centre of the view.
     *
     * The screen basis for a turntable has a closed form, so there is no matrix
     * to build: with azimuth a and elevation e,
     *   right = (cos a, sin a, 0)
     *   up    = (-sin a sin e, cos a sin e, cos e)
     * and the visible half-height at the target is `distance * tan(fov / 2)`.
     */
    /**
     * World displacement of a screen *displacement*.
     *
     * Kept separate from [worldOffsetOf] because the two are not
     * interchangeable: a delta has no origin, so subtracting the centre of the
     * view from one injects a constant offset of half the view's size. Doing
     * that turned every pan event into a jump of half the visible extent, and a
     * few thousand of them left the camera aimed a hundred metres from the
     * model.
     */
    fun worldDeltaOf(dx: Float, dy: Float): FloatArray {
        val a = Math.toRadians(yaw.toDouble())
        val e = Math.toRadians(pitch.toDouble())
        val rx = cos(a).toFloat()
        val ry = sin(a).toFloat()
        val ux = (-sin(a) * sin(e)).toFloat()
        val uy = (cos(a) * sin(e)).toFloat()
        val uz = cos(e).toFloat()

        val width = viewSize.width.coerceAtLeast(1)
        val height = viewSize.height.coerceAtLeast(1)
        val halfHeight = (distance * tan(Math.toRadians(fov / 2.0))).toFloat()
        val halfWidth = halfHeight * (width.toFloat() / height)

        // Screen y grows downwards; the view's up does not.
        val right = (dx / (width / 2f)) * halfWidth
        val up = (-dy / (height / 2f)) * halfHeight
        return floatArrayOf(
            rx * right + ux * up,
            ry * right + uy * up,
            uz * up,
        )
    }

    /** World offset of a point in the view, measured from its middle. */
    fun worldOffsetOf(pixel: Offset): FloatArray = worldDeltaOf(
        pixel.x - viewSize.width / 2f,
        pixel.y - viewSize.height / 2f,
    )

    fun applyTarget(offset: FloatArray) {
        target = floatArrayOf(target[0] + offset[0], target[1] + offset[1], target[2] + offset[2])
    }

    /** One finger: turn the model. Drag down looks from higher up. */
    fun orbitBy(delta: Offset) {
        yaw = wrapDegrees(yaw - delta.x * DegreesPerPixel)
        pitch = (pitch + delta.y * DegreesPerPixel).coerceIn(-89f, 89f)
    }

    /** Two fingers, moving together: carry the orbit point with them. */
    fun applyPan(delta: Offset) {
        val world = worldDeltaOf(delta.x, delta.y)
        applyTarget(floatArrayOf(-world[0], -world[1], -world[2]))
    }

    /**
     * Two fingers, moving apart: close in on what is under them.
     *
     * Zooming alone converges on whatever the target is, so a pinch over the bow
     * of a boat would still end up looking at the middle of it. Moving the target
     * by `w * (1 - 1/factor)` keeps the point under the fingers where it is -
     * that is the point you asked to look at.
     */
    fun applyZoom(factor: Float, centroid: Offset) {
        // Two fingers resting on glass still report a spread that drifts; below
        // this the accumulated shift is noise, not intent.
        if (!factor.isFinite() || factor <= 0f || abs(factor - 1f) < 2e-3f) return
        val world = worldOffsetOf(centroid)
        val shift = 1f - 1f / factor
        applyTarget(floatArrayOf(world[0] * shift, world[1] * shift, world[2] * shift))
        distance = (distance / factor).coerceIn(MinDistanceMm, MaxDistanceMm)
    }

    // Ask the engine what it is holding, once, and frame that. The app does not
    // derive this from the mesh it happens to have: the engine is the scene.
    LaunchedEffect(client) {
        // The engine boots on its default scene, which is a cube. If a model has
        // been sent to it and it is still holding that cube, load the model now:
        // showing a cube after the user has sent one reads as a bug, and the
        // engine cannot tell them apart on its own. Anything the agent has built
        // means a real mesh is present, and that is never overwritten.
        // Restore the newest thing the engine *produced* in preference to the
        // file the user sent in. A restarted engine comes back on its default
        // scene and this puts something back; putting back the original upload
        // throws away everything done to it since. That happened: the engine was
        // restarted holding a capped roof and came back holding the uncapped
        // boat, so the app showed the finished model and the engine showed the
        // one it started from.
        val sent = File(blenderDir, "imports/current.stl").takeIf { it.isFile }
        val produced = File(blenderDir, "exports").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".stl", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
        val handoff = when {
            produced == null -> sent
            sent == null -> produced
            produced.lastModified() > sent.lastModified() -> produced
            else -> sent
        }
        if (handoff != null) {
            // null means the engine could not be asked yet - it is still booting.
            // Reading that as "the scene has content" is what silently skipped the
            // import and left the default cube in the engine instead of the model.
            val untouched = withContext(Dispatchers.IO) { client.isOnDefaultScene() }
            if (untouched != false) {
                status = "Loading " + handoff.name + " into the engine..."
                val loaded = withContext(Dispatchers.IO) {
                    // Waits for the engine when it is still coming up, which is the
                    // case this used to lose.
                    runCatching { client.importModelWhenReady(handoff, blenderDir) }.getOrDefault(false)
                }
                if (!loaded) status = "Could not load the model into the engine"
            }
        }
        val summary = withContext(Dispatchers.IO) {
            runCatching { client.sceneSummary() }.getOrNull()
        }
        if (summary != null) {
            onScene(summary)
            val centre = floatArrayOf(summary.centreX, summary.centreY, summary.centreZ)
            val radius = summary.radius
            // A target far outside the model means the view was lost - a runaway
            // pan, or a camera written for a scene that no longer exists. Recover
            // instead of opening somewhere the model is not, because there is no
            // way back from a camera aimed into empty space.
            val drift = floatArrayOf(
                target[0] - centre[0],
                target[1] - centre[1],
                target[2] - centre[2],
            ).let { (it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) }
            val lost = radius > 0f &&
                (drift > radius * radius * 16f || distance > radius * 60f)
            if (initialCamera == null || lost) {
                target = centre
                if (radius > 0f) {
                    distance = (radius * FramingFactor).coerceIn(MinDistanceMm, MaxDistanceMm)
                }
            }
        }
        if (distance <= 0f) distance = 120f
        requested.value = snapshot()
        onCameraChanged(requested.value!!)
    }

    // The agent moved the camera: adopt it and re-render. Only while it owns the
    // camera - otherwise this would yank the view out from under a user who is
    // looking at something.
    LaunchedEffect(initialCamera?.rev) {
        val cam = initialCamera ?: return@LaunchedEffect
        if (interactive) return@LaunchedEffect
        yaw = cam.yawDeg
        pitch = cam.pitchDeg
        distance = cam.distanceMm
        target = floatArrayOf(cam.targetX, cam.targetY, cam.targetZ)
        requested.value = snapshot()
    }

    // Capture the frame size from the view's *width* alone.
    //
    // Deriving it from the whole box meant collapsing the chat re-rendered at a
    // different shape, which reframed the camera: the same model, moved, because
    // a panel was hidden. The chat only changes the height, so keying on the
    // width is what leaves the picture alone.
    LaunchedEffect(viewSize) {
        if (viewSize.width <= 0 || viewSize.height <= 0) return@LaunchedEffect
        val (width, height) = renderSize(viewSize, MaxPreviewEdge)
        if (width == renderWidth && height == renderHeight) return@LaunchedEffect
        renderWidth = width
        renderHeight = height
        renderNonce++
    }

    // When the finger lifts, ask once more at full size. This is what makes the
    // drag cheap without the picture staying soft.
    LaunchedEffect(interacting) {
        if (!interacting) return@LaunchedEffect
        while (System.currentTimeMillis() - lastGestureAt.get() < SettleMs) delay(40)
        interacting = false
        renderNonce++
    }

    // One render at a time, always the newest camera.
    LaunchedEffect(client) {
        val file = File(blenderDir, "preview.png")
        while (currentCoroutineContext().isActive) {
            val camera = requested.value
            if (camera == null) {
                delay(50)
                continue
            }
            val nonce = renderNonce
            val (width, height) = frameSize()
            val outcome = withContext(Dispatchers.IO) {
                runCatching { client.renderPreview(camera, width, height, file) }
            }
            val failure = outcome.exceptionOrNull()
            if (failure != null) {
                status = failure.message?.take(120) ?: "The engine is not answering"
                delay(1_000)
                continue
            }
            if (outcome.getOrDefault(false)) {
                val decoded = withContext(Dispatchers.IO) { client.readPreview(file) }
                if (decoded != null) {
                    frame = decoded
                    status = null
                } else {
                    status = "The engine rendered nothing"
                }
            } else {
                status = "The engine refused the render"
            }
            // Either the camera moved, or the same camera has to be rendered again
            // (settle to full size, new view size).
            snapshotFlow { requested.value to renderNonce }
                .first { (latest, latestNonce) -> latest != camera || latestNonce != nonce }
        }
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { viewSize = it }
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                // One finger turns the model; two fingers move the point it turns
                // around and the distance to it. Without the pan the orbit is
                // pinned to the model's centre, so zooming in always converges on
                // the middle of the part and a detail at the bow cannot be
                // examined at all - which is the whole reason to zoom in.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var previous: Offset? = null
                    var previousSpread = 0f
                    var previousCount = 0
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val centroid = pressed.fold(Offset.Zero) { sum, c -> sum + c.position } /
                            pressed.size.toFloat()

                        if (pressed.size != previousCount) {
                            // A finger arrived or left, so the centroid moved
                            // without anything being dragged: from one finger to
                            // the midpoint of two is half the distance between
                            // them, in one frame. Treating that as a drag yanked
                            // the camera across the model the moment a second
                            // finger touched down.
                            previous = null
                            previousSpread = 0f
                            previousCount = pressed.size
                        }

                        if (pressed.size >= 2) {
                            val spread = (pressed[0].position - pressed[1].position).getDistance()
                            if (previousSpread > 0f && spread > 0f) {
                                applyZoom(spread / previousSpread, centroid)
                            }
                            previous?.let { applyPan(centroid - it) }
                            previousSpread = spread
                        } else {
                            previous?.let { orbitBy(centroid - it) }
                            previousSpread = 0f
                        }
                        previous = centroid
                        event.changes.forEach { if (it.positionChanged()) it.consume() }

                        interacting = true
                        lastGestureAt.set(System.currentTimeMillis())
                        val next = snapshot()
                        requested.value = next
                        onCameraChanged(next)
                    }
                }
            },
    ) {
        frame?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "The engine's view of the model",
                // Fit, so the frame fills whatever the view is. Drawing it at
                // its pixel size instead does not mean "1:1" here - the painter
                // draws at density-scaled size, so a fixed-pixel frame came out
                // a third of the size it was rendered at and collapsing the chat
                // left it in a small box. Filling the view is worth the frame
                // following the view's shape; the camera does not follow it.
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        status?.let { message ->
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize().padding(24.dp),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!interactive) {
            Text(
                text = "The agent has the camera",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            )
        }
    }
}

/**
 * The view's aspect ratio, scaled so its longest edge is at most [longest].
 *
 * The engine renders at the shape it is asked for, so matching the view's aspect
 * means no letterboxing and no wasted pixels; scaling to a common longest edge
 * means a fold or a rotation changes the count of pixels rather than the framing.
 */
private fun renderSize(view: IntSize, longest: Int): Pair<Int, Int> {
    val width = view.width.coerceAtLeast(1)
    val height = view.height.coerceAtLeast(1)
    val scale = (longest.toFloat() / maxOf(width, height)).coerceAtMost(1f)
    return Pair(
        (width * scale).roundToInt().coerceIn(MinPreviewEdge, longest),
        (height * scale).roundToInt().coerceIn(MinPreviewEdge, longest),
    )
}

private fun wrapDegrees(value: Float): Float {
    var wrapped = value % 360f
    if (wrapped < 0f) wrapped += 360f
    return wrapped
}
