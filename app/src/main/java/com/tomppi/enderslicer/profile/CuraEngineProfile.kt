package com.tomppi.enderslicer.profile

data class CuraEngineProfile(
    val globalValues: Map<String, String> = emptyMap(),
    val extruderValues: Map<String, String> = emptyMap(),
    val rawGlobalValues: Map<String, String> = globalValues,
    val rawExtruderValues: Map<String, String> = extruderValues,
    val definitionFiles: Map<String, String> = emptyMap(),
    val machineDefinitionFileName: String? = null,
    val extruderDefinitionFileName: String? = null,
    val unresolvedExpressions: List<String> = emptyList(),
) {
    val concreteSettingCount: Int
        get() = globalValues.size + extruderValues.size

    val usesProjectDefinitions: Boolean
        get() = machineDefinitionFileName != null &&
            extruderDefinitionFileName != null &&
            definitionFiles.containsKey(machineDefinitionFileName) &&
            definitionFiles.containsKey(extruderDefinitionFileName)
}
