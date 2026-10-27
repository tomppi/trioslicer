package com.tomppi.enderslicer.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.cadagent.AgentToolTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A rotation must not delete the chat.
 *
 * The activity is really recreated (ActivityScenario.recreate) and the ViewModel is really the
 * one the activity's ViewModelStore hands to the new activity; the chat state - conversation,
 * busy flag and selected source - is asserted after the recreate. Those are exactly the values
 * the overlay and the sheet render, so a new composition would draw them; before the fix they
 * lived in composable `remember`s and were gone.
 *
 * The recreate is driven through the activity rule's scenario, so the ViewModel is the real
 * activity-scoped one and not a test double.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ChatRotationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: MainViewModel

    private fun seedNativeLibraryDirectory() {
        // Robolectric's ApplicationInfo has no nativeLibraryDir; the ViewModel's engine runners
        // need one at construction. Nothing here slices - the chat state is what is exercised.
        compose.activityRule.scenario.onActivity { activity ->
            activity.application.applicationInfo.nativeLibraryDir =
                java.io.File(activity.filesDir, "lib").apply { mkdirs() }.absolutePath
        }
    }

    private fun hostTheChat() {
        seedNativeLibraryDirectory()
        compose.activityRule.scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[MainViewModel::class.java]
            activity.setContent {
                EnderSlicerTheme { RotationChatHost(viewModel) }
            }
        }
        compose.waitForIdle()
    }

    private fun recreate() {
        val before = viewModel
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity ->
            val after = ViewModelProvider(activity)[MainViewModel::class.java]
            // The point of the test: the same ViewModel instance survives the recreate, so the
            // chat state it holds survives with it.
            assertSame(before, after)
            viewModel = after
        }
    }

    @Test
    fun aTypedInAppMessageAndItsToolRowAndBusyFlagSurviveARotation() {
        hostTheChat()

        // Type into the real composer and send it. The host's onSend writes the message into
        // the ViewModel - the same state the real send path records - and marks the turn busy.
        compose.onNode(hasSetTextAction()).performTextInput("a 40 x 30 x 10 box")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
        assertEquals("a 40 x 30 x 10 box", viewModel.cadConversations.inApp.single().text)

        // The turn produced a tool row and is still running when the phone is rotated.
        viewModel.applyCadToolTrace(
            AgentToolTrace(
                callId = 1,
                tool = "execute",
                name = "solid.box",
                arguments = "{length=40, width=30, height=10}",
                running = false,
                durationMillis = 12,
                outcome = "1 body created (Body1)",
            ),
        )
        assertTrue(viewModel.cadBusy)

        recreate()

        // The transcript, the tool row and the running state are all still there.
        val inApp = viewModel.cadConversations.inApp
        assertEquals("a 40 x 30 x 10 box", inApp.first().text)
        assertEquals("solid.box", inApp[1].tool?.name)
        assertEquals("1 body created (Body1)", inApp[1].tool?.outcome)
        assertTrue("the turn is still running after the rotation", viewModel.cadBusy)
    }

    @Test
    fun theSelectedChatSourceSurvivesARotation() {
        hostTheChat()
        viewModel.chatSource = ChatSource.HARNESS
        compose.waitForIdle()

        recreate()

        assertEquals(ChatSource.HARNESS, viewModel.chatSource)
    }

    @Test
    fun theModellingChatsMessagesAndBusyFlagSurviveARotation() {
        hostTheChat()
        viewModel.modellingMessages = listOf(
            AiChatMessage(fromUser = true, text = "model a 20 mm cube"),
            AiChatMessage(fromUser = false, text = "the cube is in the Blender scene"),
        )
        viewModel.modellingBusy = true

        recreate()

        assertEquals(2, viewModel.modellingMessages.size)
        assertEquals("model a 20 mm cube", viewModel.modellingMessages[0].text)
        assertEquals("the cube is in the Blender scene", viewModel.modellingMessages[1].text)
        assertTrue("the modelling turn is still running after the rotation", viewModel.modellingBusy)
    }

    @Test
    fun aNotificationTapIsHandledOnceAndIsNotReplayedByTheRecreate() {
        hostTheChat()
        ChatOpenRequest.resetForTest()
        ChatOpenRequest.request()
        assertNotNull(
            "the running composition handles the notification tap",
            ChatOpenRequest.takeUnhandled(),
        )

        // The activity is really recreated: a new composition runs over the same process-wide
        // request state. With no new tap it must not open the chat again.
        recreate()

        assertNull(
            "a recreated activity must not replay the last notification tap",
            ChatOpenRequest.takeUnhandled(),
        )
    }
}

/** The real overlay, bound only to the ViewModel's chat state - the wiring the app uses. */
@Composable
private fun RotationChatHost(viewModel: MainViewModel) {
    AiChatOverlay(
        messages = viewModel.cadConversations.harness,
        onSend = {},
        onClose = {},
        onStop = { viewModel.cancelInAppTurn() },
        inApp = InAppChat(
            messages = viewModel.cadConversations.inApp,
            busy = viewModel.cadBusy,
            status = viewModel.cadStatus,
            backendLine = "In-app - fake-model at example.test",
            configured = true,
            onSend = { text ->
                viewModel.cadConversations = viewModel.cadConversations.appendInApp(
                    AiChatMessage(fromUser = true, text = text),
                )
                viewModel.cadBusy = true
            },
        ),
        source = viewModel.chatSource,
        onSourceChange = { viewModel.chatSource = it },
        authorLine = "Author: nobody",
        harnessLine = "Harness - not connected",
        modifier = Modifier.fillMaxSize(),
    )
}
