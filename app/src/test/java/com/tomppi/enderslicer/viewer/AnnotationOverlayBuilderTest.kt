package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.annotation.AnnotationAnchor
import com.tomppi.enderslicer.annotation.AnnotationChain
import com.tomppi.enderslicer.annotation.AnnotationKind
import com.tomppi.enderslicer.annotation.AnnotationPoint
import com.tomppi.enderslicer.annotation.AnnotationState
import com.tomppi.enderslicer.annotation.Point3
import com.tomppi.enderslicer.annotation.SegmentEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay is a GL_LINES buffer split into two ranges: the committed and
 * pending segments, then the handle crosses. The split index is what the
 * renderer uses to colour each half, so an off-by-one there draws the markers
 * in the line colour or drops them entirely.
 */
class AnnotationOverlayBuilderTest {

    private fun build(state: AnnotationState, marker: Float = 1f) =
        AnnotationOverlayBuilder.build(state, marker)

    private fun AnnotationState.tapAt(x: Float, y: Float, z: Float) =
        tap(Point3(x, y, z), AnnotationAnchor.PLANE)

    @Test
    fun emptyStateProducesNothingToDraw() {
        val overlay = build(AnnotationState())
        assertTrue(overlay.isEmpty)
        assertEquals(0, overlay.lineVertexCount)
        assertEquals(0, overlay.markerVertexCount)
        assertEquals(0, overlay.totalVertexCount)
        assertTrue(overlay.handles.isEmpty())
    }

    @Test
    fun aLockedTwoPointSeriesIsOneSegmentWithNoHandles() {
        val state = AnnotationState()
        state.tapAt(0f, 0f, 0f)
        state.tapAt(10f, 0f, 0f)
        state.lockSeries()

        val overlay = build(state)
        assertEquals("one segment is two vertices", 2, overlay.lineVertexCount)
        assertEquals("a committed series has nothing to grab", 0, overlay.markerVertexCount)
        assertEquals(6, overlay.vertices.size)
    }

    @Test
    fun aLockedThreePointSeriesIsTwoSegments() {
        val state = AnnotationState()
        state.tapAt(0f, 0f, 0f)
        state.tapAt(10f, 0f, 0f)
        state.lockSegment()
        state.tapAt(10f, 10f, 0f)
        state.lockSeries()

        val overlay = build(state)
        assertEquals(4, overlay.lineVertexCount)
    }

    @Test
    fun aPendingSegmentDrawsTheLineAndBothHandles() {
        val state = AnnotationState()
        state.tapAt(0f, 0f, 0f)
        state.tapAt(10f, 0f, 0f)

        val overlay = build(state, marker = 2f)
        assertEquals("the segment itself", 2, overlay.lineVertexCount)
        assertEquals("both ends are grabbable, three axes each", 12, overlay.markerVertexCount)
        assertEquals(14, overlay.totalVertexCount)
        assertEquals(2, overlay.handles.size)
        assertEquals(SegmentEnd.START, overlay.handles[0].first)
        assertEquals(SegmentEnd.END, overlay.handles[1].first)
    }

    @Test
    fun onlyTheStartHandleExistsUntilTheSecondTap() {
        val state = AnnotationState()
        state.tapAt(1f, 2f, 3f)

        val overlay = build(state)
        assertEquals("no line yet, only one point", 0, overlay.lineVertexCount)
        assertEquals("the placed end is still grabbable", 6, overlay.markerVertexCount)
        assertEquals(1, overlay.handles.size)
        assertEquals(SegmentEnd.START, overlay.handles[0].first)
    }

    @Test
    fun theSeriesBeingDrawnIsVisibleWhileItIsExtended() {
        val state = AnnotationState()
        state.tapAt(0f, 0f, 0f)
        state.tapAt(10f, 0f, 0f)
        state.lockSegment()
        state.tapAt(10f, 10f, 0f)

        val overlay = build(state)
        // The committed run plus the segment in progress.
        assertEquals(4, overlay.lineVertexCount)
        // Only the end is grabbable: the start is a committed series point.
        assertEquals(6, overlay.markerVertexCount)
        assertEquals(1, overlay.handles.size)
        assertEquals(SegmentEnd.END, overlay.handles[0].first)
    }

    @Test
    fun aClosedRestoredChainDrawsItsClosingSegment() {
        val state = AnnotationState()
        state.restore(
            listOf(
                AnnotationChain(
                    id = 1,
                    kind = AnnotationKind.REGION,
                    points = listOf(
                        AnnotationPoint(Point3(0f, 0f, 0f), AnnotationAnchor.PLANE),
                        AnnotationPoint(Point3(10f, 0f, 0f), AnnotationAnchor.PLANE),
                        AnnotationPoint(Point3(10f, 10f, 0f), AnnotationAnchor.PLANE),
                    ),
                    closed = true,
                ),
            ),
        )

        val overlay = build(state)
        assertEquals("three sides of a triangle", 6, overlay.lineVertexCount)
    }

    @Test
    fun thicknessTravelsWithTheGeometry() {
        val state = AnnotationState()
        state.thicknessPx = 11f

        assertEquals(11f, build(state).thicknessPx, 1e-4f)
    }
}
