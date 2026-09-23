package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.conical.ConicalRuntime
import com.tomppi.enderslicer.engine.AdaptiveWallModifier
import com.tomppi.enderslicer.engine.NonPlanarPreparation
import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.nonplanar.NonPlanarRuntime
import com.tomppi.enderslicer.smartinfill.SmartInfillCuraContract
import com.tomppi.enderslicer.smartinfill.SmartInfillModifier
import com.tomppi.enderslicer.smartinfill.requireValidBinaryStl
import com.tomppi.enderslicer.supportpaint.SupportPaintModifier
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlSliceTransform
import org.json.JSONObject
import java.io.File

internal object CuraResolvedSettingsWriter {
    fun write(
        destination: File,
        modelFileName: String,
        resolved: CuraSliceSettingsResolver.Result,
        modelTransform: StlSliceTransform? = null,
        smartInfillModifiers: List<SmartInfillModifier> = emptyList(),
        adaptiveWallModifiers: List<AdaptiveWallModifier> = emptyList(),
        supportPaintModifiers: List<SupportPaintModifier> = emptyList(),
    ) {
        require(modelFileName.endsWith(".stl", ignoreCase = true)) {
            "Resolved Cura model must be an STL file"
        }
        val modelDirectory = destination.parentFile
            ?: error("Resolved settings destination has no parent directory")
        val modelFile = File(modelDirectory, modelFileName)
        require(modelFile.isFile && modelFile.length() > 0L) {
            "Resolved Cura STL is missing or empty: ${modelFile.absolutePath}"
        }
        val nonPlanarSnapshot = NonPlanarRuntime.snapshot()
        val conicalSnapshot = ConicalRuntime.snapshot()
        val stagedForNonPlanar = modelTransform === NON_PLANAR_STAGED_IDENTITY
        val stagedForConical = modelTransform === CONICAL_STAGED_IDENTITY
        require(!stagedForNonPlanar || nonPlanarSnapshot != null) {
            "The resolved model was staged for non-planar printing but non-planar printing is no longer active"
        }
        require(!stagedForConical || conicalSnapshot != null) {
            "The resolved model was staged for conical slicing but conical slicing is no longer active"
        }
        require(nonPlanarSnapshot == null || stagedForNonPlanar) {
            "Non-planar resolved requests must explicitly stage displayed geometry"
        }
        require(conicalSnapshot == null || stagedForConical) {
            "Conical resolved requests must explicitly stage displayed geometry"
        }
        val effectiveModelTransform = if (stagedForNonPlanar || stagedForConical) null else modelTransform

        val effectiveSmartInfillModifiers = smartInfillModifiers
            .sortedBy(SmartInfillModifier::densityPercent)
        val meshNames = buildList {
            add(modelFileName)
            addAll(effectiveSmartInfillModifiers.map { it.file.name })
            addAll(adaptiveWallModifiers.map { it.file.name })
            addAll(supportPaintModifiers.map { it.file.name })
        }
        val duplicatedNames = meshNames.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicatedNames.isEmpty()) {
            "Resolved Cura request contains duplicate mesh names: " + duplicatedNames.joinToString(", ")
        }
        if (effectiveSmartInfillModifiers.isNotEmpty()) {
            val requestedDensities = effectiveSmartInfillModifiers
                .map(SmartInfillModifier::densityPercent)
                .toSet()
            require(resolved.smartInfillModelValues.keys.containsAll(requestedDensities)) {
                "Resolved Cura request is missing density-dependent Smart Infill settings"
            }
        }

        val machineWidth = requiredNumber(resolved.globalValues, "machine_width")
        val machineDepth = requiredNumber(resolved.globalValues, "machine_depth")
        val machineHeight = requiredNumber(resolved.globalValues, "machine_height")
        val centerIsZero = requiredBoolean(resolved.globalValues, "machine_center_is_zero")
        val printerEnvelope = PrinterEnvelope(
            widthMm = machineWidth,
            depthMm = machineDepth,
            heightMm = machineHeight,
            buildPlateShape = resolved.globalValues["machine_shape"]
                ?: error("Resolved Cura setting is missing: machine_shape"),
            originAtCenter = centerIsZero,
            gcodeFlavor = resolved.globalValues["machine_gcode_flavor"]
                ?.trim()
                ?.trim('"')
                ?.takeIf(String::isNotBlank)
                ?: PrinterEnvelope.DEFAULT_GCODE_FLAVOR,
        )
        printerEnvelope.requireBinaryStlFits(modelFile, effectiveModelTransform)
        effectiveSmartInfillModifiers.forEach { modifier ->
            require(modifier.file.parentFile?.canonicalFile == modelDirectory.canonicalFile) {
                "Smart Infill modifier was not staged inside the CuraEngine request"
            }
            requireValidBinaryStl(modifier.file, Int.MAX_VALUE)
            printerEnvelope.requireBinaryStlFits(modifier.file)
        }

