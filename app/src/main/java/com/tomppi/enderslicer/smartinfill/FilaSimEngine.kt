package com.tomppi.enderslicer.smartinfill

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/**
 * Native Smart Infill session: the filaSim structural workflow without a
 * WebView.
 *
 * The engine runs the same pinned solver the bundled workspace ran, so the
 * results — modifier regions, densities, mass, safety factor — are the ones the
 * slice integration already consumes. Everything here is synchronous native
 * work; the blocking calls are wrapped in [Dispatchers.Default] and the UI can
 * poll [FilaSimNative.progress] and request [cancel] while they run.
 */
class FilaSimEngine private constructor(
    private var session: Long,
    private var control: Long,
    override val sourceName: String,
    override val sourceSha256: String,
    override val patchCount: Int,
) : SmartInfillEngine {

    private var lastOptions: FilaSimOptimizeOptions? = null

    /** True once [close] ran; the controller is idempotent, this makes it safe alone. */
    private var closed = false

    companion object {
        /** The upstream revision the engine is built from. */
        const val FILASIM_COMMIT = "e7485ec22d4ebe8baca04190404fbb877c90e031"

        private const val PROGRESS_POLL_MILLIS = 120L

        /** False when the native engine is not packaged for this ABI. */
        fun isAvailable(): Boolean = FilaSimNative.ensureLoaded()

        /** Native library version, for a diagnostic readout. */
        fun nativeVersion(): String =
            if (isAvailable()) runCatching { FilaSimNative.nativeVersion() }.getOrDefault("") else ""

        /**
         * Open a session for the model the app displays. The STL bytes are the
         * ones on the plate, so the package's source fingerprint is the file the
         * slice validates against.
         */
        fun open(source: File, name: String = source.name): FilaSimEngine {
            require(FilaSimNative.ensureLoaded()) {
                "The native Smart Infill engine is not available for this device ABI"
            }
            require(source.isFile && source.length() > 0) {
                "The model for Smart Infill is missing or empty"
            }
            val bytes = source.readBytes()
            val session = FilaSimNative.createSession(bytes, name)
            check(session != 0L) { "The Smart Infill session could not be created" }
            val control = FilaSimNative.createControl(session)
            if (control == 0L) {
                FilaSimNative.destroySession(session)
                error("The Smart Infill session could not be controlled")
            }
            return FilaSimEngine(
                session = session,
                control = control,
                sourceName = name,
                sourceSha256 = sha256(source),
                patchCount = FilaSimNative.patchCount(session),
            )
        }
    }

    /** Grid, patch and body counts for the current resolution setting. */
    override fun sessionInfo(): FilaSimVoxelInfo =
        FilaSimVoxelInfo.fromJson(JSONObject(FilaSimNative.sessionInfo(session)))

    /**
     * The settings the next run will use. The engine owns the defaults, so the
     * panel fills its fields from here instead of keeping a second copy of them.
     */
    override fun configuration(): FilaSimConfiguration =
        FilaSimConfiguration.fromJson(JSONObject(FilaSimNative.configuration(session)))

    /** Patch id per ORIGINAL model triangle — a picker hit maps to its patch. */
    override fun patchOfTriangle(): IntArray = FilaSimNative.patchOfOriginalTriangle(session)

    /** Every ORIGINAL triangle of one surface patch (the "whole face" pick). */
    override fun trianglesOfPatch(patch: Int): IntArray =
        FilaSimNative.originalTrianglesOfPatch(session, patch)

    /**
     * The connected surface within [radiusMm] of the hit triangle — what a tap
     * assigns. The patch alone is not a selection: the crease segmentation
     * merges an organic surface into one patch (a 3DBenchy hull is 70% of its
     * triangles), so a tap is a bounded disc and the patch is opt-in.
     */
    override fun regionAround(triangle: Int, radiusMm: Double): IntArray =
        FilaSimNative.regionAround(session, triangle, radiusMm)

    /** Density bin under each ORIGINAL triangle — the result view's tint. */
    override fun surfaceBins(): IntArray = FilaSimNative.surfaceBins(session)

    /** Flattened xyz per original triangle vertex (9 floats per triangle). */
    override fun originalPositions(): FloatArray = FilaSimNative.originalPositions(session)

    override fun setConfiguration(configuration: FilaSimConfiguration) {
        FilaSimNative.configure(session, configuration.toJson())
    }

    override fun clearBoundaryConditions() {
        FilaSimNative.clearBoundaryConditions(session)
    }

    /** Adds one condition; returns the number of conditions on the session. */
    override fun addBoundaryCondition(condition: FilaSimBoundaryCondition): Int =
        FilaSimNative.addBoundaryCondition(session, condition.toJson())

    /** Island + rigid-body-mode check before a solve or an optimize. */
    override suspend fun checkSetup(): FilaSimCheckReport = withContext(Dispatchers.Default) {
        FilaSimCheckReport.fromJson(JSONObject(FilaSimNative.checkSetup(session)))
    }

    /** Static solve of the as-configured part (the "as printed" analysis). */
    override suspend fun solve(): FilaSimSolveReport = withContext(Dispatchers.Default) {
        FilaSimSolveReport.fromJson(JSONObject(FilaSimNative.solve(session)))
    }

    /**
     * Optimize the infill (or the topology, in solid mode). [onProgress] is
     * called on a background dispatcher with live telemetry; marshal it to the
     * UI thread before touching UI state.
     */
    override suspend fun optimize(
        options: FilaSimOptimizeOptions,
        onProgress: (FilaSimProgress) -> Unit,
    ): FilaSimOptimization = coroutineScope {
        val poller = launch(Dispatchers.Default) {
            while (isActive) {
                runCatching { onProgress(FilaSimProgress.fromJson(JSONObject(FilaSimNative.progress(control)))) }
                delay(PROGRESS_POLL_MILLIS)
            }
        }
        try {
            val summary = withContext(Dispatchers.Default) {
                FilaSimNative.optimize(session, options.toJson())
            }
            lastOptions = options
            readOptimization(JSONObject(summary), options)
        } finally {
            poller.cancel()
            runCatching { onProgress(FilaSimProgress.fromJson(JSONObject(FilaSimNative.progress(control)))) }
        }
    }

    /** Ask a running solve/optimize to stop. Safe from any thread. */
    override fun cancel() {
        FilaSimNative.cancel(control)
    }

    private fun readOptimization(
        summary: JSONObject,
        options: FilaSimOptimizeOptions,
    ): FilaSimOptimization {
        val regions = List(FilaSimNative.regionCount(session)) { index ->
            FilaSimRegion(
                densityPercent = (FilaSimNative.regionDensity(session, index) * 100.0),
                positions = FilaSimNative.regionPositions(session, index),
                indices = FilaSimNative.regionIndices(session, index),
            )
        }
        return FilaSimOptimization(
            summary = summary,
            options = options,
            regions = regions,
            solid = FilaSimNative.isSolidMode(session),
        )
    }

    /** One binary STL per modifier region, zipped — the store's transport. */
    override fun modifierArchive(): ByteArray = FilaSimNative.exportModifierZip(session)

    /** Solid topology mode: the optimized body as one binary STL. */
    override fun optimizedShape(): ByteArray = FilaSimNative.exportSolidStl(session)

    /** The options of the last successful optimization. */
    fun lastOptions(): FilaSimOptimizeOptions? = lastOptions

    /**
     * Destroys the native session and control exactly once.
     *
     * The handles are raw addresses (a `Box::into_raw` pointer on the Rust side,
     * where `destroy*` is `Box::from_raw` + drop), and zero is the one value the
     * shim refuses, so both are cleared *before* the calls: a second close is a
     * no-op instead of a double free, and any call that slipped through after a
     * close sees 0 and is refused instead of reading freed memory.
     */
    override fun close() {
        if (closed) return
        closed = true
        val session = session
        val control = control
        this.session = 0L
        this.control = 0L
        FilaSimNative.destroyControl(control)
        FilaSimNative.destroySession(session)
    }
}

