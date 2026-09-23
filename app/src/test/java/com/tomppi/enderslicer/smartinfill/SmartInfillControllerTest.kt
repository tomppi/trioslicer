package com.tomppi.enderslicer.smartinfill

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.concurrent.TimeUnit
import org.junit.Test

/** The Plate workflow, exercised against a fake engine so no library is needed. */
class SmartInfillControllerTest {

    private class FakeEngine(
        override val sourceName: String = "hook.stl",
        override val sourceSha256: String = "a".repeat(64),
        private val patches: IntArray = intArrayOf(0, 0, 1, 1, 2, 2),
    ) : SmartInfillEngine {
        override val patchCount: Int = (patches.maxOrNull() ?: -1) + 1

        var clearedConditions = 0
        val addedConditions = mutableListOf<FilaSimBoundaryCondition>()
        var optimizeCalls = 0
        var lastOptions: FilaSimOptimizeOptions? = null
        var cancelCalls = 0
        var closed = false
        var closeCount = 0

        /**
         * Holds an optimization inside the engine, the way a blocking JNI call
         * does: not cancellable, so only the gate lets the run return.
         */
        var optimizeGate: CompletableDeferred<Unit>? = null
        var checkCalls = 0
        var solveCalls = 0
        var appliedConfiguration: FilaSimConfiguration? = null

        /** Holds the grid build inside the engine until the test releases it. */
        var sessionInfoLatch: java.util.concurrent.CountDownLatch? = null

        /** Opens the moment the grid build is entered. */
        val sessionInfoStarted = java.util.concurrent.CountDownLatch(1)

        override fun sessionInfo(): FilaSimVoxelInfo {
            sessionInfoStarted.countDown()
            sessionInfoLatch?.await()
            return FilaSimVoxelInfo(
                nx = 112, ny = 208, nz = 32, cellSizeMm = 0.35, cells = 745_472,
                solidCells = 309_766, multigridLevels = 5, patches = patchCount,
                bodies = 1, workingTriangles = 6, originalTriangles = patches.size,
            )
        }

        /** Two bins over six triangles: the result view's tint source. */
        override fun surfaceBins(): IntArray = intArrayOf(0, 1, 0, 1, 0, 1)

        override fun configuration() = FilaSimConfiguration(
            youngsModulusMpa = 2400.0,
            poisson = 0.35,
            densityGramsPerCm3 = 1.24,
            strengthMpa = 50.0,
            layerStrengthMpa = 35.0,
            shearStrengthMpa = 21.0,
            layerShearOn = true,
            targetCells = 300_000,
        )

        override fun patchOfTriangle(): IntArray = patches

        override fun trianglesOfPatch(patch: Int): IntArray =
            patches.indices.filter { patches[it] == patch }.toIntArray()

        var lastRegionRadius: Double? = null

        override fun regionAround(triangle: Int, radiusMm: Double): IntArray {
            lastRegionRadius = radiusMm
            val patch = patches.getOrElse(triangle) { return IntArray(0) }
            val samePatch = patches.indices.filter { patches[it] == patch }
            // One triangle per millimetre of radius, so a test can tell a
            // bounded pick from the whole patch.
            val reach = radiusMm.toInt().coerceAtLeast(1)
            val seed = samePatch.indexOf(triangle)
            val from = (seed - reach + 1).coerceAtLeast(0)
            val to = (seed + reach).coerceAtMost(samePatch.size)
            return samePatch.subList(from, to).toIntArray()
        }

        /**
         * A real triangle soup: triangles 0 and 1 are one 10 mm square in the
         * z = 5 plane, so a centroid snap has something to find.
         */
        override fun originalPositions(): FloatArray {
            val soup = FloatArray(patches.size * 9)
            fun put(triangle: Int, values: FloatArray) {
                if (triangle * 9 + 9 <= soup.size) values.copyInto(soup, triangle * 9)
            }
            put(0, floatArrayOf(0f, 0f, 5f, 10f, 0f, 5f, 10f, 10f, 5f))
            put(1, floatArrayOf(0f, 0f, 5f, 10f, 10f, 5f, 0f, 10f, 5f))
            put(2, floatArrayOf(20f, 0f, 0f, 21f, 0f, 0f, 20f, 1f, 0f))
            put(3, floatArrayOf(20f, 0f, 0f, 20f, 1f, 0f, 21f, 1f, 0f))
            put(4, floatArrayOf(40f, 0f, 0f, 41f, 0f, 0f, 40f, 1f, 0f))
            put(5, floatArrayOf(40f, 0f, 0f, 40f, 1f, 0f, 41f, 1f, 0f))
            return soup
        }

        override fun setConfiguration(configuration: FilaSimConfiguration) {
            this.appliedConfiguration = configuration
        }

        override fun clearBoundaryConditions() {
            clearedConditions++
            addedConditions.clear()
        }

        override fun addBoundaryCondition(condition: FilaSimBoundaryCondition): Int {
            addedConditions += condition
            return addedConditions.size
        }

        override suspend fun checkSetup() =
            FilaSimCheckReport(ok = true, islandCount = 1, components = emptyList())

        override suspend fun solve() = FilaSimSolveReport(
            iterations = 24, relativeResidual = 8.85e-6, converged = true,
            maxDisplacementMm = 0.0013, tolerance = 1e-5,
        )

        override suspend fun optimize(
            options: FilaSimOptimizeOptions,
            onProgress: (FilaSimProgress) -> Unit,
        ): FilaSimOptimization {
            optimizeCalls++
            lastOptions = options
            optimizeGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
            onProgress(
                FilaSimProgress(
                    phase = "optimize_pass", iteration = 5, maxIterations = 80, pass = 1,
                    passes = 1, budgetFraction = 0.25, compliance = 1.6, massFraction = 0.42,
                    meanInfillFraction = 0.25, change = 0.03, innerIterations = 13,
                    innerResidual = 0.003, running = true,
                ),
            )
            return FilaSimOptimization(
                summary = JSONObject()
                    .put("baseDensity", 0.10)
                    .put("massGrams", 6.65)
                    .put("massSolidGrams", 15.84)
                    .put("meanInfill", 0.249)
                    .put("targetInfill", 0.25)
                    .put("maxDisplacement", 0.0052)
                    .put(
                        "bins",
                        JSONArray()
                            .put(JSONObject().put("density", 0.10).put("cells", 1200))
                            .put(JSONObject().put("density", 0.25).put("cells", 800)),
                    )
                    .put("regionCount", 2),
                options = options,
                regions = emptyList(),
                solid = false,
            )
        }

        override fun cancel() {
            cancelCalls++
        }

        override fun modifierArchive(): ByteArray = byteArrayOf(1, 2, 3)

        override fun optimizedShape(): ByteArray = byteArrayOf(4, 5, 6)

        override fun close() {
            closed = true
            closeCount++
        }
    }

