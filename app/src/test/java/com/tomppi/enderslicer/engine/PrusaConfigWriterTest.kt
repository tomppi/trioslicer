package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.AllSettingsCatalogs
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaSliceSettings
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the 3.x JSON config writer against the shipped MK4 base config.
 * The value shapes mirror PrusaSlicer 3.x: Percentage and FloatOrPercentage
 * are {value, is_percent} objects and filament values are per-slot arrays.
 */
class PrusaConfigWriterTest {

    private val baseConfig: String by lazy { File("src/main/assets/prusa3-base.json").readText() }

    private val printer = PrinterDefinition(
        name = "Modified Ender 3 V2",
        widthMm = 230.0, depthMm = 230.0, heightMm = 250.0,
        buildPlateShape = "rectangular", originAtCenter = false,
        heatedBed = true, heatedBuildVolume = false,
        gcodeFlavor = "Marlin", extruders = 1,
        nozzleSizeMm = 0.4, filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0, printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0, printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
    )

    private val settings = PrusaSliceSettings(
        layerHeightMm = 0.2,
        firstLayerHeightMm = 0.3,
        perimeters = 3,
        topSolidLayers = 4,
        bottomSolidLayers = 5,
        fillDensityPercent = 15.0,
        fillPattern = "grid",
        supportMaterial = true,
        supportThresholdAngleDegrees = 55.0,
        supportInterface = true,
        supportInterfaceLayers = 2,
        printSpeedMmPerSecond = 60.0,
        externalPerimeterSpeedMmPerSecond = 25.0,
        infillSpeedMmPerSecond = 50.0,
        firstLayerSpeedMmPerSecond = 20.0,
        travelSpeedMmPerSecond = 150.0,
        nozzleTemperatureC = 210,
        bedTemperatureC = 60,
        retractionLengthMm = 0.8,
        retractionSpeedMmPerSecond = 45.0,
        useFirmwareRetraction = false,
        extrusionMultiplierPercent = 100.0,
    )

    private fun config(vararg args: Any): JSONObject =
        JSONObject(PrusaConfigWriter.render(settings, printer, args[0] as String, args[1] as String, baseConfig))

    private fun printBucket(cfg: JSONObject): JSONObject =
        cfg.getJSONObject("configuration").getJSONObject("print_settings")

    private fun printerBucket(cfg: JSONObject): JSONObject =
        cfg.getJSONObject("configuration").getJSONObject("printer_settings")

    private fun filamentBucket(cfg: JSONObject): JSONObject =
        cfg.getJSONObject("configuration").getJSONObject("filament_settings")

    @Test
    fun writesCorePrintKeysWithPrusaNames() {
        val print = printBucket(config("G28\nG1 X10", "M84"))
        assertEquals(0.2, print.getDouble("layer_height"), 1e-9)
        assertEquals(0.3, print.getJSONObject("first_layer_height").getDouble("value"), 1e-9)
        assertFalse(print.getJSONObject("first_layer_height").getBoolean("is_percent"))
        assertEquals(3, print.getInt("perimeters"))
        assertEquals(4, print.getInt("top_solid_layers"))
        assertEquals(5, print.getInt("bottom_solid_layers"))
        assertEquals(15.0, print.getJSONObject("fill_density").getDouble("value"), 1e-9)
        assertTrue(print.getJSONObject("fill_density").getBoolean("is_percent"))
        assertEquals("grid", print.getString("fill_pattern"))
        assertEquals("everywhere", print.getString("support_material"))
        assertEquals(55, print.getInt("support_material_threshold"))
        assertEquals(2, print.getInt("support_material_interface_layers"))
        assertEquals(60.0, print.getJSONObject("perimeter_speed").getDouble("value"), 1e-9)
        assertEquals(25.0, print.getJSONObject("external_perimeter_speed").getDouble("value"), 1e-9)
        assertEquals(50.0, print.getJSONObject("infill_speed").getDouble("value"), 1e-9)
        assertEquals(20.0, print.getJSONObject("first_layer_speed").getDouble("value"), 1e-9)
        assertEquals(150.0, print.getDouble("travel_speed"), 1e-9)
        assertEquals(0.8, print.getDouble("retract_length"), 1e-9)
        assertEquals(45.0, print.getDouble("retract_speed"), 1e-9)
    }

