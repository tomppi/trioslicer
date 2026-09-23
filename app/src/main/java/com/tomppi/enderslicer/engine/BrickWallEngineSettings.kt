package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.SlicerSettings

/**
 * App-owned settings consumed by the enderslicercura CuraEngine patch.
 *
 * These keys intentionally do not belong to Cura's upstream definition tree.
 * They are appended to the temporary resolved slice snapshot after Cura has
 * evaluated its normal dependency graph.
 */
internal object BrickWallEngineSettings {
    const val ENABLED = "enderslicer_brick_wall_enabled"
    const val SPEED = "enderslicer_brick_wall_speed"
    const val FLOW = "enderslicer_brick_wall_flow"
    const val FAN_SPEED = "enderslicer_brick_wall_fan_speed"
    const val MAX_ITERATIONS = "enderslicer_brick_wall_max_iterations"
    const val BRICK_LENGTH = "enderslicer_brick_wall_brick_length"

    fun values(settings: SlicerSettings): LinkedHashMap<String, String> {
        validate(settings)
        return linkedMapOf(
            ENABLED to settings.brickWallEnabled.toString(),
            SPEED to settings.brickWallSpeedMmPerSecond.toString(),
            FLOW to settings.brickWallFlowPercent.toString(),
            FAN_SPEED to settings.brickWallFanSpeedPercent.toString(),
            MAX_ITERATIONS to settings.brickWallMaxIterations.toString(),
            BRICK_LENGTH to settings.brickWallBrickLengthMm.toString(),
        )
    }

    fun validate(settings: SlicerSettings) {
        require(settings.brickWallSpeedMmPerSecond in 0.5..100.0) {
            "Brick-wall speed must be between 0.5 and 100 mm/s"
        }
        require(settings.brickWallFlowPercent in 50.0..200.0) {
            "Brick-wall flow must be between 50% and 200%"
        }
        require(settings.brickWallFanSpeedPercent in 0.0..100.0) {
            "Brick-wall fan speed must be between 0% and 100%"
        }
        require(settings.brickWallMaxIterations in 2..200) {
            "Brick-wall course limit must be between 2 and 200"
        }
        require(settings.brickWallBrickLengthMm in 0.5..10.0) {
            "Brick-wall brick length must be between 0.5 and 10 mm"
        }
    }
}
