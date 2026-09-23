package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.supportpaint.SupportPaintState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour buffer exists to make a stroke update cheap, so the properties
 * that matter are that it classifies correctly and that it rewrites - and
 * reports as dirty - only the triangles an edit touched.
 */
class PaintColorBufferTest {

    private val base = floatArrayOf(0.1f, 0.2f, 0.3f)
    private val enforcer = floatArrayOf(0.2f, 0.85f, 0.32f)
    private val blocker = floatArrayOf(0.9f, 0.25f, 0.22f)
    private val support = floatArrayOf(0.16f, 0.72f, 0.38f)
    private val load = floatArrayOf(0.95f, 0.45f, 0.10f)
    private val active = floatArrayOf(1.0f, 0.84f, 0.16f)

    /** Region bins beyond the fixed slots, one colour per bin. */
    private val regionLow = floatArrayOf(0.15f, 0.30f, 0.90f)
    private val regionHigh = floatArrayOf(0.95f, 0.90f, 0.10f)

    private fun newBuffer(triangles: Int, regions: Array<FloatArray> = emptyArray()) =
        PaintColorBuffer(
            triangleCount = triangles,
            palette = arrayOf(base, enforcer, blocker, support, load, active) + regions,
        )

    private fun PaintColorBuffer.colorAt(triangle: Int): List<Float> {
        val offset = triangle * 9
        return listOf(buffer.get(offset), buffer.get(offset + 1), buffer.get(offset + 2))
    }

    @Test
    fun startsUniformlyBaseColoured() {
        val colors = newBuffer(6)
        assertFalse(colors.hasPaint)
        for (triangle in 0 until 6) {
            assertEquals(base.toList(), colors.colorAt(triangle))
        }
        assertNull("a fresh buffer has nothing to upload", colors.takeDirtyRange())
    }

    @Test
    fun resyncClassifiesEachTriangle() {
        val colors = newBuffer(6)
        colors.resync(
            SupportPaintState(
                enforcerTriangles = setOf(1, 3),
                blockerTriangles = setOf(5),
            ),
        )
        assertTrue(colors.hasPaint)
        assertEquals(enforcer.toList(), colors.colorAt(1))
        assertEquals(enforcer.toList(), colors.colorAt(3))
        assertEquals(blocker.toList(), colors.colorAt(5))
        assertEquals(base.toList(), colors.colorAt(0))
        assertEquals(base.toList(), colors.colorAt(4))
    }

    @Test
    fun resyncReportsOnlyTheTrianglesThatChanged() {
        val colors = newBuffer(4)
        colors.resync(SupportPaintState(enforcerTriangles = setOf(2)))
        // Only triangle 2 changed classification, so only its floats are dirty:
        // this is what stops a stroke from re-uploading the whole model.
        assertEquals(2 * 9..(2 * 9 + 8), colors.takeDirtyRange())
        assertNull(colors.takeDirtyRange())
    }

    @Test
    fun resyncOfAnUnchangedStateReportsNothingDirty() {
        val colors = newBuffer(4)
        val state = SupportPaintState(enforcerTriangles = setOf(1, 3))
        colors.resync(state)
        colors.takeDirtyRange()

        colors.resync(state)
        assertNull("re-syncing identical state must not dirty anything", colors.takeDirtyRange())
    }

    @Test
    fun resyncOfAChangedStateCoversBothTheOldAndNewTriangles() {
        val colors = newBuffer(6)
        colors.resync(SupportPaintState(enforcerTriangles = setOf(1)))
        colors.takeDirtyRange()

        colors.resync(SupportPaintState(enforcerTriangles = setOf(4)))
        // Triangle 1 goes back to base and triangle 4 becomes painted.
        val dirty = colors.takeDirtyRange()
        assertEquals(1 * 9, dirty?.first)
        assertEquals(4 * 9 + 8, dirty?.last)
        assertEquals(base.toList(), colors.colorAt(1))
        assertEquals(enforcer.toList(), colors.colorAt(4))
    }

