package com.tomppi.enderslicer.texturizer

import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure, JVM-testable parts of the BumpMesh studio engine.
 *
 * Everything the WebView/Android layer does that can be reasoned about without a browser lives here: the settings and
 * mask JSON the vendored page reads, the .bumpmesh ZIP envelope, the triangle-index remap that makes an app-painted
 * mask survive BumpMesh's loader cleanup, the progress-phase naming, and the safety guard rails around the mesh
 * triangle limit. Keeping these out of the WebView class lets the unit tests pin the contracts that are easy to break.
 */
internal object BumpMeshDriverCommand {
    /**
     * The JSON the injected driver turns into page control events. The keys are the driver's contract; the driver
     * dispatches them on #mapping-mode (change), #scale-u-val/#scale-v-val (change), #amplitude-val (change),
     * #texture-smoothing-val (change), #refine-length-val (change) and #max-triangles (input) before export.
     */
    fun settingsJson(
        mappingMode: Int,
        textureAssetPath: String,
        tileMm: Float,
        heightMm: Float,
        smoothing: Float,
        refineMm: Float,
        maxTriangles: Int,
    ): String = buildString {
        append('{')
        append("\"mappingMode\":").append(mappingMode)
        append(",\"textureAsset\":").append(jsString(textureAssetPath))
        append(",\"tileMm\":").append(number(tileMm))
        append(",\"heightMm\":").append(number(heightMm))
        append(",\"smoothing\":").append(number(smoothing))
        append(",\"refineMm\":").append(number(refineMm))
        append(",\"maxTriangles\":").append(maxTriangles)
        append('}')
    }

    private fun number(value: Float): String =
        String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.').let { if (it.isEmpty() || it == "-") "0" else it }

