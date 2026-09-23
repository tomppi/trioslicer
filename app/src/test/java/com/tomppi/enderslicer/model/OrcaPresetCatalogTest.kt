package com.tomppi.enderslicer.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vendor index and the machine profile are the two files the app reads to offer a printer,
 * so both parsers are pinned against the exact field names the bundled bundles use.
 */
class OrcaPresetCatalogTest {

    private val vendorIndex = """
        {
          "name": "Creality",
          "version": "2.0.0",
          "machine_list": [
            { "name": "fdm_creality_common", "sub_path": "machine/fdm_creality_common.json" },
            { "name": "Creality Ender-3 V2 0.4 nozzle", "sub_path": "machine/Creality Ender-3 V2 0.4 nozzle.json" }
          ],
          "process_list": [
            { "name": "0.20mm Standard @Creality Ender3V2", "sub_path": "process/0.20mm Standard @Creality Ender3V2.json" }
          ]
        }
    """.trimIndent()

    @Test
    fun readsTheMachinesAVendorShips() {
        val machines = OrcaPresetCatalog.machinesFromVendorIndex(vendorIndex)

        assertEquals(2, machines.size)
        assertEquals("Creality Ender-3 V2 0.4 nozzle", machines[1].name)
        assertEquals("machine/Creality Ender-3 V2 0.4 nozzle.json", machines[1].subPath)
    }

    @Test
    fun readsWhatAMachinePreselects() {
        // The two fields are the ones the console falls back to when the app names only a
        // printer, so the app shows exactly what a slice with this machine will use.
        val defaults = OrcaPresetCatalog.defaultsFromMachineProfile(
            """
            {
              "type": "machine",
              "name": "Creality Ender-3 V2 0.4 nozzle",
              "inherits": "fdm_creality_common",
              "default_print_profile": "0.20mm Standard @Creality Ender3V2",
              "default_filament_profile": ["Creality Generic PLA"],
              "nozzle_diameter": ["0.4"]
            }
            """.trimIndent(),
        )

        assertEquals("0.20mm Standard @Creality Ender3V2", defaults.processPreset)
        assertEquals("Creality Generic PLA", defaults.filamentPreset)
    }

    @Test
    fun aMachineWithoutDefaultsYieldsEmptyNames() {
        val defaults = OrcaPresetCatalog.defaultsFromMachineProfile("""{ "type": "machine" }""")

        assertEquals("", defaults.processPreset)
        assertEquals("", defaults.filamentPreset)
        assertTrue(defaults.processPreset.isEmpty() && defaults.filamentPreset.isEmpty())
    }

    @Test
    fun machinesTheEngineCannotInstantiateAreNotOffered() {
        // The index lists the shared base templates beside the printers; their own
        // profiles set instantiation: false because they exist only to be inherited, and
        // the default process they name is not in the bundle, so offering one made every
        // later Orca slice fail. The default Creality Ender-3 V2 entry must survive.
        val machines = OrcaPresetCatalog.machinesFromVendorIndex(vendorIndex) { machine ->
            machine.name != "fdm_creality_common"
        }

        assertEquals(listOf("Creality Ender-3 V2 0.4 nozzle"), machines.map { it.name })
        assertTrue(machines.any { it.name == OrcaSliceSettings.DEFAULT_PRINTER_PRESET })
    }

    @Test
    fun aMalformedIndexYieldsNoMachinesInsteadOfFailing() {
        assertEquals(emptyList<OrcaPresetCatalog.Machine>(), OrcaPresetCatalog.machinesFromVendorIndex("not json"))
    }

    /**
     * The base-preset lookup an imported profile goes through, against the tree the app ships
     * rather than a fixture: a profile states the preset it inherits from, and the app has to find
     * the vendor that carries it to select it.
     */
    @Test
    fun findsTheBasePresetAnImportedProfileInheritsFrom() {
        val base = OrcaPresetCatalog.findInstantiableBase(
            read = ::assetText,
            vendorIds = vendorIds(),
            kind = OrcaPresetKind.PROCESS,
            name = "0.20mm Standard @Creality Ender3V2",
        )

        assertNotNull(base)
        assertEquals("Creality", base!!.vendorId)
        assertEquals("process/0.20mm Standard @Creality Ender3V2.json", base.subPath)
        assertTrue(
            base.compatiblePrinters.toString(),
            base.compatiblePrinters.contains(OrcaSliceSettings.DEFAULT_PRINTER_PRESET),
        )
    }

    /**
     * The shared templates are listed in the same index, but a profile that inherits one is not a
     * profile the engine can slice: it has no printable area, so it must never be selected.
     */
    @Test
    fun aBaseTemplateIsNotOfferedAsAPreset() {
        val base = OrcaPresetCatalog.findInstantiableBase(
            read = ::assetText,
            vendorIds = vendorIds(),
            kind = OrcaPresetKind.PROCESS,
            name = "fdm_process_creality_common",
        )

        assertNull(base)
    }

    @Test
    fun anUnknownBaseNameResolvesToNothing() {
        val base = OrcaPresetCatalog.findInstantiableBase(
            read = ::assetText,
            vendorIds = vendorIds(),
            kind = OrcaPresetKind.PROCESS,
            name = "0.20mm Standard @Nonexistent Vendor",
        )

        assertNull(base)
    }

    private fun vendorIds(): List<String> = File(assets, "orca/resources/profiles")
        .listFiles { file -> file.extension == "json" }
        ?.map { it.nameWithoutExtension }
        ?.sorted()
        .orEmpty()

    /** The reader receives the same asset-relative path the AssetManager version opens. */
    private fun assetText(path: String): String? =
        File(assets, path).takeIf { it.isFile }?.readText()

    private companion object {
        /** Gradle runs unit tests from the module directory; a root-relative run still resolves. */
        private val assets = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .first { it.isDirectory }
    }
}