        NonPlanarPreparation.prepare(
            modelFile = modelFile,
            workspace = modelDirectory,
            printerEnvelope = printerEnvelope,
            layerHeightMm = nonPlanarSnapshot?.let { requiredResolvedNumber(resolved, "layer_height") } ?: 0.0,
            nozzleDiameterMm = nonPlanarSnapshot?.let { requiredResolvedNumber(resolved, "machine_nozzle_size") } ?: 0.0,
            smartInfillModifiers = effectiveSmartInfillModifiers,
            adaptiveWallModifiers = adaptiveWallModifiers,
            supportPaintModifiers = supportPaintModifiers,
        )
        printerEnvelope.writeTo(File(modelDirectory, PrinterEnvelope.METADATA_FILE_NAME))

        val machineCenterX = if (centerIsZero) 0.0 else machineWidth / 2.0
        val machineCenterY = if (centerIsZero) 0.0 else machineDepth / 2.0
        val enginePositionX = -machineCenterX
        val enginePositionY = -machineCenterY
        val enginePositionZ = 0.0

        val linear = effectiveModelTransform?.linear ?: IDENTITY
        val affineTranslationX = effectiveModelTransform?.translationXmm ?: 0.0
        val affineTranslationY = effectiveModelTransform?.translationYmm ?: 0.0
        val affineTranslationZ = effectiveModelTransform?.translationZmm ?: 0.0

        val extruderValues = JSONObject(resolved.extruderValues)
        resolved.modelValues.forEach { (key, value) -> extruderValues.put(key, value) }
        applyTransform(
            values = extruderValues,
            linear = linear,
            translationX = affineTranslationX,
            translationY = affineTranslationY,
            translationZ = affineTranslationZ,
            enginePositionX = enginePositionX,
            enginePositionY = enginePositionY,
            enginePositionZ = enginePositionZ,
        )

        val modelValues = JSONObject(resolved.modelValues)
        modelValues.put("extruder_nr", 0)
        val overhangFillEnabled = resolved.extruderValues["enderslicer_arc_overhang_enabled"] == "true" ||
            resolved.extruderValues["enderslicer_wave_overhang_enabled"] == "true" ||
            resolved.extruderValues["enderslicer_brick_wall_enabled"] == "true"
        if (overhangFillEnabled) {
            // The pinned definitions default bridge detection off, and the
            // arc/wave/brick-wall overhang generators only work on detected
            // bridges or the layer below.
            modelValues.put("bridge_settings_enabled", true)
        }
        applyTransform(
            values = modelValues,
            linear = linear,
            translationX = affineTranslationX,
            translationY = affineTranslationY,
            translationZ = affineTranslationZ,
            enginePositionX = enginePositionX,
            enginePositionY = enginePositionY,
            enginePositionZ = enginePositionZ,
        )

        val globalValues = LinkedHashMap(resolved.globalValues)
        if (nonPlanarSnapshot != null || conicalSnapshot != null) {
            globalValues["machine_end_gcode"] = NonPlanarPreparation.markMachineEndGcode(
                globalValues["machine_end_gcode"].orEmpty(),
            )
        }
        val root = JSONObject()
            .put("global", JSONObject(globalValues))
            .put("extruder.0", extruderValues)
            .put(modelFileName, modelValues)