/** One optimized modifier region: its density and its mesh in model mm. */
data class FilaSimRegion(
    val densityPercent: Double,
    val positions: FloatArray,
    val indices: IntArray,
) {
    val triangleCount: Int get() = indices.size / 3

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** The optimizer's result: regions plus the numbers the results panel shows. */
data class FilaSimOptimization(
    val summary: JSONObject,
    val options: FilaSimOptimizeOptions,
    val regions: List<FilaSimRegion>,
    val solid: Boolean,
) {
    val baseDensityPercent: Double get() = summary.optDouble("baseDensity", 0.0) * 100.0
    val meanInfillPercent: Double get() = summary.optDouble("meanInfill", 0.0) * 100.0
    val targetInfillPercent: Double get() = summary.optDouble("targetInfill", 0.0) * 100.0
    val massGrams: Double get() = summary.optDouble("massGrams", 0.0)
    val massSolidGrams: Double get() = summary.optDouble("massSolidGrams", 0.0)
    val massFraction: Double get() = summary.optDouble("massFrac", 0.0)
    val maxDisplacementMm: Double get() = summary.optDouble("maxDisplacement", 0.0)
    val uniformMaxDisplacementMm: Double get() = summary.optDouble("uniformMaxDisp", 0.0)
    val solidMaxDisplacementMm: Double get() = summary.optDouble("solidMaxDisp", 0.0)
    val stiffnessVsSolid: Double get() = summary.optDouble("stiffnessVsSolid", 0.0)
    val gainVsUniform: Double get() = summary.optDouble("gainVsUniform", 0.0)
    val iterations: Int get() = summary.optInt("iterations", 0)
    val converged: Boolean get() = summary.optBoolean("converged", false)
    val mode: String
        get() = when {
            solid -> "solid"
            options.mode == FilaSimOptimizeMode.BINARY -> "binary"
            else -> "graded"
        }

    /** Per-bin densities and cell counts. */
    val bins: List<FilaSimBin>
        get() = summary.optJSONArray("bins")?.let { array ->
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                FilaSimBin(item.optDouble("density", 0.0) * 100.0, item.optInt("cells", 0))
            }
        } ?: emptyList()

    /** Strength goal readout: target vs achieved safety factor. */
    val safetyFactorTarget: Double? get() = summary.optDoubleOrNull("sfTarget")
    val safetyFactorAchieved: Double? get() = summary.optDoubleOrNull("sfAchieved")
    val safetyFactorBest: Double? get() = summary.optDoubleOrNull("sfBest")
    val safetyFactorFeasible: Boolean? get() = summary.optBooleanOrNull("sfFeasible")
}

