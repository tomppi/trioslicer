package com.tomppi.enderslicer.model

import com.tomppi.enderslicer.engine.OrcaConfigWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OrcaSlicer profile import, against the shapes the desktop app actually writes.
 *
 * The classification rule is Orca's own (PresetBundle::import_json_presets dispatches on
 * printer_settings_id / print_settings_id / filament_settings_id), the key vocabulary is the one
 * the engine's bundled profiles use, and the override filter is held to the engine's own
 * --dump-settings catalogue. The last test walks the whole bundled Creality tree so the rules are
 * checked against a thousand real profiles and not only against hand-written ones.
 */
class OrcaProfileImporterTest {

    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
    private val catalog: List<ExtraSettingSpec> by lazy {
        AllSettingsCatalogs.orcaFromJson(File(assets, "orca/all-settings.json").readText())
    }

    private fun parse(json: String, name: String = "profile.json") =
        OrcaProfileImporter.parse(name, json.toByteArray(), catalog)

    private fun bundle(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((entryName, content) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun aProcessPresetLandsOnTheAppFieldsAndTheRestTravelAsOverrides() {
        val result = parse(
            JSONObject()
                .put("name", "My 0.12 profile")
                .put("from", "User")
                .put("version", "2.4.2.1")
                .put("print_settings_id", "My 0.12 profile")
                .put("inherits", "0.20mm Standard @Creality Ender3V2")
                .put("layer_height", "0.12")
                .put("initial_layer_print_height", "0.2")
                .put("wall_loops", "4")
                .put("sparse_infill_density", "25%")
                .put("sparse_infill_pattern", "gyroid")
                .put("support_threshold_angle", "45")
                .put("inner_wall_speed", "60")
                .put("nozzle_temperature", "230")
                .put("hot_plate_temp", "65")
                .put("filament_flow_ratio", 0.97)
                .put("enable_support", "1")
                .put("seam_position", "aligned")
                .put("ironing_flow", "12%")
                .put("not_an_orca_option", "1")
                .toString(),
        )

        val preset = result.presets.single()
        assertEquals(OrcaProfileImporter.Kind.PROCESS, preset.kind)
        assertEquals("My 0.12 profile", preset.name)
        assertEquals("0.20mm Standard @Creality Ender3V2", preset.inherits)

        val applied = result.applyTo(OrcaSliceSettings())
        assertEquals(0.12, applied.layerHeightMm, 1e-9)
        assertEquals(0.2, applied.firstLayerHeightMm, 1e-9)
        assertEquals(4, applied.wallLoops)
        assertEquals(25.0, applied.sparseInfillDensityPercent, 1e-9)
        assertEquals("gyroid", applied.sparseInfillPattern)
        assertEquals(45, applied.supportThresholdAngleDegrees)
        assertEquals(60.0, applied.innerWallSpeedMmPerSecond, 1e-9)
        assertEquals(230, applied.nozzleTemperatureC)
        assertEquals(65, applied.hotPlateTemperatureC)
        assertEquals(97.0, applied.filamentFlowRatioPercent, 1e-9)
        assertTrue(applied.supportEnabled)
        // A field the profile does not state keeps whatever the sheet had.
        assertEquals(OrcaSliceSettings().travelSpeedMmPerSecond, applied.travelSpeedMmPerSecond, 1e-9)

        // Keys the engine declares, but that have no editor here, ride along as overrides.
        assertEquals("aligned", result.extraKeys["seam_position"])
        assertEquals("12%", result.extraKeys["ironing_flow"])
        // Keys this engine does not declare are dropped rather than sent to it.
        assertFalse(result.extraKeys.containsKey("not_an_orca_option"))
        assertTrue(result.refusedKeys.isEmpty())
    }

    @Test
    fun aNumericKeyStillRefusesAValueThatIsNotANumber() {
        val result = parse(
            JSONObject()
                .put("print_settings_id", "p")
                .put("initial_layer_infill_speed", "not-a-number")
                .put("top_surface_pattern", "monotonicline")
                .toString(),
        )
        assertEquals(listOf("initial_layer_infill_speed"), result.refusedKeys)
        assertEquals("monotonicline", result.extraKeys["top_surface_pattern"])
    }

    @Test
    fun aFilamentPresetSetsTheFilamentFieldsAndTheSpoolDiameter() {
        val result = parse(
            JSONObject()
                .put("name", "My PETG")
                .put("filament_settings_id", "My PETG")
                .put("filament_type", org.json.JSONArray().put("PETG"))
                .put("filament_diameter", org.json.JSONArray().put("1.75"))
                .put("nozzle_temperature", org.json.JSONArray().put("240"))
                .put("nozzle_temperature_initial_layer", org.json.JSONArray().put("245"))
                .put("hot_plate_temp", org.json.JSONArray().put("80"))
                .put("fan_max_speed", org.json.JSONArray().put("60"))
                .put("filament_flow_ratio", org.json.JSONArray().put("0.95"))
                .toString(),
        )

        assertEquals(OrcaProfileImporter.Kind.FILAMENT, result.presets.single().kind)
        val applied = result.applyTo(OrcaSliceSettings())
        assertEquals("PETG", applied.filamentType)
        assertEquals(240, applied.nozzleTemperatureC)
        assertEquals(245, applied.initialLayerNozzleTemperatureC)
        assertEquals(80, applied.hotPlateTemperatureC)
        assertEquals(60, applied.fanMaxSpeedPercent)
        assertEquals(95.0, applied.filamentFlowRatioPercent, 1e-9)
        assertEquals(1.75, result.machine.filamentDiameterMm!!, 1e-9)
    }

    @Test
    fun aPrinterPresetSetsTheMachineEnvelopeAndReportsTheGcodeItCannotCarry() {
        val result = parse(
            JSONObject()
                .put("name", "Ender 3 V2 0.6")
                .put("type", "machine")
                .put("printer_settings_id", "Ender 3 V2 0.6")
                .put("printable_area", org.json.JSONArray(listOf("0x0", "220x0", "220x220", "0x220")))
                .put("printable_height", "250")
                .put("nozzle_diameter", org.json.JSONArray().put("0.6"))
                .put("gcode_flavor", "klipper")
                .put("machine_start_gcode", "G28")
                .put("machine_end_gcode", "M84")
                .toString(),
        )

        assertEquals(OrcaProfileImporter.Kind.PRINTER, result.presets.single().kind)
        assertEquals(220.0, result.machine.widthMm!!, 1e-9)
        assertEquals(220.0, result.machine.depthMm!!, 1e-9)
        assertEquals(false, result.machine.originAtCenter)
        assertEquals(250.0, result.machine.heightMm!!, 1e-9)
        assertEquals(0.6, result.machine.nozzleSizeMm!!, 1e-9)
        assertEquals("Klipper", result.machine.gcodeFlavor)
        assertTrue(
            result.notes.toString(),
            result.notes.any { it.contains("machine G-code") },
        )
    }

    @Test
    fun aBedCentredOnZeroIsRecognised() {
        val result = parse(
            JSONObject()
                .put("printer_settings_id", "centered")
                .put(
                    "printable_area",
                    org.json.JSONArray(listOf("-110x-110", "110x-110", "110x110", "-110x110")),
                )
                .toString(),
        )

        assertEquals(220.0, result.machine.widthMm!!, 1e-9)
        assertEquals(220.0, result.machine.depthMm!!, 1e-9)
        assertEquals(true, result.machine.originAtCenter)
    }

    @Test
    fun aBundleAppliesItsPrinterProcessAndFilament() {
        val process = JSONObject()
            .put("name", "bundle process")
            .put("print_settings_id", "bundle process")
            .put("layer_height", "0.28")
            .toString()
        val filament = JSONObject()
            .put("name", "bundle filament")
            .put("filament_settings_id", "bundle filament")
            .put("filament_type", org.json.JSONArray().put("ABS"))
            .toString()
        val printer = JSONObject()
            .put("name", "bundle printer")
            .put("printer_settings_id", "bundle printer")
            .put("printable_area", org.json.JSONArray(listOf("0x0", "300x0", "300x300", "0x300")))
            .toString()

        val bytes = bundle(
            "bundle_structure.json" to JSONObject().put("bundle_id", "abc").toString(),
            "user/default/process/bundle process.json" to process,
            "user/default/filament/bundle filament.json" to filament,
            "user/default/machine/bundle printer.json" to printer,
            "thumbnail/cover.png" to "not json",
        )
        val result = OrcaProfileImporter.parse("bundle.orca_bundle", bytes, catalog)

        assertEquals(
            listOf(
                OrcaProfileImporter.Kind.PRINTER,
                OrcaProfileImporter.Kind.PROCESS,
                OrcaProfileImporter.Kind.FILAMENT,
            ),
            result.presets.map { it.kind },
        )
        assertEquals(0.28, result.applyTo(OrcaSliceSettings()).layerHeightMm, 1e-9)
        assertEquals("ABS", result.applyTo(OrcaSliceSettings()).filamentType)
        assertEquals(300.0, result.machine.widthMm!!, 1e-9)
        assertEquals(0, result.skippedPresetCount)
    }

    /**
     * A vendor bundle can hold hundreds of presets of one kind. Applying each in turn would leave
     * whichever happened to be last, so the first by name is applied and the rest are counted.
     */
    @Test
    fun onlyTheFirstPresetOfAKindIsApplied() {
        val bytes = bundle(
            "process/b.json" to JSONObject().put("name", "b").put("print_settings_id", "b").put("layer_height", "0.3").toString(),
            "process/a.json" to JSONObject().put("name", "a").put("print_settings_id", "a").put("layer_height", "0.1").toString(),
            "process/c.json" to JSONObject().put("name", "c").put("print_settings_id", "c").put("layer_height", "0.5").toString(),
        )
        val result = OrcaProfileImporter.parse("bundle.zip", bytes, catalog)

        assertEquals("a", result.presets.single().name)
        assertEquals(0.1, result.applyTo(OrcaSliceSettings()).layerHeightMm, 1e-9)
        assertEquals(2, result.skippedPresetCount)
    }

    @Test
    fun whatTheConfigWriterWritesIsWhatTheImporterReadsBack() {
        val printer = com.tomppi.enderslicer.model.PrinterDefinition(
            name = "Modified Ender 3 V2",
            widthMm = 220.0, depthMm = 220.0, heightMm = 250.0,
            buildPlateShape = "rectangular", originAtCenter = false,
            heatedBed = true, heatedBuildVolume = false,
            gcodeFlavor = "Marlin", extruders = 1,
            nozzleSizeMm = 0.4, filamentDiameterMm = 1.75,
            printheadXMinMm = -26.0, printheadYMinMm = -32.0,
            printheadXMaxMm = 32.0, printheadYMaxMm = 34.0,
            gantryHeightMm = 25.0,
        )
        val settings = OrcaSliceSettings(
            firstLayerHeightMm = 0.24,
            wallLoops = 3,
            topShellLayers = 4,
            bottomShellLayers = 5,
            sparseInfillDensityPercent = 15.0,
            sparseInfillPattern = "grid",
            skirtLoops = 2,
            brimWidthMm = 4.0,
            supportEnabled = true,
            supportThresholdAngleDegrees = 55,
            supportBasePattern = "rectilinear",
            supportInterfaceTopLayers = 2,
            innerWallSpeedMmPerSecond = 40.0,
            outerWallSpeedMmPerSecond = 25.0,
            initialLayerSpeedMmPerSecond = 15.0,
            sparseInfillSpeedMmPerSecond = 50.0,
            internalSolidInfillSpeedMmPerSecond = 45.0,
            travelSpeedMmPerSecond = 150.0,
            nozzleTemperatureC = 210,
            initialLayerNozzleTemperatureC = 215,
            hotPlateTemperatureC = 60,
            initialLayerHotPlateTemperatureC = 65,
            fanMaxSpeedPercent = 100,
            fanMinSpeedPercent = 30,
            filamentType = "PLA",
            filamentFlowRatioPercent = 98.0,
            retractionLengthMm = 0.8,
            retractionSpeedMmPerSecond = 35.0,
            zHopMm = 0.4,
        )
        val rendered = OrcaConfigWriter.render(
            settings = settings,
            printer = printer,
            startGcode = "G28\nG1 Z5 F5000",
            endGcode = "M104 S0\nM84",
        )
        val document = JSONObject()
            .put("printer_settings_id", "round trip")
            .put("print_settings_id", "round trip")
            .put("filament_settings_id", "round trip")
        for ((id, bucket) in listOf("printer_settings_id" to rendered.printer, "print_settings_id" to rendered.print, "filament_settings_id" to rendered.filament)) {
            for (line in bucket.lineSequence()) {
                if (!line.contains(" = ")) continue
                document.put(line.substringBefore(" = ").trim(), line.substringAfter(" = "))
            }
        }

        val result = OrcaProfileImporter.parse("round-trip.json", document.toString().toByteArray(), catalog)

        // Every key the writer emits is one the importer places: only the three identifiers are
        // left over, so a key added to the writer without a home here fails this test.
        assertEquals(3, result.ignoredKeyCount)
        assertTrue(result.refusedKeys.toString(), result.refusedKeys.isEmpty())
        assertEquals(settings, result.applyTo(OrcaSliceSettings()))
        assertEquals(220.0, result.machine.widthMm!!, 1e-9)
        assertEquals(250.0, result.machine.heightMm!!, 1e-9)
        assertEquals(0.4, result.machine.nozzleSizeMm!!, 1e-9)
        assertEquals(1.75, result.machine.filamentDiameterMm!!, 1e-9)
        assertEquals("Marlin", result.machine.gcodeFlavor)
    }

    @Test
    fun refusesAnEmptyFile() {
        val error = runCatching { parse("") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("is empty"))
    }

    @Test
    fun refusesSomethingThatIsNotAPreset() {
        val error = runCatching { parse("{ not json") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("not an OrcaSlicer preset"))
    }

    @Test
    fun refusesADocumentThatNamesNoKindOfPreset() {
        val error = runCatching { parse("""{"some_key": "1"}""") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("does not look like an OrcaSlicer preset"))
    }

    /** A whole saved configuration mixes buckets, so no one collection can claim it. */
    @Test
    fun refusesAFullConfigurationThatMixesTheThreeKinds() {
        val mixed = JSONObject()
            .put("printable_area", org.json.JSONArray(listOf("0x0", "220x0", "220x220", "0x220")))
            .put("layer_height", "0.2")
            .toString()
        val error = runCatching { parse(mixed) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("does not look like an OrcaSlicer preset"))
    }

    @Test
    fun refusesAProfileLargerThanTheLimit() {
        val error = runCatching {
            OrcaProfileImporter.parse("big.json", ByteArray(OrcaProfileImporter.MAX_INPUT_BYTES + 1), catalog)
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("larger than"))
    }

    @Test
    fun refusesABundleWithTooManyEntries() {
        val entries = (1..70).map { "preset-$it.json" to """{"print_settings_id":"$it"}""" }
        val error = runCatching { OrcaProfileImporter.parse("many.zip", bundle(*entries.toTypedArray()), catalog) }
            .exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("more than 64 entries"))
    }

    @Test
    fun refusesAnEntryLargerThanTheLimit() {
        val huge = "x".repeat(4 * 1024 * 1024 + 1)
        val error = runCatching { OrcaProfileImporter.parse("huge.zip", bundle("preset.json" to huge), catalog) }
            .exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("larger than the 4 MiB limit"))
    }

