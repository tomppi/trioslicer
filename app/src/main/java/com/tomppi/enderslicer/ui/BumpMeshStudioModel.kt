package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.PlateObject
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import com.tomppi.enderslicer.viewer.StlMesh
import java.util.Locale
import kotlin.math.ceil

/*
 * The native BumpMesh studio: tiers, the estimate, the triangle-limit predicate, the painted
 * mask and the texture-undo stack. Everything here is plain Kotlin so the phone's UI and the
 * unit tests share one rule - the Compose sheet only renders this state.
 *
 * The numbers come from .build/bumpmesh-spike/REPORT.md, measured on the dev phone with the
 * bundled engine: 225,154-triangle 3DBenchy -> 424,347 triangles at refine 0.4 mm (whole
 * object), 266,440 with an include-only painted region at the same refine, 1,494,185 at refine
 * 0.2 mm and 4,794,760 at refine 0.1 mm (which the slicer did not finish). So refine length is
 * the real size control; the estimate below is deliberately conservative and is clamped by the
 * tier's own output cap.
 */

/** How many texture applies the plate remembers; undo is the user's own side-rail row. */
internal const val BUMPMESH_UNDO_LIMIT = 5

/** Below this, a fast apply->slice loop is practical on the dev phone (spike: 424k -> 53 s). */
internal const val BUMPMESH_FAST_OUTPUT_TRIPANGLES = 800_000

/**
 * The studio's three sizes.
 *
 * - Fast: the default tier, up to 800k output triangles - the spike's sensible run was 424k and
 *   sliced in 53 s. Above this the sheet warns that slicing can take minutes.
 * - Detailed: up to 1.5M - the app's default MeshTriangleLimits, and the spike showed a 1.49M
 *   output that the slicer stalled on for more than six minutes.
 * - Extreme: the app's 8M maximum, only behind an explicit confirmation - the spike's 4.79M run
 *   peaked at 5.13 GiB of WebView renderer memory on a 7.8 GB phone.
 */
enum class BumpMeshTier(
    val label: String,
    /** Most triangles the engine may produce/return for this tier. */
    val outputCap: Int,
    /** The refine length that pairs with the tier by default, in mm. */
    val refineMm: Float,
    /**
     * The finest refine this tier may ask for. Above the measured 2M/detailed band the engine's
     * own BumpMeshStudioLimits refuses finer than 0.12 mm by its memory ceiling, so the UI never
     * lets an Extreme request slide below that floor only to be refused after the press.
     */
    val minRefineMm: Float,
) {
    FAST("Fast", BUMPMESH_FAST_OUTPUT_TRIPANGLES, 0.40f, 0.05f),
    DETAILED("Detailed", 1_500_000, 0.25f, 0.05f),
    EXTREME("Extreme", MeshTriangleLimits.MAX_TRIANGLES, 0.12f, 0.12f);

    val requiresConfirmation: Boolean get() = this == EXTREME

    val shortDescription: String
        get() = when (this) {
            FAST -> "up to 800k triangles - fastest to apply and slice"
            DETAILED -> "up to 1.5M triangles - slower to slice"
            EXTREME -> "up to 8M triangles - confirm; can take minutes and a lot of memory"
        }

    companion object {
        /** The tier that owns this output cap; Extreme for the app maximum. */
        fun forOutputCap(cap: Int): BumpMeshTier = entries.firstOrNull { it.outputCap == cap } ?: EXTREME
    }
}

/** The mapping modes the engine exposes; the labels are the page's own vocabulary. */
enum class BumpMeshMappingMode(val label: String) {
    TRIPLANAR("Triplanar"),
    PLANAR_XZ("Planar XZ"),
    PLANAR_XY("Planar XY"),
    CYLINDRICAL("Cylindrical"),
    SPHERICAL("Spherical"),
}

/**
 * One texture in the studio gallery.
 *
 * The asset path is the form the engine lane's contract takes ("bumpmesh/textures/brick.png"), so
 * the catalog scan of the app's own asset tree feeds the request without a second naming scheme.
 */
