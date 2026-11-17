package com.rawmechatronics.easyscanner

import java.util.concurrent.atomic.AtomicInteger

/**
 * One frame's result as the core writes it (`core/android/src/frame_out.rs`):
 * a fixed header of numbers, then layers `[kind, stride, count, data…]` in
 * analysis-frame pixel coordinates.
 */
class FrameResult(capacity: Int = 32 * 1024) {
    var data = FloatArray(capacity)
    var length = 0

    /** Wall time of the JNI call, measured by [FrameProcessor]. */
    var procMs = 0.0
    var timestampNs = 0L

    operator fun get(slot: Int): Float = data[slot]
    fun flag(slot: Int) = data[slot] > 0.5f
    fun events() = data[H.EVENTS].toInt()

    /** Visit each layer: kind, floats per item, item count, offset of the first float. */
    inline fun forEachLayer(visit: (kind: Int, stride: Int, count: Int, offset: Int) -> Unit) {
        var i = H.LEN
        while (i + 3 <= length) {
            val kind = data[i].toInt()
            val stride = data[i + 1].toInt()
            val count = data[i + 2].toInt()
            visit(kind, stride, count, i + 3)
            i += 3 + stride * count
        }
    }

    /** Header slots, mirroring `frame_out::h`. */
    object H {
        const val FRAME_W = 0
        const val FRAME_H = 1
        const val DETECTED = 2
        const val N_CORNERS = 3
        const val CAM_X = 4
        const val CAM_Y = 5
        const val CAM_Z = 6
        const val ROLL = 7
        const val PITCH = 8
        const val YAW = 9
        const val DISTANCE = 10
        const val REPROJ_RMS = 11
        const val POSE_MARGIN = 12
        const val FOCUS_PCT = 13
        const val T_TOTAL_MS = 14
        const val T_BOARD_MS = 15
        const val T_LASER_MS = 16
        const val LASER_STATE = 17
        const val LASER_POINTS = 18
        const val LASER_PEAK = 19
        const val LASER_MEAN_X = 20
        const val LASER_MEAN_Y = 21
        const val LASER_ANGLE = 22
        const val LASER_LENGTH = 23
        const val LASER_PLANE_RMS = 24
        const val CALIB_STATE = 25
        const val CALIB_COUNT = 26
        const val CALIB_TARGET = 27
        const val CALIB_RMS = 28
        const val PLANE_ACTIVE = 29
        const val PLANE_LINES = 30
        const val PLANE_TARGET = 31
        const val PLANE_SPREAD = 32
        const val PLANE_RMS = 33
        const val PLANE_OK = 34
        const val SCAN_ON = 35
        const val CLOUD_POINTS = 36
        const val CLOUD_MB = 37
        const val IN_VOLUME = 38
        const val K_CALIBRATED = 39
        const val FX = 40
        const val FY = 41
        const val CX = 42
        const val CY = 43
        const val EVENTS = 44
        const val LASER_ROI_FRAC = 45
        const val CALIB_ACTIVE = 46
        const val PHOTO_STATE = 47
        const val PHOTO_COUNT = 48
        const val CAM_SPEED = 49
        const val CAM_TURN = 50
        const val PHOTO_SERIAL = 51
        const val K_OK = 52
        const val PLANE_STALE = 53
        const val N_MARKERS = 54
        const val LEN = 55
    }

    /** Event bits in [H.EVENTS], mirroring `frame_out::ev`. */
    object Ev {
        const val MESSAGES = 1
        const val INTRINSICS_SAVED = 2
        const val PLANE_SAVED = 4
        const val PHOTO_DUE = 8
    }

    /** Photo keyframe states in [H.PHOTO_STATE], mirroring `frame_out::photo_state`. */
    object Photo {
        const val OFF = 0
        const val WAITING = 1
        const val IN_FLIGHT = 2
        const val HOLD_NO_BOARD = 3
        const val HOLD_POSE_RMS = 4
        const val HOLD_SETTLING = 5
        const val HOLD_MOVING = 6
        const val HOLD_FEW_CORNERS = 7
        const val NO_PHOTOS = 8
    }

    /** Layer kinds, mirroring `frame_out::layer`. */
    object Layer {
        const val CORNERS = 1
        const val GRID = 2
        const val OUTLINE = 3
        const val AXIS_X = 4
        const val AXIS_Y = 5
        const val AXIS_Z = 6
        const val VOLUME = 7
        const val CLOUD = 8
        const val LASER_RAW = 9
        const val LASER_IN = 10
        const val LASER_OUT = 11
        const val LASER_FIT = 12
    }

    /** Calibration states in [H.CALIB_STATE], mirroring `calib::CalibState`. */
    object Calib {
        const val IDLE = 0
        const val COLLECTING = 1
        const val SOLVING = 2
        const val SAVED = 3
        const val REJECTED = 4
    }
}

/**
 * Lock-free triple buffer: the producer always has a buffer to write, the
 * consumer always reads the newest complete one, neither ever waits or
 * allocates.
 */
class TripleBuffer<T>(make: () -> T) {
    private val buffers = List(3) { make() }
    private val middle = AtomicInteger(1)
    private var back = 0
    private var front = 2

    /** The buffer the producer fills next. */
    fun writeBuffer(): T = buffers[back]

    /** Publish the filled write buffer. */
    fun publish() {
        back = middle.getAndSet(back or DIRTY) and MASK
    }

    /** The newest published buffer, or null before the first publish. */
    fun latest(): T? {
        if (middle.get() and DIRTY != 0) {
            front = middle.getAndSet(front) and MASK
            seen = true
        }
        return if (seen) buffers[front] else null
    }

    private var seen = false

    private companion object {
        const val DIRTY = 4
        const val MASK = 3
    }
}
