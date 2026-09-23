package com.tomppi.enderslicer.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.smartinfill.FilaSimBoundaryCondition
import com.tomppi.enderslicer.smartinfill.FilaSimCheckReport
import com.tomppi.enderslicer.smartinfill.FilaSimComponent
import com.tomppi.enderslicer.smartinfill.FilaSimConfiguration
import com.tomppi.enderslicer.smartinfill.FilaSimGoal
import com.tomppi.enderslicer.smartinfill.FilaSimMaterialPresets
import com.tomppi.enderslicer.smartinfill.FilaSimOptimization
import com.tomppi.enderslicer.smartinfill.FilaSimOptimizeOptions
import com.tomppi.enderslicer.smartinfill.FilaSimSolveReport
import com.tomppi.enderslicer.smartinfill.SmartInfillCondition
import com.tomppi.enderslicer.smartinfill.SmartInfillPhase
import com.tomppi.enderslicer.smartinfill.SmartInfillUiState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import org.robolectric.annotation.Config

/**
 * The panel on a JVM: Robolectric renders the Compose tree, so the wiring the
 * device would exercise — which button starts what, which state disables the
 * actions, what the run readouts say — is covered without a phone.
 */
@RunWith(AndroidJUnit4::class)
// SDK 35, not 36: Robolectric only drives 36/37 on a Java 21 test JVM, and this
// module's unit tests run on 17. 35 still exercises the Compose and Android APIs
// the panel touches, and the app's minSdk is 29.
@Config(sdk = [35])
class SmartInfillWorkbenchPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val added = mutableListOf<FilaSimBoundaryCondition>()
    private var starts = 0
    private var armed: Long? = null
    private var stops = 0

    private fun show(
        state: SmartInfillUiState,
        starting: Boolean = false,
        enabled: Boolean = true,
        onUpdate: (Long, FilaSimBoundaryCondition) -> Unit = { _, _ -> },
        onConfiguration: (FilaSimConfiguration) -> Unit = {},
        onOptions: (FilaSimOptimizeOptions) -> Unit = {},
    ) {
        compose.setContent {
            SmartInfillWorkbenchPanel(
                state = state,
                packageValue = null,
                starting = starting,
                enabled = enabled,
                onStart = { starts++ },
                onAddCondition = { added += it },
                onArmPicking = { armed = it },
                onRemoveCondition = {},
                onExpandToSurface = {},
                onUpdateCondition = onUpdate,
                onSpotSize = {},
                onConfiguration = onConfiguration,
                onOptions = onOptions,
                onCheck = {},
                onSolve = {},
                onOptimize = {},
                onStop = { stops++ },
                onApply = {},
                onRemovePackage = {},
                onOpenWorkspace = {},
                onClose = {},
            )
        }
    }

    private fun condition(id: Long, triangles: IntArray = intArrayOf(0, 1)) = SmartInfillCondition(
        id = id,
        condition = FilaSimBoundaryCondition.Fixed(triangles),
    )

    @Test
    fun withoutASessionThePanelOffersToOpenOne() {
        show(SmartInfillUiState(hasSession = false, modelName = "cube.stl"))
        compose.onNodeWithText("Open the analysis").performClick()
        assertEquals(1, starts)
    }

    @Test
    fun whilePreparingItSaysSoInsteadOfOfferingTheButton() {
        show(SmartInfillUiState(hasSession = false, modelName = "cube.stl"), starting = true)
        compose.onNodeWithText("Preparing the model for the analysis", substring = true).assertExists()
    }

    @Test
    fun theQuickAddButtonsBuildTheConditionsTheyName() {
        show(SmartInfillUiState(modelName = "cube.stl"))
        compose.onNodeWithText("Fixed").performClick()
        compose.onNodeWithText("Force 100 N", substring = true).performClick()

        assertEquals(2, added.size)
        val fixed = added[0] as FilaSimBoundaryCondition.Fixed
        assertTrue("a new condition starts unpicked", fixed.triangles.isEmpty())
        val force = added[1] as FilaSimBoundaryCondition.Force
        assertEquals(listOf(0.0, 0.0, -100.0), force.vector)
    }

    @Test
    fun aRunningSessionDisablesEveryEdit() {
        show(
            SmartInfillUiState(
                modelName = "cube.stl",
                phase = SmartInfillPhase.BUSY,
                conditions = listOf(condition(1)),
            ),
        )
        compose.onNodeWithText("Fixed").assertIsNotEnabled()
        compose.onNodeWithText("Force 100 N", substring = true).assertIsNotEnabled()
    }

    @Test
    fun theSessionLineReportsTheGridItBuilt() {
        show(SmartInfillUiState(modelName = "cube.stl"))
        compose.onNodeWithText("cube.stl").assertExists()
        compose.onNodeWithText("Boundary conditions").assertExists()
        compose.onNodeWithText("Add the supports the part rests on", substring = true).assertExists()
    }

    @Test
    fun anArmedConditionTellsTheUserWhatToTap() {
        show(
            SmartInfillUiState(
                modelName = "cube.stl",
                conditions = listOf(condition(7)),
                pickingConditionId = 7,
            ),
        )
        compose.onNodeWithText("Tap the surface that", substring = true).assertExists()
    }

    @Test
    fun aFailedCheckSaysWhyInsteadOfNothing() {
        show(
            SmartInfillUiState(
                modelName = "cube.stl",
                conditions = listOf(condition(1)),
                check = FilaSimCheckReport(
                    ok = false,
                    islandCount = 2,
                    components = listOf(
                        FilaSimComponent(
                            cells = 100,
                            constrained = false,
                            lambdaRatio = 0.01,
                            hasLoads = false,
                            rigidModeTranslation = null,
                        ),
                    ),
                ),
            ),
        )
        compose.onNodeWithText("cannot be optimized yet", substring = true).assertExists()
        compose.onNodeWithText("add the supports and the load", substring = true).assertExists()
    }

    @Test
    fun aSolveReportsWhatItCost() {
        show(
            SmartInfillUiState(
                modelName = "cube.stl",
                conditions = listOf(condition(1)),
                solve = FilaSimSolveReport(
                    iterations = 27,
                    relativeResidual = 9.1e-6,
                    converged = true,
                    maxDisplacementMm = 0.00478,
                    tolerance = 1e-5,
                ),
            ),
        )
        compose.onNodeWithText("27 iterations", substring = true).assertExists()
        compose.onNodeWithText("max displacement", substring = true).assertExists()
    }

    @Test
    fun theResultLegendListsEveryDensityBin() {
        val summary = JSONObject()
            .put("baseDensity", 0.10)
            .put("meanInfill", 0.249)
            .put("targetInfill", 0.25)
            .put("massGrams", 1.72)
            .put("massSolidGrams", 3.57)
            .put("maxDisplacement", 0.017)
            .put("uniformMaxDisp", 0.020)
            .put("solidMaxDisp", 0.005)
            .put("iterations", 14)
            .put("converged", true)
            .put(
                "bins",
                JSONArray()
                    .put(JSONObject().put("density", 0.10).put("cells", 23235))
                    .put(JSONObject().put("density", 0.18).put("cells", 15818)),
            )
        val optimization = FilaSimOptimization(
            summary = summary,
            options = FilaSimOptimizeOptions(),
            regions = emptyList(),
            solid = false,
        )
        show(
            SmartInfillUiState(
                modelName = "cube.stl",
                conditions = listOf(condition(1)),
                optimization = optimization,
            ),
        )
        compose.onNodeWithText("Infill by density", substring = true).assertExists()
        compose.onNodeWithText("23235 cells", substring = true).assertExists()
        compose.onNodeWithText("15818 cells", substring = true).assertExists()
    }

    /**
     * A numeric field has to survive being typed into. Every keystroke commits a
     * value and the panel re-renders from it, so the field must keep the keystrokes
     * while it has focus: without that, typing "3,5" into the tap radius rewrites
     * the "3" to "3.00" and the rest lands on top of it, committing nothing.
     */
    @Test
    fun typingIntoANumericFieldKeepsTheKeystrokes() {
        val state = mutableStateOf(SmartInfillUiState(modelName = "cube.stl"))
        var committed = 0.0
        compose.setContent {
            SmartInfillWorkbenchPanel(
                state = state.value,
                packageValue = null,
                starting = false,
                enabled = true,
                onStart = {},
                onAddCondition = {},
                onArmPicking = {},
                onRemoveCondition = {},
                onExpandToSurface = {},
                onUpdateCondition = { _, _ -> },
                onSpotSize = { value ->
                    committed = value
                    state.value = state.value.copy(spotSizeMm = value)
                },
                onConfiguration = {},
                onOptions = {},
                onCheck = {},
                onSolve = {},
                onOptimize = {},
                onStop = {},
                onApply = {},
                onRemovePackage = {},
                onOpenWorkspace = {},
                onClose = {},
            )
        }
        val field = compose.onNodeWithText("Tap radius mm").performScrollTo()
        field.performClick()
        field.performTextReplacement("")
        field.performTextInput("3")
        field.performTextInput(",")
        field.performTextInput("5")

        val typed = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("the field keeps the keystrokes", "3,5", typed)
        assertEquals(3.5, committed, 1e-9)
    }

    private fun forceState(id: Long = 1) = SmartInfillUiState(
        modelName = "cube.stl",
        conditions = listOf(
            SmartInfillCondition(
                id = id,
                condition = FilaSimBoundaryCondition.Force(
                    intArrayOf(0, 1, 2),
                    listOf(0.0, 0.0, -100.0),
                ),
            ),
        ),
    )

    @Test
    fun aForceVectorIsEditableComponentByComponent() {
        val updates = mutableListOf<FilaSimBoundaryCondition>()
        show(forceState(), onUpdate = { _, condition -> updates += condition })
        compose.onNodeWithText("Y N").performScrollTo().performTextReplacement("-42,5")

        val force = updates.last() as FilaSimBoundaryCondition.Force
        assertEquals(listOf(0.0, -42.5, -100.0), force.vector)
        assertEquals(listOf(0, 1, 2), force.triangles.toList())
    }

    @Test
    fun nonFiniteInputNeverReachesTheEngine() {
        val updates = mutableListOf<FilaSimBoundaryCondition>()
        show(forceState(), onUpdate = { _, condition -> updates += condition })
        compose.onNodeWithText("Y N").performScrollTo().performTextReplacement("NaN")

        assertTrue("a non-finite value must not be sent: $updates", updates.isEmpty())
    }

    @Test
    fun aMaterialPresetSendsItsValues() {
        val configurations = mutableListOf<FilaSimConfiguration>()
        show(
            SmartInfillUiState(modelName = "cube.stl"),
            onConfiguration = { configurations += it },
        )
        // Semantics click, not a touch: the preset row scrolls horizontally, so a
        // tap through the vertical scroll would be aimed at a clipped node. The
        // values that reach the engine are what this test is about.
        compose.onNodeWithText("PLA").performSemanticsAction(SemanticsActions.OnClick)

        val pla = FilaSimMaterialPresets.ALL.first { it.name == "PLA" }
        val sent = configurations.single()
        assertEquals(pla.youngsModulusMpa, sent.youngsModulusMpa!!, 1e-9)
        assertEquals(pla.poisson, sent.poisson!!, 1e-9)
        assertEquals(pla.densityGramsPerCm3, sent.densityGramsPerCm3!!, 1e-9)
        assertEquals(pla.strengthMpa, sent.strengthMpa!!, 1e-9)
    }

    @Test
    fun theGoalButtonsSendTheGoalTheyName() {
        val options = mutableListOf<FilaSimOptimizeOptions>()
        show(SmartInfillUiState(modelName = "cube.stl"), onOptions = { options += it })
        compose.onNodeWithText("Safety factor").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(FilaSimGoal.STRENGTH, options.single().goal)
    }

    /**
     * A fold gets one sheet cut down the middle: what acts on the part on the
     * left, what is asked of the optimizer and what it answered on the right.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h800dp")
    fun aFoldableSplitsTheWorkflowInTwoColumns() {
        show(SmartInfillUiState(modelName = "cube.stl", conditions = listOf(condition(1))))

        compose.onNodeWithText("Smart Infill · native engine").assertIsDisplayed()
        compose.onNodeWithText("Setup").assertIsDisplayed()
        compose.onNodeWithText("Run and results").assertIsDisplayed()
        // Whichever column a section lands in, it lands there once.
        compose.onAllNodesWithText("Boundary conditions").assertCountEquals(1)
        compose.onAllNodesWithText("Material").assertCountEquals(1)
        compose.onAllNodesWithText("Goal").assertCountEquals(1)
        compose.onAllNodesWithText("Check").assertCountEquals(1)
    }

    /**
     * The split view itself: one column against each side of the plate, both the
     * same height, meeting at the divider rather than floating apart as two
     * cards of different sizes.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h800dp")
    fun theTwoColumnsSplitTheWidthAndShareOneHeight() {
        show(SmartInfillUiState(modelName = "cube.stl"))

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val setup = compose.onNodeWithTag(SETUP_PANE_TAG).fetchSemanticsNode().boundsInRoot
        val run = compose.onNodeWithTag(RUN_PANE_TAG).fetchSemanticsNode().boundsInRoot

        assertTrue("setup is the left column, its edge was ${setup.left}", setup.left < root.width / 4f)
        assertTrue(
            "run is the right column, its edge was ${run.right} of ${root.width}",
            run.right > root.width * 3f / 4f,
        )
        assertTrue(
            "the columns meet at the divider, they were ${setup.right} and ${run.left}",
            run.left - setup.right < 40f,
        )
        assertTrue(
            "both columns are one height, they were ${setup.height} and ${run.height}",
            abs(setup.height - run.height) < 2f,
        )
        assertTrue(
            "the split fills its own band, it was ${setup.height} of ${root.height}",
            setup.height > root.height * 0.3f,
        )
    }

    /** A phone, and any window under 600dp, keeps the single card. */
    @Test
    fun aPhoneKeepsOneCard() {
        show(SmartInfillUiState(modelName = "cube.stl"))

        compose.onNodeWithText("Smart Infill · native engine").assertExists()
        compose.onNodeWithText("Setup").assertDoesNotExist()
        compose.onNodeWithText("Run and results").assertDoesNotExist()
    }
}