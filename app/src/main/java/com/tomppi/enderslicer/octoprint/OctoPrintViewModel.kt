package com.tomppi.enderslicer.octoprint

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow

class OctoPrintViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = OctoPrintRepository(application, viewModelScope)
    val state: StateFlow<OctoPrintUiState> = repository.state

    fun testConnection(baseUrl: String, apiKey: String?) = repository.testConnection(baseUrl, apiKey)

    fun saveManualConfiguration(
        baseUrl: String,
        username: String,
        apiKey: String,
        snapshotUrlOverride: String,
        pollIntervalSeconds: Int,
    ) = repository.saveManualConfiguration(baseUrl, username, apiKey, snapshotUrlOverride, pollIntervalSeconds)

    fun beginApplicationAuthorization(
        baseUrl: String,
        username: String,
        snapshotUrlOverride: String,
        pollIntervalSeconds: Int,
    ) = repository.beginApplicationAuthorization(baseUrl, username, snapshotUrlOverride, pollIntervalSeconds)

    fun reopenAuthorizationDialog() = repository.reopenAuthorizationDialog()
    fun cancelAuthorization() = repository.cancelAuthorization()
    fun clearConfiguration() = repository.clearConfiguration()
    fun refresh() = repository.refresh()
    fun refreshFiles() = repository.refreshFiles()

    fun uploadGcode(
        localPath: String?,
        suggestedName: String,
        remoteDirectory: String,
        action: OctoPrintUploadAction,
    ) = repository.uploadGcode(localPath, suggestedName, remoteDirectory, action)

    fun selectFile(path: String, print: Boolean) = repository.selectFile(path, print)
    fun deleteFile(path: String) = repository.deleteFile(path)
    fun createFolder(parentPath: String, name: String) = repository.createFolder(parentPath, name)
    fun moveFile(path: String, destination: String) = repository.moveFile(path, destination)
    fun copyFile(path: String, destination: String) = repository.copyFile(path, destination)

    fun startJob() = repository.startJob()
    fun pauseJob() = repository.pauseJob()
    fun resumeJob() = repository.resumeJob()
    fun cancelJob() = repository.cancelJob()
    fun restartJob() = repository.restartJob()

    fun connect(
        port: String?,
        baudrate: Int?,
        printerProfile: String?,
        save: Boolean,
        autoConnect: Boolean,
    ) = repository.connect(port, baudrate, printerProfile, save, autoConnect)

    fun disconnect() = repository.disconnect()
    fun jog(x: Double? = null, y: Double? = null, z: Double? = null) = repository.jog(x, y, z)
    fun home(axes: Set<String>) = repository.home(axes)
    fun setToolTemperature(tool: String, target: Int) = repository.setToolTemperature(tool, target)
    fun setBedTemperature(target: Int) = repository.setBedTemperature(target)
    fun extrude(amountMm: Double) = repository.extrude(amountMm)
    fun setFeedRate(percent: Int) = repository.setFeedRate(percent)
    fun setFlowRate(percent: Int) = repository.setFlowRate(percent)
    fun sendGcode(command: String) = repository.sendGcode(command)
    fun setWebcamVisible(visible: Boolean) = repository.setWebcamVisible(visible)
    fun saveSnapshotOverride(value: String) = repository.saveSnapshotOverride(value)

    override fun onCleared() {
        repository.setWebcamVisible(false)
        super.onCleared()
    }
}
