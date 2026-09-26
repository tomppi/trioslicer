package com.tomppi.enderslicercura.printer

import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
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
 * pty - and it applies termios to it exactly as it would to a real port. So Python
 * creates the pair (os.openpty, which the embedded interpreter supports), klippy is
 * pointed at the slave path, and this class shovels bytes between the master and USB.
 *
 * Python and this code share a process, therefore a descriptor table, so the pty's
 * master descriptor can simply be handed over as an int.
 *
 * Nothing here is Klipper-specific: it moves bytes and does not look at them.
 */
object PrinterBridge {
    private const val TAG = "PrinterBridge"

    /** Klipper's conventional rate. On a USB serial chip the rate is emulated. */
    const val BAUD_RATE = 250000

    private const val READ_TIMEOUT_MS = 200
    private const val BUFFER_SIZE = 4096

    @Volatile
    private var running = false

    private var port: UsbSerialPort? = null
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
            // The chip buffers until a full packet unless this is off; klipper sends
            // short frames, so latency matters. 1 ms is the library's minimum.
            serial.setDTR(true)
            serial.setRTS(true)
            port = serial
            val pfd = ParcelFileDescriptor.adoptFd(ptyMasterFd)
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
        while (running) {
            try {
                val read = serial.read(buffer, READ_TIMEOUT_MS)
                if (read > 0) {
                    out.write(buffer, 0, read)
                    out.flush()
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "usb read ended: ${e.message}")
                break
            }
        }
    }

    private fun pumpPtyToUsb(input: InputStream, serial: UsbSerialPort) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (running) {
            try {
                val read = input.read(buffer)
                if (read > 0) serial.write(buffer, read)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "pty read ended: ${e.message}")
                break
            }
        }
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
    }

    val isRunning: Boolean get() = running
}
