package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.PlateObject
import java.io.File
import java.util.Locale

/*
 * The Mesh -> STEP (Mesh-Mix-S2C) sheet's rules: presets, quality, unit, the cost band and the
 * small state machine around one conversion. Everything is plain Kotlin so the sheet and the
 * unit tests share one rule; the engine seam is MeshToStepEngine.kt.
 *
 * The engine runs on-device and is CPU-heavy: a mechanical part is seconds, an organic mesh can
 * be minutes. The cost band is deliberately a band, not a promise - the engine lane's measured
 * numbers can tighten it without changing this file's shape.
 */

/** The presets the engine lane's conversion exposes, with the one-line meaning the sheet shows. */
enum class MeshToStepPreset(
    val label: String,
    val meaning: String,
    /** Relative cost against Auto; used only for the estimate band. */
    val costFactor: Float,
) {
    AUTO(
        "Auto",
        "The engine decides: mechanical parts fast (seconds to about a minute); organic can take minutes",
        1.0f,
    ),
    CAD(
        "CAD",
        "Mechanical, mostly flat parts: exact faces, seconds to about a minute",
        0.55f,
    ),
    SMOOTH(
        "Smooth",
        "Organic and sculpted meshes: quad remeshing - seconds for a clean small mesh, " +
            "minutes for messy or larger ones",
        // A clean small organic mesh can remesh in seconds; messy, damaged, multi-shell or
        // larger ones are minutes. The estimate must never call this path fast as a rule.
        6.0f,
    ),
    PRECISE(
        "Precise",
        "Tighter tolerance: slower and a larger file",
        1.9f,
    ),
    EXACT(
        "Exact",
        "Same kind of result as Precise (faceted output was retired); slowest, tens of minutes",
        1.9f,
    ),
}

/**
 * The unit of the mesh being converted; the engine writes STEP in millimetres regardless.
 *
 * The engine's argparse accepts mm/cm/m/in/um/ft, so the UI's "inch" chip maps to "in" - the
 * value the engine really understands.
 */
enum class MeshToStepUnit(val label: String, val value: String) {
    MM("mm", "mm"),
    INCH("inch", "in"),
}

/** What a finished conversion reported, kept so the sheet can summarise it after the engine goes quiet. */
data class MeshToStepResultInfo(
    /** The cache file the engine wrote; Save copies it to the user's own destination. */
    val stepPath: String?,
    val faces: Int?,
    /** Solids the engine wrote, when it reports them. */
    val solids: Int? = null,
    /** False when the engine finished but its own check found mesh problems; still a real file. */
    val valid: Boolean = true,
    val warnings: List<String> = emptyList(),
    val millis: Long,
) {
    val fileName: String? get() = stepPath?.substringAfterLast('/')

    /** The one line under the result: what was written and how long it took. */
    val summaryLine: String
        get() {
            val facesText = faces?.let { MeshTriangleLimits.formatCount(it) + " faces" }
                ?: "face count not reported"
            val solidsText = solids?.takeIf { it > 0 }?.let { " · " + it + " solids" }.orEmpty()
            val validText = if (valid) " · valid" else " · validation issues"
            return facesText + solidsText + validText + " · " + formatMeshToStepMillis(millis)
        }
}

