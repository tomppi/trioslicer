package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.conical.ConicalPreparations
import com.tomppi.enderslicer.conical.ConicalRuntime
import com.tomppi.enderslicer.conical.ConicalSettings
import com.tomppi.enderslicer.engine.CuraEngineCommand
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.nonplanar.NonPlanarRuntime
import com.tomppi.enderslicer.nonplanar.NonPlanarSettings
import com.tomppi.enderslicer.smartinfill.SmartInfillActivity
import com.tomppi.enderslicer.smartinfill.SmartInfillPackage
import com.tomppi.enderslicer.smartinfill.SmartInfillRuntime
import com.tomppi.enderslicer.supportpaint.SupportPaintModifier
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Settings-leak validation: when an advanced feature (arc/wave overhangs,
 * non-planar printing, conical slicing, Smart Infill, calibration) is OFF, nothing it
 * controls may fall through into the core CuraEngine slice. App-owned
 * `enderslicer_*` keys are always emitted because the patched engine has no
 * definition defaults for them, so the contract is value-neutrality: disabled
 * flags, no standard-key drift, no end-G-code sentinels, no modifier meshes,
 * and zero residue after an enable -> disable cycle.
 */
class AdvancedFeatureSettingsLeakTest {

    @Before
    fun resetFeatureState() = resetAllFeatures()

    @After
    fun clearFeatureState() = resetAllFeatures()