/** One density bin: its percentage and how many design cells carry it. */
data class FilaSimBin(val densityPercent: Double, val cells: Int)

/** Progress telemetry of a running solve/optimize. */
data class FilaSimProgress(
    val phase: String,
    val iteration: Int,
    val maxIterations: Int,
    val pass: Int,
    val passes: Int,
    val budgetFraction: Double,
    val compliance: Double,
    val massFraction: Double,
    val meanInfillFraction: Double,
    val change: Double,
    val innerIterations: Int,
    val innerResidual: Double,
    val running: Boolean,
) {
    val fraction: Float
        get() = when {
            !running -> 1f
            maxIterations <= 0 -> 0f
            else -> (iteration.toFloat() / maxIterations.toFloat()).coerceIn(0f, 1f)
        }

    companion object {
        fun fromJson(root: JSONObject) = FilaSimProgress(
            phase = root.optString("phase", ""),
            iteration = root.optInt("iteration", 0),
            maxIterations = root.optInt("maxIter", 0),
            pass = root.optInt("pass", 0),
            passes = root.optInt("passes", 0),
            budgetFraction = root.optDouble("budget", 0.0),
            compliance = root.optDouble("compliance", 0.0),
            massFraction = root.optDouble("massFrac", 0.0),
            meanInfillFraction = root.optDouble("meanInfill", 0.0),
            change = root.optDouble("change", 0.0),
            innerIterations = root.optInt("innerIters", 0),
            innerResidual = root.optDouble("innerResidual", 0.0),
            running = root.optBoolean("running", false),
        )
    }
}