    fun jsString(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format(Locale.US, "\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}

/**
 * The .bumpmesh project envelope the page's own "Import project" input reads.
 *
 * A project is a ZIP with settings.json, model.stl (original pose) and an optional mask.json
 * ({"selectionMode":true,"excluded":[trimmed base-geometry triangle indices]}). Building it natively and feeding it
 * through the page's import is the only mask route that needs no change to the vendored files.
 */
internal object BumpMeshProjectFiles {
    const val SETTINGS_ENTRY = "settings.json"
    const val MODEL_ENTRY = "model.stl"
    const val MASK_ENTRY = "mask.json"

    /** The subset of the page's settings snapshot that matters for a mask apply; controls are driven again after import. */
    fun settingsJson(
        mappingMode: Int,
        tileMm: Float,
        heightMm: Float,
        smoothing: Float,
        refineMm: Float,
        maxTriangles: Int,
    ): String = buildString {
        append('{')
        append("\"mappingMode\":").append(mappingMode)
        append(",\"scaleUnit\":\"mm\"")
        append(",\"scaleU\":").append(number(tileMm))
        append(",\"scaleV\":").append(number(tileMm))
        append(",\"amplitude\":").append(number(heightMm))
        append(",\"textureHeight\":").append(number(heightMm))
        append(",\"invertDisplacement\":false")
        append(",\"textureSmoothing\":").append(number(smoothing))
        append(",\"refineLength\":").append(number(refineMm))
        append(",\"maxTriangles\":").append(maxTriangles)
        // The soft edge the spike used for include-only masks; smoothed over 1 mm with the ease-in ramp.
        append(",\"boundaryFalloff\":1.0")
        append(",\"boundaryFalloffCurve\":\"ease\"")
        append(",\"lockScale\":false")
        append(",\"preserveUntextured\":true")
        append(",\"smoothBottom\":true")
        append(",\"harvestFlatFaces\":true")
        append(",\"bottomAngleLimit\":0")
        append(",\"topAngleLimit\":0")
        append('}')
    }

    fun maskJson(mask: BooleanArray): String = buildString {
        append("{\"selectionMode\":true,\"excluded\":[")
        var first = true
        mask.forEachIndexed { index, include ->
            if (include) {
                if (!first) append(',')
                append(index)
                first = false
            }
        }
        append("]}")
    }

    fun buildZip(destination: File, modelFile: File, settingsJson: String, maskJson: String?) {
        destination.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(destination.outputStream(), 64 * 1024)).use { zip ->
            zip.putNextEntry(ZipEntry(SETTINGS_ENTRY))
            zip.write(settingsJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(MODEL_ENTRY))
            modelFile.inputStream().use { input -> input.copyTo(zip, 64 * 1024) }
            zip.closeEntry()
            if (maskJson != null) {
                zip.putNextEntry(ZipEntry(MASK_ENTRY))
                zip.write(maskJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun number(value: Float): String =
        String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.').let { if (it.isEmpty() || it == "-") "0" else it }
}

/**
 * Re-maps an app painting mask through BumpMesh's loader cleanup.
 *
 * stlLoader.js runs validateAndCleanGeometry before anything else: it drops triangles with a non-finite coordinate and
 * triangles whose area² is below 1e-24, and compacts the non-indexed position array. A mask written against the app's
 * STL would therefore start painting the wrong faces from the first dropped triangle onward (3DBenchy loses 11), so the
 * studio rebuilds the same filter here and returns a mask indexed against the cleaned geometry the page will use.
 */
internal object BumpMeshMaskRemap {
    private const val STL_HEADER_BYTES = 84L
    private const val STL_TRIANGLE_BYTES = 50L
    private const val AREA_SQUARED_EPSILON = 1e-24
    private const val CHUNK_TRIANGLES = 50_000

    data class Result(val mask: BooleanArray, val droppedTriangles: Int)

    fun remap(sourceStl: File, sourceTriangles: Int, includeMask: BooleanArray): Result {
        if (includeMask.size != sourceTriangles) {
            throw IllegalArgumentException(
                "The paint mask names " + includeMask.size + " triangles but the source mesh has " +
                    sourceTriangles + "; the mask belongs to another mesh.",
            )
        }
        val scan = scan(sourceStl, sourceTriangles, includeMask)
        return Result(mask = scan.mask!!, droppedTriangles = scan.droppedTriangles)
    }

    /** The triangle count the page will report after its own validateAndCleanGeometry pass. */
    fun cleanedTriangleCount(sourceStl: File, sourceTriangles: Int): Int =
        scan(sourceStl, sourceTriangles, null).keptTriangles

    private data class Scan(val mask: BooleanArray?, val keptTriangles: Int, val droppedTriangles: Int)

    private fun scan(sourceStl: File, sourceTriangles: Int, includeMask: BooleanArray?): Scan {
        if (!sourceStl.isFile) throw IllegalArgumentException("The textured source STL is missing: " + sourceStl.name)
        if (sourceTriangles <= 0) throw IllegalArgumentException("The source mesh has no triangles")
        val length = sourceStl.length()
        if (length < STL_HEADER_BYTES) throw IllegalArgumentException("The source STL is not a binary STL")
        RandomAccessFile(sourceStl, "r").use { input ->
            input.seek(80L)
            val countBytes = ByteArray(4)
            input.readFully(countBytes)
            val fileTriangles = ByteBuffer.wrap(countBytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            if (fileTriangles != sourceTriangles.toLong()) {
                throw IllegalArgumentException(
                    "The source STL holds " + fileTriangles + " triangles but " + sourceTriangles +
                        " were expected; refusing to map a mask onto a different mesh.",
                )
            }
            if (length != STL_HEADER_BYTES + sourceTriangles * STL_TRIANGLE_BYTES) {
                throw IllegalArgumentException("The source STL is truncated or not a binary STL")
            }
            val kept = if (includeMask != null) BooleanArray(sourceTriangles) else null
            var keptCount = 0
            var dropped = 0
            val buffer = ByteBuffer.allocate(CHUNK_TRIANGLES * STL_TRIANGLE_BYTES.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            var triangle = 0
            while (triangle < sourceTriangles) {
                val chunk = minOf(CHUNK_TRIANGLES, sourceTriangles - triangle)
                val bytes = chunk * STL_TRIANGLE_BYTES.toInt()
                buffer.clear()
                buffer.limit(bytes)
                input.readFully(buffer.array(), 0, bytes)
                buffer.rewind()
                for (index in 0 until chunk) {
                    buffer.position(index * STL_TRIANGLE_BYTES.toInt() + 12) // skip the normal
                    val ax = buffer.float
                    val ay = buffer.float
                    val az = buffer.float
                    val bx = buffer.float
                    val by = buffer.float
                    val bz = buffer.float
                    val cx = buffer.float
                    val cy = buffer.float
                    val cz = buffer.float
                    val source = triangle + index
                    if (!isFinite(ax, ay, az, bx, by, bz, cx, cy, cz)) {
                        dropped++
                        continue
                    }
                    val ux = bx - ax
                    val uy = by - ay
                    val uz = bz - az
                    val vx = cx - ax
                    val vy = cy - ay
                    val vz = cz - az
                    val qx = uy * vz - uz * vy
                    val qy = uz * vx - ux * vz
                    val qz = ux * vy - uy * vx
                    val areaSquared = qx * qx + qy * qy + qz * qz
                    if (!areaSquared.isFinite() || areaSquared < AREA_SQUARED_EPSILON) {
                        dropped++
                        continue
                    }
                    if (kept != null && includeMask!![source]) kept[keptCount] = true
                    keptCount++
                }
                triangle += chunk
            }
            return Scan(mask = kept?.copyOf(keptCount), keptTriangles = keptCount, droppedTriangles = dropped)
        }
    }

    private fun isFinite(vararg values: Float): Boolean = values.all { it.isFinite() }
}

/** The public progress phases; the order is the order an apply moves through them. */
internal object BumpMeshProgress {
    const val LOADING_ENGINE = "loading engine"
    const val IMPORTING = "importing"
    const val SUBDIVIDING = "subdividing"
    const val DISPLACING = "displacing"
    const val COLLECTING = "collecting"
    const val EXPORTING = "exporting"
    const val RECEIVING_MESH = "receiving mesh"

    val phases: List<String> = listOf(
        LOADING_ENGINE,
        IMPORTING,
        SUBDIVIDING,
        DISPLACING,
        COLLECTING,
        EXPORTING,
        RECEIVING_MESH,
    )

    /** The page's 0..1 export-progress fraction → the public phase and the overall fraction the UI shows. */
    fun phaseForPageFraction(pageFraction: Float): Pair<String, Float> {
        val fraction = pageFraction.coerceIn(0f, 1f)
        return when {
            fraction < 0.38f -> SUBDIVIDING to (0.20f + (fraction / 0.38f) * 0.35f)
            fraction < 0.71f -> DISPLACING to (0.55f + ((fraction - 0.38f) / 0.33f) * 0.23f)
            fraction < 0.96f -> COLLECTING to (0.78f + ((fraction - 0.71f) / 0.25f) * 0.12f)
            else -> EXPORTING to (0.90f + ((fraction - 0.96f) / 0.04f) * 0.06f)
        }
    }

    /** The chunked bridge stream → the public "receiving mesh" fraction (0.96..1.0 overall). */
    fun receivingFraction(writtenBytes: Long, expectedBytes: Long): Float {
        if (expectedBytes <= 0L) return 0.96f
        val fraction = (writtenBytes.toDouble() / expectedBytes.toDouble()).coerceIn(0.0, 1.0)
        return (0.96 + 0.04 * fraction).toFloat()
    }
}

/**
 * The safety rails around MeshTriangleLimits.
 *
 * The engine must refuse by name, never silently decimate: the app's limit is one setting consumed by the BumpMesh
 * bridge, the STL importer and the slicer. The spike measured the aggressive-tier renderer peak (5.13 GiB at a 4.79M
 * triangle result / 0.1 mm refine), so an Extreme apply is refused when it asks the engine for a finer subdivision
 * than the measured-safe one unless the cap itself stays in the detailed tier.
 */
internal object BumpMeshStudioLimits {
    const val FAST_MAX_TRIANGLES = 800_000
    const val DETAILED_MAX_TRIANGLES = 2_000_000
    const val EXTREME_MAX_TRIANGLES = MeshTriangleLimits.MAX_TRIANGLES
    /** The finest resolution the spike measured with a renderer peak without crossing into a new risk band. */
    const val EXTREME_MIN_REFINE_MM = 0.12f

    fun tierFor(maxTriangles: Int): String = when {
        maxTriangles <= FAST_MAX_TRIANGLES -> "fast"
        maxTriangles <= DETAILED_MAX_TRIANGLES -> "detailed"
        else -> "extreme"
    }

    fun memoryWarningFor(maxTriangles: Int): String? = when {
        maxTriangles <= DETAILED_MAX_TRIANGLES -> null
        else ->
            "This is an Extreme texture: the studio's hidden WebView renderer peaked at 5.13 GiB for a 4.79M-triangle " +
                "result on the dev phone. Keep the phone on power and expect minutes; Android may kill the app on an " +
                "8 GB device if memory pressure is high."
    }

    /** Returns a refusal sentence, or null when the request may run. */
    fun refusalFor(
        maxTriangles: Int,
        refineMm: Float,
        currentLimit: Int,
    ): String? {
        val limit = MeshTriangleLimits.sanitize(currentLimit)
        if (maxTriangles < 1) return "The output triangle cap must be at least 1"
        if (maxTriangles > EXTREME_MAX_TRIANGLES) {
            return "This texture asks for " + MeshTriangleLimits.formatCount(maxTriangles) +
                " triangles; the app's mesh limit is at most " + MeshTriangleLimits.formatCount(EXTREME_MAX_TRIANGLES)
        }
        if (maxTriangles > limit) {
            return "The app's mesh triangle limit is " + MeshTriangleLimits.formatCount(limit) + ", but this apply asks for " +
                MeshTriangleLimits.formatCount(maxTriangles) + " triangles. Raise Mesh triangle limit to at least " +
                MeshTriangleLimits.formatCount(maxTriangles) + " in the Plate menu first."
        }
        if (maxTriangles > DETAILED_MAX_TRIANGLES && refineMm < EXTREME_MIN_REFINE_MM) {
            return "An Extreme apply above " + MeshTriangleLimits.formatCount(DETAILED_MAX_TRIANGLES) +
                " triangles needs a resolution of " + EXTREME_MIN_REFINE_MM +
                " mm or coarser; finer subdivision is beyond the studio's measured memory ceiling (5.13 GiB renderer peak)."
        }
        return null
    }
}

/**
 * Turns the page's console-styled repair log into the sentence the studio shows.
 *
 * The page logs colour directives with the message ("%c[stlTexturizer] mesh repair ... color:#0a0;font-weight:bold");
 * only the diagnostic belongs in ApplyResult.repairWarnings.
 */
internal fun cleanBumpMeshWarning(message: String): String = message
    .replace("%c[stlTexturizer] ", "")
    .replace(Regex("\\s*color:#[0-9a-fA-F]+(;[^ ]*)*\\s*$"), "")
    .trim()

/**
 * Human-readable names for the bundled textures.
 *
 * The current BumpMesh bundle renamed a few files relative to the names its preset grid shows (stripes.png is
 * "Stripes 1", wood.jpg is "Wood 1", ...). Those aliases keep the native catalogue identical to the page's own preset
 * names for the shipped 24; anything added later falls back to a prettified file name.
 */
internal fun textureDisplayName(fileName: String): String {
    val normalized = fileName.trim().lowercase()
    KNOWN_TEXTURE_NAMES[normalized]?.let { return it }
    val base = fileName.substringBeforeLast('.', fileName)
    val words = base
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace(Regex("[_\\-.]+"), " ")
        .trim()
        .split(' ')
        .filter { it.isNotBlank() }
    if (words.isEmpty()) return fileName
    return words.joinToString(" ") { word ->
        when {
            word.all { it.isDigit() } -> word
            word.length <= 2 && word.all { it.isUpperCase() } -> word
            else -> word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}

private val KNOWN_TEXTURE_NAMES: Map<String, String> = mapOf(
    "basket.png" to "Basket",
    "brick.png" to "Brick",
    "bubble.png" to "Bubble",
    "carbonfiber.jpg" to "Carbon Fiber",
    "crystal.png" to "Crystal",
    "dots.png" to "Dots",
    "grid.png" to "Grid",
    "gripsurface.jpg" to "Grip Surface",
    "hexagon.jpg" to "Hexagon",
    "hexagons.jpg" to "Hexagons",
    "isogrid.png" to "Isogrid",
    "knitting.png" to "Knitting",
    "knurling.jpg" to "Knurling",
    "leather2.png" to "Leather 2",
    "noise.jpg" to "Noise",
    "stripes.png" to "Stripes 1",
    "stripes_02.png" to "Stripes 2",
    "voronoi.jpg" to "Voronoi",
    "weave.png" to "Weave 1",
    "weave_02.jpg" to "Weave 2",
    "weave_03.jpg" to "Weave 3",
    "wood.jpg" to "Wood 1",
    "woodgrain_02.jpg" to "Wood 2",
    "woodgrain_03.jpg" to "Wood 3",
)

/** How a studio apply ends; used by the cancellation plumbing the bridge and the cancel() command share. */
internal class BumpMeshCallSlot {
    private val lock = Any()
    private var running = false
    private var cancelRequested = false

    fun tryBegin(): Boolean = synchronized(lock) {
        if (running) return false
        running = true
        cancelRequested = false
        true
    }

    fun finish() = synchronized(lock) {
        running = false
        cancelRequested = false
    }

    fun requestCancel(): Boolean = synchronized(lock) {
        if (!running) return false
        cancelRequested = true
        true
    }

    val isCancelRequested: Boolean get() = synchronized(lock) { cancelRequested }
    val isRunning: Boolean get() = synchronized(lock) { running }
}

/**
 * A minimal JSON reader (objects, arrays, strings with escapes, numbers, booleans, null).
 *
 * The texture library manifest is pinned JSON produced by the asset pipeline; reading it here without org.json keeps the
 * manifest parsing testable in plain JVM unit tests, where android.util.JSONObject is not available.
 */
internal object BumpMeshJson {
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.value()
        parser.skipWhitespace()
        if (parser.position != text.length) throw IllegalArgumentException("trailing JSON content at " + parser.position)
        return value
    }

    private class Parser(private val source: String) {
        var position = 0

        fun skipWhitespace() {
            while (position < source.length && source[position].isWhitespace()) position++
        }

        fun value(): Any? {
            skipWhitespace()
            if (position >= source.length) throw IllegalArgumentException("unexpected end of JSON")
            return when (source[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { position++; return result }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = value()
                skipWhitespace()
                when (peek()) {
                    ',' -> { position++; continue }
                    '}' -> { position++; return result }
                    else -> throw IllegalArgumentException("expected , or } at " + position)
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { position++; return result }
            while (true) {
                result.add(value())
                skipWhitespace()
                when (peek()) {
                    ',' -> { position++; continue }
                    ']' -> { position++; return result }
                    else -> throw IllegalArgumentException("expected , or ] at " + position)
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (position >= source.length) throw IllegalArgumentException("unterminated JSON string")
                val character = source[position++]
                when (character) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (position >= source.length) throw IllegalArgumentException("unterminated JSON escape")
                        when (val escaped = source[position++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (position + 4 > source.length) throw IllegalArgumentException("short unicode escape")
                                val hex = source.substring(position, position + 4)
                                position += 4
                                builder.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("bad JSON escape \\" + escaped)
                        }
                    }
                    else -> builder.append(character)
                }
            }
        }

        private fun readNumber(): Double {
            val start = position
            if (peek() == '-') position++
            while (position < source.length && source[position].isDigit()) position++
            if (peek() == '.') {
                position++
                while (position < source.length && source[position].isDigit()) position++
            }
            if (peek() == 'e' || peek() == 'E') {
                position++
                if (peek() == '+' || peek() == '-') position++
                while (position < source.length && source[position].isDigit()) position++
            }
            return source.substring(start, position).toDouble()
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!source.startsWith(literal, position)) throw IllegalArgumentException("bad JSON literal at " + position)
            position += literal.length
            return value
        }

        private fun peek(): Char = if (position < source.length) source[position] else '\u0000'

        private fun expect(character: Char) {
            if (peek() != character) throw IllegalArgumentException("expected " + character + " at " + position)
            position++
        }
    }
}

/**
 * The pinned 101-texture BumpMesh library (assets/bumpmesh/library/manifest.json).
 *
 * The asset lane stages the catalogue at manifest["textures"]: each entry has name, file ("textures/armor.png"),
 * thumb ("textures/thumbs/armor.webp"), category, default_scale, credit and source. The canonical asset path the engine
 * hands the page is "bumpmesh/library/" + file; the webp thumb is gallery-only and must never reach
 * #texture-file-input. When the manifest is absent (a build that has not staged the library yet) the studio falls back to
 * the pre-1.9.14 vendored preset folder, so a broken bundle still gives a usable studio.
 */
internal object BumpMeshLibraryManifest {
    const val LIBRARY_ROOT = "bumpmesh/library"
    const val MANIFEST_ASSET = LIBRARY_ROOT + "/manifest.json"
    const val LIBRARY_TEXTURE_PREFIX = LIBRARY_ROOT + "/textures/"
    const val LEGACY_TEXTURE_PREFIX = "bumpmesh/textures/"
    private val DISPLACEMENT_EXTENSIONS = setOf("png", "jpg", "jpeg")
    private val LISTABLE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    data class Entry(
        val assetPath: String,
        val displayName: String,
        val category: String?,
        val defaultScale: Float?,
        val thumbnailAssetPath: String?,
    )

    /** Manifest JSON -> the catalogue in the same display-name order the studio gallery uses. */
    fun parse(manifestText: String): List<Entry> {
        val root = runCatching { BumpMeshJson.parse(manifestText) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val categories = LinkedHashMap<String, String>()
        val categoryArray = root["categories"] as? List<*> ?: emptyList<Any?>()
        categoryArray.forEach { raw ->
            val category = raw as? Map<*, *> ?: return@forEach
            val id = category["id"] as? String ?: return@forEach
            val label = category["label"] as? String
            if (label != null) categories[id] = label
        }
        val textures = root["textures"] as? List<*> ?: return emptyList()
        val entries = ArrayList<Entry>()
        textures.forEach { raw ->
            val entry = raw as? Map<*, *> ?: return@forEach
            val name = entry["name"] as? String
            val file = entry["file"] as? String
            if (name.isNullOrBlank() || file.isNullOrBlank()) return@forEach
            val categoryId = entry["category"] as? String
            val thumb = entry["thumb"] as? String
            val defaultScale = (entry["default_scale"] as? Number)?.toFloat()
            entries.add(
                Entry(
                    assetPath = canonicalPath(file),
                    displayName = name,
                    category = categoryId?.let { categories[it] ?: it },
                    defaultScale = defaultScale,
                    thumbnailAssetPath = thumb?.takeIf { it.isNotBlank() }?.let { canonicalPath(it) },
                ),
            )
        }
        return entries.sortedBy { it.displayName.lowercase() }
    }

    /** The pre-1.9.14 vendored preset folder as a fallback catalogue. */
    fun legacy(fileNames: List<String>): List<Entry> = fileNames
        .filter { it.substringAfterLast('.', "").lowercase() in LISTABLE_EXTENSIONS }
        .map { fileName ->
            Entry(
                assetPath = LEGACY_TEXTURE_PREFIX + fileName,
                displayName = textureDisplayName(fileName),
                category = null,
                defaultScale = null,
                thumbnailAssetPath = null,
            )
        }
        .sortedBy { it.displayName.lowercase() }

    fun canonicalPath(relative: String): String = LIBRARY_ROOT + "/" + relative.trimStart('/')

    fun isLibraryTexturePath(assetPath: String): Boolean = assetPath.startsWith(LIBRARY_TEXTURE_PREFIX)

    fun isLegacyTexturePath(assetPath: String): Boolean = assetPath.startsWith(LEGACY_TEXTURE_PREFIX)

    /**
     * Why [assetPath] cannot be used as a displacement map, or null when it can.
     *
     * Accepted: the canonical library path (bumpmesh/library/textures/<file>) and the legacy vendored folder
     * (bumpmesh/textures/<file>). A .webp is the gallery thumbnail, never the map. Missing files are refused by name.
     */
    fun refusalFor(assetPath: String, exists: (String) -> Boolean): String? {
        val path = assetPath.trimStart('/')
        val accepted = isLibraryTexturePath(path) || isLegacyTexturePath(path)
        if (!accepted) {
            return "The selected texture is not one of the bundled BumpMesh textures: " + assetPath
        }
        val extension = path.substringAfterLast('.', "").lowercase()
        if (extension !in DISPLACEMENT_EXTENSIONS) {
            return "That file is a gallery thumbnail, not a displacement map: " + assetPath +
                ". Choose the texture itself (.png or .jpg)."
        }
        if (!exists(path)) {
            return "The selected texture file is missing from the app: " + assetPath
        }
        return null
    }
}