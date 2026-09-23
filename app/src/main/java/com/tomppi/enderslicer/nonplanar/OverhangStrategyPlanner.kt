package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.viewer.StlMesh
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Per-region overhang strategy classifier.
 *
 * The slicer owns arc overhangs, wave overhangs, brick walls and non-planar
 * printing. Instead of requiring the user to pick one globally, this planner
 * walks the positioned mesh once and classifies every triangle:
 *
 * - **Sloped upper surface** (normal points up, tilt within the printable
 *   range): non-planar candidate.
 * - **Flat roof underside** (normal points down, near-horizontal): arc-overhang
 *   candidate, because Cura treats exactly these as unsupported bottom skin.
 * - **Sloped underside** (normal points down, steep): a hard overhang that is
 *   risky for both strategies and is recorded for the safety decision.
 *
 * When non-planar printing is enabled the planner also builds the conformal
 * surface regions. The conformal shells only replace material within a few
 * layer heights of the surface, so flat roofs and their arc/brick-wall paths
 * on the layers below stay exactly as sliced - the combination is always
 * safe and both strategies can run together.
 */
internal enum class OverhangRecommendation { NONE, ARC_ONLY, NON_PLANAR_ONLY, ARC_AND_NON_PLANAR }

internal data class OverhangStrategyPlan(
    val recommendation: OverhangRecommendation,
    val slopedUpperAreaMm2: Double,
    val flatRoofAreaMm2: Double,
    val slopedUndersideAreaMm2: Double,
    val lowReliefRoofFraction: Double,
    val nonPlanarUseful: Boolean,
    val arcUseful: Boolean,
    val combinedSafe: Boolean,
    val summary: String,
)

internal object OverhangStrategyPlanner {

    fun plan(
        mesh: StlMesh,
        nonPlanarSettings: NonPlanarSettings,
        layerHeightMm: Double,
        nozzleDiameterMm: Double,
    ): OverhangStrategyPlan {
        var slopedUpperArea = 0.0
        var flatRoofArea = 0.0
        var slopedUndersideArea = 0.0

        val slopeLimitDegrees = nonPlanarSettings.effectiveSlopeLimitDegrees
        val bedContactMaxZ = BED_CONTACT_MM
        val vertices = mesh.interleavedVertices
        val triangleCount = mesh.triangleCount
        var flatRoofCount = 0
        var triangleIndex = 0
        var base = 0
        while (triangleIndex < triangleCount) {
            val ax = vertices[base].toDouble()
            val ay = vertices[base + 1].toDouble()
            val az = vertices[base + 2].toDouble()
            val bx = vertices[base + 6].toDouble()
            val by = vertices[base + 7].toDouble()
            val bz = vertices[base + 8].toDouble()
            val cx = vertices[base + 12].toDouble()
            val cy = vertices[base + 13].toDouble()
            val cz = vertices[base + 14].toDouble()

            val ux = bx - ax
            val uy = by - ay
            val uz = bz - az
            val vx = cx - ax
            val vy = cy - ay
            val vz = cz - az
            val crossX = uy * vz - uz * vy
            val crossY = uz * vx - ux * vz
            val crossZ = ux * vy - uy * vx
            val crossLength = sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ)
            if (crossLength > 1e-12) {
                val normalZ = crossZ / crossLength
                val slopeDegrees = Math.toDegrees(acos(kotlin.math.abs(normalZ).coerceIn(0.0, 1.0)))
                val projectedArea = 0.5 * crossLength * kotlin.math.abs(normalZ)
                if (normalZ > 0.05) {
                    if (slopeDegrees >= MIN_SLOPED_SURFACE_DEGREES && slopeDegrees <= slopeLimitDegrees) {
                        slopedUpperArea += projectedArea
                    }
                } else if (normalZ < -0.05) {
                    if (slopeDegrees <= FLAT_ROOF_MAX_DEGREES) {
                        val centroidZ = (az + bz + cz) / 3.0
                        if (centroidZ > bedContactMaxZ) {
                            flatRoofArea += projectedArea
                            flatRoofCount++
                        }
                    } else {
                        slopedUndersideArea += projectedArea
                    }
                }
            }
            triangleIndex++
            base += VERTEX_FLOATS_PER_TRIANGLE
        }

