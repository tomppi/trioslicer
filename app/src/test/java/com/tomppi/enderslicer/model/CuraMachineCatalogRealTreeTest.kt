package com.tomppi.enderslicer.model

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The catalogue rules against the real fetched tree.
 *
 * scripts/fetch-cura-resources.sh owns the tree and is not run for every build, so this skips
 * itself when the assets are absent and asserts the data where they are present (the CI build
 * fetches them first).
 */
class CuraMachineCatalogRealTreeTest {

    private val definitions = File("src/main/assets/cura/definitions")

    private fun load(id: String): String? =
        File(definitions, id + DEFINITION_SUFFIX).takeIf { it.isFile }?.readText()

    private fun ids(): List<String> = definitions.listFiles().orEmpty()
        .map { it.name }
        .filter { it.endsWith(DEFINITION_SUFFIX) }
        .map { it.removeSuffix(DEFINITION_SUFFIX) }

    @Test
    fun `the fetched tree resolves and offers its machines`() {
        assumeTrue(definitions.isDirectory)
        val ids = ids()
        assertEquals(1250, ids.size)

        val machines = CuraMachineCatalog.machinesFrom(ids, ::load)
        assertEquals(636, machines.size)
        assertTrue(machines.all { it.name.isNotBlank() })
        assertEquals(
            "Creality Ender-3 / Ender-3 v2",
            machines.first { it.id == "creality_ender3" }.name,
        )

        // Printers that something inherits from are still machines - Cura marks real printers
        // with metadata.visible and lets their variants inherit them.
        val offered = machines.mapTo(HashSet()) { it.id }
        for (id in listOf("ultimaker_s5", "ultimaker3", "wanhao_d6", "anycubic_i3_mega_s")) {
            assertTrue("the picker must offer " + id, id in offered)
        }
        // Visible scaffolding and extruder trains are not printers.
        for (id in listOf(
            "creality_base", "elegoo_base", "sovol_base_titan", "sovol_base_planetary",
            "lnl3d_base", "modix_v3_base", "modix_v4_base",
        )) {
            assertTrue("the picker must not offer " + id, id !in offered)
        }
        assertTrue(
            "the picker must not offer an extruder train",
            offered.none { "_extruder" in it },
        )

        // The default chain is the five files the app shipped before the catalogue existed,
        // so an untouched install slices exactly what it used to.
        assertEquals(
            setOf(
                "fdmprinter.def.json",
                "fdmextruder.def.json",
                "creality_base.def.json",
                "creality_base_extruder_0.def.json",
                "creality_ender3.def.json",
            ),
            CuraMachineCatalog.closureOf("creality_ender3", ::load)!!.files.toSet(),
        )

        // A second vendor resolves as well, including the second train of a dual machine.
        val ultimaker = CuraMachineCatalog.closureOf("ultimaker_s5", ::load)!!
        assertEquals("ultimaker_s5_extruder_left.def.json", ultimaker.extruderFile)
        assertTrue(ultimaker.files.contains("ultimaker_s5_extruder_right.def.json"))
    }

    @Test
    fun `no definition in the tree points at a file that is not there`() {
        assumeTrue(definitions.isDirectory)
        val present = ids().toSet()
        val dangling = mutableListOf<String>()
        for (id in present) {
            val json = load(id) ?: continue
            val root = JSONObject(json)
            val parent = root.optString("inherits").trim()
            if (parent.isNotEmpty() && parent != "null" && parent !in present) {
                dangling.add(id + " inherits " + parent)
            }
            val trains = root.optJSONObject("metadata")?.optJSONObject("machine_extruder_trains")
            if (trains != null) {
                for (slot in trains.keys().asSequence().toList()) {
                    val train = trains.optString(slot).trim().removeSuffix(DEFINITION_SUFFIX)
                    if (train.isNotEmpty() && train !in present) {
                        dangling.add(id + " uses extruder train " + train)
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), dangling)
    }

    private companion object {
        const val DEFINITION_SUFFIX = ".def.json"
    }
}
