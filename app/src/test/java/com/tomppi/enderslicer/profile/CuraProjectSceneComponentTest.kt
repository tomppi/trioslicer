package com.tomppi.enderslicer.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CuraProjectSceneComponentTest {
    @Test
    fun componentSceneTransformIsRecursivelyComposed() {
        val scene = requireNotNull(
            CuraProjectSceneParser.parse(
                projectArchive(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
                      <resources>
                        <object id="1" name="assembly" type="model">
                          <components>
                            <component objectid="2" transform="1 0 0 0 1 0 0 0 1 5 0 0"/>
                          </components>
                        </object>
                        <object id="2" type="model">
                          <components>
                            <component objectid="3" transform="1 0 0 0 1 0 0 0 1 2 0 0"/>
                          </components>
                        </object>
                        <object id="3" name="leaf" type="model">
                          <mesh>
                            <vertices>
                              <vertex x="5" y="15" z="3"/>
                              <vertex x="15" y="15" z="3"/>
                              <vertex x="10" y="25" z="8"/>
                            </vertices>
                            <triangles><triangle v1="0" v2="1" v3="2"/></triangles>
                          </mesh>
                        </object>
                      </resources>
                      <build>
                        <item objectid="1" transform="1 0 0 0 1 0 0 0 1 100 0 0"/>
                      </build>
                    </model>
                    """.trimIndent(),
                ),
            ),
        )
        val affine = assertNotNull(scene.affine).let { requireNotNull(scene.affine) }

        assertEquals(2, scene.componentCount)
        assertEquals("assembly", scene.modelName)
        assertEquals(107.0, affine.translationXmm, 0.0001)
        assertEquals(0.0, affine.translationYmm, 0.0001)
        assertEquals(117.0, affine.targetCenterXmm ?: Double.NaN, 0.0001)
        assertEquals(20.0, affine.targetCenterYmm ?: Double.NaN, 0.0001)
        assertEquals(3.0, affine.targetBaseZmm ?: Double.NaN, 0.0001)
        assertTrue(scene.warnings.none { "component transform could not be applied" in it })
    }

    @Test
    fun componentTransformIsAppliedWhenTheBuildItemHasNoTransform() {
        val scene = requireNotNull(
            CuraProjectSceneParser.parse(
                projectArchive(
                    """
                    <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
                      <resources>
                        <object id="1" type="model">
                          <components>
                            <component objectid="2" transform="1 0 0 0 1 0 0 0 1 5 6 7"/>
                          </components>
                        </object>
                        <object id="2" type="model">
                          <mesh>
                            <vertices>
                              <vertex x="0" y="0" z="0"/>
                              <vertex x="2" y="0" z="0"/>
                              <vertex x="0" y="2" z="1"/>
                            </vertices>
                            <triangles><triangle v1="0" v2="1" v3="2"/></triangles>
                          </mesh>
                        </object>
                      </resources>
                      <build><item objectid="1"/></build>
                    </model>
                    """.trimIndent(),
                ),
            ),
        )
        val affine = requireNotNull(scene.affine)

        assertEquals(5.0, affine.translationXmm, 0.0)
        assertEquals(6.0, affine.translationYmm, 0.0)
        assertEquals(7.0, affine.translationZmm, 0.0)
    }

    @Test
    fun ambiguousMultiComponentSceneDoesNotExposeAPartialAffine() {
        val scene = requireNotNull(
            CuraProjectSceneParser.parse(
                projectArchive(
                    """
                    <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
                      <resources>
                        <object id="1" type="model">
                          <components>
                            <component objectid="2" transform="1 0 0 0 1 0 0 0 1 5 0 0"/>
                            <component objectid="3" transform="1 0 0 0 1 0 0 0 1 10 0 0"/>
                          </components>
                        </object>
                        <object id="2" type="model">
                          <mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices><triangles><triangle v1="0" v2="1" v3="2"/></triangles></mesh>
                        </object>
                        <object id="3" type="model">
                          <mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices><triangles><triangle v1="0" v2="1" v3="2"/></triangles></mesh>
                        </object>
                      </resources>
                      <build><item objectid="1" transform="1 0 0 0 1 0 0 0 1 100 0 0"/></build>
                    </model>
                    """.trimIndent(),
                ),
            ),
        )

        assertNull(scene.affine)
        assertTrue(scene.warnings.any { "multi-component composition is ambiguous" in it })
    }

    @Test
    fun componentCycleDoesNotExposeAnAffine() {
        val scene = requireNotNull(
            CuraProjectSceneParser.parse(
                projectArchive(
                    """
                    <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
                      <resources>
                        <object id="1" type="model"><components><component objectid="2" transform="1 0 0 0 1 0 0 0 1 1 0 0"/></components></object>
                        <object id="2" type="model"><components><component objectid="1" transform="1 0 0 0 1 0 0 0 1 1 0 0"/></components></object>
                      </resources>
                      <build><item objectid="1" transform="1 0 0 0 1 0 0 0 1 100 0 0"/></build>
                    </model>
                    """.trimIndent(),
                ),
            ),
        )

        assertNull(scene.affine)
        assertTrue(scene.warnings.any { "component cycle detected" in it })
    }

    private fun projectArchive(modelXml: String): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream().use { buffer ->
            ZipOutputStream(buffer).use { zip ->
                zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
                zip.write(modelXml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            buffer.toByteArray()
        }
        return ByteArrayInputStream(bytes)
    }
}