    @Test
    fun resolvedSliceWithAllFeaturesOffCarriesOnlyInertAppKeys() {
        val resolved = resolve(SlicerSettings())

        assertEquals("false", resolved.extruderValues["enderslicer_arc_overhang_enabled"])
        assertEquals("false", resolved.extruderValues["enderslicer_wave_overhang_enabled"])
        assertTrue(resolved.smartInfillModelValues.isEmpty())
        assertFalse(resolved.globalValues.getValue("machine_end_gcode").contains("ENDERSLICER"))

        val directory = Files.createTempDirectory("enderslicer-leak-off").toFile()
        try {
            val modelFile = File(directory, "current.stl")
            writeTriangle(modelFile, 100f, 100f, 1f)
            val destination = File(directory, "resolved-settings.json")

            CuraResolvedSettingsWriter.write(
                destination = destination,
                modelFileName = modelFile.name,
                resolved = resolved,
            )

            val root = JSONObject(destination.readText())
            assertEquals(
                "No modifier-mesh sections may appear when every feature is off",
                setOf("global", "extruder.0", "current.stl"),
                root.keys().asSequence().toSet(),
            )
            assertFalse(
                root.getJSONObject("global").getString("machine_end_gcode").contains("ENDERSLICER"),
            )
            val extruder = root.getJSONObject("extruder.0")
            assertEquals("false", extruder.getString("enderslicer_arc_overhang_enabled"))
            assertEquals("false", extruder.getString("enderslicer_wave_overhang_enabled"))
            assertEquals(0.0, extruder.getDouble("enderslicer_mesh_translation_x"), 1e-12)
            assertEquals(0.0, extruder.getDouble("enderslicer_mesh_translation_y"), 1e-12)
            assertEquals(0.0, extruder.getDouble("enderslicer_mesh_translation_z"), 1e-12)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun arcWaveTuningValuesCannotTouchCuraSettingsWhenDisabled() {
        val pristine = resolve(SlicerSettings())
        val tuned = resolve(
            SlicerSettings().copy(
                arcOverhangSpeedMmPerSecond = 7.5,
                arcOverhangFlowPercent = 150.0,
                waveOverhangLineSpacingMm = 0.9,
                waveOverhangMaxIterations = 999,
            ),
        )

        assertEquals("false", tuned.extruderValues["enderslicer_arc_overhang_enabled"])
        assertEquals("false", tuned.extruderValues["enderslicer_wave_overhang_enabled"])
        assertEquals(pristine.globalValues.withoutAppKeys(), tuned.globalValues.withoutAppKeys())
        assertEquals(pristine.extruderValues.withoutAppKeys(), tuned.extruderValues.withoutAppKeys())
        assertEquals(pristine.modelValues.withoutAppKeys(), tuned.modelValues.withoutAppKeys())

        val pristineCommand = buildStandaloneCommand(SlicerSettings()).withoutAppArguments()
        val tunedCommand = buildStandaloneCommand(
            SlicerSettings().copy(
                arcOverhangSpeedMmPerSecond = 7.5,
                arcOverhangFlowPercent = 150.0,
                waveOverhangLineSpacingMm = 0.9,
                waveOverhangMaxIterations = 999,
            ),
        ).withoutAppArguments()
        assertEquals(
            "Disabled arc/wave tuning values must not drift any standard -s argument",
            pristineCommand,
            tunedCommand,
        )
    }

    @Test
    fun standaloneCommandWithAllFeaturesOffCarriesOnlyInertAppKeys() {
        val command = buildStandaloneCommand(SlicerSettings())

        assertTrue(command.contains("enderslicer_arc_overhang_enabled=false"))
        assertTrue(command.contains("enderslicer_wave_overhang_enabled=false"))
        assertFalse(command.any { it.contains(NonPlanarRuntime.MACHINE_END_SENTINEL) })
        assertFalse(command.any { it.contains(ConicalRuntime.MACHINE_END_SENTINEL) })
        assertFalse(command.any { it.contains("infill_mesh=true") })
        assertFalse(command.any { it.contains("support_mesh=true") })
        assertFalse(command.any { it.contains("anti_overhang_mesh=true") })
        assertTrue(command.contains("adhesion_type=${SlicerSettings().adhesionType}"))
    }

    @Test
    fun conicalEnableThenDisableLeavesNoSettingsResidue() {
        val pristine = resolve(SlicerSettings())
        val storedSettings = SlicerSettings()

        ConicalRuntime.activate(ConicalSettings(enabled = true))
        assertTrue(ConicalRuntime.snapshot() != null)
        assertTrue(
            ConicalRuntime.markMachineEndGcode(END_GCODE)
                .contains(ConicalRuntime.MACHINE_END_SENTINEL),
        )

        val adjusted = ConicalPreparations.adjustSettings(storedSettings)
        assertEquals(ConicalPreparations.ADHESION_NONE, adjusted.adhesionType)
        assertTrue(adjusted.overriddenSettingKeys.contains(SlicerSettings.Keys.ADHESION_TYPE))
        assertEquals(
            "Conical preparations must adjust a slice-local copy, never the stored settings",
            SlicerSettings(),
            storedSettings,
        )

        ConicalRuntime.activate(ConicalSettings(enabled = false))
        assertNull(ConicalRuntime.snapshot())
        assertEquals(END_GCODE, ConicalRuntime.markMachineEndGcode(END_GCODE))
        assertEquals(pristine, resolve(SlicerSettings()))
    }

    @Test
    fun nonPlanarEnableThenDisableLeavesNoSettingsResidue() {
        val pristine = resolve(SlicerSettings())

        NonPlanarRuntime.activate(NonPlanarSettings(enabled = true))
        assertTrue(NonPlanarRuntime.snapshot() != null)
        assertTrue(
            NonPlanarRuntime.markMachineEndGcode(END_GCODE)
                .contains(NonPlanarRuntime.MACHINE_END_SENTINEL),
        )

        NonPlanarRuntime.activate(NonPlanarSettings(enabled = false))
        assertNull(NonPlanarRuntime.snapshot())
        assertEquals(END_GCODE, NonPlanarRuntime.markMachineEndGcode(END_GCODE))
        assertEquals(pristine, resolve(SlicerSettings()))
    }

    @Test
    fun smartInfillActivateThenDeactivateLeavesNoSettingsResidue() {
        val pristine = resolve(SlicerSettings())

        SmartInfillRuntime.activate(packageValue("leak-probe"))
        assertTrue(SmartInfillRuntime.current() != null)
        assertNotEquals(
            "An active Smart Infill package must change engine settings",
            pristine,
            resolve(SlicerSettings()),
        )

        SmartInfillRuntime.activate(null)
        assertNull(SmartInfillRuntime.current())
        assertEquals(pristine, resolve(SlicerSettings()))
    }

    @Test
    fun standaloneCommandIsPristineAfterAllFeatureCycles() {
        val pristine = buildStandaloneCommand(SlicerSettings())

        ConicalRuntime.activate(ConicalSettings(enabled = true))
        ConicalRuntime.activate(ConicalSettings(enabled = false))
        NonPlanarRuntime.activate(NonPlanarSettings(enabled = true))
        NonPlanarRuntime.activate(NonPlanarSettings(enabled = false))
        SmartInfillRuntime.activate(packageValue("cycle"))
        SmartInfillRuntime.activate(null)

        assertEquals(pristine, buildStandaloneCommand(SlicerSettings()))
    }

    @Test
    fun arcOverhangKeysReachEngineOnlyWhenEnabled() {
        val offResolved = resolve(SlicerSettings())
        val onResolved = resolve(SlicerSettings().copy(arcOverhangEnabled = true))
        assertEquals("false", offResolved.extruderValues["enderslicer_arc_overhang_enabled"])
        assertEquals("true", onResolved.extruderValues["enderslicer_arc_overhang_enabled"])
        assertEquals(
            offResolved.extruderValues.withoutAppKeys(),
            onResolved.extruderValues.withoutAppKeys(),
        )

        assertTrue(buildStandaloneCommand(SlicerSettings()).contains("enderslicer_arc_overhang_enabled=false"))
        assertTrue(
            buildStandaloneCommand(SlicerSettings().copy(arcOverhangEnabled = true))
                .contains("enderslicer_arc_overhang_enabled=true"),
        )
    }

    @Test
    fun waveOverhangKeysReachEngineOnlyWhenEnabled() {
        val offResolved = resolve(SlicerSettings())
        val onResolved = resolve(SlicerSettings().copy(waveOverhangEnabled = true))
        assertEquals("false", offResolved.extruderValues["enderslicer_wave_overhang_enabled"])
        assertEquals("true", onResolved.extruderValues["enderslicer_wave_overhang_enabled"])
        assertEquals(
            offResolved.extruderValues.withoutAppKeys(),
            onResolved.extruderValues.withoutAppKeys(),
        )

        assertTrue(buildStandaloneCommand(SlicerSettings()).contains("enderslicer_wave_overhang_enabled=false"))
        assertTrue(
            buildStandaloneCommand(SlicerSettings().copy(waveOverhangEnabled = true))
                .contains("enderslicer_wave_overhang_enabled=true"),
        )

        // Bridge detection must be enabled only when an overhang feature is on:
        // the pinned definitions default it off, and without it the arc/wave
        // generators never see a bridge to replace. (The resolved transport
        // sets it at model-mesh scope in CuraResolvedSettingsWriter so the
        // resolved extruder/global maps stay value-neutral.)
        assertFalse(buildStandaloneCommand(SlicerSettings()).contains("bridge_settings_enabled=true"))
        assertTrue(
            buildStandaloneCommand(SlicerSettings().copy(waveOverhangEnabled = true))
                .contains("bridge_settings_enabled=true"),
        )
    }

    @Test
    fun brickWallKeysReachEngineOnlyWhenEnabled() {
        val offResolved = resolve(SlicerSettings())
        val onResolved = resolve(SlicerSettings().copy(brickWallEnabled = true))
        assertEquals("false", offResolved.extruderValues["enderslicer_brick_wall_enabled"])
        assertEquals("true", onResolved.extruderValues["enderslicer_brick_wall_enabled"])
        assertEquals(
            offResolved.extruderValues.withoutAppKeys(),
            onResolved.extruderValues.withoutAppKeys(),
        )

        assertTrue(buildStandaloneCommand(SlicerSettings()).contains("enderslicer_brick_wall_enabled=false"))
        assertTrue(
            buildStandaloneCommand(SlicerSettings().copy(brickWallEnabled = true))
                .contains("enderslicer_brick_wall_enabled=true"),
        )

        // Bridge detection must be enabled only when an overhang feature is on:
        // the brick-wall generator anchors its staircase on the supported
        // region that bridgeAngle computes from the layer below.
        assertFalse(buildStandaloneCommand(SlicerSettings()).contains("bridge_settings_enabled=true"))
        assertTrue(
            buildStandaloneCommand(SlicerSettings().copy(brickWallEnabled = true))
                .contains("bridge_settings_enabled=true"),
        )
    }

    @Test
    fun masonryWallsKeyReachesEngineOnlyWhenEnabled() {
        val offResolved = resolve(SlicerSettings())
        val onResolved = resolve(SlicerSettings().copy(masonryWallsEnabled = true))
        assertEquals("false", offResolved.extruderValues["enderslicer_masonry_walls_enabled"])
        assertEquals("true", onResolved.extruderValues["enderslicer_masonry_walls_enabled"])
        assertEquals(
            offResolved.extruderValues.withoutAppKeys(),
            onResolved.extruderValues.withoutAppKeys(),
        )

        assertTrue(buildStandaloneCommand(SlicerSettings()).contains("enderslicer_masonry_walls_enabled=false"))
        assertTrue(
            buildStandaloneCommand(SlicerSettings().copy(masonryWallsEnabled = true))
                .contains("enderslicer_masonry_walls_enabled=true"),
        )

        // Masonry walls do not depend on bridge detection, so the bridge gate
        // must stay off unless an overhang feature turns it on.
        assertFalse(buildStandaloneCommand(SlicerSettings().copy(masonryWallsEnabled = true)).contains("bridge_settings_enabled=true"))
    }

    @Test
    fun paintedSupportsWithNonPlanarOnNeverLeakPaintMeshesWhenUnpainted() {
        // Paint OFF + non-planar ON: the slice must load exactly one mesh (the
        // model) and carry no support_mesh/anti_overhang_mesh roles.
        NonPlanarRuntime.activate(NonPlanarSettings(enabled = true))
        val command = buildStandaloneCommand(SlicerSettings(), paint = false)

        assertEquals("Only the model mesh may be loaded", 1, command.count { it == "-l" })
        assertFalse(command.contains("support_mesh=true"))
        assertFalse(command.contains("anti_overhang_mesh=true"))
    }

    @Test
    fun paintedSupportsWithNonPlanarOffEmitThePaintedEnforcerMeshAndNoWarpSidecars() {
        // Paint ON + non-planar OFF: exactly one painted enforcer mesh is
        // loaded with its support role, and the non-planar pipelines contribute
        // nothing (no sentinels, no extra mesh loads).
        val command = buildStandaloneCommand(SlicerSettings(), paint = true)

        assertEquals("Model plus one painted enforcer mesh", 2, command.count { it == "-l" })
        assertTrue(command.contains("<WORKSPACE>" + File.separator + "support-enforcer.stl"))
        assertTrue(command.contains("support_mesh=true"))
        assertFalse(command.any { it.contains("ENDERSLICER_NON_PLANAR") || it.contains("ENDERSLICER_CONICAL") })
    }

    private fun resetAllFeatures() {
        SmartInfillRuntime.activate(null)
        NonPlanarRuntime.activate(NonPlanarSettings())
        ConicalRuntime.activate(ConicalSettings())
    }

    private fun Map<String, String>.withoutAppKeys(): Map<String, String> =
        filterKeys { !it.startsWith("enderslicer_") }

    private fun List<String>.withoutAppArguments(): List<String> =
        filter { !it.startsWith("enderslicer_") }

    private fun resolve(settings: SlicerSettings): CuraSliceSettingsResolver.Result =
        CuraSliceSettingsResolver.resolve(
            profile = profile(),
            printer = printer,
            settings = settings,
            startGcode = START_GCODE,
            endGcode = END_GCODE,
        )

    private fun buildStandaloneCommand(settings: SlicerSettings, paint: Boolean = false): List<String> {
        val directory = Files.createTempDirectory("enderslicer-leak-command").toFile()
        try {
            val modelFile = File(directory, "model.stl")
            writeFlatTriangle(modelFile, 100f, 1f)
            val paintModifiers = if (paint) {
                val enforcer = File(directory, "support-enforcer.stl")
                writeTriangle(enforcer, 101f, 101f, 1f)
                listOf(SupportPaintModifier(isBlocker = false, file = enforcer))
            } else {
                emptyList()
            }
            return CuraEngineCommand.build(
                executablePath = "/native/libcuraengine_exec.so",
                definitionsDirectory = "/files/definitions",
                machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
                extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
                modelPath = modelFile.absolutePath,
                outputPath = File(directory, "current.gcode").absolutePath,
                printer = printer,
                settings = settings,
                startGcode = START_GCODE,
                endGcode = END_GCODE,
                supportPaintModifiers = paintModifiers,
                threadCount = 4,
            ).map { it.replace(directory.absolutePath, "<WORKSPACE>") }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun packageValue(id: String): SmartInfillPackage = SmartInfillPackage(
        id = id,
        directory = File("."),
        sourceName = "$id.stl",
        sourceSha256 = "0".repeat(64),
        baseDensityPercent = 10.0,
        pattern = "cubic",
        mode = "graded",
        perimeters = 2,
        lineWidthMm = 0.4,
        topBottomLayers = 4,
        layerHeightMm = 0.2,
        upstreamCommit = SmartInfillActivity.FILASIM_COMMIT,
        modifiers = emptyList(),
    )

    private fun writeTriangle(file: File, x: Float, y: Float, z: Float) {
        val buffer = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(80)
        buffer.putInt(1)
        buffer.putFloat(0f).putFloat(0f).putFloat(1f)
        buffer.putFloat(x).putFloat(y).putFloat(z)
        buffer.putFloat(x + 1f).putFloat(y).putFloat(z)
        buffer.putFloat(x).putFloat(y + 1f).putFloat(z + 1f)
        buffer.putShort(0)
        file.writeBytes(buffer.array())
    }

    /** A large flat, up-facing triangle that qualifies as a conformal region. */
    private fun writeFlatTriangle(file: File, size: Float, z: Float) {
        val buffer = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(80)
        buffer.putInt(1)
        buffer.putFloat(0f).putFloat(0f).putFloat(1f)
        buffer.putFloat(size).putFloat(0f).putFloat(z)
        buffer.putFloat(0f).putFloat(size).putFloat(z)
        buffer.putFloat(0f).putFloat(0f).putFloat(z)
        buffer.putShort(0)
        file.writeBytes(buffer.array())
    }

    private fun loadDefinitions(): Map<String, String> {
        val directory = sequenceOf(
            File("app/src/main/assets/cura/definitions"),
            File("src/main/assets/cura/definitions"),
        ).firstOrNull(File::isDirectory)
            ?: error("Pinned Cura definition directory was not found")
        return listOf(
            "fdmprinter.def.json",
            "fdmextruder.def.json",
            "creality_base.def.json",
            "creality_base_extruder_0.def.json",
            "creality_ender3.def.json",
        ).associateWith { name -> File(directory, name).readText() }
    }

    private fun profile(): CuraEngineProfile = CuraEngineProfile(
        globalValues = mapOf(
            "layer_height" to "0.2",
            "adhesion_type" to "none",
        ),
        extruderValues = mapOf(
            "layer_height" to "0.1",
            "adhesion_type" to "brim",
            "speed_print" to "120",
        ),
        rawGlobalValues = linkedMapOf(
            "layer_height" to "0.2",
            "layer_height_0" to "0.28",
            "top_bottom_thickness" to "=layer_height_0+layer_height*3",
            "wall_thickness" to "=line_width*2",
            "material_bed_temperature" to "60",
            "adhesion_type" to "none",
        ),
        rawExtruderValues = linkedMapOf(
            "machine_nozzle_size" to "0.4",
            "material_diameter" to "1.75",
            "line_width" to "=machine_nozzle_size",
            "layer_height" to "0.1",
            "adhesion_type" to "brim",
            "speed_print" to "120",
            "speed_infill" to "=speed_print",
            "infill_sparse_density" to "10",
            "infill_pattern" to "cubic",
            "infill_line_width" to "=line_width",
            "infill_line_distance" to "=0 if infill_sparse_density == 0 else (infill_line_width * 100) / infill_sparse_density * (3 if infill_pattern == 'cubic' else 1)",
            "material_print_temperature" to "200",
            "material_print_temperature_layer_0" to "220",
            "cool_min_temperature" to "=material_print_temperature",
            "cool_fan_speed" to "100",
            "top_bottom_thickness" to "=layer_height_0+layer_height*3",
            "wall_line_count" to "=1 if magic_spiralize else max(1, round((wall_thickness - wall_line_width_0) / wall_line_width_x) + 1) if wall_thickness != 0 else 0",
            "wall_thickness" to "=line_width*2",
            "wall_line_width_0" to "=line_width",
            "wall_line_width_x" to "=line_width",
        ),
        definitionFiles = loadDefinitions(),
        machineDefinitionFileName = "creality_ender3.def.json",
        extruderDefinitionFileName = "creality_base_extruder_0.def.json",
    )

    private companion object {
        const val START_GCODE = "G28"
        const val END_GCODE = "M104 S0"
    }

    private val printer = PrinterDefinition(
        name = "Modified Ender 3 V2",
        widthMm = 230.0,
        depthMm = 230.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        heatedBed = true,
        heatedBuildVolume = false,
        gcodeFlavor = "Marlin",
        extruders = 1,
        nozzleSizeMm = 0.4,
        filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0,
        printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0,
        printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
    )
}