        effectiveSmartInfillModifiers.forEachIndexed { index, modifier ->
            val densityResolved = resolved.smartInfillModelValues[modifier.densityPercent]
                ?: error("Resolved Cura settings are missing for ${modifier.densityPercent}% Smart Infill")
            val values = JSONObject(densityResolved)
                .put("extruder_nr", 0)
                .put("infill_mesh", true)
                .put("infill_mesh_order", index + 1)
                .put("infill_sparse_density", modifier.densityPercent)
                .put("support_mesh", false)
                .put("anti_overhang_mesh", false)
                .put("cutting_mesh", false)
            SmartInfillCuraContract.modifierShellNeutralValues.forEach { (key, value) ->
                values.put(key, value.toInt())
            }
            applyTransform(
                values = values,
                linear = IDENTITY,
                translationX = 0.0,
                translationY = 0.0,
                translationZ = 0.0,
                enginePositionX = enginePositionX,
                enginePositionY = enginePositionY,
                enginePositionZ = enginePositionZ,
            )
            root.put(modifier.file.name, values)
        }

        adaptiveWallModifiers.forEachIndexed { index, modifier ->
            val values = JSONObject(resolved.modelValues)
                .put("extruder_nr", 0)
                .put("infill_mesh", true)
                .put("infill_mesh_order", effectiveSmartInfillModifiers.size + index + 1)
                .put("wall_line_count", modifier.wallLineCount)
                .put("wall_0_material_flow", modifier.wallFlowPercent)
                .put("wall_x_material_flow", modifier.wallFlowPercent)
                .put("support_mesh", false)
                .put("anti_overhang_mesh", false)
                .put("cutting_mesh", false)
            applyTransform(
                values = values,
                linear = IDENTITY,
                translationX = 0.0,
                translationY = 0.0,
                translationZ = 0.0,
                enginePositionX = enginePositionX,
                enginePositionY = enginePositionY,
                enginePositionZ = enginePositionZ,
            )
            root.put(modifier.file.name, values)
        }

        supportPaintModifiers.forEach { modifier ->
            val values = JSONObject(resolved.modelValues)
                .put("extruder_nr", 0)
                .put("meshfix_union_all", false)
                .put("support_mesh", !modifier.isBlocker)
                .put("anti_overhang_mesh", modifier.isBlocker)
                .put("infill_mesh", false)
                .put("cutting_mesh", false)
            applyTransform(
                values = values,
                linear = IDENTITY,
                translationX = 0.0,
                translationY = 0.0,
                translationZ = 0.0,
                enginePositionX = enginePositionX,
                enginePositionY = enginePositionY,
                enginePositionZ = enginePositionZ,
            )
            root.put(modifier.file.name, values)
        }

