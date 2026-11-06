package com.tomppi.enderslicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The AI settings' new home, as a source contract.
 *
 * The in-app agent's endpoint, model, credential, web tools, agent mode and the harness
 * connection used to live in the SolidCraft document sheet - reached only through one engine's
 * menu, even though they are app-wide. They live in More -> Configuration -> AI settings now;
 * the SolidCraft sheet keeps the document and the engines, and the per-engine "Copy MCP token"
 * menu items are gone because the unified door owns the one token.
 */
class AiSettingsSourceTest {

    private val main = File("src/main/java")
    private val ui = File(main, "com/tomppi/enderslicer/ui")

    private fun source(relative: String): String {
        val file = File(main, relative)
        assertTrue("missing " + file.path, file.isFile)
        return file.readText()
    }

    @Test
    fun theSettingsLiveInMoreConfigurationUnderTheirOwnName() {
        val app = source("com/tomppi/enderslicer/ui/EnderSlicerApp.kt")
        val configuration = app.substringAfter("MoreSectionLabel(\"Configuration\")")
        val card = configuration.substringBefore("MoreSectionLabel(\"Experimental\")")
        assertTrue(
            "Configuration carries the AI settings row",
            card.contains("title = \"AI settings\""),
        )
        assertTrue("the row opens the settings sheet", card.contains("onClick = onAiSettings"))
        assertTrue("the screen takes the callback", app.contains("onAiSettings: () -> Unit"))
        // It is wired to the same helpers the SolidCraft sheet used to carry.
        assertTrue(app.contains("onAgentModeChange = ::setAgentMode"))
        assertTrue(app.contains("onAgentSettingsSave = ::saveAgentSettings"))
        assertTrue(app.contains("onConnectHarness = { connectHarness() }"))
    }

    @Test
    fun theAiSettingsSheetHasTheInAppEndpointAndTheHarnessConnection() {
        val sheet = source("com/tomppi/enderslicer/ui/AiSettingsSheet.kt")
        assertTrue(sheet.contains("Text(\"AI settings\""))
        // In-app endpoint, moved out of the document sheet.
        assertTrue(sheet.contains("\"Base URL\""))
        assertTrue(sheet.contains("\"Model\""))
        assertTrue(sheet.contains("\"API key"))
        assertTrue(sheet.contains("\"Save endpoint\""))
        assertTrue(sheet.contains("\"Web search & page fetch\""))
        assertTrue(sheet.contains("\"Lend the agent my view\""))
        // Harness connection, always editable - the form used to disappear once connected.
        assertTrue(sheet.contains("\"Launch URL\""))
        assertTrue(sheet.contains("\"Workspace on the harness\""))
        assertTrue(sheet.contains("\"Reconnect\""))
        assertTrue(sheet.contains("onConnectHarness"))
        assertTrue(
            "the harness section says the fields replace the stored connection",
            sheet.contains("paste a new launch URL to replace it"),
        )
    }

    @Test
    fun theDocumentSheetKeepsTheDocumentAndLosesTheAgentSettings() {
        val sheet = source("com/tomppi/enderslicer/ui/SolidCraftSheet.kt")
        assertTrue("the sheet is named for its surface", sheet.contains("\"SolidCraft\""))
        // The moved-out controls are not duplicated here.
        assertFalse(sheet.contains("onAgentModeChange"))
        assertFalse(sheet.contains("onAgentSettingsSave"))
        assertFalse(sheet.contains("onAgentWebSearchChange"))
        assertFalse(sheet.contains("InAppAgentSettings"))
        assertFalse(sheet.contains("\"Base URL\""))
        assertFalse(sheet.contains("Set the endpoint above"))
        // It points at the settings that own the configuration.
        assertTrue(sheet.contains("AI settings"))
        // The document/engine surface stays.
        assertTrue(sheet.contains("Document: "))
        assertTrue(sheet.contains("Take over"))
    }

    @Test
    fun thePerEngineMcpTokenMenuItemsAreGoneAndTheDoorKeepsTheOneToken() {
        val app = source("com/tomppi/enderslicer/ui/EnderSlicerApp.kt")
        assertFalse(
            "no engine menu offers its own token copy any more",
            app.contains("Text(\"Copy MCP token\")"),
        )
        val sheet = source("com/tomppi/enderslicer/ui/SolidCraftSheet.kt")
        assertFalse("nor does the document sheet", sheet.contains("Copy MCP token"))
        // The unified door's own copy stays: one token, one place.
        val chat = source("com/tomppi/enderslicer/ui/AiChatOverlay.kt")
        assertTrue(chat.contains("Text(\"Copy token\")"))
        assertTrue(chat.contains("door.onCopyToken()"))
    }
}
