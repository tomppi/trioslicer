package com.tomppi.enderslicer.model

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The PrusaSlicer All-settings catalogue is derived, not dumped.
 *
 * The 3.0 launcher has no --dump-settings: its whole command line is
 * --datadir/--load/--export-gcode, and every other flag prints the usage stub.
 * AllSettingsCatalogs.prusa read a file nothing generated, runCatching turned the
 * missing asset into an empty list, and the sheet was empty in every build.
 * scripts/generate-prusa-all-settings.py now derives the committed catalogue from
 * prusa3-base.json and the preset repository view.
 *
 * This test is what keeps that from regressing silently: the catalogue has to
 * cover the resolved base configuration and state the types and defaults it
 * implies. It skips only when the asset is absent, like the other real-data
 * tests; CI runs the fetch script, which regenerates it before the unit tests.
 */
class PrusaAllSettingsCatalogTest {

    private val assets = File("src/main/assets")
    private val catalogueFile = File(assets, "prusa/all-settings.json")
    private val baseFile = File(assets, "prusa3-base.json")

    private val buckets = listOf("print_settings", "printer_settings", "filament_settings")

    private val catalogue: Map<String, JSONObject> by lazy {
        val settings = JSONObject(catalogueFile.readText()).getJSONArray("settings")
        val entries = linkedMapOf<String, JSONObject>()
        for (index in 0 until settings.length()) {
            val item = settings.getJSONObject(index)
            entries[item.getString("key")] = item
        }
        entries
    }

    private val baseKeys: Set<String> by lazy {
        val configuration = JSONObject(baseFile.readText()).getJSONObject("configuration")
        buckets.flatMap { bucket ->
            configuration.getJSONObject(bucket).keys().asSequence().toList()
        }.toSet()
    }

    @Test
    fun `catalogue covers every key of the shipped base configuration`() {
        assumeTrue(catalogueFile.isFile)
        assertTrue("the Prusa all-settings catalogue is empty", catalogue.isNotEmpty())
        assertTrue("only ${catalogue.size} entries", catalogue.size >= 300)
        assertEquals(baseKeys, catalogue.keys)
    }

    @Test
    fun `catalogue states the type and default the base configuration implies`() {
        assumeTrue(catalogueFile.isFile)
        // A string enum, a FloatOrPercent-capable speed the base saved as a plain
        // number, the per-slot filament temperatures, a percentage, a
        // {value, is_percent} object and a point list.
        assertEntry("fill_pattern", "string", "grid")
        assertEntry("perimeter_speed", "float", "170")
        assertEntry("first_layer_temperature", "ints", "230")
        assertEntry("fill_density", "percent", "15%")
        assertEntry("first_layer_height", "float_or_percent", "0.2")
        assertEntry("bed_shape", "points", "0x0,250x0,250x210,0x210")
    }

    @Test
    fun `the choices the preset repository states reach the catalogue`() {
        assumeTrue(catalogueFile.isFile)
        val choices = catalogue.getValue("fill_pattern").getJSONArray("values")
        val values = (0 until choices.length()).map(choices::getString)
        assertTrue("fill_pattern choices: $values", values.size >= 2)
        assertTrue("fill_pattern choices: $values", "grid" in values)
    }

    private fun assertEntry(key: String, type: String, default: String) {
        val entry = catalogue[key] ?: error("the catalogue is missing $key")
        assertEquals("type of $key", type, entry.getString("type"))
        assertEquals("default of $key", default, entry.getString("default"))
    }
}
