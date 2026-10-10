package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.PlateObject
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The plate's below-bed caution: how much of the model is under the bed, and
 * what the one line about it says.
 *
 * The notice exists because the cut is lossy - the part below Z=0 is not
 * printed - but the move that causes it is allowed, so this is a caution and not
 * a refusal. The wording and the amount are pinned here so a later edit cannot
 * quietly turn "2.40 mm ... will not be printed" into a vaguer sentence.
 */
class BelowBedNoticeTest {
    private val printer = PrinterDefinition(
        name = "Modified Ender 3 V2",
        widthMm = 220.0,
        depthMm = 220.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        heatedBed = true,
        heatedBuildVolume = false,
        gcodeFlavor = "Marlin",
        extruders = 1,
        nozzleSizeMm = 0.4,
        filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0,
        printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0,
        printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
    )

    @Test
    fun aModelSittingOnTheBedSaysNothing() {
        val state = plateState(placed(cutMm = 0.0))

        assertEquals(0.0, state.belowBedCutMm, 0.0)
        assertEquals("no notice when nothing is under the bed", "", state.belowBedNotice)
    }

    @Test
    fun theNoticeNamesTheAmountThatWillNotBePrinted() {
        // With its bottom 0.5 mm under the bed the cube spans -0.5 to 1.5.
        assertEquals(
            "0.50 mm of this model is below the build plate; that part will not be printed.",
            plateState(placed(cutMm = 0.5)).belowBedNotice,
        )
        // And a deeper cut names the deeper amount.
        assertEquals(
            "2.40 mm of this model is below the build plate; that part will not be printed.",
            plateState(placed(cutMm = 2.4)).belowBedNotice,
        )
    }

    @Test
    fun raisingTheModelTakesTheNoticeAwayAgain() {
        val sunk = plateState(placed(cutMm = 2.4))
        val raised = sunk.withModel(sunk.selectedModelId!!) { it.withPlacement(it.placement.moved(baseZmm = 0.0)) }

        assertEquals(0.0, raised.belowBedCutMm, 0.0)
        assertEquals("", raised.belowBedNotice)
    }

    @Test
    fun aPartBelowTheBedIsReportedEvenWhenAnotherPartIsSelected() {
        // The plate as a workspace restore leaves it: the first object is the one
        // the tools act on, and the part under the bed need not be that one. The
        // slice cuts every object, so the notice has to say the deeper part's name
        // rather than describe the object the summary above it belongs to.
        val cube = placed(cutMm = 0.0, id = "cube", name = "cube.stl")
        val cone = placed(cutMm = 2.0, id = "cone", name = "cone-below.stl")
        val state = plateState(cube, cone)

        assertEquals(2.0, state.belowBedCutMm, 0.0)
        assertEquals(
            "2.00 mm of cone-below.stl is below the build plate; that part will not be printed.",
            state.belowBedNotice,
        )
    }

    @Test
    fun theDeepestPartBelowTheBedIsTheOneReported() {
        val shallow = placed(cutMm = 0.5, id = "shallow", name = "shallow.stl")
        val deep = placed(cutMm = 3.0, id = "deep", name = "deep.stl")
        val state = plateState(shallow, deep)

        assertEquals(3.0, state.belowBedCutMm, 0.0)
        assertEquals(
            "3.00 mm of deep.stl is below the build plate; that part will not be printed.",
            state.belowBedNotice,
        )
    }

    @Test
    fun anEmptyPlateHasNothingToWarnAbout() {
        val state = MainUiState(printer = printer)

        assertEquals(0.0, state.belowBedCutMm, 0.0)
        assertEquals("", state.belowBedNotice)
    }

    /** A 2 x 2 x 2 cube whose lowest point sits [cutMm] below the bed. */
    private fun placed(cutMm: Double, id: String = "cube", name: String = "cube.stl"): PlateObject {
        // ModelPlacement puts the mesh's own minZ at baseZmm, and the cube's own
        // minZ is -1, so baseZmm = -cutMm leaves its bottom cutMm under the bed.
        val source = cube()
        val placement = ModelPlacement(
            centerXmm = 110.0,
            centerYmm = 110.0,
            baseZmm = -cutMm,
        )
        return PlateObject(
            id = id,
            name = name,
            sourceMesh = source,
            mesh = placement.transformed(source),
            sourcePath = "/models/cube.stl",
            placement = placement,
        )
    }

    private fun plateState(vararg objects: PlateObject) = MainUiState(
        printer = printer,
        models = objects.toList(),
        selectedModelId = objects.firstOrNull()?.id,
    )

    /** A 2 x 2 x 2 cube centered on the origin, twelve outward triangles. */
    private fun cube(): StlMesh {
        val corners = arrayOf(
            floatArrayOf(-1f, -1f, -1f),
            floatArrayOf(1f, -1f, -1f),
            floatArrayOf(1f, 1f, -1f),
            floatArrayOf(-1f, 1f, -1f),
            floatArrayOf(-1f, -1f, 1f),
            floatArrayOf(1f, -1f, 1f),
            floatArrayOf(1f, 1f, 1f),
            floatArrayOf(-1f, 1f, 1f),
        )
        val faces = arrayOf(
            intArrayOf(0, 3, 2, 1),
            intArrayOf(4, 5, 6, 7),
            intArrayOf(0, 1, 5, 4),
            intArrayOf(1, 2, 6, 5),
            intArrayOf(2, 3, 7, 6),
            intArrayOf(3, 0, 4, 7),
        )
        val floats = FloatArray(faces.size * 36)
        var out = 0
        faces.forEach { face ->
            listOf(
                intArrayOf(face[0], face[1], face[2]),
                intArrayOf(face[0], face[2], face[3]),
            ).forEach { triangle ->
                triangle.forEach { corner ->
                    floats[out++] = corners[corner][0]
                    floats[out++] = corners[corner][1]
                    floats[out++] = corners[corner][2]
                    floats[out++] = 0f
                    floats[out++] = 0f
                    floats[out++] = 1f
                }
            }
        }
        return StlMesh(
            displayName = "cube.stl",
            interleavedVertices = VertexData.fromArray(floats),
            triangleCount = floats.size / 18,
            bounds = MeshBounds(-1f, -1f, -1f, 1f, 1f, 1f),
        )
    }
}
