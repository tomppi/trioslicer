package com.rawmechatronics.easyscanner

/**
 * Evaluates live camera-to-object distance (preferring freehand metric LASER_CAM depth, falling
 * back to ChArUco board distance) and gates capture outside `[minMm, maxMm]` anchored around the
 * calibrated focus distance.
 *
 * Uses a rolling alpha-trimmed mean across the last [windowSize] valid frames plus enter/exit
 * hysteresis and an exit deadband so single-frame specular/background spikes never cause false
 * red flicks or stop capture while holding still.
 */
class CaptureRangeGate(
    private val windowSize: Int = 5,
    private val enterFrames: Int = 2,
    private val exitFrames: Int = 5,
    private val unknownGraceFrames: Int = 12,
    private val exitMarginMm: Double = 15.0,
) {
    enum class Status { UNKNOWN, TOO_CLOSE, IN_RANGE, TOO_FAR }

    data class Eval(
        val status: Status,
        val distanceMm: Double,
        val allowCapture: Boolean,
        val source: String,
    )

    private var capturing = false
    private var inRun = 0
    private var rawInRun = 0
    private var outRun = 0
    private var unknownRun = 0
    private val zScratch = DoubleArray(512)
    private val ring = DoubleArray(windowSize.coerceAtLeast(1))
    private val sortBuf = DoubleArray(windowSize.coerceAtLeast(1))
    private var ringCount = 0
    private var ringHead = 0

    fun reset() {
        capturing = false
        inRun = 0
        rawInRun = 0
        outRun = 0
        unknownRun = 0
        ringCount = 0
        ringHead = 0
    }

    /** Extract live object distance in millimetres from [FrameResult] (laser first, then board). */
    fun measureDistanceMm(r: FrameResult): Pair<Double, String> {
        var count = 0
        r.forEachLayer { kind, stride, n, offset ->
            if (kind == FrameResult.Layer.LASER_CAM && stride == 3 && n > 0 && count == 0) {
                val step = maxOf(1, n / zScratch.size)
                var i = 0
                while (i < n && count < zScratch.size) {
                    val z = r.data[offset + i * 3 + 2].toDouble()
                    if (z.isFinite() && z in 40.0..4000.0) {
                        zScratch[count++] = z
                    }
                    i += step
                }
            }
        }
        if (count >= 3) {
            zScratch.sort(0, count)
            return zScratch[count / 2] to "laser"
        }
        if (r.flag(FrameResult.H.DETECTED)) {
            val d = r[FrameResult.H.DISTANCE].toDouble()
            if (d.isFinite() && d in 40.0..4000.0) {
                return d to "board"
            }
        }
        return Double.NaN to "none"
    }

    fun evaluate(
        r: FrameResult,
        gateEnabled: Boolean,
        minMm: Double,
        maxMm: Double,
    ): Eval {
        val (distMm, source) = measureDistanceMm(r)
        return evaluateDistance(distMm, source, gateEnabled, minMm, maxMm)
    }

    /**
     * Smooths [rawDistMm] with a rolling alpha-trimmed mean over [windowSize] valid frames
     * (dropping the lowest and highest outlier when >= 5 samples are present) and applies
     * Schmitt-trigger hysteresis before toggling [Eval.allowCapture].
     */
    fun evaluateDistance(
        rawDistMm: Double,
        source: String,
        gateEnabled: Boolean,
        minMm: Double,
        maxMm: Double,
    ): Eval {
        val lo = minOf(minMm, maxMm - 20.0).coerceAtLeast(50.0)
        val hi = maxOf(maxMm, lo + 20.0)

        val smoothedMm: Double
        if (rawDistMm.isFinite()) {
            // When auto-paused out of range, returning inside [lo, hi] for `enterFrames`
            // consecutive frames flushes stale out-of-range samples so auto-resume is immediate.
            if (!capturing && rawDistMm in lo..hi) {
                rawInRun++
                if (rawInRun >= enterFrames) {
                    ringCount = 0
                    ringHead = 0
                }
            } else {
                rawInRun = 0
            }
            ring[ringHead] = rawDistMm
            ringHead = (ringHead + 1) % ring.size
            if (ringCount < ring.size) ringCount++
            smoothedMm = trimmedMean()
        } else {
            rawInRun = 0
            // Brief dropouts keep showing the last smoothed distance during the grace window.
            smoothedMm = if (unknownRun < unknownGraceFrames && ringCount > 0) trimmedMean() else Double.NaN
        }

        // Slightly wider band while actively capturing so hovering on the boundary does not chatter.
        val effLo = if (capturing) (lo - exitMarginMm).coerceAtLeast(40.0) else lo
        val effHi = if (capturing) hi + exitMarginMm else hi

        val status = when {
            !rawDistMm.isFinite() && unknownRun >= unknownGraceFrames -> Status.UNKNOWN
            !smoothedMm.isFinite() -> Status.UNKNOWN
            smoothedMm < effLo -> Status.TOO_CLOSE
            smoothedMm > effHi -> Status.TOO_FAR
            else -> Status.IN_RANGE
        }

        if (!gateEnabled) {
            capturing = true
            inRun = 0; outRun = 0; unknownRun = 0
            return Eval(status, smoothedMm, allowCapture = true, source = source)
        }

        if (!rawDistMm.isFinite()) {
            unknownRun++
            inRun = 0; outRun = 0
            if (unknownRun > unknownGraceFrames) {
                capturing = false
                ringCount = 0
                ringHead = 0
            }
            val effectiveStatus = if (unknownRun > unknownGraceFrames) Status.UNKNOWN else status
            return Eval(effectiveStatus, smoothedMm, allowCapture = capturing, source = source)
        }

        unknownRun = 0
        when (status) {
            Status.IN_RANGE -> {
                inRun++; outRun = 0
                if (inRun >= enterFrames) capturing = true
            }
            Status.TOO_CLOSE, Status.TOO_FAR -> {
                outRun++; inRun = 0
                if (outRun >= exitFrames) capturing = false
            }
            Status.UNKNOWN -> {
                inRun = 0; outRun = 0
            }
        }
        return Eval(status, smoothedMm, allowCapture = capturing, source = source)
    }

    private fun trimmedMean(): Double {
        val n = ringCount
        if (n == 0) return Double.NaN
        for (i in 0 until n) sortBuf[i] = ring[i]
        sortBuf.sort(0, n)
        val trim = when {
            n >= 7 -> 2 // drop 2 lowest and 2 highest -> average middle 3 (rejects 2-frame spikes)
            n >= 5 -> 1 // drop 1 lowest and 1 highest -> average middle 3
            else -> 0
        }
        var sum = 0.0
        val count = n - 2 * trim
        for (i in trim until n - trim) sum += sortBuf[i]
        return sum / count
    }
}
