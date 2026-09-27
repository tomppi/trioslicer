package com.tomppi.enderslicer.printer

/**
 * Which of a printer's objects this app follows.
 *
 * klippy will not answer a subscription for an object that does not exist - it refuses
 * the whole request - so the list cannot be a constant. What exists is the printer's
 * configuration, and it is asked for at every connection: a chamber sensor added to
 * printer.cfg, a second micro-controller, a fan someone named, all appear on their own
 * a second later, and none of them need this app to have known about them beforehand.
 *
 * The fixed part is what the screens read on any printer; the families are the object
 * types a screen can render generically because they all publish a temperature or a
 * speed.
 */
internal object KlipperWatch {
    /**
     * The objects every screen reads, whatever the printer is.
     *
     * Filtered against what klippy publishes before it is used: a printer with no
     * [bed_mesh] section has no bed_mesh object, and asking for one would cost the
     * whole subscription rather than that one entry.
     */
    val always = listOf(
        // The machine and the print on it: the dashboard's own fields.
        "extruder", "heater_bed", "toolhead", "print_stats", "virtual_sdcard", "mcu",
        // Everything else a tab reads.
        "gcode_move", "motion_report", "fan", "idle_timeout", "pause_resume", "display_status",
        "bed_mesh", "exclude_object", "configfile", "system_stats", "query_endstops",
        // The probe and the calibration that is waiting for a piece of paper under it.
        "probe", "manual_probe",
    )

    /**
     * Object families a screen can render without knowing the name.
     *
     * A [temperature_sensor chamber] publishes the same status as the hotend, and a
     * [fan_generic partfan] the same as the part fan, so these are followed by prefix
     * rather than by name.
     */
    private val families = listOf(
        "temperature_sensor ", "temperature_fan ", "temperature_host ", "temperature_mcu ",
        "heater_generic ", "fan_generic ", "controller_fan ", "heater_fan ", "output_pin ",
        "mcu ",
    )

    /** The objects to subscribe to on a printer that publishes [published]. */
    fun forPrinter(published: List<String>): List<String> {
        if (published.isEmpty()) return always
        val available = published.toSet()
        val wanted = LinkedHashSet<String>()
        always.filterTo(wanted) { it in available }
        published.filterTo(wanted) { name -> families.any { name.startsWith(it) } }
        return wanted.toList()
    }
}
