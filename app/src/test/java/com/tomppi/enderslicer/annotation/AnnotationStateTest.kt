package com.tomppi.enderslicer.annotation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interaction is tap-to-place and drag-to-adjust, edited a segment at a
 * time. These cover the rules that make that coherent: a segment needs both
 * ends before it can be locked, locking continues from the point just
 * committed, and only the first segment of a series owns its start.
 */
class AnnotationStateTest {

    private fun surface(x: Float, y: Float, z: Float) =
        Triple(Point3(x, y, z), AnnotationAnchor.SURFACE, 7)

    private fun AnnotationState.tapAt(x: Float, y: Float, z: Float): Boolean {
        val (p, anchor, face) = surface(x, y, z)
        return tap(p, anchor, face)
    }

    @Test
    fun startsEmpty() {
        val state = AnnotationState()
        assertTrue(state.isEmpty)
        assertTrue(state.chains.isEmpty())
        assertNull(state.pendingStart)
        assertNull(state.pendingEnd)
        assertFalse(state.canLockSegment)
    }

    @Test
    fun firstTapSetsStartAndSecondSetsEnd() {
        val state = AnnotationState()

        assertTrue(state.tapAt(0f, 0f, 0f))
        assertNotNull(state.pendingStart)
        assertNull(state.pendingEnd)
        assertFalse("one point is not a lockable line", state.canLockSegment)

        assertTrue(state.tapAt(10f, 0f, 0f))
        assertTrue(state.canLockSegment)
    }

    @Test
    fun aThirdTapIsRefusedUntilTheLineIsLocked() {
        val state = AnnotationState()
        state.tapAt(0f, 0f, 0f)
        state.tapAt(10f, 0f, 0f)

        assertFalse("the line must be locked before another begins", state.tapAt(20f, 0f, 0f))

        state.lockSegment()
        assertTrue("after locking, the next tap extends the series", state.tapAt(20f, 0f, 0f))
    }

    @Test
    fun lockingContinuesFromThePointJustCommitted() {
        val state = AnnotationState()
        state.tapAt(0f, 0f, 0f)
        state.tapAt(10f, 0f, 0f)
        assertTrue(state.lockSegment())

        // The next segment begins at the point just committed, and it is no
        // longer the user's to place or to drag.
        assertNull(state.pendingStart)
        assertEquals(2, state.currentSeries.size)
        assertNotNull(state.segmentStart)
        assertEquals(10f, state.segmentStart!!.position.x, 1e-4f)
        assertNull("a committed start is not grabbable", state.startHandle)
    }

    @Test
    fun lockingASeriesStoresItAndStartsFresh() {
        val state = AnnotationState()
        state.tapAt(0f, 0f, 0f)
        state.tapAt(10f, 0f, 0f)
        state.lockSegment()
        state.tapAt(10f, 10f, 0f)

        val chain = state.lockSeries()
        assertNotNull(chain)
        assertEquals(1, state.chains.size)
        assertEquals(3, state.chains[0].points.size)
        assertTrue(state.currentSeries.isEmpty())
        assertNull(state.pendingStart)
        assertNull(state.pendingEnd)
    }

    @Test
    fun aSeriesWithOnePointIsDiscardedRatherThanStored() {
        val state = AnnotationState()
        state.tapAt(0f, 0f, 0f)

        assertNull(state.lockSeries())
        assertTrue("a single point is not geometry", state.chains.isEmpty())
        assertTrue(state.isEmpty)
    }

    @Test
    fun onlyTheFirstSegmentOfASeriesOwnsItsStart() {
        val state = AnnotationState()
        state.tapAt(0f, 0f, 0f)
        state.tapAt(10f, 0f, 0f)
        assertNotNull("the first segment can move both ends", state.startHandle)

        state.lockSegment()
        state.tapAt(10f, 10f, 0f)
        // Once committed, the start belongs to the series: the next segment
        // still begins there, but the user can no longer drag it.
        assertNotNull("the segment still starts there", state.segmentStart)
        assertNull("but it is not grabbable", state.startHandle)
        assertNull(state.pendingStart)

        // And moving it is refused: there is no start handle to move.
        state.beginAdjust(SegmentEnd.START, Point3(0f, 0f, 0f))
        assertNull("a committed start is not adjustable", state.adjusting)
    }

