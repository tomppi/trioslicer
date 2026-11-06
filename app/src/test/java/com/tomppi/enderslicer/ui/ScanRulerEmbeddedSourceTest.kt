package com.tomppi.enderslicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The 1.15.0 Plate <-> ScanRuler workspace as a source contract.
 *
 * The engine lane owns the session, the loopback servers, the page and the agent host; this test
 * pins the UI's half: one switch in the plate, one hosted WebView that is hidden (not destroyed)
 * while Plate works, a named loading/error/empty plate sentence, the plate-mesh handoff on the
 * first entry, and the in-app agent following the selected workspace.
 */
class ScanRulerEmbeddedSourceTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/" + relative),
            File("app/src/main/java/com/tomppi/enderslicer/" + relative),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to locate " + relative)
    }

    private val app by lazy { source("ui/EnderSlicerApp.kt") }
    private val workspace by lazy { source("ui/ScanRulerWorkspace.kt") }
    private val vm by lazy { source("ui/MainViewModel.kt") }
    private val state by lazy { source("ui/MainUiState.kt") }

    @Test
    fun thePlateDrawsTheSwitchAndOnePersistentHost() {
        assertTrue(app.contains("PlateWorkspaceSwitch("))
        assertTrue(app.contains("onViewChange = viewModel::setPlateView"))
        assertTrue(app.contains("ScanRulerEmbeddedHost("))
        assertTrue(app.contains("active = state.activeView == PlateView.SCAN_RULER"))
        assertTrue(app.contains("session = viewModel.scanRulerSessionOrNull()"))
        assertTrue(app.contains("ensureScanRulerViewEntered"))
        assertTrue("the plate's bottom bar gives way to the viewport", app.contains("state.activeView == PlateView.PLATE"))
    }

    @Test
    fun theHostHidesInsteadOfDestroyingAndNeverBuildsASecondWebView() {
        assertTrue(workspace.contains("AndroidView("))
        assertTrue("the engine's one WebView instance is hosted, not created here", workspace.contains("factory = { session.webView }"))
        assertTrue(workspace.contains("key(session)"))
        assertTrue("hidden: 1dp and transparent, still composed", workspace.contains("size(1.dp).alpha(0f)"))
        assertTrue(workspace.contains("session?.setVisible(active)"))
        assertFalse("no second WebView implementation", workspace.contains("WebView(this"))
        assertFalse(workspace.contains("WebView(context"))
        assertFalse("no reload on switch", workspace.contains("loadUrl(") || workspace.contains("reload()"))
    }

    @Test
    fun theViewShowsLoadingErrorRetryAndTheEmptyPlateSentence() {
        assertTrue(workspace.contains("Retry"))
        assertTrue(workspace.contains("Preparing ScanRuler"))
        assertTrue(workspace.contains("phase == ScanRulerPhase.FAILED"))
        assertTrue(workspace.contains("onRetry"))
        assertTrue(workspace.contains("!hasMesh"))
        assertTrue(workspace.contains("Import a model"))
        assertTrue(vm.contains("scanRulerError"))
        assertTrue(vm.contains("retryScanRulerView"))
        assertTrue(vm.contains("is empty - import or select a model first"))
        assertTrue(vm.contains("loading = state.scanRulerLoading") || app.contains("loading = state.scanRulerLoading"))
        assertTrue(app.contains("error = state.scanRulerError"))
    }

    @Test
    fun enteringScanRulerLoadsThePlateMeshAndShowsTheEmbeddedWorkspace() {
        assertTrue(vm.contains("StlMeshWriter.writeBinary(model.mesh"))
        assertTrue(vm.contains("session.loadScan(staged)"))
        assertTrue(vm.contains("session.showEmbeddedWorkspace()"))
        assertTrue(vm.contains("scanRulerLoadedSignature"))
        assertTrue("a switch does not reload the same mesh", vm.contains("if (signature != scanRulerLoadedSignature)"))
        assertTrue(vm.contains("state.value.phase == com.tomppi.enderslicer.scanruler.engine.ScanRulerPhase.IDLE"))
        assertTrue(vm.contains("setVisible(false)"))
        assertTrue(vm.contains("setVisible(true)"))
        assertTrue(state.contains("val scanRulerLoading: Boolean = false"))
        assertTrue(state.contains("val scanRulerError: String? = null"))
    }

    @Test
    fun theInAppAgentFollowsTheSelectedEngine() {
        assertTrue(app.contains("val engine = viewModel.agentEngine"))
        assertTrue(
            "the ScanRuler engine needs its workspace",
            app.contains("scanRulerTurn = engine == AgentEngine.SCANRULER"),
        )
        assertTrue(app.contains("viewModel.scanRulerSessionOrNull()"))
        assertTrue(app.contains("live.toolDefinitions()"))
        assertTrue(app.contains("live.agentHost"))
        assertTrue(app.contains("live.agentInstructions"))
        assertTrue(app.contains("includePlateScreenshot = scanRulerTurn"))
        assertTrue(app.contains("scanruler-not-ready"))
        assertTrue("the wire engines bind their own hosts", app.contains("WireEngineAgentHost(surface)"))
        assertTrue("the agent is told which engine it is driving", app.contains("AgentEngineContext.instructions"))
        assertTrue(
            "only SolidCraft turns claim the CAD document",
            app.contains("if (engine == AgentEngine.SOLIDCRAFT) {") && app.contains("ownership.beginInAppTurn()"),
        )
        assertTrue("a switch resets the cached agent for the next context", vm.contains("inAppCadAgent = null"))
        assertTrue("the agent can run a non-SolidCraft prompt", source("cadagent/CadAgent.kt").contains("systemPromptOverride"))
    }

    @Test
    fun theRailPlusStepsAsideOnlyInScanRulerView() {
        assertTrue("the switch is wired to the persisted active view", app.contains("showAddSettings = state.activeView == PlateView.PLATE"))
        assertTrue("the + is composed only while the plate owns the corner", app.contains("if (showAddSettings)"))
        assertTrue("the rail's default keeps every other caller unchanged", app.contains("showAddSettings: Boolean = true"))
        assertTrue(app.contains("SESSION_RAIL_ADD_TAG"))
        // The View switch itself is never conditional on the agent view.
        assertTrue(app.contains("PlateWorkspaceSwitch("))
        assertTrue(app.contains("onViewChange = viewModel::setPlateView"))
        // The engine's floating tab bar reserves content-top 0-48dp: the card is 40dp at 6dp.
        assertTrue(app.contains(".padding(top = 6.dp)"))
        // The notes panel owns the bottom-left, so no plate-only row is drawn there.
        assertFalse(app.contains("RailButtonLabel(\"Texture surface\")"))
        assertFalse(app.contains("onTextureSurface"))
    }

    @Test
    fun theAskAiSurfaceNamesTheSelectedEngine() {
        val overlay = source("ui/AiChatOverlay.kt")
        assertTrue(overlay.contains("inAppLabel: String = ChatSource.IN_APP.label"))
        assertTrue(overlay.contains("inAppComposerHint: String = \"Ask for a CAD change\""))
        assertTrue(overlay.contains("inAppEmptyLine: String"))
        assertTrue(overlay.contains("inAppEndpointHint: String"))
        assertTrue("the engine menu owns the selection", overlay.contains("onSelectEngine: (AgentEngine) -> Unit"))
        assertTrue(
            "engine readiness is polled into live state - a boot finishes without a recomposition",
            app.contains("engineReadinessSnapshot") && app.contains("ENGINE_READINESS_POLL_MILLIS"),
        )
        assertTrue("the door menu carries the universal MCP", overlay.contains("doorMenu: AgentDoorMenu?"))
        assertTrue("the source chip uses the caller's label", overlay.contains("if (candidate == ChatSource.IN_APP) inAppLabel else candidate.label"))
        assertTrue("the caller names the agent from the selected engine", app.contains("val selectedEngine = viewModel.agentEngine"))
        assertTrue(app.contains("val inAppAgentName = selectedEngine.label + \" agent\""))
        assertTrue(app.contains("onSelectEngine = viewModel::selectAgentEngine"))
        assertTrue(app.contains("inAppLabel = inAppAgentName + \" (in-app)\"") || app.contains("inAppLabel = inAppAgentName + \" (in-app)\","))
        assertTrue(app.contains("Ask the ScanRuler agent"))
        // The endpoint editor is inside the SolidCraft settings - there is no separate
        // "ScanRuler agent settings" surface, and the hint may not name one.
        assertTrue(
            "the endpoint hint points at the sheet that holds the editor",
            app.contains("Set the endpoint in AI settings (More -> Configuration)."),
        )
        assertTrue("the clipboard label follows the same name", app.contains("copyChatTranscript(text, inAppAgentName)"))
    }

    @Test
    fun theFullWebViewEntryFromOneFourteenIsUntouched() {
        val activity = source("scanruler/ScanRulerActivity.kt")
        assertTrue(activity.contains("WebViewAssetLoader.Builder()"))
        assertTrue(activity.contains("/assets/scanruler/index.html"))
        assertTrue(app.contains("launchScanRuler"))
        assertTrue(app.contains("onScanRuler = ::launchScanRuler"))
    }
}
