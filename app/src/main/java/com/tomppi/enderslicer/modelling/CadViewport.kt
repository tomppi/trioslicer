package com.tomppi.enderslicer.modelling

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
 * engine's own frame, so there is one camera and one scene, and the app never renders the
 * model itself.
 *
 * Two things make it behave under a finger rather than fighting it:
 *
 *  - **Requests coalesce.** Only the newest camera matters, so a drag that arrives while a
 *    frame is rendering replaces the pending one instead of queueing behind it. Without this
 *    a slow frame turns a drag into a backlog that keeps moving after the finger stops.
 *  - **The size follows the view.** The frame is asked for at the size it is shown, so a tap
 *    maps to the pixel the engine picked at. Picking at a different resolution than the one
 *    displayed would name the wrong face.
 */
class CadViewport(
    private val scope: CoroutineScope,
    private val client: CadPreviewClient,
    private val frameFile: File,
) : Closeable {

    private val _frame = MutableStateFlow<Bitmap?>(null)
    /** The engine's most recent frame, or null before the first one arrives. */
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private val _pick = MutableStateFlow<CadPick?>(null)
    /** The last surface picked, cleared once it has been said something about. */
    val pick: StateFlow<CadPick?> = _pick.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Depth only counts 0 or 1: a second request while one renders replaces it, never queues. */
    private val pending = Channel<ViewRequest>(Channel.CONFLATED)
    private val width = AtomicInteger(0)
    private val height = AtomicInteger(0)
    private var pump: Job? = null

    /** The size the frame should be rendered at. Changes are picked up on the next frame. */
    fun setViewSize(w: Int, h: Int) {
        width.set(w.coerceAtLeast(1))
        height.set(h.coerceAtLeast(1))
    }

    fun start() {
        if (pump != null) return
        pump = scope.launch(Dispatchers.IO) {
            // The first frame frames the part; after that the camera belongs to the user.
            pending.trySend(ViewRequest(reset = true))
            for (request in pending) {
                _busy.value = true
                try {
                    val w = width.get()
                    val h = height.get()
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
                        width = w,
                        height = h,
                    )
                    client.readFrame(frameFile)?.let { _frame.value = it }
                    // A tap with nothing under it clears any standing pick, so a stale
                    // surface cannot be attached to what the user says next.
                    if (request.selectX != null) _pick.value = picked
                } catch (error: Throwable) {
                    // One failed frame is not a reason to take the screen down; keep the
                    // last good one and let the next request try again.
                } finally {
                    _busy.value = false
                }
            }
        }
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
