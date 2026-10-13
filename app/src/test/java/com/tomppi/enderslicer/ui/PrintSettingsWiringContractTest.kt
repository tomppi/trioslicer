package com.tomppi.enderslicer.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The print settings' wiring to the window: the app's own expanded decision, and
 * the one call site that hands it to the sheet.
 *
 * The threshold is the window's, not the sheet's - the Print tab's content box has
 * already lost the navigation rail's width by the time the sheet is composed, so a
 * second measurement there would disagree on a 600-680dp window. This pins the one
 * decision both layouts hang off, and the one section list they are built from.
 */
class PrintSettingsWiringContractTest {
    private val app = source("ui/EnderSlicerApp.kt")
    private val sheet = source("ui/CategorizedSettingsSheet.kt")

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

    /** The window decides expanded, and the print settings sheet is told it. */
    @Test
    fun theWindowDecidesExpandedAndTheSheetIsToldIt() {
        assertTrue(
            "the app's one width decision",
            app.contains("val expandedLayout = maxWidth >= 600.dp"),
        )
        val call = app.substringAfter("CategorizedSettingsSheet(").substringBefore("\n                        )")
        assertTrue(
            "the sheet is handed the window's own decision, not a second measurement",
            call.contains("expandedLayout = expandedLayout"),
        )
    }

    /**
     * Both layouts render the same section bodies and the same selector list: the
     * phone's stacked cards and the wide layout's two panes cannot drift apart into
     * two different sets of settings.
     */
    @Test
    fun bothLayoutsRenderTheSameSections() {
        assertTrue(
            "the phone stacks the categories",
            sheet.contains("PRINT_SETTINGS_CATEGORIES.forEach { section ->") &&
                sheet.contains("SettingsCategory("),
        )
        assertTrue(
            "the wide layout gives each pane its own selector over the same list",
            sheet.contains("private fun PrintSettingsPane(") &&
                sheet.contains("PrintSettingsSection.entries.forEach { entry ->") &&
                sheet.contains("PRINT_SETTINGS_LEFT_PANE_TAG") &&
                sheet.contains("PRINT_SETTINGS_RIGHT_PANE_TAG"),
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