    @Test
    fun applyRewritesOnlyTheChangedTriangles() {
        val colors = newBuffer(6)
        colors.resync(SupportPaintState(enforcerTriangles = setOf(1, 3)))
        colors.takeDirtyRange()

        // Erase triangle 3 only.
        val next = SupportPaintState(enforcerTriangles = setOf(1))
        colors.apply(next, changed = setOf(3))

        assertEquals(base.toList(), colors.colorAt(3))
        assertEquals("untouched triangles keep their colour", enforcer.toList(), colors.colorAt(1))

        val dirty = colors.takeDirtyRange()
        assertEquals("only triangle 3 should be dirty", 3 * 9, dirty?.first)
        assertEquals(3 * 9 + 8, dirty?.last)
    }

    @Test
    fun applyWithNoChangesReportsNothingDirty() {
        val colors = newBuffer(4)
        val state = SupportPaintState(enforcerTriangles = setOf(2))
        colors.resync(state)
        colors.takeDirtyRange()

        colors.apply(state, changed = emptySet())
        assertNull(colors.takeDirtyRange())
    }

    @Test
    fun hasPaintClearsWhenEverythingIsErased() {
        val colors = newBuffer(4)
        colors.resync(SupportPaintState(enforcerTriangles = setOf(1)))
        assertTrue(colors.hasPaint)

        colors.apply(SupportPaintState(), changed = setOf(1))
        assertFalse(colors.hasPaint)
        assertEquals(base.toList(), colors.colorAt(1))
    }

    @Test
    fun indicesOutsideTheMeshAreIgnored() {
        val colors = newBuffer(3)
        // Out-of-range indices in the state must not throw or corrupt anything.
        colors.resync(SupportPaintState(enforcerTriangles = setOf(1, 99)))
        assertEquals(enforcer.toList(), colors.colorAt(1))
        assertTrue(colors.hasPaint)

        // Out-of-range indices in the changed set are skipped the same way, and
        // the in-range member of the set is still applied.
        colors.apply(SupportPaintState(), changed = setOf(-5, 1, 42))
        assertEquals(base.toList(), colors.colorAt(1))
    }

    @Test
    fun aBoundaryConditionColourWinsOverPaint() {
        val colors = newBuffer(4)
        colors.resync(SupportPaintState(enforcerTriangles = setOf(1, 2)))
        colors.takeDirtyRange()

        colors.resyncOverlay(supports = intArrayOf(2), loads = intArrayOf(3), active = IntArray(0))

        assertTrue(colors.hasOverlay)
        assertEquals("unclaimed paint keeps its colour", enforcer.toList(), colors.colorAt(1))
        assertEquals("the picked surface wins", support.toList(), colors.colorAt(2))
        assertEquals(load.toList(), colors.colorAt(3))
        val dirty = colors.takeDirtyRange()
        assertEquals("only the two overlaid triangles changed", 2 * 9, dirty?.first)
        assertEquals(3 * 9 + 8, dirty?.last)
    }

    @Test
    fun clearingTheOverlayRestoresThePaintUnderneath() {
        val colors = newBuffer(3)
        colors.resync(SupportPaintState(enforcerTriangles = setOf(1)))
        colors.resyncOverlay(supports = intArrayOf(1), loads = IntArray(0), active = IntArray(0))
        assertEquals(support.toList(), colors.colorAt(1))

        colors.takeDirtyRange()
        colors.resyncOverlay(IntArray(0), IntArray(0), IntArray(0))
        assertFalse(colors.hasOverlay)
        assertEquals("the paint is still there", enforcer.toList(), colors.colorAt(1))
        assertEquals("and is re-uploaded once", 1 * 9, colors.takeDirtyRange()?.first)
    }

