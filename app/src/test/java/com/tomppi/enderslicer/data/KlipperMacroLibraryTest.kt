package com.tomppi.enderslicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The macros the app can offer a printer.
 *
 * These are Klipper's own sample macros, adapted - so the tests that matter are about what is
 * *not* in them: a duplicate section, or a section that is already defined in the shipped
 * configuration, would make klippy refuse the file outright. [pause_resume] is the concrete
 * case: the sample file defines it, this printer already has it, and adding it twice would stop
 * the host starting.
 */
class KlipperMacroLibraryTest {
    @Test
    fun everyMacroIsOfferedAsItsOwnSection() {
        val sections = KlipperMacroLibrary.sections()
        for (macro in KlipperMacroLibrary.all) {
            assertTrue("${macro.name} has no section", sections.contains("[gcode_macro ${macro.name}]"))
            assertTrue("${macro.name} has no gcode body", macro.section.contains("gcode:"))
            assertTrue("${macro.name} has no explanation", macro.summary.isNotBlank())
        }
    }

    @Test
    fun nothingIsOfferedThatThePrinterAlreadyDefines() {
        // The sample macros file also carries [pause_resume] and [exclude_object], and this
        // printer's configuration has both: a second copy of either is a file klippy will not
        // load. Only section headers count - the M486 body mentions [exclude_object] inside an
        // error message, which is not a section.
        val headers = KlipperMacroLibrary.sections()
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("[") && it.endsWith("]") }
            .toList()
        assertFalse(headers.contains("[pause_resume]"))
        assertFalse(headers.contains("[exclude_object]"))
        assertTrue(headers.all { it.startsWith("[gcode_macro ") })
    }

    @Test
    fun noSectionIsOfferedTwice() {
        val headers = KlipperMacroLibrary.sections()
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("[") && it.endsWith("]") }
            .toList()
        assertEquals(headers.size, headers.distinct().size)
    }

    @Test
    fun whatIsMissingIsWhatTheFileDoesNotDefine() {
        assertEquals(0, KlipperMacroLibrary.missingFrom(KlipperMacroLibrary.sections()).size)
        assertEquals(4, KlipperMacroLibrary.missingFrom("").size)
        val onlyM600 = "[gcode_macro M600]\ngcode:\n    PAUSE\n"
        val missing = KlipperMacroLibrary.missingFrom(onlyM600).map { it.name }
        assertEquals(listOf("M486", "LOAD_FILAMENT", "UNLOAD_FILAMENT"), missing)
    }

    @Test
    fun theSectionHeaderIsMatchedWhateverCaseItIsWrittenIn() {
        // klippy registers a macro by the name it is given, and a file may spell it either way.
        assertTrue(KlipperMacroLibrary.missingFrom("[gcode_macro m600]").none { it.name == "M600" })
        assertTrue(KlipperMacroLibrary.missingFrom("[gcode_macro M600]").none { it.name == "M600" })
        // A macro whose name merely starts the same is not a match.
        assertTrue(KlipperMacroLibrary.missingFrom("[gcode_macro M6000]").any { it.name == "M600" })
    }
}
