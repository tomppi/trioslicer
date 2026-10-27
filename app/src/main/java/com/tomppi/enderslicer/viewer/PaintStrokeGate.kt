package com.tomppi.enderslicer.viewer

/**
 * The decide-first rule for a brush gesture: a finger-down near a brush is a question, not a
 * stroke.
 *
 * Over the part the gesture paints; beside it the camera orbits. This is what makes a paint mode
 * usable without switching it off every time the view needs turning, and it is the same rule the
 * Smart Infill surface picker already used. It is extracted from [ModelSurfaceView] so the rule
 * can be tested without a GL context.
 *
 * The gesture is held while the picker (which runs off the UI thread) answers. A hit starts the
 * stroke; a miss replays the movement accumulated during the question as the orbit it would have
 * been. Two fingers cancel the question (the gesture was the camera's) but never cancel a stroke
 * that already began: a stroke keeps its finger, and the second finger pans and zooms.
 *
 * Not thread-safe: the view owns one instance and drives it from the touch thread; the picker's
 * answer comes back through [probe] on that same thread.
 */
internal class PaintStrokeGate {

    /** True while the first pick is in flight and the gesture is held, deciding. */
    var deciding: Boolean = false
        private set

    /** True once the probe answered "on the model": later samples of this gesture paint. */
    var dragging: Boolean = false
        private set

    private var accumulatedDx = 0f
    private var accumulatedDy = 0f

    /** Bumped for every new gesture and cancelled decision, so a stale answer cannot start one. */
    var generation: Int = 0
        private set

    /** Starts a gesture: the first pick answers brush-or-camera. Returns the gesture's token. */
    fun begin(): Int {
        deciding = true
        dragging = false
        accumulatedDx = 0f
        accumulatedDy = 0f
        generation++
        return generation
    }

    /** Movement while the question is in flight: held for the possible orbit replay. */
    fun accumulate(dx: Float, dy: Float) {
        if (!deciding) return
        accumulatedDx += dx
        accumulatedDy += dy
    }

    /**
     * The picker's answer for gesture [token].
     *
     * Returns true when it started a stroke (the caller reports the first sample and paints later
     * ones). Returns false for a miss - calling [orbit] once with the held movement, if any - and
     * for a stale answer (the gesture ended or two fingers cancelled it): nothing happens then,
     * which is exactly why a probe can never paint after the finger has moved on.
     */
    fun probe(hit: Boolean, token: Int, orbit: (dx: Float, dy: Float) -> Unit): Boolean {
        if (token != generation || !deciding) return false
        deciding = false
        if (hit) {
            dragging = true
            return true
        }
        val dx = accumulatedDx
        val dy = accumulatedDy
        accumulatedDx = 0f
        accumulatedDy = 0f
        if (dx != 0f || dy != 0f) orbit(dx, dy)
        return false
    }

    /**
     * A second finger arrived. An undecided gesture was the camera's all along, so the question is
     * cancelled and its later answer ignored; a stroke that already began keeps its finger (the
     * two-finger pan/zoom leaves it alone).
     */
    fun cancelDecision(): Boolean {
        if (!deciding) return false
        deciding = false
        accumulatedDx = 0f
        accumulatedDy = 0f
        generation++
        return true
    }

    /** The gesture ended: nothing is held and no later pick can start a stroke. */
    fun end() {
        deciding = false
        dragging = false
        accumulatedDx = 0f
        accumulatedDy = 0f
        generation++
    }
}
