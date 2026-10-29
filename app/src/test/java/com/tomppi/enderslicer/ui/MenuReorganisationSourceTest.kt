package com.tomppi.enderslicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The top-bar menu reorganisation, as a source contract.
 *
 * The four menus that used to overlap (Plate, Blender, SolidCraft, CAD/OCCT) now each own one
 * job: the plate owns plate-level actions (including the CAD document -> STL conversion and no
 * texture/files shortcuts), Blender owns its files destination, SolidCraft owns the design and
 * the CAD agent, and the built-in OCCT workspace calls itself OCCT rather than CAD.
 */
class MenuReorganisationSourceTest {

    private fun app(): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt"),
            File("app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("EnderSlicerApp.kt was not found")
    }

    private fun menu(startLabel: String, endLabel: String): String =
        app().substringAfter("label = \"" + startLabel + "\"").substringBefore("label = \"" + endLabel + "\"")

    private fun plateMenu(): String = menu("Plate", "Blender")

    private fun blenderMenu(): String = menu("Blender", "SolidCraft")

    private fun solidCraftMenu(): String = menu("SolidCraft", "OCCT")

    @Test
    fun thePlateMenuDropsTheTextureAndBlenderFilesShortcutsAndOwnsConvertToStl() {
        val plate = plateMenu()
        assertFalse("Texture surface lives on the rail and More now", plate.contains("Texture surface"))
        assertFalse("Blender files belongs to the Blender menu", plate.contains("Blender files"))
        assertFalse(plate.contains("MenuSectionLabel(\"Storage\")"))
        // The CAD document -> STL models press is a plate-level conversion.
        assertTrue(plate.contains("state.cadConvertLabel ?: \"Convert to STL\""))
        assertTrue(plate.contains("viewModel.clearPendingQuestion()"))
        assertTrue(plate.contains("viewModel.convertCadToStl()"))
        assertTrue(plate.contains("state.cadDocument != null && !state.isBusy"))
        // The rail's own texture studio door survived; only the plate shortcut went.
        val text = app()
        assertTrue(text.contains("RailButtonLabel(\"Texture surface\")"))
        assertTrue(text.contains("title = \"Texture surface\""))
    }

    @Test
    fun theBlenderMenuOwnsTheBlenderFilesDestination() {
        val blender = blenderMenu()
        assertTrue(blender.contains("MenuSectionLabel(\"Files\")"))
        assertTrue(blender.contains("Text(\"Blender files\")"))
        assertTrue(blender.contains("blenderFilesOpen = true"))
        // The dialog itself is unchanged and still reachable.
        assertTrue(app().contains("title = \"Blender files\""))
        assertTrue(app().contains("EngineFilesScreen("))
    }

    @Test
    fun theSolidCraftMenuOwnsTheCadAgentAndHasNoPlateSection() {
        val solidCraft = solidCraftMenu()
        assertTrue(solidCraft.contains("\"CAD agent · \""))
        assertTrue(solidCraft.contains("viewModel.connectCadAgent()"))
        assertTrue(solidCraft.contains("viewModel.openSolidCraftSheet()"))
        assertTrue(solidCraft.contains("MenuSectionLabel(\"Design\")"))
        assertTrue(solidCraft.contains("MenuSectionLabel(\"Export\")"))
        assertTrue(solidCraft.contains("MenuSectionLabel(\"Engine\")"))
        assertFalse(
            "SolidCraft does not need its own plate menu",
            solidCraft.contains("MenuSectionLabel(\"Plate\")"),
        )
        assertFalse(solidCraft.contains("state.cadConvertLabel"))
        assertFalse("no second Convert press", solidCraft.contains("viewModel.convertCadToStl()"))
        assertFalse(solidCraft.contains("\"Connect CAD agent\""))
    }

    @Test
    fun theRailHasNoCadAgentButtonAnymore() {
        val text = app()
        assertFalse(text.contains("CAD agent · live"))
        assertFalse(text.contains("onCadAgent"))
        assertFalse(text.contains("onClick = onCadAgent"))
        // The sheet is still opened through the one menu door.
        assertTrue(text.contains("viewModel.connectCadAgent()"))
        assertTrue(text.contains("viewModel.openSolidCraftSheet()"))
    }

    @Test
    fun theTopBarCallsTheBuiltInWorkspaceOcct() {
        val text = app()
        assertTrue(text.contains("label = \"OCCT\""))
        assertFalse("the nav label no longer says CAD", text.contains("label = \"CAD\""))
        val occt = text.substringAfter("label = \"OCCT\"").substringBefore("bottomBar = {")
        assertTrue(occt.contains("\"Model with OCCT\""))
        assertTrue(occt.contains("\"Stop OCCT engine\""))
        assertTrue(occt.contains("\"OCCT files\""))
        assertTrue(occt.contains("\"OCCT MCP token\""))
        assertFalse(occt.contains("\"Model with CAD\""))
        assertFalse(occt.contains("\"Stop CAD engine\""))
        assertFalse(occt.contains("\"CAD files\""))
        // The document/agent vocabulary is intentionally untouched.
        assertTrue(text.contains("\"CAD agent · \""))
        assertTrue(text.contains("\"Convert to STEP (CAD)…\""))
        assertTrue(text.contains("viewModel.openMeshToStep()"))
    }

    @Test
    fun convertToStlHasOneMenuDoorAndItIsThePlate() {
        val text = app()
        assertTrue(plateMenu().contains("viewModel.convertCadToStl()"))
        assertFalse(solidCraftMenu().contains("viewModel.convertCadToStl()"))
        // The rail's action row and the plate action bar keep their own Convert buttons; the
        // menu duplication is what went away.
        assertEquals(
            3,
            Regex(Regex.escape("viewModel.convertCadToStl()")).findAll(text).count(),
        )
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
