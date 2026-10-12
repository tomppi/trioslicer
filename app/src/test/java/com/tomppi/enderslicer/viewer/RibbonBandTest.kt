package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.engine.PrusaNozzlePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The band is what keeps a 2.4-million-move print drawable: the vertex buffers hold the moves
 * around the scrub and are rebuilt when it leaves them, and every move inside the band is
 * drawn - the band is a window, never a sample.
 */
class RibbonBandTest {

    /** A path of alternating one-millimetre extrusions with a travel every fifth move. */
    private fun path(moveCount: Int): PrusaNozzlePath {
        val moves = FloatArray(moveCount * PrusaNozzlePath.VALUES_PER_MOVE)
        var x = 0f
        var y = 0f
        var extrusions = 0
        var travels = 0
        for (move in 0 until moveCount) {
            val o = move * PrusaNozzlePath.VALUES_PER_MOVE
            val travel = move % 5 == 4
            val ex = x + 1f
            val ey = y
            moves[o + PrusaNozzlePath.X1] = x
            moves[o + PrusaNozzlePath.Y1] = y
            moves[o + PrusaNozzlePath.Z1] = (move / 100) * 0.08f
            moves[o + PrusaNozzlePath.X2] = ex
            moves[o + PrusaNozzlePath.Y2] = ey
            moves[o + PrusaNozzlePath.Z2] = (move / 100) * 0.08f
            moves[o + PrusaNozzlePath.SPEED] = 30f
            moves[o + PrusaNozzlePath.KIND] =
                if (travel) PrusaNozzlePath.Kind.TRAVEL.code else PrusaNozzlePath.Kind.EXTRUSION.code
            moves[o + PrusaNozzlePath.WIDTH] = 0.44f
            moves[o + PrusaNozzlePath.HEIGHT] = 0.08f
            if (travel) travels++ else extrusions++
            x = ex
            y = ey
        }
        return PrusaNozzlePath(
            moves = moves,
            sourceMoveIndices = IntArray(moveCount) { it },
            minX = 0f, minY = 0f, minZ = 0f,
            maxX = moveCount.toFloat(), maxY = 1f, maxZ = 1f,
            extrusionMoveCount = extrusions,
            travelMoveCount = travels,
            sourceMoveCount = moveCount,
            layerCount = 1,
            truncated = false,
        )
    }

    private fun firstExtrusion(path: PrusaNozzlePath, range: IntRange): Int =
        range.first { path.moves[it * PrusaNozzlePath.VALUES_PER_MOVE + PrusaNozzlePath.KIND] == PrusaNozzlePath.Kind.EXTRUSION.code }

    private fun lastExtrusion(path: PrusaNozzlePath, range: IntRange): Int =
        range.last { path.moves[it * PrusaNozzlePath.VALUES_PER_MOVE + PrusaNozzlePath.KIND] == PrusaNozzlePath.Kind.EXTRUSION.code }

    @Test
    fun aBandFitsBothBudgetsAndHoldsTheAnchor() {
        val path = path(40_000)
        val anchor = 20_000
        val range = RibbonBand.plan(path.moves, path.moveCount, anchor, capacityWindows = 5_000, capacityTravels = 5_000)
        assertTrue("the anchor has to be inside its band", anchor in range)
        assertTrue("windows over budget", RibbonBand.countWindows(path.moves, range) <= 5_000)
        assertTrue("the band has to be a band, not the path", range.count() < path.moveCount)
        // Centred: the band reaches about as far back as forward.
        val back = anchor - range.first
        val forward = range.last - anchor
        assertTrue("band is lopsided: " + back + " back, " + forward + " forward", kotlin.math.abs(back - forward) <= 2)
    }

    @Test
    fun aPathThatFitsIsHeldWhole() {
        val path = path(3_000)
        val range = RibbonBand.plan(
            path.moves,
            path.moveCount,
            anchor = 1_500,
            capacityWindows = RibbonBand.MAX_WINDOWS,
            capacityTravels = RibbonBand.MAX_TRAVELS,
        )
        assertEquals(0, range.first)
        assertEquals(path.moveCount, range.last + 1)
    }

    @Test
    fun scrubbingToAFarMoveRebuildsTheBandThere() {
        val path = path(40_000)
        val first = RibbonBand.plan(path.moves, path.moveCount, 5_000, 4_000, 4_000)
        assertTrue(
            "the anchor is inside its band, so no rebuild",
            !RibbonBand.needsRebuild(first.first, first.last + 1, path.moveCount, 5_000),
        )
        assertTrue(
            "a scrub far away has to rebuild",
            RibbonBand.needsRebuild(first.first, first.last + 1, path.moveCount, 30_000),
        )
        assertTrue(
            "a band that already holds the whole path never rebuilds",
            !RibbonBand.needsRebuild(0, path.moveCount, path.moveCount, 30_000) &&
                !RibbonBand.needsRebuild(0, path.moveCount, path.moveCount, 0),
        )
        val second = RibbonBand.plan(path.moves, path.moveCount, 30_000, 4_000, 4_000)
        assertTrue(30_000 in second)
        assertTrue("the rebuilt band is somewhere else", second.first > first.last)
    }

    @Test
    fun everyMoveInTheBandIsDrawnAndNothingOutsideIt() {
        val path = path(6_000)
        val range = RibbonBand.plan(path.moves, path.moveCount, 3_000, 1_200, 1_200)
        val recorder = RibbonChunkRecorder(
            collect = true,
            expectedWindows = path.extrusionMoveCount,
            expectedTravels = path.travelMoveCount,
        )
        val staging = RibbonChunkStaging(recorder)
        PrusaRibbonBuilder(path).build(staging, range.first, range.last + 1)
        staging.finish()

        assertEquals(
            "one window per extrusion move in the band",
            RibbonBand.countWindows(path.moves, range),
            recorder.windows,
        )
        val positions = recorder.ribbonPositionArray()
        val first = firstExtrusion(path, range)
        val last = lastExtrusion(path, range)
        // Window 0's top face runs a -> b at the start and c -> d at the end; the centre line
        // is the mean of those corners, so the band's own first and last moves come back out.
        val startX = (positions[0] + positions[3]) * 0.5f
        val startY = (positions[1] + positions[4]) * 0.5f
        assertEquals(path.moves[first * PrusaNozzlePath.VALUES_PER_MOVE + PrusaNozzlePath.X1], startX, 1e-3f)
        assertEquals(path.moves[first * PrusaNozzlePath.VALUES_PER_MOVE + PrusaNozzlePath.Y1], startY, 1e-3f)
        val endBase = (recorder.windows - 1) * RibbonPathGeometry.WINDOW_VERTICES * 3
        val endX = (positions[endBase + 6] + positions[endBase + 15]) * 0.5f
        val endY = (positions[endBase + 7] + positions[endBase + 16]) * 0.5f
        assertEquals(path.moves[last * PrusaNozzlePath.VALUES_PER_MOVE + PrusaNozzlePath.X2], endX, 1e-3f)
        assertEquals(path.moves[last * PrusaNozzlePath.VALUES_PER_MOVE + PrusaNozzlePath.Y2], endY, 1e-3f)
    }
}
