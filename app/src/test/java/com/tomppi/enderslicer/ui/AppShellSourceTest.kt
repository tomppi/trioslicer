package com.tomppi.enderslicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app shell's wiring contracts, as a source check.
 *
 * These are the shell fixes the component tests cannot reach: state that has to
 * survive a composition recreation, back handlers and tab-change cleanup around
 * the whole screen tree, and two user-facing sentences that name where things
 * live and what was copied.
 */
class AppShellSourceTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/" + relative),
            File("app/src/main/java/com/tomppi/enderslicer/" + relative),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate source file for " + relative + " from " + File(".").absolutePath)
        return file.readText()
    }

    @Test
    fun theModellingStandingBriefSurvivesACompositionRecreation() {
        val app = source("ui/EnderSlicerApp.kt")

        assertTrue(
            "the owed brief is saveable beside the bootstrapped flag",
            app.contains("var modellingIntroPending by rememberSaveable"),
        )
        assertFalse(
            "a rotation between the visit and the delivery dropped the brief for good",
            app.contains("var modellingIntroPending by remember {"),
        )
    }

    @Test
    fun theSidebarPickerHasItsOwnBackHandler() {
        val app = source("ui/EnderSlicerApp.kt")

        assertTrue(
            "back closes the picker and returns to the tab that raised it",
            app.contains("BackHandler(enabled = sidebarPickerOpen) { sidebarPickerOpen = false }"),
        )
    }

    @Test
    fun theTapOwningToolsDoNotSurviveATabChange() {
        val app = source("ui/EnderSlicerApp.kt")
        val tabEffect = app.substring(
            app.indexOf("LaunchedEffect(selectedTab)"),
            app.indexOf("LaunchedEffect(state.sliceResultId"),
        )

        // Their BackHandlers are composed at the top level and stay enabled on
        // every tab: a flag left set swallows the first back press on the other
        // tab and resurrects the tool on return.
        assertTrue(tabEffect.contains("supportPaintUiOpen = false"))
        assertTrue(tabEffect.contains("viewModel.exitFaceSelectMode()"))
        assertTrue(tabEffect.contains("viewModel.setCutActive(false)"))
        assertTrue(tabEffect.contains("viewModel.setSnapActive(false)"))
        assertTrue(
            "the paint overlay clears its own flag when a viewer-mode switch hides it",
            Regex("onDispose \\{\\s*onPaintMode\\(SupportPaintMode.NONE\\)\\s*onClose\\(\\)\\s*\\}")
                .containsMatchIn(app),
        )
    }

    @Test
    fun theTokenToastNamesTheEngine() {
        val app = source("ui/EnderSlicerApp.kt")

        assertFalse(
            "the successful toast names the engine (MCP_TOKEN_SECURITY.md section 6)",
            app.contains("copiedMessage = \"MCP token copied\""),
        )
        assertTrue(app.contains("copiedMessage = \"MCP token for \" + engineName + \" copied\""))
        // The cancellation and unavailability sentences the section names are not
        // part of this fix and stay as they are.
        assertTrue(app.contains("cancelledMessage = \"MCP token not copied\""))
        assertTrue(app.contains("unavailableMessage = \"The MCP token is not available yet\""))
    }

    @Test
    fun theEndpointHintPointsAtARealSurface() {
        val app = source("ui/EnderSlicerApp.kt")

        assertFalse(
            "no string may point at a settings surface that does not exist",
            app.contains("ScanRuler agent settings"),
        )
        assertTrue(app.contains("inAppEndpointHint = \"Set the endpoint in AI settings (More -> Configuration).\""))
    }

    @Test
    fun theAgentChipStaysOneLineAndInsideItsCorner() {
        val workspace = source("ui/ScanRulerWorkspace.kt")

        // The notes panel's 72px bottom offset clears a one-line chip (1.16.1),
        // and a long scan name ellipsizes instead of growing over the panel.
        assertTrue(workspace.contains("maxLines = 1,"))
        assertTrue(workspace.contains("overflow = TextOverflow.Ellipsis,"))
        assertTrue(workspace.contains("widthIn(max = 280.dp)"))
    }
}
