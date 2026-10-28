package com.tomppi.enderslicer.engine

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.tomppi.enderslicer.nativebridge.BlenderEngine
import com.tomppi.enderslicer.nativebridge.CadEngine
import com.tomppi.enderslicer.nativebridge.KlipperPayload
import com.tomppi.enderslicer.mesh2step.MeshToStepEngine
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "EnginePreparation"
private const val MB = 1024L * 1024L

/**
 * The first init: every engine's payload, unpacked on purpose, one at a time.
 *
 * Every payload in this app is extracted lazily, on first use, and each one surprises the
 * user separately - minutes of silence on the big ones with no way to tell "working" from
 * "stuck". This is the one deliberate action that turns that into a single step with a
 * visible end: each engine reports ready, extracting with real numbers, or failed with the
 * reason, and the run always ends.
 *
 * It reuses the engines' own extraction paths and stamps on purpose. A second extractor
 * would mean two trees, two stamps, and a lazy start that does not find what this one
 * wrote. The engines here are the only writers.
 *
 * Sequential, never in parallel: the CAD tree alone is 533 MB and Orca's profiles are
 * 80 MB over 12,800 files, so doing them together would hold the peak at the sum of every
 * tree being written at once. One at a time keeps the peak at the largest single tree.
 */
object EnginePreparation {

    /**
     * What can be prepared, in the order it is prepared.
     *
     * The order is a dependency order, not an alphabetical one: CAD borrows the CPython
     * that Klipper brings, so the Python is always unpacked before the tree that needs it.
     * [needsBytes] is the asset payload measured from the APK - an estimate the screen
     * shows before anything starts, because the price is part of the decision.
     */
    enum class Engine(
        val id: String,
        val label: String,
        val detail: String,
        val needsBytes: Long,
    ) {
        CURA("cura", "CuraEngine", "packaged in the APK; nothing to extract", 0L),
        PRUSA("prusa", "PrusaSlicer", "the print, filament and machine presets", 9L * MB),
        ORCA("orca", "OrcaSlicer", "the profiles and vendor bundles", 80L * MB),
        BLENDER("blender", "Blender", "the Python payload the modelling engine runs", 140L * MB),
        KLIPPER("klipper", "Klipper host", "the CPython the host runs and the CAD engine borrows", 27L * MB),
        CAD("cad", "CAD engine", "OCCT and build123d; needs the Klipper host's Python", 533L * MB),
        /**
         * The Mesh-Mix-S2C converter's launcher, scripts and PyNanoInstantMeshes accelerator
         * (~1.8 MB, staged assets).
         *
         * Last on purpose: it borrows the Klipper CPython and the CAD tree, so preparing it
         * before either would leave it useless. It is still small - a second or two - so the
         * default selection includes it and a fresh install's first Mesh -> STEP conversion
         * finds its scripts and the Smooth remesher ready.
         */
        MESH2STEP(
            "mesh2step",
            "Mesh to STEP",
            "the mesh-to-STEP converter; needs the Klipper Python and CAD payload",
            2L * MB,
        ),
    }

    enum class Phase { PENDING, RUNNING, READY, FAILED }

    /** One engine's row: what it is doing, with the numbers while it does it. */
    data class Row(
        val engine: Engine,
        val phase: Phase = Phase.PENDING,
        val files: Int = 0,
        val bytes: Long = 0L,
        val startedAt: Long = 0L,
        val finishedAt: Long = 0L,
        val reason: String? = null,
    ) {
        /** Seconds this row has been running, ticking while the screen is open. */
        fun elapsedSeconds(now: Long): Long =
            if (startedAt == 0L) 0L else ((if (finishedAt == 0L) now else finishedAt) - startedAt) / 1000
    }

    private val _rows = MutableStateFlow(Engine.entries.map { Row(it) })
    val rows: StateFlow<List<Row>> = _rows.asStateFlow()

    private val running = AtomicBoolean(false)

    /** True while a run is in flight; the screen disables its button on this. */
    val isRunning: Boolean get() = running.get()

    /**
     * The engines a first init offers ticked in advance.
     *
     * The slicer the user has already chosen, the Klipper host, and CAD: the three that
     * make the app's two headline features work. Blender and the other slicers are offered
     * and left unticked - a user who slices with Cura should not be made to wait for 80 MB
     * of Orca profiles to call the app ready.
     */
    fun defaultSelection(activeSlicerId: String?): Set<Engine> {
        val selected = mutableSetOf<Engine>()
        Engine.entries.firstOrNull { it.id == activeSlicerId }?.let { selected.add(it) }
        selected.add(Engine.KLIPPER)
        selected.add(Engine.CAD)
        // Tiny, and it means a fresh install's first Mesh -> STEP conversion does not trigger
        // a surprise 533 MB extraction behind the sheet.
        selected.add(Engine.MESH2STEP)
        return selected
    }

