package com.tomppi.enderslicer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Mesh -> STEP sheet: presets and meanings, the quality/unit controls, the honest cost line,
 * the live Cancel and the result/save/open section. Rendering only - the conversion itself is the
 * engine lane's and is tested on the engine side.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w480dp-h900dp")
class MeshToStepSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun state(
        converting: Boolean = false,
        result: MeshToStepResultInfo? = null,
        savedName: String? = null,
        savedUri: String? = null,
        error: String? = null,
        engineNote: String? = null,
    ) = MeshToStepState(
        open = true,
        objectId = "m1",
        sourceName = "cantilever.stl",
        sourceTriangles = 1_928,
        converting = converting,
        stage = if (converting) "tessellating" else null,
        progress = if (converting) 0.4f else 0f,
        stageMessage = if (converting) "reading the mesh" else null,
        result = result,
        savedName = savedName,
        savedUri = savedUri,
        error = error,
    )

    private fun render(
        state: MeshToStepState,
        actions: MeshToStepActions = MeshToStepActions(),
    ) {
        compose.setContent {
            MeshToStepSheet(
                state = state,
                actions = actions,
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun itShowsTheSourceThePresetsTheirMeaningsAndTheCost() {
        render(state())

        compose.onNodeWithText("Mesh -> STEP").assertIsDisplayed()
        compose.onNodeWithText("cantilever.stl · 1,928 triangles").assertIsDisplayed()
        MeshToStepPreset.entries.forEach { preset ->
            compose.onNodeWithText(preset.label).assertExists()
        }
        compose.onNodeWithText(MeshToStepPreset.AUTO.meaning).assertIsDisplayed()
        compose.onNodeWithText("Coarse · fast").assertExists()
        compose.onNodeWithText("Fine · slow").assertExists()
        compose.onNodeWithText("~seconds for this mesh", substring = true).assertExists()
        compose.onNodeWithText("Cancel stays live", substring = true).assertExists()
        compose.onNodeWithText("mm").assertIsDisplayed()
        compose.onNodeWithTag(MESH_TO_STEP_START_TAG).assertIsEnabled()
    }

    @Test
    fun presetAndUnitClicksReachTheHost() {
        val presets = mutableListOf<MeshToStepPreset>()
        val units = mutableListOf<MeshToStepUnit>()
        render(
            state(),
            MeshToStepActions(
                onPreset = { presets += it },
                onUnit = { units += it },
            ),
        )
        compose.onNodeWithText("CAD").performClick()
        assertEquals(listOf(MeshToStepPreset.CAD), presets)
        compose.onNodeWithText("inch").performClick()
        assertEquals(listOf(MeshToStepUnit.INCH), units)
    }

    @Test
    fun thePresetMeaningAndTheExactCautionAreShown() {
        render(state().copy(preset = MeshToStepPreset.EXACT))
        compose.onNodeWithText("Same kind of result as Precise", substring = true).assertExists()
        compose.onNodeWithText("The slowest mode", substring = true).assertExists()
    }

    @Test
    fun smoothIsSelectableAndMapsToTheEnginePreset() {
        val chosen = mutableListOf<MeshToStepPreset>()
        render(state(), MeshToStepActions(onPreset = { chosen += it }))
        compose.onNodeWithText("Smooth").assertIsEnabled()
        compose.onNodeWithText("Smooth").performClick()
        compose.waitForIdle()
        assertEquals(listOf(MeshToStepPreset.SMOOTH), chosen)
    }

    @Test
    fun smoothSelectedShowsTheSlowOrganicMeaningAndCost() {
        render(state().copy(preset = MeshToStepPreset.SMOOTH))
        compose.onNodeWithText(MeshToStepPreset.SMOOTH.meaning).assertExists()
        compose.onNodeWithText(
            MeshToStepState(preset = MeshToStepPreset.SMOOTH).presetCaution.orEmpty(),
        ).assertExists()
        compose.onNodeWithText("~seconds to minutes for this mesh", substring = true).assertExists()
        compose.onNodeWithText("Smooth needs the organic remesher", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun aRunningConversionShowsItsStageAndARealCancel() {
        var cancels = 0
        render(
            state(converting = true),
            MeshToStepActions(onCancel = { cancels++ }),
        )
        compose.onNodeWithText("tessellating · 40%").assertIsDisplayed()
        compose.onNodeWithText("reading the mesh").assertIsDisplayed()
        compose.onNodeWithTag(MESH_TO_STEP_CANCEL_TAG).assertIsDisplayed().performClick()
        assertEquals(1, cancels)
        compose.onNodeWithTag(MESH_TO_STEP_START_TAG).assertDoesNotExist()
    }

    @Test
    fun theResultCarriesFacesValidityAndTheWaysOut() {
        var saves = 0
        render(
            state(
                result = MeshToStepResultInfo(
                    stepPath = "/cache/mesh2step/run/cantilever-cad.step",
                    faces = 31,
                    solids = 1,
                    valid = true,
                    warnings = listOf("closed a small seam"),
                    millis = 8_090,
                ),
            ),
            MeshToStepActions(onSaveStep = { saves++ }),
        )
        compose.onNodeWithText("STEP written").assertIsDisplayed()
        compose.onNodeWithText("31 faces", substring = true).assertExists()
        compose.onNodeWithText("closed a small seam", substring = true).assertExists()
        compose.onNodeWithTag(MESH_TO_STEP_SAVE_TAG)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()
        assertEquals(1, saves)
        compose.onNodeWithTag(MESH_TO_STEP_OPEN_CAD_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Save the STEP first", substring = true).assertExists()
    }

    @Test
    fun aSavedResultCanOpenAsCadAndNamesTheDestination() {
        var opens = 0
        render(
            state(
                result = MeshToStepResultInfo(
                    stepPath = "/cache/mesh2step/run/cantilever-cad.step",
                    faces = 31,
                    valid = false,
                    warnings = listOf("open boundary at x=12"),
                    millis = 20_000,
                ),
                savedName = "cantilever-cad.step",
                savedUri = "content://downloads/42",
            ),
            MeshToStepActions(onOpenAsCad = { opens++ }),
        )
        compose.onNodeWithText("validation issues", substring = true).assertExists()
        compose.onNodeWithText("Saved STEP to cantilever-cad.step.").assertExists()
        compose.onNodeWithTag(MESH_TO_STEP_OPEN_CAD_TAG)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()
        assertEquals(1, opens)
        compose.onNodeWithText("Save again…").assertExists()
    }

    @Test
    fun anEngineFailureIsShownInItsOwnSentence() {
        render(state(error = "the Python payload is missing"))
        compose.onNodeWithText("the Python payload is missing").assertIsDisplayed()
    }
}
