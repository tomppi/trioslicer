package com.tomppi.enderslicer.printer

import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * Bridges the printer's USB serial port to a pty, so that klippy sees an ordinary
 * serial port where there is really a CH340 on the end of a USB cable.
 *
 * Why a pty: the phone's kernel reports CONFIG_USB_SERIAL is not set, so no
 * /dev/ttyUSB exists and klippy cannot open a device node. It can, however, open a
 * pty - and it applies termios to it exactly as it would to a real port. So the app
 * creates the pair (KlipperPty, in native code), points klippy at the slave path in
 * its configuration, and this class shovels bytes between the master and USB.
 *
 * The master descriptor stays in this process and klippy only ever sees the slave:
 * klippy runs as a child process, not as a library inside this one.
 *
 * Nothing here is Klipper-specific: it moves bytes and does not look at them.
 */
object PrinterBridge {
    private const val TAG = "PrinterBridge"

    /** Klipper's conventional rate. On a USB serial chip the rate is emulated. */
    const val BAUD_RATE = 250000

    private const val READ_TIMEOUT_MS = 200
    private const val WRITE_TIMEOUT_MS = 200
    private const val BUFFER_SIZE = 4096
    private const val RETRY_PAUSE_MS = 100L

    /**
     * How many chunks each direction logs before it goes quiet.
     *
     * klippy's connection is a request and a reply per attempt, so the bring-up
     * question - did anything cross at all, and which way - is answered by the
     * first few. A print would otherwise fill the log with its own traffic.
     */
    private const val TRAFFIC_LOG_LIMIT = 24

    @Volatile
    private var running = false

    private var port: UsbSerialPort? = null
    private var pty: ParcelFileDescriptor? = null
    private var usbToPty: Thread? = null
    private var ptyToUsb: Thread? = null

    /** The first printer-like serial port attached, or null. */
    fun findPrinter(manager: UsbManager): UsbSerialPort? {
        val drivers = PrinterUsb.findDrivers(manager)
        if (drivers.isEmpty()) return null
        val driver = drivers.first()
        val ports = driver.ports
        if (ports.isEmpty()) {
            Log.w(TAG, "device ${driver.device.deviceName} exposes no serial port")
            return null
        }
        return ports[0]
    }

    /**
     * Claim the port and start moving bytes to and from [ptyMasterFd].
     *
     * The caller is responsible for having been granted permission on the device;
     * UsbManager.openDevice returns null without it.
     */
    fun start(manager: UsbManager, serial: UsbSerialPort, ptyMasterFd: Int): Boolean {
        if (running) {
            Log.w(TAG, "already running")
            return true
        }
        val connection = manager.openDevice(serial.device)
        if (connection == null) {
            Log.w(TAG, "could not open ${serial.device.deviceName} - permission not granted?")
            return false
        }
        return try {
            serial.open(connection)
            serial.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Both lines asserted, which is what klipper does when it opens a serial
            // port itself. Whether this board's CH340 does anything with them has
            // not been tested.
            serial.setDTR(true)
            serial.setRTS(true)
            port = serial
            // Kept in a field for as long as the bridge runs. A ParcelFileDescriptor
            // closes its descriptor when it is finalised, and the streams alone do
            // not keep it reachable: once this became garbage the pty vanished and
            // every later read and write failed with EBADF, mid-print.
            val pfd = ParcelFileDescriptor.adoptFd(ptyMasterFd)
            pty = pfd
            val toPty = FileOutputStream(pfd.fileDescriptor)
            val fromPty = FileInputStream(pfd.fileDescriptor)
            running = true
            usbToPty = thread(name = "klipper-usb-to-pty") { pumpUsbToPty(serial, toPty) }
            ptyToUsb = thread(name = "klipper-pty-to-usb") { pumpPtyToUsb(fromPty, serial) }
            Log.i(TAG, "bridging ${serial.device.deviceName} at $BAUD_RATE baud to pty fd $ptyMasterFd")
            true
        } catch (e: Exception) {
            Log.e(TAG, "could not start bridge", e)
            stop()
            false
        }
    }

