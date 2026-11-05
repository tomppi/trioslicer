package com.tomppi.enderslicer.ui.klipper

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The klipper-UI fix round (bug-audit 2026-11-04, Area 4), as source contracts on the screens
 * that own each item. Each test names the audit item it pins; the KAMP value parsing itself is
 * held by KlipperKampTabTest, and the Limits card's file choice by KlipperLimitsFileTest.
 */
class KlipperUiFixSourceTest {

    private val mesh = source("ui/klipper/KlipperMeshTab.kt")
    private val zprobe = source("ui/klipper/KlipperZProbeTab.kt")
    private val kamp = source("ui/klipper/KlipperKampTab.kt")
    private val dashboard = source("ui/klipper/KlipperDashboardTab.kt")
    private val screen = source("ui/klipper/KlipperScreen.kt")
    private val temperatures = source("ui/klipper/KlipperTemperaturesTab.kt")

    @Test
    fun kui01TheMeshTabRefusesHomingAndProbingWhilePrinting() {
        assertTrue(
            "the mesh tab holds the Move tab's motionAllowed rule",
            mesh.contains("val motionAllowed = state.isReady && !state.isPrinting"),
        )
        assertTrue(
            "Home first is gated on it",
            mesh.contains("KlipperButton(\"Home first\", enabled = motionAllowed)"),
        )
        assertTrue(
            "and so is Probe the bed, behind its own homed precondition",
            mesh.contains("KlipperButton(\"Probe the bed\", enabled = motionAllowed && state.isHomed)"),
        )
    }

    @Test
    fun kui02TheZProbeTabRefusesHomingAndCalibratingWhilePrinting() {
        assertTrue(
            "the Z probe tab holds the Move tab's motionAllowed rule",
            zprobe.contains("val motionAllowed = state.isReady && !state.isPrinting"),
        )
        assertTrue(
            "Home is gated on it",
            zprobe.contains("KlipperButton(\"Home\", enabled = motionAllowed, onClick = onHome)"),
        )
        assertTrue(
            "and so is Start calibrating, behind its own homed precondition",
            zprobe.contains("enabled = motionAllowed && state.isHomed"),
        )
    }

    @Test
    fun kui03TheKampFieldsSendParsedNumbersNeverRawText() {
        assertFalse(
            "no field forwards its raw text into the command",
            kamp.contains("VALUE=\$text"),
        )
        assertTrue(
            "the command carries the parsed value",
            kamp.contains("VALUE=\$value"),
        )
        assertTrue(
            "and the send button is the parser's decision",
            kamp.contains("enabled = kampValue(text) != null"),
        )
    }

    @Test
    fun kui04TheLimitsCardReadsThePrintingFileAndNamesNoFileAsNone() {
        assertTrue(
            "the card chooses its file through limitsFile",
            dashboard.contains("val file = limitsFile("),
        )
        assertTrue(
            "with the print's own file while a print runs",
            dashboard.contains("printing = state.isPrinting || state.isPaused"),
        )
        assertTrue(
            "and a card with nothing to read says so instead of claiming what a file sets",
            dashboard.contains(
                "?: if (file == null) \"no file is loaded\" else \"none set in the file\"",
            ),
        )
    }

    @Test
    fun kui05M900IsDescribedAsIgnoredPressureAdvanceNotALimit() {
        assertTrue(
            "M900 is split out of the ignored limit commands",
            dashboard.contains("limits.ignoredMarlinLimits.filterNot { it == \"M900\" }"),
        )
        assertTrue(
            "and named as pressure advance rather than a limit",
            dashboard.contains("M900, which is Marlin's pressure advance and not "),
        )
        assertTrue(
            "with what ignoring it means",
            dashboard.contains("keeps its own pressure advance."),
        )
    }

    @Test
    fun kui06ThePerTabStopIsNamedForTheEmergencyStopItRuns() {
        assertTrue(
            "the per-tab button runs emergencyStop and is labelled for it",
            screen.contains("KlipperButton(\"Emergency stop\") { confirmEmergencyStop = true }"),
        )
        assertFalse(
            "the plain Stop label there is gone",
            screen.contains("KlipperButton(\"Stop\") { confirmEmergencyStop = true }"),
        )
        assertTrue(
            "while the dashboard's Stop stays a cancel",
            dashboard.contains("KlipperButton(\"Stop\", onClick = onCancel)"),
        )
    }

    @Test
    fun kui07TunePidRespectsTheCardsOwnPrecondition() {
        assertTrue(
            "the card's precondition is a gate on the call",
            temperatures.contains("pidAllowed = state.connected && !state.isPrinting"),
        )
        assertTrue(
            "and the button is what it gates",
            temperatures.contains(
                "KlipperButton(\"Tune PID\", enabled = pidAllowed, onClick = onCalibrate)",
            ),
        )
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/$relative"),
            File("app/src/main/java/com/tomppi/enderslicer/$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to locate $relative from " + File(".").absolutePath)
    }
}
