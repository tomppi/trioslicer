package com.tomppi.enderslicer.modelling

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.math.roundToInt
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "CadViewport"

/** How long to wait between attempts while the engine is still coming up. */
private const val STARTUP_RETRY_MS = 1_000L

/**
 * How many times to try once the engine claims to be ready.
 *
 * Before it is ready there is no limit worth setting: the first boot extracts 566 MB of
 * payload, and it would be wrong to give up on a screen that is merely early. Once it says it
 * is ready, though, a failure is a real fault and should be reported rather than retried
 * forever behind a spinner.
 */
private const val READY_RETRIES = 3

/**
 * Longest side of a frame drawn while the finger is moving, in pixels.
 *
 * 640 measured about 218 ms on the development phone, against 577 ms at 1080x1900, so
 * this is roughly a four-fold improvement in drag responsiveness. Frames are scaled to
 * fit, so the cost is sharpness during the gesture and nothing else - the settle frame
 * that follows at full size restores it, and picking stays pixel-exact because a tap
 * cannot arrive mid-drag.
 */
/** One request to the engine's camera, or a tap for it to pick at. */
/**
 * Degrees of turntable rotation per pixel of finger travel.
 *
 * The same number the plate's own viewer uses (ModellingPreview.DegreesPerPixel), so a drag turns
 * a model by the same amount in both places - the two viewers should not disagree about what a
 * centimetre of finger means.
 */
private const val DEGREES_PER_PIXEL = 0.35f

/** How many times a failed frame is re-asked for while a tap or a preset is still waiting. */
private const val FAILED_RETRIES = 4

/** How long to wait before re-asking. Longer than a frame, shorter than a user's patience. */
private const val RETRY_AFTER_FAILURE_MS = 1_200L

/**
 * Where the frame lands inside the view: ContentScale.Fit's factor and its letterbox offsets.
 *
 * The frame is drawn with ContentScale.Fit, so it is scaled by one factor and centred; a view
 * pixel is not a frame pixel. With the same aspect and no clamping this reduces to the old
 * scale with no offset, which is why it went unnoticed until a view exceeded the engine's cap.
 */
private data class FrameFit(val scale: Float, val offsetX: Float, val offsetY: Float) {
    /** View pixels to frame pixels - for a tap, and for a pan, which the engine takes in frame pixels. */
    fun toFrame(x: Float, y: Float): Pair<Float, Float> =
        (x - offsetX) / scale to (y - offsetY) / scale

    /** Frame pixels back to view pixels, so the answer names the pixel the user touched. */
    fun toView(x: Float, y: Float): Pair<Float, Float> =
        x * scale + offsetX to y * scale + offsetY
}

/**
 * How a frame of [frameWidth] x [frameHeight] is shown in a [viewWidth] x [viewHeight] view.
 *
 * A size of zero means the view has not been measured yet; the frame is then taken to fill it.
 */
private fun frameFit(viewWidth: Int, viewHeight: Int, frameWidth: Int, frameHeight: Int): FrameFit {
    if (viewWidth <= 0 || viewHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) {
        return FrameFit(1f, 0f, 0f)
    }
    val scale = minOf(viewWidth.toFloat() / frameWidth, viewHeight.toFloat() / frameHeight)
    return FrameFit(
        scale = scale,
        offsetX = (viewWidth - frameWidth * scale) / 2f,
        offsetY = (viewHeight - frameHeight * scale) / 2f,
    )
}

/** One request to the engine's camera, or a tap for it to pick at. */
private data class ViewRequest(
    val turnYaw: Float = 0f,
    val turnPitch: Float = 0f,
    val panDx: Float = 0f,
    val panDy: Float = 0f,
    val zoom: Float = 1f,

    val selectX: Int? = null,
    val selectY: Int? = null,
    val reset: Boolean = false,
    /** A named view - "front", "top" - which re-frames the part as well as turning the camera. */
    val orientation: String? = null,
) {
    /** Whether anything in here moves the camera. Picking and flags do not. */
    val hasMotion: Boolean
        get() = turnYaw != 0f || turnPitch != 0f || panDx != 0f || panDy != 0f || zoom != 1f
}