    @Test
    fun writesMachineAndFilamentKeys() {
        val cfg = config("G28", "M84")
        assertEquals("marlin2", printerBucket(cfg).getString("gcode_flavor"))
        val bedShape = printerBucket(cfg).getJSONArray("bed_shape")
        assertEquals("[[0,0],[230,0],[230,230],[0,230]]", bedShape.toString().replace(" ", ""))
        val filament = filamentBucket(cfg)
        assertEquals("1.75", filament.getJSONArray("filament_diameter").getDouble(0).toString())
        assertEquals(210, filament.getJSONArray("temperature").getInt(0))
        assertEquals(60, filament.getJSONArray("bed_temperature").getInt(0))
        assertEquals(100, filament.getJSONArray("max_fan_speed").getInt(0))
        assertEquals(1.0, filament.getJSONArray("extrusion_multiplier").getDouble(0), 1e-9)
        assertEquals("Ender", printerBucket(cfg).getString("printer_model"))
    }

    @Test
    fun keepsStartGcodeAsJsonStringWithNewlines() {
        val cfg = config("G28\nG1 X10 F3000\nG1 Y10", "M84")
        val start = printerBucket(cfg).getString("start_gcode")
        assertTrue(start.startsWith("G28\nG1 X10 F3000\nG1 Y10"))
        assertTrue(start.contains("\n"))
    }

    @Test
    fun valueShapesMatchConsoleConfigLoad() {
        val cfg = config("G28", "M84")
        // The 3.x launcher accepts the --save JSON format: preset + configuration.
        assertTrue(cfg.has("preset"))
        assertTrue(cfg.has("configuration"))
        val configuration = cfg.getJSONObject("configuration")
        assertFalse(configuration.has("invalid"))
        // Filament values are per-slot arrays (ConfigLoad validates this shape).
        assertTrue(filamentBucket(cfg).get("temperature") is JSONArray)
        // Percentage types are objects with the value/is_percent pair.
        val fill = printBucket(cfg).get("fill_density")
        assertTrue(fill is JSONObject)
    }

    @Test
    fun writesExtraCatalogKeysIntoPrintSettings() {
        val withExtras = PrusaSliceSettings(
            layerHeightMm = 0.2, firstLayerHeightMm = 0.3, perimeters = 2,
            extraKeys = mapOf("top_solid_infill_flow_ratio" to "0.9", "seam_position" to "nearest"),
        )
        val cfg = JSONObject(PrusaConfigWriter.render(withExtras, printer, "G28", "M84", baseConfig))
        val print = printBucket(cfg)
        assertEquals("0.9", print.getString("top_solid_infill_flow_ratio"))
        assertEquals("nearest", print.getString("seam_position"))
    }

    @Test
    fun managedKeyHintMatchesTheKeysTheWriterReallyWrites() {
        // The All-settings sheet marks AllSettingsCatalogs.PRUSA_MANAGED_KEYS as
        // "(managed by the app)". Rendering against an empty base config leaves
        // exactly the keys this writer writes, so the hint cannot drift from it.
        val withWidths = settings.copy(
            firstLayerExtrusionWidthMm = 0.45,
            perimeterExtrusionWidthMm = 0.45,
            externalPerimeterExtrusionWidthMm = 0.4,
            infillExtrusionWidthMm = 0.45,
            solidInfillExtrusionWidthMm = 0.45,
            topInfillExtrusionWidthMm = 0.45,
        )
        val cfg = JSONObject(
            PrusaConfigWriter.render(withWidths, printer, "G28", "M84", "{\"configuration\":{}}"),
        )

        val written = linkedSetOf<String>()
        val configuration = cfg.getJSONObject("configuration")
        listOf("print_settings", "printer_settings", "filament_settings").forEach { bucket ->
            val keys = configuration.getJSONObject(bucket).keys()
            while (keys.hasNext()) written += keys.next()
        }

        assertEquals(AllSettingsCatalogs.PRUSA_MANAGED_KEYS, written)
    }

    @Test
    fun rejectsAnExtraSettingValueTheLauncherCannotUse() {
        val withExtras = PrusaSliceSettings(
            layerHeightMm = 0.2,
            extraKeys = mapOf("seam_position" to " "),
        )

        val failure = runCatching {
            PrusaConfigWriter.render(withExtras, printer, "G28", "M84", baseConfig)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("seam_position"))
    }

    @Test
    fun centeredBedShapeUsesNegativeCoordinates() {
        val centered = printer.copy(originAtCenter = true)
        val cfg = JSONObject(PrusaConfigWriter.render(settings, centered, "G28", "M84", baseConfig))
        val bedShape = printerBucket(cfg).getJSONArray("bed_shape")
        assertEquals("[[-115,-115],[115,-115],[115,115],[-115,115]]", bedShape.toString().replace(" ", ""))
    }
}
