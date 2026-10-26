package com.tomppi.enderslicer.cadagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.tomppi.enderslicer.MainActivity

/**
 * Foreground keeper for one in-app agent turn.
 *
 * Without a foreground service the app process is an ordinary cached process: the moment the
 * screen locks (or the activity goes to the background), Android culls it and the model call's
 * socket dies mid-turn - which is exactly the "worked for an unusually long time, then couldn't
 * reach the endpoint" report this was built from. This service pins the process to foreground
 * priority for the length of a turn, with its own notification naming the CAD agent as working.
 *
 * The partial wake lock is bounded, like the engine keeper's: it is armed when the turn starts
 * and given a fixed lease ([WAKE_LOCK_TIMEOUT_MS], a little past the longest bounded turn), so a
 * release that is ever missed still expires instead of pinning the CPU until the app ends. The
 * turn boundary releases it explicitly on every exit path - answer, error, cancel, service stop.
 *
 * **No screen-on flag.** The user asked for the screen to be allowed to sleep; the screen going
 * off is fine. What must not happen is the process being frozen, and that is the service's job.
 */
class InAppAgentTurnService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        acquireWakeLock()
        Log.i(
            TAG,
            "in-app agent turn keeper foreground; wake lock armed for " +
                WAKE_LOCK_TIMEOUT_MS / 60_000 + " min",
        )
        // A finished turn must not be resurrected by a restart: the conversation state it was
        // keeping alive lives in the activity, not here.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        Log.i(TAG, "in-app agent turn keeper stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Test-visible: true while this turn's partial wake lock is held. */
    internal fun isWakeLockHeld(): Boolean = wakeLock?.isHeld == true

    private fun acquireWakeLock() {
        // Released before it is re-armed: a non-reference-counted wake lock ignores a repeat
        // acquire and keeps the first lease's expiry, which would run out underneath a turn.
        releaseWakeLock()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val TAG = "InAppAgentTurnService"
        private const val CHANNEL_ID = "in-app-agent-turn"
        private const val NOTIF_ID = 0x6167
        private const val WAKE_LOCK_TAG = "enderslicercura:in-app-agent-turn"

        /**
         * The lease on the CPU for one turn.
         *
         * A turn is bounded by eight tool rounds, each of which can spend up to the client's
         * 180 s read timeout before it even runs a tool, so half an hour covers the longest
         * bounded turn. Past that, the lock lapses on its own rather than holding the CPU for
         * the life of the process.
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1_000L

        /** Idempotent; safe to call when the app is still foreground (a turn starts in it). */
        fun start(context: Context) {
            try {
                val app = context.applicationContext
                app.startForegroundService(Intent(app, InAppAgentTurnService::class.java))
            } catch (error: Throwable) {
                Log.e(TAG, "start failed", error)
            }
        }

        /** Stops the turn's keeper and ends its wake-lock lease. */
        fun stop(context: Context) {
            try {
                val app = context.applicationContext
                app.stopService(Intent(app, InAppAgentTurnService::class.java))
            } catch (error: Throwable) {
                Log.e(TAG, "stop failed", error)
            }
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "CAD agent turn",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "Keeps an in-app CAD agent turn running while the screen is off" },
                )
            }
        }

        private fun buildNotification(context: Context): Notification {
            val contentIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            // minSdk is 29, so the channel-aware builder is the only one.
            return Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("CAD agent working")
                .setContentText("The in-app agent is changing the CAD document; this keeps the turn alive with the screen off")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build()
        }
    }
}

/**
 * The turn boundary the app calls.
 *
 * One place to start and stop the keeper keeps the lifecycle easy to read in the turn's
 * `try/finally`: begin before any request leaves the phone, end on answer, error and cancel.
 */
object InAppTurnKeeper {
    fun begin(context: Context) = InAppAgentTurnService.start(context)

    fun end(context: Context) = InAppAgentTurnService.stop(context)
}
