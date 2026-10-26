package com.tomppi.enderslicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rotation bug as a source contract: conversation and turn state belongs to the ViewModel.
 *
 * Behaviour is covered by [ChatRotationTest]; this fails if a future change puts a conversation
 * back into a composable `remember` (which a rotation discards) or launches the in-app turn in
 * the composition's scope.
 */
class ChatRotationSourceTest {

    private val main = File("src/main/java")

    private fun source(relative: String): String {
        val file = File(main, relative)
        assertTrue("missing " + file.path, file.isFile)
        return file.readText()
    }

    @Test
    fun theConversationsAreHeldInTheViewModelNotInBareRemembers() {
        val app = source("com/tomppi/enderslicer/ui/EnderSlicerApp.kt")
        val conversational = listOf(
            "cadConversations",
            "cadStatus",
            "cadBusy",
            "modellingMessages",
            "modellingStatus",
            "modellingBusy",
            "inAppWebNotice",
        )
        conversational.forEach { name ->
            assertFalse(
                name + " is conversation state: a bare remember in the composable is the bug " +
                    "that came back, because a rotation discards it",
                Regex("var " + name + " by remember \\{").containsMatchIn(app),
            )
        }

        val viewModel = source("com/tomppi/enderslicer/ui/MainViewModel.kt")
        assertTrue(viewModel.contains("var cadConversations by mutableStateOf(CadConversations())"))
        assertTrue(viewModel.contains("var cadBusy by mutableStateOf(false)"))
        assertTrue(viewModel.contains("var modellingMessages by mutableStateOf(listOf<AiChatMessage>())"))
        assertTrue(viewModel.contains("var modellingBusy by mutableStateOf(false)"))
        assertTrue(
            "a conversation survives a rotation, not a process death - say it in the code",
            viewModel.contains("NOT persisted to disk"),
        )
    }

    @Test
    fun theInAppTurnRunsInTheViewModelsScopeAndTheChatSelectionsSurvive() {
        val app = source("com/tomppi/enderslicer/ui/EnderSlicerApp.kt")
        val turn = app.substringAfter("fun askInAppAgent(text: String, stored: StoredAgentSettings)")
            .substringBefore("fun askInAppAgentFromChat")
        assertTrue("the turn is launched in the ViewModel's scope", turn.contains("viewModel.runChatTurn {"))
        assertFalse(
            "a turn launched in the composition's scope is cancelled by a rotation",
            turn.contains("aiScope.launch"),
        )
        assertTrue(
            "the chat size is rememberSaveable",
            Regex("var chatSize by rememberSaveable \\{ mutableStateOf\\(ChatSize\\.PANEL\\) \\}")
                .containsMatchIn(app),
        )

        val viewModel = source("com/tomppi/enderslicer/ui/MainViewModel.kt")
        assertTrue(
            "the ViewModel owns the turn scope and the chat surfaces' updates",
            viewModel.contains("viewModelScope.launch(block = block)"),
        )
        assertTrue("the selected chat source survives in the ViewModel", viewModel.contains("var chatSource by mutableStateOf(ChatSource.IN_APP)"))
    }
}