    @Test
    fun aRepeatedOverlayIsNotDirty() {
        val colors = newBuffer(3)
        colors.resyncOverlay(supports = intArrayOf(0), loads = intArrayOf(1), active = intArrayOf(2))
        colors.takeDirtyRange()
        colors.resyncOverlay(supports = intArrayOf(0), loads = intArrayOf(1), active = intArrayOf(2))
        assertNull("an unchanged selection must not re-upload", colors.takeDirtyRange())
        assertEquals("the armed condition is drawn", active.toList(), colors.colorAt(2))
    }

    @Test
    fun overlayIndicesOutsideTheMeshAreIgnored() {
        val colors = newBuffer(2)
        colors.resyncOverlay(supports = intArrayOf(0, 99), loads = intArrayOf(-3), active = IntArray(0))
        assertEquals(support.toList(), colors.colorAt(0))
        assertTrue(colors.hasOverlay)
    }

    @Test
    fun aResultTintFillsWhatNoConditionClaimed() {
        val colors = newBuffer(4, regions = arrayOf(regionLow, regionHigh))
        colors.resyncOverlay(
            supports = intArrayOf(0),
            loads = IntArray(0),
            active = IntArray(0),
            regions = intArrayOf(0, 0, 1, -1),
        )
        assertEquals("a picked surface outranks the tint", support.toList(), colors.colorAt(0))
        assertEquals(regionLow.toList(), colors.colorAt(1))
        assertEquals(regionHigh.toList(), colors.colorAt(2))
        assertEquals("no bin and no paint is plain", base.toList(), colors.colorAt(3))
        assertTrue(colors.hasOverlay)
    }

    @Test
    fun aSameSizePaletteWithDifferentColoursNeedsARebuild() {
        val colors = newBuffer(3, regions = arrayOf(regionLow, regionHigh))
        val fixed = arrayOf(base, enforcer, blocker, support, load, active)
        assertTrue(
            "the same slots with the same values",
            colors.hasPalette(fixed + arrayOf(regionLow, regionHigh)),
        )
        assertTrue(
            "fresh arrays carrying the same values are still the same palette",
            colors.hasPalette(
                arrayOf(
                    base.copyOf(), enforcer.copyOf(), blocker.copyOf(),
                    support.copyOf(), load.copyOf(), active.copyOf(),
                    regionLow.copyOf(), regionHigh.copyOf(),
                ),
            ),
        )
        // A second optimization with the same number of bins keeps the size and
        // changes the colours: reusing the buffer would tint the part with the
        // previous run's ramp.
        assertFalse("a different region colour", colors.hasPalette(fixed + arrayOf(regionHigh, regionLow)))
        assertFalse("a missing slot", colors.hasPalette(fixed + arrayOf(regionLow)))
        assertFalse(
            "an extra slot",
            colors.hasPalette(fixed + arrayOf(regionLow, regionHigh, regionLow)),
        )
    }
    @Test
    fun aBinWithoutAPaletteSlotStaysPlain() {
        val colors = newBuffer(2, regions = arrayOf(regionLow))
        colors.resyncOverlay(
            supports = IntArray(0),
            loads = IntArray(0),
            active = IntArray(0),
            regions = intArrayOf(0, 5),
        )
        assertEquals(regionLow.toList(), colors.colorAt(0))
        assertEquals(base.toList(), colors.colorAt(1))
    }

    @Test
    fun droppingTheResultTintRestoresTheBaseColour() {
        val colors = newBuffer(3, regions = arrayOf(regionLow, regionHigh))
        colors.resyncOverlay(
            supports = IntArray(0),
            loads = IntArray(0),
            active = IntArray(0),
            regions = intArrayOf(0, 1, -1),
        )
        colors.takeDirtyRange()

        colors.resyncOverlay(IntArray(0), IntArray(0), IntArray(0))
        assertFalse(colors.hasOverlay)
        assertEquals(base.toList(), colors.colorAt(0))
        assertEquals(base.toList(), colors.colorAt(1))
        assertEquals(0, colors.takeDirtyRange()?.first)
    }
}
