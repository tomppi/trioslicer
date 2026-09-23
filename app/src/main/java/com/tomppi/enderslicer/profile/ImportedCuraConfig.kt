package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings

data class ImportedCuraConfig(
    val name: String,
    val source: String,
    val curaVersion: String? = null,
    val settingVersion: String? = null,
    val rawValues: Map<String, String>,
    val mappedSettings: SlicerSettings,
    val startGcode: String? = null,
    val endGcode: String? = null,
    val engineProfile: CuraEngineProfile? = null,
    val warnings: List<String> = emptyList(),
)
