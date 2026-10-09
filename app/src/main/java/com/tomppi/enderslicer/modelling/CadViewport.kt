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
private data class ViewRequest(
    val orbitDx: Float = 0f,
    val orbitDy: Float = 0f,
    val panDx: Float = 0f,
    val panDy: Float = 0f,
    val zoom: Float = 1f,
    /** Degrees about the view's own axis, from the two-finger twist. */
    val roll: Float = 0f,
    val selectX: Int? = null,
    val selectY: Int? = null,
    val reset: Boolean = false,
    /** A named view - "front", "top" - which re-frames the part as well as turning the camera. */
    val orientation: String? = null,
) {
    /** Whether anything in here moves the camera. Picking and flags do not. */
    val hasMotion: Boolean
        get() = orbitDx != 0f || orbitDy != 0f || panDx != 0f || panDy != 0f || zoom != 1f || roll != 0f
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
            // The first frame frames the part; after that the camera belongs to the user.
            pending.trySend(ViewRequest(reset = true))
            for (request in pending) {
                _busy.value = true
                try {
                    var readyAttempts = 0
                    while (true) {
                        try {
                            renderOnce(request)
                            break
                        } catch (error: Throwable) {
                            // While the engine is starting, keep waiting - there is nothing
                            // wrong, the screen is just early. Once it reports ready, a failure
                            // is real and is reported after a couple of tries.
                            val ready = runCatching { engineReady() }.getOrDefault(false)
                            if (ready) {
                                if (_frame.value != null || readyAttempts >= READY_RETRIES) throw error
                                readyAttempts++
                            }
                            delay(STARTUP_RETRY_MS)
                        }
                    }
                } catch (error: Throwable) {
                    // A viewport that fails quietly is worse than one that fails loudly: the
                    // screen just stays empty and nothing says why.
                    Log.w(TAG, "frame failed: " + error.message, error)
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    /** One frame. Throws when the engine is not reachable yet, or refuses. */
    private suspend fun renderOnce(request: ViewRequest) {
        // Take everything that has arrived since the last frame, in one go.
        val deltas = synchronized(deltaLock) {
            val taken = pendingDeltas
            pendingDeltas = ViewRequest()
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
        val dragging = deltas.hasMotion && request.selectX == null
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
        val renderScale = dragScale * stillScale
        val renderWidth = (displayWidth * renderScale).roundToInt()
        val renderHeight = (displayHeight * renderScale).roundToInt()

        val picked = client.view(
            into = frameFile,
            // Scaled with the render: a delta is a distance on the display, and what the
            // engine turns and pans is a distance in the frame it just rendered.
            orbitDx = deltas.orbitDx * dragScale,
            orbitDy = deltas.orbitDy * dragScale,
            panDx = deltas.panDx * dragScale,
            panDy = deltas.panDy * dragScale,
            zoom = deltas.zoom,
            roll = request.roll,
            selectX = request.selectX,
            selectY = request.selectY,
            reset = request.reset,
            shaded = viewer.shaded,
            orientation = request.orientation,
            // Supersampling only for the frame that settles: while a finger is moving, four
            // times the pixels is four times the lag, and the smoothing is invisible anyway.
            antialiasing = viewer.antialiasing && !dragging,
            width = renderWidth,
            height = renderHeight,
        )
        val bitmap = client.readFrame(frameFile)
        if (bitmap == null) {
            Log.w(TAG, "the engine wrote no readable frame at " + frameFile.absolutePath)
        } else {
            _frame.value = bitmap
        }
        // A tap with nothing under it clears any standing pick, so a stale surface cannot be
        // attached to what the user says next.
        if (request.selectX != null) _pick.value = picked
        // Settle: that frame was coarse, so ask for one at full size. Only after a frame that
        // moved, or the loop would never idle.
        if (dragging) pending.trySend(ViewRequest())
    }

    fun orbit(dx: Float, dy: Float) {
        synchronized(deltaLock) {
            pendingDeltas = pendingDeltas.copy(
                orbitDx = pendingDeltas.orbitDx + dx,
                orbitDy = pendingDeltas.orbitDy + dy,
            )
        }
        pending.trySend(ViewRequest())
    }

    /**
     * Rolls the view about its own axis: the two-finger twist, as photo software has.
     *
     * Momentary rather than accumulated - a twist is an angle, not a distance travelled - so the
     * engine is given the degrees from this event and nothing else.
     */
    fun rotate(degrees: Float) {
        if (degrees == 0f) return
        pending.trySend(ViewRequest(roll = degrees))
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

    fun zoomBy(factor: Float) {
        // Multiplied, not added: a zoom of 1 is no change, and two zooms compose.
        synchronized(deltaLock) {
            pendingDeltas = pendingDeltas.copy(zoom = pendingDeltas.zoom * factor)
        }
        pending.trySend(ViewRequest())
    }

    fun select(x: Int, y: Int) {
        pending.trySend(ViewRequest(selectX = x, selectY = y))
    }

    fun reset() {
        pending.trySend(ViewRequest(reset = true))
    }

    /** Iso, Front, Top, Right: a named view re-frames the part, which is what a preset means. */
    fun setOrientation(name: String) {
        pending.trySend(ViewRequest(orientation = name))
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
