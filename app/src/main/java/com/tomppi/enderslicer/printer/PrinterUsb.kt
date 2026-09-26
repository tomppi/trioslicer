package com.tomppi.enderslicer.printer

import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Finding a Klipper printer board on USB.
 *
 * The phone's kernel reports CONFIG_USB_SERIAL is not set, so no /dev/ttyUSB will
 * ever exist and the port cannot be opened from the filesystem. It has to be
 * claimed in userspace over UsbManager, which is what usb-serial-for-android does.
 * This is the front door: enumerate what is attached, pick a driver for it.
 *
 * A prober only ever sees devices the kernel has already enumerated. If nothing is
 * on the bus, every prober returns an empty list - that is a detection problem, not
 * a driver problem, and no amount of matching rules fixes it.
 *
 * Creality 4.2.x boards carry a CH340, but clones and variants are common, so when
 * the well-known table finds nothing we fall back to naming the CH34x driver
 * explicitly for the IDs those parts actually use.
 */
object PrinterUsb {
    private const val TAG = "PrinterUsb"

    /** VID/PID pairs known to be CH34x parts. */
    private val CH34X_IDS = listOf(
        0x1a86 to 0x7523, // CH340, the usual one on Creality boards
        0x1a86 to 0x5523, // CH341A
        0x1a86 to 0x7522, // CH340 variant
        0x4348 to 0x5523, // WCH CH341A clone
    )

    /** The library's own rules, plus our CH34x table behind them. */
    fun prober(): UsbSerialProber {
        val table = ProbeTable()
        for ((vid, pid) in CH34X_IDS) {
            table.addProduct(vid, pid, Ch34xSerialDriver::class.java)
        }
        return UsbSerialProber(table)
    }

    /**
     * Every serial device the library can drive for us.
     *
     * Returns an empty list when nothing is attached, and says so in the log with
     * the device count, because "no printer" and "no device at all" are different
     * problems and the count is what tells them apart.
     */
    fun findDrivers(manager: UsbManager): List<UsbSerialDriver> {
        val standard = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (standard.isNotEmpty()) return standard

        val fallback = prober().findAllDrivers(manager)
        if (fallback.isNotEmpty()) return fallback

        Log.i(TAG, "no USB serial device attached; UsbManager lists ${manager.deviceList.size} device(s)")
        return emptyList()
    }
}
