package com.tomppi.enderslicer.data

/** Compile-time defaults avoid synchronous asset I/O during ViewModel construction. */
object BuiltInGcode {
    /**
     * The start G-code a new profile is given, in the printer's own language.
     *
     * This used to be Marlin's, and the machine this app drives runs Klipper: the two G29 lines
     * were UBL - load a mesh from slot 0, then activate UBL - and on this printer G29 is a
     * gcode_macro that homes and probes the bed. So every print ran G28, then G29 L0 (which
     * homed and probed), then G29 A (which homed and probed again): three homes and two full
     * mesh probes before the first extrusion, with two parameters that meant nothing to anyone.
     *
     * Klipper's own answer is either a mesh measured in this session or a saved profile loaded
     * by name. Probing is the default because it is right whatever the printer's history is;
     * the line below it is what to use instead when a mesh is saved and the wait is unwanted.
     */
    val START_KLIPPER: String = """
        ; Ender 3 start G-code for Klipper
        G92 E0 ; reset the extruder's position
        G28 ; home all axes
        BED_MESH_CALIBRATE ; probe the bed for this print
        ; BED_MESH_PROFILE LOAD=default ; ...or use a saved mesh instead of probing
        G1 Z2.0 F3000 ; lift
        G1 X0.1 Y20 Z0.3 F5000.0 ; move to the start of the prime line
        G1 X0.1 Y200.0 Z0.3 F1500.0 E15 ; draw the first line
        G1 X0.4 Y200.0 Z0.3 F5000.0 ; move across a little
        G1 X0.4 Y20 Z0.3 F1500.0 E30 ; draw the second line
        G92 E0 ; reset the extruder again
        G1 Z2.0 F3000 ; lift off the line
        G1 X5 Y20 Z0.3 F5000.0 ; move clear of it
    """.trimIndent()

    /**
     * The end G-code a new profile is given.
     *
     * M84 said "disable all steppers but Z", and Klipper's M84 takes no axis words at all - it
     * releases every motor, Z included. On a leadscrew that is not a drop, and the comment now
     * says what happens rather than what Marlin would have done.
     */
    val END_KLIPPER: String = """
        G91 ; relative positioning
        G1 E-2 F2700 ; retract a little
        G1 E-2 Z0.2 F2400 ; retract and raise
        G1 X5 Y5 F3000 ; wipe
        G1 Z10 ; raise further
        G90 ; absolute positioning

        G1 X0 Y20 ; present the print
        M106 S0 ; part fan off
        M104 S0 ; hotend off
        M140 S0 ; bed off

        M84 ; release every stepper, Z included: Klipper's M84 ignores axis words
    """.trimIndent()

    /**
     * The Marlin start G-code, which is what a Marlin printer needs.
     *
     * It was the only default this app had, and the app slices for two very different
     * machines: a printer on the other end of OctoPrint, which is usually Marlin, and the
     * Klipper host inside the app. G29 L0 and G29 A are UBL - load a mesh from slot 0 and
     * activate it - and on a Marlin board that is exactly right. So this stays, and the
     * choice between the two is made from the profile's own flavour rather than by there
     * being one default for both.
     */
    val START_MARLIN: String = """
        ; Ender 3 Custom Start G-code
        G92 E0 ; Reset Extruder
        G28 ; Home all axes
        G29 L0 ; load a valid mesh from slot 0
        G29 A  ; active the UBL system
        G1 Z2.0 F3000 ; Move Z Axis up little to prevent scratching of Heat Bed
        G1 X0.1 Y20 Z0.3 F5000.0 ; Move to start position
        G1 X0.1 Y200.0 Z0.3 F1500.0 E15 ; Draw the first line
        G1 X0.4 Y200.0 Z0.3 F5000.0 ; Move to side a little
        G1 X0.4 Y20 Z0.3 F1500.0 E30 ; Draw the second line
        G92 E0 ; Reset Extruder
        G1 Z2.0 F3000 ; Move Z Axis up little to prevent scratching of Heat Bed
        G1 X5 Y20 Z0.3 F5000.0 ; Move over to prevent blob squish
    """

    /** The Marlin end G-code, where M84 does take axis words and Z can be spared. */
    val END_MARLIN: String = """
        G91 ;Relative positioning
        G1 E-2 F2700 ;Retract a bit
        G1 E-2 Z0.2 F2400 ;Retract and raise Z
        G1 X5 Y5 F3000 ;Wipe out
        G1 Z10 ;Raise Z more
        G90 ;Absolute positioning

        G1 X0 Y20 ;Present print
        M106 S0 ;Turn-off fan
        M104 S0 ;Turn-off hotend
        M140 S0 ;Turn-off bed

        M84 X Y E ;Disable all steppers but Z
    """

    /**
     * The default for a profile, in the dialect that profile declares.
     *
     * Klipper is named in the flavour field; everything else - Marlin, RepRap, Smoothie, a
     * blank - gets the Marlin text, which is what it had before and what such a printer wants.
     */
    fun startGcodeFor(flavor: String): String = if (isKlipper(flavor)) START_KLIPPER else START_MARLIN

    /** The same for the end G-code. */
    fun endGcodeFor(flavor: String): String = if (isKlipper(flavor)) END_KLIPPER else END_MARLIN

    /**
     * What a stored profile should hold, given the dialect it declares.
     *
     * Two untouched defaults are moved to the right one for the flavour: a Marlin profile that
     * somehow holds the Klipper text gets Marlin's, and the other way round. Anything a user
     * has edited is theirs and comes back unchanged - which is also what keeps a Marlin
     * printer's UBL lines from being rewritten into Klipper commands it cannot run.
     */
    fun migrateStart(stored: String, flavor: String): String {
        val trimmed = stored.trim()
        val wantsKlipper = isKlipper(flavor)
        return when {
            trimmed == START_KLIPPER.trim() && !wantsKlipper -> START_MARLIN
            trimmed == START_MARLIN.trim() && wantsKlipper -> START_KLIPPER
            trimmed.isEmpty() -> startGcodeFor(flavor)
            else -> stored
        }
    }

    /** The same for the end G-code. */
    fun migrateEnd(stored: String, flavor: String): String {
        val trimmed = stored.trim()
        val wantsKlipper = isKlipper(flavor)
        return when {
            trimmed == END_KLIPPER.trim() && !wantsKlipper -> END_MARLIN
            trimmed == END_MARLIN.trim() && wantsKlipper -> END_KLIPPER
            trimmed.isEmpty() -> endGcodeFor(flavor)
            else -> stored
        }
    }

    private fun isKlipper(flavor: String): Boolean = flavor.trim().lowercase().startsWith("klipper")
}
