package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CuraEngine writes no EXCLUDE_OBJECT_DEFINE lines, so KAMP always answered "No objects detected!"
 * on a Cura slice and measured the whole bed. The definition is written from the file's own header
 * bounds, and it has to be in place before BED_MESH_CALIBRATE - Klipper reads the objects when the
 * macro runs, not when the file is read.
 */
class KlipperObjectDefinitionWriterTest {
    private val header = ";FLAVOR:Marlin\n" +
        ";MINX:85.196\n;MINY:99.688\n;MAXX:144.754\n;MAXY:130.308\n"

    private fun file(text: String): File =
        File.createTempFile("object-definitions", ".gcode").apply { writeText(text) }

    @Test
    fun theDefinitionIsWrittenInFrontOfTheMeshCall() {
        val target = file(header + "G28\n;ENDERSLICER_KAMP_MESH\nBED_MESH_CALIBRATE\nG1 X1 Y1 E1\n")
        assertTrue(KlipperObjectDefinitionWriter.inject(target))
        val lines = target.readText().lines()
        val define = lines.indexOfFirst { it.startsWith("EXCLUDE_OBJECT_DEFINE") }
        val start = lines.indexOfFirst { it.startsWith("EXCLUDE_OBJECT_START") }
        val call = lines.indexOfFirst { it.trim() == "BED_MESH_CALIBRATE" }
        assertTrue("definition at " + define + ", call at " + call, define in 1 until call)
        assertTrue("start at " + start + ", call at " + call, start in define until call)
        assertEquals(
            "EXCLUDE_OBJECT_DEFINE NAME=enderslicer_model CENTER=114.975,114.998 " +
                "POLYGON=[[83.196,97.688],[146.754,97.688],[146.754,132.308],[83.196,132.308]]",
            lines[define],
        )
    }

    @Test
    fun aFileThatDeclaresItsOwnObjectsIsLeftAlone() {
        val text = header + "EXCLUDE_OBJECT_DEFINE NAME='model_stl' CENTER=1,1 POLYGON=[[0,0]]\n" +
            "BED_MESH_CALIBRATE\n"
        val target = file(text)
        assertFalse(KlipperObjectDefinitionWriter.inject(target))
        assertEquals(text, target.readText())
    }

    @Test
    fun aFileThatAsksForNoMeshIsLeftAlone() {
        val text = header + "G28\nG1 X1 Y1 E1\n"
        val target = file(text)
        assertFalse(KlipperObjectDefinitionWriter.inject(target))
        assertEquals(text, target.readText())
    }

    @Test
    fun theAppsUnsetSentinelIsNotABound() {
        // Before the sanitizer rewrites them, the header bounds carry this sentinel. A definition
        // built from it declared a two-million-millimetre object, and KAMP clamped the mesh back
        // to the whole bed - the cube printed with a full-bed mesh and no error to show for it.
        val text = ";FLAVOR:Marlin\n;MINX:2147478.0\n;MINY:2147478.0\n;MAXX:2147478.0\n" +
            ";MAXY:2147478.0\nG28\nBED_MESH_CALIBRATE\n"
        val target = file(text)
        assertFalse(KlipperObjectDefinitionWriter.inject(target))
        assertEquals(text, target.readText())
    }

    @Test
    fun aFileWithoutBoundsIsLeftAlone() {
        val text = ";FLAVOR:Marlin\nG28\nBED_MESH_CALIBRATE\n"
        val target = file(text)
        assertFalse(KlipperObjectDefinitionWriter.inject(target))
        assertEquals(text, target.readText())
    }
}
