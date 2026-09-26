package com.tomppi.enderslicer.nativebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.system.Os
import android.util.Log
import com.tomppi.enderslicer.MainActivity
import com.tomppi.enderslicer.printer.KlipperClient
import com.tomppi.enderslicer.printer.KlipperPty
import com.tomppi.enderslicer.printer.PrinterBridge
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

    /**
     * The master end of the printer's pty, or -1 before one exists.
     *
     * Kept because a start request that arrives while klippy is already running is
     * about the printer rather than the host - it has just been plugged in, or
     * permission for it has just been granted - and this is the descriptor the bridge
     * needs to act on that.
     */
    private var ptyMasterFd = -1

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
        } else {
            // klippy is already up, so this request is about the printer. It has just
            // been plugged in, or permission for it has just been granted, and the pty
            // has been sitting unbridged since the host started. Without this the
            // printer is on the bus, permitted and connected to nothing, which looks
            // from every log exactly like a printer that is not there.
            Thread({ bridgePrinterIfNeeded() }, "klipper-bridge").start()
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

    /** Extract the payload, prepare a printer, then run klippy. */
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
            // The printer is reached through a pty this process owns one end of:
            // klippy opens a device node and applies termios to it, and there is no
            // device node to give it.
            val pty = KlipperPty.open()
            if (pty == null) {
                Log.e(TAG, "could not open a pty for the printer")
                return
            }
            Log.i(TAG, "printer pty ${pty.slavePath}, master fd ${pty.masterFd}")
            ptyMasterFd = pty.masterFd
            val config = writePrinterConfig(root, pty.slavePath)
            bridgePrinter(pty.masterFd)

            // Truncated first: klippy appends to the log it is given, so without
            // this a run is read together with every run before it and the failures
            // of an old one look like the failures of this one.
            val klippyLog = File(filesDir, "klippy.log")
            klippyLog.writeText("")

            // -I is where klippy puts a pty of its own for legacy clients, and its
            // default is /tmp/printer - there is no /tmp an app may write to. -l is
            // not decoration either: without a log file klippy logs to stderr and
            // warns that the timing it costs may be severe.
            val pb = ProcessBuilder(
                exe.absolutePath, File(root, "klippy/klippy.py").absolutePath,
                "-I", File(root, "printer").absolutePath,
                "-a", File(filesDir, "klippy.sock").absolutePath,
                "-l", klippyLog.absolutePath,
                config.absolutePath,
            )
            pb.directory(root)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["PYTHONHOME"] = root.absolutePath
            env["LD_LIBRARY_PATH"] = "${libexec.absolutePath}:${nativeDir.absolutePath}"
            env["PYTHONDONTWRITEBYTECODE"] = "1"
            Log.i(TAG, "starting klippy: ${pb.command().joinToString(" ")}")
            val proc = pb.start()
            process = proc
            // klippy's API is what the app's front end talks to, so it is proved here
            // as soon as klippy opens it. This runs on its own thread because the one
            // below reads klippy's output until it exits.
            Thread({ proveApi() }, "klipper-api").start()
            // Android's Process has no pid() (that is a JVM method), so the start
            // is logged without one. What klippy writes goes to its log file; what
            // reaches here is a startup failure or a traceback.
            proc.inputStream.bufferedReader().forEachLine { line ->
                Log.i(TAG, "klippy: $line")
            }
            val code = proc.waitFor()
            Log.i(TAG, "klippy exited with $code")
        } catch (error: Throwable) {
            // Throwable, not Exception: a missing libklipper_pty.so surfaces as an
            // UnsatisfiedLinkError, and an uncaught one takes the app's process down.
            Log.e(TAG, "klipper host stopped", error)
        } finally {
            process = null
        }
    }

    /**
     * First light on klippy's API, and the app's way in to it.
     *
     * The socket exists from the moment klippy starts, so the first answer is not the
     * interesting one - it is asked again until the micro-controller handshake is done
     * and the printer reports itself ready, or until it is clear it will not. A
     * failure here is not fatal to the host: it means there is nothing for a front end
     * to talk to yet.
     */
    private fun proveApi() {
        val path = File(filesDir, "klippy.sock")
        val client = KlipperClient(path.absolutePath)
        try {
            // Retried until it connects, not until the path exists: klippy removes and
            // recreates that file, and the one a previous run left behind is a socket
            // nobody is listening on. Waiting for the name and connecting once got
            // "Connection refused" three milliseconds after klippy started.
            val waitUntil = System.currentTimeMillis() + API_WAIT_MS
            while (true) {
                try {
                    client.connect(readTimeoutMs = 10000)
                    break
                } catch (e: Exception) {
                    if (System.currentTimeMillis() > waitUntil) throw e
                    Thread.sleep(500)
                }
            }
            var info = client.info()
            val readyUntil = System.currentTimeMillis() + API_READY_WAIT_MS
            while (info.optString("state") != "ready"
                && System.currentTimeMillis() < readyUntil
                && process != null
            ) {
                Thread.sleep(2000)
                info = client.info()
            }
            Log.i(TAG, "klippy is ${info.optString("state")} " +
                "(${info.optString("software_version")}, mcu ${info.optString("mcu_version")})")
            if (info.optString("state") != "ready") {
                Log.w(TAG, "printer not ready: ${info.optString("state_message")}")
                return
            }
            val status = client.query("extruder", "heater_bed", "toolhead")
            Log.i(TAG, "printer: extruder=${status.optJSONObject("extruder")?.optDouble("temperature")}C " +
                "bed=${status.optJSONObject("heater_bed")?.optDouble("temperature")}C " +
                "position=${status.optJSONObject("toolhead")?.optJSONArray("position")} " +
                "homed='${status.optJSONObject("toolhead")?.optString("homed_axes")}'")
        } catch (e: Exception) {
            Log.w(TAG, "klippy API not reachable: ${e.message}")
        } finally {
            client.close()
        }
    }

    /**
     * The board's own configuration, with the two paths this device has to supply.
     *
     * Pins, kinematics and probe offsets belong to the printer and are not touched
     * here; only the serial port and the gcode directory differ from the file the
     * same printer is set up with on a host.
     */
    private fun writePrinterConfig(root: File, serialPath: String): File {
        val gcodes = File(filesDir, "gcodes").apply { mkdirs() }
        val template = assets.open("klipper-host/printer.cfg")
            .bufferedReader().use { it.readText() }
        val config = File(root, "printer.cfg")
        config.writeText(
            template.replace("__SERIAL__", serialPath)
                .replace("__GCODES__", gcodes.absolutePath),
        )
        Log.i(TAG, "printer config ${config.absolutePath}: serial $serialPath")
        return config
    }

    /**
     * Hand the other end of the pty to the printer, if one is attached and permitted.
     *
     * Nothing here is required for klippy to start: with no printer it simply keeps
     * trying to reach the MCU, which is the state a user sees before plugging one in.
     */
    /** Bridge now, if there is a printer to bridge and it is not bridged already. */
    private fun bridgePrinterIfNeeded() {
        if (PrinterBridge.isRunning) {
            Log.i(TAG, "printer already bridged")
            return
        }
        if (ptyMasterFd < 0) {
            Log.w(TAG, "no pty to bridge the printer to")
            return
        }
        bridgePrinter(ptyMasterFd)
    }

    private fun bridgePrinter(masterFd: Int) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val serial = PrinterBridge.findPrinter(manager)
        if (serial == null) {
            Log.i(TAG, "no printer on USB; klippy will wait for one")
            return
        }
        if (!manager.hasPermission(serial.device)) {
            Log.i(TAG, "printer ${serial.device.deviceName} is attached but not permitted")
            return
        }
        if (!PrinterBridge.start(manager, serial, masterFd)) {
            Log.w(TAG, "could not bridge ${serial.device.deviceName}")
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
        // The bridge holds the USB port and the pty master: leaving it running would
        // keep both claimed by a host that is no longer there.
        PrinterBridge.stop()
        ptyMasterFd = -1
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    companion object {
        private const val TAG = "KlipperEngine"
        private const val NOTIF_ID = 4711
        private const val CHANNEL_ID = "klipper_host"

        /** How long to wait for klippy to create its API socket. */
        private const val API_WAIT_MS = 30_000L

        /** How long to keep asking until the printer calls itself ready. */
        private const val API_READY_WAIT_MS = 90_000L

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
