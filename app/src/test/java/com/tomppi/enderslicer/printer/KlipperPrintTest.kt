package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a sliced file is called once the printer can see it.
 *
 * These matter more than they look: the name is passed to the host's virtual SD card
 * as a filename inside a directory, so a slash in it would be read as a subdirectory
 * and a leading dot would make the file invisible to the printer's own file list.
 */
class KlipperPrintTest {

    @Test
    fun keepsAPlainNameAndItsSuffix() {
        assertEquals("benchy.gcode", KlipperPrint.fileName("benchy.gcode"))
    }

    @Test
    fun addsTheSuffixWhenItIsMissing() {
        assertEquals("widget.gcode", KlipperPrint.fileName("widget"))
    }

    @Test
    fun stripsDirectoryComponentsFromEitherKindOfPath() {
        assertEquals("Benchy.gcode", KlipperPrint.fileName("/sdcard/Download/Benchy.gcode"))
        assertEquals("widget.gcode", KlipperPrint.fileName("""C:\models\widget"""))
    }

    @Test
    fun replacesCharactersAPrinterFilenameShouldNotCarry() {
        assertEquals("benchy _1_.gcode", KlipperPrint.fileName("benchy (1).gcode"))
        assertEquals("a_b.gcode", KlipperPrint.fileName("a:b.gcode"))
    }

    @Test
    fun neverProducesAHiddenFile() {
        assertEquals("hidden.gcode", KlipperPrint.fileName(".hidden"))
    }

    @Test
    fun fallsBackWhenThereIsNothingUsable() {
        assertEquals("print.gcode", KlipperPrint.fileName(""))
        assertEquals("print.gcode", KlipperPrint.fileName("   "))
        assertEquals("print.gcode", KlipperPrint.fileName("///"))
    }

    @Test
    fun keepsSpacesAndDashesThePrinterHandlesFine() {
        assertEquals("my part-v2.gcode", KlipperPrint.fileName("my part-v2.gcode"))
    }
}
