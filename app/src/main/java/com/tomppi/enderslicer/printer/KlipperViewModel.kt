package com.tomppi.enderslicer.printer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tomppi.enderslicer.nativebridge.KlipperEngineService
import kotlinx.coroutines.flow.StateFlow

/**
 * The printer front end's view model: what the machine this device is driving is
 * doing, and the actions a user takes on it.
 *
 * Watching starts with the view model, so opening the screen is what connects the UI
 * to the host - and the host itself is started by the USB attach intent, or by the
 * button here when it is not.
 */
class KlipperViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = KlipperPrinterRepository(application, viewModelScope)
    val state: StateFlow<KlipperPrinterState> = repository.state

    init {
        repository.start()
    }

    fun startHost() = KlipperEngineService.start(getApplication())
    fun home() = repository.home()
    fun setExtruderTemperature(celsius: Int) = repository.setExtruderTemperature(celsius)
    fun setBedTemperature(celsius: Int) = repository.setBedTemperature(celsius)
    fun firmwareRestart() = repository.firmwareRestart()

    override fun onCleared() {
        repository.stop()
    }
}
