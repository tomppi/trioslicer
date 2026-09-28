package com.tomppi.enderslicer.engine.gcode

import com.tomppi.enderslicer.engine.GcodeCommand
import com.tomppi.enderslicer.engine.LayerEventType

/**
 * What a G-code route has to answer, and the one place the two routes meet.
 *
 * This app slices for two machines: a printer at the far end of OctoPrint, which is usually
 * Marlin, and the Klipper host running inside the app. They share the slicers, the settings and
 * the plumbing, and they agree about almost nothing that ends up in the file - so the file's
 * dialect is a route, chosen once from the profile's flavour, and the routes do not share an
 * implementation.
 *
 * [FrozenGcodeRoute] is the route as it stood at TrioSlicer 1.4.0, when it was the only one and
 * it worked. It is not edited for Klipper work: a Klipper fix goes into [KlipperGcodeRoute], and
 * a change that genuinely belongs to both is a deliberate edit to the frozen file, recorded as
 * one. MarlinRouteContractTest holds its output to the text and the commands it had at 1.4.0.
 */
internal interface GcodeRoute {
    /** The flavour this route was chosen for, as the profile spells it. */
    val flavor: String

    /** What stops this machine: Klipper has PAUSE, the frozen route has M0. */
    fun pauseCommand(): String

    /** The commands a layer event becomes, or a refusal if this route has no encoder for it. */
    fun commands(
        type: LayerEventType,
        layerNumber: Int,
        value: Double? = null,
        secondaryValue: Double? = null,
        text: String = "",
    ): List<String>

    /** The start and end G-code a new profile for this route is given. */
    fun startGcode(): String
    fun endGcode(): String

    fun hotendOffCommand(): String

    fun isFirmwareRetract(command: GcodeCommand.Parsed): Boolean
    fun isFirmwareUnretract(command: GcodeCommand.Parsed): Boolean

    companion object {
        /**
         * The route for a profile's declared flavour.
         *
         * Klipper is the only flavour that is not the frozen route: Marlin, RepRapFirmware,
         * Smoothie and anything unrecognised were all served by the same code before Klipper
         * existed, and are served by the same code still.
         */
        fun forFlavor(rawFlavor: String): GcodeRoute {
            val flavor = rawFlavor.trim().ifBlank {
                com.tomppi.enderslicer.engine.PrinterEnvelope.DEFAULT_GCODE_FLAVOR
            }
            return if (isKlipper(flavor)) {
                KlipperGcodeRoute(flavor)
            } else {
                FrozenGcodeRoute(flavor)
            }
        }

        /**
         * The start G-code a stored profile should hold, given the route it is for.
         *
         * Two defaults exist and a profile can hold either - a Klipper profile made before the
         * routes were split still has the Marlin text in it, and the reverse is possible too.
         * Only an untouched default is moved; anything edited by hand is the user's.
         *
         * This is the one function in the app that knows both routes exist, which is why it
         * lives in the seam rather than in either route: neither route carries the other's
         * text, and neither is edited when the other changes.
         */
        fun migrateStart(stored: String, flavor: String): String =
            migrate(stored, flavor) { route -> route.startGcode() }

        /** The same for the end G-code. */
        fun migrateEnd(stored: String, flavor: String): String =
            migrate(stored, flavor) { route -> route.endGcode() }

        private fun migrate(stored: String, flavor: String, text: (GcodeRoute) -> String): String {
            val trimmed = stored.trim()
            if (trimmed.isEmpty()) return text(forFlavor(flavor))
            val mine = forFlavor(flavor)
            val theirs = forFlavor(if (isKlipper(flavor)) "Marlin" else "Klipper")
            return if (trimmed == text(theirs).trim()) text(mine) else stored
        }

        private fun isKlipper(flavor: String): Boolean =
            "klipper" in flavor.lowercase(java.util.Locale.US)
    }
}

/**
 * An event a route cannot express.
 *
 * Thrown rather than answered with a command that machine does not have: Klipper has no
 * junction deviation and a generic flavour has no verified retraction, and a line the printer
 * would answer "Unknown command" to is worse than a refusal the screen can show.
 */
internal class UnsupportedFirmwareCommand(message: String) : IllegalArgumentException(message)
