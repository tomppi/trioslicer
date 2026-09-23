package com.tomppi.enderslicer.model

data class PrinterDefinition(
    val name: String,
    val widthMm: Double,
    val depthMm: Double,
    val heightMm: Double,
    val buildPlateShape: String,
    val originAtCenter: Boolean,
    val heatedBed: Boolean,
    val heatedBuildVolume: Boolean,
    val gcodeFlavor: String,
    val extruders: Int,
    val nozzleSizeMm: Double,
    val filamentDiameterMm: Double,
    val printheadXMinMm: Double,
    val printheadYMinMm: Double,
    val printheadXMaxMm: Double,
    val printheadYMaxMm: Double,
    val gantryHeightMm: Double,
)
