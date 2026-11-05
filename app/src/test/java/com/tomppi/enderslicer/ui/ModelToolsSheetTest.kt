package com.tomppi.enderslicer.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.PlateObject
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.viewer.MeshFixtures
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The model tools panel: the transform fields' refusals, the disabled actions'
 * reason sentences, and the rotate tally's placement key.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ModelToolsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val printer = PrinterDefinition(
        name = "Modified Ender 3 V2",
        widthMm = 220.0,
        depthMm = 220.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        heatedBed = true,
        heatedBuildVolume = false,
        gcodeFlavor = "Marlin",
        extruders = 1,
        nozzleSizeMm = 0.4,
        filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0,
        printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0,
        printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
    )

    private fun objectOf(id: String, mesh: com.tomppi.enderslicer.viewer.StlMesh): PlateObject =
        PlateObject(
            id = id,
            name = mesh.displayName,
            sourceMesh = mesh,
            mesh = mesh,
            sourcePath = null,
            placement = ModelPlacement.centeredOnBed(mesh, bedWidthMm = 220.0, bedDepthMm = 220.0),
        )

    /** One model on the plate, selected, placed at 100% - the panel's resting case. */
    private fun oneModel(): MainUiState {
        val mesh = MeshFixtures.box(0f, 0f, 0f, 20f, 20f, 20f, name = "box")
        return MainUiState(
            printer = printer,
            models = listOf(objectOf("box", mesh)),
            selectedModelId = "box",
        )
    }

    /** A split pair: two halves, so the snap joint has its pair to go on. */
    private fun twoHalves(): MainUiState {
        val low = MeshFixtures.box(0f, 0f, 0f, 40f, 40f, 20f, name = "part lower")
        val high = MeshFixtures.box(0f, 0f, 20f, 40f, 40f, 40f, name = "part upper")
        return MainUiState(
            printer = printer,
            models = listOf(objectOf("low", low), objectOf("high", high)),
            selectedModelId = "low",
        ).copy(snapLowHalfId = "low", snapHighHalfId = "high")
    }

    private fun tools(
        state: State<MainUiState>,
        onScale: (Double) -> Unit = {},
        onRotate: (ModelPlacement.Axis, Double) -> Unit = { _, _ -> },
        onArrangeNow: () -> Unit = {},
        onOpenSnapUi: () -> Unit = {},
    ) {
        compose.setContent {
            ModelToolsOverlay(
                state = state.value,
                expandedLayout = false,
                plateHeight = 600.dp,
                onSelectModel = {},
                onRemoveModel = {},
                onArrangeNow = onArrangeNow,
                onOpenMultiObjectSettings = {},
                onMove = { _, _, _ -> },
                onRotate = onRotate,
                onScale = onScale,
                onDropToBed = {},
                onLayFlat = {},
                onReset = {},
                onApplyImportedTransform = {},
                onOpenCutUi = {},
                onOpenSnapUi = onOpenSnapUi,
                onOpenSupportPaintUi = {},
                onBrushRadius = {},
                onClearPaint = {},
                dragMove = false,
                onToggleDragMove = {},
                canUndoPlacement = false,
                undoPlacementLabel = null,
                onUndoPlacement = {},
                onClose = {},
            )
        }
    }

    /**
     * Apply scale refuses an entry outside 1-1000% by name - the button used to
     * swallow it silently - and the range is shown with the field.
     */
    @Test
    fun applyScaleRefusesOutOfRangeInputByNameAndShowsTheRange() {
        var scaled = 0.0
        tools(mutableStateOf(oneModel()), onScale = { scaled = it })

        compose.onNodeWithText("Transform").performClick()
        compose.onNodeWithText("Scale: 100%").performClick()
        compose.onNodeWithText("Apply takes a scale between 1% and 1000%.", substring = true)
            .performScrollTo()
            .assertIsDisplayed()

        compose.onNodeWithText("Scale (%)").performScrollTo().performTextReplacement("5000")
        compose.onNodeWithText("Apply scale").performClick()
        compose.onNodeWithText("Scale must be a number between 1% and 1000%; nothing was applied.")
            .assertExists()
        assertEquals("the refused entry is never applied", 0.0, scaled, 1e-9)

        compose.onNodeWithText("Scale (%)").performScrollTo().performTextReplacement("500")
        compose.onNodeWithText("Apply scale").performClick()
        assertEquals(500.0, scaled, 1e-9)
        compose.onNodeWithText("Scale must be a number between 1% and 1000%; nothing was applied.")
            .assertDoesNotExist()
    }

    /** One model is already arranged: the dead button says so. */
    @Test
    fun arrangeSaysWhyThereIsNothingToArrange() {
        var arranged = 0
        tools(mutableStateOf(oneModel()), onArrangeNow = { arranged++ })

        compose.onNodeWithText("Plate").performClick()
        compose.onNodeWithText("Arrange now").assertIsNotEnabled()
        compose.onNodeWithText("Arranging needs more than one model on the plate.").assertIsDisplayed()
        assertEquals(0, arranged)
    }

    /** The snap joint waits for the running operation, and says so while it waits. */
    @Test
    fun theSnapJointSaysWhyItWaits() {
        var opened = 0
        tools(mutableStateOf(twoHalves().copy(isBusy = true)), onOpenSnapUi = { opened++ })

        compose.onNodeWithText("Actions").performClick()
        compose.onNodeWithText("Snap fit joint").assertIsNotEnabled()
        compose.onNodeWithText("Wait for the running operation, then place the joint.").assertIsDisplayed()
        assertEquals(0, opened)
    }
}
