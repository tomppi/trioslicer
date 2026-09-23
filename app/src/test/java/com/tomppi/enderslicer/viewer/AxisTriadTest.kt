package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plate's XYZ marker: its arm is sized from the corner's own depth so it
 * keeps its size on screen, and every axis is a shaft with a two-line head.
 */
class AxisTriadTest {

    @Test
    fun theArmKeepsTheMarkersSizeOnScreen() {
        val arm = AxisTriad.armMm(
            viewDepthMm = 100f,
            viewportHeightPx = 1000,
            fieldOfViewDegrees = 42f,
            armPx = 50f,
        )

        // Twice as far away on the same screen needs twice the arm.
        assertEquals(arm * 2f, AxisTriad.armMm(200f, 1000, 42f, 50f), 1e-4f)
        // A viewport half as tall shows half as many millimetres, so the arm grows.
        assertEquals(arm * 2f, AxisTriad.armMm(100f, 500, 42f, 50f), 1e-4f)
        // A corner at the camera is degenerate, not a zero-length marker.
        assertTrue(AxisTriad.armMm(0f, 1000, 42f, 50f) > 0f)
    }

    @Test
    fun everyAxisIsAShaftAndAHead() {
        val origin = floatArrayOf(0f, 0f, -0.08f)
        val eye = floatArrayOf(0f, -200f, 120f)
        val vertices = AxisTriad.vertices(
            origin = origin,
            armMm = 10f,
            eye = eye,
            cameraUp = floatArrayOf(-0.4f, 0.75f, 0.53f),
        )

        assertEquals(3 * AxisTriad.VERTICES_PER_AXIS * 3, vertices.size)

        // +X first: the shaft runs from the bed corner to ten millimetres out.
        assertEquals(origin[0], vertices[0], 1e-5f)
        assertEquals(origin[2], vertices[2], 1e-5f)
        assertEquals(10f, vertices[3], 1e-5f)
        assertEquals(origin[2], vertices[5], 1e-5f)

        // Its head starts at that tip and comes back along the axis.
        assertEquals(10f, vertices[6], 1e-5f)
        assertTrue("the head comes back along the axis, was ${vertices[9]}", vertices[9] < 10f)
        // The two head lines open either side of it.
        assertTrue(
            "the head has two sides",
            vertices[9] != vertices[15] || vertices[10] != vertices[16] ||
                vertices[11] != vertices[17],
        )
    }

    @Test
    fun everyAxisCarriesItsLetterPastTheTip() {
        val origin = floatArrayOf(0f, 0f, -0.08f)
        val eye = floatArrayOf(0f, -200f, 120f)
        val arm = 10f
        val vertices = AxisTriad.vertices(
            origin = origin,
            armMm = arm,
            eye = eye,
            cameraUp = floatArrayOf(-0.4f, 0.75f, 0.53f),
        )

        for (axis in 0 until 3) {
            val base = axis * AxisTriad.VERTICES_PER_AXIS * 3
            val tip = floatArrayOf(vertices[base + 3], vertices[base + 4], vertices[base + 5])
            // Six points: a letter's three lines, each with two ends.
            val letter = (0 until 6).map { corner ->
                floatArrayOf(
                    vertices[base + 18 + corner * 3],
                    vertices[base + 19 + corner * 3],
                    vertices[base + 20 + corner * 3],
                )
            }
            val centre = (0 until 3).map { i -> letter.map { it[i] }.average().toFloat() }
            assertTrue(
                "axis $axis names itself past the tip, moved " +
                    "${centre[axis] - tip[axis]} on ${arm}mm",
                centre[axis] - tip[axis] > 0f,
            )
            // A letter, not a dot: its ends are spread out.
            assertTrue("axis $axis draws a letter", letter.distinct().size > 2)
        }
    }

    @Test
    fun anAxisPointingAtTheCameraStillGetsAHead() {
        // Seen straight down +X there is no plane of its own to build the head
        // in, which is where the borrowed perpendicular keeps it from collapsing
        // into the shaft.
        val vertices = AxisTriad.vertices(
            origin = floatArrayOf(0f, 0f, 0f),
            armMm = 10f,
            eye = floatArrayOf(500f, 0f, 0f),
            cameraUp = floatArrayOf(0f, 0f, 1f),
        )

        assertTrue("no coordinate may go NaN", vertices.all { it.isFinite() })
        // The head's two points are across the axis, so it is their Y that parts
        // them; their X sits back along the shaft either way.
        assertTrue("the head still opens", vertices[10] != vertices[16])
    }

