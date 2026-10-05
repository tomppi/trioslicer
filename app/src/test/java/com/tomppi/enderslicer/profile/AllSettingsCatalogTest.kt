package com.tomppi.enderslicer.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.model.AllSettingsCatalogs
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Cura all-settings catalog has to carry every setting the definitions declare.
 *
 * It did not. A node that is both a setting and a parent was recursed into and then
 * skipped, so its own key never reached the catalog - 79 settings, among them
 * print acceleration and jerk. The app looked like it had no acceleration setting,
 * and the All-settings sheet could not offer one. Nothing failed, because the
 * catalog is built by walking JSON and every individual step was valid; only the
 * total was wrong, and no test compared the total to anything.
 *
 * This one does: it walks the same definitions independently and demands that the
 * catalog knows every key they declare.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AllSettingsCatalogTest {

    private val assets get() = ApplicationProvider.getApplicationContext<Context>().assets

    /** Every key the definitions declare, gathered without assuming anything about nesting. */
    private fun declaredKeys(node: JSONObject, into: MutableSet<String>) {
        val names = node.keys()
        while (names.hasNext()) {
            val name = names.next()
            val child = node.optJSONObject(name) ?: continue
            if (child.has("type") && child.has("default_value")) into.add(name)
            declaredKeys(child, into)
        }
    }

    private fun everyDeclaredKey(): Set<String> {
        val keys = mutableSetOf<String>()
        for (path in listOf(
            "cura/definitions/fdmprinter.def.json",
            "cura/definitions/creality_base.def.json",
            "cura/definitions/creality_ender3.def.json",
        )) {
            val root = JSONObject(assets.open(path).bufferedReader().use { it.readText() })
            root.optJSONObject("settings")?.let { declaredKeys(it, keys) }
        }
        return keys
    }

    @Test
    fun theCatalogCarriesEverySettingTheDefinitionsDeclare() {
        val catalog = AllSettingsCatalogs.cura(assets).map { it.key }.toSet()
        assertTrue("the catalog came back empty, so this test proves nothing", catalog.isNotEmpty())
        val missing = everyDeclaredKey() - catalog
        assertTrue(
            "settings the definitions declare but the catalog cannot offer: " +
                missing.sorted().joinToString(", "),
            missing.isEmpty(),
        )
    }

    @Test
    fun theSettingsThatWereLostArePresent() {
        val catalog = AllSettingsCatalogs.cura(assets).map { it.key }.toSet()
        for (key in listOf(
            "acceleration_print", "acceleration_wall", "acceleration_layer_0",
            "jerk_print", "jerk_wall", "line_width", "infill_sparse_density",
            "material_flow", "brim_width",
        )) {
            assertTrue("$key is missing from the Cura all-settings catalog", key in catalog)
        }
    }
}
