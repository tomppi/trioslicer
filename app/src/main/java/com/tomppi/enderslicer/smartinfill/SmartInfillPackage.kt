package com.tomppi.enderslicer.smartinfill

import android.content.Context
import android.net.Uri
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

private const val STL_HEADER_BYTES = 84L
private const val STL_TRIANGLE_BYTES = 50L

internal data class BinaryStlBounds(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    val centerX: Double get() = (minX + maxX) * 0.5
    val centerY: Double get() = (minY + maxY) * 0.5
}

/** One filaSim modifier volume and the Cura sparse-infill density it applies. */
data class SmartInfillModifier(
    val densityPercent: Int,
    val file: File,
)

data class SmartInfillSummary(
    val packageId: String,
    val sourceName: String,
    val baseDensityPercent: Double,
    val modifierDensitiesPercent: List<Int>,
    /** filaSim's ordinary sparse pattern used by the printable/base mesh. */
    val pattern: String,
    val mode: String,
    val perimeters: Int,
    val lineWidthMm: Double,
    val topBottomLayers: Int,
    val layerHeightMm: Double,
    val binarySolidPattern: String? = null,
)

data class SmartInfillPackage(
    val id: String,
    val directory: File,
    val sourceName: String,
    val sourceSha256: String,
    val baseDensityPercent: Double,
    /** filaSim's ordinary sparse pattern used by the printable/base mesh. */
    val pattern: String,
    val mode: String,
    val perimeters: Int,
    val lineWidthMm: Double,
    val topBottomLayers: Int,
    val layerHeightMm: Double,
    val upstreamCommit: String,
    val modifiers: List<SmartInfillModifier>,
    /** Required for binary packages; ignored for graded packages. */
    val binarySolidPattern: String? = null,
) {
    val summary: SmartInfillSummary
        get() = SmartInfillSummary(
            packageId = id,
            sourceName = sourceName,
            baseDensityPercent = baseDensityPercent,
            modifierDensitiesPercent = modifiers.map(SmartInfillModifier::densityPercent),
            pattern = pattern,
            mode = mode,
            perimeters = perimeters,
            lineWidthMm = lineWidthMm,
            topBottomLayers = topBottomLayers,
            layerHeightMm = layerHeightMm,
            binarySolidPattern = binarySolidPattern,
        )

    fun requireMatchesSource(source: File) {
        require(source.isFile && source.length() >= STL_HEADER_BYTES) {
            "The displayed STL used for Smart Infill is unavailable"
        }
        val actual = sha256(source)
        require(actual == sourceSha256) {
            "The model or its placement changed after Smart Infill was generated. Run Smart Infill again."
        }
    }

    /**
     * Stages filaSim modifier volumes in the displayed model's printer coordinates.
     * filaSim centers imported geometry around local X/Y zero and grounds it at
     * local Z zero. The analyzed STL is already placed on the build plate, so its
     * center/base translation must be restored before CuraEngine sees the volume.
     */
    fun stageModifiers(destination: File, analyzedSource: File): List<SmartInfillModifier> {
        require(destination.mkdirs() || destination.isDirectory) {
            "Unable to create the Smart Infill staging directory"
        }
        requireMatchesSource(analyzedSource)
        val triangleLimit = MeshTriangleLimits.current()
        val sourceBounds = binaryStlBounds(analyzedSource, triangleLimit)
        return modifiers.mapIndexed { index, modifier ->
            requireValidBinaryStl(modifier.file, triangleLimit)
            val target = File(destination, "smart-infill-${index + 1}-${modifier.densityPercent}pct.stl")
            translateStable(
                source = modifier.file,
                target = target,
                translationX = sourceBounds.centerX,
                translationY = sourceBounds.centerY,
                translationZ = sourceBounds.minZ,
            )
            requireValidBinaryStl(target, triangleLimit)
            SmartInfillModifier(modifier.densityPercent, target)
        }
    }

    private fun translateStable(
        source: File,
        target: File,
        translationX: Double,
        translationY: Double,
        translationZ: Double,
    ) {
        require(listOf(translationX, translationY, translationZ).all(Double::isFinite)) {
            "Smart Infill source placement is not finite"
        }
        val size = source.length()
        val modified = source.lastModified()
        RandomAccessFile(source, "r").use { input ->
            FileOutputStream(target).use { output ->
                val header = ByteArray(STL_HEADER_BYTES.toInt())
                input.readFully(header)
                output.write(header)
                val triangleCount = ByteBuffer.wrap(header, 80, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                val triangle = ByteArray(STL_TRIANGLE_BYTES.toInt())
                repeat(triangleCount) {
                    input.readFully(triangle)
                    val buffer = ByteBuffer.wrap(triangle).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(3) { vertex ->
                        val offset = 12 + vertex * 12
                        val x = buffer.getFloat(offset).toDouble() + translationX
                        val y = buffer.getFloat(offset + 4).toDouble() + translationY
                        val z = buffer.getFloat(offset + 8).toDouble() + translationZ
                        require(listOf(x, y, z).all(Double::isFinite)) {
                            "Smart Infill modifier translation produced a non-finite vertex"
                        }
                        buffer.putFloat(offset, x.toFloat())
                        buffer.putFloat(offset + 4, y.toFloat())
                        buffer.putFloat(offset + 8, z.toFloat())
                    }
                    output.write(triangle)
                }
                output.fd.sync()
            }
        }
        check(target.length() == size && source.length() == size && source.lastModified() == modified) {
            target.delete()
            "A Smart Infill modifier changed while it was being staged"
        }
    }
}

/** Atomic private storage for filaSim modifier exports. */
class SmartInfillPackageStore(private val context: Context) {
    private val root = File(context.filesDir, "smart-infill").apply { mkdirs() }
    private val packagesDirectory = File(root, "packages").apply { mkdirs() }
    private val activeFile = File(root, "active-package.txt")
    private val activeNextFile = File(root, "active-package.next")
    private val activePreviousFile = File(root, "active-package.previous")
    private val loadWarningFile = File(root, "load-warning.txt")

    fun importPackage(zipUri: Uri, metadataJson: String, sourceSha256: String): SmartInfillPackage {
        require(sourceSha256.matches(SHA_PATTERN)) { "Smart Infill source fingerprint is invalid" }
        val metadata = parseMetadata(metadataJson)
        require(metadata.sourceSha256 == null || metadata.sourceSha256 == sourceSha256) {
            "filaSim returned a source fingerprint that does not match the analyzed STL"
        }
        val id = "filasim-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val staging = File(packagesDirectory, "$id.next")
        val destination = File(packagesDirectory, id)
        staging.deleteRecursively()
        destination.deleteRecursively()
        check(staging.mkdir()) { "Unable to create the Smart Infill package workspace" }

        try {
            val modifiers = context.contentResolver.openInputStream(zipUri)?.use { input ->
                extractModifiers(input, staging, metadata.baseDensityPercent)
            } ?: error("Unable to open the filaSim modifier archive")

            val manifest = JSONObject()
                .put("version", MANIFEST_VERSION)
                .put("id", id)
                .put("sourceName", metadata.sourceName)
                .put("sourceSha256", sourceSha256)
                .put("baseDensityPercent", metadata.baseDensityPercent)
                .put("basePattern", metadata.basePattern)
                .put("mode", metadata.mode)
                .put("perimeters", metadata.perimeters)
                .put("lineWidthMm", metadata.lineWidthMm)
                .put("topBottomLayers", metadata.topBottomLayers)
                .put("layerHeightMm", metadata.layerHeightMm)
                .put("upstreamCommit", metadata.upstreamCommit)
                .put(
                    "modifiers",
                    JSONArray().apply {
                        modifiers.forEach { modifier ->
                            put(
                                JSONObject()
                                    .put("densityPercent", modifier.densityPercent)
                                    .put("fileName", modifier.file.name),
                            )
                        }
                    },
                )
            metadata.binarySolidPattern?.let { manifest.put("binarySolidPattern", it) }
            writeSynced(File(staging, MANIFEST_FILE), manifest.toString())
            check(staging.renameTo(destination)) { "Unable to publish the Smart Infill package" }

            val loaded = loadPackage(destination)
            activate(loaded)
            loadWarningFile.delete()
            cleanupOldPackages(loaded.id)
            return loaded
        } catch (error: Throwable) {
            staging.deleteRecursively()
            destination.deleteRecursively()
            throw error
        }
    }

    fun loadActive(): SmartInfillPackage? {
        recoverActivePointer()
        if (!activeFile.isFile) return null
        val id = activeFile.readText().trim()
        if (!SAFE_ID.matches(id)) {
            activeFile.delete()
            return null
        }
        // Keep the pointer on load failure so a transient error (disk read,
        // triangle-limit change, interrupted activation) does not permanently
        // deactivate Smart Infill and orphan the package directory.
        return runCatching { loadPackage(File(packagesDirectory, id)) }
            .onFailure { error ->
                val warning = error.message
                    ?.take(MAX_LOAD_WARNING_CHARS)
                    ?.takeIf(String::isNotBlank)
                if (warning != null) runCatching { writeSynced(loadWarningFile, warning) }
            }
            .getOrNull()
    }

    fun consumeLoadWarning(): String? {
        if (!loadWarningFile.isFile || loadWarningFile.length() !in 1..MAX_LOAD_WARNING_BYTES) {
            loadWarningFile.delete()
            return null
        }
        return runCatching { loadWarningFile.readText().take(MAX_LOAD_WARNING_CHARS) }
            .also { loadWarningFile.delete() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    fun clearActive() {
        activeFile.delete()
        activeNextFile.delete()
        activePreviousFile.delete()
    }

    fun clearAll() {
        clearActive()
        loadWarningFile.delete()
        packagesDirectory.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    @Synchronized
    fun activate(packageValue: SmartInfillPackage) {
        require(packageValue.directory.parentFile?.canonicalFile == packagesDirectory.canonicalFile) {
            "Smart Infill package is outside private storage"
        }
        validatePatternContract(packageValue.mode, packageValue.pattern, packageValue.binarySolidPattern)
        activeNextFile.delete()
        activePreviousFile.delete()
        writeSynced(activeNextFile, packageValue.id)
        try {
            if (activeFile.exists()) {
                check(activeFile.renameTo(activePreviousFile)) { "Unable to preserve the active Smart Infill pointer" }
            }
            try {
                check(activeNextFile.renameTo(activeFile)) { "Unable to activate the Smart Infill package" }
            } catch (error: Throwable) {
                activeFile.delete()
                if (activePreviousFile.exists()) activePreviousFile.renameTo(activeFile)
                throw error
            }
            activePreviousFile.delete()
        } finally {
            activeNextFile.delete()
            if (activeFile.exists()) activePreviousFile.delete()
        }
    }

    @Synchronized
    private fun recoverActivePointer() {
        if (activeFile.isFile) {
            activeNextFile.delete()
            activePreviousFile.delete()
            return
        }
        val candidate = when {
            activeNextFile.isFile -> activeNextFile
            activePreviousFile.isFile -> activePreviousFile
            else -> return
        }
        val id = runCatching { candidate.readText().trim() }.getOrNull()
        val valid = id != null && SAFE_ID.matches(id) && runCatching {
            loadPackage(File(packagesDirectory, id))
        }.isSuccess
        if (valid) {
            check(candidate.renameTo(activeFile) || candidate.copyTo(activeFile, overwrite = true).let { candidate.delete(); true }) {
                "Unable to recover the active Smart Infill pointer"
            }
        } else {
            candidate.delete()
        }
        activeNextFile.delete()
        activePreviousFile.delete()
    }

    private fun loadPackage(directory: File): SmartInfillPackage {
        require(directory.isDirectory && directory.parentFile?.canonicalFile == packagesDirectory.canonicalFile) {
            "Smart Infill package directory is invalid"
        }
        val manifestFile = File(directory, MANIFEST_FILE)
        require(manifestFile.isFile && manifestFile.length() in 1..MAX_MANIFEST_BYTES) {
            "Smart Infill package manifest is missing or too large"
        }
        val root = JSONObject(manifestFile.readText())
        val version = root.getInt("version")
        require(version == LEGACY_MANIFEST_VERSION || version == MANIFEST_VERSION) {
            "Unsupported Smart Infill package version"
        }
        val id = root.getString("id")
        require(id == directory.name && SAFE_ID.matches(id)) { "Smart Infill package identity is invalid" }
        val sourceSha256 = root.getString("sourceSha256")
        require(sourceSha256.matches(SHA_PATTERN)) { "Smart Infill source fingerprint is invalid" }
        val mode = requireSupportedMode(root.getString("mode"))
        val basePattern: String
        val binarySolidPattern: String?
        if (version == LEGACY_MANIFEST_VERSION) {
            require(mode != "binary") {
                "This binary Smart Infill package predates regional pattern metadata. Regenerate Smart Infill."
            }
            basePattern = requireSupportedPattern(root.getString("pattern"))
            binarySolidPattern = null
        } else {
            basePattern = requireSupportedPattern(root.getString("basePattern"))
            binarySolidPattern = root.optString("binarySolidPattern")
                .takeIf(String::isNotBlank)
                ?.let(::requireSupportedPattern)
        }
        validatePatternContract(mode, basePattern, binarySolidPattern)
        val upstreamCommit = root.getString("upstreamCommit")
        require(upstreamCommit == SmartInfillActivity.FILASIM_COMMIT) {
            "Smart Infill package was generated by an unsupported filaSim build"
        }

        val baseDensity = root.getDouble("baseDensityPercent")
        require(baseDensity.isFinite() && baseDensity in MIN_DENSITY..MAX_DENSITY) {
            "Smart Infill base density is invalid"
        }
        val modifiersJson = root.getJSONArray("modifiers")
        require(modifiersJson.length() in 1..MAX_MODIFIERS) { "Smart Infill modifier count is invalid" }
        val modifiers = List(modifiersJson.length()) { index ->
            val item = modifiersJson.getJSONObject(index)
            val density = item.getInt("densityPercent")
            val fileName = item.getString("fileName")
            require(MODIFIER_FILE.matches(fileName)) { "Smart Infill modifier name is invalid" }
            val file = File(directory, fileName)
            require(file.canonicalFile.parentFile == directory.canonicalFile) {
                "Smart Infill modifier path escapes its package"
            }
            requireValidBinaryStl(file, MeshTriangleLimits.current())
            SmartInfillModifier(density, file)
        }.sortedBy(SmartInfillModifier::densityPercent)
        validateDensities(baseDensity, modifiers.map(SmartInfillModifier::densityPercent))

        val lineWidth = root.getDouble("lineWidthMm")
        val layerHeight = root.getDouble("layerHeightMm")
        val perimeters = root.getInt("perimeters")
        val topBottom = root.getInt("topBottomLayers")
        require(lineWidth.isFinite() && lineWidth in 0.1..2.0) { "Smart Infill line width is invalid" }
        require(layerHeight.isFinite() && layerHeight in 0.02..1.5) { "Smart Infill layer height is invalid" }
        require(perimeters in 1..20) { "Smart Infill perimeter count is invalid" }
        require(topBottom in 0..50) { "Smart Infill top/bottom layer count is invalid" }

        return SmartInfillPackage(
            id = id,
            directory = directory,
            sourceName = root.getString("sourceName").take(MAX_SOURCE_NAME),
            sourceSha256 = sourceSha256,
            baseDensityPercent = baseDensity,
            pattern = basePattern,
            mode = mode,
            perimeters = perimeters,
            lineWidthMm = lineWidth,
            topBottomLayers = topBottom,
            layerHeightMm = layerHeight,
            upstreamCommit = upstreamCommit,
            modifiers = modifiers,
            binarySolidPattern = binarySolidPattern,
        )
    }

    private fun extractModifiers(
        input: InputStream,
        destination: File,
        baseDensityPercent: Double,
    ): List<SmartInfillModifier> {
        val triangleLimit = MeshTriangleLimits.current()
        val maxStlBytes = STL_HEADER_BYTES + triangleLimit.toLong() * STL_TRIANGLE_BYTES
        val seen = hashSetOf<Int>()
        val modifiers = mutableListOf<SmartInfillModifier>()
        var totalBytes = 0L
        var totalEntries = 0
        val startedNanos = System.nanoTime()

        fun checkWorkBudget() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Smart Infill extraction was cancelled")
            val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L
            require(elapsedMillis <= MAX_EXTRACTION_MILLIS) { "Smart Infill extraction exceeded its work-time limit" }
            require(totalEntries <= MAX_ARCHIVE_ENTRIES) { "Smart Infill archive contains too many entries" }
        }

        ZipInputStream(BufferedInputStream(input, BUFFER_SIZE)).use { zip ->
            while (true) {
                checkWorkBudget()
                val entry = zip.nextEntry ?: break
                totalEntries++
                if (entry.isDirectory) continue
                require('/' !in entry.name && '\\' !in entry.name) { "Smart Infill archive contains nested paths" }
                val match = MODIFIER_ARCHIVE.matchEntire(entry.name)
                    ?: error("Unexpected file in Smart Infill archive: ${entry.name}")
                val density = match.groupValues[1].toInt()
                require(seen.add(density)) { "Smart Infill archive contains duplicate $density% modifiers" }
                require(density in 1..100) { "Smart Infill modifier density is invalid" }
                require(modifiers.size < MAX_MODIFIERS) { "Smart Infill archive contains too many modifiers" }

                val target = File(destination, "modifier-${density}pct.stl")
                var written = 0L
                FileOutputStream(target).buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        written += count
                        totalBytes += count
                        require(written <= maxStlBytes) { "Smart Infill modifier exceeds the mesh triangle limit" }
                        require(totalBytes <= MAX_TOTAL_UNCOMPRESSED_BYTES) { "Smart Infill archive is too large" }
                        output.write(buffer, 0, count)
                    }
                }
                requireValidBinaryStl(target, triangleLimit)
                modifiers += SmartInfillModifier(density, target)
            }
        }
        require(modifiers.isNotEmpty()) { "filaSim did not export any modifier volumes" }
        val sorted = modifiers.sortedBy(SmartInfillModifier::densityPercent)
        validateDensities(baseDensityPercent, sorted.map(SmartInfillModifier::densityPercent))
        return sorted
    }

    private fun parseMetadata(raw: String): Metadata {
        require(raw.length in 2..MAX_METADATA_CHARS) { "Smart Infill metadata is missing or too large" }
        val root = JSONObject(raw)
        require(root.getInt("metadataVersion") == METADATA_VERSION) {
            "This filaSim export does not preserve regional pattern metadata. Regenerate Smart Infill."
        }
        val baseDensity = root.getDouble("baseDensityPercent")
        val lineWidth = root.getDouble("lineWidthMm")
        val layerHeight = root.getDouble("layerHeightMm")
        val perimeters = root.getInt("perimeters")
        val topBottom = root.getInt("topBottomLayers")
        require(baseDensity.isFinite() && baseDensity in MIN_DENSITY..MAX_DENSITY) {
            "filaSim returned an invalid base infill density"
        }
        require(lineWidth.isFinite() && lineWidth in 0.1..2.0) { "filaSim returned an invalid line width" }
        require(layerHeight.isFinite() && layerHeight in 0.02..1.5) { "filaSim returned an invalid layer height" }
        require(perimeters in 1..20) { "filaSim returned an invalid perimeter count" }
        require(topBottom in 0..50) { "filaSim returned an invalid shell count" }
        val upstreamCommit = root.getString("upstreamCommit")
        require(upstreamCommit == SmartInfillActivity.FILASIM_COMMIT) {
            "filaSim export came from an unsupported source revision"
        }
        val sourceFingerprint = root.optString("sourceSha256")
            .takeIf(String::isNotBlank)
        require(sourceFingerprint == null || sourceFingerprint.matches(SHA_PATTERN)) {
            "filaSim returned an invalid source fingerprint"
        }
        val mode = requireSupportedMode(root.getString("mode"))
        val basePattern = requireSupportedPattern(root.getString("basePattern"))
        val binarySolidPattern = root.optString("binarySolidPattern")
            .takeIf(String::isNotBlank)
            ?.let(::requireSupportedPattern)
        require(
            root.optString(
                "gradedFullDensityPattern",
                SmartInfillCuraContract.GRADED_FULL_DENSITY_PATTERN,
            ).trim().lowercase() == SmartInfillCuraContract.GRADED_FULL_DENSITY_PATTERN,
        ) {
            "filaSim returned an unsupported graded full-density pattern contract"
        }
        validatePatternContract(mode, basePattern, binarySolidPattern)
        return Metadata(
            sourceName = root.optString("sourceName", "model.stl").take(MAX_SOURCE_NAME),
            sourceSha256 = sourceFingerprint,
            baseDensityPercent = baseDensity,
            basePattern = basePattern,
            binarySolidPattern = binarySolidPattern,
            mode = mode,
            perimeters = perimeters,
            lineWidthMm = lineWidth,
            topBottomLayers = topBottom,
            layerHeightMm = layerHeight,
            upstreamCommit = upstreamCommit,
        )
    }

    private fun requireSupportedMode(raw: String): String {
        val normalized = raw.trim().lowercase()
        require(normalized in SUPPORTED_MODES) {
            "filaSim modifier export must use graded or binary optimization, not '$raw'"
        }
        return normalized
    }

    private fun requireSupportedPattern(raw: String): String {
        val normalized = raw.trim().lowercase()
        require(normalized in SUPPORTED_PATTERNS) {
            "filaSim returned an infill pattern that TrioSlicer cannot reproduce: '$raw'"
        }
        return normalized
    }

    private fun validatePatternContract(mode: String, basePattern: String, binarySolidPattern: String?) {
        requireSupportedPattern(basePattern)
        when (mode) {
            "binary" -> require(binarySolidPattern != null) {
                "Binary Smart Infill is missing its solid-region pattern. Regenerate Smart Infill."
            }
            "graded" -> require(binarySolidPattern == null) {
                "Graded Smart Infill must not contain a binary solid pattern"
            }
            else -> error("Unsupported Smart Infill mode: $mode")
        }
        binarySolidPattern?.let(::requireSupportedPattern)
    }

    private fun cleanupOldPackages(activeId: String) {
        packagesDirectory.listFiles().orEmpty()
            .filter { it.isDirectory && it.name != activeId && !it.name.endsWith(".next") }
            .sortedByDescending(File::lastModified)
            .drop(MAX_RETAINED_PACKAGES - 1)
            .forEach(File::deleteRecursively)
        packagesDirectory.listFiles().orEmpty()
            .filter { it.name.endsWith(".next") }
            .forEach(File::deleteRecursively)
    }

    private fun writeSynced(file: File, text: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private data class Metadata(
        val sourceName: String,
        val sourceSha256: String?,
        val baseDensityPercent: Double,
        val basePattern: String,
        val binarySolidPattern: String?,
        val mode: String,
        val perimeters: Int,
        val lineWidthMm: Double,
        val topBottomLayers: Int,
        val layerHeightMm: Double,
        val upstreamCommit: String,
    )

    companion object {
        private const val MANIFEST_VERSION = 2
        private const val LEGACY_MANIFEST_VERSION = 1
        private const val METADATA_VERSION = 2
        private const val MANIFEST_FILE = "manifest.json"
        private const val MAX_MANIFEST_BYTES = 256L * 1024L
        private const val MAX_METADATA_CHARS = 64 * 1024
        private const val MAX_SOURCE_NAME = 512
        private const val MAX_MODIFIERS = 16
        private const val MAX_ARCHIVE_ENTRIES = 2_048
        private const val MAX_EXTRACTION_MILLIS = 30_000L
        private const val MAX_RETAINED_PACKAGES = 4
        private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 768L * 1024L * 1024L
        private const val MAX_LOAD_WARNING_BYTES = 16L * 1024L
        private const val MAX_LOAD_WARNING_CHARS = 4 * 1024
        private const val BUFFER_SIZE = 128 * 1024
        private const val MIN_DENSITY = 1.0
        private const val MAX_DENSITY = 100.0
        private val SUPPORTED_MODES = setOf("graded", "binary")
        private val SUPPORTED_PATTERNS = setOf("cubic", "gyroid", "grid", "rectilinear", "zig-zag", "zigzag", "concentric")
        private val MODIFIER_ARCHIVE = Regex("modifier_(\\d{1,3})pct\\.stl", RegexOption.IGNORE_CASE)
        private val MODIFIER_FILE = Regex("modifier-\\d{1,3}pct\\.stl", RegexOption.IGNORE_CASE)
        private val SAFE_ID = Regex("filasim-[A-Za-z0-9-]+")
        private val SHA_PATTERN = Regex("[0-9a-f]{64}")
    }
}

internal fun binaryStlBounds(file: File, maxTriangles: Int): BinaryStlBounds {
    requireValidBinaryStl(file, maxTriangles)
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var minZ = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    var maxZ = Double.NEGATIVE_INFINITY
    RandomAccessFile(file, "r").use { input ->
        input.seek(STL_HEADER_BYTES)
        val triangle = ByteArray(STL_TRIANGLE_BYTES.toInt())
        val triangleCount = ((file.length() - STL_HEADER_BYTES) / STL_TRIANGLE_BYTES).toInt()
        repeat(triangleCount) {
            input.readFully(triangle)
            val buffer = ByteBuffer.wrap(triangle).order(ByteOrder.LITTLE_ENDIAN)
            repeat(3) { vertex ->
                val offset = 12 + vertex * 12
                val x = buffer.getFloat(offset).toDouble()
                val y = buffer.getFloat(offset + 4).toDouble()
                val z = buffer.getFloat(offset + 8).toDouble()
                require(listOf(x, y, z).all(Double::isFinite)) {
                    "Smart Infill STL contains a non-finite vertex"
                }
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                minZ = minOf(minZ, z)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
                maxZ = maxOf(maxZ, z)
            }
        }
    }
    return BinaryStlBounds(minX, minY, minZ, maxX, maxY, maxZ)
}

internal fun requireValidBinaryStl(file: File, maxTriangles: Int) {
    require(file.isFile && file.length() >= STL_HEADER_BYTES) { "Smart Infill modifier STL is missing or empty" }
    RandomAccessFile(file, "r").use { input ->
        input.seek(80L)
        val countBytes = ByteArray(4)
        input.readFully(countBytes)
        val triangleCount = ByteBuffer.wrap(countBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0xffffffffL
        require(triangleCount in 1..maxTriangles.toLong()) {
            "Smart Infill modifier triangle count is outside the configured limit"
        }
        require(file.length() == STL_HEADER_BYTES + triangleCount * STL_TRIANGLE_BYTES) {
            "Smart Infill modifier is not a structurally valid binary STL"
        }
    }
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun validateDensities(baseDensityPercent: Double, modifierDensities: List<Int>) {
    require(modifierDensities == modifierDensities.sorted() && modifierDensities.distinct().size == modifierDensities.size) {
        "Smart Infill modifier densities must be unique and increasing"
    }
    require(modifierDensities.all { it.toDouble() > baseDensityPercent && it <= 100 }) {
        "Every Smart Infill modifier must be denser than the base infill"
    }
}
