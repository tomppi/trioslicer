package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.nonplanar.NonPlanarRuntime
import java.io.File

object GcodeLayerEventProcessor {
    fun materialize(
        baseFile: File,
        destination: File,
        events: List<LayerEvent>,
        firmware: CalibrationFirmwareEncoder = CalibrationFirmwareEncoder.fromFlavor(
            PrinterEnvelope.DEFAULT_GCODE_FLAVOR,
        ),
        /**
         * The machine end gcode handed to the engine, when the caller knows it. Its
         * first line marks where the end script starts: CuraEngine writes that script
         * before its `;End of Gcode` comment, so restoring at the comment alone leaves
         * the script running under an active M220/M221.
         */
        machineEndGcode: String? = null,
    ) {
        require(baseFile.isFile && baseFile.length() > 0L) { "The original sliced G-code is unavailable" }
        val orderedEvents = LayerEventOrdering.normalize(events).onEach(::validate)
        val grouped = orderedEvents.groupBy(LayerEvent::layerNumber)
        val eventTypes = orderedEvents.mapTo(linkedSetOf(), LayerEvent::type)
        val endGcodeFirstLine = machineEndGcode
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull(String::isNotEmpty)
        val endGcodeStartLine = endGcodeFirstLine?.let { locateMachineEndGcode(baseFile, it) }

        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.events.tmp")
        temporary.delete()
        temporary.bufferedWriter().use { writer ->
            var firmwareRetracted = false
            val deferredRetraction = mutableListOf<LayerEvent>()

            fun writeEvent(event: LayerEvent) {
                writer.write(";ENDERSLICER_LAYER_EVENT:${safeMarker(event.id)}:${event.type.name}:${event.source.name}")
                writer.newLine()
                val label = event.label.takeIf(String::isNotBlank) ?: event.displayName()
                writer.write(";ENDERSLICER_LAYER_EVENT_LABEL:${safeMarker(label)}")
                writer.newLine()
                commands(event, firmware).forEach { command ->
                    writer.write(command)
                    writer.newLine()
                }
            }

            var restoresWritten = false

            fun writeRestores() {
                if (restoresWritten) return
                restoresWritten = true
                deferredRetraction.forEach(::writeEvent)
                deferredRetraction.clear()

                if (LayerEventType.SPEED_FACTOR in eventTypes) {
                    writer.write("M220 S100 ; enderslicercura restore speed factor")
                    writer.newLine()
                }
                if (LayerEventType.FLOW_FACTOR in eventTypes) {
                    writer.write("M221 S100 ; enderslicercura restore flow factor")
                    writer.newLine()
                }
            }

            var lineIndex = 0
            baseFile.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parsedCommand = GcodeCommand.parse(line)
                    val opcode = parsedCommand?.opcode

                    // The end-G-code runs with whatever event factors are
                    // active when it starts (e.g. an M221 flow factor scales the
                    // final retract). Restore the factors before that boundary so
                    // the end script executes at safe, nominal settings. The
                    // script's own first line is the boundary when the caller
                    // named it; the comments below are the fallbacks.
                    val trimmedLine = line.trimStart()
                    if (
                        !restoresWritten &&
                        (lineIndex == endGcodeStartLine ||
                            trimmedLine.startsWith(";End of Gcode", ignoreCase = true) ||
                            trimmedLine.startsWith(";END_OF_PRINT") ||
                            trimmedLine == NonPlanarRuntime.MACHINE_END_SENTINEL)
                    ) {
                        writeRestores()
                    }

                    writer.write(line)
                    writer.newLine()
                    lineIndex++

                    when {
                        parsedCommand != null && firmware.isFirmwareRetract(parsedCommand) -> firmwareRetracted = true
                        parsedCommand != null && firmware.isFirmwareUnretract(parsedCommand) -> {
                            firmwareRetracted = false
                            // This G11 line has already been written by the time
                            // we reach here, so any retraction events that were
                            // deferred while the firmware was retracted can now
                            // be flushed safely.
                            if (deferredRetraction.isNotEmpty()) {
                                deferredRetraction.forEach(::writeEvent)
                                deferredRetraction.clear()
                            }
                        }
                    }

                    if (!line.startsWith(";LAYER:")) return@forEach
                    val layerNumber = line.substringAfter(':').trim().toIntOrNull() ?: return@forEach
                    grouped[layerNumber].orEmpty().forEach { event ->
                        if (event.type == LayerEventType.RETRACTION && firmwareRetracted) {
                            deferredRetraction += event
                        } else {
                            writeEvent(event)
                        }
                    }
                }
            }

