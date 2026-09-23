package com.tomppi.enderslicer.smartinfill

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Auditable result captured from filaSim's build-simulation workspace.
 *
 * This is deliberately a build-process thermo-mechanical report, not a general
 * heat-transfer result. The solver uses sequential voxel activation and an
 * inherent-strain/eigenstrain model to estimate bonded/released deformation
 * and bed reactions. It does not solve transient heat conduction, interlayer
 * welding, creep, or an absolute bed-adhesion failure threshold.
 */
internal data class ThermalFeaReport(
    val schemaVersion: Int,
    val precisionSource: String,
    val sourceName: String,
    val sourceSha256: String,
    val upstreamCommit: String,
    val analysisFingerprintSha256: String,
    val generatedAtEpochMillis: Long,
    /** filaSim layout: row-major 3×3 linear matrix followed by tx, ty, tz. */
    val modelTransform3x4: List<Double>,
    val materialName: String,
    val shrinkXyPercent: Double,
    val shrinkZPercent: Double,
    val yieldStrengthMpa: Double?,
    val lockingTemperatureC: Double?,
    val bedTemperatureC: Double?,
    val chamberTemperatureC: Double?,
    val finalTemperatureC: Double?,
    val thermalDecayMm: Double?,
    val requestedState: String,
    val densityAware: Boolean,
    val voxelSizeMm: Double,
    val gridNx: Int,
    val gridNy: Int,
    val gridNz: Int,
    val activeCells: Int,
    val buildLayers: Int,
    val bondedWarpMm: Double,
    val releasedWarpMm: Double,
    val peakLiftMpa: Double,
    val peakShearMpa: Double,
    val solverSeconds: Double,
    val meanIterationsPerLayer: Double,
    val maxIterationsPerLayer: Int,
) {
    fun toCanonicalJson(): String = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("analysisKind", ANALYSIS_KIND)
        .put("solverModel", SOLVER_MODEL)
        .put("precisionSource", precisionSource)
        .put("sourceName", sourceName)
        .put("sourceSha256", sourceSha256)
        .put("upstreamCommit", upstreamCommit)
        .put("analysisFingerprintSha256", analysisFingerprintSha256)
        .put("generatedAtEpochMillis", generatedAtEpochMillis)
        .put(
            "pose",
            JSONObject().put("transform3x4", modelTransform3x4.toJsonArray()),
        )
        .put(
            "material",
            JSONObject()
                .put("name", materialName)
                .put("shrinkXyPercent", shrinkXyPercent)
                .put("shrinkZPercent", shrinkZPercent)
                .putNullable("yieldStrengthMpa", yieldStrengthMpa)
                .putNullable("lockingTemperatureC", lockingTemperatureC),
        )
        .put(
            "process",
            JSONObject()
                .putNullable("bedTemperatureC", bedTemperatureC)
                .putNullable("chamberTemperatureC", chamberTemperatureC)
                .putNullable("finalTemperatureC", finalTemperatureC)
                .putNullable("thermalDecayMm", thermalDecayMm)
                .put("requestedState", requestedState)
                .put("densityAware", densityAware),
        )
        .put(
            "mesh",
            JSONObject()
                .put("voxelSizeMm", voxelSizeMm)
                .put("nx", gridNx)
                .put("ny", gridNy)
                .put("nz", gridNz)
                .put("activeCells", activeCells)
                .put("buildLayers", buildLayers),
        )
        .put(
            "results",
            JSONObject()
                .put("bondedWarpMm", bondedWarpMm)
                .put("releasedWarpMm", releasedWarpMm)
                .put("peakLiftMpa", peakLiftMpa)
                .put("peakShearMpa", peakShearMpa)
                .put("solverSeconds", solverSeconds)
                .put("meanIterationsPerLayer", meanIterationsPerLayer)
                .put("maxIterationsPerLayer", maxIterationsPerLayer),
        )
        .put(
            "confidence",
            JSONObject()
                .put("level", CONFIDENCE_LEVEL)
                .put("calibratedToPrinter", false),
        )
        .toString(2)

    fun summaryText(): String = buildString {
        appendLine("Model: $sourceName")
        appendLine("Material: $materialName")
        appendLine("Analysis: ${analysisFingerprintSha256.take(12)}…")
        appendLine()
        appendLine("On-bed warp: ${formatLength(bondedWarpMm)}")
        appendLine("Released warp: ${formatLength(releasedWarpMm)}")
        appendLine("Peak bed lift traction: ${formatStress(peakLiftMpa)}")
        appendLine("Peak bed shear: ${formatStress(peakShearMpa)}")
        appendLine()
        append("Experimental literature-seeded estimate. No absolute pass/fail threshold is applied.")
    }

    fun toMarkdown(): String = buildString {
        appendLine("# TrioSlicer thermal FEA report")
        appendLine()
        appendLine("> Experimental build-process thermo-mechanical estimate. This report is not a certification or an absolute failure verdict.")
        appendLine()
        appendLine("## Identity")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|---|---|")
        appendLine("| Model | ${markdown(sourceName)} |")
        appendLine("| Model SHA-256 | `$sourceSha256` |")
        appendLine("| Analysis fingerprint | `$analysisFingerprintSha256` |")
        appendLine("| filaSim commit | `$upstreamCommit` |")
        appendLine("| Numeric source | Exact final `buildSim` worker response |")
        appendLine("| Generated | ${DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(generatedAtEpochMillis))} |")
        appendLine()
        appendLine("## Build pose")
        appendLine()
        appendLine("filaSim stores the transform as nine row-major linear terms followed by tx, ty, tz. It is rendered below as a 3×4 affine matrix and binds this report to the exact orientation, scale, and plate placement that were solved.")
        appendLine()
        appendLine("```text")
        appendLine(formatTransformRow(0, 1, 2, 9))
        appendLine(formatTransformRow(3, 4, 5, 10))
        appendLine(formatTransformRow(6, 7, 8, 11))
        appendLine("```")
        appendLine()
        appendLine("## Inputs")
        appendLine()
        appendLine("| Input | Value |")
        appendLine("|---|---:|")
        appendLine("| Material | ${markdown(materialName)} |")
        appendLine("| In-plane shrink | ${formatPercent(shrinkXyPercent)} |")
        appendLine("| Through-layer shrink | ${formatPercent(shrinkZPercent)} |")
        yieldStrengthMpa?.let { appendLine("| Yield strength used by plastic correction | ${formatStress(it)} |") }
        lockingTemperatureC?.let { appendLine("| Locking temperature | ${formatTemperature(it)} |") }
        bedTemperatureC?.let { appendLine("| Bed temperature | ${formatTemperature(it)} |") }
        chamberTemperatureC?.let { appendLine("| Chamber temperature | ${formatTemperature(it)} |") }
        finalTemperatureC?.let { appendLine("| Final temperature | ${formatTemperature(it)} |") }
        thermalDecayMm?.let { appendLine("| Bed-temperature decay depth | ${formatLength(it)} |") }
        appendLine("| Requested displayed state | $requestedState |")
        appendLine("| Voxel size | ${formatLength(voxelSizeMm)} |")
        appendLine("| Coarse build grid | $gridNx × $gridNy × $gridNz |")
        appendLine("| Active cells | $activeCells |")
        appendLine("| Activated computational layers | $buildLayers |")
        appendLine("| Stiffness field | ${if (densityAware) "As-printed density aware" else "Solid hull"} |")
        appendLine()
        appendLine("## Calculated results")
        appendLine()
        appendLine("| Result | Value |")
        appendLine("|---|---:|")
        appendLine("| Maximum deformation while bonded to bed | ${formatLength(bondedWarpMm)} |")
        appendLine("| Maximum deformation after release | ${formatLength(releasedWarpMm)} |")
        appendLine("| Peak bed lift traction | ${formatStress(peakLiftMpa)} |")
        appendLine("| Peak bed shear traction | ${formatStress(peakShearMpa)} |")
        appendLine("| Solver time | ${formatNumber(solverSeconds)} s |")
        appendLine("| MGCG iterations/layer, mean | ${formatNumber(meanIterationsPerLayer)} |")
        appendLine("| MGCG iterations/layer, max | $maxIterationsPerLayer |")
        appendLine()
        appendLine("## Method and interpretation")
        appendLine()
        appendLine("- Sequential voxel-layer activation with an inherent-strain/eigenstrain load.")
        appendLine("- Bonded state constrains the bed plane; released state removes the bed and suppresses rigid-body motion with a minimal 3-2-1 pin.")
        appendLine("- Bed traction and shear are failure drivers, not a bed-adhesion probability. **No absolute pass/fail threshold is applied.**")
        appendLine("- The source SHA and exact cumulative solver transform are included in the analysis fingerprint. Geometry, orientation, scale, placement, material, process, or grid changes require a new run.")
        appendLine()
        appendLine("## Applicability limits")
        appendLine()
        appendLine("This implementation does **not** calculate transient heat flow, nozzle-path reheating, interlayer weld strength, creep, service-temperature softening, radiation, convection, or an absolute probability of bed release. Those require G-code thermal history and printer/filament/build-surface calibration.")
        appendLine()
        appendLine("## Material evidence")
        appendLine()
        appendLine(materialEvidence(materialName))
        appendLine()
        appendLine("Thermal-conductivity measurements for printed PLA/PET-G/ABS across infill, pattern, and temperature are available in DOI `10.3390/ma18173950`, but are not applied here because this solver does not yet include a heat-conduction equation.")
    }

    private fun formatTransformRow(a: Int, b: Int, c: Int, t: Int): String =
        listOf(modelTransform3x4[a], modelTransform3x4[b], modelTransform3x4[c], modelTransform3x4[t])
            .joinToString(prefix = "[ ", postfix = " ]", separator = "  ") { formatNumber(it) }

    companion object {
        const val SCHEMA_VERSION = 1
        const val ANALYSIS_KIND = "fdm-build-thermomechanical"
        const val SOLVER_MODEL = "sequential-voxel-inherent-strain"
        const val PRECISION_SOURCE = "raw-worker-response"
        const val CONFIDENCE_LEVEL = "experimental-literature-seeded"

        private val SHA_PATTERN = Regex("[0-9a-f]{64}")
        private val COMMIT_PATTERN = Regex("[0-9a-f]{40}")

        fun parse(
            payload: String,
            expectedSourceSha256: String,
            expectedUpstreamCommit: String,
            nowEpochMillis: Long = System.currentTimeMillis(),
        ): ThermalFeaReport {
            require(payload.length in 2..MAX_JSON_CHARS) { "Thermal FEA report size is invalid" }
            require(expectedSourceSha256.matches(SHA_PATTERN)) { "Expected model fingerprint is invalid" }
            require(expectedUpstreamCommit.matches(COMMIT_PATTERN)) { "Expected filaSim commit is invalid" }

            val root = JSONObject(payload)
            val schema = root.requireInt("schemaVersion", SCHEMA_VERSION..SCHEMA_VERSION)
            require(root.requireText("analysisKind", 64) == ANALYSIS_KIND) {
                "Unsupported thermal FEA analysis kind"
            }
            require(root.requireText("solverModel", 96) == SOLVER_MODEL) {
                "Unsupported thermal FEA solver model"
            }
            val precision = root.requireText("precisionSource", 64)
            require(precision == PRECISION_SOURCE) {
                "Thermal FEA report did not originate from the exact worker response"
            }
            val sourceName = root.requireText("sourceName", 240).safeFileName()
            val sourceSha = root.requireText("sourceSha256", 64)
            require(sourceSha.matches(SHA_PATTERN) && sourceSha == expectedSourceSha256) {
                "Thermal FEA result does not match the analyzed STL"
            }
            val commit = root.requireText("upstreamCommit", 40)
            require(commit.matches(COMMIT_PATTERN) && commit == expectedUpstreamCommit) {
                "Thermal FEA result came from an unexpected filaSim build"
            }
            val generatedAt = root.requireLong("generatedAtEpochMillis")
            require(generatedAt in MIN_TIMESTAMP_MILLIS..(nowEpochMillis + MAX_FUTURE_SKEW_MILLIS)) {
                "Thermal FEA report timestamp is invalid"
            }

            val pose = root.requireObject("pose")
            val transform = pose.requireFiniteArray("transform3x4", 12, -MAX_TRANSFORM_COMPONENT..MAX_TRANSFORM_COMPONENT)
            requireValidTransform(transform)

            val material = root.requireObject("material")
            val process = root.requireObject("process")
            val mesh = root.requireObject("mesh")
            val results = root.requireObject("results")
            val confidence = root.requireObject("confidence")
            require(confidence.requireText("level", 64) == CONFIDENCE_LEVEL) {
                "Thermal FEA confidence label is missing"
            }
            require(!confidence.optBoolean("calibratedToPrinter", true)) {
                "Unverified printer calibration must not be claimed"
            }

            val materialName = material.requireText("name", 120)
            val shrinkXyPercent = material.requireFinite("shrinkXyPercent", 0.0..20.0)
            val shrinkZPercent = material.requireFinite("shrinkZPercent", 0.0..20.0)
            val yieldStrengthMpa = material.optionalFinite("yieldStrengthMpa", 0.0..20_000.0)
            val lockingTemperatureC = material.optionalFinite("lockingTemperatureC", -50.0..350.0)
            val bedTemperatureC = process.optionalFinite("bedTemperatureC", -50.0..250.0)
            val chamberTemperatureC = process.optionalFinite("chamberTemperatureC", -50.0..200.0)
            val finalTemperatureC = process.optionalFinite("finalTemperatureC", -50.0..200.0)
            val thermalDecayMm = process.optionalFinite("thermalDecayMm", 0.001..10_000.0)
            val requestedState = process.requireText("requestedState", 16).also {
                require(it == "bonded" || it == "released") { "Thermal FEA requested state is invalid" }
            }
            val densityAware = process.getBoolean("densityAware")
            val voxelSizeMm = mesh.requireFinite("voxelSizeMm", 0.01..100.0)
            val gridNx = mesh.requireWhole("nx", 1..100_000)
            val gridNy = mesh.requireWhole("ny", 1..100_000)
            val gridNz = mesh.requireWhole("nz", 1..100_000)
            val activeCells = mesh.requireWhole("activeCells", 1..100_000_000)
            val buildLayers = mesh.requireWhole("buildLayers", 1..100_000)
            val bondedWarpMm = results.requireFinite("bondedWarpMm", 0.0..2_000.0)
            val releasedWarpMm = results.requireFinite("releasedWarpMm", 0.0..2_000.0)
            val peakLiftMpa = results.requireFinite("peakLiftMpa", 0.0..20_000.0)
            val peakShearMpa = results.requireFinite("peakShearMpa", 0.0..20_000.0)
            val solverSeconds = results.requireFinite("solverSeconds", 0.0..604_800.0)
            val meanIterationsPerLayer = results.requireFinite("meanIterationsPerLayer", 0.0..100_000.0)
            val maxIterationsPerLayer = results.requireWhole("maxIterationsPerLayer", 0..100_000)

            val fingerprint = analysisFingerprint(
                schemaVersion = schema,
                precisionSource = precision,
                sourceSha256 = sourceSha,
                upstreamCommit = commit,
                modelTransform3x4 = transform,
                materialName = materialName,
                shrinkXyPercent = shrinkXyPercent,
                shrinkZPercent = shrinkZPercent,
                yieldStrengthMpa = yieldStrengthMpa,
                lockingTemperatureC = lockingTemperatureC,
                bedTemperatureC = bedTemperatureC,
                chamberTemperatureC = chamberTemperatureC,
                finalTemperatureC = finalTemperatureC,
                thermalDecayMm = thermalDecayMm,
                requestedState = requestedState,
                densityAware = densityAware,
                voxelSizeMm = voxelSizeMm,
                gridNx = gridNx,
                gridNy = gridNy,
                gridNz = gridNz,
                activeCells = activeCells,
                buildLayers = buildLayers,
            )
            val suppliedFingerprint = root.optString("analysisFingerprintSha256", "").trim()
            if (suppliedFingerprint.isNotEmpty()) {
                require(suppliedFingerprint.matches(SHA_PATTERN) && suppliedFingerprint == fingerprint) {
                    "Thermal FEA analysis fingerprint does not match its inputs"
                }
            }

            return ThermalFeaReport(
                schemaVersion = schema,
                precisionSource = precision,
                sourceName = sourceName,
                sourceSha256 = sourceSha,
                upstreamCommit = commit,
                analysisFingerprintSha256 = fingerprint,
                generatedAtEpochMillis = generatedAt,
                modelTransform3x4 = transform,
                materialName = materialName,
                shrinkXyPercent = shrinkXyPercent,
                shrinkZPercent = shrinkZPercent,
                yieldStrengthMpa = yieldStrengthMpa,
                lockingTemperatureC = lockingTemperatureC,
                bedTemperatureC = bedTemperatureC,
                chamberTemperatureC = chamberTemperatureC,
                finalTemperatureC = finalTemperatureC,
                thermalDecayMm = thermalDecayMm,
                requestedState = requestedState,
                densityAware = densityAware,
                voxelSizeMm = voxelSizeMm,
                gridNx = gridNx,
                gridNy = gridNy,
                gridNz = gridNz,
                activeCells = activeCells,
                buildLayers = buildLayers,
                bondedWarpMm = bondedWarpMm,
                releasedWarpMm = releasedWarpMm,
                peakLiftMpa = peakLiftMpa,
                peakShearMpa = peakShearMpa,
                solverSeconds = solverSeconds,
                meanIterationsPerLayer = meanIterationsPerLayer,
                maxIterationsPerLayer = maxIterationsPerLayer,
            )
        }

        private fun analysisFingerprint(
            schemaVersion: Int,
            precisionSource: String,
            sourceSha256: String,
            upstreamCommit: String,
            modelTransform3x4: List<Double>,
            materialName: String,
            shrinkXyPercent: Double,
            shrinkZPercent: Double,
            yieldStrengthMpa: Double?,
            lockingTemperatureC: Double?,
            bedTemperatureC: Double?,
            chamberTemperatureC: Double?,
            finalTemperatureC: Double?,
            thermalDecayMm: Double?,
            requestedState: String,
            densityAware: Boolean,
            voxelSizeMm: Double,
            gridNx: Int,
            gridNy: Int,
            gridNz: Int,
            activeCells: Int,
            buildLayers: Int,
        ): String {
            val values = buildList {
                add("enderslicercura-thermal-fea-input-v1")
                add(schemaVersion.toString())
                add(ANALYSIS_KIND)
                add(SOLVER_MODEL)
                add(precisionSource)
                add(sourceSha256)
                add(upstreamCommit)
                modelTransform3x4.forEach { add(it.stableHex()) }
                add(materialName)
                add(shrinkXyPercent.stableHex())
                add(shrinkZPercent.stableHex())
                add(yieldStrengthMpa.stableHexOrNull())
                add(lockingTemperatureC.stableHexOrNull())
                add(bedTemperatureC.stableHexOrNull())
                add(chamberTemperatureC.stableHexOrNull())
                add(finalTemperatureC.stableHexOrNull())
                add(thermalDecayMm.stableHexOrNull())
                add(requestedState)
                add(densityAware.toString())
                add(voxelSizeMm.stableHex())
                add(gridNx.toString())
                add(gridNy.toString())
                add(gridNz.toString())
                add(activeCells.toString())
                add(buildLayers.toString())
            }
            val digest = MessageDigest.getInstance("SHA-256")
            for (value in values) {
                digest.update(value.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(Locale.US, byte.toInt() and 0xff)
            }
        }

        private fun requireValidTransform(transform: List<Double>) {
            val a = transform[0]
            val b = transform[1]
            val c = transform[2]
            val d = transform[3]
            val e = transform[4]
            val f = transform[5]
            val g = transform[6]
            val h = transform[7]
            val i = transform[8]
            val determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
            require(determinant.isFinite() && abs(determinant) > MIN_TRANSFORM_DETERMINANT) {
                "Thermal FEA model transform is singular"
            }
        }
    }
}

internal data class StoredThermalFeaReport(
    val report: ThermalFeaReport,
    val jsonFile: File,
    val markdownFile: File,
)

/** Atomic storage keyed by both source STL and the complete analysis inputs. */
internal class ThermalFeaReportStore(context: Context) {
    private val root = File(context.filesDir, "thermal-fea").apply { mkdirs() }

    fun save(
        payload: String,
        expectedSourceSha256: String,
        expectedUpstreamCommit: String,
    ): StoredThermalFeaReport {
        val report = ThermalFeaReport.parse(payload, expectedSourceSha256, expectedUpstreamCommit)
        val baseName = "${report.sourceSha256}-${report.analysisFingerprintSha256}"
        val json = File(root, "$baseName.json")
        val markdown = File(root, "$baseName.md")
        writeAtomic(json, report.toCanonicalJson())
        writeAtomic(markdown, report.toMarkdown())
        cleanup(baseName)
        return StoredThermalFeaReport(report, json, markdown)
    }

    /** Returns the newest valid analysis for this source STL, regardless of pose. */
    fun load(
        sourceSha256: String,
        expectedUpstreamCommit: String,
    ): StoredThermalFeaReport? {
        if (!sourceSha256.matches(Regex("[0-9a-f]{64}"))) return null
        val candidates = root.listFiles().orEmpty()
            .filter { file ->
                file.isFile && file.extension == "json" &&
                    file.nameWithoutExtension.startsWith("$sourceSha256-")
            }
            .sortedByDescending(File::lastModified)

        for (json in candidates) {
            val markdown = File(root, "${json.nameWithoutExtension}.md")
            val stored = runCatching {
                require(json.length() in 2..MAX_JSON_BYTES) { "Stored thermal FEA JSON size is invalid" }
                val report = ThermalFeaReport.parse(
                    json.readText(),
                    expectedSourceSha256 = sourceSha256,
                    expectedUpstreamCommit = expectedUpstreamCommit,
                )
                require(json.nameWithoutExtension == "${report.sourceSha256}-${report.analysisFingerprintSha256}") {
                    "Stored thermal FEA filename does not match its analysis fingerprint"
                }
                if (!markdown.isFile || markdown.length() !in 2..MAX_MARKDOWN_BYTES) {
                    writeAtomic(markdown, report.toMarkdown())
                }
                StoredThermalFeaReport(report, json, markdown)
            }.getOrNull()
            if (stored != null) return stored
            quarantine(json, markdown)
        }
        return null
    }

    private fun quarantine(json: File, markdown: File) {
        val quarantineDir = File(root, ".quarantine").apply { mkdirs() }
        runCatching { json.renameTo(File(quarantineDir, json.name)) }
        if (markdown.isFile) runCatching { markdown.renameTo(File(quarantineDir, markdown.name)) }
    }

    private fun cleanup(activeBaseName: String) {
        val validName = Regex("[0-9a-f]{64}-[0-9a-f]{64}")
        val bases = root.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" && it.nameWithoutExtension.matches(validName) }
            .sortedByDescending(File::lastModified)
            .map(File::nameWithoutExtension)
        val keep = (listOf(activeBaseName) + bases.filter { it != activeBaseName })
            .take(MAX_STORED_REPORTS)
            .toSet()
        root.listFiles().orEmpty()
            .filter(File::isFile)
            .filter { file ->
                file.extension == "json" || file.extension == "md" || file.name.endsWith(".next")
            }
            .filter { file -> file.nameWithoutExtension !in keep }
            .forEach(File::delete)
    }

    private fun writeAtomic(target: File, text: String) {
        val staging = File(root, ".${target.name}.${UUID.randomUUID()}.next")
        try {
            FileOutputStream(staging).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            runCatching {
                Files.move(
                    staging.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            staging.delete()
        }
    }
}

private fun List<Double>.toJsonArray(): JSONArray = JSONArray().apply {
    this@toJsonArray.forEach(::put)
}

private fun JSONObject.putNullable(name: String, value: Double?): JSONObject = apply {
    if (value == null) put(name, JSONObject.NULL) else put(name, value)
}

private fun JSONObject.requireObject(name: String): JSONObject =
    optJSONObject(name) ?: error("Thermal FEA field '$name' is missing")

private fun JSONObject.requireText(name: String, maxLength: Int): String {
    val value = getString(name).trim()
    require(value.isNotEmpty() && value.length <= maxLength && value.none { it == '\u0000' }) {
        "Thermal FEA field '$name' is invalid"
    }
    return value
}

private fun JSONObject.requireInt(name: String, range: IntRange): Int =
    getInt(name).also { require(it in range) { "Thermal FEA field '$name' is out of range" } }

private fun JSONObject.requireLong(name: String): Long =
    getLong(name).also { require(it >= 0L) { "Thermal FEA field '$name' is invalid" } }

private fun JSONObject.requireWhole(name: String, range: IntRange): Int {
    val value = getDouble(name)
    require(value.isFinite() && value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) {
        "Thermal FEA field '$name' must be an integer"
    }
    return value.toInt().also {
        require(it in range) { "Thermal FEA field '$name' is out of range" }
    }
}

private fun JSONObject.requireFinite(name: String, range: ClosedFloatingPointRange<Double>): Double =
    getDouble(name).also {
        require(it.isFinite() && it in range) { "Thermal FEA field '$name' is out of range" }
    }

private fun JSONObject.optionalFinite(
    name: String,
    range: ClosedFloatingPointRange<Double>,
): Double? {
    if (!has(name) || isNull(name)) return null
    return requireFinite(name, range)
}

private fun JSONObject.requireFiniteArray(
    name: String,
    expectedSize: Int,
    range: ClosedFloatingPointRange<Double>,
): List<Double> {
    val values = optJSONArray(name) ?: error("Thermal FEA field '$name' is missing")
    require(values.length() == expectedSize) { "Thermal FEA field '$name' has the wrong length" }
    return List(expectedSize) { index ->
        values.getDouble(index).also { value ->
            require(value.isFinite() && value in range) {
                "Thermal FEA field '$name[$index]' is out of range"
            }
        }
    }
}

private fun String.safeFileName(): String =
    substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "model.stl" }

private fun Double.stableHex(): String = java.lang.Double.toHexString(this)
private fun Double?.stableHexOrNull(): String = this?.stableHex() ?: "null"

private fun markdown(value: String): String = value
    .replace("\r", " ")
    .replace("\n", " ")
    .replace("|", "\\|")
    .trim()

private fun formatNumber(value: Double): String = String.format(Locale.US, "%.8g", value)
private fun formatLength(valueMm: Double): String = "${formatNumber(valueMm)} mm"
private fun formatStress(valueMpa: Double): String = "${formatNumber(valueMpa)} MPa"
private fun formatPercent(value: Double): String = "${formatNumber(value)} %"
private fun formatTemperature(value: Double): String = "${formatNumber(value)} °C"

private fun materialEvidence(name: String): String = when {
    name.contains("PLA", ignoreCase = true) ->
        "The filaSim PLA seed uses an effective printed-part CTE near 96 × 10⁻⁶/K. A 0° printed PLA value of 96 ± 5 × 10⁻⁶/K over 25–50 °C was measured in DOI `10.3390/ma17184668`. This is a literature seed, not calibration of the user's filament or printer."

    name.contains("ABS", ignoreCase = true) ->
        "Printed ABS CTE is orientation dependent. Measurements over 20–50 °C reported 74.5–85.8 × 10⁻⁶/K across three build orientations in DOI `10.3390/nano8010049`; another FDM study reported about 86 × 10⁻⁶/K for a 0° raster in DOI `10.3390/ma17184668`."

    else ->
        "Thermal shrink and locking values come from the pinned filaSim material preset. No printer-, filament-, moisture-, or profile-specific calibration is attached to this report."
}

private const val MAX_JSON_CHARS = 64 * 1024
private const val MAX_JSON_BYTES = 64L * 1024L
private const val MAX_MARKDOWN_BYTES = 256L * 1024L
private const val MAX_STORED_REPORTS = 12
private const val MIN_TIMESTAMP_MILLIS = 1_577_836_800_000L // 2020-01-01 UTC
private const val MAX_FUTURE_SKEW_MILLIS = 24L * 60L * 60L * 1_000L
private const val MAX_TRANSFORM_COMPONENT = 1.0e9
private const val MIN_TRANSFORM_DETERMINANT = 1.0e-12
