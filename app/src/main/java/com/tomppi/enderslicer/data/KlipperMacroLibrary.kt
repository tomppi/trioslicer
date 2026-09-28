package com.tomppi.enderslicer.data

/**
 * Macros this app can add to a printer's configuration.
 *
 * The printer's own printer.cfg is where macros live, and the app has no macros of its own -
 * the Macros screen reads whatever gcode_macro sections the configuration defines. What it can
 * do is offer the ones a Klipper printer is usually given and add the missing ones on request,
 * which is what this is: Klipper's own config/sample-macros.cfg, adapted with the reasons
 * written down, plus the load/unload pair that makes a filament change finish cleanly.
 *
 * Nothing here is added without being asked for, and nothing already in the file is touched.
 */
internal object KlipperMacroLibrary {

    /** One macro, with the section text that defines it. */
    internal data class StarterMacro(val name: String, val summary: String, val section: String)

    /**
     * Filament change, from Klipper's sample-macros.cfg.
     *
     * Pauses the print, parks the head and pulls the filament clear of the hotend so it can be
     * swapped by hand. X, Y and Z can be given on the command; the defaults park near the
     * front of the bed this app ships for. The 50 mm pull at the end leaves the filament out
     * of the extruder as well as the hotend, which is what a change needs and why the new
     * filament has to be fed in by hand - LOAD_FILAMENT fills the hotend before RESUME.
     */
    private val M600 = StarterMacro(
        name = "M600",
        summary = "Change filament: pause, park, and pull the filament clear",
        section = """
            [gcode_macro M600]
            # Filament change, from Klipper's config/sample-macros.cfg.
            gcode:
                {% set X = params.X|default(50)|float %}
                {% set Y = params.Y|default(0)|float %}
                {% set Z = params.Z|default(10)|float %}
                SAVE_GCODE_STATE NAME=M600_state
                PAUSE
                G91
                G1 E-.8 F2700
                G1 Z{Z}
                G90
                G1 X{X} Y{Y} F3000
                G91
                G1 E-50 F1000
                RESTORE_GCODE_STATE NAME=M600_state
        """.trimIndent(),
    )

    /**
     * Object cancellation under Marlin's name, from Klipper's sample-macros.cfg.
     *
     * A file sliced for Marlin asks for this rather than klippy's own EXCLUDE_OBJECT, and the
     * printer needs [exclude_object] for either - which the shipped configuration has. The
     * macro is the sample's, including its refusal to run when the section is missing.
     */
    private val M486 = StarterMacro(
        name = "M486",
        summary = "Cancel an object the way a Marlin-sliced file asks for it",
        section = """
            [gcode_macro M486]
            # Object cancellation under Marlin's name, from Klipper's config/sample-macros.cfg.
            gcode:
              {% if 'exclude_object' not in printer %}
                {action_raise_error("[exclude_object] is not enabled")}
              {% endif %}
              {% if 'T' in params %}
                EXCLUDE_OBJECT RESET=1
                {% for i in range(params.T | int) %}
                  EXCLUDE_OBJECT_DEFINE NAME={i}
                {% endfor %}
              {% endif %}
              {% if 'C' in params %}
                EXCLUDE_OBJECT CURRENT=1
              {% endif %}
              {% if 'P' in params %}
                EXCLUDE_OBJECT NAME={params.P}
              {% endif %}
              {% if 'S' in params %}
                {% if params.S == '-1' %}
                  {% if printer.exclude_object.current_object %}
                    EXCLUDE_OBJECT_END NAME={printer.exclude_object.current_object}
                  {% endif %}
                {% else %}
                  EXCLUDE_OBJECT_START NAME={params.S}
                {% endif %}
              {% endif %}
              {% if 'U' in params %}
                EXCLUDE_OBJECT RESET=1 NAME={params.U}
              {% endif %}
        """.trimIndent(),
    )

    /**
     * Fill the hotend after a change.
     *
     * Heat to T (210 by default), then push B millimetres in at S mm per minute, in relative
     * mode inside a saved state, so the file's own modes are left as they were. Run it after
     * a filament change and before RESUME, or the first lines back are thin.
     */
    private val LOAD_FILAMENT = StarterMacro(
        name = "LOAD_FILAMENT",
        summary = "Heat up and push filament in after a change",
        section = """
            [gcode_macro LOAD_FILAMENT]
            # T is the temperature, B the length in mm, S the speed in mm per minute.
            gcode:
                {% set T = params.T|default(210)|int %}
                {% set B = params.B|default(50)|float %}
                {% set S = params.S|default(300)|int %}
                M109 S{T}
                SAVE_GCODE_STATE NAME=load_filament
                M83
                G1 E{B} F{S}
                RESTORE_GCODE_STATE NAME=load_filament
        """.trimIndent(),
    )

    /** The same, backwards, for taking a spool out. */
    private val UNLOAD_FILAMENT = StarterMacro(
        name = "UNLOAD_FILAMENT",
        summary = "Heat up and pull filament back out",
        section = """
            [gcode_macro UNLOAD_FILAMENT]
            # T is the temperature, B the length in mm, S the speed in mm per minute.
            gcode:
                {% set T = params.T|default(210)|int %}
                {% set B = params.B|default(50)|float %}
                {% set S = params.S|default(300)|int %}
                M109 S{T}
                SAVE_GCODE_STATE NAME=unload_filament
                M83
                G1 E-{B} F{S}
                RESTORE_GCODE_STATE NAME=unload_filament
        """.trimIndent(),
    )

    /** Everything this app can offer, in the order a user would meet them. */
    val all: List<StarterMacro> = listOf(M600, M486, LOAD_FILAMENT, UNLOAD_FILAMENT)

    /** The text of every section, for the shipped configuration and for the writer. */
    fun sections(): String = all.joinToString("\n\n") { it.section }

    /**
     * The ones this configuration does not define.
     *
     * Matched on the section header, case-insensitively, because klippy registers a macro by
     * the name it is given and a file may spell it either way.
     */
    fun missingFrom(config: String): List<StarterMacro> {
        val defined = config.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("[gcode_macro ") && it.endsWith("]") }
            .map { it.removePrefix("[gcode_macro ").removeSuffix("]").trim().uppercase() }
            .toSet()
        return all.filterNot { it.name.uppercase() in defined }
    }
}
