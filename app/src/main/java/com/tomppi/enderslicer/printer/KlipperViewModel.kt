package com.tomppi.enderslicer.printer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tomppi.enderslicer.nativebridge.KlipperEngineService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The printer front end's view model: what the machine this device is driving is
 * doing, and the actions a user takes on it.
 *
 * Watching starts with the view model, so opening the screen is what connects the UI
 * to the host - and the host itself is started by the USB attach intent, or by the
 * button here when it is not.
 *
 * The actions are one line each on purpose: they are G-code, the printer is the thing
 * that knows what it means, and klippy's own status is what reports the result. Any
 * checking done here would be a second opinion about a machine this code cannot see.
 */
class KlipperViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = KlipperPrinterRepository(application, viewModelScope)
    val state: StateFlow<KlipperPrinterState> = repository.state

    /** The console's scrollback, oldest first. */
    val consoleLines: StateFlow<List<KlipperConsoleLine>> = repository.consoleLines

    /** Every command this printer answers, with klippy's own description. */
    val commands: StateFlow<Map<String, String>> = repository.commands

    /** One temperature reading a second for the last five minutes. */
    val temperatures: StateFlow<List<KlipperTemperatureSample>> = repository.temperatures

    /** Where the running configuration came from: this app, or the person using it. */
    val configSource: StateFlow<KlipperConfigSource> = repository.configSource

    /** Prints this app has run, newest first. */
    val history: StateFlow<List<KlipperPrintRecord>> = repository.history

    init {
        repository.start()
    }

    /** Which host the app drives: this device, or a computer running Klipper. */
    internal fun hostChoice(): KlipperHostChoice = KlipperHostChoiceStore(getApplication()).load()

    /**
     * Point the app at a host, and connect to it.
     *
     * Saved before the reconnection is asked for: the repository reads the choice when it
     * opens a connection, so a choice saved afterwards would be one connection late.
     */
    internal fun setHostChoice(choice: KlipperHostChoice) {
        KlipperHostChoiceStore(getApplication()).save(choice)
        repository.reconnect()
    }

    /** Write pressure advance into the printer's configuration, where a restart finds it. */
    suspend fun savePressureAdvance(advance: Double, smoothTime: Double? = null): Boolean =
        repository.savePressureAdvance(advance, smoothTime)

    /** A Marlin-only command in a sliced file's start G-code, if it has one. */
    suspend fun marlinOnlyStartCommand(path: String): String? =
        repository.marlinOnlyStartCommand(path)

    /** One frame from a camera the host publishes, fetched when a screen asks for one. */
    suspend fun webcamSnapshot(url: String): ByteArray? = repository.webcamSnapshot(url)

    fun startHost() = KlipperEngineService.start(getApplication())

    /** Stop the host: the printer is released and the process ends. */
    fun stopHost() = KlipperEngineService.stop(getApplication())

    /**
     * Rebuild the host, which is what applying a saved configuration means.
     *
     * Not a G-code restart: klippy's own restart makes it exit, and the process it
     * exits from is the one this app started and would have to start again.
     */
    /**
     * Restart the host, saving anything klippy is holding first.
     *
     * A calibration that has been accepted but not saved - a probe offset, a PID, a mesh
     * profile - lives only in klippy's memory, and klippy writes it when SAVE_CONFIG is run
     * and at no other time: not on a signal, not on the way out. Restarting without this
     * discarded the lot, and the dialog that promised only that a print would be lost said
     * nothing about it.
     */
    fun restartHost() {
        viewModelScope.launch {
            if (state.value.saveConfigPending) {
                repository.saveConfig()
                // klippy answers when it has written the file; a moment is enough, and
                // restarting into a half-written config would be worse than waiting.
                delay(SAVE_CONFIG_SETTLE_MS)
            }
            // The repository decides what a restart means for the host in use: the service
            // here, or klippy restarting itself on the computer.
            repository.restartHost()
        }
    }

    // The machine.
    fun home(axes: String = "") = repository.home(axes)
    fun jog(axis: String, distance: Double, feedrate: Int) = repository.jog(axis, distance, feedrate)
    fun adjustZOffset(delta: Double, move: Boolean = true) = repository.adjustZOffset(delta, move)
    fun resetZOffset() = repository.resetZOffset()
    fun disableMotors() = repository.disableMotors()
    fun enableMotors() = repository.enableMotors()
    fun queryEndstops() = repository.queryEndstops()

    // Temperatures.
    fun setExtruderTemperature(celsius: Int) = repository.setExtruderTemperature(celsius)
    fun setBedTemperature(celsius: Int) = repository.setBedTemperature(celsius)
    fun setHeaterTemperature(heater: String, celsius: Int) = when (heater) {
        "extruder" -> repository.setExtruderTemperature(celsius)
        "heater_bed" -> repository.setBedTemperature(celsius)
        // Any other heater is addressed the way its own section names it.
        else -> repository.send(KlipperScripts.heaterTemperature(heater, celsius))
    }
    fun coolDown() = repository.coolDown()
    fun calibratePid(heater: String, target: Int) = repository.calibratePid(heater, target)

    // Printing.
    fun printFile(sourcePath: String, name: String) {
        viewModelScope.launch { repository.printFile(sourcePath, name) }
    }
    fun pausePrint() = repository.pausePrint()
    fun resumePrint() = repository.resumePrint()
    fun cancelPrint() = repository.cancelPrint()

    // Extrusion and the print's own adjustments.
    fun extrude(lengthMm: Double, feedrate: Int) = repository.extrude(lengthMm, feedrate)
    fun setPressureAdvance(advance: Double) = repository.setPressureAdvance(advance)
    fun setRetraction(length: Double, speed: Double) = repository.setRetraction(length, speed)
    fun setFan(percent: Int) = repository.setFan(percent)

    /** A fan the configuration named: SET_FAN_SPEED FAN=<name> SPEED=<0..1>. */
    fun setGenericFan(name: String, speed: Double) = repository.setGenericFan(name, speed)

    /** The toolhead's own limits, live, until the host restarts. */
    fun setVelocityLimits(
        maxVelocity: Double? = null,
        maxAccel: Double? = null,
        squareCornerVelocity: Double? = null,
    ) = repository.setVelocityLimits(maxVelocity, maxAccel, squareCornerVelocity)
    fun setSpeedFactor(percent: Int) = repository.setSpeedFactor(percent)
    fun setExtrudeFactor(percent: Int) = repository.setExtrudeFactor(percent)
    fun excludeObject(name: String) = repository.excludeObject(name)

    // The Z probe: the offset the first layer is decided by.
    fun calibrateProbe() = repository.calibrateProbe()
    fun testZ(delta: Double) = repository.testZ(delta)
    fun acceptProbeCalibration() = repository.acceptProbeCalibration()
    fun abortProbeCalibration() = repository.abortProbeCalibration()

    // Input shaping: adjusted live, saved into the printer's configuration.
    fun applyShaper(axis: String, type: String, frequency: Double, dampingRatio: Double?) =
        repository.applyShaper(axis, type, frequency, dampingRatio)
    fun reportShapers() = repository.reportShapers()

    /**
     * Measure one axis with the phone's own accelerometer.
     *
     * The recording starts before the sweep is asked for and runs past its end, because the
     * analysis works out when the machine started moving from the recording itself - the
     * phone's clock and the printer's have nothing to do with each other, and a free
     * measurement must not need them to.
     */
    private val _measurement = MutableStateFlow<KlipperMeasurementState>(KlipperMeasurementState.Idle)

    /**
     * What the measurement is doing, held here rather than on the screen.
     *
     * A sweep takes a minute. A screen that started it would cancel it by being left, and a
     * screen that kept the result would lose it the same way - so the run belongs to the view
     * model, and the Shaping screen only shows it.
     */
    val measurement: StateFlow<KlipperMeasurementState> = _measurement.asStateFlow()

    private val _measurementHistory = MutableStateFlow<List<KlipperResonanceMeasurement>>(emptyList())

    /**
     * The measurements so far, newest last.
     *
     * Kept because one measurement is as much a picture of where the phone was standing as of
     * the machine: a peak that appears in one run and nowhere else is mostly a fact about the
     * phone's position, while the frequency that comes back wherever it is put is the
     * machine's. Both are on screen, and the difference between them is the point.
     */
    val measurementHistory: StateFlow<List<KlipperResonanceMeasurement>> =
        _measurementHistory.asStateFlow()

    /** Forget the runs so far, for a new axis or a machine that has been changed. */
    fun clearMeasurements() {
        _measurementHistory.value = emptyList()
        _measurement.value = KlipperMeasurementState.Idle
    }

    /**
     * Measure one axis with the phone's own accelerometer, in the background.
     *
     * The recording starts before the sweep is asked for and runs past its end, because the
     * analysis works out when the machine started moving from the recording itself - the
     * phone's clock and the printer's have nothing to do with each other, and a measurement
     * must not need them to.
     */
    fun measureResonances(
        axis: String,
        freqStart: Double,
        freqEnd: Double,
        hzPerSec: Double,
        onToolhead: Boolean = false,
        movingMassGrams: Double = DEFAULT_MOVING_MASS_GRAMS,
    ) {
        if (_measurement.value is KlipperMeasurementState.Measuring) return
        val total = ResonanceAnalysis.sweepDuration(freqStart, freqEnd, hzPerSec) + RECORDING_MARGIN
        _measurement.value = KlipperMeasurementState.Measuring(axis.uppercase(), 0.0, total)
        viewModelScope.launch {
            // A clock the user can see, because the alternative is a button that appears to do
            // nothing for the better part of a minute.
            val clock = launch {
                while (isActive) {
                    delay(250)
                    val current = _measurement.value
                    if (current is KlipperMeasurementState.Measuring) {
                        _measurement.value = current.copy(elapsedSeconds = current.elapsedSeconds + 0.25)
                    }
                }
            }
            val result = runCatching {
                measureAxis(axis, freqStart, freqEnd, hzPerSec, onToolhead, movingMassGrams)
            }
            clock.cancel()
            _measurement.value = result.fold(
                onSuccess = { measurement ->
                    _measurementHistory.value =
                        (_measurementHistory.value + measurement).takeLast(MAX_REMEMBERED_RUNS)
                    KlipperMeasurementState.Done(measurement)
                },
                onFailure = { error ->
                    KlipperMeasurementState.Failed(
                        axis.uppercase(),
                        error.message ?: "the measurement failed",
                    )
                },
            )
        }
    }

    private suspend fun measureAxis(
        axis: String,
        freqStart: Double,
        freqEnd: Double,
        hzPerSec: Double,
        onToolhead: Boolean,
        movingMassGrams: Double,
    ): KlipperResonanceMeasurement = withContext(Dispatchers.IO) {
        val accelerometer = PhoneAccelerometer(getApplication())
        // Before the printer is asked to do anything: a phone without an accelerometer used to
        // be discovered after the sweep had already been commanded, so the machine ran for a
        // minute to measure nothing at all.
        if (accelerometer.sensor == null) {
            throw IllegalStateException("this phone has no accelerometer to measure with")
        }
        val duration = ResonanceAnalysis.sweepDuration(freqStart, freqEnd, hzPerSec)
        val recording = coroutineScope {
            // Long enough to include the journey as well as the sweep: the module drives to
            // the middle of the travel before it starts - three seconds on this printer, more
            // on a bigger one - and the analysis drops every frequency whose window runs past
            // the end of the recording. With three seconds of margin the curve simply stopped
            // near 116 Hz on the first measurement after homing, and a machine ringing above
            // that was reported as having nothing to say.
            val recorder = async { accelerometer.record(duration + RECORDING_MARGIN) }
            // A beat of quiet first, which is what the analysis uses as the machine's own
            // noise floor, and what it compares the sweep against to find where it began.
            delay(700)
            repository.playResonances(
                axis,
                freqStart,
                freqEnd,
                hzPerSec,
                if (onToolhead) KlipperScripts.GENTLE_ACCEL_PER_HZ else KlipperScripts.STANDARD_ACCEL_PER_HZ,
            )
            recorder.await()
        } ?: throw IllegalStateException("this phone would not give its accelerometer")
        val clean = ResonanceAnalysis.detrend(recording.samples, recording.sampleRateHz)
        // The printer's own account of when the sweep started, if the console caught it: it
        // is stamped on the wall clock, and the recording carries the wall clock of its first
        // sample. Failing that, the machine is heard rather than asked, which is less exact.
        val announced = repository.sweepStartMillis(recording.startedAtMillis.toLong())
        val movedAt: Double
        val startFrom: String
        if (announced != null) {
            movedAt = (announced - recording.startedAtMillis) / 1000.0
            startFrom = "the printer's own sweep messages"
        } else {
            val start = ResonanceAnalysis.motionStart(clean, recording.sampleRateHz)
                ?: throw IllegalStateException(
                    "the machine was not heard moving, and the printer said nothing about " +
                        "the sweep: check it is homed, and that the axis chosen is the one " +
                        "that moved",
                )
            movedAt = start / recording.sampleRateHz
            startFrom = "the vibration itself, which is less exact"
        }
        val curve = ResonanceAnalysis.response(
            samples = clean,
            sampleRateHz = recording.sampleRateHz,
            motionStartSeconds = movedAt,
            fStart = freqStart,
            fEnd = freqEnd,
            hzPerSec = hzPerSec,
            // On the toolhead the sensor rides the drive as well as the machine, and the
            // drive's own acceleration climbs with frequency: it has to be divided out or the
            // curve is mostly the sweep rather than the machine.
        )
        if (curve.magnitudes.isEmpty()) {
            throw IllegalStateException("the sweep was too short to measure")
        }
        // The phone's weight is riding on the toolhead, which lowers the frequency it is
        // measuring; the correction is an estimate, and the card says so.
        val correction = if (onToolhead) {
            ResonanceAnalysis.massCorrection(movingMassGrams, PHONE_GRAMS)
        } else {
            1.0
        }
        KlipperResonanceMeasurement(
            axis = axis.uppercase(),
            sensorName = recording.sensorName,
            sampleRateHz = recording.sampleRateHz,
            movedAt = movedAt,
            curve = curve,
            peaks = curve.peaks().map { peak ->
                peak.copy(frequencyHz = peak.frequencyHz * correction)
            },
            onToolhead = onToolhead,
            massCorrection = correction,
            movingMassGrams = movingMassGrams,
            saturated = recording.saturated,
            startFrom = startFrom,
        )
    }

    private companion object {
        /** Seconds to record before the sweep, so there is a quiet stretch to compare with. */
        /**
         * Seconds recorded beyond the sweep: the travel to the test point, its dwell, and
         * enough slack for a larger printer. It is also what the progress bar counts, so it
         * is not free - but a band that stops short is worse than a minute of waiting.
         */
        const val RECORDING_MARGIN = 15.0

        /** How many measurements stay on screen to be compared with each other. */
        const val MAX_REMEMBERED_RUNS = 8

        /**
         * What the phone weighs, which is what the toolhead measurement has to correct for.
         *
         * A Galaxy Z Fold5 is 253 grams. It is the one number in the correction that does not
         * depend on the printer, which is why it is a constant and the moving mass is not.
         */
        const val PHONE_GRAMS = 253.0

        /**
         * A starting figure for the moving mass: carriage, hotend, extruder, duct and probe.
         *
         * An Orbiter v2 direct drive is light for what it is, but it puts the motor on the
         * carriage where a stock Ender-3 V2 had nothing at all, so the assembly comes to
         * something like 350 grams against the stock machine's 210. The screen lets it be
         * corrected, and two measurements from two places can work it out from the machine
         * itself.
         */
        const val DEFAULT_MOVING_MASS_GRAMS = 350.0

        /** How long to let klippy finish writing its saved values before restarting. */
        const val SAVE_CONFIG_SETTLE_MS = 750L
    }
    internal suspend fun saveShapers(settings: List<KlipperConfigFile.ShaperSetting>): Boolean =
        repository.saveShapers(settings)

    // The bed mesh.
    fun calibrateMesh() = repository.calibrateMesh()
    fun meshProfile(action: String, name: String) = repository.meshProfile(action, name)

    // The extruder calibration: the one figure that decides whether the slicer's extrusion
    // is what the printer delivers.
    fun extrudeForCalibration(lengthMm: Double) = repository.extrudeForCalibration(lengthMm)
    fun applyRotationDistance(distance: Double) = repository.applyRotationDistance(distance)
    fun askRotationDistance() = repository.askRotationDistance()
    internal suspend fun saveRotationDistance(distance: Double): Boolean =
        repository.saveRotationDistance(distance)

    /** Add Klipper's starter macros that this printer is missing; returns their names. */
    internal suspend fun addStarterMacros(): List<String> = repository.addStarterMacros()

    // Macros and the console.
    fun runMacro(name: String) = repository.runMacro(name)
    fun sendCommand(script: String) = repository.send(script)
    fun clearConsole() = repository.clearConsole()

    // The host itself.
    fun firmwareRestart() = repository.firmwareRestart()
    fun saveConfig() = repository.saveConfig()
    fun emergencyStop() = repository.emergencyStop()

    /** The configuration the host is running with, for the Machine screen. */
    suspend fun readConfig(): String? = repository.readConfigFile()

    /**
     * Bring a printer.cfg of the user's own: their printer, as their printer is set up.
     *
     * More than one file may be chosen, because a configuration that includes others
     * needs them beside it - the app writes them where klippy will look for them.
     */
    suspend fun importConfig(uris: List<Uri>): Result<KlipperImportResult> = repository.importConfig(uris)

    /** Write the running configuration out where the user asked for it. */
    suspend fun exportConfig(uri: Uri): Boolean = repository.exportConfig(uri)

    /** True when the running configuration is not the one this app ships. */
    suspend fun configDiffersFromShipped(): Boolean = repository.configDiffersFromShipped()

    /** Put the shipped configuration back, keeping what the printer has saved. */
    suspend fun restoreShippedConfig(): Boolean = repository.restoreShippedConfig()

    /** The tail of klippy's own log, for the Machine screen. */
    suspend fun readLog(): String? = repository.readHostLog()

    // The files on the printer's own SD card.
    suspend fun listGcodeFiles(): List<KlipperGcodeFile> = repository.listGcodeFiles()
    suspend fun printGcodeFile(name: String) = repository.printGcodeFile(name)
    suspend fun deleteGcodeFile(name: String): Boolean = repository.deleteGcodeFile(name)
    suspend fun thumbnailFor(name: String): Bitmap? = repository.fileThumbnail(name)

    /** Forget every print this app has written down. */
    fun clearHistory() = repository.clearHistory()

    override fun onCleared() {
        repository.stop()
    }
}