data class BumpMeshTextureRef(
    /** The asset the engine is handed: "bumpmesh/library/textures/<file>" for the 101-library. */
    val assetPath: String,
    val displayName: String,
    /** The pinned catalogue's category label when the manifest provided one. */
    val categoryLabel: String? = null,
    /** The catalogue's own thumbnail asset, when it has one. */
    val thumbnailAssetPath: String? = null,
) {
    val fileStem: String get() = assetPath.substringAfterLast('/').substringBeforeLast('.')

    val category: String get() = categoryLabel ?: bumpMeshTextureCategory(displayName)
}

/**
 * A cheap, deliberately small classification for the gallery's filter: the names are the
 * bundled texture stems (brick, woodgrain_02, knurling, carbonFiber, ...), and an unknown stem
 * is "Other" rather than being dropped from the gallery.
 */
internal fun bumpMeshTextureCategory(displayName: String): String {
    val name = displayName.lowercase(Locale.US)
    return when {
        listOf("brick", "stone", "rock", "marble", "tile").any(name::contains) -> "Stone"
        listOf("wood", "bark", "bamboo").any(name::contains) -> "Wood"
        listOf("metal", "knurl", "carbon", "hexagon", "grid", "diagrid", "isogrid", "grip", "weave_0").any(name::contains) -> "Metal"
        listOf("knit", "fabric", "leather", "weave", "basket", "textile").any(name::contains) -> "Fabric"
        listOf("bubble", "crystal", "voronoi", "noise", "organic", "cell").any(name::contains) -> "Organic"
        listOf("stripe", "dot", "checker", "pattern", "wave", "bubble").any(name::contains) -> "Pattern"
        else -> "Other"
    }
}

/**
 * Estimated output triangles for a whole-object or painted-region apply.
 *
 * This is the spike's own arithmetic, not a guess at the engine's internals: at refine 0.4 mm a
 * 225k-triangle part became 424k (x1.88), and refine length is the size control. The estimate
 * therefore scales as (2 mm / refine)^2 with a 0.075 coefficient calibrated to that measured
 * run. At refine 0.2 mm it predicts 1.69M for the same part (measured 1.49M) and at 0.1 mm 6.75M
 * (measured 4.79M) - conservative in the direction that matters, and the tier cap is applied
 * afterwards. A painted region only refines the painted share of the mesh, so its factor is
 * 1 + paintedFraction x (gain - 1): 16 % of 3DBenchy at 0.4 mm predicts 257k against the
 * measured 266k.
 */
internal fun estimateBumpMeshTriangles(
    sourceTriangles: Int,
    refineMm: Float,
    paintedFraction: Float?,
): Int {
    if (sourceTriangles <= 0) return 0
    val refine = refineMm.coerceIn(0.05f, 2.0f)
    val gain = (0.075f * (2.0f / refine) * (2.0f / refine)).coerceIn(1.0f, 40.0f)
    val factor = paintedFraction?.let { fraction ->
        1.0f + fraction.coerceIn(0.0f, 1.0f) * (gain - 1.0f)
    } ?: gain
    return ceil(sourceTriangles.toDouble() * factor.toDouble()).toInt().coerceAtLeast(1)
}

/** The one warning sentence the estimate card shows, or null when there is nothing to say. */
internal fun bumpMeshEstimateWarning(estimate: Int, tier: BumpMeshTier): String? = when {
    estimate > tier.outputCap ->
        "Estimated " + MeshTriangleLimits.formatCount(estimate) + " triangles - above the " +
            tier.label + " tier's " + MeshTriangleLimits.formatCount(tier.outputCap) +
            "; the result will be decimated to that cap."
    estimate > BUMPMESH_FAST_OUTPUT_TRIPANGLES ->
        "Estimated " + MeshTriangleLimits.formatCount(estimate) + " triangles - slicing can " +
            "take minutes at this size."
    else -> null
}

/**
 * Whether the app's mesh triangle limit is high enough for the chosen tier.
 *
 * The limit is one number: BumpMeshActivity feeds it to the web page, StlParser enforces it on
 * import, and the studio's result is imported through that parser. The tier's output cap is
 * therefore what the limit must reach - refusing by name is the only honest answer, because the
 * alternative (the page silently decimating to a lower limit) is exactly what the user asked us
 * to stop doing.
 */
