package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.conical.ConicalRuntime
import com.tomppi.enderslicer.conical.ConicalSettings
import com.tomppi.enderslicer.model.ExtraSettingSpec
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.nonplanar.NonPlanarRuntime
import com.tomppi.enderslicer.nonplanar.NonPlanarSettings
import com.tomppi.enderslicer.profile.CuraEngineProfile
import com.tomppi.enderslicer.smartinfill.SmartInfillCuraContract
import com.tomppi.enderslicer.smartinfill.SmartInfillModifier
import com.tomppi.enderslicer.supportpaint.SupportPaintModifier
import com.tomppi.enderslicer.viewer.StlParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CuraEngineCommandTest {
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

    @Test
    fun resolvedCommandUsesOfficialResolvedSettingsInput() {
        val command = CuraEngineCommand.buildResolved(
            executablePath = "/native/libcuraengine_exec.so",
            definitionsDirectory = "/files/definitions",
            resolvedSettingsPath = "/files/resolved-settings.json",
            outputPath = "/files/current.gcode",
            threadCount = 4,
        )

        assertEquals(
            listOf(
                "/native/libcuraengine_exec.so",
                "slice",
                "-m4",
                // '-p' is how the engine reports slice progress to the app.
                "-p",
                "-d",
                "/files/definitions",
                "-r",
                "/files/resolved-settings.json",
                "-o",
                "/files/current.gcode",
            ),
            command,
        )
        assertFalse(command.contains("-l"))
        assertFalse(command.contains("-j"))
        assertFalse(command.contains("-s"))
    }

    @Test
    fun resolvedTransportAppliesUserExtrasAfterTheResolvedSettingsFile() {
        val command = CuraEngineCommand.buildResolved(
            executablePath = "/native/libcuraengine_exec.so",
            definitionsDirectory = "/files/definitions",
            resolvedSettingsPath = "/files/resolved-settings.json",
            outputPath = "/files/current.gcode",
            extraSettings = mapOf("speed_print" to "45", "infill_sparse_density" to "30"),
            threadCount = 4,
        )

        assertEquals(
            listOf(
                "/native/libcuraengine_exec.so",
                "slice",
                "-m4",
                // '-p' is how the engine reports slice progress to the app.
                "-p",
                "-d",
                "/files/definitions",
                "-r",
                "/files/resolved-settings.json",
                "-o",
                "/files/current.gcode",
                // CuraEngine applies -s in argv order after -r, so the user's
                // extras are the last-wins override on this transport too.
                "-s",
                "infill_sparse_density=30",
                "-s",
                "speed_print=45",
            ),
            command,
        )
        assertTrue(command.indexOf("-r") < command.indexOf("-s"))
    }

    @Test
    fun rejectsAnUnusableExtraSettingInsteadOfSendingItToTheEngine() {
        val failure = runCatching {
            CuraEngineCommand.buildResolved(
                executablePath = "/native/libcuraengine_exec.so",
                definitionsDirectory = "/files/definitions",
                resolvedSettingsPath = "/files/resolved-settings.json",
                outputPath = "/files/current.gcode",
                extraSettings = mapOf("speed_print" to " "),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("speed_print"))
    }

    @Test
    fun appliesCatalogueValueTypesWhenTheCallerSuppliesThem() {
        val catalog = listOf(ExtraSettingSpec(key = "speed_print", label = "Print Speed", numeric = true))

        val failure = runCatching {
            CuraEngineCommand.buildResolved(
                executablePath = "/native/libcuraengine_exec.so",
                definitionsDirectory = "/files/definitions",
                resolvedSettingsPath = "/files/resolved-settings.json",
                outputPath = "/files/current.gcode",
                extraSettings = mapOf("speed_print" to "fast"),
                catalog = catalog,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("must be a number"))

        val accepted = CuraEngineCommand.buildResolved(
            executablePath = "/native/libcuraengine_exec.so",
            definitionsDirectory = "/files/definitions",
            resolvedSettingsPath = "/files/resolved-settings.json",
            outputPath = "/files/current.gcode",
            extraSettings = mapOf("speed_print" to "45"),
            catalog = catalog,
        )
        assertEquals("speed_print=45", accepted[accepted.size - 1])
    }

    /**
     * The engine writes the caller's own end script when custom end G-code is
     * enabled, and the runner hands the post-processor the same string - it has to
     * search for the script the engine actually wrote, or the M220/M221 restores
     * land at the ";End of Gcode" comment and the script runs under an active
     * speed and flow factor.
     */
    @Test
    fun theEngineAndThePostProcessorAreToldTheSameMachineEndScript() {
        val settings = SlicerSettings(
            customEndGcodeEnabled = true,
            customEndGcode = "M104 S0 ; my own end script",
        )
        val command = CuraEngineCommand.build(
            executablePath = "/native/libcuraengine_exec.so",
            definitionsDirectory = "/files/definitions",
            machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
            extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
            modelPath = "/files/current.stl",
            outputPath = "/files/current.gcode",
            printer = printer,
            settings = settings,
            startGcode = "G28",
            endGcode = "M104 S0",
        )
        val resolved = CuraEngineCommand.machineEndGcodeFor(settings, "M104 S0")

        assertEquals("machine_end_gcode=" + resolved, command.single { it.startsWith("machine_end_gcode=") })
        assertTrue(
            "the caller's own end script has to be the one that is resolved",
            resolved.contains("my own end script"),
        )
    }

    @Test
    fun workerCountUsesPhysicalTopologyWhenRuntimeCpusetIsSmaller() {
        assertEquals(1, CuraEngineCommand.recommendedThreadCount(1, 1))
        assertEquals(4, CuraEngineCommand.recommendedThreadCount(4, 4))
        assertEquals(8, CuraEngineCommand.recommendedThreadCount(3, 8))
        assertEquals(8, CuraEngineCommand.recommendedThreadCount(16, 16))
    }

    @Test
    fun importedConfigurationCannotBypassDependencyResolution() {
        val error = runCatching {
            CuraEngineCommand.build(
                executablePath = "/native/libcuraengine_exec.so",
                definitionsDirectory = "/files/definitions",
                machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
                extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
                modelPath = "/files/current.stl",
                outputPath = "/files/current.gcode",
                printer = printer,
                settings = SlicerSettings(),
                startGcode = "G28",
                endGcode = "M104 S0",
                profile = CuraEngineProfile(extruderValues = mapOf("speed_print" to "120")),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("dependency-resolved"))
    }

    @Test
    fun fallbackCommandAppliesRolesAndNeutralShellsAfterEachTargetMeshIsLoaded() {
        val directory = Files.createTempDirectory("cura-smart-infill-command").toFile()
        try {
            val model = File(directory, "model.stl")
            val low = File(directory, "modifier-35pct.stl")
            val high = File(directory, "modifier-70pct.stl")
            writeTriangle(model, 100f, 100f, 0.2f)
            writeTriangle(low, 101f, 101f, 0.4f)
            writeTriangle(high, 102f, 102f, 0.6f)

            val command = CuraEngineCommand.build(
                executablePath = "/native/libcuraengine_exec.so",
                definitionsDirectory = "/files/definitions",
                machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
                extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
                modelPath = model.absolutePath,
                outputPath = File(directory, "current.gcode").absolutePath,
                printer = printer,
                settings = SlicerSettings(),
                startGcode = "G28",
                endGcode = "M104 S0",
                smartInfillModifiers = listOf(
                    SmartInfillModifier(70, high),
                    SmartInfillModifier(35, low),
                ),
                threadCount = 4,
            )

            val modelIndex = command.indexOf(model.absolutePath)
            val lowIndex = command.indexOf(low.absolutePath)
            val highIndex = command.indexOf(high.absolutePath)
            val outputIndex = command.indexOf("-o")
            assertTrue(modelIndex > 0)
            assertTrue(lowIndex > modelIndex)
            assertTrue(highIndex > lowIndex)
            assertTrue(outputIndex > highIndex)

            val modelSettings = command.subList(modelIndex + 1, lowIndex)
            assertTrue(modelSettings.contains("infill_mesh=false"))
            assertFalse(modelSettings.contains("infill_mesh=true"))
            assertFalse(modelSettings.contains("infill_sparse_density=35"))
            assertFalse(modelSettings.contains("wall_line_count=0"))

            val lowSettings = command.subList(lowIndex + 1, highIndex)
            assertTrue(lowSettings.contains("infill_mesh=true"))
            assertTrue(lowSettings.contains("infill_mesh_order=1"))
            assertTrue(lowSettings.contains("infill_sparse_density=35"))
            assertModifierShellNeutral(lowSettings)

            val highSettings = command.subList(highIndex + 1, outputIndex)
            assertTrue(highSettings.contains("infill_mesh=true"))
            assertTrue(highSettings.contains("infill_mesh_order=2"))
            assertTrue(highSettings.contains("infill_sparse_density=70"))
            assertModifierShellNeutral(highSettings)

            // Rotation/centering are load-time inputs and must precede each -l.
            assertEquals("-l", command[modelIndex - 1])
            assertEquals("-l", command[lowIndex - 1])
            assertEquals("-l", command[highIndex - 1])
            assertTrue(command.subList(0, modelIndex).contains("mesh_rotation_matrix=[[1,0,0],[0,1,0],[0,0,1]]"))
            assertTrue(command.subList(modelIndex, lowIndex).contains("mesh_rotation_matrix=[[1,0,0],[0,1,0],[0,0,1]]"))
            assertTrue(command.subList(lowIndex, highIndex).contains("mesh_rotation_matrix=[[1,0,0],[0,1,0],[0,0,1]]"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun nonPlanarKeepsPaintedSupportModifiersUnwarped() {
        val directory = Files.createTempDirectory("nonplanar-paint-command").toFile()
        try {
            NonPlanarRuntime.activate(NonPlanarSettings(enabled = true))
            val model = File(directory, "model.stl")
            val enforcer = File(directory, "support-enforcer.stl")
            writePyramid(model)
            writeFloatingPatch(enforcer, 4f, 4f, 0.8f)
            val sourceMaxZ = StlParser.parse(enforcer, enforcer.name).bounds.maxZ

            val command = CuraEngineCommand.build(
                executablePath = "/native/libcuraengine_exec.so",
                definitionsDirectory = "/files/definitions",
                machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
                extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
                modelPath = model.absolutePath,
                outputPath = File(directory, "current.gcode").absolutePath,
                printer = printer,
                settings = SlicerSettings(),
                startGcode = "G28",
                endGcode = "M104 S0",
                supportPaintModifiers = listOf(SupportPaintModifier(isBlocker = false, file = enforcer)),
                threadCount = 4,
            )

            val enforcerIndex = command.indexOf(enforcer.absolutePath)
            assertTrue(enforcerIndex > 0)
            val enforcerSettings = command.subList(enforcerIndex + 1, command.indexOf("-o"))
            assertTrue(enforcerSettings.contains("support_mesh=true"))
            assertTrue(enforcerSettings.contains("anti_overhang_mesh=false"))

            val untouched = StlParser.parse(enforcer, enforcer.name)
            assertTrue(
                "Non-planar printing must not warp the painted prism",
                untouched.bounds.maxZ == sourceMaxZ,
            )
        } finally {
            NonPlanarRuntime.activate(NonPlanarSettings())
            directory.deleteRecursively()
        }
    }

    @Test
    fun conicalSlicingWarpsPaintedSupportModifiersInsteadOfRejectingThem() {
        val directory = Files.createTempDirectory("conical-paint-command").toFile()
        try {
            ConicalRuntime.activate(ConicalSettings(enabled = true))
            val model = File(directory, "model.stl")
            val enforcer = File(directory, "support-enforcer.stl")
            writeTriangle(model, 100f, 100f, 0.2f)
            writeFloatingPatch(enforcer, 100f, 100f, 1.0f)
            val source = StlParser.parse(enforcer, enforcer.name)

            val command = CuraEngineCommand.build(
                executablePath = "/native/libcuraengine_exec.so",
                definitionsDirectory = "/files/definitions",
                machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
                extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
                modelPath = model.absolutePath,
                outputPath = File(directory, "current.gcode").absolutePath,
                printer = printer,
                settings = SlicerSettings(),
                startGcode = "G28",
                endGcode = "M104 S0",
                supportPaintModifiers = listOf(SupportPaintModifier(isBlocker = false, file = enforcer)),
                threadCount = 4,
            )

            val enforcerIndex = command.indexOf(enforcer.absolutePath)
            assertTrue(enforcerIndex > 0)
            val enforcerSettings = command.subList(enforcerIndex + 1, command.indexOf("-o"))
            assertTrue(enforcerSettings.contains("support_mesh=true"))
            assertTrue(
                "Painted prisms must skip the expensive union-all mesh fix",
                enforcerSettings.contains("meshfix_union_all=false"),
            )

            val warped = StlParser.parse(enforcer, enforcer.name)
            assertTrue(
                "Conical slicing must lift the painted prism with the cone warp",
                warped.bounds.maxZ > source.bounds.maxZ,
            )
            assertTrue(
                "The outward cone warp must stretch the painted prism away from the model centre",
                warped.bounds.width > source.bounds.width,
            )
        } finally {
            ConicalRuntime.activate(ConicalSettings())
            directory.deleteRecursively()
        }
    }

    @Test
    fun adaptiveWallModifiersRemainRejectedWithNonPlanarSlicing() {
        val directory = Files.createTempDirectory("adaptive-wall-rejected").toFile()
        try {
            val model = File(directory, "model.stl")
            writeTriangle(model, 100f, 100f, 0.2f)
            val wallModifier = File(directory, "adaptive-wall.stl")
            writeTriangle(wallModifier, 101f, 101f, 0.4f)
            val adaptive = AdaptiveWallModifier(wallLineCount = 4, wallFlowPercent = 100.0, file = wallModifier)

            NonPlanarRuntime.activate(NonPlanarSettings(enabled = true))
            val nonPlanarError = runCatching {
                CuraEngineCommand.build(
                    executablePath = "/native/libcuraengine_exec.so",
                    definitionsDirectory = "/files/definitions",
                    machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
                    extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
                    modelPath = model.absolutePath,
                    outputPath = File(directory, "current.gcode").absolutePath,
                    printer = printer,
                    settings = SlicerSettings(),
                    startGcode = "G28",
                    endGcode = "M104 S0",
                    adaptiveWallModifiers = listOf(adaptive),
                    threadCount = 4,
                )
            }.exceptionOrNull()
            assertTrue(nonPlanarError is IllegalArgumentException)
            assertTrue(nonPlanarError?.message.orEmpty().contains("Adaptive walls"))

            NonPlanarRuntime.activate(NonPlanarSettings())
            ConicalRuntime.activate(ConicalSettings(enabled = true))
            val conicalError = runCatching {
                CuraEngineCommand.build(
                    executablePath = "/native/libcuraengine_exec.so",
                    definitionsDirectory = "/files/definitions",
                    machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
                    extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
                    modelPath = model.absolutePath,
                    outputPath = File(directory, "current.gcode").absolutePath,
                    printer = printer,
                    settings = SlicerSettings(),
                    startGcode = "G28",
                    endGcode = "M104 S0",
                    adaptiveWallModifiers = listOf(adaptive),
                    threadCount = 4,
                )
            }.exceptionOrNull()
            assertTrue(conicalError is IllegalArgumentException)
            assertTrue(conicalError?.message.orEmpty().contains("Adaptive walls"))
        } finally {
            NonPlanarRuntime.activate(NonPlanarSettings())
            ConicalRuntime.activate(ConicalSettings())
            directory.deleteRecursively()
        }
    }

    @Test
    fun infillLineDistanceFollowsTheDefinitionsPatternFactor() {
        val directory = Files.createTempDirectory("cura-infill-pattern-factor").toFile()
        try {
            val model = File(directory, "model.stl")
            writeTriangle(model, 100f, 100f, 0.2f)

            fun commandFor(settings: SlicerSettings): List<String> = CuraEngineCommand.build(
                executablePath = "/native/libcuraengine_exec.so",
                definitionsDirectory = "/files/definitions",
                machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
                extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
                modelPath = model.absolutePath,
                outputPath = File(directory, "current.gcode").absolutePath,
                printer = printer,
                settings = settings,
                startGcode = "G28",
                endGcode = "M104 S0",
                threadCount = 4,
            )

            // fdmprinter.def.json's infill_line_distance at the default 10%
            // density and 0.40 mm line width. The engine spaces its lines from
            // infill_line_distance alone, so a factor that does not match the
            // definition silently prints a different density.
            val expectedLineDistance = mapOf(
                "lines" to 4.0,
                "grid" to 8.0,
                "cubic" to 12.0,
                "lightning" to 6.4,
                // Honeycomb and octagon overlap their own lines: the only
                // density-dependent branch, (4/3 - density/300) = 1.3 at 10%.
                "honeycomb" to 5.2,
                "octagon" to 5.2,
            )
            for ((pattern, lineDistance) in expectedLineDistance) {
                val command = commandFor(SlicerSettings(infillPattern = pattern))
                assertEquals(pattern, modelSetting(command, model, "infill_pattern"))
                assertEquals(
                    "infill_line_distance for $pattern",
                    lineDistance,
                    modelSetting(command, model, "infill_line_distance").toDouble(),
                    1e-9,
                )
            }

            // Same pattern, higher density: the factor falls to 1.16667, so the
            // spacing is 0.9333 mm rather than the 0.8 mm a constant factor
            // would give.
            val halfDense = commandFor(SlicerSettings(infillPattern = "honeycomb", infillDensityPercent = 50.0))
            assertEquals(
                0.9333333333333333,
                modelSetting(halfDense, model, "infill_line_distance").toDouble(),
                1e-9,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Last value the command sends for [key] after [model] is loaded. */
    private fun modelSetting(command: List<String>, model: File, key: String): String {
        val start = command.indexOf(model.absolutePath)
        assertTrue("Model was not loaded: ${model.name}", start >= 0)
        // The single output argument is appended after the last mesh, so it
        // closes the last mesh's setting block.
        val end = command.lastIndexOf("-o")
        assertTrue("Command has no output argument after the model", end > start)
        return command.subList(start + 1, end)
            .lastOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?: error("Missing command setting: $key")
    }

    private fun assertModifierShellNeutral(settings: List<String>) {
        SmartInfillCuraContract.modifierShellNeutralValues.forEach { (key, value) ->
            assertTrue("Missing modifier shell override $key=$value", settings.contains("$key=$value"))
        }
    }

    private fun writeTriangle(file: File, x: Float, y: Float, z: Float) {
        val bytes = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(80)
        bytes.putInt(1)
        bytes.putFloat(0f)
        bytes.putFloat(0f)
        bytes.putFloat(1f)
        bytes.putFloat(x)
        bytes.putFloat(y)
        bytes.putFloat(z)
        bytes.putFloat(x + 1f)
        bytes.putFloat(y)
        bytes.putFloat(z)
        bytes.putFloat(x)
        bytes.putFloat(y + 1f)
        bytes.putFloat(z)
        bytes.putShort(0)
        file.writeBytes(bytes.array())
    }

    /**
     * A 10 x 10 mm square pyramid whose apex sits at z = 1.2 over the centre:
     * tall enough for a conformal surface region and gentle enough
     * (about 13 degrees) to stay inside the default slope limit.
     */
    private fun writePyramid(file: File) {
        val apexX = 5f
        val apexY = 5f
        val apexZ = 1.2f
        val triangles = listOf(
            // Base, facing up.
            floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 10f, 10f, 0f),
            floatArrayOf(0f, 0f, 0f, 10f, 10f, 0f, 0f, 10f, 0f),
            // Sides, winding chosen so each normal has a positive Z component.
            floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, apexX, apexY, apexZ),
            floatArrayOf(10f, 0f, 0f, 10f, 10f, 0f, apexX, apexY, apexZ),
            floatArrayOf(10f, 10f, 0f, 0f, 10f, 0f, apexX, apexY, apexZ),
            floatArrayOf(0f, 10f, 0f, 0f, 0f, 0f, apexX, apexY, apexZ),
        )
        val bytes = ByteBuffer.allocate(84 + triangles.size * 50).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(80)
        bytes.putInt(triangles.size)
        for (triangle in triangles) {
            bytes.putFloat(0f).putFloat(0f).putFloat(1f)
            repeat(3) { vertex ->
                bytes.putFloat(triangle[vertex * 3])
                bytes.putFloat(triangle[vertex * 3 + 1])
                bytes.putFloat(triangle[vertex * 3 + 2])
            }
            bytes.putShort(0)
        }
        file.writeBytes(bytes.array())
    }

    /** Two triangles forming a small square patch at z within (x..x+2, y..y+2). */
    private fun writeFloatingPatch(file: File, x: Float, y: Float, z: Float) {
        val triangles = listOf(
            floatArrayOf(x, y, z, x + 2f, y, z, x, y + 2f, z),
            floatArrayOf(x + 2f, y, z, x + 2f, y + 2f, z, x, y + 2f, z),
        )
        val bytes = ByteBuffer.allocate(84 + triangles.size * 50).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(80)
        bytes.putInt(triangles.size)
        for (triangle in triangles) {
            bytes.putFloat(0f).putFloat(0f).putFloat(1f)
            repeat(3) { vertex ->
                bytes.putFloat(triangle[vertex * 3])
                bytes.putFloat(triangle[vertex * 3 + 1])
                bytes.putFloat(triangle[vertex * 3 + 2])
            }
            bytes.putShort(0)
        }
        file.writeBytes(bytes.array())
    }
}
