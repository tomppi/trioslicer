package com.tomppi.enderslicer.data

import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.engine.gcode.GcodeRoute

/**
 * The G-code a new profile starts and ends with, from the route its flavour belongs to.
 *
 * This object used to hold the texts itself, which is exactly how one default came to serve two
 * machines: the start script was replaced with Klipper's and a Marlin printer was sent
 * BED_MESH_CALIBRATE while its UBL mesh went unloaded. The texts live with their routes now -
 * FrozenGcodeRoute for Marlin and its relatives, KlipperGcodeRoute for the host in this app - and
 * this holds none of them, so there is nothing here for either route to break.
 */
internal object BuiltInGcode {
    /** The default for a profile that has not declared a flavour: the frozen route's. */
    val defaultStartGcode: String get() = startGcodeFor(PrinterEnvelope.DEFAULT_GCODE_FLAVOR)

    /** The same for the end G-code. */
    val defaultEndGcode: String get() = endGcodeFor(PrinterEnvelope.DEFAULT_GCODE_FLAVOR)

    fun startGcodeFor(flavor: String): String = GcodeRoute.forFlavor(flavor).startGcode()

    fun endGcodeFor(flavor: String): String = GcodeRoute.forFlavor(flavor).endGcode()

    /**
     * A stored profile, moved to its own route's default when it still holds the other one's.
     *
     * The rule itself lives in the route seam, which is the only place that knows both routes
     * exist; this is the name the settings layer knows it by.
     */
    fun migrateStart(stored: String, flavor: String): String = GcodeRoute.migrateStart(stored, flavor)

    fun migrateEnd(stored: String, flavor: String): String = GcodeRoute.migrateEnd(stored, flavor)
}