    private fun pumpUsbToPty(serial: UsbSerialPort, out: OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        var logged = 0
        while (running) {
            try {
                val read = serial.read(buffer, READ_TIMEOUT_MS)
                if (read > 0) {
                    if (logged < TRAFFIC_LOG_LIMIT) {
                        logged++
                        Log.i(TAG, "usb -> pty: ${preview(buffer, read)}")
                    }
                    out.write(buffer, 0, read)
                    out.flush()
                }
            } catch (e: SerialTimeoutException) {
                // Idle, not a failure: this is the read waiting for the chip to have
                // something, and a printer between handshakes has nothing.
            } catch (e: Exception) {
                if (isSlaveClosed(e)) {
                    // No slave open at the moment - klippy reopens it on each of its
                    // connection attempts. The master stays valid; keep waiting.
                    pause()
                    continue
                }
                if (running) Log.w(TAG, "usb read ended: ${e.message}")
                break
            }
        }
    }

    private fun pumpPtyToUsb(input: InputStream, serial: UsbSerialPort) {
        val buffer = ByteArray(BUFFER_SIZE)
        var logged = 0
        while (running) {
            try {
                val read = input.read(buffer)
                if (read > 0) {
                    if (logged < TRAFFIC_LOG_LIMIT) {
                        logged++
                        Log.i(TAG, "pty -> usb: ${preview(buffer, read)}")
                    }
                    // The two-argument write is (bytes, timeout), and it puts the
                    // whole array on the wire. Handing it the count instead sent
                    // 4 KiB of stale buffer per chunk and used the count as the
                    // timeout, which is what "writing 32 bytes at offset 384 of
                    // total 4096" in the log was.
                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                    serial.write(chunk, WRITE_TIMEOUT_MS)
                }
            } catch (e: Exception) {
                if (isSlaveClosed(e)) {
                    // The master reports EIO whenever no slave is open, which is the
                    // normal state between klippy's connection attempts. Treating it
                    // as fatal killed the direction carrying klippy's requests, so
                    // the board could never have answered.
                    pause()
                    continue
                }
                if (running) Log.w(TAG, "pty read ended: ${e.message}")
                break
            }
        }
    }

    /**
     * True for the EIO a pty master reports when nothing holds its slave open.
     *
     * It is not an error at this level: klippy opens the slave for each connection
     * attempt and closes it again about a second later while it waits for a printer
     * that is not answering yet, and the master stays usable throughout.
     */
    private fun isSlaveClosed(e: Exception) = e.message?.contains("EIO") == true

    /** A short pause, so a retry loop waiting on a closed slave does not spin. */
    private fun pause() {
        try {
            Thread.sleep(RETRY_PAUSE_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * The first bytes in each direction, logged once per bridge.
     *
     * klippy's side of this is a request followed by a reply it waits about a second
     * for, and "did anything at all cross the bridge" is the question that separates
     * a silent chip from a silent board. Bounded to one line per direction so a print
     * does not fill the log.
     */
    private fun preview(buffer: ByteArray, length: Int): String {
        val shown = buffer.take(length.coerceAtMost(16)).joinToString(" ") { "%02x".format(it) }
        return if (length > 16) "$shown ... ($length bytes)" else "$shown ($length bytes)"
    }

    /** Stop pumping and release the port. Safe to call more than once. */
    fun stop() {
        running = false
        usbToPty?.interrupt(); ptyToUsb?.interrupt()
        usbToPty = null; ptyToUsb = null
        port?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "close: ${e.message}") }
        }
        port = null
        // Closing this is what releases the master end; the slave then disappears
        // and klippy sees the port go away, which is the truthful thing to tell it.
        pty?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "close pty: ${e.message}") }
        }
        pty = null
    }

    val isRunning: Boolean get() = running
}
