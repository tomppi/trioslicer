package com.tomppi.enderslicer.printer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tomppi.enderslicer.MainActivity

/**
 * How the print ended, told to the phone rather than only to the screen.
 *
 * A print is tens of minutes of a device sitting there doing nothing visible, and the two
 * moments the user needs are the ones they will not be watching: it finished, or it stopped and
 * wants to know why. The reason klippy gives is carried into the body, because it is the whole
 * value of the notification - "Move out of range" and "Heater extruder not heating at expected
 * rate" want different things done about them.
 *
 * The channel is separate from the host service's, so a user who wants the printer quiet can
 * turn these off without turning off the running-host notification that keeps the process alive.
 */
internal object KlipperNotifications {
    private const val CHANNEL_ID = "print-results"
    private const val NOTIFICATION_ID = 0x4B10

    /** Post the result of a print that has just left printing or paused behind. */
    fun printEnded(context: Context, fileName: String?, printState: String, message: String?) {
        val title = when (printState) {
            "complete" -> "The print finished"
            "cancelled" -> "The print was cancelled"
            else -> "The print stopped"
        }
        val text = listOfNotNull(
            fileName?.takeIf { it.isNotBlank() },
            message?.takeIf { it.isNotBlank() },
        ).joinToString(" - ").ifBlank { "Klipper reported $printState." }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Print results",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "How a print on the Klipper host ended" },
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        val notification = builder
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    /** True when a print has just stopped being one, which is what is worth telling. */
    fun endedBetween(previous: String?, next: String?): Boolean {
        val wasRunning = previous == "printing" || previous == "paused"
        val isOver = next == "complete" || next == "error" || next == "cancelled"
        return wasRunning && isOver
    }
}
