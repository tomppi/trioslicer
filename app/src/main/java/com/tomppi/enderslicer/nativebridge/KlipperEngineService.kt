package com.tomppi.enderslicer.nativebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.Os
import android.util.Log
import com.tomppi.enderslicer.MainActivity
import com.tomppi.enderslicer.printer.KlipperClient
import com.tomppi.enderslicer.printer.KlipperConfigFile
import com.tomppi.enderslicer.printer.KlipperHostFiles
import com.tomppi.enderslicer.printer.KlipperPrint
import com.tomppi.enderslicer.printer.KlipperPrinterState
import com.tomppi.enderslicer.printer.KlipperPty
import com.tomppi.enderslicer.printer.withStatus
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
     * True while the engine is being taken down on purpose.
     *
     * klippy exiting is not always a failure: SAVE_CONFIG restarts it deliberately,
     * and its host is expected to start it again. That makes "it exited" and "it
     * should stay exited" two different things, and this is the second one.
     */
    @Volatile private var stopping = false

    /**
     * The master end of the printer's pty, or -1 before one exists.
     *
     * Kept because a start request that arrives while klippy is already running is
     * about the printer rather than the host - it has just been plugged in, or
     * permission for it has just been granted - and this is the descriptor the bridge
     * needs to act on that.
     */
    private var ptyMasterFd = -1

    private val usbPermission = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "permission for ${device?.deviceName}: $granted")
            if (granted) Thread({ bridgePrinterIfNeeded() }, "klipper-bridge").start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        // For as long as the service runs: the answer to a USB permission request is a
        // broadcast, and the request is not the only thing that happens in this process.
        registerReceiver(
            usbPermission,
            IntentFilter(ACTION_USB_PERMISSION),
            if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        if (intent?.getStringExtra(EXTRA_ACTION) == ACTION_RESTART) {
            // Rebuild the host from the socket down, on a thread of its own: the
            // front end asks for this when the link has wedged or the configuration
            // has been saved, and both mean the process, the pty and the bridge all
            // have to go together.
            Thread({ restartHost() }, "klipper-restart").start()
            return START_STICKY
        }
        if (process == null) {
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
    /**
     * One host at a time, whatever the intents say.
     *
     * A restart clears the process before the new one exists, so a start arriving while the
     * restart unwinds - a rotation re-delivering the USB attach intent, or a Start button
     * pressed while the screen says "not reachable" - began a second launch. Two klippy
     * processes then share a socket path and a log, the second unlinks the first's socket,
     * and the app talks to whichever won. The lock is held across a restart too, so the
     * second request waits rather than racing.
     */
    private val launchLock = Any()

    /** True between claiming a start and that start finishing, so two starts cannot overlap. */
    @Volatile
    private var starting = false

    /** Set when the service is destroyed: a start after that would be a host nothing can stop. */
    @Volatile
    private var destroyed = false

    private fun launch() {
        // The lock covers the decision only. Holding it while the process ran was how a restart
        // parked for ever: restartHost waited on the same monitor the running host was holding,
        // and klippy exits by itself only after SAVE_CONFIG or a crash.
        synchronized(launchLock) {
            if (destroyed) {
                Log.i(TAG, "the service is gone; a host will not be started")
                return
            }
            if (process != null || starting) {
                Log.i(TAG, "a host is already running; ignoring the start")
                return
            }
            starting = true
        }
        try {
            launchLocked()
        } finally {
            starting = false
        }
    }

    private fun launchLocked() {
        stopping = false
        // Held so that the finally below can tell this run apart from the next one:
        // a restart starts the new host before this one has finished unwinding.
        var started: Process? = null
        try {
            val root = File(filesDir, "klipper")
            val libexec = File(root, "libexec")
            extractPayload(root)
            linkPythonLibrary(libexec)
            val nativeDir = File(applicationInfo.nativeLibraryDir)
            val exe = File(nativeDir, "libklipper_exec.so")
            if (!exe.isFile) {
                Log.e(TAG, "interpreter missing at ${exe.absolutePath}")
                reportHostNotRunning("the interpreter for the host is missing from this build")
                return
            }
            // The printer is reached through a pty this process owns one end of:
            // klippy opens a device node and applies termios to it, and there is no
            // device node to give it.
            // Whatever the last run left behind goes first. A host that exited on its own - a
            // crash, an OOM kill - left the bridge pumping a pty whose klippy is gone, and
            // PrinterBridge.start() answers "already running" without adopting the new one:
            // the restarted klippy would be pointed at a node nobody reads. Closing the old
            // master here is also what keeps a crash loop from leaking one per relaunch.
            PrinterBridge.stop()
            if (ptyMasterFd >= 0) {
                runCatching { ParcelFileDescriptor.adoptFd(ptyMasterFd).close() }
                ptyMasterFd = -1
            }
            val pty = KlipperPty.open()
            if (pty == null) {
                Log.e(TAG, "could not open a pty for the printer")
                reportHostNotRunning("the app could not open a connection for the printer")
                return
            }
            Log.i(TAG, "printer pty ${pty.slavePath}, master fd ${pty.masterFd}")
            ptyMasterFd = pty.masterFd
            val config = printerConfig(serialPath(pty.slavePath))
            bridgePrinter(pty.masterFd)
            // Held for the life of a run, and taken only now that there is a run to hold: a
            // restart releases it on the way out, so a lock taken by the caller was gone for
            // good after the first restart - and taken before these checks, it was held by a
            // launch that had already given up, pinning the phone awake with no host.
            acquireWakeLock()

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
            started = proc
            // klippy's API is what the app's front end talks to, so it is proved here
            // as soon as klippy opens it. This runs on its own thread because the one
            // below reads klippy's output until it exits.
            Thread({ watchHost() }, "klipper-api").start()
            // Android's Process has no pid() (that is a JVM method), so the start
            // is logged without one. What klippy writes goes to its log file; what
            // reaches here is a startup failure or a traceback.
            proc.inputStream.bufferedReader().forEachLine { line ->
                Log.i(TAG, "klippy: $line")
            }
            val code = proc.waitFor()
            Log.i(TAG, "klippy exited with $code")
            // Started again rather than left dead. klippy exits on its own after
            // SAVE_CONFIG, which is the app telling the user their calibration was
            // written and then leaving the printer with no host at all - and it
            // exits when it crashes, where a restart is the only recovery a user
            // has. Only when this is still the current run and nobody asked it to
            // stop: a restart has already assigned the next process, and onDestroy
            // means stay down.
            if (process === proc && !stopping) {
                Log.i(TAG, "starting the host again in ${RELAUNCH_DELAY_MS}ms")
                Thread.sleep(RELAUNCH_DELAY_MS)
                if (process === proc && !stopping) {
                    Thread({ launch() }, "klipper-launch").start()
                }
            }
        } catch (error: Throwable) {
            // Throwable, not Exception: a missing libklipper_pty.so surfaces as an
            // UnsatisfiedLinkError, and an uncaught one takes the app's process down.
            Log.e(TAG, "klipper host stopped", error)
        } finally {
            // Only when this run is still the current one. A restart assigns the
            // next process before this one has finished unwinding, and clearing the
            // field here would leave the service believing nothing is running -
            // while a klippy it can no longer stop keeps the printer.
            if (process === started) process = null
        }
    }

    /**
     * Follow the host through klippy's API: first light, then the numbers that matter.
     *
     * The socket exists from the moment klippy starts, so the first answer is not the
     * interesting one - it is asked again until the micro-controller handshake is done
     * and the printer reports itself ready, or until it is clear it will not. A
     * failure here is not fatal to the host: it means there is nothing for a front end
     * to talk to yet.
     *
     * After that it keeps recording the timing margins. A print runs with the screen
     * off, so no printer screen is open and these would otherwise never be written
     * down: the lookahead against the one-to-two seconds klippy aims to keep queued,
     * the stall count, the round trip and the retransmits. That is the measurement the
     * feasibility question needs, taken from the machine doing the work.
     */
    private fun watchHost() {
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
            recordMargins(client)
        } catch (e: Exception) {
            Log.w(TAG, "klippy API not reachable: ${e.message}")
        } finally {
            client.close()
        }
    }

    /**
     * Write the host's timing margins down while it runs.
     *
     * Every [MARGIN_POLL_MS] at most, and immediately whenever something goes wrong
     * with them, so a long print leaves a record without filling the log.
     */
    private fun recordMargins(client: KlipperClient) {
        var state = KlipperPrinterState(connected = true)
        var lastStalls = 0
        var lastRetransmits = 0
        var lastReport = System.currentTimeMillis()
        // Tracked separately from lastRetransmits, which is only written when a line
        // is logged; this has to be updated on every poll to mean anything.
        var previousRetransmits = 0
        var climbing = 0
        var lastRestart = 0L
        while (process != null && client.isConnected) {
            Thread.sleep(MARGIN_POLL_MS)
            state = state.withStatus(client.query("mcu", "toolhead", "print_stats"))
            val stalls = state.printStalls ?: 0
            val retransmits = state.timing?.retransmittedBytes ?: 0
            val now = System.currentTimeMillis()

            // The wedge, as it has looked every time: the micro-controller stops
            // answering and the retransmit counter climbs without an acknowledgement
            // ever arriving. Nothing recovers from it on its own - the bridge keeps
            // reporting itself healthy - so the host rebuilds itself rather than
            // waiting for someone to notice a dead print. Twenty seconds of climbing is
            // the signal; the startup burst is a handful and then stops.
            climbing = if (retransmits > previousRetransmits) climbing + 1 else 0
            previousRetransmits = retransmits
            if (climbing >= WEDGE_SAMPLES && now - lastRestart >= RESTART_COOLDOWN_MS) {
                Log.w(TAG, "link wedged: retransmits climbing for " +
                    (climbing * MARGIN_POLL_MS / 1000) + "s, now " + retransmits +
                    "; restarting the host")
                restartHost()
                return
            }

            if (stalls != lastStalls || retransmits != lastRetransmits
                || now - lastReport >= MARGIN_LOG_MS
            ) {
                Log.i(TAG, "margins: lookahead=${state.lookaheadSeconds?.let { "%.2fs".format(it) } ?: "-"} " +
                    "stalls=$stalls retransmits=$retransmits " +
                    "srtt=${state.timing?.roundTripSeconds?.let { "%.1fms".format(it * 1000) } ?: "-"} " +
                    "rto=${state.timing?.retransmitTimeoutSeconds?.let { "%.0fms".format(it * 1000) } ?: "-"} " +
                    "print=${state.printState ?: state.state}")
                lastStalls = stalls
                lastRetransmits = retransmits
                lastReport = now
            }
        }
    }

    /**
     * A path for the printer that means the same thing on every run.
     *
     * klippy is given this instead of the pty's own /dev/pts/N, because N is
     * different every time the host starts and the configuration file is not. A file
     * naming the pty of an earlier run is a file that has to be rewritten on every
     * run - and a file that is rewritten on every run is a file klippy's own
     * SAVE_CONFIG can never keep anything in, which is where a PID calibration, a Z
     * offset and every saved mesh profile live.
     *
     * A symlink to the slave end is opened by klippy exactly like the device node it
     * points at: connect_pipe does nothing but os.open it. What it sees is a pty
     * either way, and what the config file holds is a name that does not move.
     */
    private fun serialPath(slavePath: String): String {
        val link = KlipperHostFiles.pty(filesDir)
        // Unconditionally: the link is only ever a name for this run's pty.
        if (!link.delete() && link.exists()) {
            Log.w(TAG, "could not replace ${link.absolutePath}")
        }
        Os.symlink(slavePath, link.absolutePath)
        return link.absolutePath
    }

    /**
     * The printer's configuration: the app's default once, and then its own.
     *
     * Seeded from the assets the first time it is needed and never rewritten
     * afterwards, so that what klippy saves survives both a restart of the host and
     * an update of the app. Pins, kinematics and probe offsets belong to the printer
     * and are not touched here; only the serial port and the gcode directory differ
     * from the file the same printer is set up with on a host.
     *
     * The shipped default is left beside it, resolved the same way, so that the app
     * can say whether the file has moved on from what it ships - and put it back
     * without losing what the printer has learned.
     */
    private fun printerConfig(serialPath: String): File {
        val directory = KlipperHostFiles.directory(filesDir).apply { mkdirs() }
        // The same directory constant the print path copies files into: the config's
        // virtual_sdcard and the code that hands it a file have to agree, and a second
        // literal here is how they stop agreeing.
        val gcodes = KlipperHostFiles.gcodes(filesDir).apply { mkdirs() }
        val shipped = assets.open("klipper-host/printer.cfg")
            .bufferedReader().use { it.readText() }
        val resolved = KlipperConfigFile.resolve(shipped, serialPath, gcodes.absolutePath)
        File(directory, KlipperHostFiles.SHIPPED).writeText(resolved)
        // The app's own sections, in the app's own file: the printer's configuration gets
        // one include line rather than a section of ours appearing in it. Written every
        // start because it is ours and small, and the include is added if it is missing -
        // including for a configuration somebody brought, which is the point of doing it
        // here rather than at import.
        KlipperHostFiles.appConfig(filesDir).writeText("[resonance_playback]\n")
        val config = File(directory, KlipperHostFiles.CONFIG)
        if (!config.isFile || config.readText().isBlank()) {
            config.writeText(KlipperConfigFile.withAppInclude(resolved))
            Log.i(TAG, "seeded ${config.absolutePath}")
        } else {
            val existing = config.readText()
            val included = KlipperConfigFile.withAppInclude(existing)
            if (included != existing) {
                config.writeText(included)
                Log.i(TAG, "added the app include to ${config.absolutePath}")
            }
        }
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
            // "A bridge is running" is not the same question as "the printer is
            // bridged". A power cycle leaves the old bridge holding a dead device: it
            // reports itself healthy, moves nothing, and klippy retransmits into it
            // until it gives up. Asking the second question is what lets the button do
            // what killing the app used to be the only way to do.
            val attached = PrinterBridge.findPrinter(
                getSystemService(Context.USB_SERVICE) as UsbManager,
            )
            val bridged = PrinterBridge.bridgedDeviceName
            if (attached != null && attached.device.deviceName == bridged) {
                Log.i(TAG, "printer already bridged to " + bridged)
                return
            }
            Log.i(TAG, "the bridge is stale: it holds " + bridged + ", attached is " +
                (attached?.device?.deviceName ?: "nothing"))
            restartHost()
            return
        }
        if (ptyMasterFd < 0) {
            Log.w(TAG, "no pty to bridge the printer to")
            return
        }
        bridgePrinter(ptyMasterFd)
    }

    /**
     * Rebuild the host from the socket down: klippy, the pty and the bridge.
     *
     * They have to go together. The bridge closes the pty when it stops and klippy
     * holds the slave end, so a bridge rebuilt underneath a klippy that has given up
     * would be feeding a process that has stopped listening. Stopping and starting the
     * engine is what killing the app does, which until now was the only recovery that
     * worked on a wedged link.
     *
     * Safe to call from a start request: the restarted launch asks the same question
     * again and finds no bridge, so it bridges normally.
     */
    private fun restartHost() {
        Log.i(TAG, "restarting the host")
        // Deliberately not under launchLock: stopEngine is safe on its own, and launch claims the
        // lock for its decision only. Wrapping both is what made this wait on the running host.
        stopEngine()
        launch()
    }

    private fun bridgePrinter(masterFd: Int) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val serial = PrinterBridge.findPrinter(manager)
        if (serial == null) {
            Log.i(TAG, "no printer on USB; klippy will wait for one")
            return
        }
        if (!manager.hasPermission(serial.device)) {
            // Permission is granted either by the attach chooser for a device on the manifest
            // filter, or by asking. Only the first exists here, so a board the app can drive
            // perfectly well - the whole CDC-ACM and Prolific half of the library's default
            // prober, which the docs promise - was found, recognised, and then left on the bus
            // with nothing on screen and no dialog to accept. Ask, and bridge on the answer.
            Log.i(TAG, "asking for permission to use ${serial.device.deviceName}")
            requestUsbPermission(manager, serial.device)
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
        // The module app.cfg loads at every start: without it klippy refuses the
        // configuration outright, so a payload missing it is not a degraded host, it is no
        // host at all.
        PAYLOAD_SENTINELS.all { File(root, it).isFile } && countFiles(root) >= MINIMUM_PAYLOAD_FILES

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

        // A link can be present and wrong. Every install gets its own native library
        // directory, while the payload and the stamp that says it is extracted sit in
        // app data and survive an update - so the link left by the previous version
        // points into a directory Android has since removed, and exists() follows it
        // and reports false. Left in place it is worse than absent: symlink then fails
        // with EEXIST, and the host cannot start at all after an update.
        if (soname.exists() && soname.canonicalPath == target.canonicalPath) {
            Log.i(TAG, "soname link already resolves to ${target.absolutePath}")
            return
        }
        if (soname.exists() || soname.delete()) {
            Log.i(TAG, "replaced the soname link: it did not resolve to ${target.absolutePath}")
        }
        Os.symlink(target.absolutePath, soname.absolutePath)
        if (!soname.exists()) {
            throw IOException("soname link does not resolve: ${soname.absolutePath}")
        }
        Log.i(TAG, "linked ${soname.name} to ${target.absolutePath}")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbPermission) }
        stopEngine()
        destroyed = true
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Ask Android for the printer, and bridge it when the answer is yes.
     *
     * The answer arrives as a broadcast, so the receiver is registered for as long as the
     * service runs rather than for the length of one request. A refusal is logged and the
     * user can plug the printer in again; the intent has to be mutable, which is what the
     * platform requires of a USB permission PendingIntent.
     */
    private fun requestUsbPermission(manager: UsbManager, device: UsbDevice) {
        val granted = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE,
        )
        manager.requestPermission(device, granted)
    }

    /**
     * Say in the notification that the host is not running, and why.
     *
     * The notification is the one surface that is always visible - it is what a user sees on a
     * lock screen an hour into a print - and it was built once, when the service started, so it
     * went on saying "Klipper host running" after a launch had failed or klippy had exited.
     */
    private fun reportHostNotRunning(reason: String) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIF_ID, buildNotification(this, "The Klipper host is not running: $reason"))
        }
    }

    private fun stopEngine() {
        // Before the process is destroyed, so that the thread watching it does not
        // start it again on the way out.
        stopping = true
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

        /** The action Android sends a USB permission answer under. */
        private const val ACTION_USB_PERMISSION = "com.tomppi.enderslicercura.USB_PERMISSION"
        private const val NOTIF_ID = 4711
        private const val CHANNEL_ID = "klipper_host"

        /** What a start request is asking for, when it is asking for more than a start. */
        private const val EXTRA_ACTION = "klipper_action"
        private const val ACTION_RESTART = "restart"


        /** How long to wait for klippy to create its API socket. */
        private const val API_WAIT_MS = 30_000L

        /**
         * How long to wait before starting klippy again after it has exited.
         *
         * Long enough that a crash loop does not become a busy loop on a phone,
         * short enough that a SAVE_CONFIG restart is not something a user waits for.
         */
        private const val RELAUNCH_DELAY_MS = 2_000L

        /** How long to keep asking until the printer calls itself ready. */
        private const val API_READY_WAIT_MS = 90_000L

        /** How often the margins are read while the host runs. */
        private const val MARGIN_POLL_MS = 5_000L

        /** How often they are written when nothing about them has changed. */
        private const val MARGIN_LOG_MS = 60_000L

        /**
         * Consecutive polls of a climbing retransmit counter before the host rebuilds
         * itself. At MARGIN_POLL_MS this is twenty seconds.
         */
        private const val WEDGE_SAMPLES = 4

        /** How long to leave a restarted host alone before deciding it is wedged again. */
        private const val RESTART_COOLDOWN_MS = 180_000L

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
            "klippy/extras/resonance_playback.py",
            "lib/python3.11/encodings/__init__.py",
        )

        /**
         * A tree this size, or the extraction did not finish.
         *
         * The sentinels above catch a payload that is wrong; this catches one that is
         * incomplete, which is the shape a copy that half failed leaves behind - and the
         * stamp that says "already extracted" would otherwise freeze it for the life of the
         * installed version.
         */
        private const val MINIMUM_PAYLOAD_FILES = 800

        private fun countFiles(root: File): Int {
            val entries = root.listFiles() ?: return 0
            return entries.sumOf { if (it.isDirectory) countFiles(it) else 1 }
        }

        fun start(context: Context) {
            val intent = Intent(context, KlipperEngineService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KlipperEngineService::class.java))
        }

        /**
         * Rebuild the running host: klippy, the pty it listens on, and the bridge.
         *
         * The action a front end takes after saving the configuration, and the one
         * that clears a wedged link. Starting the service when it is not running
         * starts a host rather than restarting one, which is what a user means by
         * the same button in both cases.
         */
        fun restart(context: Context) {
            val intent = Intent(context, KlipperEngineService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
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

        private fun buildNotification(
            context: Context,
            text: String = "The printer is being driven by this device",
            title: String = "Klipper host running",
        ): Notification {
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
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }
}