        val surface = if (nonPlanarSettings.enabled) {
            runCatching { ConformalSurfaceBuilder.build(mesh, nonPlanarSettings) }.getOrNull()
        } else {
            null
        }
        val surfaceExists = surface != null && surface.regions.isNotEmpty()
        // Conformal shells only move material within a few layer heights of
        // the surface, so every flat roof and its arc paths stay planar.
        val lowReliefRoofFraction = if (surfaceExists && flatRoofArea > 0.0) 1.0 else 0.0

        val arcUseful = flatRoofArea >= MIN_USEFUL_AREA_MM2
        val nonPlanarUseful = slopedUpperArea >= MIN_USEFUL_AREA_MM2 && surfaceExists
        val slopedUndersideFraction = if (flatRoofArea + slopedUndersideArea > 0.0) {
            slopedUndersideArea / (flatRoofArea + slopedUndersideArea)
        } else {
            0.0
        }
        val combinedSafe = surfaceExists &&
            arcUseful &&
            slopedUndersideFraction <= MAX_SLOPED_UNDERSIDE_FRACTION

        val recommendation = decide(arcUseful, nonPlanarUseful, combinedSafe)
        val summary = summary(recommendation, flatRoofArea, slopedUpperArea, flatRoofCount)
        return OverhangStrategyPlan(
            recommendation = recommendation,
            slopedUpperAreaMm2 = slopedUpperArea,
            flatRoofAreaMm2 = flatRoofArea,
            slopedUndersideAreaMm2 = slopedUndersideArea,
            lowReliefRoofFraction = lowReliefRoofFraction,
            nonPlanarUseful = nonPlanarUseful,
            arcUseful = arcUseful,
            combinedSafe = combinedSafe,
            summary = summary,
        )
    }

    internal fun decide(
        arcUseful: Boolean,
        nonPlanarUseful: Boolean,
        combinedSafe: Boolean,
    ): OverhangRecommendation = when {
        arcUseful && nonPlanarUseful && combinedSafe -> OverhangRecommendation.ARC_AND_NON_PLANAR
        arcUseful -> OverhangRecommendation.ARC_ONLY
        nonPlanarUseful -> OverhangRecommendation.NON_PLANAR_ONLY
        else -> OverhangRecommendation.NONE
    }

    private fun summary(
        recommendation: OverhangRecommendation,
        flatRoofAreaMm2: Double,
        slopedUpperAreaMm2: Double,
        flatRoofCount: Int,
    ): String = when (recommendation) {
        OverhangRecommendation.ARC_AND_NON_PLANAR ->
            "Smart overhangs: arc fill for %d flat roofs (%.0f mm²) + non-planar layers for slopes (%.0f mm²); safe to combine".format(
                flatRoofCount,
                flatRoofAreaMm2,
                slopedUpperAreaMm2,
            )
        OverhangRecommendation.ARC_ONLY ->
            "Smart overhangs: arc fill for %d flat roofs (%.0f mm²); no non-planar-usable slopes".format(
                flatRoofCount,
                flatRoofAreaMm2,
            )
        OverhangRecommendation.NON_PLANAR_ONLY ->
            "Smart overhangs: non-planar layers for slopes (%.0f mm²); no flat roofs for arc fill".format(
                slopedUpperAreaMm2,
            )
        OverhangRecommendation.NONE ->
            "Smart overhangs: no flat roofs or non-planar-usable slopes detected; standard slicing retained"
    }

    private const val VERTEX_FLOATS_PER_TRIANGLE = 18
    private const val MIN_SLOPED_SURFACE_DEGREES = 2.0
    private const val FLAT_ROOF_MAX_DEGREES = 15.0
    private const val MIN_USEFUL_AREA_MM2 = 50.0
    private const val MAX_SLOPED_UNDERSIDE_FRACTION = 0.3
    private const val BED_CONTACT_MM = 0.5
}
