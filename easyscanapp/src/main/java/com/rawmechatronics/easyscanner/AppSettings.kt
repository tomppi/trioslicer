package com.rawmechatronics.easyscanner

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/** Everything the Settings screen controls (keys in `res/xml/preferences.xml`). */
data class AppSettings(
    val targetFps: Int,
    val autoResolution: Boolean,
    /** Longest side of the analysis stream; 0 = the largest the camera offers. */
    val maxLongSide: Int,
    val cameraId: String,
    val manualFocus: Boolean,
    val focusDiopters: Float,
    val manualExposure: Boolean,
    val exposureUs: Int,
    val iso: Int,
    val exposureComp: Int,
    val board: Board,
    val laserEnabled: Boolean,
    val laserColor: String,
    val laserThresh: Int,
    val laserMinSat: Int,
    val laserWithoutBoard: Boolean,
    val volumeHeightMm: Double,
    val volumeMarginMm: Double,
    val calibViews: Int,
    val planeViews: Int,
    val aruco3: Boolean,
    val roiTracking: Boolean,
    val draw: Draw,
    val photos: Photos,
    /** The four tool modes instead of the guided steps. */
    val expertMode: Boolean,
    /** [board] as last confirmed in the guided Board step, or null. */
    val boardConfirmed: String?,
) {
    data class Board(val squaresX: Int, val squaresY: Int, val squareMm: Double, val markerMm: Double, val dict: Int) {
        /** Identifies the board, so changing any number un-confirms it. */
        val key get() = "${squaresX}x$squaresY/$squareMm/$markerMm/$dict"
    }

    /** A printable board from `charuco/boards/boards.json`. */
    data class BoardPreset(val paper: String, val board: Board) {
        val label get() = "$paper · ${board.squaresX}×${board.squaresY} · ${board.squareMm.fmt()} mm"
    }
    data class Draw(val grid: Boolean, val axes: Boolean, val corners: Boolean, val volume: Boolean, val cloud: Boolean)

    /** Photo keyframes: [trigger] is "both", "interval" or "motion". */
    data class Photos(
        val trigger: String,
        val intervalS: Double,
        val moveMm: Double,
        val turnDeg: Double,
        val maxSpeedMmS: Double,
        val maxTurnDegS: Double,
        /** Take photo keyframes during every scan. */
        val withScan: Boolean,
    )

    /** The core's `Config` JSON (`core/algo/src/config.rs`). */
    fun nativeConfig(camera: CameraInfo?): String = JSONObject().apply {
        put("board", JSONObject().apply {
            put("squares_x", board.squaresX)
            put("squares_y", board.squaresY)
            put("square_mm", board.squareMm)
            put("marker_mm", board.markerMm)
            put("dict", board.dict)
        })
        put("laser", JSONObject().apply {
            put("enabled", laserEnabled)
            put("color", laserColor)
            put("thresh", laserThresh)
            put("min_sat", laserMinSat)
            put("without_board", laserWithoutBoard)
        })
        put("volume", JSONObject().apply {
            put("height_mm", volumeHeightMm)
            put("margin_mm", volumeMarginMm)
        })
        put("detector", JSONObject().apply {
            put("aruco3", aruco3)
            put("roi_tracking", roiTracking)
        })
        put("draw", JSONObject().apply {
            put("grid", draw.grid)
            put("axes", draw.axes)
            put("corners", draw.corners)
            put("volume", draw.volume)
            put("cloud", draw.cloud)
        })
        put("camera", JSONObject().apply {
            put("id", camera?.id ?: cameraId)
            camera?.guess?.let { g ->
                put("guess", JSONObject().apply {
                    put("fx", g.fx); put("fy", g.fy); put("cx", g.cx); put("cy", g.cy)
                    put("dist", JSONArray(g.dist.toList()))
                    put("width", g.width); put("height", g.height)
                })
            }
            if (manualFocus && camera?.manualFocus == true) put("focus_diopters", focusDiopters.toDouble())
        })
        put("keyframes", JSONObject().apply {
            // a trigger that is not selected is sent as 0 = off
            val interval = photos.trigger != "motion"
            val motion = photos.trigger != "interval"
            put("interval_s", if (interval) photos.intervalS else 0.0)
            put("move_mm", if (motion) photos.moveMm else 0.0)
            put("turn_deg", if (motion) photos.turnDeg else 0.0)
            put("max_speed_mm_s", photos.maxSpeedMmS)
            put("max_turn_deg_s", photos.maxTurnDegS)
        })
        put("calib_views", calibViews)
        put("plane_views", planeViews)
    }.toString()

    companion object {
        fun load(ctx: Context): AppSettings {
            val p = PreferenceManager.getDefaultSharedPreferences(ctx)
            fun str(k: String, d: String) = p.getString(k, d) ?: d
            fun int(k: String, d: Int) = str(k, d.toString()).trim().toIntOrNull() ?: d
            fun dbl(k: String, d: Double) = str(k, d.toString()).trim().replace(',', '.').toDoubleOrNull() ?: d
            return AppSettings(
                targetFps = int("target_fps", 30).coerceIn(5, 120),
                autoResolution = p.getBoolean("auto_resolution", true),
                maxLongSide = int("max_long_side", 1920),
                cameraId = str("camera_id", ""),
                manualFocus = p.getBoolean("manual_focus", false),
                focusDiopters = p.getInt("focus_tenths", 30) / 10f,
                manualExposure = p.getBoolean("manual_exposure", false),
                exposureUs = p.getInt("exposure_us", 8000),
                iso = p.getInt("iso", 400),
                exposureComp = p.getInt("exposure_comp", 0),
                board = Board(
                    squaresX = int("board_squares_x", 8),
                    squaresY = int("board_squares_y", 8),
                    squareMm = dbl("board_square_mm", 45.0),
                    markerMm = dbl("board_marker_mm", 33.3),
                    dict = int("board_dict", 5),
                ),
                laserEnabled = p.getBoolean("laser_enabled", true),
                laserColor = str("laser_color", "red"),
                laserThresh = p.getInt("laser_thresh", 12),
                laserMinSat = p.getInt("laser_min_sat", 40),
                laserWithoutBoard = p.getBoolean("laser_without_board", true),
                volumeHeightMm = dbl("volume_height_mm", 0.0),
                volumeMarginMm = dbl("volume_margin_mm", 20.0),
                calibViews = p.getInt("calib_views", 64),
                planeViews = p.getInt("plane_views", 10),
                aruco3 = p.getBoolean("aruco3", false),
                roiTracking = p.getBoolean("roi_tracking", true),
                draw = Draw(
                    grid = p.getBoolean("draw_grid", true),
                    axes = p.getBoolean("draw_axes", true),
                    corners = p.getBoolean("draw_corners", true),
                    volume = p.getBoolean("draw_volume", true),
                    cloud = p.getBoolean("draw_cloud", true),
                ),
                photos = Photos(
                    trigger = str("photo_trigger", "both"),
                    intervalS = dbl("photo_interval_s", 5.0),
                    moveMm = dbl("photo_move_mm", 50.0),
                    turnDeg = dbl("photo_turn_deg", 10.0),
                    maxSpeedMmS = dbl("photo_max_speed", 40.0),
                    maxTurnDegS = dbl("photo_max_turn", 8.0),
                    withScan = p.getBoolean("photos_with_scan", true),
                ),
                expertMode = p.getBoolean("expert_mode", false),
                boardConfirmed = p.getString("board_confirmed", null),
            )
        }

        val BOARD_PRESETS = listOf(
            BoardPreset("A4", Board(4, 6, 45.0, 33.3, 5)),
            BoardPreset("A3", Board(6, 8, 45.0, 33.3, 5)),
            BoardPreset("A2", Board(8, 10, 45.0, 33.3, 5)),
            BoardPreset("A1", Board(9, 13, 60.0, 44.4, 5)),
            BoardPreset("A0", Board(10, 14, 80.0, 59.2, 5)),
        )

        /** Make [b] the board in the settings. */
        fun saveBoard(ctx: Context, b: Board) {
            PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString("board_squares_x", b.squaresX.toString())
                .putString("board_squares_y", b.squaresY.toString())
                .putString("board_square_mm", b.squareMm.fmt())
                .putString("board_marker_mm", b.markerMm.fmt())
                .putString("board_dict", b.dict.toString())
                .apply()
        }
    }
}

/** 45.0 → "45", 33.3 → "33.3": the way the board sheets print the numbers. */
private fun Double.fmt() = if (this == Math.floor(this)) toInt().toString() else toString()
