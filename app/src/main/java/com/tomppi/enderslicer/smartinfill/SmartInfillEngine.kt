package com.tomppi.enderslicer.smartinfill

import java.io.Closeable

/**
 * The Smart Infill engine the Plate workflow drives.
 *
 * [FilaSimEngine] is the real implementation, backed by the native solver;
 * the interface exists so the workflow logic — conditions, picking, run
 * sequencing, results — is testable without the phone's native library.
 */
interface SmartInfillEngine : Closeable {
    val sourceName: String

    val sourceSha256: String

    val patchCount: Int

    fun sessionInfo(): FilaSimVoxelInfo

    /** The settings the next run will use, as the engine itself reports them. */
    fun configuration(): FilaSimConfiguration

    fun patchOfTriangle(): IntArray

    fun trianglesOfPatch(patch: Int): IntArray

    /**
     * The connected part of the hit triangle's surface within [radiusMm] — what
     * a tap assigns. A whole crease patch would be the entire model on an
     * organic surface, so the tap is a bounded disc instead.
     */
    fun regionAround(triangle: Int, radiusMm: Double): IntArray

    /**
     * The density bin of the material under every ORIGINAL model triangle (-1
     * where there is none), which is what the result view tints the part with.
     * Empty before an optimization.
     */
    fun surfaceBins(): IntArray

    fun originalPositions(): FloatArray

    fun setConfiguration(configuration: FilaSimConfiguration)

    fun clearBoundaryConditions()

    fun addBoundaryCondition(condition: FilaSimBoundaryCondition): Int

    /**
     * Island + rigid-body-mode check. Suspends on a background dispatcher: the
     * check assembles the system, which is seconds of work on a real part.
     */
    suspend fun checkSetup(): FilaSimCheckReport

    suspend fun solve(): FilaSimSolveReport

    suspend fun optimize(
        options: FilaSimOptimizeOptions,
        onProgress: (FilaSimProgress) -> Unit = {},
    ): FilaSimOptimization

    fun cancel()

    fun modifierArchive(): ByteArray

    fun optimizedShape(): ByteArray
}