/** Voxel grid and model facts for the current resolution setting. */
data class FilaSimVoxelInfo(
    val nx: Int,
    val ny: Int,
    val nz: Int,
    val cellSizeMm: Double,
    val cells: Long,
    val solidCells: Long,
    val multigridLevels: Int,
    val patches: Int,
    val bodies: Int,
    val workingTriangles: Int,
    val originalTriangles: Int,
) {
    companion object {
        fun fromJson(root: JSONObject) = FilaSimVoxelInfo(
            nx = root.optInt("nx", 0),
            ny = root.optInt("ny", 0),
            nz = root.optInt("nz", 0),
            cellSizeMm = root.optDouble("h", 0.0),
            cells = root.optLong("cells", 0L),
            solidCells = root.optLong("solid", 0L),
            multigridLevels = root.optInt("levels", 0),
            patches = root.optInt("patches", 0),
            bodies = root.optInt("bodies", 1),
            workingTriangles = root.optInt("triangles", 0),
            originalTriangles = root.optInt("originalTriangles", 0),
        )
    }
}

/** One connected body's constraint state. */
data class FilaSimComponent(
    val cells: Long,
    val constrained: Boolean,
    val lambdaRatio: Double,
    val hasLoads: Boolean,
    val rigidModeTranslation: Double?,
)

/** Setup check: islands, rigid-body freedom and whether a solve may run. */
data class FilaSimCheckReport(
    val ok: Boolean,
    val islandCount: Int,
    val components: List<FilaSimComponent>,
) {
    companion object {
        fun fromJson(root: JSONObject): FilaSimCheckReport {
            val components = root.optJSONArray("components")?.let { array ->
                List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    FilaSimComponent(
                        cells = item.optLong("cells", 0L),
                        constrained = item.optBoolean("constrained", false),
                        lambdaRatio = item.optDouble("lambdaRatio", 0.0),
                        hasLoads = item.optBoolean("hasLoads", false),
                        rigidModeTranslation = item.optJSONObject("mode")?.optDouble("t"),
                    )
                }
            } ?: emptyList()
            return FilaSimCheckReport(
                ok = root.optBoolean("ok", false),
                islandCount = root.optInt("islandCount", 0),
                components = components,
            )
        }
    }
}

/** Static solve readout. */
data class FilaSimSolveReport(
    val iterations: Int,
    val relativeResidual: Double,
    val converged: Boolean,
    val maxDisplacementMm: Double,
    val tolerance: Double,
) {
    companion object {
        fun fromJson(root: JSONObject) = FilaSimSolveReport(
            iterations = root.optInt("iterations", 0),
            relativeResidual = root.optDouble("relResidual", 0.0),
            converged = root.optBoolean("converged", false),
            maxDisplacementMm = root.optDouble("maxDisplacement", 0.0),
            tolerance = root.optDouble("tol", 0.0),
        )
    }
}

/**
 * Material, resolution, acceleration and solver limits in one payload. The
 * engine wants SI-ish units (MPa, tonne/mm3, mm/s2); [densityGramsPerCm3] is
 * the one the UI shows and the conversion happens here.
 */
