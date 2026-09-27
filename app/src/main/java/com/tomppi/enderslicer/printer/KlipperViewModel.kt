package com.tomppi.enderslicer.printer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tomppi.enderslicer.nativebridge.KlipperEngineService
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

    fun startHost() = KlipperEngineService.start(getApplication())

    /** Stop the host: the printer is released and the process ends. */
    fun stopHost() = KlipperEngineService.stop(getApplication())

    /**
     * Rebuild the host, which is what applying a saved configuration means.
     *
     * Not a G-code restart: klippy's own restart makes it exit, and the process it
     * exits from is the one this app started and would have to start again.
     */
    fun restartHost() = KlipperEngineService.restart(getApplication())

    // The machine.
    fun home(axes: String = "") = repository.home(axes)
    fun jog(axis: String, distance: Double) = repository.jog(axis, distance)
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
    fun extrude(lengthMm: Double) = repository.extrude(lengthMm)
    fun setPressureAdvance(advance: Double) = repository.setPressureAdvance(advance)
    fun setRetraction(length: Double, speed: Double) = repository.setRetraction(length, speed)
    fun setFan(percent: Int) = repository.setFan(percent)
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
    suspend fun measureResonances(
        axis: String,
        freqStart: Double,
        freqEnd: Double,
        hzPerSec: Double,
    ): Result<KlipperResonanceMeasurement> = withContext(Dispatchers.IO) {
        val accelerometer = PhoneAccelerometer(getApplication())
        val duration = ResonanceAnalysis.sweepDuration(freqStart, freqEnd, hzPerSec)
        val recording = coroutineScope {
            val recorder = async { accelerometer.record(duration + 3.0) }
            // A beat of quiet first, which is what the analysis uses as the machine's own
            // noise floor, and what it compares the sweep against to find where it began.
            delay(700)
            repository.playResonances(axis, freqStart, freqEnd, hzPerSec)
            recorder.await()
        } ?: return@withContext Result.failure(
            IllegalStateException("this phone would not give its accelerometer"),
        )
        val clean = ResonanceAnalysis.detrend(recording.samples, recording.sampleRateHz)
        val start = ResonanceAnalysis.motionStart(clean, recording.sampleRateHz)
            ?: return@withContext Result.failure(
                IllegalStateException(
                    "the machine was not heard moving: check it is homed, and that the axis " +
                        "chosen is the one that moved",
                ),
            )
        val movedAt = start / recording.sampleRateHz
        val curve = ResonanceAnalysis.response(
            samples = clean,
            sampleRateHz = recording.sampleRateHz,
            motionStartSeconds = movedAt,
            fStart = freqStart,
            fEnd = freqEnd,
            hzPerSec = hzPerSec,
        )
        if (curve.magnitudes.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("the sweep was too short to measure"),
            )
        }
        Result.success(
            KlipperResonanceMeasurement(
                axis = axis.uppercase(),
                sensorName = recording.sensorName,
                sampleRateHz = recording.sampleRateHz,
                movedAt = movedAt,
                curve = curve,
                peaks = curve.peaks(),
            ),
        )
    }
    internal suspend fun saveShapers(settings: List<KlipperConfigFile.ShaperSetting>): Boolean =
        repository.saveShapers(settings)

    // The bed mesh.
    fun calibrateMesh() = repository.calibrateMesh()
    fun meshProfile(action: String, name: String) = repository.meshProfile(action, name)

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
