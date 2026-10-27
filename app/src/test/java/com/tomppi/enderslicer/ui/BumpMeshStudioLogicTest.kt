package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.PlateObject
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native BumpMesh studio's rules: tiers, the measured-calibrated estimate, the app's mesh
 * triangle limit predicate, the painted mask, the texture-undo stack and the studio's own state
 * transitions. The Compose sheet renders this state; these are the rules it renders.
 *
 * The numbers the estimate is checked against are the spike's real measurements on the dev
 * phone (3DBenchy, 225,154 source triangles): 424,347 whole-object at refine 0.4 mm, 266,440
 * painted-region at the same refine, and 1,494,185 at refine 0.2 mm.
 */
class BumpMeshStudioLogicTest {

    private fun texture(name: String = "Knurling") =
        BumpMeshTextureRef("bumpmesh/library/textures/knurling.jpg", name)

    private fun mesh(triangles: Int, name: String = "part.stl"): StlMesh = StlMesh(
        displayName = name,
        interleavedVertices = VertexData.fromArray(FloatArray(triangles * 18)),
        triangleCount = triangles,
        bounds = MeshBounds(0f, 0f, 0f, 10f, 10f, 10f),
    )

    private fun plateObject(
        id: String = "m1",
        triangles: Int = 100,
        paint: SupportPaintState = SupportPaintState(),
    ): PlateObject = PlateObject(
        id = id,
        name = "part.stl",
        sourceMesh = mesh(triangles),
        mesh = mesh(triangles),
        sourcePath = null,
        placement = ModelPlacement(centerXmm = 5.0, centerYmm = 5.0, baseZmm = 0.0),
        supportPaint = paint,
    )

    @Test
    fun theThreeTiersAreTheAppCapAndTheSpikesFastCeiling() {
        assertEquals(800_000, BumpMeshTier.FAST.outputCap)
        assertEquals(1_500_000, BumpMeshTier.DETAILED.outputCap)
        assertEquals(MeshTriangleLimits.MAX_TRIANGLES, BumpMeshTier.EXTREME.outputCap)
        assertFalse(BumpMeshTier.FAST.requiresConfirmation)
        assertFalse(BumpMeshTier.DETAILED.requiresConfirmation)
        assertTrue("Extreme is the one behind a confirmation", BumpMeshTier.EXTREME.requiresConfirmation)
        assertEquals(0.40f, BumpMeshTier.FAST.refineMm)
        assertEquals(0.25f, BumpMeshTier.DETAILED.refineMm)
        assertEquals(0.12f, BumpMeshTier.EXTREME.refineMm)
        assertEquals(
            "Extreme's measured-safe floor is the engine's 0.12 mm above 2M",
            0.12f,
            BumpMeshTier.EXTREME.minRefineMm,
        )
    }

    @Test
    fun theEstimateIsCalibratedToTheSpikesMeasuredRuns() {
        // 225,154 x 1.875 = 422,164 predicted; the spike measured 424,347 (-0.5 %).
        val whole = estimateBumpMeshTriangles(225_154, 0.4f, paintedFraction = null)
        assertEquals(422_164, whole)
        assertTrue(
            "the estimate is within 5 % of the measured 424,347",
            kotlin.math.abs(whole - 424_347) <= 4_000,
        )

        // 35,927 painted triangles of that mesh (16 %): predicted 256,577 vs measured 266,440.
        val painted = estimateBumpMeshTriangles(
            sourceTriangles = 225_154,
            refineMm = 0.4f,
            paintedFraction = 35_927f / 225_154f,
        )
        assertTrue(
            "the painted estimate is within 5 % of the measured 266,440",
            kotlin.math.abs(painted - 266_440) <= 12_000,
        )
        assertTrue("a painted region is cheaper than the whole object", painted < whole)

        // refine 0.2 mm measured 1.49M; the estimate is deliberately conservative above it.
        val detailed = estimateBumpMeshTriangles(225_154, 0.2f, paintedFraction = null)
        assertTrue(
            "the detailed estimate is above the measured 1,494,185",
            detailed > 1_494_185,
        )
        assertTrue(detailed > whole)
        assertEquals(0, estimateBumpMeshTriangles(0, 0.4f, paintedFraction = null))
        // A painted region larger than the mesh (bad input) cannot exceed the whole-object gain.
        assertEquals(
            whole,
            estimateBumpMeshTriangles(225_154, 0.4f, paintedFraction = 2.0f),
        )
    }

