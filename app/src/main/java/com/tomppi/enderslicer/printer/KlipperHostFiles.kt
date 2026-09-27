package com.tomppi.enderslicer.printer

import java.io.File

/**
 * Where the host's own files live under the app's storage, in one place.
 *
 * Two things write them and they have to agree: the service, which starts klippy and gives
 * it a printer, and the front end, which reads the configuration and can replace it. A
 * second literal for the same path is how the two stop agreeing, which is why the paths
 * are here rather than in each of them.
 */
internal object KlipperHostFiles {
    /** The name the printer's pty is given, which is what the configuration file holds. */
    const val PTY_LINK = "printer-pty"

    /**
     * The printer's own directory: the configuration, its shipped copy, the last one it
     * replaced, and the note saying where it came from.
     *
     * Deliberately not inside the extracted payload, which is replaced whenever the app is
     * updated - a calibration saved through SAVE_CONFIG lives in the configuration file and
     * has to outlive that.
     */
    const val DIRECTORY = "klipper-host"

    /** The configuration klippy is given. */
    const val CONFIG = "printer.cfg"

    /** What this version of the app ships, resolved the same way, for comparison. */
    const val SHIPPED = "printer.cfg.default"

    /** The configuration that was replaced by the last import. */
    const val PREVIOUS = "printer.cfg.previous"

    /** Where the running configuration came from: shipped, or imported. */
    const val SOURCE = "config.source"

    /**
     * The app's own sections of the printer's configuration.
     *
     * The printer's file gets one line - an include of this - and everything the app
     * needs the configuration to contain lives here instead of being spread through a
     * file that belongs to somebody else.
     */
    const val APP_CONFIG = "app.cfg"

    /** Printed files, which the configuration's virtual SD card points at. */
    const val GCODES = "gcodes"

    fun directory(filesDir: File): File = File(filesDir, DIRECTORY)
    fun config(filesDir: File): File = File(directory(filesDir), CONFIG)
    fun shipped(filesDir: File): File = File(directory(filesDir), SHIPPED)
    fun previous(filesDir: File): File = File(directory(filesDir), PREVIOUS)
    fun source(filesDir: File): File = File(directory(filesDir), SOURCE)
    fun pty(filesDir: File): File = File(filesDir, PTY_LINK)
    fun gcodes(filesDir: File): File = File(filesDir, GCODES)
    fun appConfig(filesDir: File): File = File(directory(filesDir), APP_CONFIG)
}