            // Fallback for files without an end-G-code marker: keep the restores
            // at the end so event factors are still reset before the next job.
            writeRestores()
        }
        check(temporary.isFile && temporary.length() > 0L) { "Layer-event G-code output is empty" }
        if (destination.exists()) destination.delete()
        check(temporary.renameTo(destination) || temporary.copyTo(destination, overwrite = true).let { temporary.delete(); true }) {
            "Unable to write layer-event G-code"
        }
    }

    fun commands(event: LayerEvent): List<String> = commands(
        event,
        CalibrationFirmwareEncoder.fromFlavor(PrinterEnvelope.DEFAULT_GCODE_FLAVOR),
    )

    internal fun commands(
        event: LayerEvent,
        firmware: CalibrationFirmwareEncoder,
    ): List<String> {
        validate(event)
        return firmware.commands(
            type = event.type,
            layerNumber = event.layerNumber,
            value = event.value,
            secondaryValue = event.secondaryValue,
            text = event.text,
        )
    }

    /**
     * The line index where the machine end gcode starts: the last occurrence of its
     * first line at or after the file's final layer marker.
     *
     * The last-layer rule matters. A script may open with a line the start gcode also
     * contains (G91, M104 S0); matching that copy would spend the single restore point
     * before the print even started, so a match only counts once the layers have begun.
     * Null when the line is absent - a resolved profile may have rewritten the script -
     * and the caller falls back to the end-of-print comments.
     */
    private fun locateMachineEndGcode(file: File, firstLine: String): Int? {
        var lastLayerIndex = -1
        var match: Int? = null
        var index = 0
        file.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                when {
                    raw.startsWith(";LAYER:") -> {
                        lastLayerIndex = index
                        match = null
                    }
                    index > lastLayerIndex && raw.trim() == firstLine -> match = index
                }
                index++
            }
        }
        return match
    }

    private fun validate(event: LayerEvent) {
        require(event.layerNumber in -100_000..1_000_000) { "Layer event number is invalid" }
        event.value?.let { require(it.isFinite()) { "Layer event value must be finite" } }
        event.secondaryValue?.let { require(it.isFinite()) { "Layer event secondary value must be finite" } }
        when (event.type) {
            LayerEventType.NOZZLE_TEMPERATURE -> requireValue(event, 0.0, 450.0)
            LayerEventType.BED_TEMPERATURE -> requireValue(event, 0.0, 200.0)
            LayerEventType.FAN_SPEED -> requireValue(event, 0.0, 100.0)
            LayerEventType.SPEED_FACTOR,
            LayerEventType.FLOW_FACTOR,
            -> requireValue(event, 1.0, 999.0)
            LayerEventType.RETRACTION -> {
                requireValue(event, 0.0, 100.0)
                event.secondaryValue?.let { require(it in 0.1..1000.0) { "Retraction speed is outside 0.1..1000 mm/s" } }
            }
            LayerEventType.PRESSURE_ADVANCE -> requireValue(event, 0.0, 10.0)
            LayerEventType.JUNCTION_DEVIATION -> requireValue(event, 0.0, 1.0)
            LayerEventType.MESSAGE -> require(event.text.isNotBlank()) { "Message event text cannot be blank" }
            LayerEventType.CUSTOM_GCODE -> {
                val lines = event.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                require(lines.isNotEmpty()) { "Custom G-code event is empty" }
                require(lines.size <= 16) { "Custom G-code event exceeds 16 commands" }
                lines.forEach(::validateCustomLine)
            }
            LayerEventType.PAUSE,
            LayerEventType.FILAMENT_CHANGE,
            LayerEventType.CAMERA_TRIGGER,
            -> Unit
        }
    }

    private fun requireValue(event: LayerEvent, minimum: Double, maximum: Double) {
        val value = requireNotNull(event.value) { "${event.type} requires a value" }
        require(value in minimum..maximum) { "${event.type} value is outside $minimum..$maximum" }
    }

    private fun validateCustomLine(line: String) {
        require(line.length <= 160) { "Custom G-code command is too long" }
        require(line.none { it == '\u0000' || it == '\r' || it == '\n' }) { "Custom G-code contains a control character" }
        val command = GcodeCommand.parse(line)
            ?: throw IllegalArgumentException("Custom G-code must contain a recognized command")
        GcodeCommandPolicy.requireSafeCustomEvent(command)
        if (command.opcode == "M104" || command.opcode == "M109") {
            val target = command.value('S') ?: command.value('R')
            require(target != null && target in 0.0..450.0) { "Custom nozzle temperature is invalid" }
        }
        if (command.opcode == "M140" || command.opcode == "M190") {
            val target = command.value('S') ?: command.value('R')
            require(target != null && target in 0.0..200.0) { "Custom bed temperature is invalid" }
        }
    }

    private fun safeMarker(value: String): String = value
        .replace(Regex("[\\r\\n:;]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
        .ifBlank { "event" }
}
