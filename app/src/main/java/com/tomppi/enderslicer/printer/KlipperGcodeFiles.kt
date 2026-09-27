package com.tomppi.enderslicer.printer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * A file the printer can print, with what its own header says about it.
 *
 * Every slicer writes a comment block at the top of the file naming itself and what it
 * decided - the time it thinks the print takes, the filament it uses, the layer height.
 * Reading it here is the difference between a file list and an answer to "which of these
 * is this one".
 */
data class KlipperGcodeFile(
    val name: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val slicer: String = "",
    val estimatedSeconds: Int? = null,
    val filamentMillimetres: Double? = null,
    val filamentGrams: Double? = null,
    val layerHeight: Double? = null,
    val layerCount: Int? = null,
    /** The first thumbnail in the header, still base64: decoded only when shown. */
    val thumbnail: String? = null,
)

/**
 * Reading the virtual SD card's directory.
 *
 * The directory is the app's own storage - [virtual_sdcard] is pointed at it in the
 * generated configuration - so listing it needs no protocol and no upload: a file is a
 * file, and the printer is told to print it by name.
 *
 * Only the head of each file is read. A g-code file is tens of megabytes and its header
 * is its first few kilobytes; reading them whole to show a list would be slow on the
 * phone and pointless, since the only thing further down is the print itself.
 */
