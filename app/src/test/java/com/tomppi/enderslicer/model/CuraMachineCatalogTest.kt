package com.tomppi.enderslicer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue rules, exercised on a small hand-written tree. The real tree is fetched and
 * validated by scripts/fetch-cura-resources.sh; these tests pin the rules themselves.
 */
class CuraMachineCatalogTest {

    private val tree = mapOf(
        "fdmprinter" to """{"name":"Generic","metadata":{"visible":false,"machine_extruder_trains":{"0":"fdmextruder"}}}""",
        "fdmextruder" to """{"name":"Generic extruder"}""",
        "acme_base" to """{"name":"Acme base","inherits":"fdmprinter","metadata":{"machine_extruder_trains":{"0":"acme_base_extruder_0"}}}""",
        "acme_base_extruder_0" to """{"name":"Acme extruder","inherits":"fdmextruder"}""",
        "acme_one" to """{"name":"Acme One","inherits":"acme_base"}""",
        "acme_one_variant" to """{"name":"Acme One Variant","inherits":"acme_one"}""",
        "acme_hidden" to """{"name":"Acme Hidden","inherits":"acme_base","metadata":{"visible":false}}""",
        "orphan" to """{"name":"Orphan"}""",
        "broken_train" to """{"name":"Broken","inherits":"acme_base","metadata":{"machine_extruder_trains":{"0":"gone_extruder_0"}}}""",
        "dual" to """{"name":"Dual","inherits":"fdmprinter","metadata":{"machine_extruder_trains":{"0":"dual_z","1":"dual_a"}}}""",
        "dual_z" to """{"name":"Z train","inherits":"fdmextruder"}""",
        "dual_a" to """{"name":"A train","inherits":"fdmextruder"}""",
        "nameless_thing" to """{"inherits":"fdmprinter"}""",
    )

    private fun load(id: String): String? = tree[id]

    @Test
    fun `lists machines, not shared bases, trains, hidden or broken definitions`() {
        val ids = CuraMachineCatalog.machinesFrom(tree.keys, ::load).map { it.id }
        // acme_one is a parent (acme_one_variant inherits it) and must still be offered;
        // acme_base is visible but is shared scaffolding, fdmprinter is visible:false.
        assertEquals(listOf("acme_one", "acme_one_variant", "dual", "nameless_thing"), ids.sorted())
    }

    @Test
    fun `a visible base other definitions inherit is not a machine`() {
        val ids = CuraMachineCatalog.machinesFrom(tree.keys, ::load).map { it.id }
        assertTrue("acme_base" !in ids)
        assertTrue("acme_base_extruder_0" !in ids)
    }

    @Test
    fun `labels use the definition name and fall back to the identifier`() {
        val machines = CuraMachineCatalog.machinesFrom(tree.keys, ::load).associateBy { it.id }
        assertEquals("Acme One", machines.getValue("acme_one").name)
        assertEquals("nameless thing", machines.getValue("nameless_thing").name)
        assertEquals("acme_one.def.json", machines.getValue("acme_one").file)
    }

    @Test
    fun `closure carries the machine, its parents and the extruder train`() {
        val closure = CuraMachineCatalog.closureOf("acme_one", ::load)!!
        assertEquals("acme_one.def.json", closure.machineFile)
        assertEquals("acme_base_extruder_0.def.json", closure.extruderFile)
        assertEquals(
            listOf(
                "acme_one.def.json",
                "acme_base.def.json",
                "fdmprinter.def.json",
                "acme_base_extruder_0.def.json",
                "fdmextruder.def.json",
            ),
            closure.files,
        )
    }

    @Test
    fun `slot zero picks the extruder train`() {
        assertEquals("dual_z.def.json", CuraMachineCatalog.closureOf("dual", ::load)!!.extruderFile)
    }

    @Test
    fun `a chain with a missing file has no closure`() {
        assertNull(CuraMachineCatalog.closureOf("broken_train", ::load))
        assertNull(CuraMachineCatalog.closureOf("not_a_machine", ::load))
        assertNull(CuraMachineCatalog.closureOf("acme_base", { if (it == "fdmprinter") null else load(it) }))
    }

    @Test
    fun `an empty catalogue is empty rather than failing`() {
        assertTrue(CuraMachineCatalog.machinesFrom(emptyList(), ::load).isEmpty())
        assertTrue(CuraMachineCatalog.machinesFrom(listOf("gone"), ::load).isEmpty())
    }
}
