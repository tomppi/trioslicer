package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.conical.ConicalPipeline
import com.tomppi.enderslicer.conical.ConicalRuntime
import com.tomppi.enderslicer.conical.ConicalStorage
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.nonplanar.ConformalSurfaceStorage
import com.tomppi.enderslicer.nonplanar.NonPlanarPipeline
import com.tomppi.enderslicer.nonplanar.NonPlanarRuntime
import com.tomppi.enderslicer.smartinfill.SmartInfillModifier
import com.tomppi.enderslicer.supportpaint.SupportPaintModifier
import java.io.File

/**
 * Single source of truth for the non-planar preparation shared by both engine
 * transports (the standalone command builder and the resolved-settings
 * writer). Builds the conformal surface regions from the displayed mesh,
 * re-validates the staged model against the printer envelope, and persists the
 * sidecars the G-code transformers read back.
 */
internal object NonPlanarPreparation {
    /**
     * The conformal shell banding assumes a constant layer height (the
     * reference implementation requires the same), so adaptive layer height
     * is forced off for non-planar slices on a slice-local copy.
     */
    fun adjustSettings(settings: SlicerSettings): SlicerSettings = settings.copy(
        adaptiveLayerHeightEnabled = false,
        overriddenSettingKeys = settings.overriddenSettingKeys + SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED,
    )

    data class Outcome(
        val nonPlanarPrepared: NonPlanarPipeline.ConformalPrepared?,
        val conicalPrepared: ConicalPipeline.Prepared?,
    )

    fun prepare(
        modelFile: File,
        workspace: File,
        printerEnvelope: PrinterEnvelope,
        layerHeightMm: Double,
        nozzleDiameterMm: Double,
        smartInfillModifiers: List<SmartInfillModifier>,
        adaptiveWallModifiers: List<AdaptiveWallModifier>,
        supportPaintModifiers: List<SupportPaintModifier>,
    ): Outcome {
        val nonPlanarPrepared = NonPlanarRuntime.snapshot()?.let { active ->
            require(adaptiveWallModifiers.isEmpty()) {
                "Adaptive walls cannot be combined with non-planar printing"
            }
            require(smartInfillModifiers.isEmpty()) {
                "Smart Infill cannot be combined with non-planar printing: " +
                    "the conformal shells need the full top-layer material"
            }
            require(ConicalRuntime.snapshot() == null) {
                "Non-planar printing cannot be combined with conical slicing"
            }
            NonPlanarPipeline.prepareConformal(
                modelFile = modelFile,
                settings = active.settings,
                layerHeightMm = layerHeightMm,
                nozzleDiameterMm = nozzleDiameterMm,
            )
        }
        if (nonPlanarPrepared != null) {
            // The model is untouched in conformal mode, so modifier volumes
            // stay at their displayed coordinates and need no warping.
            printerEnvelope.requireBinaryStlFits(modelFile)
            supportPaintModifiers.forEach { modifier ->
                printerEnvelope.requireBinaryStlFits(
                    modifier.file,
                    label = "Support-paint modifier " + modifier.file.name,
                )
            }
            ConformalSurfaceStorage.write(workspace, nonPlanarPrepared)
        }

        val conicalPrepared = ConicalRuntime.snapshot()?.let { snapshot ->
            require(smartInfillModifiers.isEmpty()) {
                "Conical slicing cannot be combined with Smart Infill modifier volumes"
            }
            require(adaptiveWallModifiers.isEmpty()) {
                "Adaptive walls cannot be combined with conical slicing: " +
                    "the modifier volumes are generated from the un-warped model and would misalign"
            }
            ConicalPipeline.prepareAndWarp(modelFile = modelFile, settings = snapshot.settings)
        }
        if (conicalPrepared != null) {
            supportPaintModifiers.forEach { modifier -> conicalPrepared.warpModifier(modifier.file) }
            printerEnvelope.requireBinaryStlFits(modelFile)
            supportPaintModifiers.forEach { modifier ->
                printerEnvelope.requireBinaryStlFits(
                    modifier.file,
                    label = "Support-paint modifier " + modifier.file.name,
                )
            }
            ConicalStorage.write(workspace, conicalPrepared)
        }
        return Outcome(nonPlanarPrepared, conicalPrepared)
    }

    fun markMachineEndGcode(gcode: String): String =
        ConicalRuntime.markMachineEndGcode(NonPlanarRuntime.markMachineEndGcode(gcode))
}
