package com.tomppi.enderslicer.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The print settings' wiring to the window: the app's own expanded decision, the
 * three call sites that hand it to the three engine sheets, and the one shape and
 * one card they are all built from.
 *
 * The threshold is the window's, not the sheet's - the Print tab's content box has
 * already lost the navigation rail's width by the time the sheet is composed, so a
 * second measurement there would disagree on a 600-680dp window. This pins the one
 * decision every layout hangs off, and the one section list they are built from.
 */
class PrintSettingsWiringContractTest {
    private val app = source("ui/EnderSlicerApp.kt")
    private val sheet = source("ui/CategorizedSettingsSheet.kt")
    private val layout = source("ui/SettingsSheet.kt")
    private val prusa = source("ui/PrusaSettingsSheet.kt")
    private val orca = source("ui/OrcaSettingsSheet.kt")

    /** The sheet's sections are the ones the phone shows, in the phone's own order. */
    @Test
    fun theSectionsAreTheOnesTheScreenShowsInTheOrderItShowsThem() {
        assertEquals(
            listOf(
                "Printer & profile",
                "Quality",
                "Walls and top/bottom",
                "Infill",
                "Speed",
                "Material",
                "Cooling",
                "Supports",
                "Travel and retraction",
                "Build plate adhesion",
                "Experimental",
            ),
            PrintSettingsSection.entries.map { it.label },
        )
        assertTrue(
            "the phone's cards are every section but the header block",
            sheet.contains("PrintSettingsSection.entries.filter { it != PrintSettingsSection.PRINTER }"),
        )
    }

    /** The window decides expanded, and all three engine sheets are told it. */
    @Test
    fun theWindowDecidesExpandedAndEverySheetIsToldIt() {
        assertTrue(
            "the app's one width decision",
            app.contains("val expandedLayout = maxWidth >= 600.dp"),
        )
        val curaCall = app.substringAfter("CategorizedSettingsSheet(").substringBefore("\n                        )")
        assertTrue(
            "the Cura sheet is handed the window's own decision, not a second measurement",
            curaCall.contains("expandedLayout = expandedLayout"),
        )
        val prusaCall = app.substringAfter("PrusaSettingsSheet(").substringBefore("\n                        )")
        assertTrue("and the PrusaSlicer sheet", prusaCall.contains("expandedLayout = expandedLayout"))
        val orcaCall = app.substringAfter("OrcaSettingsSheet(").substringBefore("\n                        )")
        assertTrue("and the OrcaSlicer sheet", orcaCall.contains("expandedLayout = expandedLayout"))
    }

    /**
     * One shape and one card for all three engines: the phone's single column and
     * the wide layout's two columns come from the same [SettingsSheet], and every
     * section of every engine is the same [SettingsCategory] card.
     */
    @Test
    fun oneShapeAndOneCardAreSharedByEveryEngine() {
        assertTrue(
            "the shape renders every section on a phone and splits the same list when wide",
            layout.contains("sections.forEach { section ->") &&
                layout.contains("sections.take(split)") &&
                layout.contains("sections.drop(split)") &&
                layout.contains("SETTINGS_LEFT_PANE_TAG") &&
                layout.contains("SETTINGS_RIGHT_PANE_TAG"),
        )
        assertTrue(
            "with the profile block in the left column only",
            layout.contains("header = header") && layout.contains("header?.invoke()"),
        )
        listOf("Cura" to sheet, "PrusaSlicer" to prusa, "OrcaSlicer" to orca).forEach { (name, text) ->
            assertTrue("$name's screen uses the one shape", text.contains("SettingsSheet("))
            assertTrue("$name's sections are the one list type", text.contains("SettingsSection("))
        }
        assertTrue(
            "the Cura sections are the categories themselves, with no selector left",
            sheet.contains("PRINT_SETTINGS_CATEGORIES.map { section ->") &&
                !sheet.contains("Section: "),
        )
        assertTrue(
            "and one body per section, written once",
            sheet.contains("private fun PrintSettingsSectionBody("),
        )
        PrintSettingsSection.entries.forEach { section ->
            assertTrue(
                "${section.label} has its own rows in the one body",
                sheet.contains("PrintSettingsSection.${section.name} -> {"),
            )
        }
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/$relative"),
            File("app/src/main/java/com/tomppi/enderslicer/$relative"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate source file for $relative from " + File(".").absolutePath)
        return file.readText()
    }
}
