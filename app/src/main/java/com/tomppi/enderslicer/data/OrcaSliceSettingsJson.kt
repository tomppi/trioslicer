package com.tomppi.enderslicer.data

import com.tomppi.enderslicer.model.OrcaSliceSettings
import org.json.JSONObject

/**
 * JSON serialization for [OrcaSliceSettings]; additive and forgiving on read.
 *
 * A field added later reads as its default on an older document, and an unreadable document
 * falls back to the defaults, so a stale snapshot can never stop the app from starting.
 */
object OrcaSliceSettingsJson {

    fun serialize(settings: OrcaSliceSettings): String =
        JSONObject()
            .put("printerPreset", settings.printerPreset)
            .put("processPreset", settings.processPreset)
            .put("filamentPreset", settings.filamentPreset)
            .put("layerHeightMm", settings.layerHeightMm)
            .put("firstLayerHeightMm", settings.firstLayerHeightMm)
            .put("wallLoops", settings.wallLoops)
            .put("topShellLayers", settings.topShellLayers)
            .put("bottomShellLayers", settings.bottomShellLayers)
            .put("sparseInfillDensityPercent", settings.sparseInfillDensityPercent)
            .put("sparseInfillPattern", settings.sparseInfillPattern)
            .put("skirtLoops", settings.skirtLoops)
            .put("brimWidthMm", settings.brimWidthMm)
            .put("supportEnabled", settings.supportEnabled)
            .put("supportThresholdAngleDegrees", settings.supportThresholdAngleDegrees)
            .put("supportBasePattern", settings.supportBasePattern)
            .put("supportInterfaceTopLayers", settings.supportInterfaceTopLayers)
            .put("innerWallSpeedMmPerSecond", settings.innerWallSpeedMmPerSecond)
            .put("outerWallSpeedMmPerSecond", settings.outerWallSpeedMmPerSecond)
            .put("initialLayerSpeedMmPerSecond", settings.initialLayerSpeedMmPerSecond)
            .put("sparseInfillSpeedMmPerSecond", settings.sparseInfillSpeedMmPerSecond)
            .put("internalSolidInfillSpeedMmPerSecond", settings.internalSolidInfillSpeedMmPerSecond)
            .put("travelSpeedMmPerSecond", settings.travelSpeedMmPerSecond)
            .put("nozzleTemperatureC", settings.nozzleTemperatureC)
            .put("initialLayerNozzleTemperatureC", settings.initialLayerNozzleTemperatureC)
            .put("hotPlateTemperatureC", settings.hotPlateTemperatureC)
            .put("initialLayerHotPlateTemperatureC", settings.initialLayerHotPlateTemperatureC)
            .put("fanMaxSpeedPercent", settings.fanMaxSpeedPercent)
            .put("fanMinSpeedPercent", settings.fanMinSpeedPercent)
            .put("filamentType", settings.filamentType)
            .put("filamentFlowRatioPercent", settings.filamentFlowRatioPercent)
            .put("retractionLengthMm", settings.retractionLengthMm)
            .put("retractionSpeedMmPerSecond", settings.retractionSpeedMmPerSecond)
            .put("zHopMm", settings.zHopMm)
            .put("useFirmwareRetraction", settings.useFirmwareRetraction)
            .put("extraKeys", JSONObject(settings.extraKeys))
            .toString()

    fun deserialize(encoded: String): OrcaSliceSettings? = runCatching {
        val json = JSONObject(encoded)
        val base = OrcaSliceSettings()
        base.copy(
            printerPreset = json.optString("printerPreset", base.printerPreset),
            processPreset = json.optString("processPreset", base.processPreset),
            filamentPreset = json.optString("filamentPreset", base.filamentPreset),
            layerHeightMm = json.optDouble("layerHeightMm", base.layerHeightMm),
            firstLayerHeightMm = json.optDouble("firstLayerHeightMm", base.firstLayerHeightMm),
            wallLoops = json.optInt("wallLoops", base.wallLoops),
            topShellLayers = json.optInt("topShellLayers", base.topShellLayers),
            bottomShellLayers = json.optInt("bottomShellLayers", base.bottomShellLayers),
            sparseInfillDensityPercent = json.optDouble("sparseInfillDensityPercent", base.sparseInfillDensityPercent),
            sparseInfillPattern = json.optString("sparseInfillPattern", base.sparseInfillPattern),
            skirtLoops = json.optInt("skirtLoops", base.skirtLoops),
            brimWidthMm = json.optDouble("brimWidthMm", base.brimWidthMm),
            supportEnabled = json.optBoolean("supportEnabled", base.supportEnabled),
            supportThresholdAngleDegrees = json.optInt("supportThresholdAngleDegrees", base.supportThresholdAngleDegrees),
            supportBasePattern = json.optString("supportBasePattern", base.supportBasePattern),
            supportInterfaceTopLayers = json.optInt("supportInterfaceTopLayers", base.supportInterfaceTopLayers),
            innerWallSpeedMmPerSecond = json.optDouble("innerWallSpeedMmPerSecond", base.innerWallSpeedMmPerSecond),
            outerWallSpeedMmPerSecond = json.optDouble("outerWallSpeedMmPerSecond", base.outerWallSpeedMmPerSecond),
            initialLayerSpeedMmPerSecond = json.optDouble("initialLayerSpeedMmPerSecond", base.initialLayerSpeedMmPerSecond),
            sparseInfillSpeedMmPerSecond = json.optDouble("sparseInfillSpeedMmPerSecond", base.sparseInfillSpeedMmPerSecond),
            internalSolidInfillSpeedMmPerSecond = json.optDouble("internalSolidInfillSpeedMmPerSecond", base.internalSolidInfillSpeedMmPerSecond),
            travelSpeedMmPerSecond = json.optDouble("travelSpeedMmPerSecond", base.travelSpeedMmPerSecond),
            nozzleTemperatureC = json.optInt("nozzleTemperatureC", base.nozzleTemperatureC),
            initialLayerNozzleTemperatureC = json.optInt("initialLayerNozzleTemperatureC", base.initialLayerNozzleTemperatureC),
            hotPlateTemperatureC = json.optInt("hotPlateTemperatureC", base.hotPlateTemperatureC),
            initialLayerHotPlateTemperatureC = json.optInt("initialLayerHotPlateTemperatureC", base.initialLayerHotPlateTemperatureC),
            fanMaxSpeedPercent = json.optInt("fanMaxSpeedPercent", base.fanMaxSpeedPercent),
            fanMinSpeedPercent = json.optInt("fanMinSpeedPercent", base.fanMinSpeedPercent),
            filamentType = json.optString("filamentType", base.filamentType),
            filamentFlowRatioPercent = json.optDouble("filamentFlowRatioPercent", base.filamentFlowRatioPercent),
            retractionLengthMm = json.optDouble("retractionLengthMm", base.retractionLengthMm),
            retractionSpeedMmPerSecond = json.optDouble("retractionSpeedMmPerSecond", base.retractionSpeedMmPerSecond),
            zHopMm = json.optDouble("zHopMm", base.zHopMm),
            useFirmwareRetraction = json.optBoolean("useFirmwareRetraction", base.useFirmwareRetraction),
            extraKeys = json.optJSONObject("extraKeys")?.let { parseStringMap(it) } ?: base.extraKeys,
        )
    }.getOrNull()

