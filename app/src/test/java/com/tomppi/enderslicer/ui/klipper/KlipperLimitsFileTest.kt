package com.tomppi.enderslicer.ui.klipper

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which file the dashboard's Limits card describes.
 *
 * The card read the last slice whatever the printer was printing, so a print was diagnosed
 * under "This file, and this printer" with another file's M204 lines - and with nothing sliced
 * it claimed what "the file" set for a file that did not exist. The file is the printing one
 * while a print runs, and a file this device cannot read is no file at all rather than a
 * stand-in.
 */
class KlipperLimitsFileTest {

    private fun onDisk(): File = File.createTempFile("limits", ".gcode").apply { deleteOnExit() }

    @Test
    fun whilePrintingThePrintingFileIsTheOneRead() {
        val printing = onDisk()
        val sliced = onDisk()
        assertEquals(
            printing,
            limitsFile(printing = true, printingFile = printing, slicedFile = sliced),
        )
    }

    @Test
    fun aPrintDoesNotFallBackToTheLastSlice() {
        val sliced = onDisk()
        assertNull(
            "another file's limit lines must never stand in for the print's own",
            limitsFile(printing = true, printingFile = File("not-here.gcode"), slicedFile = sliced),
        )
    }

    @Test
    fun withoutAPrintTheLastSliceIsTheFile() {
        val sliced = onDisk()
        assertEquals(
            sliced,
            limitsFile(printing = false, printingFile = null, slicedFile = sliced),
        )
    }

    @Test
    fun withNoFileAtAllThereIsNone() {
        assertNull(limitsFile(printing = false, printingFile = null, slicedFile = null))
        assertNull(limitsFile(printing = true, printingFile = null, slicedFile = null))
        assertNull(
            limitsFile(printing = false, printingFile = null, slicedFile = File("not-here.gcode")),
        )
    }
}