/** One conversion's sheet state; survives rotation like the other sheets. */
data class MeshToStepState(
    val open: Boolean = false,
    val objectId: String? = null,
    val sourceName: String = "",
    val sourceTriangles: Int = 0,
    val preset: MeshToStepPreset = MeshToStepPreset.AUTO,
    /**
     * 0 = coarse/fast, 1 = fine/slow. Passed to the engine unchanged; the engine lane's
     * recommended default until its measured numbers land is 0.7.
     */
    val quality: Float = 0.7f,
    val unit: MeshToStepUnit = MeshToStepUnit.MM,
    val converting: Boolean = false,
    val stage: String? = null,
    val progress: Float = 0f,
    val stageMessage: String? = null,
    val result: MeshToStepResultInfo? = null,
    /** The name the user's SAVE destination reported, once Save has copied the file there. */
    val savedName: String? = null,
    /** The content URI of the saved STEP, for "Open as CAD document" in this session. */
    val savedUri: String? = null,
    val error: String? = null,
) {
    val presetMeaning: String get() = preset.meaning

    /** The short band next to the dial; the two ends of the slider carry the arrows. */
    /**
     * The extra caution under the selected preset, when its cost needs saying.
     *
     * Organic work is not what a phone should be asked to do for an hour, and Exact is only a
     * slow spelling of Precise now that faceted output was retired upstream.
     */
    val presetCaution: String?
        get() = when (preset) {
            MeshToStepPreset.SMOOTH ->
                "Smooth is the organic path, not the fast path: a clean small mesh can remesh " +
                    "in seconds; messy, damaged, multi-shell or larger meshes can take minutes. " +
                    "Mechanical parts stay on Auto/CAD/Precise."
            MeshToStepPreset.EXACT ->
                "The slowest mode: use it only if you accept many minutes of CPU on this device."
            else -> null
        }

    val qualityLabel: String
        get() = when {
            quality < 0.34f -> "Coarse"
            quality < 0.67f -> "Balanced"
            else -> "Fine"
        }

    /** The band the estimate line shows: mechanical parts are seconds, organic meshes minutes. */
    val costBand: String
        get() {
            if (sourceTriangles <= 0) return "Select a mesh first"
            val cost = sourceTriangles.toFloat() * preset.costFactor * (0.55f + 1.5f * quality)
            // A clean small organic mesh can be seconds; the band never promises speed and
            // larger organic meshes are tens of minutes.
            if (preset == MeshToStepPreset.SMOOTH) {
                return when {
                    cost < 40_000f -> "Seconds to minutes"
                    cost < 250_000f -> "Minutes"
                    else -> "Tens of minutes"
                }
            }
            return when {
                cost < 20_000f -> "Seconds"
                cost < 80_000f -> "About a minute"
                cost < 250_000f -> "Several minutes"
                else -> "Tens of minutes"
            }
        }

/**
     * The sheet's cost sentence: on-device, CPU-heavy, band first and the measured example second.
     *
     * The spike numbers are the honest shape: a 1,928-triangle mechanical test part converted in
     * 8 s (31 CAD faces) on x86, while the 225k-triangle organic 3DBenchy did not finish in
     * 25 minutes on the same machine. Cancel is live from the first second for exactly that
     * reason.
     */
    val costLine: String
        get() {
            if (sourceTriangles <= 0) return "Select a mesh first."
            if (preset == MeshToStepPreset.SMOOTH) {
                return "On-device and CPU-heavy: ~" + costBand.lowercase(Locale.US) +
                    " for this mesh. Smooth quad-remeshes organic and sculpted meshes and is " +
                    "not the fast path - a clean small mesh can take seconds, while messy, " +
                    "damaged, multi-shell or larger meshes can take minutes. Keep mechanical " +
                    "parts on Auto/CAD/Precise. Cancel stays live."
            }
            return "On-device and CPU-heavy: ~" + costBand.lowercase(Locale.US) +
                " for this mesh. Mechanical and mostly-flat meshes convert in seconds (a " +
                "~25k-triangle part about a minute); organic or sculpted meshes need Smooth and " +
                "can take minutes to tens of minutes. Cancel stays live."
        }

    val startBlockedReason: String?
        get() = when {
            converting -> "A Mesh -> STEP conversion is already running - wait for it or Cancel it."
            sourceTriangles <= 0 || objectId == null -> "Select a plate object first."
            else -> null
        }

    val canStart: Boolean get() = startBlockedReason == null

    val progressLabel: String
        get() = stage?.let { it + " · " + (progress * 100f).toInt() + "%" } ?: "Preparing…"

    val resultWarningsLine: String?
        get() {
            val warnings = result?.warnings.orEmpty()
            if (warnings.isEmpty()) return null
            val shown = warnings.take(3)
            val extra = if (warnings.size > shown.size) " (+" + (warnings.size - shown.size) + " more)" else ""
            return shown.joinToString(" · ") + extra
        }

    val savedLine: String?
        get() = savedName?.let { "Saved STEP to " + it + "." }

    fun withPreset(value: MeshToStepPreset): MeshToStepState {
        if (converting) return this
        return copy(preset = value, error = null)
    }

    fun withQuality(value: Float): MeshToStepState {
        if (converting) return this
        return copy(quality = value.coerceIn(0f, 1f), error = null)
    }

    fun withUnit(value: MeshToStepUnit): MeshToStepState {
        if (converting) return this
        return copy(unit = value, error = null)
    }

    fun starting(): MeshToStepState = copy(
        converting = true,
        stage = "Starting",
        progress = 0f,
        stageMessage = null,
        result = null,
        savedName = null,
        savedUri = null,
        error = null,
    )

    fun progressed(stageName: String, fraction: Float, message: String?): MeshToStepState = copy(
        converting = true,
        stage = stageName,
        progress = fraction.coerceIn(0f, 1f),
        stageMessage = message,
    )

    fun succeeded(result: MeshToStepResultInfo): MeshToStepState = copy(
        converting = false,
        stage = null,
        progress = 1f,
        stageMessage = null,
        result = result,
        error = null,
    )

    fun failed(message: String): MeshToStepState = copy(
        converting = false,
        stage = null,
        progress = 0f,
        stageMessage = null,
        error = message,
        result = null,
    )

    fun cancelled(): MeshToStepState = copy(
        converting = false,
        stage = null,
        progress = 0f,
        stageMessage = null,
        error = "Cancelled - no STEP file was written.",
    )

    fun saved(name: String, uri: String): MeshToStepState = copy(
        savedName = name,
        savedUri = uri,
        error = null,
    )

    fun closed(): MeshToStepState = copy(open = false, converting = false, stage = null, progress = 0f)
}

