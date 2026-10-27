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
    fun theRegionMaskIsTheStudiosOwnSetAndOutOfRangeIndicesAreDropped() {
        // A support paint that deliberately does NOT become a texture mask: the studio has its
        // own set, so a user's enforcers are not silently textured.
        val painted = setOf(0, 2, 4, 99)
        val mask = textureRegionMaskOf(painted, meshTriangles = 5)
        assertNotNull(mask)
        assertEquals(listOf(true, false, true, false, true), mask!!.toList())
        // 99 is out of range for a 5-triangle mesh and never reaches the engine.
        assertNull(textureRegionMaskOf(emptySet(), 5))
        assertNull(textureRegionMaskOf(painted, 0))
    }

    @Test
    fun aSupportPaintWithBlockerTrianglesCreatesNoTextureRegion() {
        val support = SupportPaintState(
            enforcerTriangles = setOf(3),
            blockerTriangles = setOf(0, 1),
        )
        val model = plateObject(triangles = 40, paint = support)
        val studio = bumpMeshStudioOpened(model, 1_500_000, listOf(texture()))
        assertEquals("the studio opens with no region of its own", 0, studio.maskTriangles)
        assertNull(studio.paintedFraction)
        assertTrue(studio.wholeObject)
        // The user's support paint is exactly what it was.
        assertEquals(support, model.supportPaint)
    }

    @Test
    fun paintingATextureRegionLeavesTheSupportPaintByteIdentical() {
        val supportBefore = SupportPaintState(
            enforcerTriangles = setOf(1, 4),
            blockerTriangles = setOf(2, 7),
            brushRadiusMm = 4.5,
        )
        val model = plateObject(triangles = 40, paint = supportBefore)

        // Paint a texture region on the studio's own store...
        val studio = bumpMeshStudioOpened(model, 1_500_000, listOf(texture()))
            .withWholeObject(false)
            .withRegionStroke(setOf(10, 11, 12), erase = false)
        // ...and apply the texture through the same replacement the view model uses.
        val replaced = plateObjectWithTextureMesh(model, mesh(99, "textured.stl"))

        assertEquals("support paint survives the region paint", supportBefore, model.supportPaint)
        assertEquals("support paint survives the apply", supportBefore, replaced.supportPaint)
        assertEquals("the region lives only in the studio", 3, studio.maskTriangles)
        assertTrue(replaced.supportPaint.enforcerTriangles == setOf(1, 4))
        assertTrue(replaced.supportPaint.blockerTriangles == setOf(2, 7))
        assertEquals(4.5, replaced.supportPaint.brushRadiusMm, 0.0)

        // ...and the other way round: support painting never writes the studio region.
        val studioAfterSupport = studio.withRegionStroke(setOf(4, 5), erase = false)
        assertEquals(5, studioAfterSupport.maskTriangles)
        assertEquals(supportBefore, model.supportPaint)
    }

    @Test
    fun theRegionBrushDrawsAndErasesAndTheCountFollows() {
        val studio = BumpMeshStudioState(sourceTriangles = 50, objectId = "m1")
        val drawn = studio.withRegionStroke(setOf(1, 2, 3), erase = false)
        assertEquals(3, drawn.maskTriangles)
        assertEquals(3, drawn.paintedTriangles.size)
        val more = drawn.withRegionStroke(setOf(3, 4), erase = false)
        assertEquals(setOf(1, 2, 3, 4), more.paintedTriangles)
        val erased = more.withRegionStroke(setOf(2), erase = true)
        assertEquals(setOf(1, 3, 4), erased.paintedTriangles)
        // Out-of-range triangles are dropped rather than wrapped onto another face.
        assertEquals(emptySet<Int>(), studio.withRegionStroke(setOf(50, -1), erase = false).paintedTriangles)
        // The brush choice is the bar's, and a stroke does not change the support paint.
        assertEquals(TextureRegionBrush.ERASE, studio.withRegionBrush(TextureRegionBrush.ERASE).regionBrush)
    }

    @Test
    fun doneBringsTheStudioBackWithTheRegionKept() {
        val painting = BumpMeshStudioState(
            open = false,
            painting = true,
            sourceTriangles = 100,
            objectId = "m1",
            texture = texture(),
            wholeObject = true,
        ).withRegionStroke(setOf(1, 2, 3), erase = false)
        val done = bumpMeshStudioAfterPaintDone(painting)
        assertTrue(done.open)
        assertFalse(done.painting)
        assertEquals(3, done.maskTriangles)
        assertFalse("Done reopens on the painted region", done.wholeObject)
        assertNull("Apply is enabled after Done", done.applyBlockedReason)
        // An empty region reopens on Whole object.
        val emptyDone = bumpMeshStudioAfterPaintDone(painting.copy(paintedTriangles = emptySet()))
        assertTrue(emptyDone.wholeObject)
    }

    @Test
    fun theRegionOverlayIsSceneIndicesOffsetByTheObjectsBeforeIt() {
        val first = plateObject(id = "a", triangles = 10)
        val second = plateObject(id = "b", triangles = 20)
        val tint = textureRegionSceneTint(
            models = listOf(first, second),
            objectId = "b",
            paintedTriangles = setOf(0, 5, 19),
            painting = true,
        )
        assertNotNull(tint)
        assertEquals(listOf(10, 15, 29), tint!!.toList())
        // Only while painting, only for a known object.
        assertNull(textureRegionSceneTint(listOf(first, second), "b", setOf(0), painting = false))
        assertNull(textureRegionSceneTint(listOf(first), "gone", setOf(0), painting = true))
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
    fun openingTheStudioReadsTheSelectedMeshLimitAndObject() {
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
        assertEquals("m1", state.objectId)
        assertEquals(0, state.maskTriangles)
        assertEquals(40, state.maskMeshTriangles)
        assertEquals(1_500_000, state.limit)
        assertEquals(texture(), state.texture)
        assertTrue("Whole object is the default", state.wholeObject)

        // A region painted earlier rides back in only while object and mesh still match.
        val withRegion = state.withWholeObject(false).withRegionStroke(setOf(1, 3), erase = false)
        val reopened = bumpMeshStudioOpened(model, 1_500_000, listOf(texture()), existing = withRegion)
        assertEquals(2, reopened.maskTriangles)
        assertFalse(reopened.wholeObject)
        val otherObject = bumpMeshStudioOpened(
            plateObject(id = "m2", triangles = 40), 1_500_000, listOf(texture()), existing = withRegion,
        )
        assertEquals("a different object drops the region", 0, otherObject.maskTriangles)
        val otherMesh = bumpMeshStudioOpened(
            plateObject(triangles = 41), 1_500_000, listOf(texture()), existing = withRegion,
        )
        assertEquals("a different mesh drops the region", 0, otherMesh.maskTriangles)
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

        val paintedWithMask = paintedWithoutMask.withRegionStroke((0 until 10).toSet(), erase = false)
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
    fun replacingTheMeshDropsOrRefusesAStaleRegionInsteadOfUsingIt() {
        val paintedAndFresh = BumpMeshStudioState(
            sourceTriangles = 120,
            objectId = "m1",
            texture = texture(),
            wholeObject = false,
            paintedTriangles = (0 until 10).toSet(),
            maskMeshTriangles = 120,
            limit = 1_500_000,
        )
        assertNotNull(paintedAndFresh.paintedFraction)
        assertNull(paintedAndFresh.applyBlockedReason)

        // A different mesh: the region's triangle numbering no longer exists, and the refusal
        // names that instead of asking for a paint that is already there.
        val replaced = paintedAndFresh.copy(sourceTriangles = 200, maskMeshTriangles = 120)
        assertTrue(replaced.maskStale)
        assertNull(replaced.paintedFraction)
        assertTrue(replaced.applyBlockedReason.orEmpty().contains("paint it again"))
        assertTrue(replaced.maskLifecycleNote.contains("new mesh"))

        // The apply itself clears the region (apply/undo/import all replace the mesh).
        val finished = paintedAndFresh.finished("Applied Knurling", repair = null)
        assertEquals(0, finished.maskTriangles)
        assertEquals(0, finished.maskMeshTriangles)
        assertTrue(finished.wholeObject)
        assertFalse(finished.painting)
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