    @Test
    fun aFinerRefineLengthAlwaysEstimatesMoreAndTheCapIsTheTiers() {
        val coarse = estimateBumpMeshTriangles(100_000, 0.8f, paintedFraction = null)
        val medium = estimateBumpMeshTriangles(100_000, 0.4f, paintedFraction = null)
        val fine = estimateBumpMeshTriangles(100_000, 0.1f, paintedFraction = null)
        assertTrue(coarse < medium)
        assertTrue(medium < fine)
        // The estimate itself is not clamped; the cap is the tier's job (and the engine's).
        assertTrue(fine > BumpMeshTier.FAST.outputCap)
    }

    @Test
    fun theLimitPredicateNamesTheAppLimitAndTheTiersNeed() {
        val fast = bumpMeshLimitCheck(1_500_000, BumpMeshTier.FAST)
        assertTrue("the default limit already covers Fast", fast.satisfied)
        assertEquals(800_000, fast.required)

        val detailed = bumpMeshLimitCheck(1_500_000, BumpMeshTier.DETAILED)
        assertTrue("the default limit is Detailed's cap", detailed.satisfied)

        val extreme = bumpMeshLimitCheck(1_500_000, BumpMeshTier.EXTREME)
        assertFalse("Extreme needs the 8M app cap", extreme.satisfied)
        assertEquals(MeshTriangleLimits.MAX_TRIANGLES, extreme.required)
        assertTrue(extreme.sentence.contains("1,500,000"))
        assertTrue(extreme.sentence.contains("8,000,000"))
        assertTrue(
            "the refusal names the raise, not a device type",
            extreme.sentence.contains("Raise it"),
        )

        val raised = bumpMeshLimitCheck(8_000_000, BumpMeshTier.EXTREME)
        assertTrue(raised.satisfied)
        assertTrue(raised.sentence.contains("enough for this tier"))
    }

    @Test
    fun theMaskIsThePaintsEnforcerSetAndOutOfRangeIndicesAreDropped() {
        val paint = SupportPaintState(
            enforcerTriangles = setOf(0, 2, 4, 99),
            blockerTriangles = setOf(1),
        )
        val mask = textureMaskOf(paint, meshTriangles = 5)
        assertNotNull(mask)
        assertEquals(3, paintedTriangleCount(mask))
        assertEquals(listOf(true, false, true, false, true), mask!!.toList())
        // 99 is out of range for a 5-triangle mesh and never reaches the engine.
        assertNull(textureMaskOf(SupportPaintState(), 5))
        assertNull(textureMaskOf(paint, 0))
        // The blocker set is support material, not a texture mask: no enforcer, no mask.
        assertEquals(
            0,
            paintedTriangleCount(textureMaskOf(SupportPaintState(blockerTriangles = setOf(0, 1)), 2)),
        )
    }

    @Test
    fun theUndoStackIsBoundedToFiveAndLabelsTheRail() {
        val stack = TextureUndoStack()
        assertFalse(stack.canUndo)
        assertNull(stack.railLabel)

        val first = mesh(10, "first")
        repeat(6) { index ->
            stack.push(
                TextureUndoEntry(
                    objectId = "m1",
                    textureName = "Texture " + index,
                    before = plateObject(triangles = 100 + index),
                    appliedSourceMesh = if (index == 5) mesh(77) else first,
                ),
            )
        }
        assertEquals(5, stack.size)
        assertEquals("Undo Texture 5", stack.railLabel)
        assertEquals("Texture 5", stack.peek?.textureName)
        assertEquals("Texture 5", stack.pop()?.textureName)
        assertEquals("Texture 4", stack.pop()?.textureName)
        assertEquals(3, stack.size)
        stack.clear()
        assertFalse(stack.canUndo)
        assertNull(stack.railLabel)
    }