data class FilaSimConfiguration(
    val youngsModulusMpa: Double? = null,
    val poisson: Double? = null,
    val densityGramsPerCm3: Double? = null,
    val strengthMpa: Double? = null,
    val layerStrengthMpa: Double? = null,
    val shearStrengthMpa: Double? = null,
    val layerShearOn: Boolean? = null,
    val targetCells: Int? = null,
    val fixedCellSizeMm: Double? = null,
    val snapWallMm: Double? = null,
    val compositeSkin: Boolean? = null,
    val accelerationMmPerS2: List<Double>? = null,
    val tolerance: Double? = null,
    val maxIterations: Int? = null,
    val maxLevels: Int? = null,
) {
    companion object {
        /**
         * The engine's effective settings, as `nativeConfiguration` reports them.
         * Every field is present, so a panel filled from this shows real values
         * rather than blanks — including the derived shear allowable, which the
         * engine computes as 0.6 x the layer strength when it is unset.
         */
        fun fromJson(value: JSONObject): FilaSimConfiguration = FilaSimConfiguration(
            youngsModulusMpa = value.optDoubleOrNull("youngsModulusMpa"),
            poisson = value.optDoubleOrNull("poisson"),
            densityGramsPerCm3 = value.optDoubleOrNull("densityTonneMm3")?.times(1e9),
            strengthMpa = value.optDoubleOrNull("strengthMpa"),
            layerStrengthMpa = value.optDoubleOrNull("layerStrengthMpa"),
            shearStrengthMpa = value.optDoubleOrNull("shearStrengthMpa"),
            layerShearOn = value.optBooleanOrNull("layerShearOn"),
            targetCells = value.optDoubleOrNull("targetCells")?.toInt(),
            fixedCellSizeMm = value.optDoubleOrNull("fixedHMm"),
            snapWallMm = value.optDoubleOrNull("snapWallMm"),
            compositeSkin = value.optBooleanOrNull("compositeSkin"),
            accelerationMmPerS2 = value.optJSONArray("accel")?.let { array ->
                List(array.length()) { index -> array.optDouble(index) }
            },
            tolerance = value.optDoubleOrNull("tolerance"),
            maxIterations = value.optDoubleOrNull("maxIterations")?.toInt(),
            maxLevels = value.optDoubleOrNull("maxLevels")?.toInt(),
        )
    }

    fun toJson(): String {
        val root = JSONObject()
        youngsModulusMpa?.let { root.put("youngsModulusMpa", it) }
        poisson?.let { root.put("poisson", it) }
        densityGramsPerCm3?.let { root.put("densityTonneMm3", it * 1e-9) }
        strengthMpa?.let { root.put("strengthMpa", it) }
        layerStrengthMpa?.let { root.put("layerStrengthMpa", it) }
        shearStrengthMpa?.let { root.put("shearStrengthMpa", it) }
        layerShearOn?.let { root.put("layerShearOn", it) }
        targetCells?.let { root.put("targetCells", it) }
        fixedCellSizeMm?.let { root.put("fixedHMm", it) }
        snapWallMm?.let { root.put("snapWallMm", it) }
        compositeSkin?.let { root.put("compositeSkin", it) }
        accelerationMmPerS2?.let { root.put("accel", JSONArray(it)) }
        tolerance?.let { root.put("tolerance", it) }
        maxIterations?.let { root.put("maxIterations", it) }
        maxLevels?.let { root.put("maxLevels", it) }
        return root.toString()
    }
}

/** How the optimizer spends material. */
enum class FilaSimOptimizeMode {
    /** Graded infill: continuous densities clustered into printable levels. */
    GRADED,

    /** Binary infill: hollow or solid regions, nothing in between. */
    BINARY,

    /** Part Topo: material removal producing one new body. */
    SOLID_TOPOLOGY,
}

/** What the optimizer walks the budget against. */
enum class FilaSimGoal {
    /** Stiffest design at the given mean infill. */
    BUDGET,

    /** Lightest design as stiff as a uniform print at the same mean infill. */
    MATCH,

