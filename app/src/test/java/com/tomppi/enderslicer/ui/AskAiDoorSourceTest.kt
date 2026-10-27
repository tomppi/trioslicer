package com.tomppi.enderslicer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The chat's discoverability, as a source contract.
 *
 * The chat used to live behind the Blender menu, and several user-facing strings told people to
 * look for a "sparkle button". This pins the move to the rail: the old menu door is gone, the
 * rail is the one door, and no UI string points at the sparkle any more. The behaviour of the
 * rail itself is covered by [AskAiRailTest].
 */
class AskAiDoorSourceTest {

    private val ui = File("src/main/java/com/tomppi/enderslicer/ui")

    private fun source(name: String): String {
        val file = File(ui, name)
        assertTrue("missing " + file.path, file.isFile)
        return file.readText()
    }

    @Test
    fun theBlenderMenusAskAiDoorIsGoneAndTheRailIsTheOnlyDoor() {
        val app = source("EnderSlicerApp.kt")

        assertFalse(
            "the Blender menu's Assistant section and its Ask AI item are the door this replaced",
            app.contains("MenuSectionLabel(\"Assistant\")") || app.contains("Text(\"Ask AI\")"),
        )
        assertEquals(
            "the rail and the question notification open the chat through one door helper",
            1,
            Regex("aiChatOpen = true").findAll(app).count(),
        )
        assertTrue(
            "the rail wires it through the door that shows a pending question on its own source",
            app.contains("onAskAi = { openChatForAttention() }"),
        )
        assertTrue(
            "the notification tap asks for the chat itself through the same door",
            app.contains("if (ChatOpenRequest.takeUnhandled() != null) openChatForAttention()"),
        )
        assertTrue(
            "the door opens a waiting question in the conversation that draws its card",
            app.contains("fun openChatForAttention()") &&
                app.contains("viewModel.chatSource = ChatSource.IN_APP"),
        )
        assertTrue(
            "the rail item is drawn like the other destinations",
            app.contains("icon = AppIcons.Sparkle,") &&
                app.contains("label = \"Ask AI\"") &&
                app.contains("badge = askAiBadge"),
        )
        assertFalse(
            "opening the chat must not be gated on the Plate tab: the rail is there on every tab",
            app.contains("if (aiChatOpen && selectedTab == AppTab.PLATE)"),
        )
    }

    @Test
    fun theTwoCadConversationsAreSeparateListsOwnedByTheirBackends() {
        val app = source("EnderSlicerApp.kt")

        // The in-app loop appends to the in-app list, tool rows included.
        assertTrue(
            "a tool row goes through the in-app list only",
            app.contains("viewModel.applyCadToolTrace(trace)"),
        )
        assertTrue("in-app messages are appended to the in-app list", app.contains("cadConversations.appendInApp("))
        // The harness session's writes go to the harness list and nowhere else.
        assertTrue("the harness sends append to the harness list", app.contains("cadConversations.appendHarness("))
        assertTrue("the harness poll replaces the harness list", app.contains("cadConversations.replaceHarness("))
        assertFalse("there is no shared conversation variable left", app.contains("var cadMessages"))

        // One shared selection: the overlay's source is the sheet's own mode, and the sheet reads
        // the list belonging to that same mode.
        assertTrue(app.contains("source = viewModel.chatSource"))
        assertTrue(app.contains("setAgentMode(it.agentMode())"))
        assertTrue(
            app.contains(
                "messages = viewModel.cadConversations.messagesFor(agentSettings.mode.chatSource())",
            ),
        )

        // Every harness poll in the app repopulates the harness list - never the in-app one.
        val polls = Regex(
            "pollForReply\\(\\s*chat,\\s*\\{ viewModel\\.cadConversations = " +
                "viewModel\\.cadConversations\\.replaceHarness\\(it\\) \\}",
        ).findAll(app).count()
        assertEquals("both harness ask paths poll into the harness list", 2, polls)
        val resume = Regex(
            "resumeReplyIfRunning\\(\\s*cad,\\s*\\{ viewModel\\.cadConversations = " +
                "viewModel\\.cadConversations\\.replaceHarness\\(it\\) \\}",
        ).findAll(app).count()
        assertEquals("the restored CAD session repopulates the harness list", 1, resume)
    }

    @Test
    fun theInAppConversationIsClearedWhenItsDocumentClosesOrIsReplaced() {
        val app = source("EnderSlicerApp.kt")
        // Nothing in-app is persisted (the list lives in the view model, not on disk), so a
        // closed or replaced document must not leave its transcript on screen. The reset moved
        // into one view-model helper (audit docs #5 / AGENT-7): it clears the transcript, the
        // agent's memory and a waiting question, it announces that it did, and every path that
        // replaces the document calls it.
        val viewModel = source("MainViewModel.kt")
        assertTrue(
            "the reset clears only the in-app list",
            viewModel.contains("cadConversations = cadConversations.copy(inApp = emptyList())"),
        )
        assertTrue(
            "the reset forgets the agent's conversation",
            viewModel.contains("inAppCadAgent?.reset()"),
        )
        assertTrue(
            "the reset clears a waiting question",
            viewModel.contains("clearPendingQuestion()"),
        )
        assertTrue(
            "the reset is announced, not silent",
            viewModel.contains("the in-app conversation was reset for this document"),
        )
        val calls = Regex("resetInAppConversationForDocumentSwitch\\(\\)").findAll(viewModel).count()
        assertTrue("the document-switch paths all reset: " + calls, calls >= 4)
        assertTrue(app.contains("viewModel.closeCadDocument()"))
        assertTrue(app.contains("viewModel.openCadDocument(picked)"))
    }

    @Test
    fun noUserFacingStringStillSendsPeopleToTheSparkleButton() {
        // AppIcons.kt is the glyph's own internal name ("Sparkle"), not a pointer at a button.
        val quotedSparkle = Regex("\"[^\"\n]*sparkle[^\"\n]*\"", RegexOption.IGNORE_CASE)
        val offenders = ui.walkTopDown()
            .filter { it.extension == "kt" && it.name != "AppIcons.kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (quotedSparkle.containsMatchIn(line)) {
                        file.name + ":" + (index + 1) + ": " + line.trim()
                    } else {
                        null
                    }
                }
            }
            .toList()
        assertTrue("strings still point at the sparkle button: " + offenders, offenders.isEmpty())
    }

    @Test
    fun theCadAgentSheetsPointerNamesTheRail() {
        val sheet = source("SolidCraftSheet.kt")
        assertTrue(
            "the sheet has to point at the one door",
            sheet.contains("Ask AI in the sidebar rail"),
        )
        assertFalse("and not at the old button", sheet.contains("sparkle button"))
    }
}