    /**
     * Applies [values] to [base]: a key the document carries replaces the base value and a key it
     * omits keeps the base value. The vendor profile selection and the catalog keys are never part
     * of a preset, so they always keep the base value.
     */
    fun mergeValues(base: OrcaSliceSettings, values: JSONObject): OrcaSliceSettings = base.copy(
        layerHeightMm = values.optDouble("layerHeightMm", base.layerHeightMm),
        firstLayerHeightMm = values.optDouble("firstLayerHeightMm", base.firstLayerHeightMm),
        wallLoops = values.optInt("wallLoops", base.wallLoops),
        topShellLayers = values.optInt("topShellLayers", base.topShellLayers),
        bottomShellLayers = values.optInt("bottomShellLayers", base.bottomShellLayers),
        sparseInfillDensityPercent = values.optDouble("sparseInfillDensityPercent", base.sparseInfillDensityPercent),
        sparseInfillPattern = values.optString("sparseInfillPattern", base.sparseInfillPattern),
        skirtLoops = values.optInt("skirtLoops", base.skirtLoops),
        brimWidthMm = values.optDouble("brimWidthMm", base.brimWidthMm),
        supportEnabled = values.optBoolean("supportEnabled", base.supportEnabled),
        supportThresholdAngleDegrees = values.optInt("supportThresholdAngleDegrees", base.supportThresholdAngleDegrees),
        supportBasePattern = values.optString("supportBasePattern", base.supportBasePattern),
        supportInterfaceTopLayers = values.optInt("supportInterfaceTopLayers", base.supportInterfaceTopLayers),
        innerWallSpeedMmPerSecond = values.optDouble("innerWallSpeedMmPerSecond", base.innerWallSpeedMmPerSecond),
        outerWallSpeedMmPerSecond = values.optDouble("outerWallSpeedMmPerSecond", base.outerWallSpeedMmPerSecond),
        initialLayerSpeedMmPerSecond = values.optDouble("initialLayerSpeedMmPerSecond", base.initialLayerSpeedMmPerSecond),
        sparseInfillSpeedMmPerSecond = values.optDouble("sparseInfillSpeedMmPerSecond", base.sparseInfillSpeedMmPerSecond),
        internalSolidInfillSpeedMmPerSecond = values.optDouble("internalSolidInfillSpeedMmPerSecond", base.internalSolidInfillSpeedMmPerSecond),
        travelSpeedMmPerSecond = values.optDouble("travelSpeedMmPerSecond", base.travelSpeedMmPerSecond),
        nozzleTemperatureC = values.optInt("nozzleTemperatureC", base.nozzleTemperatureC),
        initialLayerNozzleTemperatureC = values.optInt("initialLayerNozzleTemperatureC", base.initialLayerNozzleTemperatureC),
        hotPlateTemperatureC = values.optInt("hotPlateTemperatureC", base.hotPlateTemperatureC),
        initialLayerHotPlateTemperatureC = values.optInt("initialLayerHotPlateTemperatureC", base.initialLayerHotPlateTemperatureC),
        fanMaxSpeedPercent = values.optInt("fanMaxSpeedPercent", base.fanMaxSpeedPercent),
        fanMinSpeedPercent = values.optInt("fanMinSpeedPercent", base.fanMinSpeedPercent),
        filamentType = values.optString("filamentType", base.filamentType),
        filamentFlowRatioPercent = values.optDouble("filamentFlowRatioPercent", base.filamentFlowRatioPercent),
        retractionLengthMm = values.optDouble("retractionLengthMm", base.retractionLengthMm),
        retractionSpeedMmPerSecond = values.optDouble("retractionSpeedMmPerSecond", base.retractionSpeedMmPerSecond),
        zHopMm = values.optDouble("zHopMm", base.zHopMm),
        useFirmwareRetraction = values.optBoolean("useFirmwareRetraction", base.useFirmwareRetraction),
    )

    private fun parseStringMap(source: JSONObject): Map<String, String> = buildMap {
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, source.optString(key))
        }
    }
}