internal object KlipperGcodeFiles {
    /**
     * Files the printer's directory holds, newest first.
     *
     * Only [KlipperPrint.fileName]'s shape is listed, which is what klippy itself will
     * print: a stray file that is not g-code is not offered, because asking the printer
     * to print it is an error a user should not be able to make from a list.
     */
    fun list(directory: File): List<KlipperGcodeFile> {
        val entries = directory.listFiles() ?: return emptyList()
        return entries
            .filter { it.isFile && it.name.endsWith(".gcode", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .map { file -> read(file) }
    }

    /** One file's name, size, date and header. */
    fun read(file: File): KlipperGcodeFile {
        val header = readHeader(file)
        return KlipperGcodeFile(
            name = file.name,
            sizeBytes = file.length(),
            modifiedAtMillis = file.lastModified(),
            slicer = slicer(header),
            estimatedSeconds = estimatedSeconds(header),
            filamentMillimetres = value(header, FILAMENT_MM)?.toDoubleOrNull(),
            filamentGrams = value(header, FILAMENT_G)?.toDoubleOrNull(),
            layerHeight = (value(header, LAYER_HEIGHT) ?: curaValue(header, "Layer height"))?.toDoubleOrNull(),
            layerCount = (patterned(header, LAYER_COUNT)
                ?: value(header, "total_layer_number")
                ?: curaValue(header, "LAYER_COUNT"))?.toIntOrNull(),
            thumbnail = thumbnail(header),
        )
    }

    /**
     * The comments of a file: its head, and its tail.
     *
     * The head alone was not enough, and the slicer this app ships is the reason. OrcaSlicer
     * writes the layer count near the top and then eighty lines of G-code, and puts the
     * estimated time, the filament used, the layer height and the thumbnail in a trailing
     * block after "; EXECUTABLE_BLOCK_END" - twelve thousand lines further down. Stopping at
     * the first line that is not a comment meant the Files screen could read the slicer's name
     * out of this app's own output and nothing else.
     *
     * The tail is read whole (it is bounded) and its comments are kept, in order, so the same
     * parsers see both ends.
     */
    private fun readHeader(
        file: File,
        maxBytes: Int = HEADER_BYTES,
        maxTailBytes: Int = TAIL_BYTES,
    ): List<String> = runCatching {
        val head = file.inputStream().bufferedReader().use { reader ->
            val lines = mutableListOf<String>()
            var read = 0
            while (read < maxBytes) {
                val line = reader.readLine() ?: break
                read += line.length + 1
                // The header ends at the first line that is not a comment, which is
                // where the print's own G-code begins.
                if (line.isNotBlank() && !line.startsWith(";")) break
                lines += line
            }
            lines
        }
        head + tailComments(file, maxTailBytes)
    }.getOrDefault(emptyList())

    /** The comments in the last stretch of a file, in the order they appear. */
    private fun tailComments(file: File, maxBytes: Int): List<String> = runCatching {
        val length = file.length()
        val from = (length - maxBytes).coerceAtLeast(0L)
        RandomAccessFile(file, "r").use { input ->
            input.seek(from)
            val bytes = ByteArray((length - from).toInt())
            input.readFully(bytes)
            String(bytes, Charsets.UTF_8)
                .lineSequence()
                // The first line of a window cut mid-file is not a whole line.
                .drop(if (from > 0) 1 else 0)
                .filter { it.startsWith(";") }
                .toList()
        }
    }.getOrDefault(emptyList())

    /** The slicer that wrote the file, from whichever of its own headers says so. */
    private fun slicer(header: List<String>): String {
        header.firstOrNull { it.startsWith("; generated by ", ignoreCase = true) }?.let { line ->
            return line.substringAfter("generated by ", "").substringBefore(" on ").trim()
        }
        header.firstOrNull { it.startsWith(";Generated with ", ignoreCase = true) }?.let { line ->
            return line.substringAfter(";Generated with ", "").trim()
        }
        header.firstOrNull { it.startsWith(";FLAVOR:", ignoreCase = true) }?.let { return "PrusaSlicer" }
        return ""
    }

    /** How long the slicer thinks the print takes. */
    private fun estimatedSeconds(header: List<String>): Int? {
        value(header, TIME)?.let { return parseDuration(it) }
        curaValue(header, "TIME")?.let { return it.trim().toIntOrNull() }
        return null
    }

    /**
     * "1h 23m 45s", "23m", "45s", "2d 3h" - the shapes slicers write.
     *
     * Read with a regular expression per unit rather than a date library: this is a
     * duration in a comment, and the units are optional and out of order as often as
     * not.
     */
    fun parseDuration(text: String): Int? {
        var total = 0
        var found = false
        for ((unit, seconds) in DURATION_UNITS) {
            val match = Regex("([0-9]+)\\s*" + unit, RegexOption.IGNORE_CASE).find(text) ?: continue
            total += (match.groupValues[1].toIntOrNull() ?: continue) * seconds
            found = true
        }
        return if (found) total else null
    }

    /** A "key = value" comment, which is what OrcaSlicer and PrusaSlicer write. */
    private fun value(header: List<String>, key: String): String? =
        header.firstOrNull { it.startsWith("; " + key, ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** The same, for a key whose spelling differs between the head and the tail. */
    private fun patterned(header: List<String>, key: Regex): String? =
        header.firstOrNull { key.containsMatchIn(it) }
            ?.substringAfter('=', "")
            ?.substringAfterLast(':', "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** A "KEY:value" comment, which is what Cura writes. */
    private fun curaValue(header: List<String>, key: String): String? =
        header.firstOrNull { it.startsWith(";" + key + ":", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * The first thumbnail, as the base64 the header carries.
     *
     * Slicers embed a small PNG so that a printer's screen can show what is about to be
     * printed, and every one of them writes it the same way: a marker with its size,
     * then the data, then an end marker.
     */
    private fun thumbnail(header: List<String>): String? {
        val begin = header.indexOfFirst { THUMBNAIL_BEGIN.containsMatchIn(it) }
        if (begin < 0) return null
        val data = StringBuilder()
        for (index in begin + 1 until header.size) {
            val line = header[index]
            if (line.contains("thumbnail end", ignoreCase = true)) break
            if (!line.startsWith(";")) break
            data.append(line.removePrefix(";").trim())
        }
        return data.toString().takeIf { it.isNotEmpty() }
    }

    /** How much of a file is read to find its header. */
    private const val HEADER_BYTES = 96 * 1024

    /** And how much of its end, where OrcaSlicer keeps the numbers worth having. */
    private const val TAIL_BYTES = 256 * 1024

    private const val TIME = "estimated printing time (normal mode)"
    private const val FILAMENT_MM = "filament used [mm]"
    private const val FILAMENT_G = "filament used [g]"
    private const val LAYER_HEIGHT = "layer_height"

    /**
     * The layer count, under both of the spellings the engines use.
     *
     * OrcaSlicer writes "; total layer number: 100" in the head and
     * "; total layers count = 100" in its trailing block, and neither is the underscore form
     * this looked for.
     */
    private val LAYER_COUNT = Regex("total layer[s]? (number|count)\\s*[:=]", RegexOption.IGNORE_CASE)

    private val THUMBNAIL_BEGIN = Regex("; thumbnail(_[A-Z]+)? begin ", RegexOption.IGNORE_CASE)

    private val DURATION_UNITS = listOf(
        "d" to 86_400,
        "h" to 3_600,
        "m" to 60,
        "s" to 1,
    )
}

/**
 * What this app has printed, kept between runs.
 *
 * klippy's own print_stats describes the print that is happening and then the one after
 * it; there is no history in it, and Moonraker's is a database this app has no business
 * carrying. A record per print, written when it ends, is enough to answer what a user
 * asks this screen: what did I print, how long did it take, and how much filament is
 * gone.
 */
internal class KlipperPrintHistory(private val file: File) {
    /** Records, newest first. */
    fun load(): List<KlipperPrintRecord> = runCatching {
        val text = file.takeIf { it.isFile }?.readText().orEmpty()
        if (text.isBlank()) return emptyList()
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { KlipperPrintRecord.from(it) }
        }
    }.getOrDefault(emptyList())

    /** Add a record, and answer with the whole list. */
    fun append(record: KlipperPrintRecord): List<KlipperPrintRecord> {
        val records = (listOf(record) + load()).take(CAPACITY)
        write(records)
        return records
    }

    fun clear(): List<KlipperPrintRecord> {
        write(emptyList())
        return emptyList()
    }

    private fun write(records: List<KlipperPrintRecord>) {
        runCatching {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            records.forEach { array.put(it.toJson()) }
            file.writeText(array.toString())
        }
    }

    private companion object {
        /** How many prints are kept: a few months on a printer that is used often. */
        const val CAPACITY = 200
    }
}

/** One finished print. */
data class KlipperPrintRecord(
    val fileName: String,
    val startedAtMillis: Long,
    val durationSeconds: Double,
    val filamentMillimetres: Double,
    /** complete, cancelled or error - klippy's own word for how it ended. */
    val outcome: String,
    val layers: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("file", fileName)
        .put("started", startedAtMillis)
        .put("duration", durationSeconds)
        .put("filament", filamentMillimetres)
        .put("outcome", outcome)
        .put("layers", layers ?: JSONObject.NULL)

    companion object {
        fun from(json: JSONObject) = KlipperPrintRecord(
            fileName = json.optString("file"),
            startedAtMillis = json.optLong("started"),
            durationSeconds = json.optDouble("duration", 0.0),
            filamentMillimetres = json.optDouble("filament", 0.0),
            outcome = json.optString("outcome"),
            layers = if (json.isNull("layers")) null else json.optInt("layers"),
        )
    }
}
