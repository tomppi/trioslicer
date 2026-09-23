package com.tomppi.enderslicer.smartinfill

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.Closeable

private val EMPTY_BINS = IntArray(0)
private val EMPTY_DENSITIES = DoubleArray(0)

/** Where the workflow is, for the Plate sheet's busy and result states. */
enum class SmartInfillPhase {
    /** Nothing analyzed yet: the session is open, the setup is empty. */
    IDLE,

    /** A setup exists; a check, solve or optimize may run. */
    READY,

    /** A native call is running; the UI shows progress and offers Stop. */
    BUSY,

    /** An optimization result is available to inspect and apply. */
    OPTIMIZED,

    /** The last call failed; [SmartInfillUiState.error] says why. */
    FAILED,
}

/** One boundary condition and the model surface it acts on. */
data class SmartInfillCondition(
    val id: Long,
    val condition: FilaSimBoundaryCondition,
) {
    val triangleCount: Int get() = condition.triangles.size

    /** What this condition does, from its current values, so edits never go stale. */
    val label: String get() = condition.summary()
}

/** Everything the Plate's Smart Infill sheet renders. */
data class SmartInfillUiState(
    val modelName: String = "",
    /**
     * True once the native engine is open for the displayed model. A controller
     * only exists with a session behind it, so the default is the open state and
     * the sheet's "not started yet" view is the absence of a controller.
     */
    val hasSession: Boolean = true,
    val phase: SmartInfillPhase = SmartInfillPhase.IDLE,
    val info: FilaSimVoxelInfo? = null,
    val conditions: List<SmartInfillCondition> = emptyList(),
    val pickingConditionId: Long? = null,
    val configuration: FilaSimConfiguration = FilaSimConfiguration(),
    val options: FilaSimOptimizeOptions = FilaSimOptimizeOptions(),
    /** The radius a tap selects around the hit triangle, in mm. */
    val spotSizeMm: Double = SmartInfillController.DEFAULT_SPOT_MM,
    /**
     * Density bin under each model triangle, as the last run reported it, and the
     * densities those bins stand for — the result view's tint on the part.
     */
    val surfaceBins: IntArray = IntArray(0),
    val binDensities: DoubleArray = DoubleArray(0),
    val progress: FilaSimProgress? = null,
    val check: FilaSimCheckReport? = null,
    val solve: FilaSimSolveReport? = null,
    val optimization: FilaSimOptimization? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val isBusy: Boolean get() = phase == SmartInfillPhase.BUSY

    /**
     * A run needs a condition that actually acts on a surface: a condition
     * whose pick has not happened yet would leave the engine unconstrained.
     */
    val canRun: Boolean get() = conditions.any { it.triangleCount > 0 } && !isBusy

    val hasResult: Boolean get() = optimization != null

    /**
     * The tint to draw: a stale result's bins would describe a run that no longer
     * matches the setup, so they are only offered while the optimization they came
     * from is still the current one.
     */
    val resultBins: IntArray get() = if (optimization == null) EMPTY_BINS else surfaceBins

    val resultBinDensities: DoubleArray
        get() = if (optimization == null) EMPTY_DENSITIES else binDensities

    /** The condition a surface pick would be assigned to. */
    val pickingCondition: SmartInfillCondition?
        get() = pickingConditionId?.let { id -> conditions.firstOrNull { it.id == id } }
}

/**
 * The Plate's Smart Infill workflow: conditions and their surface picks, the
 * analysis settings, the run sequencing and the result.
 *
 * All native work happens in [SmartInfillEngine]; this class owns the state the
 * UI renders and keeps the engine's condition list in step with it. It is
 * Android-free so the workflow is unit-testable against a fake engine.
 */
