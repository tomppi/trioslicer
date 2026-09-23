package com.tomppi.enderslicer.conical

import com.tomppi.enderslicer.engine.GcodeCommand
import com.tomppi.enderslicer.model.SlicerSettings

/**
 * Automatic slice-time preparations required by the EasyConical algorithm,
 * mirroring the "Required Settings" section of the upstream user guide:
 * skirts/brims are disabled and nozzle priming lines are stripped so the
 * back-transformed G-code keeps correct bed contact. Relative extrusion is
 * handled by the back-transformer itself, so it is not forced here.
 */
internal object ConicalPreparations {
    const val ADHESION_NONE = "none"

    fun adjustSettings(settings: SlicerSettings): SlicerSettings =
        settings.copy(
            adhesionType = ADHESION_NONE,
            customStartGcode = if (settings.customStartGcodeEnabled) {
                stripPrimeLines(settings.customStartGcode)
            } else {
                settings.customStartGcode
            },
            overriddenSettingKeys = settings.overriddenSettingKeys + SlicerSettings.Keys.ADHESION_TYPE,
        )

    fun stripPrimeLines(startGcode: String): String =
        startGcode.lineSequence()
            .filterNot { line ->
                val command = GcodeCommand.parse(line) ?: return@filterNot false
                command.opcode == "G1" && command.has('E')
            }
            .joinToString("\n")
}