    /** Lightest design meeting the safety-factor target on every load step. */
    STRENGTH,
}

/** Optimization inputs, mirroring the WebView's engine options. */
data class FilaSimOptimizeOptions(
    val budgetPercent: Double = 25.0,
    val exponent: Double = 1.5,
    val coefficient: Double = 1.0,
    val perimeters: Int = 2,
    val lineWidthMm: Double = 0.45,
    val topBottomLayers: Int = 3,
    val layerHeightMm: Double = 0.2,
    val minimumMemberMm: Double = 0.0,
    val binCount: Int = 3,
    val smoothingPasses: Int = 2,
    val floorPercent: Double = 10.0,
    val capPercent: Double = 70.0,
    val levelsPercent: List<Double>? = null,
    val mode: FilaSimOptimizeMode = FilaSimOptimizeMode.GRADED,
    val binarySolidPattern: String = "rectilinear",
    val goal: FilaSimGoal = FilaSimGoal.BUDGET,
    val safetyFactorTarget: Double = 2.0,
    val safetyFactorMeasure: String = "both",
    val selfSupporting: Boolean = false,
    val overhangDegrees: Double = 45.0,
    val retainLoadRegions: Boolean = true,
    val symmetry: List<Double>? = null,
) {
    fun toJson(): String {
        val root = JSONObject()
            .put("budget_pct", budgetPercent)
            .put("exponent", exponent)
            .put("coeff", coefficient)
            .put("perimeters", perimeters)
            .put("line_width", lineWidthMm)
            .put("top_bottom_layers", topBottomLayers)
            .put("layer_height", layerHeightMm)
            .put("min_member_mm", minimumMemberMm)
            .put("n_bins", binCount)
            .put("smooth_iters", smoothingPasses)
            .put("floor_pct", floorPercent)
            .put("cap_pct", capPercent)
            .put("binary", mode == FilaSimOptimizeMode.BINARY)
            .put("solid", mode == FilaSimOptimizeMode.SOLID_TOPOLOGY)
            .put("solid_pattern", binarySolidPattern)
            .put("goal", goal.name.lowercase())
            .put("sf_target", safetyFactorTarget)
            .put("sf_measure", safetyFactorMeasure)
            .put("self_support", selfSupporting)
            .put("overhang_deg", overhangDegrees)
            .put("retain_bc", retainLoadRegions)
        levelsPercent?.let { root.put("levels_pct", JSONArray(it)) }
        symmetry?.let { root.put("symmetry", JSONArray(it)) }
        return root.toString()
    }
}

/**
 * Boundary conditions address ORIGINAL model triangles, which is what the model
 * picker and the paint brush produce; the engine expands them onto its working
 * tessellation.
 */
sealed class FilaSimBoundaryCondition {
    abstract val triangles: IntArray

    /** The same condition applied to a different triangle selection. */
    abstract fun withTriangles(triangles: IntArray): FilaSimBoundaryCondition

    abstract fun describe(): JSONObject

    fun toJson(): String = describe().put("tris", JSONArray(triangles.toList())).toString()

    /**
     * The same condition with its vector payload replaced — a force, moment or
     * bearing direction, a prescribed displacement, or a remote mass's centre
     * of gravity. Kinds without one return themselves, so a field editor can
     * call it unconditionally.
     */
    open fun withVector(vector: List<Double>): FilaSimBoundaryCondition = this

    /** The same condition with its scalar payload replaced (pressure, bedding modulus, mass). */
    open fun withScalar(value: Double): FilaSimBoundaryCondition = this

    /** Fully fixed support. */
    data class Fixed(override val triangles: IntArray) : FilaSimBoundaryCondition() {
        override fun withTriangles(triangles: IntArray) = copy(triangles = triangles)

        override fun describe() = JSONObject().put("kind", "fixed")
    }