    @Test
    fun adjustingSlidesTheHandleAtTheDistanceTheDragStartedAt() {
        val state = AnnotationState()
        state.tapAt(0f, 0f, 0f)
        state.tapAt(10f, 0f, 0f)

        val camera = Point3(0f, 0f, 100f)
        val captured = camera.distanceTo(Point3(10f, 0f, 0f))
        state.beginAdjust(SegmentEnd.END, camera)
        assertEquals(SegmentEnd.END, state.adjusting)

        // The end lands on the finger's ray, still at the distance the drag
        // began at - that is what keeps a lateral correction from also moving
        // the point nearer or further.
        state.moveAlongRay(camera, Point3(0f, 1f, 0f))
        assertEquals("depth is held", captured, camera.distanceTo(state.pendingEnd!!.position), 1e-2f)
        assertEquals("sliding detaches it from the surface", AnnotationAnchor.PLANE, state.pendingEnd!!.anchor)

        state.endAdjust()
        assertNull(state.adjusting)
    }

    @Test
    fun movingWithoutACapturedDepthDoesNothing() {
        val state = AnnotationState()
        state.tapAt(0f, 0f, 0f)
        state.tapAt(10f, 0f, 0f)

        assertFalse(state.moveAlongRay(Point3(0f, 0f, 0f), Point3(1f, 0f, 0f)))
    }

    @Test
    fun snappingReanchorsOntoTheSurface() {
        val state = AnnotationState()
        state.tapAt(0f, 0f, 0f)
        state.tapAt(10f, 0f, 0f)

        state.snap(SegmentEnd.END, Point3(5f, 5f, 5f), 42)
        assertEquals(AnnotationAnchor.SURFACE, state.pendingEnd!!.anchor)
        assertEquals(42, state.pendingEnd!!.faceIndex)
    }

    @Test
    fun undoUnwindsFromTheNewestThingFirst() {
        val state = AnnotationState()
        state.tapAt(0f, 0f, 0f)
        state.tapAt(10f, 0f, 0f)

        assertTrue("the uncommitted end goes first", state.undo())
        assertNull(state.pendingEnd)
        assertNotNull(state.pendingStart)

        assertTrue(state.undo())
        assertNull(state.pendingStart)

        state.tapAt(0f, 0f, 0f)
        state.tapAt(10f, 0f, 0f)
        state.lockSegment()
        assertTrue("then the committed point", state.undo())
        assertEquals(1, state.currentSeries.size)
    }

    @Test
    fun thicknessIsClampedToTheSliderRange() {
        val state = AnnotationState()
        state.thicknessPx = 1f
        assertEquals(AnnotationState.MIN_THICKNESS_PX, state.thicknessPx, 1e-4f)
        state.thicknessPx = 999f
        assertEquals(AnnotationState.MAX_THICKNESS_PX, state.thicknessPx, 1e-4f)
    }

    @Test
    fun restoreKeepsIdsAndContinuesPastThem() {
        val state = AnnotationState()
        state.restore(
            listOf(
                AnnotationChain(4, AnnotationKind.PATH, listOf(
                    AnnotationPoint(Point3(0f, 0f, 0f), AnnotationAnchor.SURFACE, 0),
                    AnnotationPoint(Point3(1f, 0f, 0f), AnnotationAnchor.SURFACE, 1),
                )),
            ),
        )
        assertEquals(1, state.chains.size)

        // A series saved after a restore must not reuse an id.
        state.tapAt(0f, 0f, 0f)
        state.tapAt(1f, 1f, 0f)
        val next = state.lockSeries()
        assertEquals(5, next!!.id)
    }

    @Test
    fun handlesAreEmptyUntilSomethingIsPlaced() {
        val state = AnnotationState()
        assertTrue(state.handles().isEmpty())

        state.tapAt(0f, 0f, 0f)
        assertEquals("the start is grabbable before the end exists", 1, state.handles().size)

        state.tapAt(10f, 0f, 0f)
        assertEquals("both ends are grabbable", 2, state.handles().size)
    }
}
