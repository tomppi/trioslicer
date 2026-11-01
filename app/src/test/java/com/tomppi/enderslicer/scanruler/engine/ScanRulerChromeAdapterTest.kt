package com.tomppi.enderslicer.scanruler.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRulerChromeAdapterTest {

    private val css: String get() = ScanRulerChromeAdapter.CSS

    @Test
    fun theNotesUiIsTouchSized() {
        assertTrue(css.contains("width: min(480px, 82vw)"))
        assertTrue(css.contains("max-height: min(420px"))
        assertTrue(css.contains("padding: 14px 16px"))
        assertTrue(css.contains("width: min(420px, 80vw)"))
        assertTrue(css.contains("min-height: 80px"))
        assertTrue(css.contains("font-size: 15px"))
        assertTrue(css.contains("font-size: 14.5px"))
        assertTrue(css.contains("-webkit-line-clamp: 4"))
        assertTrue(css.contains("min-height: 46px"))
        assertTrue(css.contains("min-height: 44px"))
        assertTrue(css.contains("padding: 4px 9px"))
        assertTrue(css.contains("box-sizing: border-box"))
        assertTrue(css.contains("color: #f1f0eb"))
        assertTrue(css.contains("aside.panel:not(:has(.group.notes))"))
    }

    @Test
    fun brickModeSizesTheNotesPanelByItsContent() {
        assertTrue(css.contains("@media (max-width: 600px)"))
        assertTrue(css.contains("height: auto !important"))
        assertTrue(css.contains("max-height: min(360px, calc(var(--sr-vh, 824px) * 0.38))"))
        assertTrue(css.contains("left: 12px !important"))
        assertTrue(css.contains("right: 12px !important"))
        assertTrue(css.contains("bottom: 72px !important"))
        assertTrue(css.contains("overflow-y: auto !important"))
        // This round is only the notes-panel sizing: no tab-bar compaction and
        // no active-tab scrolling were added.
        assertFalse(css.contains("button:not(.on) .wtxt"))
        assertFalse(ScanRulerChromeAdapter.installScript().contains("__scanRulerActiveTab"))
    }

    @Test
    fun normalWidthLayoutIsStillTheOneFrom116() {
        assertTrue(css.contains("width: min(480px, 82vw)"))
        assertTrue(css.contains("max-height: min(420px"))
        // The base panel keeps its lower-left bottom offset; only the narrow
        // query raises it above the agent chip.
        val basePanel = css.substringBefore("@media (max-width: 600px)")
        assertTrue(basePanel.contains("bottom: 12px !important"))
    }

    @Test
    fun theNoteEditorSitsAboveTheOverlayAndTheViewport() {
        assertTrue(css.contains(".chipbar.notecard"))
        assertTrue(css.contains("z-index: 120"))
        assertTrue(css.contains("transform: translate(-50%, -50%)"))
        assertTrue(css.contains("max-height: calc(var(--sr-vh, 824px) * 0.72)"))
    }

    @Test
    fun theWorkspaceTabBarIsKeptAndRestyledForTouch() {
        assertTrue(css.contains("header.top > :not(.modeswitch)"))
        assertTrue(css.contains("header.top > .modeswitch"))
        assertTrue(css.contains("min-height: 46px"))
        assertTrue(css.contains("font-size: 14.5px"))
        assertTrue(css.contains("overflow-x: auto"))
        assertTrue(css.contains(".modeswitch button .wtxt"))
        assertTrue(css.contains("display: inline !important"))
        // The bar itself is not in the hidden list any more.
        assertFalse(css.contains("html.scanruler-embedded header.top,\n"))
        assertFalse(css.contains("html.scanruler-embedded .modeswitch,\n"))
    }

    @Test
    fun chromeIsHiddenButTheViewportAndNotesAreKept() {
        assertTrue(css.contains("header.top > :not(.modeswitch)"))
        assertTrue(css.contains("aside.panel > *:not(.group.notes)"))
        assertTrue(css.contains(".group.notes"))
        assertTrue(css.contains(".note-label"))
        assertTrue(css.contains("html.scanruler-embedded .strip"))
        assertTrue(css.contains("min-width: 0 !important"))
        assertTrue(css.contains("position: fixed !important"))
        assertTrue(css.contains("html.scanruler-embedded .stage"))
        assertFalse(css.contains("html.scanruler-embedded .stage,\n            display: none"))
    }

    @Test
    fun theAdapterIsFailSoftAndTracksTheKeyboardViewport() {
        val script = ScanRulerChromeAdapter.installScript()
        assertTrue(script.contains("try {"))
        assertTrue(script.contains("catch (e)"))
        assertTrue(script.contains("console.warn"))
        assertTrue(script.contains("MutationObserver"))
        assertTrue(script.contains("__scanRulerViewportHook"))
        assertTrue(script.contains("addEventListener('resize'"))
        assertTrue(script.contains("setProperty('--sr-vh'"))
    }

    @Test
    fun thereIsAScriptThatPutsTheFullUiBack() {
        val remove = ScanRulerChromeAdapter.removeScript()
        assertTrue(remove.contains("classList.remove('" + ScanRulerChromeAdapter.EMBEDDED_CLASS + "')"))
        assertTrue(remove.contains("removeChild"))
    }

    @Test
    fun theDefaultWorkspaceIsReverseButItIsNotReasserted() {
        assertTrue(ScanRulerChromeAdapter.EMBEDDED_WORKSPACE == "reverse")
        val session = sessionSource()
        assertFalse(session.contains("restoreEmbeddedWorkspace"))
        assertTrue(session.contains("embeddedWorkspaceInitialized"))
        val mcp = engineSource("ScanRulerMcpServer.kt")
        assertFalse(mcp.contains("afterCommand"))
    }

    private fun engineSource(name: String): String {
        val candidates = listOf(
            java.io.File("src/main/java/com/tomppi/enderslicer/scanruler/engine/" + name),
            java.io.File("app/src/main/java/com/tomppi/enderslicer/scanruler/engine/" + name),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("cannot find " + name)
    }

    private fun sessionSource(): String = engineSource("ScanRulerEmbeddedSession.kt")
}