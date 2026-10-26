package com.tomppi.enderslicer.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.tomppi.enderslicer.MainActivity
import com.tomppi.enderslicer.cadagent.AgentQuestion

/**
 * The one notification the in-app agent can post: "the CAD agent is asking a question".
 *
 * A question arrives when the agent ends its turn, and the user may not be looking at the chat.
 * When the app is in the background this notification says so and opens the chat; when the app is
 * in the foreground the rail's **Ask AI** item carries the pulse instead, and this is cancelled.
 * Everything is idempotent: [post] posts one notification (same id, guarded by a flag) and
 * [cancel] removes it, so a redraw cannot stack copies.
 *
 * If the user has denied POST_NOTIFICATIONS, Android drops the notification silently - the rail
 * pulse is the fallback, and it is part of the same pending-question state.
 */
object CadQuestionNotification {
    private const val CHANNEL_ID = "cad-agent-question"
    private const val NOTIFICATION_ID = 0x6171

    @Volatile
    private var posted = false

    fun post(context: Context, question: AgentQuestion) {
        if (posted) return
        ensureChannel(context)
        val openChat = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .putExtra(ChatOpenRequest.EXTRA_OPEN_AI_CHAT, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("CAD agent is asking a question")
            .setContentText(question.question.take(140))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openChat)
            .setAutoCancel(true)
            .build()
        // Denied POST_NOTIFICATIONS drops this silently; the rail pulse remains.
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
            posted = true
        }
    }

    fun cancel(context: Context) {
        posted = false
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
        }
    }

    /** Test-visible: whether [post] believes it has a live notification. */
    internal fun isPosted(): Boolean = posted

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "CAD agent questions",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "The in-app CAD agent is waiting for an answer" },
            )
        }
    }
}
