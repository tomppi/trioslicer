package com.tomppi.enderslicer.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import java.io.File

/*
 * The seam onto the engine lane's Mesh-Mix-S2C port.
 *
 * The sheet, the state and the tests build against these types; the adapter onto
 * com.tomppi.enderslicer.mesh2step.MeshToStepEngine lives in MeshToStepEngines.create and is the
 * only place that names the engine class. Until that class lands this build carries an honest
 * "not part of this build yet" engine rather than a second implementation: there is exactly one
 * converter in the app, and it is the engine lane's.
 */

/** The unit's STEP flavour; millimetres are the app's own plate unit and the default. */
internal data class MeshToStepRequest(
    val sourceStl: File,
    val preset: MeshToStepPreset,
    val quality: Float,
    val unit: String = "mm",
    val repair: MeshToStepRepairMode = MeshToStepRepairMode.AUTO,
    val mergeOverlaps: Boolean = false,
)

internal data class MeshToStepProgress(
    val stage: String,
    val fraction: Float,
    val message: String? = null,
)

internal data class MeshToStepResult(
    val step: File,
    val faces: Int?,
    val warnings: List<String>,
    val millis: Long,
    /** False when the engine finished but its own check found mesh problems (still a real file). */
    val valid: Boolean = true,
    val solids: Int? = null,
)

internal interface MeshToStepEngine {
    /** Runs one conversion; throws a user-facing exception on engine/process/IO failure. */
    suspend fun convert(
        request: MeshToStepRequest,
        onProgress: (MeshToStepProgress) -> Unit,
    ): MeshToStepResult

    /** Kills the running conversion; the engine stays reusable. */
    fun cancel()

    fun close()
}

/**
 * The engine factory: the hole the engine lane fills.
 *
 * The app has exactly one converter - mesh2step/MeshToStepEngine - and this is the only place
 * that names it. The adapter maps the ui seam's types onto the engine lane's nested types; the
 * engine owns its own process and scope. Construction is guarded so a build without the engine
 * class (or a device where it cannot start) still opens the sheet with a named sentence instead
 * of crashing the UI.
 */
internal object MeshToStepEngines {
    fun create(context: Context, scope: CoroutineScope): MeshToStepEngine =
        HostMeshToStepEngine(context.applicationContext)
}

/**
 * The real adapter onto com.tomppi.enderslicer.mesh2step.MeshToStepEngine.
 *
 * The engine throws its own ConversionException with a user-facing sentence; this seam lets it
 * travel unchanged (the ViewModel shows the sentence). convert() is blocking - the ViewModel
 * calls it on Dispatchers.IO - and the engine's progress callback is already on the main thread.
 */
private class HostMeshToStepEngine(context: Context) : MeshToStepEngine {

    private val host = com.tomppi.enderslicer.mesh2step.MeshToStepEngine(context)

    override suspend fun convert(
        request: MeshToStepRequest,
        onProgress: (MeshToStepProgress) -> Unit,
    ): MeshToStepResult {
        val hostResult = host.convert(
            com.tomppi.enderslicer.mesh2step.MeshToStepEngine.Request(
                sourceStl = request.sourceStl,
                preset = com.tomppi.enderslicer.mesh2step.MeshToStepEngine.Preset.valueOf(request.preset.name),
                quality = request.quality.coerceIn(0f, 1f),
                unit = request.unit,
                mergeOverlaps = request.mergeOverlaps,
                repair = com.tomppi.enderslicer.mesh2step.MeshToStepEngine.RepairMode
                    .valueOf(request.repair.name),
            ),
        ) { progress ->
            onProgress(
                MeshToStepProgress(
                    stage = progress.phase,
                    fraction = progress.fraction.coerceIn(0f, 1f),
                    message = null,
                ),
            )
        }
        return MeshToStepResult(
            step = hostResult.step,
            faces = hostResult.faces,
            warnings = hostResult.warnings,
            millis = (hostResult.seconds * 1_000.0).toLong().coerceAtLeast(0L),
            valid = hostResult.valid,
            solids = hostResult.solids,
        )
    }

    override fun cancel() = host.cancel()

    override fun close() = host.close()
}

/** The seam's request for one open sheet: the UI's choices, mapped once, in one place. */
internal fun meshToStepRequestFor(sourceStl: File, state: MeshToStepState): MeshToStepRequest =
    MeshToStepRequest(
        sourceStl = sourceStl,
        preset = state.preset,
        quality = state.quality.coerceIn(0f, 1f),
        unit = state.unit.value,
        repair = state.repair,
        mergeOverlaps = state.mergeOverlaps,
    )

/** What the sheet keeps after a conversion: the cache file, faces and warnings. */
internal fun meshToStepResultInfo(result: MeshToStepResult): MeshToStepResultInfo =
    MeshToStepResultInfo(
        stepPath = result.step.absolutePath,
        faces = result.faces,
        solids = result.solids,
        valid = result.valid,
        warnings = result.warnings,
        millis = result.millis,
    )
