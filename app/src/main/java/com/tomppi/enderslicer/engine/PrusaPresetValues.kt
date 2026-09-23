package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.AllSettingsCatalogs
import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps resolved PrusaSlicer preset values onto the configuration the console accepts.
 *
 * The repository stores values the way a person writes them - '15%', 0.45, 1, '250x0' - while
 * the saved configuration the console loads is typed: percentages are {value, is_percent}
 * objects, filament and tool settings are per-slot arrays, bed shapes are point lists. The
 * shipped base configuration is the type oracle: for every key it already carries, its value
 * says which shape the engine expects, and a value the preset states is converted into that
 * shape. Keys the base configuration does not carry are converted from the value's own shape
 * when that is unambiguous (a number, a percentage, a point list) and skipped otherwise,
 * because a wrong shape is accepted silently and slices with the wrong setting.
 *
 * Keys the app itself writes (AllSettingsCatalogs.PRUSA_MANAGED_KEYS) are never taken from a
 * preset: the printer envelope, start/end G-code and every setting the sheet edits stay the
 * app's, exactly as they do for a Cura machine. Every G-code template is skipped for the same
 * reason - a Prusa machine's start gcode must not be spliced into an Ender's output - as are
 * the bundle's own default_* pointers, binary G-code and post-processing hooks.
 */
object PrusaPresetValues {

    /** The three buckets the console expects, already typed. */
    data class Buckets(
        val print: JSONObject,
        val printer: JSONObject,
        val filament: JSONObject,
        /** Keys a preset stated but that were left out, for the slice log. */
        val skipped: List<String>,
    ) {
        val isEmpty: Boolean get() = print.length() == 0 && printer.length() == 0 && filament.length() == 0
    }

    /** Keys that must never come from a preset, whatever the app does or does not write. */
    private val REFUSED = setOf(
        "binary_gcode",
        "post_process",
        "printer_model",
        "printer_variant",
        "printer_technology",
        "output_filename_format",
        "thumbnails",
    )

    fun map(
        baseConfigJson: String,
        printerValues: Map<String, Any?>,
        printValues: Map<String, Any?>,
        filamentValues: Map<String, Any?>,
    ): Buckets {
        val configuration = JSONObject(baseConfigJson).optJSONObject("configuration") ?: JSONObject()
        val printer = JSONObject()
        val print = JSONObject()
        val filament = JSONObject()
        val skipped = mutableListOf<String>()

        apply("printer_settings", printerValues, configuration, printer, skipped)
        apply("print_settings", printValues, configuration, print, skipped)
        apply("filament_settings", filamentValues, configuration, filament, skipped)
        return Buckets(print = print, printer = printer, filament = filament, skipped = skipped.sorted())
    }

    private fun apply(
        bucket: String,
        values: Map<String, Any?>,
        configuration: JSONObject,
        target: JSONObject,
        skipped: MutableList<String>,
    ) {
        val known = configuration.optJSONObject(bucket) ?: JSONObject()
        for ((key, value) in values) {
            if (key.startsWith("default_") || key.startsWith("__")) continue
            if (key in REFUSED || key.contains("gcode")) continue
            if (key in AllSettingsCatalogs.PRUSA_MANAGED_KEYS) continue
            val template = if (known.has(key)) known.opt(key) else null
            val mapped = mapValue(value, template)
            if (mapped == null) {
                skipped.add(bucket + "." + key)
                continue
            }
            target.put(key, mapped)
        }
    }

