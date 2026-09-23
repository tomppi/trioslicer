package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.PrinterDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class SceneCameraFitTest {
    private val printer = PrinterDefinition(
        name = "Test printer",
        widthMm = 230.0,
        depthMm = 230.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        heatedBed = true,
        heatedBuildVolume = false,
        gcodeFlavor = "Marlin",
        extruders = 1,
        nozzleSizeMm = 0.4,
        filamentDiameterMm = 1.75,
        printheadXMinMm = 0.0,
        printheadYMinMm = 0.0,
        printheadXMaxMm = 0.0,
        printheadYMaxMm = 0.0,
        gantryHeightMm = 25.0,
    )

    @Test
    fun portraitViewportUsesHorizontalFieldOfView() {
        val fit = SceneCameraFit.calculate(
            printer = printer,
            meshBounds = null,
            aspect = 0.4f,
            zoom = 1f,
            verticalFieldOfViewDegrees = 42f,
        )
        val verticalHalf = Math.toRadians(21.0).toFloat()
        val horizontalHalf = atan(tan(verticalHalf) * 0.4f)

        assertTrue(fit.distance >= fit.radius / sin(horizontalHalf) * 1.15f)
        assertTrue(fit.nearPlane > 0f)
        assertTrue(fit.farPlane > fit.nearPlane)
    }

    @Test
    fun benchySizedModelIsFramedAroundItsOwnCenter() {
        val bounds = MeshBounds(
            minX = 85f,
            minY = 99.5f,
            minZ = 0f,
            maxX = 145f,
            maxY = 130.5f,
            maxZ = 48f,
        )
        val modelFit = SceneCameraFit.calculate(printer, bounds, 0.75f, 1f, 42f)
        val bedFit = SceneCameraFit.calculate(printer, null, 0.75f, 1f, 42f)

        assertEquals(115f, modelFit.centerX, 0.001f)
        assertEquals(115f, modelFit.centerY, 0.001f)
        assertEquals(24f, modelFit.centerZ, 0.001f)
        assertTrue("The bed must not dominate a loaded model fit", modelFit.distance < bedFit.distance * 0.55f)
        assertTrue(modelFit.radius >= 40f)
        assertTrue(modelFit.farPlane > modelFit.distance)
    }

    @Test
    fun smallModelKeepsUsefulDepthBufferPrecision() {
        val bounds = MeshBounds(
            minX = 100f,
            minY = 105f,
            minZ = 0f,
            maxX = 130f,
            maxY = 125f,
            maxZ = 27.5f,
        )

        val fit = SceneCameraFit.calculate(printer, bounds, 0.8f, 1f, 42f)

        assertTrue("Near plane must not collapse to the emergency minimum", fit.nearPlane > 1f)
        assertTrue(
            "A tight far/near ratio prevents hidden triangles bleeding through the shell",
            fit.farPlane / fit.nearPlane < 100f,
        )
    }

    @Test
    fun zoomingOutKeepsTheWholeBuildPlateInsideTheFarPlane() {
        val bounds = MeshBounds(
            minX = 100f,
            minY = 105f,
            minZ = 0f,
            maxX = 130f,
            maxY = 125f,
            maxZ = 27.5f,
        )

        val fit = SceneCameraFit.calculate(printer, bounds, 0.8f, 0.2f, 42f)
        val eyeDistance = fit.distance * sqrt(1f + 0.62f * 0.62f)
        val sceneRadius = sqrt(115f * 115f + 115f * 115f + 13.75f * 13.75f)

        assertTrue(
            "Far plane must include the back build-plate corner after zooming out",
            fit.farPlane > eyeDistance + sceneRadius,
        )
        assertTrue(
            "Extra render distance must not sacrifice useful depth precision",
            fit.farPlane / fit.nearPlane < 100f,
        )
    }

    @Test
    fun displacedModelChangesSceneCenterWithoutBeingPulledBackToTheBed() {
        val bounds = MeshBounds(
            minX = 300f,
            minY = -80f,
            minZ = 5f,
            maxX = 360f,
            maxY = -20f,
            maxZ = 105f,
        )

        for (aspect in listOf(0.4f, 0.5f, 1f, 2f)) {
            val fit = SceneCameraFit.calculate(printer, bounds, aspect, 1f, 42f)
            val verticalHalf = Math.toRadians(21.0).toFloat()
            val horizontalHalf = atan(tan(verticalHalf) * aspect)
            val limitingHalf = minOf(verticalHalf, horizontalHalf)

            assertEquals(330f, fit.centerX, 0.001f)
            assertEquals(-50f, fit.centerY, 0.001f)
            assertTrue(fit.distance >= fit.radius / sin(limitingHalf) * 1.15f)
            assertTrue(fit.farPlane > fit.nearPlane)
        }
    }
}
