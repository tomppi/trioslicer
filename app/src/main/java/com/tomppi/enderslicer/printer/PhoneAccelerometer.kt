package com.tomppi.enderslicer.printer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** What the phone heard, and what it heard it with. */
data class PhoneRecording(
    /** The magnitude of the acceleration, sample by sample. */
    val samples: DoubleArray,
    /** Measured from the samples' own timestamps, not from what was asked for. */
    val sampleRateHz: Double,
    val sensorName: String,
    /** True when a sample reached the sensor's own limit, so the recording is clipped. */
    val saturated: Boolean,
    /**
     * When the first sample was taken, on the same wall clock the console stamps lines with.
     *
     * Sensor samples are timed on the monotonic clock and console lines on the wall clock, so
     * the recording carries the bridge between them: the printer says when the sweep started,
     * and this says where that is in the samples.
     */
    val startedAtMillis: Double,
)

/**
 * The phone's accelerometer, used as the sensor the printer does not have.
 *
 * Klipper would measure its own resonances with an accelerometer wired to the micro-controller,
 * which is a gram of silicon bolted to the toolhead. A phone cannot be that - it weighs two
 * hundred times as much, and putting it on the moving mass would change the machine it is
 * measuring. On the printer's base it adds nothing and still feels the machine shake, which is
 * the same thing people do by ear when they run a ringing test; the difference is that this
 * records it.
 *
 * The samples are the magnitude of the vector rather than its components, so it does not matter
 * which way up the phone is lying: gravity is a constant in that number and the analysis
 * removes it, while the shaking is what is left.
 */
class PhoneAccelerometer(private val context: Context) {
    private companion object {
        /** How close to the sensor's own limit counts as having reached it. */
        const val SATURATION_FRACTION = 0.98
    }

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** The sensor itself, or null on a phone without one. */
    val sensor: Sensor? get() = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** What the screen says it measured with. */
    fun describe(): String = sensor?.let { "${it.name}, up to ${it.maximumRange.toInt()} m/s²" }
        ?: "no accelerometer"

    /**
     * Record for [seconds].
     *
     * SENSOR_DELAY_FASTEST, which on this phone is 416 Hz - the sensor's own maximum rate. The
     * band a shaper cares about ends around 120 Hz, so that is four times oversampled, and the
     * rate is measured from the timestamps afterwards rather than assumed: a sensor that
     * delivers slower than it promised would silently put every frequency in the wrong place.
     */
    suspend fun record(seconds: Double): PhoneRecording? = withContext(Dispatchers.IO) {
        val accelerometer = sensor ?: return@withContext null
        // Both clocks, read together: the sensor times its samples against the monotonic clock
        // and the console times its lines against the wall clock, and the sweep has to be
        // placed in the recording by one of them.
        val registeredAtMillis = System.currentTimeMillis()
        val registeredAtNanos = SystemClock.elapsedRealtimeNanos()
        val magnitudes = ArrayList<Double>((seconds * 400).toInt())
        val timestamps = ArrayList<Long>((seconds * 400).toInt())
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                // Floats from the sensor, doubles here: the analysis works in millimetres and
                // hertz, and a Float squared over ten thousand samples loses more than it saves.
                magnitudes.add(sqrt((x * x + y * y + z * z).toDouble()))
                timestamps.add(event.timestamp)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val thread = HandlerThread("printer-accelerometer").apply { start() }
        val registered = manager.registerListener(
            listener, accelerometer, SensorManager.SENSOR_DELAY_FASTEST, Handler(thread.looper),
        )
        if (!registered) {
            thread.quitSafely()
            return@withContext null
        }
        try {
            delay((seconds * 1000).toLong())
        } finally {
            manager.unregisterListener(listener)
            // quitSafely drains what is queued but does not wait for it: the reads below raced
            // the callbacks still being delivered, and the tail of every recording was lost.
            thread.quitSafely()
            thread.join(2_000)
        }
        if (magnitudes.size < 32) return@withContext null
        val span = (timestamps.last() - timestamps.first()) / 1_000_000_000.0
        val rate = if (span > 0.0) (magnitudes.size - 1) / span else 0.0
        if (rate <= 0.0) return@withContext null
        // The magnitude carries gravity, so a phone at rest already reads about 9.81 before
        // the machine moves at all. The limit that matters is the sensor's own, and reaching
        // it means the sweep was clipped - which on the toolhead is a real possibility, since
        // the commanded motion alone reaches three quarters of a g at the top of the band.
        val limit = accelerometer.maximumRange * SATURATION_FRACTION
        val saturated = magnitudes.any { it >= limit }
        val startedAtMillis = registeredAtMillis + (timestamps.first() - registeredAtNanos) / 1e6
        PhoneRecording(
            magnitudes.toDoubleArray(),
            rate,
            accelerometer.name,
            saturated,
            startedAtMillis,
        )
    }
}

/**
 * What the measurement is doing, kept where the screen cannot lose it.
 *
 * A sweep takes about a minute, and a screen that holds the result in its own state loses it
 * the moment the user looks at something else - or cancels the measurement outright, since a
 * coroutine started by a tab dies with the tab. Both happened, so the state lives here and the
 * run is started from the view model.
 */
sealed interface KlipperMeasurementState {
    /** Nothing measured since the app started. */
    data object Idle : KlipperMeasurementState

    data class Measuring(
        val axis: String,
        val elapsedSeconds: Double,
        val totalSeconds: Double,
    ) : KlipperMeasurementState {
        val progress: Double get() = if (totalSeconds > 0) (elapsedSeconds / totalSeconds).coerceIn(0.0, 1.0) else 0.0
    }

    data class Done(val measurement: KlipperResonanceMeasurement) : KlipperMeasurementState

    data class Failed(val axis: String, val message: String) : KlipperMeasurementState
}
/** One axis, measured: the curve, the peaks, and what they were measured with. */
data class KlipperResonanceMeasurement(
    val axis: String,
    val sensorName: String,
    val sampleRateHz: Double,
    /** When the machine was heard to start moving, in seconds into the recording. */
    val movedAt: Double,
    val curve: ResonanceCurve,
    /** The frequencies, corrected for the phone's own weight where that applied. */
    val peaks: List<ResonancePeak>,
    /** True when the phone was riding the toolhead rather than lying on the frame. */
    val onToolhead: Boolean = false,
    /** What the phone's weight is thought to have lowered the frequencies by; 1 if none. */
    val massCorrection: Double = 1.0,
    /** The moving mass the correction was worked out from, in grams. */
    val movingMassGrams: Double = 0.0,
    /**
     * Where the sweep's start was taken from.
     *
     * The printer tells the console when it begins, which is worth about a tenth of a second;
     * failing that, the recording's own vibration is used, which is worth as many seconds as
     * the machine takes to shake harder than its own noise - at two hertz per second, a second
     * of that is two hertz of error in every frequency below it.
     */
    val startFrom: String = "",

    /** True when the sensor reached its own limit, which makes the numbers unusable. */
    val saturated: Boolean = false,
)
