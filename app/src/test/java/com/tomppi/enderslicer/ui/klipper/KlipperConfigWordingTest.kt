package com.tomppi.enderslicer.ui.klipper

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the configuration screens say, and what they must not.
 *
 * Two of these are sentences the user read and reported: the app telling him the two files
 * "agree already" when they plainly did not - it meant the settings one button moves, and he
 * read it as a claim about his files - and the Z probe screen telling him his printer has no
 * probe while nothing had been read for the connection in use. A screen's wording is not
 * decoration when it is the only thing the user has to go on.
 */
class KlipperConfigWordingTest {

    private val machine = source("ui/klipper/KlipperMachineTab.kt")
    private val zprobe = source("ui/klipper/KlipperZProbeTab.kt")

    @Test
    fun anEmptyCopyNamesWhatItCompared() {
        assertTrue(
            "the settings the button moves are named",
            machine.contains("the settings this app moves - calibrations, ") &&
                machine.contains("\"rotation distance, pressure advance, limits - already match.\""),
        )
        assertFalse(
            "and never that the two files agree",
            machine.contains("the two agree already"),
        )
    }

    @Test
    fun theCardSaysHowManyLinesTheTwoFilesDifferOn() {
        assertTrue(machine.contains("result.lineDifferences?.let"))
        assertTrue(machine.contains("The two files differ on "))
        assertTrue(machine.contains("The two files are the same, line for line."))
    }

    @Test
    fun theProbeScreenOnlyClaimsThereIsNoProbeWhenOneWasRead() {
        assertTrue(
            "an unread configuration is said to be unread",
            zprobe.contains("has not been read yet, so this screen") &&
                zprobe.contains("cannot tell whether it has a probe"),
        )
        assertTrue(
            "and the claim that there is none is guarded by having read one",
            zprobe.contains("if (state.configSections == null)") &&
                zprobe.contains("if (state.probeSection == null)"),
        )
    }

    @Test
    fun theWayToTheComputerSCOnfigurationIsOnTheScreenAndNamedForWhatItDoes() {
        assertTrue(machine.contains("\"Adopt PC Klipper's configuration\""))
        assertTrue(machine.contains("viewModel.previewAdoptingComputerConfig()"))
        assertTrue(machine.contains("viewModel.adoptComputerConfig(checked)"))
        assertFalse(
            "and the value-copy into the phone is gone",
            machine.contains("Copy to Phone Klipper"),
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