    /** Converts one stated value into the shape [template] uses, or into its own obvious shape. */
    private fun mapValue(value: Any?, template: Any?): Any? {
        if (value == null) return null
        when (template) {
            is JSONArray -> {
                val element = if (template.length() > 0) template.opt(0) else null
                // A per-slot option: a stated list is already per slot (machine_max_feedrate_x),
                // a stated scalar has to be wrapped (filament temperature). A point list states
                // its points as 'XxY' either way.
                if (value is List<*>) {
                    return mapList(value, element)
                }
                return JSONArray().put(mapScalar(value, element))
            }
            is JSONObject -> return mapScalar(value, template)
            is Boolean -> return value.toBooleanValue()
            is Number -> return value.toNumberValue()?.let { numberLike(it, template) }
                ?: value.toBooleanValue()?.let { if (it) 1 else 0 }
            is String -> return value.toString()
        }
        // The base configuration does not carry the key: only unambiguous shapes are taken.
        return when (value) {
            is Boolean -> null
            is Number -> numberLike(value.toDouble(), null)
            is String -> when {
                value.endsWith("%") -> percentage(value.dropLast(1).toDoubleOrNull(), true, null)
                value.toDoubleOrNull() != null -> numberLike(value.toDouble(), null)
                value.count { it == 'x' } == 1 && value.substringBefore('x').toDoubleOrNull() != null ->
                    points(listOf(value))
                else -> value
            }
            is List<*> -> when {
                value.all { it is Number } -> JSONArray().apply {
                    value.forEach { put(numberLike((it as Number).toDouble(), null)) }
                }
                value.all { it is String && it.count { character -> character == 'x' } == 1 } ->
                    points(value.map { it.toString() })
                else -> null
            }
            else -> null
        }
    }

    private fun mapScalar(value: Any?, template: Any?): Any? {
        // The console's saved format is typed exactly as the base configuration is: a boolean
        // option takes a JSON boolean, an integer stays an integer and a float keeps its point.
        // Writing 1 where a boolean belongs is not a smaller truth - the engine fails the slice.
        if (template is Boolean) return value.toBooleanValue()
        // A FloatOrPercent option: the value states whether it is a percentage of the setting
        // it relates to, which is exactly what the console's is_percent flag records.
        if (template is JSONObject && template.has("is_percent")) {
            val text = value.toString()
            val isPercent = text.endsWith("%")
            val parsed = text.removeSuffix("%").toDoubleOrNull()
                ?: (value as? Number)?.toDouble()
                ?: return null
            return percentage(parsed, isPercent, template.opt("value"))
        }
        return when (value) {
            is Boolean -> value.toBooleanValue()?.let { if (it) 1 else 0 }
            is Number -> numberLike(value.toDouble(), template)
            is String -> when {
                value.endsWith("%") -> percentage(value.dropLast(1).toDoubleOrNull(), true, null)
                template is Number || template == null -> {
                    val parsed = value.toDoubleOrNull()
                    if (parsed != null) numberLike(parsed, template) else value
                }
                else -> value
            }
            is List<*> -> mapList(value, null)
            else -> null
        }
    }

    /** A stated list: points when it is written as such, otherwise per-slot values. */
    private fun mapList(values: List<*>, element: Any?): Any? = when {
        values.all { it is String && it.count { character -> character == 'x' } == 1 } ->
            points(values.map { it.toString() })
        element is JSONArray -> null
        else -> JSONArray().apply {
            values.forEach { item ->
                val mapped = mapScalar(item, element) ?: return null
                put(mapped)
            }
        }
    }

    private fun Any?.toNumberValue(): Double? = when (this) {
        is Number -> toDouble()
        is String -> removeSuffix("%").toDoubleOrNull()
        else -> null
    }

    private fun Any?.toBooleanValue(): Boolean? = when (this) {
        is Boolean -> this
        is Number -> toDouble() != 0.0
        is String -> when (lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
        else -> null
    }

    /**
     * Keeps the number kind [template] uses: a float option keeps its decimal point, an integer
     * option stays integral. An unknown key takes the value's own obvious kind.
     */
    private fun numberLike(value: Double, template: Any?): Any = when (template) {
        // Android's org.json reads a decimal as a Double and the JVM one as a BigDecimal, so
        // both count as a float option here; an integral option keeps its integer form.
        is Double, is Float, is BigDecimal -> value
        is Int, is Long, is Short, is Byte, is BigInteger -> integral(value)
        else -> integral(value)
    }

    private fun integral(value: Double): Any =
        if (value == value.toLong().toDouble()) value.toLong() else value

    private fun percentage(value: Double?, isPercent: Boolean, template: Any?): Any? {
        if (value == null) return null
        return JSONObject().put("value", numberLike(value, template)).put("is_percent", isPercent)
    }

    /** The repository writes a point as "XxY". */
    private fun points(values: List<String>): JSONArray = JSONArray().apply {
        values.forEach { point ->
            val x = point.substringBefore('x').toDoubleOrNull()
            val y = point.substringAfter('x').toDoubleOrNull()
            if (x != null && y != null) {
                put(JSONArray().put(x).put(y))
            }
        }
    }
}
