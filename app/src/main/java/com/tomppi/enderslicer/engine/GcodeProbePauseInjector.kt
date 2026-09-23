package com.tomppi.enderslicer.engine

import java.io.File

/**
 * Inserts a user-resumable pause immediately after the bed probe so the operator
 * can raise a deployable probe out of the way before non-planar (conical /
 * conformal surface) motion begins. The pause is emitted only when the start G-code
 * actually probes with G29; a saved-mesh start script without G29 is left alone.
 */
internal object GcodeProbePauseInjector {
    private const val MARKER = ";ENDERSLICER_PROBE_PAUSE"
    private const val PAUSE_MESSAGE = "Tilt probe up, resume"

    /**
     * Finds the last G29 before the first printable layer and writes `M117` +
     * `M0` immediately after it. Returns false when there is no probe to pause
     * after, or when the marker is already present (idempotent).
     */
    fun inject(file: File): Boolean {
        require(file.isFile && file.length() > 0L) { "Sliced G-code is unavailable" }

        var lastProbeIndex = -1
        var sawMarker = false
        var index = 0
        file.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val trimmed = line.trimStart()
                if (trimmed == MARKER) sawMarker = true
                if (trimmed.startsWith(";LAYER:")) break
                if (GcodeCommand.parse(line)?.opcode == "G29") lastProbeIndex = index
                index++
            }
        }
        if (lastProbeIndex < 0 || sawMarker) return false

        val temporary = File(file.parentFile, "${file.name}.probe-pause.tmp")
        temporary.delete()
        try {
            file.bufferedReader().use { reader ->
                temporary.bufferedWriter().use { writer ->
                    var current = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        writer.write(line)
                        writer.newLine()
                        if (current == lastProbeIndex) {
                            writer.write(MARKER)
                            writer.newLine()
                            writer.write("M117 $PAUSE_MESSAGE")
                            writer.newLine()
                            writer.write("M0")
                            writer.newLine()
                        }
                        current++
                    }
                }
            }
            try {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.io.IOException) {
                check(temporary.renameTo(file) || temporary.copyTo(file, overwrite = true).let { temporary.delete(); true }) {
                    "Unable to publish the probe-pause G-code"
                }
            }
        } finally {
            temporary.delete()
        }
        return true
    }
}
