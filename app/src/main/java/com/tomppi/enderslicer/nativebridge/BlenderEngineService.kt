package com.tomppi.enderslicer.nativebridge

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
 * Foreground keeper for the embedded Blender MCP engine.
 *
 * Without a foreground service the app process is an ordinary cached process:
 * the moment the screen locks (or the activity goes to background), Android
 * culls it -> the engine (in-process libblender_exec) and its MCP socket die
 * mid-generation. This service pins the process to foreground priority with a
 * persistent notification.
 *
 * The partial wake lock is not part of that pinning and is deliberately NOT
 * held for the life of the service. It exists so the CPU stays awake for the
 * engine's own work with the screen off, and that is a bounded lease: a start
 * request arms it for [WAKE_LOCK_TIMEOUT_MS] and it lapses unless another one
 * arrives. Holding it from onStartCommand to onDestroy - which is what it used
 * to do - meant that one launch kept the CPU awake until the explicit Stop
 * action, however idle the engine was in between.
 *
 * Started by [EnderSlicerApplication] / MainActivity alongside
 * [BlenderEngine.ensureStarted]; stopped via [stop] on engine shutdown.
 */
class BlenderEngineService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        // A start request is the only engine-use signal this service gets - the
        // app's own, at launch, when a model is handed to Blender, and when the
        // system restarts the culled service. It re-arms the bounded wake lock,
        // so the engine start that request asks for is not raced by a CPU that
        // is already on its way to suspending.
        acquireWakeLock()
        Log.i(TAG, "engine keeper foreground, wake lock armed for " + WAKE_LOCK_TIMEOUT_MS / 60_000 + " min")
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        Log.i(TAG, "engine keeper stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Arms the partial wake lock for the next [WAKE_LOCK_TIMEOUT_MS].
     *
     * Bounded, because nothing here can see the engine's work: the command loop
     * lives inside the engine's own thread and the service is only told when the
     * app wants the engine, not what it is doing. An unbounded lock is therefore
     * indistinguishable from a lock that never ends.
     */
    private fun acquireWakeLock() {
        // Released before it is taken again: a non-reference-counted wake lock
        // ignores a repeat acquire and keeps the expiry of the first one, which
        // would leave the lock running out underneath the request that just
        // refreshed it.
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
        private const val TAG = "BlenderEngineService"
        private const val CHANNEL_ID = "blender-engine"
        private const val NOTIF_ID = 0x6C61
        private const val WAKE_LOCK_TAG = "enderslicercura:blender-engine"

        /**
         * How long one request to the engine keeps the CPU awake: long enough
         * for the engine start it asked for (the first one extracts ~480 MB of
         * assets and loads a 1.3 GB library) and for the generation that follows
         * it, short enough that an engine nobody is working with stops pinning
         * the CPU to the end of the process's life.
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1_000L

        /** Idempotent start; safe to call before the app is foregrounded. */
        fun start(context: Context) {
            val intent = Intent(context, BlenderEngineService::class.java)
            try {
                context.startForegroundService(intent)
                Log.i(TAG, "start requested")
            } catch (error: Throwable) {
                Log.e(TAG, "start failed", error)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, BlenderEngineService::class.java))
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
                        "Blender engine",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "Keeps the embedded Blender MCP engine running" },
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
            val builder = Notification.Builder(context, CHANNEL_ID)
            return builder
                .setContentTitle("Blender engine running")
                .setContentText("Generation socket active; use the MCP port to drive it")
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build()
        }
    }
}
