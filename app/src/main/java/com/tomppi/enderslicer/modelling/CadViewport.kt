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
import java.io.Closeable
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

/** One request to the engine's camera, or a tap for it to pick at. */
private data class ViewRequest(
    val orbitDx: Float = 0f,
    val orbitDy: Float = 0f,
    val panDx: Float = 0f,
    val panDy: Float = 0f,
    val zoom: Float = 1f,
    val selectX: Int? = null,
    val selectY: Int? = null,
    val reset: Boolean = false,
)

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
    private val width = AtomicInteger(0)
    private val height = AtomicInteger(0)
    private var pump: Job? = null

    /** The size the frame should be rendered at. Picked up on the next frame. */
    fun setViewSize(w: Int, h: Int) {
        width.set(w.coerceAtLeast(1))
        height.set(h.coerceAtLeast(1))
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
        val picked = client.view(
            into = frameFile,
            orbitDx = request.orbitDx,
            orbitDy = request.orbitDy,
            panDx = request.panDx,
            panDy = request.panDy,
            zoom = request.zoom,
            selectX = request.selectX,
            selectY = request.selectY,
            reset = request.reset,
            width = width.get(),
            height = height.get(),
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
    }

    fun orbit(dx: Float, dy: Float) {
        pending.trySend(ViewRequest(orbitDx = dx, orbitDy = dy))
    }

    fun pan(dx: Float, dy: Float) {
        pending.trySend(ViewRequest(panDx = dx, panDy = dy))
    }

    fun zoomBy(factor: Float) {
        pending.trySend(ViewRequest(zoom = factor))
    }

    fun select(x: Int, y: Int) {
        pending.trySend(ViewRequest(selectX = x, selectY = y))
    }

    fun reset() {
        pending.trySend(ViewRequest(reset = true))
    }

    /** Consumed once it has been attached to something the user said. */
    fun clearPick() {
        _pick.value = null
    }

    override fun close() {
        pump?.cancel()
        pump = null
        pending.close()
        client.close()
    }
}