data class BumpMeshLimitCheck(val current: Int, val required: Int) {
    val satisfied: Boolean get() = current >= required

    val sentence: String
        get() = if (satisfied) {
            "Mesh triangle limit " + MeshTriangleLimits.formatCount(current) +
                " - enough for this tier."
        } else {
            "The mesh triangle limit is " + MeshTriangleLimits.formatCount(current) +
                "; this tier needs " + MeshTriangleLimits.formatCount(required) +
                ". Raise it before applying, or the result would be capped."
        }
}

internal fun bumpMeshLimitCheck(current: Int, tier: BumpMeshTier): BumpMeshLimitCheck =
    BumpMeshLimitCheck(current = current, required = tier.outputCap)

/**
 * The per-triangle mask for painted-region texturing, taken from the plate's own paint.
 *
 * The support-paint brush is the plate's existing per-triangle editor, and its enforcer set is
 * exactly the painted triangles of the selected object's mesh. The studio uses the Draw
 * (enforcer) set as the "include only" region; the paint is cleared by an apply so it never
 * quietly becomes support material, and it is cleared by a mesh replacement naturally because a
 * new mesh has no paint. Indices outside the mesh are dropped rather than passed on, so a stale
 * paint can never name a triangle that no longer exists.
 */
internal fun textureMaskOf(paint: SupportPaintState, meshTriangles: Int): BooleanArray? {
    if (meshTriangles <= 0 || paint.enforcerTriangles.isEmpty()) return null
    val mask = BooleanArray(meshTriangles)
    var painted = false
    for (index in paint.enforcerTriangles) {
        if (index in 0 until meshTriangles) {
            mask[index] = true
            painted = true
        }
    }
    return if (painted) mask else null
}

internal fun paintedTriangleCount(mask: BooleanArray?): Int = mask?.count { it } ?: 0

/** One applied texture, remembered so the side rail can undo it. */
internal data class TextureUndoEntry(
    val objectId: String,
    val textureName: String,
    /** The whole object before the apply - mesh, placement and paint. */
    val before: PlateObject,
    /**
     * The mesh the apply installed. Undo only restores when this is still the object's mesh,
     * so it can never paste an old texture over a mesh that has since been edited, imported or
     * joined - the rail hides the row when the top step no longer matches.
     */
    val appliedSourceMesh: StlMesh,
) {
    // Short enough for the 80 dp side rail: the row must read as a texture undo, not clip
    // its own object name. The full story is the sheet and the accessibility label.
    val railLabel: String get() = "Undo " + textureName
}

/**
 * The texture-undo history: bounded (five, as the plate asked), last-in-first-out, and separate
 * from the placement history. The two are deliberately different stories - "Undo" in the
 * transform card moves the object, the rail's "Undo texture" replaces the mesh.
 */
internal class TextureUndoStack(private val limit: Int = BUMPMESH_UNDO_LIMIT) {
    private val entries = ArrayDeque<TextureUndoEntry>()

    val canUndo: Boolean get() = entries.isNotEmpty()
    val size: Int get() = entries.size
    val peek: TextureUndoEntry? get() = entries.lastOrNull()

    /** The rail row's label, or null while there is nothing to undo. */
    val railLabel: String? get() = peek?.railLabel

    fun push(entry: TextureUndoEntry) {
        entries.addLast(entry)
        while (entries.size > limit) entries.removeFirst()
    }

    fun pop(): TextureUndoEntry? = entries.removeLastOrNull()

    fun clear() = entries.clear()
}

/**
 * The studio's own state: settings, estimate and progress. Built fresh when the sheet opens and
 * then updated by small pure transitions, so the unit tests can drive the whole studio story
 * without an Android context.
 */
