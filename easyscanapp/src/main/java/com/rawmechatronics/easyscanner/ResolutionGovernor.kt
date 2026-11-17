package com.rawmechatronics.easyscanner

import kotlin.math.ceil

/** An analysis stream size (pure Kotlin so the governor unit-tests on the JVM). */
data class FrameSize(val width: Int, val height: Int) {
    override fun toString() = "${width}x$height"
}

/**
 * Picks the largest analysis resolution whose processing still keeps up with
 * the target frame rate.
 *
 * After each measurement window (frames right after a switch are skipped —
 * the pipeline is still settling) it takes the slow end of the window (80th
 * percentile processing time, `p80`) and remembers it for that size:
 *  - **down** when p80 eats more than 92 % of the frame budget in two windows
 *    in a row (one slow window is usually start-up warm-up: OpenCV and thread
 *    pool initialisation, JIT), straight to the first smaller size expected
 *    to fit in 80 % (its remembered time, else a
 *    pixel-count estimate — which errs toward the larger size, since a fixed
 *    per-frame cost does not shrink with the image);
 *  - **up** one step when the larger size is remembered to fit in 85 %, or,
 *    never measured (or forgotten), as a probe when this size leaves real
 *    headroom (p80 ≤ 60 %). A probe that is too slow comes straight back down
 *    and is remembered for [MEMORY_NS], so it is not retried every few
 *    seconds. Measuring instead of predicting upward matters: fixed per-frame
 *    costs make "time ∝ pixels" badly pessimistic at small sizes.
 * A frame rate held back by the camera itself (long exposure in low light) is
 * reported, not "fixed" by shrinking the image.
 *
 * Pure logic, no Android calls.
 */
class ResolutionGovernor(
    /** Candidate sizes, largest first; all reachable at the target fps. */
    private val sizes: List<FrameSize>,
    private val targetFps: Int,
    startIndex: Int,
) {
    var index = startIndex.coerceIn(0, sizes.lastIndex)
        private set

    /** Paused (e.g. during intrinsics calibration, which needs a fixed size). */
    var frozen = false

    /** Largest size allowed (index); raised by [limitTo] when the camera refuses a size. */
    private var ceiling = 0

    var status = "measuring"
        private set

    private val budgetMs = 1000.0 / targetFps
    private val window = ArrayList<Double>(128)
    private val measuredMs = DoubleArray(sizes.size) { Double.NaN }
    private val measuredAtNs = LongArray(sizes.size)
    private var windowStartNs = 0L
    private var settleUntilNs = 0L
    private var lastChangeNs = 0L
    private var frames = 0
    private var slowWindows = 0

    val size: FrameSize get() = sizes[index]

    /** Never go larger than [i] again (the camera could not stream it); restart there. */
    fun limitTo(i: Int, nowNs: Long) {
        ceiling = i.coerceIn(0, sizes.lastIndex)
        reset(maxOf(index, ceiling), nowNs)
    }

    /** Restart measuring at [i] (e.g. after the camera was reconfigured). */
    fun reset(i: Int, nowNs: Long) {
        index = i.coerceIn(0, sizes.lastIndex)
        window.clear()
        frames = 0
        slowWindows = 0
        settleUntilNs = nowNs + SETTLE_NS
        windowStartNs = settleUntilNs
        lastChangeNs = nowNs
        status = "measuring"
    }

    /**
     * Feed one processed frame. Returns the new size index when a switch is
     * wanted, else null.
     */
    fun onFrame(procMs: Double, nowNs: Long): Int? {
        if (nowNs < settleUntilNs) return null
        if (window.isEmpty()) windowStartNs = nowNs
        window.add(procMs)
        frames++
        val spanNs = nowNs - windowStartNs
        if (spanNs < WINDOW_NS || window.size < 8) return null

        val sorted = window.sorted()
        val p80 = sorted[(ceil(sorted.size * 0.8).toInt() - 1).coerceIn(0, sorted.lastIndex)]
        val deliveredFps = (frames - 1) * 1e9 / spanNs
        window.clear()
        frames = 0
        measuredMs[index] = p80
        measuredAtNs[index] = nowNs
        status = "%s %.0f fps, proc p80 %.1f / %.1f ms".format(sizes[index], deliveredFps, p80, budgetMs)
        if (frozen) {
            status += " (locked)"
            return null
        }

        slowWindows = if (p80 > 0.92 * budgetMs) slowWindows + 1 else 0
        if (slowWindows >= 2 && index < sizes.lastIndex) {
            var next = index + 1
            while (next < sizes.lastIndex && expected(next, p80, nowNs) > 0.8 * budgetMs) next++
            return switchTo(next, nowNs)
        }
        if (slowWindows > 0) return null
        if (deliveredFps < 0.9 * targetFps) status += " — camera-limited (exposure?)"
        if (index > ceiling && nowNs - lastChangeNs > UP_COOLDOWN_NS) {
            val known = remembered(index - 1, nowNs)
            val up = if (known != null) known <= 0.85 * budgetMs else p80 <= 0.6 * budgetMs
            if (up) return switchTo(index - 1, nowNs)
        }
        return null
    }

    private fun remembered(i: Int, nowNs: Long): Double? =
        measuredMs[i].takeIf { !it.isNaN() && nowNs - measuredAtNs[i] < MEMORY_NS }

    /** Remembered time at [i], else [ms] at the current size scaled by pixel count. */
    private fun expected(i: Int, ms: Double, nowNs: Long): Double =
        remembered(i, nowNs) ?: (ms * area(i) / area(index))

    private fun area(i: Int) = sizes[i].width.toDouble() * sizes[i].height

    private fun switchTo(i: Int, nowNs: Long): Int {
        reset(i, nowNs)
        return i
    }

    companion object {
        const val SETTLE_NS = 700_000_000L
        const val WINDOW_NS = 1_000_000_000L
        const val UP_COOLDOWN_NS = 4_000_000_000L
        const val MEMORY_NS = 60_000_000_000L
    }
}