    @Test
    fun refusesABundleThatHoldsNoJsonPreset() {
        val error = runCatching {
            OrcaProfileImporter.parse("empty.zip", bundle("readme.txt" to "hello"), catalog)
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("holds no JSON preset"))
    }

    @Test
    fun aJsonFileGetsTheSameTreatmentAsABundleInsideOne() {
        val inner = JSONObject()
            .put("print_settings_id", "inner")
            .put("layer_height", "0.16")
            .toString()
        val direct = parse(inner, "direct.json")
        val packed = OrcaProfileImporter.parse("bundle.orca_process", bundle("process/inner.json" to inner), catalog)

        assertEquals(direct.values, packed.values)
        assertEquals(direct.extraKeys, packed.extraKeys)
    }

    /**
     * The whole bundled Creality tree: every process, filament and machine preset the app ships
     * must import without an unclassified document and without a single value the engine's own
     * catalogue refuses. A rule that only fits hand-written fixtures fails here.
     */
    @Test
    fun everyBundledCrealityPresetImportsCleanly() {
        val expected = listOf(
            "process" to OrcaProfileImporter.Kind.PROCESS,
            "filament" to OrcaProfileImporter.Kind.FILAMENT,
            "machine" to OrcaProfileImporter.Kind.PRINTER,
        )
        val problems = mutableListOf<String>()
        var files = 0
        var refusedCount = 0
        for ((directory, kind) in expected) {
            val presets = File(assets, "orca/resources/profiles/Creality/$directory")
                .listFiles { file -> file.extension == "json" }
                ?.sortedBy { it.name }
                .orEmpty()
            assertTrue("no $directory presets under $assets", presets.isNotEmpty())
            for (file in presets) {
                files++
                val result = runCatching {
                    OrcaProfileImporter.parse(file.name, file.readBytes(), catalog)
                }.getOrElse { error ->
                    problems += file.name + ": " + error.message
                    continue
                }
                val preset = result.presets.singleOrNull()
                if (preset == null || preset.kind != kind) {
                    problems += file.name + ": classified as " + result.presets.map { it.kind }
                    continue
                }
                // A value the engine stores across several lines (the small-area flow model is a
                // table) cannot travel as one ini line, so refusing it is the rule. Anything else
                // refused would mean the importer rejects values the engine itself wrote.
                for (refused in result.refusedKeys) {
                    refusedCount++
                    val raw = preset.values[refused].orEmpty()
                    val unsendable = raw.contains('\n') || raw.contains('\r') ||
                        raw.length > ExtraSettingValidation.MAX_VALUE_CHARS
                    if (!unsendable) {
                        problems += file.name + ": refused a sendable value for " + refused
                    }
                }
            }
        }
        assertEquals(problems.take(20).toString(), emptyList<String>(), problems)
        assertTrue("only $files presets were checked", files >= 300)
        // The refusal rule above has to be exercised by real data, not just described by it.
        assertTrue("no multi-line value was refused anywhere in the tree", refusedCount > 0)
    }

    @Test
    fun theRealCrealityProcessPresetKeepsItsInheritance() {
        val file = File(assets, "orca/resources/profiles/Creality/process/0.20mm Standard @Creality Ender3V2.json")
        assertTrue("missing " + file.path, file.isFile)

        val result = OrcaProfileImporter.parse(file.name, file.readBytes(), catalog)

        val preset = result.presets.single()
        assertEquals(OrcaProfileImporter.Kind.PROCESS, preset.kind)
        assertEquals("0.20mm Standard @Creality Ender3V2", preset.name)
        assertEquals("fdm_process_creality_common", preset.inherits)
        assertEquals("0.2", result.values["layer_height"])
        assertTrue("no overrides were read", result.extraKeys.isNotEmpty())
        assertTrue(result.refusedKeys.toString(), result.refusedKeys.isEmpty())
        assertNull(result.machine.gcodeFlavor)
    }
}
