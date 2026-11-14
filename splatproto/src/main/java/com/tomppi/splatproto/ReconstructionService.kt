package com.tomppi.splatproto

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.concurrent.thread

/** What the reconstruction is doing; observed by the UI. */
sealed interface Job {
    data object Idle : Job
    data class Running(val scan: Scan, val stage: String, val fraction: Float, val cancelling: Boolean = false) : Job
    data class Done(val scan: Scan) : Job
    data class Failed(val scan: Scan, val message: String) : Job
}

/**
 * Runs a reconstruction in a foreground service, so it survives the user leaving the app or
 * the screen turning off. Progress is shown in a notification with a Cancel action.
 */
class ReconstructionService : Service() {
    companion object {
        private const val CHANNEL = "reconstruction"
        private const val NOTIFICATION = 1
        private const val EXTRA_SCAN = "scan"
        private const val ACTION_CANCEL = "com.tomppi.splatproto.CANCEL"

        private val _state = MutableStateFlow<Job>(Job.Idle)
        val state: StateFlow<Job> = _state
        @Volatile private var cancel = false

        fun start(ctx: Context, scan: Scan) {
            if (_state.value is Job.Running) return
            cancel = false
            _state.value = Job.Running(scan, "Starting", 0f)
            val i = Intent(ctx, ReconstructionService::class.java).putExtra(EXTRA_SCAN, scan.dir.absolutePath)
            runCatching { ctx.startForegroundService(i) }.onFailure {
                _state.value = Job.Failed(scan, "Could not start the reconstruction service: ${it.message}")
            }
        }

        fun cancel() {
            cancel = true
            (_state.value as? Job.Running)?.let { _state.value = it.copy(cancelling = true) }
        }

        /** The UI has shown the result. */
        fun acknowledge() {
            if (_state.value !is Job.Running) _state.value = Job.Idle
        }
    }

    private lateinit var manager: NotificationManager
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { cancel(); return START_NOT_STICKY }
        val dir = intent?.getStringExtra(EXTRA_SCAN) ?: run { stopSelf(); return START_NOT_STICKY }
        val scan = Scan(File(dir))
        manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Reconstruction", NotificationManager.IMPORTANCE_LOW))
        val n = notification(scan, "Starting", 0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION, n)
        }
        val processing = DeviceProbe.processing(this)
        val markerMm = Settings.markerSizeMm(this)
        thread(name = "parallax-reconstruction") {
            val result = Reconstruction.run(scan, processing, markerMm) { stage, f ->
                _state.value = Job.Running(scan, stage, f, cancel)
                val now = System.currentTimeMillis()
                if (now - lastNotified > 500) {
                    lastNotified = now
                    manager.notify(NOTIFICATION, notification(scan, stage, f))
                }
                !cancel
            }
            _state.value = result.fold({ Job.Done(scan) }, { Job.Failed(scan, it.message?.lineSequence()?.firstOrNull() ?: "Failed") })
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(scan: Scan, stage: String, fraction: Float): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, PipelineActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val cancelIntent = PendingIntent.getService(this, 1, Intent(this, ReconstructionService::class.java)
            .setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Reconstructing ${scan.title}")
            .setContentText("$stage · ${(fraction * 100).toInt()} %")
            .setProgress(100, (fraction * 100).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Cancel", cancelIntent).build())
            .build()
    }
}

/** Small persistent settings. */
object Settings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** Edge length of the printed ArUco scale marker (mm); 0 = not used. */
    fun markerSizeMm(ctx: Context): Int = prefs(ctx).getInt("markerSizeMm", 0)
    fun setMarkerSizeMm(ctx: Context, mm: Int) = prefs(ctx).edit().putInt("markerSizeMm", mm).apply()
}
