package com.tomppi.enderslicer.conical

import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.engine.publishAtomic
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Isolated request-side handoff between STL warping and G-code restoration. */
internal object ConicalStorage {
    private const val FILE_NAME = "conical-transform.bin"
    private const val MAGIC = 0x434F4E45
    private const val VERSION = 1

    fun write(workspace: File, prepared: ConicalPipeline.Prepared) {
        require(workspace.isDirectory) { "Conical workspace is unavailable" }
        val destination = File(workspace, FILE_NAME)
        val temporary = File(workspace, "$FILE_NAME.tmp")
        temporary.delete()
        try {
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeDouble(prepared.centerX)
                output.writeDouble(prepared.centerY)
                with(prepared.settings) {
                    output.writeDouble(coneAngleDegrees)
                    output.writeInt(refinementIterations)
                    output.writeUTF(coneType.name)
                    output.writeDouble(firstLayerHeightMm)
                    output.writeDouble(xShiftMm)
                    output.writeDouble(yShiftMm)
                }
                with(prepared.diagnostics) {
                    output.writeInt(sourceTriangles)
                    output.writeInt(refinedTriangles)
                    output.writeInt(refinementIterations)
                    output.writeDouble(coneAngleDegrees)
                }
            }
            check(temporary.isFile && temporary.length() > 64L) { "Unable to persist the conical transform" }
            publishAtomic(temporary, destination, "the conical transform")
        } finally {
            temporary.delete()
        }
    }

    fun backtransformStagedGcode(
        file: File,
        printerEnvelope: PrinterEnvelope,
    ): ConicalPipeline.GcodeDiagnostics? {
        val sidecar = File(file.parentFile, FILE_NAME)
        if (!sidecar.isFile) return null
        return read(sidecar).backtransformGcode(file, printerEnvelope)
    }

    fun isPrepared(workspace: File): Boolean = File(workspace, FILE_NAME).isFile

    private fun read(file: File): ConicalPipeline.Prepared =
        DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == MAGIC) { "Invalid conical transform marker" }
            require(input.readInt() == VERSION) { "Unsupported conical transform version" }
            val centerX = input.readDouble()
            val centerY = input.readDouble()
            val settings = ConicalSettings(
                enabled = true,
                coneAngleDegrees = input.readDouble(),
                refinementIterations = input.readInt(),
                coneType = runCatching { ConeType.valueOf(input.readUTF()) }.getOrElse {
                    error("Invalid conical cone type")
                },
                firstLayerHeightMm = input.readDouble(),
                xShiftMm = input.readDouble(),
                yShiftMm = input.readDouble(),
            ).validated()
            val diagnostics = ConicalPipeline.Diagnostics(
                sourceTriangles = input.readInt(),
                refinedTriangles = input.readInt(),
                refinementIterations = input.readInt(),
                coneAngleDegrees = input.readDouble(),
            )
            ConicalPipeline.Prepared(centerX, centerY, settings, diagnostics)
        }
}
