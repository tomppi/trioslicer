package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.viewer.StlMesh

/**
 * Automatic overhang-strategy resolver.
 *
 * When [SlicerSettings.smartOverhangStrategy] is enabled the app runs
 * [OverhangStrategyPlanner] on the positioned mesh before slicing and lets the
 * result drive arc fill instead of relying only on the manual toggle. The
 * resolver only forces changes when it has evidence:
 *
 * - A flat roof is present, so arc fill is enabled and wave fill is disabled
 *   to preserve the arc/wave exclusivity. Brick walls follow the same
 *   exclusivity as wave fill.
 * - Non-planar printing only replaces material within a few layer heights of
 *   the surface, so flat roofs and their arc/brick-wall paths on lower layers
 *   stay exactly as sliced: arc fill is never forced off because of
 *   non-planar layers.
 * - No evidence either way: the explicit user setting is preserved.
 *
 * Non-planar printing itself is never auto-enabled: it is an explicit
 * pipeline the user turns on in the Non Planar sheet.
 */
internal object SmartOverhangStrategy {

    data class Resolution(
        val settings: SlicerSettings,
        val message: String?,
    )

    fun resolve(
        settings: SlicerSettings,
        nonPlanarSettings: NonPlanarSettings,
        mesh: StlMesh,
        layerHeightMm: Double,
        nozzleDiameterMm: Double,
    ): Resolution {
        if (!settings.smartOverhangStrategy) {
            return Resolution(settings, null)
        }
        val nonPlanarActive = nonPlanarSettings.enabled
        val plan = OverhangStrategyPlanner.plan(mesh, nonPlanarSettings, layerHeightMm, nozzleDiameterMm)

        val arcOn = if (plan.arcUseful) true else settings.arcOverhangEnabled
        val effective = settings.copy(
            arcOverhangEnabled = arcOn,
            waveOverhangEnabled = if (arcOn) false else settings.waveOverhangEnabled,
            brickWallEnabled = if (arcOn) false else settings.brickWallEnabled,
        )
        return Resolution(effective, message(plan, nonPlanarActive, arcOn))
    }

    private fun message(
        plan: OverhangStrategyPlan,
        nonPlanarActive: Boolean,
        arcOn: Boolean,
    ): String = buildString {
        append("Smart overhangs: ")
        when {
            arcOn && nonPlanarActive -> {
                append(
                    "arc fill kept for %.0f mm² of flat roofs together with non-planar printing".format(plan.flatRoofAreaMm2),
                )
            }
            arcOn -> {
                append("arc fill auto-enabled for %.0f mm² of flat roofs".format(plan.flatRoofAreaMm2))
                if (plan.nonPlanarUseful && !nonPlanarActive) {
                    append("; non-planar printing would also help %.0f mm² of slopes — enable it in Non Planar for the combined result".format(plan.slopedUpperAreaMm2))
                }
            }
            nonPlanarActive && plan.nonPlanarUseful -> {
                append("no flat roofs for arc fill; non-planar printing handles %.0f mm² of slopes".format(plan.slopedUpperAreaMm2))
            }
            else -> append(plan.summary.removePrefix("Smart overhangs: "))
        }
    }
}