    private fun settle() = runBlocking { delay(20) }

    @Test
    fun addingAConditionArmsPickingForIt() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            val id = controller.addCondition(FilaSimBoundaryCondition.Fixed(IntArray(0)))
            val state = controller.state.value
            assertEquals(id, state.pickingConditionId)
            assertEquals(1, state.conditions.size)
            // A condition with no surface yet cannot be run.
            assertFalse(state.canRun)
            assertTrue(controller.pickAt(0))
            assertTrue(controller.state.value.canRun)
        }
    }

    /**
     * The reported bug: tapping a load or support surface selected the whole
     * model, because a tap took the crease patch — and a flat or smooth surface
     * is ONE patch. A tap is a bounded region around the hit instead.
     */
    @Test
    fun aTapAssignsABoundedRegionAroundTheHit() {
        val engine = FakeEngine(patches = intArrayOf(0, 0, 0, 0, 1, 1))
        runBlocking {
            val controller = SmartInfillController(engine, this)
            controller.setSpotSize(2.0)
            val id = controller.addCondition(FilaSimBoundaryCondition.Fixed(IntArray(0)))

            assertTrue(controller.pickAt(0))
            val state = controller.state.value
            assertNull(state.pickingConditionId)
            assertArrayEquals(intArrayOf(0, 1), state.conditions.single().condition.triangles)
            assertEquals(id, state.conditions.single().id)
            assertEquals(2.0, engine.lastRegionRadius!!, 1e-9)
            // The engine saw the assigned selection.
            assertEquals(1, engine.addedConditions.size)
            assertArrayEquals(intArrayOf(0, 1), engine.addedConditions.single().triangles)
            // A second tap without arming is not consumed by Smart Infill.
            assertFalse(controller.pickAt(4))
        }
    }

    @Test
    fun tapsAccumulateAndFaceTakesTheWholeSurface() {
        val engine = FakeEngine(patches = intArrayOf(0, 0, 0, 0, 1, 1))
        runBlocking {
            val controller = SmartInfillController(engine, this)
            controller.setSpotSize(1.0)
            val id = controller.addCondition(FilaSimBoundaryCondition.Fixed(IntArray(0)))
            controller.pickAt(0)
            controller.armPicking(id)
            controller.pickAt(2)
            assertArrayEquals(
                "a second tap adds to the first",
                intArrayOf(0, 2),
                controller.state.value.conditions.single().condition.triangles,
            )

            controller.expandToSurface(id)
            assertArrayEquals(
                "Face takes the whole surface patch",
                intArrayOf(0, 1, 2, 3),
                controller.state.value.conditions.single().condition.triangles,
            )
        }
    }

    @Test
    fun editingALoadReachesTheEngineAndDropsTheStaleResult() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            val id = controller.addCondition(
                FilaSimBoundaryCondition.Force(IntArray(0), listOf(0.0, 0.0, -100.0)),
            )
            controller.pickAt(0)
            controller.optimize()
            settle()
            assertNotNull(controller.state.value.optimization)
            assertEquals(
                "Force (0.00, 0.00, -100.00) N",
                controller.state.value.conditions.single().label,
            )

            val force = controller.state.value.conditions.single().condition
            assertTrue(force is FilaSimBoundaryCondition.Force)
            controller.updateCondition(
                id,
                force.withVector(listOf(0.0, 0.0, -250.0)),
            )

            val updated = controller.state.value.conditions.single()
            assertEquals(
                "the title follows the edited value",
                "Force (0.00, 0.00, -250.00) N",
                updated.label,
            )
            assertNull("a changed load invalidates the result", controller.state.value.optimization)
            val sent = engine.addedConditions.single() as FilaSimBoundaryCondition.Force
            assertEquals(-250.0, sent.vector[2], 1e-9)
        }
    }

    @Test
    fun aPointMassSnapsToTheSurfaceItWasPickedOn() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            controller.addCondition(
                FilaSimBoundaryCondition.Mass(
                    triangles = IntArray(0),
                    point = listOf(0.0, 0.0, 0.0),
                    massTonnes = 100e-6,
                ),
            )
            controller.pickAt(0)
            val mass = controller.state.value.conditions.single().condition
            assertTrue(mass is FilaSimBoundaryCondition.Mass)
            val point = (mass as FilaSimBoundaryCondition.Mass).point
            assertEquals("the CG sits on the picked square", 5.0, point[0], 1e-4)
            assertEquals(5.0, point[1], 1e-4)
            assertEquals(5.0, point[2], 1e-4)
            assertEquals(100e-6, mass.massTonnes, 1e-12)
        }
    }

    @Test
    fun anOptimizationPublishesTheTintForTheResultView() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            controller.addCondition(FilaSimBoundaryCondition.Fixed(IntArray(0)))
            controller.pickAt(0)
            controller.optimize()
            settle()

            val state = controller.state.value
            assertArrayEquals(intArrayOf(0, 1, 0, 1, 0, 1), state.surfaceBins)
            assertEquals(2, state.binDensities.size)
            assertEquals(0.10, state.binDensities[0], 1e-9)
            assertEquals(0.25, state.binDensities[1], 1e-9)
            assertTrue("a live result offers its tint", state.resultBins.isNotEmpty())

            // A setup change invalidates the run, and the stale tint with it.
            controller.setOptions(state.options.copy(budgetPercent = 30.0))
            assertTrue(
                "the bins go with the result they came from",
                controller.state.value.resultBins.isEmpty(),
            )
        }
    }

    /**
     * The preparation is the grid build - tens of seconds on a phone - and it is
     * tracked work for the same reason a run is: closing the session while the
     * worker is inside the engine would free memory it is still writing to.
     */
    @Test
    fun closingDuringThePreparationDestroysTheSessionOnlyAfterItReturns() {
        val engine = FakeEngine()
        val release = java.util.concurrent.CountDownLatch(1)
        engine.sessionInfoLatch = release
        // The real app runs this scope off the main thread, like this one.
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            val controller = SmartInfillController(engine, scope)
            controller.prepare(5.0)
            if (!engine.sessionInfoStarted.await(5, TimeUnit.SECONDS)) {
                throw AssertionError("the preparation never reached the engine")
            }

            controller.close()
            assertEquals("the worker is still inside the engine", 0, engine.closeCount)

            release.countDown()
            val deadline = System.currentTimeMillis() + 5_000
            while (engine.closeCount == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals("the session goes once the grid is built", 1, engine.closeCount)
        } finally {
            scope.cancel()
        }
    }

    /**
     * The session is destroyed only once the blocking native call has returned:
     * freeing it under a worker that is still reading its memory would be a
     * use-after-free, and a JNI call cannot be interrupted.
     */
    @Test
    fun closingDuringARunDestroysTheSessionOnlyAfterTheRunReturns() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            controller.addCondition(FilaSimBoundaryCondition.Fixed(IntArray(0)))
            controller.pickAt(0)
            val gate = CompletableDeferred<Unit>()
            engine.optimizeGate = gate
            controller.optimize()
            settle()
            assertEquals(1, engine.optimizeCalls)
            assertEquals(0, engine.closeCount)

            controller.close()
            assertEquals("the worker is still inside the engine", 0, engine.closeCount)
            assertEquals("the run is asked to stop first", 1, engine.cancelCalls)

            gate.complete(Unit)
            settle()
            assertEquals("the session goes once the call returns", 1, engine.closeCount)

            controller.close()
            assertEquals("closing twice destroys once", 1, engine.closeCount)
        }
    }

    @Test
    fun closingAnIdleControllerDestroysTheSessionAtOnce() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            controller.close()
            assertEquals(1, engine.closeCount)
            controller.close()
            assertEquals(1, engine.closeCount)
        }
    }

    @Test
    fun aClosedControllerRefusesFurtherWork() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            controller.close()
            controller.optimize()
            controller.addCondition(FilaSimBoundaryCondition.Fixed(IntArray(0)))
            settle()
            assertEquals("no run starts on a closed session", 0, engine.optimizeCalls)
        }
    }

    /**
     * The state is a data class compared by content, so writing an unchanged state
     * back does not emit: the sheet's collectors - and the tint they drive on the
     * model - are not woken for nothing.
     */
    @Test
    fun anUnchangedStateIsNotEmittedAgain() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            val seen = mutableListOf<SmartInfillUiState>()
            val collector = launch { controller.state.collect { seen += it } }
            settle()
            val before = seen.size
            assertTrue("the collector sees the current state", before > 0)

            // The same value written again: a new object, equal by content.
            controller.setSpotSize(controller.state.value.spotSizeMm)
            settle()

            assertEquals("an equal state is not re-emitted", before, seen.size)
            collector.cancel()
        }
    }
    @Test
    fun aMaterialChangeReachesTheEngineWithoutARebuild() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            controller.refreshConfiguration()
            val petg = FilaSimMaterialPresets.ALL.first { it.name == "PETG" }
            controller.setConfiguration(petg.applyTo(controller.state.value.configuration))
            assertEquals(2100.0, engine.appliedConfiguration?.youngsModulusMpa ?: 0.0, 1e-9)
            assertFalse("a material edit is not a grid rebuild", controller.state.value.isBusy)
        }
    }

    @Test
    fun aResolutionChangeRebuildsTheGridOffTheCaller() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            controller.refreshConfiguration()
            val base = controller.state.value.configuration
            controller.setConfiguration(base.copy(targetCells = 120_000))
            settle()
            assertEquals(120_000, engine.appliedConfiguration?.targetCells)
            assertEquals(SmartInfillPhase.READY, controller.state.value.phase)
            assertNotNull("the grid readout follows the new resolution", controller.state.value.info)
        }
    }

    @Test
    fun optionsOnlyInvalidateTheResultWhenTheyChange() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            controller.addCondition(FilaSimBoundaryCondition.Fixed(IntArray(0)))
            controller.pickAt(0)
            controller.optimize()
            settle()
            val options = controller.state.value.options
            assertNotNull(controller.state.value.optimization)

            controller.setOptions(options)
            assertNotNull(
                "re-sending the same options keeps the result",
                controller.state.value.optimization,
            )

            controller.setOptions(options.copy(budgetPercent = options.budgetPercent + 5.0))
            assertNull(
                "a real change drops the result it invalidates",
                controller.state.value.optimization,
            )
        }
    }

    @Test
    fun removingAConditionResyncsTheEngine() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            val fixed = controller.addCondition(FilaSimBoundaryCondition.Fixed(IntArray(0)))
            controller.pickAt(0)
            val force = controller.addCondition(
                FilaSimBoundaryCondition.Force(IntArray(0), listOf(0.0, 0.0, -100.0)),
            )
            controller.pickAt(4)
            assertEquals(2, controller.state.value.conditions.size)

            controller.removeCondition(fixed)
            val state = controller.state.value
            assertEquals(listOf(force), state.conditions.map { it.id })
            // A removal re-sends the surviving conditions, so the engine never
            // keeps the deleted support.
            assertEquals(1, engine.addedConditions.size)
            assertEquals(force, controller.state.value.conditions.single().id)
            assertTrue(engine.clearedConditions >= 3)
        }
    }

    @Test
    fun optimizePublishesProgressThenTheResult() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            controller.addCondition(FilaSimBoundaryCondition.Fixed(IntArray(0)))
            controller.pickAt(0)
            controller.setOptions(FilaSimOptimizeOptions(budgetPercent = 30.0))

            controller.optimize()
            settle()

            val state = controller.state.value
            assertEquals(1, engine.optimizeCalls)
            assertEquals(30.0, engine.lastOptions?.budgetPercent ?: 0.0, 1e-9)
            assertEquals(SmartInfillPhase.OPTIMIZED, state.phase)
            assertNotNull(state.optimization)
            val optimization = requireNotNull(state.optimization)
            assertEquals(10.0, optimization.baseDensityPercent, 1e-9)
            assertEquals(6.65, optimization.massGrams, 1e-9)
            assertEquals(15.84, optimization.massSolidGrams, 1e-9)
            assertTrue(state.hasResult)
        }
    }

    /**
     * A run reads its options once, when it starts. An edit in flight would
     * leave the result described by inputs the run never used - and the store
     * validates the exported archive against exactly that metadata.
     */
    @Test
    fun optionsEditedDuringARunAreRefusedAndTheResultKeepsTheRunOptions() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            controller.addCondition(FilaSimBoundaryCondition.Fixed(IntArray(0)))
            controller.pickAt(0)
            controller.setOptions(FilaSimOptimizeOptions(budgetPercent = 30.0, lineWidthMm = 0.5))

            val gate = CompletableDeferred<Unit>()
            engine.optimizeGate = gate
            controller.optimize()
            settle()
            assertTrue("the run owns the session", controller.state.value.isBusy)

            controller.setOptions(FilaSimOptimizeOptions(budgetPercent = 90.0, lineWidthMm = 0.9))
            assertEquals(
                "an edit while the run is in flight is refused",
                30.0,
                controller.state.value.options.budgetPercent,
                1e-9,
            )
            assertEquals(0.5, controller.state.value.options.lineWidthMm, 1e-9)

            gate.complete(Unit)
            settle()
            assertEquals(SmartInfillPhase.OPTIMIZED, controller.state.value.phase)
            val metadata = JSONObject(controller.modifierMetadata())
            assertEquals(
                "the metadata describes the run that produced the result",
                0.5,
                metadata.getDouble("lineWidthMm"),
                1e-9,
            )
        }
    }

    @Test
    fun metadataNeedsAResultAndCarriesTheOptions() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            controller.addCondition(FilaSimBoundaryCondition.Fixed(IntArray(0)))
            controller.pickAt(0)

            val failure = runCatching { controller.modifierMetadata() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)

            controller.optimize()
            settle()
            val metadata = JSONObject(controller.modifierMetadata())
            assertEquals(2, metadata.getInt("metadataVersion"))
            assertEquals("graded", metadata.getString("mode"))
            assertEquals("cubic", metadata.getString("basePattern"))
            assertEquals(10.0, metadata.getDouble("baseDensityPercent"), 1e-9)
            assertEquals("hook.stl", metadata.getString("sourceName"))
            assertEquals("a".repeat(64), metadata.getString("sourceSha256"))
            assertEquals(FilaSimEngine.FILASIM_COMMIT, metadata.getString("upstreamCommit"))
        }
    }

    @Test
    fun cancelReachesTheEngineAndCloseClosesIt() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            controller.cancel()
            assertEquals(1, engine.cancelCalls)
            controller.close()
            assertTrue(engine.closed)
        }
    }

    @Test
    fun configurationReachesTheEngine() {
        val engine = FakeEngine()
        runBlocking {
            val controller = SmartInfillController(engine, this)
            controller.refreshConfiguration()
            assertEquals(
                "the panel starts from the engine's own defaults",
                2400.0,
                controller.state.value.configuration.youngsModulusMpa ?: 0.0,
                1e-9,
            )

            val configuration = FilaSimConfiguration(
                youngsModulusMpa = 2400.0,
                densityGramsPerCm3 = 1.24,
                targetCells = 120_000,
            )
            controller.setConfiguration(configuration)
            // A resolution change rebuilds the grid on a worker, so the engine
            // sees it a moment later (the state carries it immediately).
            assertEquals(configuration, controller.state.value.configuration)
            settle()
            assertEquals(configuration, engine.appliedConfiguration)
        }
    }
}
