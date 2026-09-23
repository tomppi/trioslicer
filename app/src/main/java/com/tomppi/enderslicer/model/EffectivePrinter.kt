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

fun SlicerSettings.resolveStartGcode(fallback: String): String =
    if (customStartGcodeEnabled) customStartGcode.ifEmpty { fallback } else fallback

fun SlicerSettings.resolveEndGcode(fallback: String): String =
    if (customEndGcodeEnabled) customEndGcode.ifEmpty { fallback } else fallback