class SmartInfillController(
    private val engine: SmartInfillEngine,
    private val scope: CoroutineScope,
) : Closeable {

    companion object {
        /** The tap radius before the app sizes it to the model. */
        const val DEFAULT_SPOT_MM = 5.0

        private const val MIN_SPOT_MM = 0.5
        private const val MAX_SPOT_MM = 50.0

        /**
         * A tap radius that suits a model of this size. A fixed millimetre
         * default is wrong at both ends — 5 mm is most of a 20 mm part and a
         * speck on a 300 mm one — so it follows the bounding-box diagonal.
         */
        fun spotSizeForDiagonalMm(diagonalMm: Double): Double {
            if (!diagonalMm.isFinite() || diagonalMm <= 0.0) return DEFAULT_SPOT_MM
            return (diagonalMm * 0.04).coerceIn(1.0, 20.0)
        }
    }

    private val _state = MutableStateFlow(SmartInfillUiState(modelName = engine.sourceName))
    val state: StateFlow<SmartInfillUiState> = _state.asStateFlow()

    /** Patch id per original model triangle — what "Face" expands a selection to. */
    private val patchOfTriangle: IntArray by lazy { engine.patchOfTriangle() }

    /** Original triangle soup, read only when a point mass needs its centroid. */
    private val originalPositions: FloatArray by lazy { engine.originalPositions() }

    private var nextConditionId = 1L
    private var work: Job? = null

    /** Grid, patch and body counts for the current resolution setting. */
    fun refreshInfo() {
        runCatching { engine.sessionInfo() }
            .onSuccess { info -> _state.update { it.copy(info = info, error = null) } }
            .onFailure(::reportFailure)
    }

    /**
     * The engine's effective settings — material, resolution, solver limits —
     * which the panel's fields start from. Called once when the session opens:
     * after that the panel is the source of the values it sends back.
     */
    fun refreshConfiguration() {
        runCatching { engine.configuration() }
            .onSuccess { configuration ->
                _state.update { it.copy(configuration = configuration, error = null) }
            }
            .onFailure(::reportFailure)
    }

    // ---- boundary conditions ----

    /**
     * Add a condition and arm picking for it: a boundary condition without a
     * surface is not a setup, so the next model tap assigns its first spot on it.
     */
    fun addCondition(condition: FilaSimBoundaryCondition): Long {
        val id = nextConditionId++
        val entry = SmartInfillCondition(id = id, condition = condition)
        _state.update {
            it.copy(
                conditions = it.conditions + entry,
                pickingConditionId = id,
                optimization = null,
                error = null,
                notice = null,
            )
        }
        return id
    }

    /**
     * Replaces one condition's parameters: the force's magnitude and direction,
     * a pressure, a bedding stiffness. Its identity and picked surface stay.
     */
    fun updateCondition(id: Long, condition: FilaSimBoundaryCondition) {
        if (_state.value.isBusy) return
        val current = _state.value.conditions.firstOrNull { it.id == id } ?: return
        if (current.condition == condition) return
        _state.update { state ->
            state.copy(
                conditions = state.conditions.map { entry ->
                    if (entry.id == id) entry.copy(condition = condition) else entry
                },
                optimization = null,
                error = null,
                notice = null,
            )
        }
        syncEngineConditions()
    }

    /** Arms the model picker for one condition (the panel's "Pick"). */
    fun armPicking(id: Long) {
        if (_state.value.conditions.none { it.id == id }) return
        _state.update { it.copy(pickingConditionId = id, notice = null) }
    }

    fun disarmPicking() {
        _state.update { it.copy(pickingConditionId = null) }
    }

    /**
     * A tap on the model. Returns true when Smart Infill consumed it: the
     * connected surface within [SmartInfillUiState.spotSizeMm] of the hit is
     * added to the armed condition, and picking disarms so one tap is one
     * gesture (re-arm to add another spot).
     *
     * A tap deliberately does not take the whole crease patch: on an organic
     * model that patch is the entire part ([expandToSurface] is the explicit
     * way to ask for it).
     */
    fun pickAt(originalTriangle: Int): Boolean {
        val state = _state.value
        // A closed controller has no session left to ask, and a tap must never
        // take the app down: a failing pick is reported, not thrown.
        if (closed || state.isBusy) return false
        val id = state.pickingConditionId ?: return false
        val triangles = runCatching { engine.regionAround(originalTriangle, state.spotSizeMm) }
            .getOrElse { error ->
                reportFailure(error)
                return false
            }
        if (triangles.isEmpty()) return false
        addTriangles(id, triangles)
        return true
    }

    /**
     * Widens a condition to the whole surface patch its selection sits on — the
     * second step after a bounded tap, for a face-like (CAD) surface.
     */
    fun expandToSurface(id: Long) {
        if (closed || _state.value.isBusy) return
        val entry = _state.value.conditions.firstOrNull { it.id == id } ?: return
        val first = entry.condition.triangles.minOrNull() ?: return
        val patch = patchOfTriangle.getOrNull(first) ?: return
        val triangles = runCatching { engine.trianglesOfPatch(patch) }
            .getOrElse { error ->
                reportFailure(error)
                return
            }
        assignTriangles(id, triangles)
    }

    /**
     * Adds a selection to a condition. Taps accumulate, so a support spread
     * over two pads is two taps rather than one oversized radius.
     */
    private fun addTriangles(id: Long, triangles: IntArray) {
        if (triangles.isEmpty()) return
        if (_state.value.conditions.none { it.id == id }) return
        val current = _state.value.conditions.first { it.id == id }.condition
        val merged = sortedUnion(current.triangles, triangles)
        _state.update { state ->
            state.copy(
                conditions = state.conditions.map { entry ->
                    if (entry.id == id) {
                        entry.copy(
                            condition = snapMassCentroid(current, merged).withTriangles(merged),
                        )
                    } else {
                        entry
                    }
                },
                pickingConditionId = null,
                optimization = null,
                error = null,
                notice = null,
            )
        }
        syncEngineConditions()
    }

    /**
     * Keeps a remote mass's centre of gravity on the surface it was picked on.
     * The engine takes the point as given, so an unsnapped mass would hang off
     * the origin; every other kind is returned untouched.
     */
    private fun snapMassCentroid(
        condition: FilaSimBoundaryCondition,
        triangles: IntArray,
    ): FilaSimBoundaryCondition {
        if (condition !is FilaSimBoundaryCondition.Mass) return condition
        val centroid = SmartInfillSelection.centroid(originalPositions, triangles) ?: return condition
        return condition.withVector(centroid)
    }

    private fun sortedUnion(first: IntArray, second: IntArray): IntArray {
        val all = java.util.TreeSet<Int>()
        first.forEach(all::add)
        second.forEach(all::add)
        return all.toIntArray()
    }

    /** Assigns an explicit triangle selection (a brush stroke) to a condition. */
    fun assignTriangles(id: Long, triangles: IntArray) {
        if (triangles.isEmpty() || _state.value.isBusy) return
        _state.update { state ->
            state.copy(
                conditions = state.conditions.map { entry ->
                    if (entry.id == id) {
                        entry.copy(
                            condition = snapMassCentroid(entry.condition, triangles)
                                .withTriangles(triangles),
                        )
                    } else {
                        entry
                    }
                },
                pickingConditionId = null,
                optimization = null,
                error = null,
                notice = null,
            )
        }
        syncEngineConditions()
    }

    fun removeCondition(id: Long) {
        if (_state.value.isBusy) return
        _state.update { state ->
            state.copy(
                conditions = state.conditions.filterNot { it.id == id },
                pickingConditionId = state.pickingConditionId?.takeIf { it != id },
                optimization = null,
                notice = null,
            )
        }
        syncEngineConditions()
    }

    fun clearConditions() {
        if (_state.value.isBusy) return
        _state.update {
            it.copy(conditions = emptyList(), pickingConditionId = null, optimization = null)
        }
        runCatching { engine.clearBoundaryConditions() }.onFailure(::reportFailure)
    }

    private fun syncEngineConditions() {
        runCatching {
            engine.clearBoundaryConditions()
            // A condition awaiting its first surface pick is not a boundary
            // condition yet: sending an empty selection would be rejected.
            _state.value.conditions
                .filter { it.triangleCount > 0 }
                .forEach { entry -> engine.addBoundaryCondition(entry.condition) }
        }.onFailure(::reportFailure)
    }

    // ---- settings ----

    /**
     * Material, resolution and solver limits. A real change drops the result they
     * produced, and a resolution change rebuilds the grid, so the panel's grid
     * readout is refreshed with it.
     */
    fun setConfiguration(configuration: FilaSimConfiguration) {
        if (_state.value.isBusy) return
        val previous = _state.value.configuration
        if (configuration == previous) return
        _state.update { it.copy(configuration = configuration, optimization = null) }
        if (!configuration.resolutionChangedFrom(previous)) {
            runCatching { engine.setConfiguration(configuration) }.onFailure(::reportFailure)
            return
        }
        // A resolution change throws the voxel grid away and the engine rebuilds
        // it: seconds of work, so it runs off the UI thread - and under the busy
        // phase, because nothing else may touch the session while it does.
        launchWork("Resolution") {
            withContext(Dispatchers.Default) {
                engine.setConfiguration(configuration)
                refreshInfo()
            }
        }
    }

    private fun FilaSimConfiguration.resolutionChangedFrom(previous: FilaSimConfiguration): Boolean =
        targetCells != previous.targetCells ||
            fixedCellSizeMm != previous.fixedCellSizeMm ||
            snapWallMm != previous.snapWallMm ||
            compositeSkin != previous.compositeSkin

    /**
     * Print and budget inputs. A real change drops the result they produced: the
     * modifier metadata is written from these values, so an old result applied
     * under new ones would describe a run that never happened.
     */
    fun setOptions(options: FilaSimOptimizeOptions) {
        if (options == _state.value.options) return
        // A run reads its options once, at the start. Editing them now would
        // leave the running result described by inputs it never used.
        if (_state.value.isBusy) return
        _state.update { it.copy(options = options, optimization = null) }
    }

    /**
     * The tap radius in mm. The app sizes it to the model once at session start
     * ([spotSizeForDiagonalMm]); the panel lets the user take it from there.
     */
    fun setSpotSize(mm: Double) {
        if (!mm.isFinite()) return
        _state.update { it.copy(spotSizeMm = mm.coerceIn(MIN_SPOT_MM, MAX_SPOT_MM)) }
    }

    // ---- runs ----

    fun checkSetup() = launchWork("Setup check") {
        val report = engine.checkSetup()
        _state.update { it.copy(check = report) }
    }

    fun solve() = launchWork("Solve") {
        val report = engine.solve()
        _state.update { it.copy(solve = report) }
    }

    fun optimize() = launchWork("Optimization") {
        val options = _state.value.options
        val result = engine.optimize(options) { progress ->
            _state.update { it.copy(progress = progress) }
        }
        // The result view tints the part by the density under each surface
        // triangle; read that while the session still holds this run.
        val bins = withContext(Dispatchers.Default) {
            runCatching { engine.surfaceBins() }.getOrDefault(EMPTY_BINS)
        }
        val densities = result.bins.map { bin -> bin.densityPercent / 100.0 }.toDoubleArray()
        _state.update {
            it.copy(
                optimization = result,
                surfaceBins = bins,
                binDensities = densities,
                progress = null,
            )
        }
    }

    /** Asks a running native call to stop; the launch reports the cancellation. */
    fun cancel() {
        runCatching { engine.cancel() }
    }

    private fun launchWork(_label: String, block: suspend () -> Unit): Job? {
        // A closed session cannot run anything: without this the call would reach
        // the engine after its native session was destroyed.
        if (closed || _state.value.isBusy) return null
        work = scope.launch {
            _state.update { it.copy(phase = SmartInfillPhase.BUSY, error = null, notice = null) }
            try {
                block()
                _state.update {
                    it.copy(
                        phase = if (it.optimization != null) {
                            SmartInfillPhase.OPTIMIZED
                        } else {
                            SmartInfillPhase.READY
                        },
                        progress = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update {
                    it.copy(phase = SmartInfillPhase.READY, progress = null, notice = "Stopped")
                }
                throw cancelled
            } catch (error: Throwable) {
                reportFailure(error)
            }
        }
        return work
    }

    private fun reportFailure(error: Throwable) {
        _state.update {
            it.copy(
                phase = SmartInfillPhase.FAILED,
                progress = null,
                error = error.message ?: "Smart Infill failed",
            )
        }
    }

    // ---- results ----

    /** The modifier archive for the slice pipeline (graded and binary modes). */
    fun modifierArchive(): ByteArray = engine.modifierArchive()

    /** The Part Topo body (solid topology mode). */
    fun optimizedShape(): ByteArray = engine.optimizedShape()

    /** The metadata the Smart Infill store validates the archive against. */
    fun modifierMetadata(): String {
        val current = _state.value
        val optimization = current.optimization
            ?: error("Run an optimization before applying Smart Infill")
        return smartInfillMetadataJson(
            optimization = optimization,
            // The run's own options, not the panel's current ones: metadata that
            // describes a different run than the archive would fail validation.
            options = optimization.options,
            sourceName = engine.sourceName,
            sourceSha256 = engine.sourceSha256,
        )
    }

    /** Clears the transient error/notice banner. */
    fun acknowledge() {
        _state.update { it.copy(error = null, notice = null) }
    }

    /** The analyzed STL's fingerprint, which the package binds to. */
    val sourceSha256: String get() = engine.sourceSha256

    /**
     * Idempotent: a session is closed by the panel and by a model change.
     *
     * The native session is destroyed only once the running call has returned.
     * A blocking JNI call cannot be interrupted, so closing the session under it
     * would free memory the worker is still reading.
     */
    override fun close() {
        if (closed) return
        closed = true
        val running = work
        running?.cancel()
        runCatching { engine.cancel() }
        if (running == null || running.isCompleted) {
            runCatching { engine.close() }
        } else {
            running.invokeOnCompletion { runCatching { engine.close() } }
        }
    }

    /**
     * Builds the engine's pick adjacency ahead of the first tap. Welding a dense
     * model costs real time, and a tap runs on the UI thread, so the session
     * start pays for it on a worker instead.
     */
    fun warmPicking() {
        if (closed || _state.value.isBusy) return
        warmPickingNow()
    }

    private fun warmPickingNow() {
        runCatching { engine.regionAround(0, 0.0) }
    }

    /**
     * The session's own start-up work: the tap radius this part deserves, the grid
     * the solver will use, and the pick adjacency the first tap needs.
     *
     * It is launched as tracked work rather than run inline, and the returned job
     * is what the caller waits on. A blocking JNI call cannot be interrupted, so
     * [close] must keep the native session alive until it returns: run inline it
     * would hold no job, and clearing the plate while the grid is being built -
     * tens of seconds of voxelizing on a phone - would free the session under the
     * worker still inside it, a use-after-free in the native engine.
     */
    fun prepare(spotSizeMm: Double): Job? = launchWork("Preparing the model") {
        // launchWork has just set the phase busy, so this is the unguarded work
        // the public entries below would refuse to do. The app's scope runs on the
        // main dispatcher, and opening a session builds the voxel grid and the pick
        // index - tens of seconds of native work - so it goes to a worker: run
        // inline, the plate freezes and even the "starting" spinner cannot draw.
        withContext(Dispatchers.Default) {
            setSpotSize(spotSizeMm)
            refreshInfo()
            refreshConfiguration()
            warmPickingNow()
        }
    }

    private var closed = false
}
