package com.rawmechatronics.imucalib

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import java.util.Locale
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Records a stationary IMU sample set and estimates the Kalibr noise statistics
 * from an Allan-deviation curve:
 *  - noise density N: white-noise floor, N = sigma(tau) * sqrt(tau)
 *  - random walk K:   rate-random-walk slope, K = sigma(tau) * sqrt(3 / tau)
 * Duration is a user choice (5/10/20 min); 1 h is not required for a phone MEMS.
 */
class ImuNoiseRecorder(
    context: Context,
    private val durationS: Int,
    private val onProgress: (Float) -> Unit,
    private val onDone: (Result?) -> Unit,
) : SensorEventListener {

    data class Sample(val t: Long, val v: DoubleArray)

    data class Result(
        val actualDurationS: Double,
        val accelRateHz: Double,
        val gyroRateHz: Double,
        val accelCount: Int,
        val gyroCount: Int,
        val accelNoiseDensity: Double,
        val accelRandomWalk: Double,
        val gyroNoiseDensity: Double,
        val gyroRandomWalk: Double,
    ) {
        fun yaml(): String = String.format(Locale.US,
            "rostopic: /imu0\n" +
                "update_rate: %.3f\n" +
                "accelerometer_noise_density: %.9g\n" +
                "accelerometer_random_walk: %.9g\n" +
                "gyroscope_noise_density: %.9g\n" +
                "gyroscope_random_walk: %.9g\n",
            (accelRateHz + gyroRateHz) * 0.5,
            accelNoiseDensity, accelRandomWalk, gyroNoiseDensity, gyroRandomWalk)

        fun report(): String = String.format(Locale.US,
            "duration %.1f s, accel %.1f Hz (%d), gyro %.1f Hz (%d)\n" +
                "accel noise density %.6g m/s^2/sqrt(Hz), random walk %.6g m/s^3/sqrt(Hz)\n" +
                "gyro noise density %.6g rad/s/sqrt(Hz), random walk %.6g rad/s^2/sqrt(Hz)\n",
            actualDurationS, accelRateHz, accelCount, gyroRateHz, gyroCount,
            accelNoiseDensity, accelRandomWalk, gyroNoiseDensity, gyroRandomWalk)
    }

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val accel = ArrayList<Sample>()
    private val gyro = ArrayList<Sample>()
    private val startNs = System.nanoTime()
    @Volatile private var running = true
    private var finished = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val elapsed = (System.nanoTime() - startNs) / 1e9
            onProgress((elapsed / durationS).toFloat().coerceIn(0f, 1f))
            if (elapsed >= durationS) stop(compute = true)
            else handler.postDelayed(this, 1000L)
        }
    }

    fun start(): Boolean {
        val a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (a == null || g == null) return false
        sm.registerListener(this, a, SensorManager.SENSOR_DELAY_FASTEST)
        sm.registerListener(this, g, SensorManager.SENSOR_DELAY_FASTEST)
        handler.postDelayed(tick, 1000L)
        return true
    }

    fun stop(compute: Boolean) {
        if (finished) return
        finished = true
        running = false
        handler.removeCallbacks(tick)
        try { sm.unregisterListener(this) } catch (_: Throwable) {}
        if (!compute) { onDone(null); return }
        val accelCopy = synchronized(accel) { ArrayList(accel) }
        val gyroCopy = synchronized(gyro) { ArrayList(gyro) }
        Thread({
            val res = analyze(accelCopy, gyroCopy)
            handler.post { onDone(res) }
        }, "imu-noise-allan").start()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!running) return
        val s = Sample(e.timestamp, doubleArrayOf(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble()))
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> synchronized(accel) { if (accel.size < 2_000_000) accel.add(s) }
            Sensor.TYPE_GYROSCOPE -> synchronized(gyro) { if (gyro.size < 2_000_000) gyro.add(s) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        fun analyze(accelIn: List<Sample>, gyroIn: List<Sample>): Result? {
            if (accelIn.size < 500 || gyroIn.size < 500) return null
            val a = accelIn.sortedBy { it.t }
            val g = gyroIn.sortedBy { it.t }
            val aDur = (a.last().t - a.first().t) / 1e9
            val gDur = (g.last().t - g.first().t) / 1e9
            val aRate = if (aDur > 0) (a.size - 1) / aDur else 0.0
            val gRate = if (gDur > 0) (g.size - 1) / gDur else 0.0
            val aNoise = noiseDensity(a, aDur)
            val gNoise = noiseDensity(g, gDur)
            val aRw = randomWalk(a, aDur)
            val gRw = randomWalk(g, gDur)
            if (!aNoise.isFinite() || !gNoise.isFinite()) return null
            return Result(aDur, aRate, gRate, a.size, g.size,
                aNoise, if (aRw.isFinite() && aRw > 0) aRw else 1e-4,
                gNoise, if (gRw.isFinite() && gRw > 0) gRw else 1e-5)
        }

        private fun tausFor(durationS: Double): DoubleArray {
            if (durationS < 2.0) return DoubleArray(0)
            val maxTau = max(0.5, min(60.0, durationS / 8.0))
            val minTau = 0.02
            if (maxTau <= minTau) return DoubleArray(0)
            val n = 30
            return DoubleArray(n) { i ->
                val f = i.toDouble() / (n - 1)
                minTau * Math.pow(maxTau / minTau, f)
            }
        }

        /** Allan deviation for one axis, with non-uniform time-bin averaging. */
        private fun allan(samples: List<Sample>, axis: Int, tau: Double): Double? {
            if (samples.size < 8) return null
            val t0 = samples.first().t / 1e9
            val t1 = samples.last().t / 1e9
            val bins = floor((t1 - t0) / tau).toInt()
            if (bins < 4) return null
            val sums = DoubleArray(bins)
            val counts = IntArray(bins)
            for (s in samples) {
                val idx = ((s.t / 1e9 - t0) / tau).toInt()
                if (idx in 0 until bins) {
                    sums[idx] += s.v[axis]
                    counts[idx]++
                }
            }
            var ss = 0.0
            var n = 0
            var prev = Double.NaN
            for (i in 0 until bins) {
                if (counts[i] == 0) { prev = Double.NaN; continue }
                val mean = sums[i] / counts[i]
                if (!prev.isNaN()) { val d = mean - prev; ss += d * d; n++ }
                prev = mean
            }
            if (n < 3) return null
            return sqrt(0.5 * ss / n)
        }

        private fun noiseDensity(samples: List<Sample>, durationS: Double): Double {
            val taus = tausFor(durationS)
            val vals = ArrayList<Double>()
            for (tau in taus) {
                if (tau < 0.4 || tau > 2.5) continue
                for (axis in 0..2) {
                    val ad = allan(samples, axis, tau) ?: continue
                    if (ad > 0) vals.add(ad * sqrt(tau))
                }
            }
            if (vals.isEmpty()) return Double.NaN
            vals.sort()
            return vals[vals.size / 2]
        }

        private fun randomWalk(samples: List<Sample>, durationS: Double): Double {
            val taus = tausFor(durationS)
            val low = max(10.0, durationS / 20.0)
            val vals = ArrayList<Double>()
            for (tau in taus) {
                if (tau < low) continue
                for (axis in 0..2) {
                    val ad = allan(samples, axis, tau) ?: continue
                    if (ad > 0) vals.add(ad * sqrt(3.0 / tau))
                }
            }
            if (vals.isEmpty()) return Double.NaN
            vals.sort()
            return vals[vals.size / 2]
        }
    }
}