/** The sheet opened on the selected plate object. */
internal fun meshToStepOpened(model: PlateObject?): MeshToStepState = MeshToStepState(
    open = true,
    objectId = model?.id,
    sourceName = model?.name.orEmpty(),
    sourceTriangles = model?.sourceMesh?.triangleCount ?: 0,
)

/**
 * Why the conversion refuses this plate object, or null when it may run.
 *
 * Mesh -> STEP converts a mesh. A live CAD document's own plate objects are the engine's display
 * tessellation (ids "cad:<body>") - converting that would make a STEP out of a repaint cache, so
 * the user is asked to Convert first. Converted parts ("stl:<body>") and imports are meshes.
 */
internal fun meshToStepRefusal(
    modelId: String?,
    documentLoaded: Boolean,
    documentConverted: Boolean,
): String? = when {
    modelId == null -> "Select a plate object first."
    documentLoaded && !documentConverted && modelId.startsWith("cad:") ->
        "Convert the CAD document first - Mesh -> STEP converts the plate's mesh, not the live document."
    else -> null
}

/** "1.9 s" / "2 min 06 s" - short, for the result line. */
internal fun formatMeshToStepMillis(millis: Long): String {
    if (millis < 1_000L) return millis.toString() + " ms"
    val seconds = millis / 1_000.0
    if (seconds < 60.0) return String.format(Locale.US, "%.1f s", seconds)
    val whole = (millis / 1_000L).toInt()
    return (whole / 60) .toString() + " min " + (whole % 60).toString().padStart(2, '0') + " s"
}

/**
 * The SAVE picker's suggested name: the engine lane's own convention, <displayName>-cad.step.
 *
 * The suffix says what the file is - a CAD conversion of the mesh - so it is never mistaken for
 * the original STL next to it. A name that already ends in -cad is not suffixed twice.
 */
internal fun meshToStepFileName(name: String): String {
    val stem = name.substringBeforeLast('.', name)
        .replace(Regex("[^A-Za-z0-9._-]"), "-")
        .trim('-')
        .ifEmpty { "mesh" }
    val base = if (stem.endsWith("-cad")) stem else stem + "-cad"
    return base + ".step"
}
