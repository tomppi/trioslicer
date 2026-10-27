package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brush's decide-first gesture rule, extracted so it can be tested without a GL context.
 *
 * This replaces the old rule for plain paint modes: an armed brush used to take every
 * single-finger drag as a stroke, so the camera could not be orbited while painting and the user
 * was stuck until they switched the brush off. A finger-down is now a question - on the part it
 * paints, beside it the camera orbits - and only a hit starts a stroke.
 */
class PaintStrokeGateTest {

    private val gate = PaintStrokeGate()

    @Test
    fun aHitOnTheModelStartsTheStrokeAndTheFirstSamplePaints() {
        val token = gate.begin()
        assertTrue(gate.deciding)
        assertFalse("nothing is painted while the pick is in flight", gate.dragging)
        var orbited = false
        val started = gate.probe(hit = true, token = token) { _, _ -> orbited = true }
        assertTrue("the probe started a stroke", started)
        assertTrue(gate.dragging)
        assertFalse(gate.deciding)
        assertFalse("a hit never moves the camera", orbited)
    }

    @Test
    fun aMissBesideTheModelOrbitsInsteadOfPainting() {
        val token = gate.begin()
        gate.accumulate(12f, -5f)
        var orbitDx = 0f
        var orbitDy = 0f
        val started = gate.probe(hit = false, token = token) { dx, dy ->
            orbitDx = dx
            orbitDy = dy
        }
        assertFalse("a miss paints nothing", started)
        assertFalse(gate.dragging)
        assertFalse(gate.deciding)
        assertEquals(12f, orbitDx, 0f)
        assertEquals(-5f, orbitDy, 0f)
    }

    @Test
    fun movementWhileTheQuestionIsInFlightIsHeldAndReplayedExactlyOnce() {
        val token = gate.begin()
        gate.accumulate(4f, 0f)
        gate.accumulate(-1f, 3f)
        var orbits = 0
        gate.probe(hit = false, token = token) { _, _ -> orbits++ }
        assertEquals(1, orbits)
        // The accumulator is drained, so a second answer cannot replay the same movement.
        gate.probe(hit = false, token = token) { _, _ -> orbits++ }
        assertEquals(1, orbits)
    }

    @Test
    fun aStaleAnswerCannotStartAStrokeAfterTheGestureEndedOrWasCancelled() {
        val first = gate.begin()
        gate.cancelDecision()
        assertFalse(gate.probe(hit = true, token = first) { _, _ -> })
        assertFalse("the cancelled question can never paint later", gate.dragging)

        val second = gate.begin()
        gate.end()
        assertFalse(gate.probe(hit = true, token = second) { _, _ -> })
        assertFalse(gate.dragging)
    }

    @Test
    fun twoFingersCancelAnUndecidedGestureButNeverABegunStroke() {
        // Undecided: two fingers mean the gesture was the camera's, so the question is dropped.
        gate.begin()
        assertTrue(gate.cancelDecision())
        assertFalse(gate.deciding)

        // A stroke that already began keeps its finger while the second finger pans/zooms.
        val token = gate.begin()
        assertTrue(gate.probe(hit = true, token = token) { _, _ -> })
        assertFalse(gate.cancelDecision())
        assertTrue("the begun stroke survives the second finger", gate.dragging)
    }

    @Test
    fun endClearsTheGestureAndBumpsTheToken() {
        val token = gate.begin()
        gate.probe(hit = true, token = token) { _, _ -> }
        gate.end()
        assertFalse(gate.deciding)
        assertFalse(gate.dragging)
        assertTrue(token < gate.generation)
        assertFalse(gate.probe(hit = true, token = token) { _, _ -> })
    }

    /**
     * The assertion this replaces: "while a paint mode is armed, a one-finger drag is a paint
     * stroke". That rule is gone - the first pick decides, and the same gesture beside the model
     * is an orbit - while the stroke itself still follows the finger once it began.
     */
    @Test
    fun anArmedBrushNoLongerOwnsEverySingleFingerDrag() {
        val token = gate.begin()
        gate.accumulate(30f, 0f)
        assertTrue("the drag starts undecided, not as paint", gate.deciding && !gate.dragging)
        var orbited = false
        gate.probe(hit = false, token = token) { _, _ -> orbited = true }
        assertTrue("and beside the model it becomes the camera's drag", orbited)
    }
}
