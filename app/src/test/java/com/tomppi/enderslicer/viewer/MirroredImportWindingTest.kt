package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A mirrored import is not inside out.
 *
 * A 3MF build item - or a placement - may carry a transform with a negative
 * determinant: a negative scale, or a mirrored parent object. The transform
 * reverses the winding, and the winding is what the normal is computed from, so
 * every facet of the imported copy faces inwards. An inside-out closed mesh
 * passes every closedness check there is: the boolean engines take it happily
 * and answer, and every half a split cuts from it measures a NEGATIVE volume.
 * One 80 x 40 x 40 box measured -6000 mm3 and split into two halves of -3000.
 *
 * Both places an affine reaches this app's geometry therefore reverse two of a
 * triangle's corners when its determinant is negative, exactly as the Blender
 * STL export in EnginePreviewClient already did.
 */
class MirroredImportWindingTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aPlacementWithANegativeDeterminantKeepsTheBoxFacingOutwards() {
        val box = MeshFixtures.box(0f, 0f, 0f, 10f, 10f, 10f)
        val mirrored = ModelPlacement(
            linear = listOf(
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, -1.0,
            ),
            centerXmm = 5.0,
            centerYmm = 5.0,
            baseZmm = 0.0,
        )

        val placed = mirrored.transformed(box)

        assertTrue("the placed box is still a closed surface", MeshFixtures.isClosed(placed))
        assertEquals(
            "and still bounds a solid, not a hole",
            1000.0,
            MeshFixtures.signedVolume(placed),
            1e-3,
        )
        // The mirror put the box below the plate and the placement lifted it
        // back: the volume is what says the winding survived, and this says the
        // transform really was applied at all.
        assertEquals("the placed box stands on the plate", 0f, placed.bounds.minZ, 1e-4f)
        assertEquals("and is ten deep", 10f, placed.bounds.maxZ, 1e-4f)
    }

    @Test
    fun anIdentityOrRotationPlacementIsUnchangedByTheFlip() {
        val box = MeshFixtures.box(0f, 0f, 0f, 10f, 10f, 10f)
        val placed = ModelPlacement.centeredOnBed(box, 250.0, 210.0).transformed(box)
        val turned = ModelPlacement.centeredOnBed(box, 250.0, 210.0).rotated(ModelPlacement.Axis.Z, 90.0).transformed(box)

        assertEquals("a plain placement bounds the box as before", 1000.0, MeshFixtures.signedVolume(placed), 1e-3)
        assertEquals("and so does a rotation, which never mirrors", 1000.0, MeshFixtures.signedVolume(turned), 1e-3)
        assertTrue(MeshFixtures.isClosed(placed))
        assertTrue(MeshFixtures.isClosed(turned))
    }

    @Test
    fun aMirroredBuildItemImportsTheBoxFacingOutwards() {
        val mirrored = File(folder.root, "mirrored.3mf")
        writeThreeMf(mirrored, boxModel(transform = "-1 0 0 0 1 0 0 0 1 10 0 0"))
        val control = File(folder.root, "control.3mf")
        writeThreeMf(control, boxModel(transform = null))

        val imported = ThreeMfModelParser.parse(mirrored, "mirrored.3mf", maxTriangles = 1_000)
        val plain = ThreeMfModelParser.parse(control, "control.3mf", maxTriangles = 1_000)

        assertEquals("the same twelve facets came in", 12, imported.mesh.triangleCount)
        assertTrue("the mirrored import is a closed surface", MeshFixtures.isClosed(imported.mesh))
        assertEquals(
            "and measures the box's own volume, not its negative",
            1000.0,
            MeshFixtures.signedVolume(imported.mesh),
            1e-3,
        )
        assertEquals(
            "the mirror changes no volume at all",
            MeshFixtures.signedVolume(plain.mesh),
            MeshFixtures.signedVolume(imported.mesh),
            1e-3,
        )
        assertEquals("the mirrored copy really is mirrored", 0f, imported.mesh.bounds.minX, 1e-4f)
    }

    /** A closed 10 mm cube as a 3MF model part, with or without a mirroring item. */
    private fun boxModel(transform: String?): String {
        val box = MeshFixtures.box(0f, 0f, 0f, 10f, 10f, 10f)
        val positions = box.interleavedVertices
        val vertices = StringBuilder()
        for (vertex in 0 until box.triangleCount * 3) {
            val base = vertex * 6
            vertices.append("<vertex x=\"").append(positions[base])
                .append("\" y=\"").append(positions[base + 1])
                .append("\" z=\"").append(positions[base + 2]).append("\"/>\n")
        }
        val triangles = StringBuilder()
        for (triangle in 0 until box.triangleCount) {
            triangles.append("<triangle v1=\"").append(triangle * 3)
                .append("\" v2=\"").append(triangle * 3 + 1)
                .append("\" v3=\"").append(triangle * 3 + 2).append("\"/>\n")
        }
        val item = if (transform == null) "<item objectid=\"1\"/>" else "<item objectid=\"1\" transform=\"" + transform + "\"/>"
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
             <resources>
              <object id="1" type="model">
               <mesh>
                <vertices>
            """.trimIndent() + "\n" + vertices + """
                </vertices>
                <triangles>
            """.trimIndent() + "\n" + triangles + """
                </triangles>
               </mesh>
              </object>
             </resources>
             <build>""" + item + "</build>\n</model>\n"
    }

    private fun writeThreeMf(file: File, model: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
            zip.write(model.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
