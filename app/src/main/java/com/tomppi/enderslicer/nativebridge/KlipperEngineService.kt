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
 * The payload lives in assets/klipper and the interpreter in jniLibs. Assets are
 * extracted to filesDir on first run (and after an app update), but the
 * interpreter is executed from nativeLibraryDir and never copied there: an app
 * targeting API 29 or later may not execute a file in its own data directory
 * (SELinux neverallows execute_no_trans on app_data_file, a W^X rule, while
 * granting it on apk_data_file - which is what nativeLibraryDir is). jniLibs
 * only ships files named lib*.so, so the soname the interpreter asks for by
 * name, libpython3.11.so.1.0, is created as a symlink beside the payload.
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

    /** Extract the payload, link the interpreter's soname, then run. */
    private fun launch() {
        try {
            val root = File(filesDir, "klipper")
            val libexec = File(root, "libexec")
            extractPayload(root)
            linkPythonLibrary(libexec)
            val nativeDir = File(applicationInfo.nativeLibraryDir)
            val exe = File(nativeDir, "libklipper_exec.so")
            if (!exe.isFile) {
                Log.e(TAG, "interpreter missing at ${exe.absolutePath}")
                return
            }
            // First light: prove the payload runs before wiring a printer to it.
            // Every step prints as it goes, so a failure still shows how far the
            // interpreter got.
            val probe = """
                import sys
                print('python', sys.version.split()[0])
                import cffi, greenlet, serial, jinja2
                print('deps ok cffi', cffi.__version__, 'greenlet', greenlet.__version__,
                      'pyserial', serial.__version__)
                from klippy import chelper
                print('chelper ok', chelper.__file__)
            """.trimIndent()
            val pb = ProcessBuilder(exe.absolutePath, "-c", probe)
            pb.directory(root)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["PYTHONHOME"] = root.absolutePath
            env["LD_LIBRARY_PATH"] = "${libexec.absolutePath}:${nativeDir.absolutePath}"
            env["PYTHONDONTWRITEBYTECODE"] = "1"
            Log.i(TAG, "exec ${exe.absolutePath} PYTHONHOME=${root.absolutePath}")
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

    /**
     * Copy assets/klipper into filesDir, once per app version.
     *
     * The stamp lives beside the payload rather than inside it: the tree is walked
     * from assets, so anything of our own in there is at best confusing and at
     * worst collides with a path the walk wants to create.
     *
     * It is written only once the copy has been checked, and a stamp left by an
     * earlier run is only trusted while that check still passes. Writing it
     * unconditionally once turned a failed extraction into a later, unrelated
     * looking error somewhere else entirely.
     */
    private fun extractPayload(root: File) {
        val stamp = File(filesDir, "klipper.extracted")
        val marker = "${packageManager.getPackageInfo(packageName, 0).lastUpdateTime}"
        if (stamp.exists() && stamp.readText() == marker && payloadIsComplete(root)) {
            Log.i(TAG, "payload already extracted for this version")
            return
        }
        Log.i(TAG, "extracting klipper payload to ${root.absolutePath}")
        // Failing here rather than carrying on is deliberate: a tree that cannot
        // be cleared cannot be written into either, and every file would fail on
        // its own with a much less obvious error.
        if (root.exists() && !root.deleteRecursively()) {
            throw IOException("could not clear ${root.absolutePath}")
        }
        if (!root.mkdirs() && !root.isDirectory) {
            throw IOException("could not create ${root.absolutePath}")
        }
        copyAssetTree("klipper", root)
        if (!payloadIsComplete(root)) {
            throw IOException("payload incomplete after extraction")
        }
        stamp.writeText(marker)
        Log.i(TAG, "payload extracted: ${root.walkTopDown().count { it.isFile }} files")
    }

    /** Files whose absence means the extraction did not really finish. */
    private fun payloadIsComplete(root: File) =
        PAYLOAD_SENTINELS.all { File(root, it).isFile }

    /**
     * Copy one asset entry, recursing into directories.
     *
     * Whether a path is a file or a directory is decided by trying to open it:
     * AssetManager.list() returns null for a file rather than an empty array, so
     * inferring from it misreads leaves and ends up opening a directory.
     *
     * The open is the only step allowed to fail quietly. Once it has succeeded
     * the copy must not, or a real write error - a directory the app cannot write
     * into - looks like "this path is a directory", the walk carries on, and the
     * extraction reports success having copied nothing at all.
     */
    private fun copyAssetTree(assetPath: String, target: File) {
        val input = try {
            assets.open(assetPath)
        } catch (e: IOException) {
            null
        }
        if (input != null) {
            input.use { source ->
                target.parentFile?.mkdirs()
                target.outputStream().use { source.copyTo(it) }
            }
            return
        }
        target.mkdirs()
        for (child in assets.list(assetPath).orEmpty()) {
            copyAssetTree("$assetPath/$child", File(target, child))
        }
    }

    /**
     * The one name the interpreter cannot be given any other way.
     *
     * libklipper_exec.so is linked against the soname libpython3.11.so.1.0, but
     * jniLibs only ships files named lib*.so, so the file it sits beside is
     * libpython3.11.so and the loader finds nothing under the name it asks for. A
     * symlink carrying the soname closes that gap: it resolves into
     * nativeLibraryDir, which is where the library has to stay anyway, because it
     * cannot be executed from app data.
     */
    private fun linkPythonLibrary(libexec: File) {
        if (!libexec.mkdirs() && !libexec.isDirectory) {
            throw IOException("could not create ${libexec.absolutePath}")
        }
        val target = File(applicationInfo.nativeLibraryDir, "libpython3.11.so")
        if (!target.isFile) throw IOException("${target.absolutePath} is missing")
        val soname = File(libexec, "libpython3.11.so.1.0")
        try {
            Os.symlink(target.absolutePath, soname.absolutePath)
            Log.i(TAG, "linked ${soname.name} to ${target.absolutePath}")
        } catch (e: Exception) {
            // Already there from an earlier run of this app version. The payload is
            // re-extracted whenever the version changes, so the link cannot be
            // pointing at a directory that no longer exists - and if it somehow is,
            // exists() follows the link and the check below says so.
            Log.i(TAG, "soname link already in place: ${e.message}")
        }
        if (!soname.exists()) {
            throw IOException("soname link does not resolve: ${soname.absolutePath}")
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

        /**
         * Files whose absence means the payload is not usable: the host itself,
         * the compiled C helper it loads through cffi, and one file from the
         * standard library. A copy that fails part way through - or a tree left
         * behind by an interrupted one - has to be caught here, not by whatever
         * reaches for a missing file much later.
         */
        private val PAYLOAD_SENTINELS = listOf(
            "klippy/klippy.py",
            "klippy/chelper/c_helper.so",
            "lib/python3.11/encodings/__init__.py",
        )

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
