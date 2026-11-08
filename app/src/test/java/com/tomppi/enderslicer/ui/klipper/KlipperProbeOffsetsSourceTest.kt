package com.tomppi.enderslicer.ui.klipper

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe-offset editor, as source contracts.
 *
 * The offsets are the probe's mount; the app could read them but not change them, so a user
 * with a wrong pair had to edit printer.cfg on the computer. The card on the Z probe tab is
 * the editor; the write goes through the one host-config path the rest of the app uses, and
 * the restart it needs is held back while a print is running.
 */
class KlipperProbeOffsetsSourceTest {

    private val printer = File("src/main/java/com/tomppi/enderslicer/printer")

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull(File::isFile) ?: error("missing " + relative)
        return file.readText()
    }

    @Test
    fun theZProbeTabCarriesTheEditor() {
        val zprobe = source("src/main/java/com/tomppi/enderslicer/ui/klipper/KlipperZProbeTab.kt")
        assertTrue(
            "the tab reads the offsets the configuration carries",
            zprobe.contains("state.probeOffsets?.let { offsets ->"),
        )
        assertTrue("and renders the editor card", zprobe.contains("ProbeOffsetsCard("))
        assertTrue("with a field per axis", zprobe.contains("\"X offset (mm)\""))
        assertTrue(zprobe.contains("\"Y offset (mm)\""))
        assertTrue("and one press to save", zprobe.contains("\"Save offsets\""))
        assertTrue("calling the view model, never the transport", zprobe.contains("viewModel.saveProbeOffsets("))
        // The restart is the host's, and the card says a print holds it back.
        assertTrue(zprobe.contains("restart was held"))
    }

    @Test
    fun theWriteGoesThroughTheOneHostConfigPath() {
        val repository = source("src/main/java/com/tomppi/enderslicer/printer/KlipperPrinterRepository.kt")
        assertTrue(
            "the offsets are set by the helper that knows which section carries them",
            repository.contains("KlipperConfigFile.withProbeOffsets(current, x, y)"),
        )
        assertTrue(
            "and written where the host reads its configuration",
            repository.contains("writeHostConfig(edited)"),
        )
        assertTrue(
            "a print in progress holds the restart back",
            repository.contains("if (_state.value.isPrinting)"),
        )
        val viewModel = source("src/main/java/com/tomppi/enderslicer/printer/KlipperViewModel.kt")
        assertTrue(
            "the screen's call is the repository's",
            viewModel.contains("suspend fun saveProbeOffsets(x: Double, y: Double): Result<ProbeOffsetWrite>"),
        )
    }

    @Test
    fun theHelperPicksTheSectionThatCarriesTheOffsets() {
        val file = File(printer, "KlipperConfigFile.kt").takeIf(File::isFile)
            ?: File("app/src/main/java/com/tomppi/enderslicer/printer/KlipperConfigFile.kt")
        val text = file.readText()
        assertTrue(text.contains("fun withProbeOffsets(text: String, x: Double, y: Double): String?"))
        assertTrue(text.contains("fun probeOffsetSection(text: String): String?"))
        // A file with no probe is a null, not a silent rewrite of something else.
        assertTrue(text.contains("val section = probeOffsetSection(text) ?: return null"))
        assertFalse("z is not in this path; it is the paper calibration's", text.contains("z_offset\", value"))
    }
}
