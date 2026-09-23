package com.tomppi.enderslicer.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CuraProjectSceneParserTest {
    @Test
    fun parsesBuildTransformEmbeddedTargetBoundsDropFlagAndPostProcessingWarning() {
        val archive = zip(
            "3D/3dmodel.model" to """
                <?xml version="1.0"?>
                <model unit="millimeter"
                    xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"
                    xmlns:cura="http://software.ultimaker.com/xml/cura/3mf/2015/10">
                  <resources>
                    <object id="7" name="part.stl" type="model">
                      <metadatagroup>
                        <metadata name="cura:drop_to_buildplate">True</metadata>
                      </metadatagroup>
                      <mesh>
                        <vertices>
                          <vertex x="0" y="0" z="0"/>
                          <vertex x="2" y="0" z="0"/>
                          <vertex x="0" y="4" z="1"/>
                        </vertices>
                        <triangles/>
                      </mesh>
                    </object>
                  </resources>
                  <build>
                    <item objectid="7" transform="1 0 0 0 0 1 0 -1 0 115 115 22.5" />
                  </build>
                </model>
            """.trimIndent(),
            "Cura/Test.global.cfg" to """
                [metadata]
                post_processing_scripts = [PauseAtHeight]\n
                [values]
            """.trimIndent(),
        )

        val scene = CuraProjectSceneParser.parse(ByteArrayInputStream(archive))
        assertNotNull(scene)
        requireNotNull(scene)
        assertEquals("part.stl", scene.modelName)
        assertTrue(scene.dropToBuildPlate)
        assertEquals(1, scene.objectCount)
        assertEquals(1, scene.buildItemCount)
        val affine = requireNotNull(scene.affine)
        assertEquals(1.0, affine.linear[0], 1e-9)
        assertEquals(-1.0, affine.linear[5], 1e-9)
        assertEquals(22.5, affine.translationZmm, 1e-9)
        assertEquals(116.0, requireNotNull(affine.targetCenterXmm), 1e-9)
        assertEquals(114.5, requireNotNull(affine.targetCenterYmm), 1e-9)
        assertEquals(22.5, requireNotNull(affine.targetBaseZmm), 1e-9)
        assertTrue(scene.warnings.any { it.contains("post-processing") })
    }

    @Test
    fun warnsAboutMultipleBuildItemsAndAnInvalidComponentRoot() {
        val archive = zip(
            "3D/3dmodel.model" to """
                <?xml version="1.0"?>
                <model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
                  <resources>
                    <object id="1" name="one.stl" type="model"><mesh><vertices/><triangles/></mesh></object>
                    <object id="2" name="assembly" type="model"><components><component objectid="1" /></components></object>
                  </resources>
                  <build><item objectid="1"/><item objectid="2"/></build>
                </model>
            """.trimIndent(),
        )
        val scene = requireNotNull(CuraProjectSceneParser.parse(ByteArrayInputStream(archive)))
        assertTrue(scene.warnings.any { it.contains("build items") })
        assertTrue(scene.warnings.any { it.contains("component") })
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