        destination.writeText(root.toString())
        check(destination.isFile && destination.length() > 0L) {
            "Unable to write resolved Cura settings"
        }
    }

    internal fun copyResolvedSourceSnapshot(
        stagedDisplayedFile: File,
        destination: File,
        copyFile: (File, File) -> Unit = { source, target -> source.copyTo(target, overwrite = true) },
    ): StlSliceTransform? {
        require(stagedDisplayedFile.isFile && stagedDisplayedFile.length() > 0L) {
            "The transformed STL is unavailable for resolved source staging"
        }
        if (NonPlanarRuntime.snapshot() != null) {
            val displayedStamp = fileStamp(stagedDisplayedFile)
            removePreStagedRequestModel(destination)
            copyFile(stagedDisplayedFile, destination)
            check(destination.isFile && destination.length() == displayedStamp.length) {
                "Unable to stage displayed STL geometry for non-planar printing"
            }
            check(fileStamp(stagedDisplayedFile) == displayedStamp) {
                "The displayed STL changed while it was being staged for non-planar printing"
            }
            return NON_PLANAR_STAGED_IDENTITY
        }
        if (ConicalRuntime.snapshot() != null) {
            val displayedStamp = fileStamp(stagedDisplayedFile)
            removePreStagedRequestModel(destination)
            copyFile(stagedDisplayedFile, destination)
            check(destination.isFile && destination.length() == displayedStamp.length) {
                "Unable to stage displayed STL geometry for conical slicing"
            }
            check(fileStamp(stagedDisplayedFile) == displayedStamp) {
                "The displayed STL changed while it was being staged for conical slicing"
            }
            return CONICAL_STAGED_IDENTITY
        }

        val sourceFile = File(
            stagedDisplayedFile.parentFile,
            "${stagedDisplayedFile.nameWithoutExtension}.slice-source.stl",
        )
        val transformFile = File(
            stagedDisplayedFile.parentFile,
            "${stagedDisplayedFile.nameWithoutExtension}.slice-transform.json",
        )
        if (!sourceFile.isFile || !transformFile.isFile) return null

        val displayedStamp = fileStamp(stagedDisplayedFile)
        val sourceStamp = fileStamp(sourceFile)
        val transformStamp = fileStamp(transformFile)
        val stagedSource = StlMeshWriter.resolvedSliceSource(stagedDisplayedFile) ?: return null
        copyFile(stagedSource.modelFile, destination)
        check(destination.isFile && destination.length() == sourceStamp.length) {
            "Unable to stage original STL geometry for direct Cura transformation"
        }
        check(fileStamp(stagedDisplayedFile) == displayedStamp) {
            "The transformed STL changed while its resolved source was being staged"
        }
        check(fileStamp(sourceFile) == sourceStamp) {
            "The original STL changed while it was being staged"
        }
        check(fileStamp(transformFile) == transformStamp) {
            "The STL transform changed while it was being staged"
        }
        return stagedSource.transform
    }

    private fun removePreStagedRequestModel(destination: File) {
        if (!destination.exists()) return
        require(destination.isFile) {
            "Resolved Cura request model destination is not a file: ${destination.absolutePath}"
        }
        check(destination.delete()) {
            "Unable to replace the pre-staged Cura request model: ${destination.absolutePath}"
        }
    }

    private fun applyTransform(
        values: JSONObject,
        linear: List<Double>,
        translationX: Double,
        translationY: Double,
        translationZ: Double,
        enginePositionX: Double,
        enginePositionY: Double,
        enginePositionZ: Double,
    ) {
        values
            .put("center_object", false)
            .put("mesh_rotation_matrix", matrixString(linear))
            .put(AFFINE_TRANSLATION_X, translationX)
            .put(AFFINE_TRANSLATION_Y, translationY)
            .put(AFFINE_TRANSLATION_Z, translationZ)
            .put("mesh_position_x", enginePositionX)
            .put("mesh_position_y", enginePositionY)
            .put("mesh_position_z", enginePositionZ)
    }

    private fun fileStamp(file: File): FileStamp = FileStamp(file.length(), file.lastModified())

    private fun matrixString(linear: List<Double>): String {
        require(linear.size == 9 && linear.all(Double::isFinite)) {
            "Resolved Cura model transform must contain nine finite values"
        }
        return linear.chunked(3).joinToString(prefix = "[", postfix = "]", separator = ",") { row ->
            row.joinToString(prefix = "[", postfix = "]", separator = ",")
        }
    }

    private fun requiredNumber(values: Map<String, String>, key: String): Double {
        val raw = values[key] ?: error("Resolved Cura setting is missing: $key")
        val value = raw.toDoubleOrNull() ?: error("Resolved Cura setting is not numeric: $key=$raw")
        require(value.isFinite() && value > 0.0) { "Resolved Cura setting is invalid: $key=$raw" }
        return value
    }

    private fun requiredResolvedNumber(resolved: CuraSliceSettingsResolver.Result, key: String): Double {
        val raw = resolved.modelValues[key]
            ?: resolved.extruderValues[key]
            ?: resolved.globalValues[key]
            ?: error("Resolved Cura setting is missing: $key")
        val value = raw.toDoubleOrNull() ?: error("Resolved Cura setting is not numeric: $key=$raw")
        require(value.isFinite() && value > 0.0) { "Resolved Cura setting is invalid: $key=$raw" }
        return value
    }

    private fun requiredBoolean(values: Map<String, String>, key: String): Boolean {
        val raw = values[key] ?: error("Resolved Cura setting is missing: $key")
        return raw.toBooleanStrictOrNull()
            ?: error("Resolved Cura setting is not boolean: $key=$raw")
    }

    private data class FileStamp(val length: Long, val modified: Long)

    private const val AFFINE_TRANSLATION_X = "enderslicer_mesh_translation_x"
    private const val AFFINE_TRANSLATION_Y = "enderslicer_mesh_translation_y"
    private const val AFFINE_TRANSLATION_Z = "enderslicer_mesh_translation_z"
    private val IDENTITY = listOf(
        1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0,
    )
    private val NON_PLANAR_STAGED_IDENTITY = StlSliceTransform(
        linear = IDENTITY,
        translationXmm = 0.0,
        translationYmm = 0.0,
        translationZmm = 0.0,
    )
    private val CONICAL_STAGED_IDENTITY = StlSliceTransform(
        linear = IDENTITY,
        translationXmm = 0.0,
        translationYmm = 0.0,
        translationZmm = 0.0,
    )
}
