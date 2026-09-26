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
import android.system.Os
import android.util.Log
import com.tomppi.enderslicer.MainActivity
import java.io.File
import java.io.IOException

/**
 * Foreground keeper and launcher for the embedded Klipper host (klippy).
 *
 * klippy runs as its OWN PROCESS, not in the app's. Two reasons:
 *
 *  - The app already carries a CPython 3.11 statically linked as libcpython.so
 *    for Blender, and this port built its own libpython3.11.so. Two interpreters
 *    in one process means two libpythons competing for the same symbols.
 *  - Klipper is GPLv3 and stays unmodified and separate: TrioSlicer hosts a
 *    process, it does not contain Klipper.
 *
 * The payload lives in assets/klipper and the executables in jniLibs, following
 * the same pattern as libblender_exec.so. Assets are extracted to filesDir on
 * first run (and after an app update), and the libraries are copied out of
 * nativeLibraryDir into the same tree - jniLibs only ships files named lib*.so,
 * and the interpreter asks for libpython3.11.so.1.0 by soname, so the name it
 * wants has to be created somewhere writable.
 *
 * The wake lock IS held for the life of a run here, unlike BlenderEngineService
 * where it is a bounded lease. A print takes hours with the screen off: the CPU
 * staying awake is the requirement, not an optimisation.
 */
class KlipperEngineService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var process: Process? = null

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
        if (process == null) {
            acquireWakeLock()
            Thread({ launch() }, "klipper-launch").start()
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrioSlicer::KlipperHost")
            wakeLock?.setReferenceCounted(false)
        }
        // Held until stop(): the printer must be fed with the screen off.
        wakeLock?.acquire()
    }

    /** Extract the payload, place the libraries, then run. */
    private fun launch() {
        try {
            val root = File(filesDir, "klipper")
            val lib = File(root, "lib")
            extractPayload(root)
            placeLibraries(lib)
            val exe = File(lib, "libklipper_exec.so")
            if (!exe.exists()) {
                Log.e(TAG, "interpreter missing at ${exe.absolutePath}")
                return
            }
            // First light: prove the payload runs before wiring a printer to it.
            val probe = "import sys, cffi, greenlet, serial, jinja2; " +
                "print('klipper payload ok:', sys.version.split()[0], " +
                "'cffi', cffi.__version__, 'greenlet', greenlet.__version__, " +
                "'pyserial', serial.__version__)"
            val pb = ProcessBuilder(exe.absolutePath, "-c", probe)
            pb.directory(root)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["PYTHONHOME"] = root.absolutePath
            env["LD_LIBRARY_PATH"] = lib.absolutePath
            env["PYTHONDONTWRITEBYTECODE"] = "1"
            val proc = pb.start()
            process = proc
            // Android's Process has no pid() (that is a JVM method), so the start
            // is logged without one.
            Log.i(TAG, "klipper interpreter started")
            proc.inputStream.bufferedReader().forEachLine { line ->
                Log.i(TAG, "klipper: $line")
            }
            val code = proc.waitFor()
            Log.i(TAG, "klipper exited with $code")
        } catch (e: Exception) {
            Log.e(TAG, "could not start klipper", e)
        } finally {
            process = null
        }
    }

    /** Copy assets/klipper into filesDir, once per app version. */
    private fun extractPayload(root: File) {
        val stamp = File(root, ".extracted")
        val marker = "${packageManager.getPackageInfo(packageName, 0).lastUpdateTime}"
        if (stamp.exists() && stamp.readText() == marker) return
        Log.i(TAG, "extracting klipper payload to ${root.absolutePath}")
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()
        copyAssetTree("klipper", root)
        stamp.writeText(marker)
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            // A file, not a directory.
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (child in children) copyAssetTree("$assetPath/$child", File(target, child))
    }

    /**
     * The interpreter and its libraries, plus the soname it asks for.
     *
     * jniLibs cannot ship "libpython3.11.so.1.0" because only lib*.so is
     * extracted, so the name is created here as a symlink next to the copy.
     */
    private fun placeLibraries(lib: File) {
        lib.mkdirs()
        val nativeDir = File(applicationInfo.nativeLibraryDir)
        for (name in listOf("libklipper_exec.so", "libpython3.11.so", "libc++_shared.so")) {
            val from = File(nativeDir, name)
            val to = File(lib, name)
            if (from.exists() && (!to.exists() || to.length() != from.length())) {
                from.copyTo(to, overwrite = true)
                to.setExecutable(true, false)
            }
        }
        val soname = File(lib, "libpython3.11.so.1.0")
        if (!soname.exists()) {
            try {
                Os.symlink("libpython3.11.so", soname.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "symlink failed, copying instead: ${e.message}")
                File(lib, "libpython3.11.so").copyTo(soname, overwrite = true)
            }
        }
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopEngine() {
        process?.let { if (it.isAlive) it.destroy() }
        process = null
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    companion object {
        private const val TAG = "KlipperEngine"
        private const val NOTIF_ID = 4711
        private const val CHANNEL_ID = "klipper_host"

        fun start(context: Context) {
            val intent = Intent(context, KlipperEngineService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KlipperEngineService::class.java))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID, "Klipper host",
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = "Runs the Klipper host that drives the printer"
            manager.createNotificationChannel(channel)
        }

        private fun buildNotification(context: Context): Notification {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(context)
            }
            return builder
                .setContentTitle("Klipper host running")
                .setContentText("The printer is being driven by this device")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }
}