    /** Frictionless (sliding) support. */
    data class Frictionless(override val triangles: IntArray) : FilaSimBoundaryCondition() {
        override fun withTriangles(triangles: IntArray) = copy(triangles = triangles)

        override fun describe() = JSONObject().put("kind", "frictionless")
    }

    /** Winkler foundation: bedding modulus k in N/mm3. */
    data class Elastic(override val triangles: IntArray, val stiffnessNPerMm3: Double) :
        FilaSimBoundaryCondition() {
        override fun withTriangles(triangles: IntArray) = copy(triangles = triangles)

        override fun withScalar(value: Double) = copy(stiffnessNPerMm3 = value)

        override fun describe() = JSONObject().put("kind", "elastic").put("k", stiffnessNPerMm3)
    }

    /** Concentrated force in N. */
    data class Force(override val triangles: IntArray, val vector: List<Double>) :
        FilaSimBoundaryCondition() {
        override fun withTriangles(triangles: IntArray) = copy(triangles = triangles)

        override fun withVector(vector: List<Double>) = copy(vector = vector)

        override fun describe() = JSONObject().put("kind", "force").put("vector", JSONArray(vector))
    }

    /** Surface pressure in MPa. */
    data class Pressure(override val triangles: IntArray, val mpa: Double) :
        FilaSimBoundaryCondition() {
        override fun withTriangles(triangles: IntArray) = copy(triangles = triangles)

        override fun withScalar(value: Double) = copy(mpa = value)

        override fun describe() = JSONObject().put("kind", "pressure").put("mpa", mpa)
    }

    /** Radial bearing load in N over a cylindrical bore. */
    data class Bearing(override val triangles: IntArray, val vector: List<Double>) :
        FilaSimBoundaryCondition() {
        override fun withTriangles(triangles: IntArray) = copy(triangles = triangles)

        override fun withVector(vector: List<Double>) = copy(vector = vector)

        override fun describe() = JSONObject().put("kind", "bearing").put("vector", JSONArray(vector))
    }

    /** Distributed couple in N·mm. */
    data class Moment(override val triangles: IntArray, val vector: List<Double>) :
        FilaSimBoundaryCondition() {
        override fun withTriangles(triangles: IntArray) = copy(triangles = triangles)

        override fun withVector(vector: List<Double>) = copy(vector = vector)

        override fun describe() = JSONObject().put("kind", "moment").put("vector", JSONArray(vector))
    }

    /** Prescribed displacement in mm on the selected axes. */
    data class Displacement(
        override val triangles: IntArray,
        val axes: List<Boolean>,
        val vector: List<Double> = listOf(0.0, 0.0, 0.0),
    ) : FilaSimBoundaryCondition() {
        override fun withTriangles(triangles: IntArray) = copy(triangles = triangles)

        override fun withVector(vector: List<Double>) = copy(vector = vector)

        override fun describe() = JSONObject()
            .put("kind", "displacement")
            .put("axes", JSONArray(axes))
            .put("vector", JSONArray(vector))
    }

    /** Cylindrical support locking radial/tangential/axial directions. */
    data class Cylindrical(override val triangles: IntArray, val dofs: List<Boolean>) :
        FilaSimBoundaryCondition() {
        override fun withTriangles(triangles: IntArray) = copy(triangles = triangles)

        override fun describe() = JSONObject().put("kind", "cylindrical").put("dofs", JSONArray(dofs))
    }

    /** Remote point mass in tonnes. */
    data class Mass(
        override val triangles: IntArray,
        val point: List<Double>,
        val massTonnes: Double,
        val rigid: Boolean = false,
    ) : FilaSimBoundaryCondition() {
        override fun withTriangles(triangles: IntArray) = copy(triangles = triangles)

        override fun withVector(vector: List<Double>) = copy(point = vector)

        override fun withScalar(value: Double) = copy(massTonnes = value)

        override fun describe() = JSONObject()
            .put("kind", "mass")
            .put("point", JSONArray(point))
            .put("mass", massTonnes)
            .put("rigid", rigid)
    }
}

