package com.tomppi.enderslicer.octoprint

/** Pure merge used by the serialized fresh-state command preflight. */
internal object OctoPrintSafetyPreflight {
    fun merge(
        cached: OctoPrintUiState,
        job: OctoPrintJobState,
        connection: OctoPrintConnectionState,
        printer: OctoPrintPrinterState,
        refreshedAtEpochMillis: Long,
    ): OctoPrintUiState = cached.copy(
        job = job,
        connection = connection,
        printer = printer,
        lastUpdatedEpochMillis = refreshedAtEpochMillis,
        statusMessage = job.error
            ?: printer.text.takeIf(String::isNotBlank)
            ?: connection.state,
        errorMessage = null,
    )
}