    @Test
    fun onlyALiveDocumentsOwnPartsAreRefused() {
        assertNull(
            "an imported or converted plate object is a mesh, whatever else is open",
            bumpMeshStudioLiveDocumentRefusal("stl:Case", documentLoaded = true, documentConverted = true),
        )
        assertNull(
            "an imported mesh beside a live document is still texturable",
            bumpMeshStudioLiveDocumentRefusal("uuid-imported", documentLoaded = true, documentConverted = false),
        )
        assertTrue(
            bumpMeshStudioLiveDocumentRefusal("cad:Case", documentLoaded = true, documentConverted = false)
                .orEmpty()
                .contains("Convert the CAD document first"),
        )
        assertTrue(
            bumpMeshStudioLiveDocumentRefusal(null, documentLoaded = false, documentConverted = false)
                .orEmpty()
                .contains("Import a model"),
        )
    }

    @Test
    fun openingTheStudioReadsTheSelectedMeshLimitAndPaint() {
        val model = plateObject(
            triangles = 40,
            paint = SupportPaintState(enforcerTriangles = setOf(1, 3)),
        )
        val state = bumpMeshStudioOpened(
            model = model,
            limit = 1_500_000,
            textures = listOf(texture()),
        )
        assertTrue(state.open)
        assertEquals("part.stl", state.sourceName)
        assertEquals(40, state.sourceTriangles)
        assertEquals(2, state.maskTriangles)
        assertEquals(40, state.maskMeshTriangles)
        assertEquals(1_500_000, state.limit)
        assertEquals(texture(), state.texture)
        assertTrue("Whole object is the default", state.wholeObject)
    }

    @Test
    fun tierAndConfirmationTransitionsArePure() {
        val base = BumpMeshStudioState(sourceTriangles = 100_000, texture = texture())
        val detailed = base.withTier(BumpMeshTier.DETAILED)
        assertEquals(0.25f, detailed.refineMm)
        assertFalse(detailed.extremeNeedsConfirm)

        val extreme = detailed.withTier(BumpMeshTier.EXTREME)
        assertTrue(extreme.extremeNeedsConfirm)
        assertTrue("Extreme has no confirmed state until the press", !extreme.tierConfirmed)
        val confirmed = extreme.withTierConfirmed()
        assertFalse(confirmed.extremeNeedsConfirm)

        val clamped = base.withRefineMm(99f)
        assertEquals(1.0f, clamped.refineMm)
        // Above the detailed band the engine refuses finer than 0.12 mm, so the slider stops there.
        val extremeFloor = base.withTier(BumpMeshTier.EXTREME).withRefineMm(0.05f)
        assertEquals(0.12f, extremeFloor.refineMm)
        val fastFloor = base.withTier(BumpMeshTier.FAST).withRefineMm(0.01f)
        assertEquals(0.05f, fastFloor.refineMm)
        val tile = base.withTileMm(0f)
        assertEquals(1.0f, tile.tileMm)
        assertEquals(3.0f, base.withHeightMm(99f).heightMm)
        assertEquals(5.0f, base.withSmoothing(99f).smoothing)
    }

    @Test
    fun theStudioRefusesByReasonAndTheReasonNamesTheFix() {
        val noObject = BumpMeshStudioState(texture = texture())
        assertEquals("Select a plate object first.", noObject.applyBlockedReason)
        assertFalse(noObject.canApply)

        val ready = BumpMeshStudioState(
            sourceTriangles = 50_000,
            texture = texture(),
            limit = 1_500_000,
        )
        assertNull(ready.applyBlockedReason)
        assertTrue(ready.canApply)
        val noTexture = ready.withTexture(null)
        assertEquals("Pick a texture.", noTexture.applyBlockedReason)

        val paintedWithoutMask = ready.withWholeObject(false)
        assertTrue(
            paintedWithoutMask.applyBlockedReason.orEmpty().contains("Paint a region"),
        )

        val paintedWithMask = paintedWithoutMask.withMask(10, 50_000)
        assertNull(paintedWithMask.applyBlockedReason)
        assertEquals(10f / 50_000f, paintedWithMask.paintedFraction!!, 1e-6f)

        val extremeUnconfirmed = ready
            .withLimit(MeshTriangleLimits.MAX_TRIANGLES)
            .withTier(BumpMeshTier.EXTREME)
        assertEquals(
            "Confirm the Extreme tier before applying.",
            extremeUnconfirmed.applyBlockedReason,
        )
        assertNull(extremeUnconfirmed.withTierConfirmed().applyBlockedReason)

        val applying = ready.starting()
        assertTrue(applying.applying)
        assertEquals("An apply is already running.", applying.applyBlockedReason)
    }