/**
 * A one-line description of a boundary condition, built from its own values so
 * an edited condition never keeps a stale label.
 */
fun FilaSimBoundaryCondition.summary(): String = when (this) {
    is FilaSimBoundaryCondition.Fixed -> "Fixed support"
    is FilaSimBoundaryCondition.Frictionless -> "Frictionless support"
    is FilaSimBoundaryCondition.Elastic ->
        "Elastic support · " + formatNumber(stiffnessNPerMm3) + " N/mm³"

    is FilaSimBoundaryCondition.Force -> "Force " + formatVector(vector) + " N"
    is FilaSimBoundaryCondition.Pressure -> "Pressure " + formatNumber(mpa) + " MPa"
    is FilaSimBoundaryCondition.Bearing -> "Bearing " + formatVector(vector) + " N"
    is FilaSimBoundaryCondition.Moment -> "Moment " + formatVector(vector) + " N·mm"
    is FilaSimBoundaryCondition.Displacement ->
        "Displacement " + formatVector(vector) + " mm on " + formatAxes(axes)

    is FilaSimBoundaryCondition.Cylindrical ->
        "Cylindrical support · " + formatDofs(dofs)

    is FilaSimBoundaryCondition.Mass ->
        "Point mass " + formatNumber(massTonnes * 1e6) + " g at " + formatVector(point)
}

private fun formatNumber(value: Double): String = "%.2f".format(value)

private fun formatVector(vector: List<Double>): String =
    "(" + vector.take(3).joinToString(", ") { formatNumber(it) } + ")"

private fun formatAxes(axes: List<Boolean>): String {
    val on = listOf("X", "Y", "Z").filterIndexed { index, _ -> axes.getOrNull(index) == true }
    return if (on.isEmpty()) "no axis" else on.joinToString("/")
}

private fun formatDofs(dofs: List<Boolean>): String {
    val names = listOf("radial", "tangential", "axial")
    val on = names.filterIndexed { index, _ -> dofs.getOrNull(index) == true }
    return if (on.isEmpty()) "free" else on.joinToString("/")
}

/**
 * The metadata the Smart Infill store validates a native result against — the
 * same v2 contract the WebView's bridge produced. The pinned solver has one
 * calibrated sparse pattern and one graded full-density pattern, so those are
 * the engine's contract, not user choices (see the WebView bridge's
 * normalizeModifierMetadata for the same statement).
 */
fun smartInfillMetadataJson(
    optimization: FilaSimOptimization,
    options: FilaSimOptimizeOptions,
    sourceName: String,
    sourceSha256: String,
): String {
    val mode = optimization.mode
    val root = JSONObject()
        .put("metadataVersion", 2)
        .put("mode", if (mode == "binary") "binary" else "graded")
        .put("basePattern", SMART_INFILL_BASE_PATTERN)
        .put("gradedFullDensityPattern", SmartInfillCuraContract.GRADED_FULL_DENSITY_PATTERN)
        .put("baseDensityPercent", optimization.baseDensityPercent)
        .put("lineWidthMm", options.lineWidthMm)
        .put("layerHeightMm", options.layerHeightMm)
        .put("perimeters", options.perimeters)
        .put("topBottomLayers", options.topBottomLayers)
        .put("upstreamCommit", FilaSimEngine.FILASIM_COMMIT)
        .put("sourceName", sourceName)
        .put("sourceSha256", sourceSha256)
    if (mode == "binary") {
        root.put(
            "binarySolidPattern",
            if (options.binarySolidPattern.equals("concentric", ignoreCase = true)) {
                "concentric"
            } else {
                "rectilinear"
            },
        )
    }
    return root.toString()
}

/** The pinned filaSim solver's calibrated sparse pattern. */
const val SMART_INFILL_BASE_PATTERN = "cubic"

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null
