package com.tomppi.enderslicer.mesh2step

import org.json.JSONObject

/**
 * The JSON-lines protocol of the bundled Mesh-Mix-S2C engine, parsed off its stdout.
 *
 * The engine speaks one JSON object per line. [MeshToStepProtocol.parse] understands every
 * event kind the engine emits - stage, progress, log, warning, part, result and error - and
 * never throws on a malformed or non-JSON line: a line that is not the engine's protocol is
 * returned as [Event.Malformed] so the runner can keep it as ordinary log output. That
 * matters because a native library printing to a file descriptor would otherwise look like a
 * broken conversion.
 */
internal object MeshToStepProtocol {

    /** One parsed engine event. */
    internal sealed interface Event {
        data class Stage(val stage: String, val fraction: Float) : Event
        data class Progress(val stage: String, val fraction: Float) : Event
        data class Log(val message: String) : Event
        data class Warning(val message: String) : Event
        data class Part(val part: JSONObject) : Event
        data class Result(val data: JSONObject) : Event
        data class Error(val message: String, val reason: String?, val details: String?) : Event
        data class Malformed(val line: String) : Event
    }

    /** Parses one stdout line, or null for a blank line. */
    fun parse(line: String): Event? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val json = try {
            JSONObject(trimmed)
        } catch (_: Throwable) {
            return Event.Malformed(trimmed)
        }
        return when (val kind = json.optString("event")) {
            "stage" -> Event.Stage(
                stage = json.optString("stage"),
                fraction = clampFraction(json.optDouble("progress", Double.NaN)),
            )
            "progress" -> Event.Progress(
                stage = json.optString("stage"),
                fraction = clampFraction(json.optDouble("progress", Double.NaN)),
            )
            "log" -> Event.Log(json.optString("message"))
            "warning" -> Event.Warning(json.optString("message"))
            "part" -> Event.Part(json)
            "result" -> Event.Result(json)
            "error" -> Event.Error(
                message = json.optString("message"),
                reason = json.optString("reason").takeIf { it.isNotBlank() },
                details = json.optString("details").takeIf { it.isNotBlank() },
            )
            else -> Event.Malformed(trimmed)
        }
    }

    /**
     * A progress fraction the seam may show: NaN (no number in the event) becomes the last
     * known fraction, and a value outside 0..1 is clamped rather than allowed to jump.
     */
    fun clampFraction(value: Double, previous: Float = 0f): Float =
        if (value.isNaN()) previous else value.coerceIn(0.0, 1.0).toFloat()

    /** The engine's command-line size of a preset: AUTO/CAD/SMOOTH/PRECISE/EXACT. */
    fun presetArgument(preset: MeshToStepEngine.Preset): String = preset.engineArgument

    /** The engine's --quality value: 0..1, printed without an exponent. */
    fun qualityArgument(quality: Float): String {
        val value = quality.coerceIn(0f, 1f)
        return if (value == value.toInt().toFloat()) value.toInt().toString()
        else String.format(java.util.Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
    }

    /** Units the engine accepts (its argparse choices). */
    val UNITS: Set<String> = setOf("mm", "cm", "m", "in", "um", "ft")

    /**
     * The arguments after the launcher for one conversion.
     *
     * Always JSON mode: stdout carries only protocol messages. Tolerance is only passed when
     * the caller set one; otherwise the engine derives it from the model size and quality.
     */
    fun convertArguments(
        request: MeshToStepEngine.Request,
        input: java.io.File,
        output: java.io.File,
    ): List<String> {
        val arguments = mutableListOf(
            "convert",
            input.absolutePath,
            "-o",
            output.absolutePath,
            "--preset",
            presetArgument(request.preset),
            "--quality",
            qualityArgument(request.quality),
            "--unit",
            request.unit,
            "--json",
        )
        request.tolerance?.let { tolerance ->
            arguments += "--tolerance"
            arguments += java.math.BigDecimal.valueOf(tolerance).stripTrailingZeros().toPlainString()
        }
        // The engine defaults are repair=auto and no overlap merging, so the flags are only
        // added when the caller chose something different; the default argv is unchanged.
        if (request.mergeOverlaps) {
            arguments += "--merge-overlaps"
        }
        if (request.repair != MeshToStepEngine.RepairMode.AUTO) {
            arguments += "--repair"
            arguments += request.repair.engineArgument
        }
        return arguments
    }

    /** The user-facing engine sentence from an error event: message plus its reason. */
    fun errorSentence(error: Event.Error): String {
        val message = error.message.ifBlank { "the converter reported an error" }
        return error.reason?.let { "$message $it" } ?: message
    }

    /**
     * Validation/repair findings from the final result event, as plain sentences.
     *
     * The engine's result carries a mesh check and a fidelity report; both are advisory for a
     * conversion that still produced a STEP file, so they become [MeshToStepEngine.Result]
     * warnings rather than failures.
     */
    fun warnings(result: JSONObject): List<String> {
        val warnings = mutableListOf<String>()
        if (!result.optBoolean("valid", true)) {
            warnings += "The converter could not validate the STEP file."
        }
        val meshCheck = result.optJSONObject("mesh_check")
        val issues = meshCheck?.optJSONArray("issues")
        if (issues != null) {
            for (index in 0 until issues.length()) {
                val issue = issues.optJSONObject(index) ?: continue
                val sentence = listOf(
                    issue.optString("problem"),
                    issue.optString("message"),
                    issue.optString("code"),
                ).firstOrNull { it.isNotBlank() } ?: continue
                warnings += sentence
            }
        }
        val checks = result.optJSONArray("checks")
        if (checks != null) {
            for (index in 0 until checks.length()) {
                val check = checks.optJSONObject(index) ?: continue
                val message = check.optString("message").ifBlank { check.optString("code") }
                if (message.isNotBlank()) warnings += message
            }
        }
        val fidelity = result.optJSONObject("fidelity")
        if (fidelity != null && fidelity.has("ok") && !fidelity.optBoolean("ok", true)) {
            val tolerance = fidelity.optDouble("tolerance", Double.NaN)
            warnings += if (tolerance.isNaN()) {
                "The CAD surfaces follow the mesh loosely."
            } else {
                String.format(
                    java.util.Locale.US,
                    "The CAD surfaces follow the mesh loosely (tolerance %.3g mm).",
                    tolerance,
                )
            }
        }
        return warnings.distinct().take(12)
    }

    /**
     * A safe STEP file name for the cache, derived from the mesh's file name.
     *
     * The engine writes into its own run directory; this name is what the UI copies to the
     * user's chosen destination, so it keeps the source's identity and is always a .step file.
     */
    fun outputName(sourceStl: java.io.File): String {
        val stem = sourceStl.name.substringBeforeLast('.', sourceStl.name)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_', '.', '-')
            .take(60)
            .ifBlank { "model" }
        return "$stem-cad.step"
    }
}