/**
 * Keeps the CAD view up to date by asking the engine to render it.
 *
 * This is the CAD counterpart of the Blender preview: the picture on screen is always the
 * engine's own frame, so there is one camera and one scene, and the app never renders the model
 * itself.
 *
 * Three things make it behave under a finger rather than fighting it:
 *
 *  - **Requests coalesce.** Only the newest camera matters, so a drag arriving while a frame is
 *    rendering replaces the pending one instead of queueing behind it. Without this a slow frame
 *    turns a drag into a backlog that keeps moving after the finger stops.
 *  - **The size follows the view.** The frame is asked for at the size it is shown, so a tap maps
 *    to the pixel the engine picked at. Picking at another resolution names the wrong face.
 *  - **Startup is retried, a steady state is not.** Until the first frame lands, a failure means
 *    the engine is still binding, so it keeps asking. Afterwards a failure is reported and left to
 *    the next request - retrying forever there would hide a real fault behind a busy loop.
 */
class CadViewport(
    private val scope: CoroutineScope,
    private val client: CadPreviewClient,
    private val frameFile: File,
    /** Whether the engine has finished starting. The app knows; a frame count does not. */
    private val engineReady: () -> Boolean,
    /**
     * The viewer settings, read on every request rather than captured once.
     *
     * Live-reading is what makes a saved setting take effect on the next frame instead of the
     * next launch - and the next frame is the one the user is looking at when they change it.
     */
    private val settings: () -> CadViewerSettings = { CadViewerSettings() },
) : Closeable {

    private val _frame = MutableStateFlow<Bitmap?>(null)
    /** The engine's most recent frame, or null before the first one arrives. */
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private val _pick = MutableStateFlow<CadPick?>(null)
    /** The last surface picked, cleared once it has been said something about. */
    val pick: StateFlow<CadPick?> = _pick.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Conflated: a second request while one renders replaces it, never queues behind it. */
    private val pending = Channel<ViewRequest>(Channel.CONFLATED)
    /**
     * Drag deltas waiting to be rendered, accumulated.
     *
     * Not carried on the channel: that is conflated, so only the newest request survives
     * while a frame is in flight, and a frame at display resolution costs hundreds of
     * milliseconds. A drag is the sum of its movements, so discarding all but the last
     * one loses most of the gesture - which is exactly how it felt. Flags stay conflated
     * (a reset is a reset); deltas add up.
     */
    private val deltaLock = Any()
    private var pendingDeltas = ViewRequest()

    // One-shot requests wait here instead of on the conflated channel, which *replaces* what is
    // buffered rather than queueing it: a tap that arrived while a frame was rendering was
    // silently replaced by the next marker and never reached the engine, and the previous pick
    // stayed armed and rode along with the user's next instruction.
    private var pendingSelect: Pair<Int, Int>? = null
    private var pendingReset = false
    private var pendingOrientation: String? = null

    private val width = AtomicInteger(0)
    private val height = AtomicInteger(0)
    private var pump: Job? = null

    /** The size the frame should be rendered at. */
    fun setViewSize(w: Int, h: Int) {
        val wasWidth = width.getAndSet(w.coerceAtLeast(1))
        val wasHeight = height.getAndSet(h.coerceAtLeast(1))
        // A size is not "picked up on the next frame" unless there is one. The first frame is
        // requested when the viewport starts, which is before the view has been measured, so it
        // is rendered at the engine's own default and then left there until the first tap or
        // drag - the view opens low-resolution and letterboxed because of it. The size becoming
        // known is itself a reason to draw.
        if (pump != null && (wasWidth != width.get() || wasHeight != height.get())) {
            pending.trySend(ViewRequest())
        }
    }

    fun start() {
        if (pump != null) return
        Log.i(TAG, "starting; frame file " + frameFile.absolutePath)
        pump = scope.launch(Dispatchers.IO) {
            // The first frame frames the part; after that the camera belongs to the user. The
            // flag goes through reset(), into the accumulator renderOnce reads: sent as a field
            // on the request it was ignored - the channel says *when* to draw and the
            // accumulators say what - so the first frame kept the engine's default camera and a
            // 20 mm part arrived as a speck in the middle of the view until a preset was pressed.
            reset()
            var failedAttempts = 0
            for (request in pending) {
                _busy.value = true
                try {
                    var readyAttempts = 0
                    while (true) {
                        try {
                            renderOnce(request)
                            failedAttempts = 0
                            break
                        } catch (error: Throwable) {
                            // While the engine is starting, keep waiting - there is nothing
                            // wrong, the screen is just early. Once it reports ready, a failure
                            // is real and is reported after a couple of tries.
                            val ready = runCatching { engineReady() }.getOrDefault(false)
                            if (ready) {
                                if (_frame.value != null || readyAttempts >= READY_RETRIES) throw error
                                readyAttempts++
                            } else if (_frame.value != null) {
                                // A frame has been shown, so the engine was up and is not any
                                // more. Waiting for it to start again never ended: the strip sat
                                // on "Rendering..." over a frozen picture for good.
                                throw error
                            }
                            delay(STARTUP_RETRY_MS)
                        }
                    }
                } catch (error: Throwable) {
                    // A viewport that fails quietly is worse than one that fails loudly: the
                    // screen just stays empty and nothing says why.
                    Log.w(TAG, "frame failed: " + error.message, error)
                    // A tap, a reset or a preset that failed is waiting in the accumulators, and
                    // nothing else asks for a frame until the user touches the screen again - so
                    // it was dropped silently while the agent was still told the previous pick.
                    // Ask again a few times, then leave it for whatever the user does next.
                    if (waitingForOneShot() && failedAttempts < FAILED_RETRIES) {
                        failedAttempts++
                        delay(RETRY_AFTER_FAILURE_MS)
                        pending.trySend(ViewRequest())
                    }
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    /** Whether a tap, a reset or a preset is still waiting for the frame that failed. */
    private fun waitingForOneShot(): Boolean = synchronized(deltaLock) {
        pendingSelect != null || pendingReset || pendingOrientation != null
    }

    /** One frame. Throws when the engine is not reachable yet, or refuses. */
    private suspend fun renderOnce(request: ViewRequest) {
        // Take everything that has arrived since the last frame, in one go.
        val deltas = synchronized(deltaLock) {
            val taken = pendingDeltas
            pendingDeltas = ViewRequest()
            taken
        }
        // The one-shot flags, which no longer travel on the conflated channel.
        val effective = synchronized(deltaLock) {
            val taken = ViewRequest(
                selectX = pendingSelect?.first,
                selectY = pendingSelect?.second,
                reset = pendingReset,
                orientation = pendingOrientation,
            )
            pendingSelect = null
            pendingReset = false
            pendingOrientation = null
            taken
        }
        // A drag renders at a fraction of the display size. At the size the view is shown -
        // 1812x1700 on an unfolded phone, about three million pixels - a frame costs the
        // better part of a second, and no amount of delta accumulation makes that feel like
        // dragging. A tap cannot happen mid-drag, so a small frame never has to be mapped
        // back to view coordinates: when the finger stops, the settle frame below re-renders
        // at full size and picking is pixel-exact again.
        //
        // The cap is on the longest side, and the deltas are scaled by the same factor. Both
        // halves are load-bearing:
        //
        //  * Capping the longest side (the drag quality setting, 640 px by default) turns
        //    1812x1700 into 640x640 - a different aspect,
        //    which the engine honours (ToPixMap adjusts the view to the dump size by default).
        //    The frame is drawn with ContentScale.Fit, so the picture shrinks into a square in
        //    the middle of the view while the finger moves, and comes back on the settle frame.
        //  * The arcball turns about the *smaller* viewport dimension. Measured on the phone:
        //    a 100-pixel drag is 9.9 degrees at 1812x1700, 16.7 at 1080x1900 and 28.1 at
        //    640x640. Deltas measured on the display, applied to a drag-sized render, therefore
        //    turn the model min(display) / min(render) further than the same drag at rest -
        //    1.7x on the phone this was measured on, more on a larger view. That is what "the
        //    drag is far too sensitive" was, and it is not a taste setting: the gesture has to
        //    be the same one at every render size.
        //
        // A tap wins over a drag that shares its drain window: the engine picks at the size it
        // rendered, and the coordinates the app sends are display pixels.
        val dragging = deltas.hasMotion && effective.selectX == null
        val displayWidth = width.get()
        val displayHeight = height.get()
        val viewer = settings()
        val longest = maxOf(1, maxOf(displayWidth, displayHeight))
        val dragScale = if (dragging) {
            minOf(1f, viewer.dragQuality.toFloat() / longest.toFloat())
        } else {
            1f
        }
        // The still frame gets its own cap: it is the one left on the glass to be looked at, and
        // a full-resolution frame of a heavy part is the difference between a viewer and a slide
        // show. Zero means no cap, which is what the app has always done.
        val stillScale = if (!dragging && viewer.idleQuality > 0) {
            minOf(1f, viewer.idleQuality.toFloat() / longest.toFloat())
        } else {
            1f
        }
        // A size of 0 means the view has not been laid out yet, and is passed on as 0: the
        // engine reads that as "no size given" and renders its own default, where 1 would be
        // a one-pixel frame.
        // The engine clamps each axis on its own to RENDER_MIN..RENDER_MAX and renders that, so a
        // larger request came back as a smaller frame. Asking for the size the engine will really
        // render is what makes the tap mapping below exact; the frame is then letterboxed by
        // ContentScale.Fit, which the mapping also knows about.
        val renderScale = dragScale * stillScale
        val renderWidth = if (displayWidth <= 0) 0 else
            (displayWidth * renderScale).roundToInt()
                .coerceIn(CadPreviewClient.FRAME_MIN, CadPreviewClient.FRAME_MAX)
        val renderHeight = if (displayHeight <= 0) 0 else
            (displayHeight * renderScale).roundToInt()
                .coerceIn(CadPreviewClient.FRAME_MIN, CadPreviewClient.FRAME_MAX)
        val fit = frameFit(displayWidth, displayHeight, renderWidth, renderHeight)
        val tapX = effective.selectX
        val tapY = effective.selectY
        val tap = if (tapX != null && tapY != null) fit.toFrame(tapX.toFloat(), tapY.toFloat()) else null

        val picked = try {
            client.view(
                into = frameFile,
            // Scaled with the render: a delta is a distance on the display, and what the
            // engine turns and pans is a distance in the frame it just rendered.
            // Degrees, deliberately unscaled: the engine's camera is an angle, so a turn is the
            // same turn whatever size the frame is rendered at.
            turnYaw = deltas.turnYaw,
            turnPitch = deltas.turnPitch,
            panDx = deltas.panDx / fit.scale,
            panDy = deltas.panDy / fit.scale,
            zoom = deltas.zoom,
            // Into the frame's own pixels, letterbox and all: the engine picks at the size and at
            // the place in the frame the picture really occupies. A view pixel sent unscaled, or
            // scaled by a size the engine then clamped, named a face the finger was nowhere near.
            selectX = tap?.first?.roundToInt(),
            selectY = tap?.second?.roundToInt(),
            reset = effective.reset,
            shaded = viewer.shaded,
            orientation = effective.orientation,
            // Supersampling only for the frame that settles: while a finger is moving, four
            // times the pixels is four times the lag, and the smoothing is invisible anyway.
            antialiasing = viewer.antialiasing && !dragging,
            width = renderWidth,
            height = renderHeight,
            )
        } catch (timeout: CadEngineTimeoutException) {
            // The motion is deliberately NOT put back. The engine queues what it accepted and
            // runs it whether or not this client is still here, so the turn, pan or zoom is
            // already on its way; restoring it and sending it again applied one drag twice. A
            // tap, a reset or a preset is absolute - asking again lands on the same state.
            restoreFlags(effective)
            throw timeout
        } catch (error: Throwable) {
            // The motion was taken and no frame came of it, so it goes back: a failed frame must
            // not eat the drag that asked for it. The engine's busy guard makes this routine
            // rather than rare, so without this the part lags the finger by exactly that motion.
            restoreMotion(deltas, effective)
            throw error
        }
        val bitmap = client.readFrame(frameFile)
        if (bitmap == null) {
            Log.w(TAG, "the engine wrote no readable frame at " + frameFile.absolutePath)
        } else {
            _frame.value = bitmap
        }
        // A tap with nothing under it clears any standing pick, so a stale surface cannot be
        // attached to what the user says next.
        // The engine answers in the frame it drew; that frame is stretched over the view, so the
        // point the user tapped is this one divided back. Unscaled, the pick's own coordinates -
        // which go into the prompt the agent reads - named a pixel the user never touched.
        if (tap != null) {
            _pick.value = picked?.let {
                val (viewX, viewY) = fit.toView(it.x.toFloat(), it.y.toFloat())
                it.copy(x = viewX.roundToInt(), y = viewY.roundToInt())
            }
        }
        // Settle: that frame was coarse, so ask for one at full size. Only after a frame that
        // moved, or the loop would never idle.
        if (dragging) pending.trySend(ViewRequest())
    }

    fun orbit(dx: Float, dy: Float) {
        // A drag turns a turntable: a fixed number of degrees per pixel of travel, the same
        // mapping the plate's own viewer uses (ModellingPreview.DegreesPerPixel), so both viewers
        // answer a finger the same way. Accumulated in degrees rather than pixels because the
        // camera is an angle - the engine is told how far to turn, not how far the finger moved,
        // and the supersampled render size therefore never enters into it.
        //
        // Yaw falls, pitch rises, for a rightward and a downward drag: the same signs the plate's
        // viewer uses, which also keeps the arcball's old promise that pulling the front of a part
        // downwards shows more of its top.
        synchronized(deltaLock) {
            pendingDeltas = pendingDeltas.copy(
                turnYaw = pendingDeltas.turnYaw - dx * DEGREES_PER_PIXEL,
                turnPitch = pendingDeltas.turnPitch + dy * DEGREES_PER_PIXEL,
            )
        }
        pending.trySend(ViewRequest())
    }

    fun pan(dx: Float, dy: Float) {
        synchronized(deltaLock) {
            pendingDeltas = pendingDeltas.copy(
                panDx = pendingDeltas.panDx + dx,
                panDy = pendingDeltas.panDy + dy,
            )
        }
        pending.trySend(ViewRequest())
    }

    /** Puts back what a failed frame took: motion adds up again, and a flag waits for the retry. */
    private fun restoreMotion(deltas: ViewRequest, flags: ViewRequest) {
        synchronized(deltaLock) {
            pendingDeltas = pendingDeltas.copy(
                turnYaw = pendingDeltas.turnYaw + deltas.turnYaw,
                turnPitch = pendingDeltas.turnPitch + deltas.turnPitch,
                panDx = pendingDeltas.panDx + deltas.panDx,
                panDy = pendingDeltas.panDy + deltas.panDy,
                zoom = pendingDeltas.zoom * deltas.zoom,
            )
            restoreFlagsLocked(flags)
        }
    }

    /**
     * Puts back only the one-shots: a tap, a reset or a preset is absolute, not a delta.
     *
     * For a request that timed out, where the engine may already have applied the motion.
     */
    private fun restoreFlags(flags: ViewRequest) {
        synchronized(deltaLock) { restoreFlagsLocked(flags) }
    }

    private fun restoreFlagsLocked(flags: ViewRequest) {
        if (flags.selectX != null) pendingSelect = flags.selectX to (flags.selectY ?: 0)
        if (flags.reset) pendingReset = true
        if (flags.orientation != null) pendingOrientation = flags.orientation
    }

    fun zoomBy(factor: Float) {
        // Multiplied, not added: a zoom of 1 is no change, and two zooms compose.
        synchronized(deltaLock) {
            pendingDeltas = pendingDeltas.copy(zoom = pendingDeltas.zoom * factor)
        }
        pending.trySend(ViewRequest())
    }

    fun select(x: Int, y: Int) {
        synchronized(deltaLock) { pendingSelect = x to y }
        pending.trySend(ViewRequest())
    }

    fun reset() {
        synchronized(deltaLock) { pendingReset = true }
        pending.trySend(ViewRequest())
    }

    /** Iso, Front, Top, Right: a named view re-frames the part, which is what a preset means. */
    fun setOrientation(name: String) {
        synchronized(deltaLock) { pendingOrientation = name }
        pending.trySend(ViewRequest())
    }

    /**
     * Hands the engine the viewer's appearance, then redraws.
     *
     * The camera is left alone: these are settings about how the view is drawn, and throwing
     * away the angle the user arranged to apply them would be a poor trade.
     */
    fun applyViewerSettings(settings: CadViewerSettings) {
        // Dispatchers.IO, like the frame pump: this talks to the engine over a socket, and a
        // blocking connect on the main dispatcher is a NetworkOnMainThreadException - which is
        // how a settings change reported itself as "refused: null" while nothing was wrong.
        scope.launch(Dispatchers.IO) {
            runCatching { client.viewerSettings(settings) }
                .onFailure {
                    Log.w(TAG, "viewer settings call failed: " + it::class.java.simpleName +
                        " / " + it.message + " / cause " + it.cause)
                }
            refresh()
        }
    }

    /**
     * Redraws with the current settings, without moving the camera.
     *
     * What a saved viewer setting needs: the camera the user has arranged is theirs, and changing
     * the frame size or the shading should not throw it away.
     */
    fun refresh() {
        pending.trySend(ViewRequest())
    }

    /** Consumed once it has been attached to something the user said. */
    fun clearPick() {
        _pick.value = null
    }

    /**
     * Hands a file to the engine, replacing the scene, and frames what comes back.
     *
     * Suspending and on IO: parsing a STEP or a large STL takes real time, and it must not
     * hold the frame loop - the pending channel is conflated, so a reset queued now simply
     * becomes the next thing rendered once the import finishes.
     *
     * @return the name the engine filed the shape under, or null when it refused.
     */
    /**
     * Loads a file into the engine's scene, the way an agent's export would.
     *
     * Fire and forget: the frame that follows is the answer, and importPart already frames the
     * part - the camera belonged to whatever was in the scene before, and that part is gone.
     */
    fun loadPart(file: java.io.File) {
        scope.launch {
            runCatching { importPart(file) }
                .onSuccess { Log.i(TAG, "loaded " + file.name + " into the CAD engine") }
                .onFailure { Log.w(TAG, "could not load " + file.name + ": " + it.message) }
        }
    }

    suspend fun importPart(file: java.io.File): String? = withContext(Dispatchers.IO) {
        val name = try {
            client.importFile(file = file, name = file.nameWithoutExtension, replace = true)
        } catch (error: Throwable) {
            Log.w(TAG, "import failed: " + error.message, error)
            throw error
        }
        // Frame it. The camera belonged to whatever was here before, and that part is gone.
        reset()
        name
    }

    override fun close() {
        pump?.cancel()
        pump = null
        pending.close()
        client.close()
    }
}
