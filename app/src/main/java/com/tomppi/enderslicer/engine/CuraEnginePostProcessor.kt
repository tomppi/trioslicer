package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.conical.ConicalRuntime
import com.tomppi.enderslicer.conical.ConicalStorage
import com.tomppi.enderslicer.nonplanar.ConformalSurfaceStorage
import com.tomppi.enderslicer.nonplanar.NonPlanarRuntime
import com.tomppi.enderslicer.nonplanar.NozzleCollisionAlert
import com.tomppi.enderslicer.nonplanar.NozzleCollisionScanner
import java.io.File

/** Finalizes staged engine output before it is eligible for immutable publication. */
internal object CuraEnginePostProcessor {
    private fun gcodeRequestsNonPlanar(file: File): Boolean = file.bufferedReader().useLines { lines ->
        lines.any { it.trim() == NonPlanarRuntime.MACHINE_END_SENTINEL }
    }

    private fun gcodeRequestsConical(file: File): Boolean = file.bufferedReader().useLines { lines ->
        lines.any { it.trim() == ConicalRuntime.MACHINE_END_SENTINEL }
    }

    data class Result(
        val summary: GcodeSanitizer.Summary,
        val layerPreview: GcodeLayerPreview?,
        val previewFailure: Throwable?,
        val layerEvents: List<LayerEvent>,
        val usedZeroEventFastPath: Boolean,
        val nozzleCollisionAlert: NozzleCollisionAlert? = null,
        val collisionSweepFailure: String? = null,
    )

    fun process(
        outputFile: File,
        baseGcodeFile: File,
        settingsTransport: String,
        layerEvents: List<LayerEvent>,
        printerEnvelope: PrinterEnvelope,
        /** The machine end gcode the engine was given; see [GcodeLayerEventProcessor.materialize]. */
        machineEndGcode: String? = null,
        amlEnabled: Boolean = false,
        amlMarginMm: Double = 5.0,
        amlGridPoints: Int = AdaptiveBedMeshInjector.DEFAULT_GRID_POINTS,
    ): Result {
        val workspace = outputFile.parentFile
            ?: error("CuraEngine output path has no parent workspace")
        val effectiveEnvelope = resolvedEnvelope(workspace) ?: printerEnvelope
        val firmware = CalibrationFirmwareEncoder.fromFlavor(effectiveEnvelope.gcodeFlavor)
        require(
            !(ConformalSurfaceStorage.isPrepared(workspace) &&
                ConicalStorage.isPrepared(workspace)),
        ) {
            "Non-planar and conical slicing cannot both be prepared for a single slice"
        }
        val conformalDiagnostics = ConformalSurfaceStorage.conformalStagedGcode(outputFile, effectiveEnvelope)
        if (conformalDiagnostics == null && gcodeRequestsNonPlanar(outputFile)) {
            throw IllegalStateException(
                "Non-planar printing was requested for this slice but its surface data is missing; " +
                    "refusing to publish a planar G-code for a non-planar request",
            )
        }
        val conicalDiagnostics = ConicalStorage.backtransformStagedGcode(outputFile, effectiveEnvelope)
        if (conicalDiagnostics == null && gcodeRequestsConical(outputFile)) {
            throw IllegalStateException(
                "Conical slicing was requested for this slice but its transform data is missing; refusing " +
                    "to publish a warped-model G-code without its back-transformation",
            )
        }
        val probePauseInjected = if (
            (conformalDiagnostics != null && NonPlanarRuntime.current().pauseAfterProbe) ||
            (conicalDiagnostics != null && ConicalRuntime.current().pauseAfterProbe)
        ) {
            GcodeProbePauseInjector.inject(outputFile)
        } else {
            false
        }
        val effectiveTransport = when {
            conformalDiagnostics != null -> "$settingsTransport+conformal-surface-android-v1"
            conicalDiagnostics != null -> "$settingsTransport+conical-android-v1"
            else -> settingsTransport
        }.let { if (probePauseInjected) "$it+probe-pause" else it }
        // Sweep the user-measured collision volume (nozzle cone + heating
        // block cone + whole-plate cutoff) along the curved toolpath so the
        // slice result can warn before a nozzle scrape happens on the printer.
        var collisionSweepFailure: String? = null
        val nozzleCollisionAlert = if (conformalDiagnostics != null) {
            runCatching {
                NozzleCollisionScanner.scan(
                    gcode = outputFile,
                    settings = NonPlanarRuntime.current(),
                )
            }.onFailure { collisionSweepFailure = it.message ?: "unknown sweep failure" }
                .getOrNull()
        } else {
            null
        }

        // The AML block belongs to the retained base G-code: the layer-event
        // publish path rebuilds the published file from base.gcode, so injecting
        // after that copy silently dropped leveling from every layer-event edit.
        // Injecting before validation also keeps the sanitizer summary and the
        // layer preview describing the bytes that are actually published. The
        // injector is idempotent, and the block always lands before the first
        // layer marker, so the sanitizer accepts it as startup G-code.
        val amlInjected = amlEnabled &&
            AdaptiveBedMeshInjector.inject(outputFile, effectiveEnvelope, amlMarginMm, amlGridPoints)
        val validatedTransport = if (amlInjected) {
            "$effectiveTransport+adaptive-mesh-leveling"
        } else {
            effectiveTransport
        }

        val baseSummary = GcodeSanitizer.validateAndRepair(
            file = outputFile,
            settingsTransport = validatedTransport,
            printerEnvelope = effectiveEnvelope,
        )
        outputFile.copyTo(baseGcodeFile, overwrite = true)
        check(baseGcodeFile.isFile && baseGcodeFile.length() > 0L) {
            "Unable to retain original sliced G-code"
        }

        val basePreview = GcodeLayerPreviewParser.parse(baseGcodeFile)
        val validLayerNumbers = basePreview.layers.mapTo(hashSetOf()) { it.number }
        val resolvedEvents = LayerEventOrdering.normalize(
            layerEvents.filter { it.layerNumber in validLayerNumbers },
        )

        if (resolvedEvents.isEmpty()) {
            return Result(
                summary = baseSummary,
                layerPreview = basePreview,
                previewFailure = null,
                layerEvents = emptyList(),
                usedZeroEventFastPath = true,
                nozzleCollisionAlert = nozzleCollisionAlert,
                collisionSweepFailure = collisionSweepFailure,
            )
        }

        GcodeLayerEventProcessor.materialize(
            baseGcodeFile,
            outputFile,
            resolvedEvents,
            firmware,
            machineEndGcode = machineEndGcode,
        )
        val summary = GcodeSanitizer.validateAndRepair(
            file = outputFile,
            settingsTransport = "$validatedTransport+layer-events",
            printerEnvelope = effectiveEnvelope,
        )
        val previewResult = runCatching { GcodeLayerPreviewParser.parse(outputFile) }
        return Result(
            summary = summary,
            layerPreview = previewResult.getOrNull(),
            previewFailure = previewResult.exceptionOrNull(),
            layerEvents = resolvedEvents,
            usedZeroEventFastPath = false,
            nozzleCollisionAlert = nozzleCollisionAlert,
            collisionSweepFailure = collisionSweepFailure,
        )
    }

    private fun resolvedEnvelope(directory: File?): PrinterEnvelope? {
        val file = directory?.let { File(it, PrinterEnvelope.METADATA_FILE_NAME) } ?: return null
        return file.takeIf(File::isFile)?.let(PrinterEnvelope::readFrom)
    }
}
