package com.tomppi.enderslicer.smartinfill

/**
 * One starting point for the panel's material fields.
 *
 * The values are the pinned upstream material library's own defaults
 * (web/src/types.ts, DEFAULT_MATERIALS) so a native run scores the same material
 * the WebView's library would have handed it. The FDM entries carry a layer
 * direction and therefore keep layer-shear scoring on; the isotropic ones (mild
 * steel, aluminium, SLA resin) have no build direction, so their safety factor is
 * scored against yield — a strength goal on those wants the "material" measure.
 */
data class FilaSimMaterialPreset(
    val name: String,
    val youngsModulusMpa: Double,
    val poisson: Double,
    val densityGramsPerCm3: Double,
    /** Tensile strength in the layer plane (MPa). */
    val strengthMpa: Double,
    /** Layer-adhesion strength (MPa); the isotropic entries repeat [strengthMpa]. */
    val layerStrengthMpa: Double,
    val isotropic: Boolean,
) {
    /** This material applied to [configuration], leaving everything else alone. */
    fun applyTo(configuration: FilaSimConfiguration): FilaSimConfiguration = configuration.copy(
        youngsModulusMpa = youngsModulusMpa,
        poisson = poisson,
        densityGramsPerCm3 = densityGramsPerCm3,
        strengthMpa = strengthMpa,
        layerStrengthMpa = layerStrengthMpa,
        // Null is "let the engine derive it" (0.6 x layer strength), which is
        // what the upstream library carries — it has no shear column.
        shearStrengthMpa = null,
        layerShearOn = !isotropic,
    )

    /** True when [configuration] already carries this material's values. */
    fun matches(configuration: FilaSimConfiguration): Boolean =
        configuration.youngsModulusMpa == youngsModulusMpa &&
            configuration.poisson == poisson &&
            configuration.densityGramsPerCm3 == densityGramsPerCm3 &&
            configuration.strengthMpa == strengthMpa &&
            configuration.layerStrengthMpa == layerStrengthMpa &&
            configuration.layerShearOn == !isotropic
}

/** The materials the panel offers, in the upstream library's order. */
object FilaSimMaterialPresets {
    val ALL: List<FilaSimMaterialPreset> = listOf(
        fdm("PLA", youngs = 3500.0, poisson = 0.35, density = 1.24, strength = 50.0, layer = 35.0),
        fdm("PETG", youngs = 2100.0, poisson = 0.37, density = 1.27, strength = 45.0, layer = 34.0),
        fdm("ABS", youngs = 2250.0, poisson = 0.37, density = 1.05, strength = 38.0, layer = 25.0),
        fdm("ASA", youngs = 2400.0, poisson = 0.37, density = 1.07, strength = 43.0, layer = 29.0),
        isotropic("Steel S235", youngs = 210_000.0, poisson = 0.30, density = 7.85, yieldStrength = 235.0),
        isotropic("Aluminium 6061-T6", youngs = 69_000.0, poisson = 0.33, density = 2.70, yieldStrength = 276.0),
        isotropic("Resin SLA", youngs = 2800.0, poisson = 0.35, density = 1.18, yieldStrength = 60.0),
    )

    private fun fdm(
        name: String,
        youngs: Double,
        poisson: Double,
        density: Double,
        strength: Double,
        layer: Double,
    ) = FilaSimMaterialPreset(
        name = name,
        youngsModulusMpa = youngs,
        poisson = poisson,
        densityGramsPerCm3 = density,
        strengthMpa = strength,
        layerStrengthMpa = layer,
        isotropic = false,
    )

    private fun isotropic(
        name: String,
        youngs: Double,
        poisson: Double,
        density: Double,
        yieldStrength: Double,
    ) = FilaSimMaterialPreset(
        name = name,
        youngsModulusMpa = youngs,
        poisson = poisson,
        densityGramsPerCm3 = density,
        strengthMpa = yieldStrength,
        layerStrengthMpa = yieldStrength,
        isotropic = true,
    )
}