    @Test
    fun everyLetterReadsTheRightWayUp() {
        // The letters are written in a box that runs from -1 to 1 with y up, so
        // they have to read normally *there*: an upside-down Y on the plate is
        // just a Y written the other way round.
        val y = AxisTriad.LETTERS[1]
        // Point 0 and point 2 are the arms' outer ends; point 5 ends the stem.
        assertTrue("the Y's arms spread at the top: ${y[1]} and ${y[5]}", y[1] == 1f && y[5] == 1f)
        assertTrue("the Y's stem ends at the bottom: ${y[11]}", y[11] == -1f)

        val z = AxisTriad.LETTERS[2]
        assertTrue("the Z's first bar is its top: ${z[1]}, ${z[3]}", z[1] == 1f && z[3] == 1f)
        assertTrue("the Z's last bar is its bottom: ${z[9]}, ${z[11]}", z[9] == -1f && z[11] == -1f)

        val x = AxisTriad.LETTERS[0]
        // Its two lines run corner to corner, so every end sits at ±1.
        assertTrue(
            "the X runs corner to corner: ${x[1]}, ${x[3]}, ${x[5]}, ${x[7]}",
            x[1] == -1f && x[3] == 1f && x[5] == 1f && x[7] == -1f,
        )
    }

    @Test
    fun everyLetterSitsClearOfItsArrowHead() {
        // The head reaches 0.30 of the arm back from the tip, so no part of the
        // letter may fall inside that: overlapping, the pair reads as one smudge.
        val origin = floatArrayOf(0f, 0f, -0.08f)
        val arm = 10f
        val vertices = AxisTriad.vertices(
            origin = origin,
            armMm = arm,
            eye = floatArrayOf(110f, 110f, 480f),
            cameraUp = floatArrayOf(-0.469f, 0.883f, 0.004f),
        )

        for (axis in 0 until 3) {
            val base = axis * AxisTriad.VERTICES_PER_AXIS * 3
            val tip = floatArrayOf(vertices[base + 3], vertices[base + 4], vertices[base + 5])
            val nearest = (0 until 6).minOf { corner ->
                val point = floatArrayOf(
                    vertices[base + 18 + corner * 3],
                    vertices[base + 19 + corner * 3],
                    vertices[base + 20 + corner * 3],
                )
                (0..2).sumOf { ((point[it] - tip[it]) * AxisTriad.AXES[axis][it]).toDouble() }
                    .toFloat()
            }
            assertTrue(
                "axis $axis: the letter starts ${nearest / arm} arms past the tip",
                nearest > arm * 0.30f,
            )
        }
    }

    @Test
    fun aLetterIsSquaredToTheCameraNotToThePlate() {
        // The case that put the letters on their side: the eye almost straight
        // above the bed corner, where the plate's own up *is* the view direction
        // and so cannot be the letter's up.
        val (across, up) = AxisTriad.letterBasis(
            centre = floatArrayOf(0f, 0f, 0f),
            eye = floatArrayOf(0f, 0f, 500f),
            cameraUp = floatArrayOf(0f, 1f, 0f),
        )

        assertArrayEquals("the letter stands up with the camera", floatArrayOf(0f, 1f, 0f), up, 1e-4f)
        assertArrayEquals("and reads left to right", floatArrayOf(1f, 0f, 0f), across, 1e-4f)
    }

    @Test
    fun aLetterPlaneIsAlwaysPerpendicularToTheView() {
        // However the camera is placed, the letter faces it: no component of the
        // letter's plane along the view direction.
        val cases = listOf(
            floatArrayOf(0f, -200f, 120f) to floatArrayOf(-0.4f, 0.75f, 0.53f),
            floatArrayOf(0f, 0f, 500f) to floatArrayOf(0f, 0f, 1f),
            floatArrayOf(120f, -40f, 30f) to floatArrayOf(0.2f, 0.9f, 0.4f),
        )
        for ((eye, cameraUp) in cases) {
            val (across, up) = AxisTriad.letterBasis(floatArrayOf(0f, 0f, 0f), eye, cameraUp)
            val toEye = eye.map { it / kotlin.math.sqrt(eye.sumOf { c -> (c * c).toDouble() }).toFloat() }
            assertEquals("across faces the eye", 0f, toEye.indices.sumOf { (across[it] * toEye[it]).toDouble() }.toFloat(), 1e-3f)
            assertEquals("up faces the eye", 0f, toEye.indices.sumOf { (up[it] * toEye[it]).toDouble() }.toFloat(), 1e-3f)
            assertEquals("and the two are square", 0f, (0..2).sumOf { (across[it] * up[it]).toDouble() }.toFloat(), 1e-3f)
        }
    }
}
