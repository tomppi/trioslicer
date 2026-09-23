package com.tomppi.enderslicer.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.model.withSettings
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlParser
import java.io.File

internal data class PreparedPartTopoResult(
    val source: StlMesh,
    val transformed: StlMesh,
    val modelFile: File,
    val placement: ModelPlacement,
)

internal object PartTopoResultPreparer {
    fun prepare(
        context: Context,
        uri: Uri,
        analyzedDisplayedMesh: StlMesh,
        printer: PrinterDefinition,
        settings: SlicerSettings,
    ): PreparedPartTopoResult {
        val triangleLimit = MeshTriangleLimits.current()
        val file = materialize(context, uri, triangleLimit)
        try {
            val source = StlParser.parse(
                file = file,
                displayName = displayName(context, uri),
                maxTriangles = triangleLimit,
            )
            val placement = placementFor(analyzedDisplayedMesh)
            val transformed = placement.transformed(source)
            PrinterEnvelope.from(printer.withSettings(settings)).requireBinaryStlFits(
                file,
                requireNotNull(transformed.slicingTransform) {
                    "Part Topo placement transform is unavailable"
                },
            )
            return PreparedPartTopoResult(source, transformed, file, placement)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    /**
     * filaSim imports the exact displayed mesh after translating its XY centre
     * and minimum Z to local zero. Its Part Topo export remains in that local
     * frame, so restore only that known translation. Rotation, scale and any
     * 3MF affine are already baked into the displayed input and must not be
     * applied again.
     */
    internal fun placementFor(analyzedDisplayedMesh: StlMesh): ModelPlacement = ModelPlacement(
        linear = ModelPlacement.IDENTITY,
        centerXmm = analyzedDisplayedMesh.bounds.centerX.toDouble(),
        centerYmm = analyzedDisplayedMesh.bounds.centerY.toDouble(),
        baseZmm = analyzedDisplayedMesh.bounds.minZ.toDouble(),
        source = "filaSim Part Topo result",
    )

    private fun materialize(context: Context, uri: Uri, maxTriangles: Int): File {
        val directory = File(context.filesDir, "models").apply {
            check(mkdirs() || isDirectory) { "Unable to create the model directory" }
        }
        val target = File(directory, "part-topo-${System.nanoTime()}.stl")
        val temporary = File(directory, "${target.name}.tmp")
        val maxBytes = MeshTriangleLimits.maxInputFileBytes(maxTriangles)
        temporary.delete()
        try {
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) {
                            "Part Topo STL is larger than ${MeshTriangleLimits.formatBytes(maxBytes)} for the ${MeshTriangleLimits.formatCount(maxTriangles)}-triangle limit"
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Unable to copy the filaSim Part Topo result")
            check(temporary.length() > 0L) { "The filaSim Part Topo result is empty" }
            check(
                temporary.renameTo(target) ||
                    temporary.copyTo(target, overwrite = false).let { temporary.delete(); true },
            ) { "Unable to store the filaSim Part Topo result" }
            return target
        } catch (error: Throwable) {
            temporary.delete()
            target.delete()
            throw error
        }
    }

    private fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.takeIf(String::isNotBlank)
            ?: "filaSim Part Topo.stl"
}