    @Test
    fun theGalleryFiltersByManifestCategoryAsWellAsBySearch() {
        val gallery = BumpMeshStudioState(
            textures = listOf(
                BumpMeshTextureRef("bumpmesh/library/textures/armor.png", "Armor", "Natural"),
                BumpMeshTextureRef("bumpmesh/library/textures/bark.png", "Bark", "Natural"),
                BumpMeshTextureRef("bumpmesh/library/textures/brick.png", "Brick", "Patterns"),
            ),
        )
        assertEquals(3, gallery.filteredTextures.size)
        assertEquals(listOf("Natural", "Patterns"), gallery.categories)
        assertEquals(2, gallery.withCategory("Natural").filteredTextures.size)
        assertEquals(1, gallery.withCategory("Patterns").filteredTextures.size)
        assertEquals(
            1,
            gallery.withCategory("Natural").withSearch("bark").filteredTextures.size,
        )
        assertEquals(0, gallery.withCategory("Natural").withSearch("brick").filteredTextures.size)
        assertEquals(3, gallery.withCategory(null).filteredTextures.size)
    }

    @Test
    fun replacingTheMeshDropsOrRefusesAStaleMaskInsteadOfUsingIt() {
        val paintedAndFresh = BumpMeshStudioState(
            sourceTriangles = 120,
            texture = texture(),
            wholeObject = false,
            maskTriangles = 10,
            maskMeshTriangles = 120,
            limit = 1_500_000,
        )
        assertNotNull(paintedAndFresh.paintedFraction)
        assertNull(paintedAndFresh.applyBlockedReason)

        // A different mesh: the mask's triangle numbering no longer exists.
        val replaced = paintedAndFresh.copy(sourceTriangles = 200, maskMeshTriangles = 120)
        assertTrue(replaced.maskStale)
        assertNull(replaced.paintedFraction)
        assertTrue(replaced.applyBlockedReason.orEmpty().contains("Paint a region"))
        assertTrue(replaced.maskLifecycleNote.contains("new mesh"))

        // The apply itself clears the mask (apply/undo/import all replace the mesh).
        val finished = paintedAndFresh.finished("Applied Knurling", repair = null)
        assertEquals(0, finished.maskTriangles)
        assertEquals(0, finished.maskMeshTriangles)
        assertTrue(finished.wholeObject)
        assertFalse(finished.applying)
        assertNull(finished.error)
        assertEquals("Applied Knurling", finished.result)
    }

    @Test
    fun failureAndCancellationAndTheEstimateWarningLeaveTheStateUsable() {
        val base = BumpMeshStudioState(
            sourceTriangles = 225_154,
            texture = texture(),
            limit = 1_500_000,
        )
        assertEquals(422_164, base.estimate)
        assertNull("422k is under the Fast ceiling, so there is nothing to warn about", base.estimateWarning)
        val heavy = base.withRefineMm(0.2f)
        assertTrue(
            "above the fast ceiling the estimate warns about the cap/slicing",
            heavy.estimateWarning.orEmpty().contains("above the Fast") ||
                heavy.estimateWarning.orEmpty().contains("slicing can take minutes"),
        )
        val failed = base.failed("the engine refused")
        assertFalse(failed.applying)
        assertEquals("the engine refused", failed.error)
        assertNull(failed.result)
        val cancelled = base.cancelled()
        assertTrue(cancelled.error.orEmpty().contains("Cancelled"))

        // The gallery's search matches the display name and the manifest category.
        val gallery = base.copy(
            textures = listOf(
                texture("Knurling"),
                BumpMeshTextureRef("bumpmesh/library/textures/brick.png", "Brick", "Patterns"),
            ),
        )
        assertEquals(1, gallery.withSearch("knurl").filteredTextures.size)
        assertEquals(1, gallery.withSearch("patterns").filteredTextures.size)
        assertEquals("Patterns", gallery.textures[1].category)
    }
}
