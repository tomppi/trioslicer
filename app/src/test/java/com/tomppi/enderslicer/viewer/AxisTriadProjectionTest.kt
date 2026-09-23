package com.tomppi.enderslicer.viewer

import android.opengl.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The marker as the renderer actually projects it: the same look-at, scene
 * rotation and projection matrices, so a letter that reads sideways on the phone
 * fails here first.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AxisTriadProjectionTest {

    private val viewportHeight = 1600
    private val aspect = 1184f / 986f

    /**
     * The renderer's own camera, looking at the middle of a 220mm bed: the same
     * look-at, scene rotation and projection [ModelSurfaceView] sets up.
     */
    private fun matrices(distance: Float = 420f): Pair<FloatArray, FloatArray> {
        val view = FloatArray(16)
        Matrix.setLookAtM(view, 0, 0f, -distance, distance * 0.62f, 0f, 0f, 0f, 0f, 0f, 1f)
        val scene = FloatArray(16)
        Matrix.setIdentityM(scene, 0)
        Matrix.rotateM(scene, 0, 58f, 1f, 0f, 0f)
        Matrix.rotateM(scene, 0, -28f, 0f, 0f, 1f)
        Matrix.translateM(scene, 0, -110f, -110f, 0f)
        val modelView = FloatArray(16)
        Matrix.multiplyMM(modelView, 0, view, 0, scene, 0)
        val projection = FloatArray(16)
        Matrix.perspectiveM(projection, 0, 42f, aspect, 1f, 5000f)
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        return mvp to modelView
    }

    private fun project(mvp: FloatArray, point: FloatArray): Pair<Float, Float> {
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, mvp, 0, floatArrayOf(point[0], point[1], point[2], 1f), 0)
        val x = (clip[0] / clip[3] * 0.5f + 0.5f) * 1812f
        val y = (1f - (clip[1] / clip[3] * 0.5f + 0.5f)) * viewportHeight
        return x to y
    }

    @Test
    fun theLettersStandUpOnScreen() {
        val (mvp, modelView) = matrices()
        // Exactly what the renderer hands the marker: the eye in plate
        // coordinates, and the camera's up out of the plate-to-view matrix.
        val inverse = FloatArray(16)
        Matrix.invertM(inverse, 0, modelView, 0)
        val eye = FloatArray(4)
        Matrix.multiplyMV(eye, 0, inverse, 0, floatArrayOf(0f, 0f, 0f, 1f), 0)
        val cameraUp = floatArrayOf(modelView[1], modelView[5], modelView[9])
        val origin = floatArrayOf(0f, 0f, -0.08f)
        val vertices = AxisTriad.vertices(
            origin = origin,
            armMm = 12f,
            eye = floatArrayOf(eye[0], eye[1], eye[2]),
            cameraUp = cameraUp,
        )

        for (axis in 0 until 3) {
            val base = axis * AxisTriad.VERTICES_PER_AXIS * 3
            // The letter's six points: their average is the letter's centre.
            val letter = (0 until 6).map { corner ->
                floatArrayOf(
                    vertices[base + 18 + corner * 3],
                    vertices[base + 19 + corner * 3],
                    vertices[base + 20 + corner * 3],
                )
            }
            val centre = FloatArray(3) { i -> letter.map { it[i] }.average().toFloat() }
            val (across, up) = AxisTriad.letterBasis(
                centre = centre,
                eye = floatArrayOf(eye[0], eye[1], eye[2]),
                cameraUp = cameraUp,
            )
            val here = project(mvp, centre)
            val alongUp = project(mvp, FloatArray(3) { centre[it] + up[it] })
            val alongAcross = project(mvp, FloatArray(3) { centre[it] + across[it] })
            println("axis $axis centre=$here up=$alongUp across=$alongAcross")

            assertTrue(
                "axis $axis: the letter's up must go up the screen, went " +
                    "${alongUp.second - here.second}",
                alongUp.second < here.second - 0.5f,
            )
            assertTrue(
                "axis $axis: the letter must read left to right, went " +
                    "${alongAcross.first - here.first}",
                alongAcross.first > here.first + 0.5f,
            )
            // Never edge-on: the letter has real height on screen.
            assertTrue("axis $axis: the letter is squashed", abs(alongUp.second - here.second) > 0.5f)
        }
    }
}
