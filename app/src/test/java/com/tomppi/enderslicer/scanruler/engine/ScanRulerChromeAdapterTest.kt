package com.tomppi.enderslicer.scanruler.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRulerChromeAdapterTest {

    @Test
    fun theAdapterKeepsTheNotesAndTheViewport() {
        val script = ScanRulerChromeAdapter.installScript()
        assertTrue(script.contains(ScanRulerChromeAdapter.EMBEDDED_CLASS))
        assertTrue(script.contains(ScanRulerChromeAdapter.STYLE_ID))
        val css = ScanRulerChromeAdapter.CSS
        assertTrue(css.contains("header.top"))
        assertTrue(css.contains("aside.panel > *:not(.group.notes)"))
        assertTrue(css.contains(".group.notes"))
        assertTrue(css.contains(".note-label"))
        assertTrue(css.contains(".notecard"))
        // The desktop 640px floor must not push the 3D stage off a phone-width page.
        assertTrue(css.contains("min-width: 0 !important"))
        assertTrue(css.contains("position: fixed !important"))
        assertTrue(css.contains("max-height: 380px !important"))
        assertTrue(css.contains("html.scanruler-embedded .strip"))
        // The viewport is not hidden.
        assertFalse(css.contains(".stage,\n") && css.contains("display: none !important;\n        html.scanruler-embedded .stage"))
    }

    @Test
    fun theAdapterIsFailSoft() {
        val script = ScanRulerChromeAdapter.installScript()
        assertTrue(script.contains("try {"))
        assertTrue(script.contains("catch (e)"))
        assertTrue(script.contains("console.warn"))
        assertTrue(script.contains("MutationObserver"))
    }

    @Test
    fun thereIsAScriptThatPutsTheFullUiBack() {
        val remove = ScanRulerChromeAdapter.removeScript()
        assertTrue(remove.contains("classList.remove('" + ScanRulerChromeAdapter.EMBEDDED_CLASS + "')"))
        assertTrue(remove.contains("removeChild"))
    }

    @Test
    fun theEmbeddedWorkspaceIsTheReverseWorkspace() {
        assertTrue(ScanRulerChromeAdapter.EMBEDDED_WORKSPACE == "reverse")
    }
}