data class BumpMeshStudioState(
    val open: Boolean = false,
    val textures: List<BumpMeshTextureRef> = emptyList(),
    val search: String = "",
    /** The gallery's category chip, or null for all textures. */
    val category: String? = null,
    val texture: BumpMeshTextureRef? = null,
    val mapping: BumpMeshMappingMode = BumpMeshMappingMode.TRIPLANAR,
    val tileMm: Float = 12.0f,
    val heightMm: Float = 0.6f,
    val smoothing: Float = 0.5f,
    val refineMm: Float = BumpMeshTier.FAST.refineMm,
    /** True = whole selected plate object; false = the painted region only. */
    val wholeObject: Boolean = true,
    /** Triangles the plate's enforcer paint covers, and the mesh size they were painted on. */
    val maskTriangles: Int = 0,
    val maskMeshTriangles: Int = 0,
    val sourceName: String = "",
    val sourceTriangles: Int = 0,
    val tier: BumpMeshTier = BumpMeshTier.FAST,
    val tierConfirmed: Boolean = false,
    val limit: Int = MeshTriangleLimits.DEFAULT_TRIANGLES,
    val applying: Boolean = false,
    val phase: String? = null,
    val progress: Float = 0.0f,
    val error: String? = null,
    val result: String? = null,
    val repairWarning: String? = null,
    /** Set after "Paint the region" closes the sheet and arms the plate's paint brush. */
    val paintArmed: Boolean = false,
) {
    /** The distinct category labels the manifest provides, in display order. */
    val categories: List<String>
        get() = textures.map { it.category }.distinct().sorted()

    val filteredTextures: List<BumpMeshTextureRef>
        get() = textures.filter { texture ->
            (category == null || texture.category == category) &&
                (search.isBlank() ||
                    texture.displayName.contains(search, ignoreCase = true) ||
                    texture.category.contains(search, ignoreCase = true))
        }

    val paintedFraction: Float?
        get() = if (maskTriangles > 0 && maskMeshTriangles == sourceTriangles && sourceTriangles > 0) {
            maskTriangles.toFloat() / sourceTriangles.toFloat()
        } else {
            null
        }

    val estimate: Int
        get() = estimateBumpMeshTriangles(
            sourceTriangles = sourceTriangles,
            refineMm = refineMm,
            paintedFraction = if (wholeObject) null else paintedFraction,
        )

    val limitCheck: BumpMeshLimitCheck get() = bumpMeshLimitCheck(limit, tier)

    val maskStale: Boolean
        get() = maskTriangles > 0 && maskMeshTriangles != sourceTriangles

    val estimateWarning: String?
        get() = when {
            !wholeObject && paintedFraction == null ->
                "Paint a region on this mesh first - the mask is the Draw (enforcer) brush's triangles."
            else -> bumpMeshEstimateWarning(estimate, tier)
        }

    val extremeNeedsConfirm: Boolean get() = tier.requiresConfirmation && !tierConfirmed

    /** The sentence under the Painted region switch - the mask's own lifecycle, said plainly. */
    val maskLifecycleNote: String
        get() = "The mask is this mesh's triangle numbering: a new mesh (apply, undo, import) " +
            "clears it, and the paint is cleared after applying so it never becomes support."

    val applyBlockedReason: String?
        get() = when {
            applying -> "An apply is already running."
            sourceTriangles <= 0 -> "Select a plate object first."
            texture == null -> "Pick a texture."
            !limitCheck.satisfied -> limitCheck.sentence
            !wholeObject && paintedFraction == null ->
                "Paint a region on the plate first, or switch back to Whole object."
            extremeNeedsConfirm -> "Confirm the Extreme tier before applying."
            else -> null
        }

    val canApply: Boolean get() = applyBlockedReason == null

    /** The refine slider's own range: the tier's measured-safe floor to 1 mm. */
    val refineRange: ClosedFloatingPointRange<Float> get() = tier.minRefineMm..1.0f

    val progressLabel: String
        get() = phase?.let { it + " · " + (progress * 100f).toInt() + "%" } ?: "Preparing…"

    fun withSearch(value: String): BumpMeshStudioState = copy(search = value)

    fun withCategory(value: String?): BumpMeshStudioState = copy(category = value)

    fun withTexture(value: BumpMeshTextureRef?): BumpMeshStudioState = copy(texture = value)

    fun withMapping(value: BumpMeshMappingMode): BumpMeshStudioState = copy(mapping = value)

    fun withTileMm(value: Float): BumpMeshStudioState = copy(tileMm = value.coerceIn(1.0f, 100.0f))

    fun withHeightMm(value: Float): BumpMeshStudioState = copy(heightMm = value.coerceIn(0.1f, 3.0f))

    fun withSmoothing(value: Float): BumpMeshStudioState = copy(smoothing = value.coerceIn(0.0f, 5.0f))

    fun withRefineMm(value: Float): BumpMeshStudioState = copy(
        refineMm = value.coerceIn(tier.minRefineMm, 1.0f),
    )

    fun withWholeObject(value: Boolean): BumpMeshStudioState = copy(wholeObject = value)

    fun withTier(value: BumpMeshTier): BumpMeshStudioState = copy(
        tier = value,
        refineMm = value.refineMm.coerceIn(value.minRefineMm, 1.0f),
        tierConfirmed = !value.requiresConfirmation,
    )

    fun withTierConfirmed(): BumpMeshStudioState = copy(tierConfirmed = true)

    fun withLimit(value: Int): BumpMeshStudioState = copy(limit = value)

    fun withMask(paintedTriangles: Int, meshTriangles: Int): BumpMeshStudioState = copy(
        maskTriangles = paintedTriangles,
        maskMeshTriangles = meshTriangles,
    )

    fun starting(): BumpMeshStudioState = copy(
        applying = true,
        phase = "Starting…",
        progress = 0.0f,
        error = null,
        result = null,
        repairWarning = null,
    )

    fun progressed(phaseName: String, fraction: Float): BumpMeshStudioState = copy(
        applying = true,
        phase = phaseName,
        progress = fraction.coerceIn(0.0f, 1.0f),
    )

    fun failed(message: String): BumpMeshStudioState = copy(
        applying = false,
        phase = null,
        progress = 0.0f,
        error = message,
    )

    fun cancelled(): BumpMeshStudioState = copy(
        applying = false,
        phase = null,
        progress = 0.0f,
        error = "Cancelled - the mesh was not changed.",
    )

    fun finished(resultLine: String, repair: String?): BumpMeshStudioState = copy(
        applying = false,
        phase = null,
        progress = 1.0f,
        error = null,
        result = resultLine,
        repairWarning = repair,
        // The apply changed the mesh; the paint is gone with it (see maskLifecycleNote).
        maskTriangles = 0,
        maskMeshTriangles = 0,
        wholeObject = true,
    )
}