    /**
     * CAD runs on Klipper's Python, and the mesh-to-STEP converter runs on both: choosing one
     * choosing one chooses what it borrows.
     */
    fun withDependencies(selected: Set<Engine>): Set<Engine> {
        var effective = selected
        if (Engine.CAD in effective || Engine.MESH2STEP in effective) {
            effective = effective + Engine.KLIPPER
        }
        if (Engine.MESH2STEP in effective) {
            effective = effective + Engine.CAD
        }
        return effective
    }

    fun neededBytes(selected: Set<Engine>): Long =
        withDependencies(selected).sumOf { it.needsBytes }

    fun freeBytes(context: Context): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    /**
     * Marks every engine whose payload is already in place, so a screen opened after a
     * previous run says ready rather than offering to do work that is done.
     */
    fun refresh(context: Context) {
        if (running.get()) return
        val app = context.applicationContext
        _rows.update { rows ->
            rows.map { row ->
                if (row.phase == Phase.PENDING && isPrepared(app, row.engine)) {
                    row.copy(phase = Phase.READY)
                } else {
                    row
                }
            }
        }
    }

    /**
     * A scope of this object's own, not the screen's.
     *
     * The run must outlive the screen that started it: skipping the onboarding step, rotating
     * the phone or opening Settings somewhere else must not cancel a half-written tree and
     * leave a payload without its stamp. The work is blocking IO on daemon threads; if the
     * process dies mid-copy the stamp was never written, so the next start redoes it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Prepares [selected], one engine at a time. Returns at once; the rows carry the state.
     */
    fun start(context: Context, selected: Set<Engine>) {
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "a prepare run is already in flight; ignoring the second")
            return
        }
        val app = context.applicationContext
        scope.launch {
            try {
                for (engine in Engine.entries.filter { it in withDependencies(selected) }) {
                    prepareOne(app, engine)
                }
                Log.i(
                    TAG,
                    "prepare run finished: " +
                        _rows.value.joinToString { it.engine.id + "=" + it.phase },
                )
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun prepareOne(context: Context, engine: Engine) {
        mark(engine) {
            it.copy(
                phase = Phase.RUNNING,
                files = 0,
                bytes = 0,
                startedAt = System.currentTimeMillis(),
                finishedAt = 0L,
                reason = null,
            )
        }
        val reason = withContext(Dispatchers.IO) {
            runCatching { prepareBlocking(context, engine) }
                .getOrElse { it.message ?: it::class.java.simpleName }
        }
        mark(engine) {
            it.copy(
                phase = if (reason == null) Phase.READY else Phase.FAILED,
                reason = reason,
                finishedAt = System.currentTimeMillis(),
            )
        }
        Log.i(TAG, engine.id + " prepared: " + (reason ?: "ready"))
    }

    private suspend fun prepareBlocking(context: Context, engine: Engine): String? = when (engine) {
        Engine.CURA -> null
        Engine.PRUSA -> if (PrusaEngineRunner(context).prepareResources(progressFor(engine))) null
        else "the PrusaSlicer presets are not packaged in this APK"
        Engine.ORCA -> if (OrcaEngineRunner(context).prepareResources(progressFor(engine))) null
        else "the OrcaSlicer profiles are not packaged in this APK"
        Engine.BLENDER -> if (BlenderEngine.isStarted()) null
        else BlenderEngine.extractPayload(context, progressFor(engine))
        Engine.KLIPPER -> KlipperPayload.ensureExtracted(context, progressFor(engine))
        Engine.CAD -> CadEngine.ensureExtracted(context, progressFor(engine))
        // The engine lane's own extractor and stamp - the same one a lazy conversion uses.
        Engine.MESH2STEP -> MeshToStepEngine.prepare(context) { files, bytes ->
            mark(engine) { it.copy(files = files, bytes = bytes) }
        }
    }

    private fun isPrepared(context: Context, engine: Engine): Boolean = when (engine) {
        Engine.CURA -> true
        Engine.PRUSA -> PrusaEngineRunner(context).isPrepared()
        Engine.ORCA -> OrcaEngineRunner(context).isPrepared()
        Engine.BLENDER -> BlenderEngine.isPayloadExtracted(context)
        Engine.KLIPPER -> KlipperPayload.isExtracted(context)
        Engine.CAD -> CadEngine.isExtracted(context) && KlipperPayload.isExtracted(context)
        Engine.MESH2STEP -> MeshToStepEngine.isPrepared(context)
    }

    /**
     * A progress callback that touches the row at most a few times a second.
     *
     * The copiers report once per file, and Orca is 12,800 of them: a StateFlow write each
     * would be 12,800 recompositions for a number the eye reads once a second.
     */
    private fun progressFor(engine: Engine): (AssetTreeExtractor.Progress) -> Unit {
        var lastEmit = 0L
        return { progress ->
            val now = System.currentTimeMillis()
            if (now - lastEmit >= 100) {
                lastEmit = now
                mark(engine) { it.copy(files = progress.files, bytes = progress.bytes) }
            }
        }
    }

    private fun mark(engine: Engine, change: (Row) -> Row) {
        _rows.update { rows -> rows.map { if (it.engine == engine) change(it) else it } }
    }
}
