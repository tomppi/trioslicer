package com.tomppi.enderslicer.smartinfill

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native engine's boundary contract: the JSON the Kotlin layer writes is
 * what native/filasim/jni deserializes, and the metadata a native optimization
 * produces is what SmartInfillPackageStore validates.
 */
class FilaSimEngineContractTest {

    private fun optimization(
        summary: JSONObject,
        options: FilaSimOptimizeOptions,
        solid: Boolean = false,
    ) = FilaSimOptimization(summary = summary, options = options, regions = emptyList(), solid = solid)

    @Test
    fun optimizeOptionsCarryTheEngineKeys() {
        val root = JSONObject(FilaSimOptimizeOptions().toJson())
        assertEquals(25.0, root.getDouble("budget_pct"), 1e-9)
        assertEquals(0.45, root.getDouble("line_width"), 1e-9)
        assertEquals(0.2, root.getDouble("layer_height"), 1e-9)
        assertEquals(3, root.getInt("top_bottom_layers"))
        assertEquals(3, root.getInt("n_bins"))
        assertEquals(10.0, root.getDouble("floor_pct"), 1e-9)
        assertEquals(70.0, root.getDouble("cap_pct"), 1e-9)
        assertEquals("budget", root.getString("goal"))
        assertEquals("both", root.getString("sf_measure"))
        assertTrue(root.getBoolean("retain_bc"))
        assertFalse(root.getBoolean("binary"))
        assertFalse(root.getBoolean("solid"))
        // Optional lists are omitted rather than sent as null.
        assertFalse(root.has("levels_pct"))
        assertFalse(root.has("symmetry"))
    }

    @Test
    fun optimizeModesSelectTheirEngineFlags() {
        val binary = JSONObject(FilaSimOptimizeOptions(mode = FilaSimOptimizeMode.BINARY).toJson())
        assertTrue(binary.getBoolean("binary"))
        assertFalse(binary.getBoolean("solid"))

        val solid = JSONObject(FilaSimOptimizeOptions(mode = FilaSimOptimizeMode.SOLID_TOPOLOGY).toJson())
        assertFalse(solid.getBoolean("binary"))
        assertTrue(solid.getBoolean("solid"))

        val strength = JSONObject(FilaSimOptimizeOptions(goal = FilaSimGoal.STRENGTH).toJson())
        assertEquals("strength", strength.getString("goal"))
    }

    @Test
    fun boundaryConditionsEncodeKindAndTriangles() {
        val fixed = JSONObject(FilaSimBoundaryCondition.Fixed(intArrayOf(1, 2, 3)).toJson())
        assertEquals("fixed", fixed.getString("kind"))
        assertEquals(3, fixed.getJSONArray("tris").length())
        assertEquals(2, fixed.getJSONArray("tris").getInt(1))

        val force = JSONObject(
            FilaSimBoundaryCondition.Force(intArrayOf(7), listOf(0.0, 0.0, -120.0)).toJson(),
        )
        assertEquals("force", force.getString("kind"))
        assertEquals(-120.0, force.getJSONArray("vector").getDouble(2), 1e-9)

        val elastic = JSONObject(
            FilaSimBoundaryCondition.Elastic(intArrayOf(9), stiffnessNPerMm3 = 50.0).toJson(),
        )
        assertEquals(50.0, elastic.getDouble("k"), 1e-9)

        val displacement = JSONObject(
            FilaSimBoundaryCondition.Displacement(
                triangles = intArrayOf(4),
                axes = listOf(true, false, true),
            ).toJson(),
        )
        assertTrue(displacement.getJSONArray("axes").getBoolean(0))
        assertFalse(displacement.getJSONArray("axes").getBoolean(1))

        val mass = JSONObject(
            FilaSimBoundaryCondition.Mass(
                triangles = intArrayOf(5),
                point = listOf(1.0, 2.0, 3.0),
                massTonnes = 0.5,
                rigid = true,
            ).toJson(),
        )
        assertEquals(0.5, mass.getDouble("mass"), 1e-9)
        assertTrue(mass.getBoolean("rigid"))
    }

    @Test
    fun gradedMetadataSatisfiesTheStoreContract() {
        val options = FilaSimOptimizeOptions(
            perimeters = 2,
            lineWidthMm = 0.45,
            layerHeightMm = 0.2,
            topBottomLayers = 3,
        )
        val summary = JSONObject().put("baseDensity", 0.10)
        val metadata = JSONObject(
            smartInfillMetadataJson(
                optimization = optimization(summary, options),
                options = options,
                sourceName = "hook.stl",
                sourceSha256 = "a".repeat(64),
            ),
        )
        assertEquals(2, metadata.getInt("metadataVersion"))
        assertEquals("graded", metadata.getString("mode"))
        assertEquals(SMART_INFILL_BASE_PATTERN, metadata.getString("basePattern"))
        assertEquals("rectilinear", metadata.getString("gradedFullDensityPattern"))
        assertEquals(10.0, metadata.getDouble("baseDensityPercent"), 1e-9)
        assertEquals(2, metadata.getInt("perimeters"))
        assertEquals(0.45, metadata.getDouble("lineWidthMm"), 1e-9)
        assertEquals(0.2, metadata.getDouble("layerHeightMm"), 1e-9)
        assertEquals(3, metadata.getInt("topBottomLayers"))
        assertEquals(FilaSimEngine.FILASIM_COMMIT, metadata.getString("upstreamCommit"))
        assertEquals("hook.stl", metadata.getString("sourceName"))
        assertEquals("a".repeat(64), metadata.getString("sourceSha256"))
        // Graded mode must not carry a binary solid pattern.
        assertFalse(metadata.has("binarySolidPattern"))
        // The base density is what the modifier densities must exceed.
        assertTrue(metadata.getDouble("baseDensityPercent") in 1.0..100.0)
    }

    @Test
    fun binaryMetadataCarriesItsSolidPattern() {
        val concentric = FilaSimOptimizeOptions(
            mode = FilaSimOptimizeMode.BINARY,
            binarySolidPattern = "concentric",
        )
        val summary = JSONObject().put("baseDensity", 0.10)
        val metadata = JSONObject(
            smartInfillMetadataJson(
                optimization = optimization(summary, concentric),
                options = concentric,
                sourceName = "hook.stl",
                sourceSha256 = "b".repeat(64),
            ),
        )
        assertEquals("binary", metadata.getString("mode"))
        assertEquals("concentric", metadata.getString("binarySolidPattern"))

        val rectilinear = concentric.copy(binarySolidPattern = "zig-zag")
        val fallback = JSONObject(
            smartInfillMetadataJson(
                optimization = optimization(summary, rectilinear),
                options = rectilinear,
                sourceName = "hook.stl",
                sourceSha256 = "b".repeat(64),
            ),
        )
        // Only concentric is a solid pattern here; anything else is the calibrated fill.
        assertEquals("rectilinear", fallback.getString("binarySolidPattern"))
    }

    @Test
    fun solidTopologyIsNotAModifierPackage() {
        val options = FilaSimOptimizeOptions(mode = FilaSimOptimizeMode.SOLID_TOPOLOGY)
        val summary = JSONObject().put("baseDensity", 1.0e-3)
        val result = optimization(summary, options, solid = true)
        assertEquals("solid", result.mode)
        // A Part Topo result is a new body, so the store never sees a graded package.
        assertTrue(result.solid)
    }
}
