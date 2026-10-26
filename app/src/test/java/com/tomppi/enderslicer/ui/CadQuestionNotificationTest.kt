package com.tomppi.enderslicer.ui

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.cadagent.AgentQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The one notification the agent can post: posted once per waiting question, cancelled on answer
 * (or when the chat is opened/put in the foreground), and safe to post again next time.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CadQuestionNotificationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun manager() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun theNotificationIsPostedOnceAndCancelledOnAnswer() {
        val question = AgentQuestion("Which material should the lid use?", listOf("PLA"))

        CadQuestionNotification.post(context, question)
        assertEquals("one notification for the question", 1, shadowOf(manager()).getAllNotifications().size)
        assertTrue(CadQuestionNotification.isPosted())
        val notification = shadowOf(manager()).getAllNotifications().first()
        assertEquals(
            "CAD agent is asking a question",
            notification.extras.getCharSequence("android.title")?.toString(),
        )
        assertEquals(
            "Which material should the lid use?",
            notification.extras.getCharSequence("android.text")?.toString(),
        )

        // A second post for the same question must not stack a copy.
        CadQuestionNotification.post(context, question)
        assertEquals("still one notification", 1, shadowOf(manager()).getAllNotifications().size)

        CadQuestionNotification.cancel(context)
        assertTrue("the answer removes it", shadowOf(manager()).getAllNotifications().isEmpty())
        assertFalse(CadQuestionNotification.isPosted())

        // A later question can post again.
        CadQuestionNotification.post(context, AgentQuestion("Which colour?"))
        assertEquals(1, shadowOf(manager()).getAllNotifications().size)
        CadQuestionNotification.cancel(context)
    }
}