/** The studio opened on [model]: its source mesh, the current limit and the bundled textures. */

/**
 * Why the studio refuses to open, or null when it may.
 *
 * The studio textures a plate object's mesh. A live CAD document's plate object is the engine's
 * display tessellation for one of its bodies (ids "cad:<body>") and the next repaint would throw
 * the texture away, so that case refuses by name and asks for Convert. A converted document's
 * parts are ordinary STLs ("stl:<body>"), and an imported mesh next to a live document is fine -
 * the chosen object says which case applies, not the presence of a document.
 */
internal fun bumpMeshStudioLiveDocumentRefusal(
    modelId: String?,
    documentLoaded: Boolean,
    documentConverted: Boolean,
): String? = when {
    modelId == null ->
        "Import a model or convert the CAD document first: the studio textures a plate object's mesh."
    documentLoaded && !documentConverted && modelId.startsWith("cad:") ->
        "Convert the CAD document first - the studio works on the plate's mesh, not on the live document."
    else -> null
}


internal fun bumpMeshStudioOpened(
    model: PlateObject?,
    limit: Int,
    textures: List<BumpMeshTextureRef>,
): BumpMeshStudioState {
    val sourceTriangles = model?.sourceMesh?.triangleCount ?: 0
    val painted = model?.let { textureMaskOf(it.supportPaint, sourceTriangles) }
    return BumpMeshStudioState(
        open = true,
        textures = textures,
        texture = textures.firstOrNull(),
        sourceName = model?.name.orEmpty(),
        sourceTriangles = sourceTriangles,
        maskTriangles = paintedTriangleCount(painted),
        maskMeshTriangles = sourceTriangles,
        limit = limit,
    )
}
