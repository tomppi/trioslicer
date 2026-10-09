package com.tomppi.enderslicer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateArrangerTest {
    private val tolerance = 1e-6

    @Test
    fun footprintsThatFitShareOneRowAcrossTheBed() {
        val footprints = listOf(PlateFootprint(40.0, 30.0), PlateFootprint(40.0, 30.0))
        val slots = requireNotNull(PlateArranger.arrange(footprints, 200.0, 200.0, 5.0))

        // One row: both footprints sit at the same centre depth.
        assertEquals(2, slots.size)
        assertEquals(slots[0].centerYmm, slots[1].centerYmm, tolerance)
        // Neighbours keep exactly the spacing, and the row is centred across the bed.
        assertEquals(5.0, slots[1].centerXmm - slots[0].centerXmm - 40.0, tolerance)
        assertEquals(200.0 - (slots[1].centerXmm + 20.0), slots[0].centerXmm - 20.0, tolerance)
        assertTrue(slots[0].centerXmm - 20.0 >= 5.0 - tolerance)
        assertTrue(slots[1].centerXmm + 20.0 <= 195.0 + tolerance)
        assertTrue(slots[0].centerYmm - 15.0 >= 5.0 - tolerance)
    }

    @Test
    fun thePackingWrapsToASecondRowWhenTheNextFootprintWouldLeaveTheBed() {
        val footprints = List(3) { PlateFootprint(40.0, 40.0) }
        val slots = requireNotNull(PlateArranger.arrange(footprints, 100.0, 100.0, 5.0))

        // Two 40 mm squares plus their gap fill the 90 mm usable width, so the third starts a row.
        assertEquals(slots[0].centerYmm, slots[1].centerYmm, tolerance)
        val frontRow = slots[0].centerYmm
        val backRow = slots[2].centerYmm
        assertTrue("the third footprint should wrap", backRow > frontRow)
        // The lone footprint of the second row is centred across the bed, with the spacing between rows.
        assertEquals(50.0, slots[2].centerXmm, tolerance)
        assertEquals(5.0, (backRow - 20.0) - (frontRow + 20.0), tolerance)
    }

    @Test
    fun aFootprintLargerThanTheBedIsRejected() {
        assertNull(PlateArranger.arrange(listOf(PlateFootprint(250.0, 10.0)), 200.0, 200.0, 5.0))
        assertNull(PlateArranger.arrange(listOf(PlateFootprint(10.0, 250.0)), 200.0, 200.0, 5.0))
        assertNull(
            PlateArranger.arrange(
                listOf(PlateFootprint(Double.POSITIVE_INFINITY, 10.0)),
                200.0, 200.0, 5.0,
            ),
        )

        // Exactly the usable area is accepted, so the boundary itself is not rejected.
        val bedFilling = requireNotNull(
            PlateArranger.arrange(listOf(PlateFootprint(190.0, 190.0)), 200.0, 200.0, 5.0),
        )
        assertEquals(100.0, bedFilling.single().centerXmm, tolerance)
        assertEquals(100.0, bedFilling.single().centerYmm, tolerance)
    }

    @Test
    fun rowsThatRunPastTheBackEdgeReturnNull() {
        val square = PlateFootprint(35.0, 35.0)
        // A 100 mm bed at 10 mm spacing takes two rows of two 35 mm squares...
        assertNotNull(PlateArranger.arrange(List(4) { square }, 100.0, 100.0, 10.0))
        // ...and a fifth square needs a third row, which would hang off the back.
        assertNull(PlateArranger.arrange(List(5) { square }, 100.0, 100.0, 10.0))
    }

    @Test
    fun theSameInputAlwaysGivesTheSameSlots() {
        val footprints = listOf(
            PlateFootprint(60.0, 40.0),
            PlateFootprint(20.0, 60.0),
            PlateFootprint(35.0, 35.0),
            PlateFootprint(60.0, 40.0),
        )
        val first = PlateArranger.arrange(footprints, 230.0, 230.0, 3.0)
        assertNotNull(first)
        assertEquals(first, PlateArranger.arrange(footprints, 230.0, 230.0, 3.0))
    }

    @Test
    fun neighboursAndBedEdgesKeepTheSpacing() {
        val footprints = listOf(
            PlateFootprint(60.0, 40.0),
            PlateFootprint(30.0, 80.0),
            PlateFootprint(50.0, 50.0),
            PlateFootprint(20.0, 20.0),
            PlateFootprint(45.0, 60.0),
            PlateFootprint(70.0, 35.0),
        )
        val spacing = 3.0
        val slots = requireNotNull(PlateArranger.arrange(footprints, 230.0, 230.0, spacing))
        val boxes = footprints.mapIndexed { index, footprint -> box(footprint, slots[index]) }

        boxes.forEachIndexed { index, box ->
            assertTrue("footprint $index pokes off the left edge", box.left >= spacing - tolerance)
            assertTrue("footprint $index pokes off the right edge", box.right <= 230.0 - spacing + tolerance)
            assertTrue("footprint $index pokes off the front edge", box.front >= spacing - tolerance)
            assertTrue("footprint $index pokes off the back edge", box.back <= 230.0 - spacing + tolerance)
        }
        for (a in boxes.indices) {
            for (b in a + 1 until boxes.size) {
                val apart = boxes[a].gapX(boxes[b]) >= spacing - tolerance ||
                    boxes[a].gapY(boxes[b]) >= spacing - tolerance
                assertTrue("footprints $a and $b are closer than the spacing", apart)
            }
        }
    }

    @Test
    fun theResultFollowsTheCallersOrderNotThePackingOrder() {
        // The small footprint is listed first but packed second, to the right of the big one.
        val footprints = listOf(PlateFootprint(10.0, 10.0), PlateFootprint(100.0, 100.0))
        val slots = requireNotNull(PlateArranger.arrange(footprints, 200.0, 200.0, 2.0))

        assertEquals(151.0, slots[0].centerXmm, tolerance)
        assertEquals(94.0, slots[1].centerXmm, tolerance)
        assertEquals(slots[0].centerYmm, slots[1].centerYmm, tolerance)
    }

    @Test
    fun anEmptyPlateArrangesToAnEmptyList() {
        assertEquals(emptyList<PlateSlot>(), PlateArranger.arrange(emptyList(), 200.0, 200.0, 5.0))
        // Nothing to place is nothing to reject, even on a bed with no room at all.
        assertEquals(emptyList<PlateSlot>(), PlateArranger.arrange(emptyList(), 0.0, 0.0, 5.0))
    }

    @Test
    fun degenerateSizesArePlacedInsteadOfRejected() {
        val slots = requireNotNull(
            PlateArranger.arrange(
                listOf(PlateFootprint(0.0, 0.0), PlateFootprint(-5.0, -5.0)),
                100.0, 100.0, 5.0,
            ),
        )
        assertEquals(2, slots.size)
        for (slot in slots) {
            assertTrue(slot.centerXmm >= 5.0 - tolerance && slot.centerXmm <= 95.0 + tolerance)
            assertTrue(slot.centerYmm >= 5.0 - tolerance && slot.centerYmm <= 95.0 + tolerance)
        }

        // A negative spacing means no gap, not a crash or a backwards row.
        assertNotNull(
            PlateArranger.arrange(
                listOf(PlateFootprint(40.0, 40.0), PlateFootprint(40.0, 40.0)),
                100.0, 100.0, -5.0,
            ),
        )
    }

    @Test
    fun aBedWithoutAPositiveSizeHoldsNothing() {
        val footprint = listOf(PlateFootprint(10.0, 10.0))
        assertNull(PlateArranger.arrange(footprint, 0.0, 200.0, 5.0))
        assertNull(PlateArranger.arrange(footprint, 200.0, 0.0, 5.0))
        assertNull(PlateArranger.arrange(footprint, 200.0, Double.NaN, 5.0))
        // A spacing wider than the bed leaves no usable area either.
        assertNull(PlateArranger.arrange(footprint, 20.0, 200.0, 15.0))
        // An unknown footprint size is treated as a point and still gets a slot.
        assertNotNull(
            PlateArranger.arrange(listOf(PlateFootprint(Double.NaN, Double.NaN)), 200.0, 200.0, 5.0),
        )
    }

    private fun box(footprint: PlateFootprint, slot: PlateSlot): Box = Box(
        left = slot.centerXmm - footprint.widthMm / 2.0,
        front = slot.centerYmm - footprint.depthMm / 2.0,
        right = slot.centerXmm + footprint.widthMm / 2.0,
        back = slot.centerYmm + footprint.depthMm / 2.0,
    )

    /** A footprint's rectangle on the bed; a positive gap means the two do not touch. */
    private data class Box(val left: Double, val front: Double, val right: Double, val back: Double) {
        fun gapX(other: Box): Double = maxOf(left, other.left) - minOf(right, other.right)
        fun gapY(other: Box): Double = maxOf(front, other.front) - minOf(back, other.back)
    }
}
