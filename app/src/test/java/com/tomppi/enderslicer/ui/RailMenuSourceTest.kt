package com.tomppi.enderslicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rail's fold, as a source contract.
 *
 * The choice must be the same UI-preference mechanism the UI scale uses: read at startup,
 * written on every press, keyed by its own name. The arrow must carry the semantics and the
 * tag, and only the wide rail may show it - the phone's sidebar panel is the same composable
 * and must not grow a second collapse control.
 */
class RailMenuSourceTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/$relative"),
            File("app/src/main/java/com/tomppi/enderslicer/$relative"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate source file for $relative")
        return file.readText()
    }

    @Test
    fun theFoldChoiceIsAReadAndWrittenUiPreference() {
        val preference = source("ui/RailMenuPreference.kt")
        assertTrue(
            "the preference key is a named constant",
            preference.contains("const val KEY_EXPANDED = \"rail-menu-expanded\""),
        )
        assertTrue(preference.contains("getBoolean(KEY_EXPANDED, DEFAULT_EXPANDED)"))
        assertTrue(preference.contains("putBoolean(KEY_EXPANDED, expanded)"))

        val app = source("ui/EnderSlicerApp.kt")
        assertTrue(
            "the rail reads the stored choice before it is drawn",
            app.contains("mutableStateOf(RailMenuPreference.initialize(context))"),
        )
        assertTrue(
            "every press writes the choice back",
            app.contains("RailMenuPreference.save(context, expanded)"),
        )
        assertTrue(
            "and the wide rail is the one that gets the fold",
            app.contains("collapsibleMenu = expandedLayout"),
        )
    }

    @Test
    fun theArrowHasItsOwnSemanticsAndTag() {
        val app = source("ui/EnderSlicerApp.kt")
        assertTrue(app.contains("internal const val SESSION_RAIL_MENU_TOGGLE_TAG"))
        assertTrue(app.contains("\"Collapse the menu\""))
        assertTrue(app.contains("\"Expand the menu\""))
        assertTrue(
            "a folded rail says the agent is waiting",
            app.contains("\"Expand the menu - Ask AI has a question\""),
        )
        assertTrue(
            "the phone's sidebar panel passes false and keeps its old shape",
            app.contains("collapsibleMenu: Boolean = false"),
        )
    }
}
