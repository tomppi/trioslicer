package com.tomppi.enderslicer.model

fun PrinterDefinition.withSettings(settings: SlicerSettings): PrinterDefinition = copy(
    name = settings.printerName.ifBlank { name },
    widthMm = settings.machineWidthMm,
    depthMm = settings.machineDepthMm,
    heightMm = settings.machineHeightMm,
    buildPlateShape = settings.buildPlateShape,
    originAtCenter = settings.originAtCenter,
    heatedBed = settings.heatedBed,
    heatedBuildVolume = settings.heatedBuildVolume,
    gcodeFlavor = settings.gcodeFlavor,
    nozzleSizeMm = settings.nozzleSizeMm,
    filamentDiameterMm = settings.filamentDiameterMm,
    printheadXMinMm = settings.printheadXMinMm,
    printheadYMinMm = settings.printheadYMinMm,
    printheadXMaxMm = settings.printheadXMaxMm,
    printheadYMaxMm = settings.printheadYMaxMm,
    gantryHeightMm = settings.gantryHeightMm,
)

/** True when this profile declares the Klipper host inside the app as its machine. */
private fun SlicerSettings.isKlipperRoute(): Boolean =
    gcodeFlavor.trim().lowercase().startsWith("klipper")

/**
 * The start script for the route this profile declares.
 *
 * Each route has its own custom pair, so a script written for Klipper is never sent to a Marlin
 * printer, nor the other way round. The fallback is the profile's own start G-code, for which
 * the route supplies the default.
 */
fun SlicerSettings.resolveStartGcode(fallback: String): String = when {
    isKlipperRoute() && customKlipperStartGcodeEnabled -> customKlipperStartGcode.ifEmpty { fallback }
    isKlipperRoute() -> fallback
    customStartGcodeEnabled -> customStartGcode.ifEmpty { fallback }
    else -> fallback
}

/** The same for the end script. */
fun SlicerSettings.resolveEndGcode(fallback: String): String = when {
    isKlipperRoute() && customKlipperEndGcodeEnabled -> customKlipperEndGcode.ifEmpty { fallback }
    isKlipperRoute() -> fallback
    customEndGcodeEnabled -> customEndGcode.ifEmpty { fallback }
    else -> fallback
}

/**
 * Move a custom script written before there were two pairs into the Klipper one.
 *
 * A profile whose flavour is Klipper and whose custom script is enabled was written by somebody
 * slicing for Klipper, so that script is Klipper's. Leaving it in the shared pair would send it
 * to a Marlin printer the moment the flavour changed, which is what the pairs exist to stop.
 * Idempotent: the pair it came from is disabled, so there is nothing left to move.
 */
fun SlicerSettings.migrateCustomScriptsToTheRoute(): SlicerSettings {
    if (!isKlipperRoute()) return this
    var migrated = this
    if (customStartGcodeEnabled && !customKlipperStartGcodeEnabled && customStartGcode.isNotBlank()) {
        migrated = migrated.copy(
            customKlipperStartGcodeEnabled = true,
            customKlipperStartGcode = customStartGcode,
            customStartGcodeEnabled = false,
            customStartGcode = "",
        )
    }
    if (customEndGcodeEnabled && !customKlipperEndGcodeEnabled && customEndGcode.isNotBlank()) {
        migrated = migrated.copy(
            customKlipperEndGcodeEnabled = true,
            customKlipperEndGcode = customEndGcode,
            customEndGcodeEnabled = false,
            customEndGcode = "",
        )
    }
    return migrated
}
