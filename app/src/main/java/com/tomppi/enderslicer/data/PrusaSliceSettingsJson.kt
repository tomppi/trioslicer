package com.tomppi.enderslicer.data

import com.tomppi.enderslicer.model.PrusaSliceSettings
import org.json.JSONObject

/** JSON serialization for [PrusaSliceSettings]; additive and forgiving on read. */
object PrusaSliceSettingsJson {

    fun serialize(settings: PrusaSliceSettings): String =
        JSONObject()
            .put("layerHeightMm", settings.layerHeightMm)
            .put("firstLayerHeightMm", settings.firstLayerHeightMm)
            .put("perimeters", settings.perimeters)
            .put("topSolidLayers", settings.topSolidLayers)
            .put("bottomSolidLayers", settings.bottomSolidLayers)
            .put("thinWalls", settings.thinWalls)
            .put("externalPerimetersFirst", settings.externalPerimetersFirst)
            .put("fillDensityPercent", settings.fillDensityPercent)
            .put("fillPattern", settings.fillPattern)
            .put("skirtLoops", settings.skirtLoops)
            .put("skirtHeightLayers", settings.skirtHeightLayers)
            .put("skirtDistanceMm", settings.skirtDistanceMm)
            .put("brimWidthMm", settings.brimWidthMm)
            .put("overhangs", settings.overhangs)
            .put("firstLayerExtrusionWidthMm", settings.firstLayerExtrusionWidthMm)
            .put("perimeterExtrusionWidthMm", settings.perimeterExtrusionWidthMm)
            .put("externalPerimeterExtrusionWidthMm", settings.externalPerimeterExtrusionWidthMm)
            .put("infillExtrusionWidthMm", settings.infillExtrusionWidthMm)
            .put("solidInfillExtrusionWidthMm", settings.solidInfillExtrusionWidthMm)
            .put("topInfillExtrusionWidthMm", settings.topInfillExtrusionWidthMm)
            .put("extraKeys", JSONObject(settings.extraKeys))
            .put("supportMaterial", settings.supportMaterial)
            .put("supportThresholdAngleDegrees", settings.supportThresholdAngleDegrees)
            .put("supportPattern", settings.supportPattern)
            .put("supportInterface", settings.supportInterface)
            .put("supportInterfaceLayers", settings.supportInterfaceLayers)
            .put("printSpeedMmPerSecond", settings.printSpeedMmPerSecond)
            .put("externalPerimeterSpeedMmPerSecond", settings.externalPerimeterSpeedMmPerSecond)
            .put("infillSpeedMmPerSecond", settings.infillSpeedMmPerSecond)
            .put("firstLayerSpeedMmPerSecond", settings.firstLayerSpeedMmPerSecond)
            .put("travelSpeedMmPerSecond", settings.travelSpeedMmPerSecond)
            .put("nozzleTemperatureC", settings.nozzleTemperatureC)
            .put("firstLayerTemperatureC", settings.firstLayerTemperatureC)
            .put("bedTemperatureC", settings.bedTemperatureC)
            .put("firstLayerBedTemperatureC", settings.firstLayerBedTemperatureC)
            .put("fanSpeedPercent", settings.fanSpeedPercent)
            .put("retractionLengthMm", settings.retractionLengthMm)
            .put("retractionSpeedMmPerSecond", settings.retractionSpeedMmPerSecond)
            .put("retractionMinTravelMm", settings.retractionMinTravelMm)
            .put("retractLiftMm", settings.retractLiftMm)
            .put("useFirmwareRetraction", settings.useFirmwareRetraction)
            .put("extrusionMultiplierPercent", settings.extrusionMultiplierPercent)
            .toString()

    fun deserialize(encoded: String): PrusaSliceSettings? = runCatching {
        val json = JSONObject(encoded)
        val base = PrusaSliceSettings()
        base.copy(
            layerHeightMm = json.optDouble("layerHeightMm", base.layerHeightMm),
            firstLayerHeightMm = json.optDouble("firstLayerHeightMm", base.firstLayerHeightMm),
            perimeters = json.optInt("perimeters", base.perimeters),
            topSolidLayers = json.optInt("topSolidLayers", base.topSolidLayers),
            bottomSolidLayers = json.optInt("bottomSolidLayers", base.bottomSolidLayers),
            thinWalls = json.optBoolean("thinWalls", base.thinWalls),
            externalPerimetersFirst = json.optBoolean("externalPerimetersFirst", base.externalPerimetersFirst),
            fillDensityPercent = json.optDouble("fillDensityPercent", base.fillDensityPercent),
            fillPattern = json.optString("fillPattern", base.fillPattern),
            skirtLoops = json.optInt("skirtLoops", base.skirtLoops),
            skirtHeightLayers = json.optInt("skirtHeightLayers", base.skirtHeightLayers),
            skirtDistanceMm = json.optDouble("skirtDistanceMm", base.skirtDistanceMm),
            brimWidthMm = json.optDouble("brimWidthMm", base.brimWidthMm),
            overhangs = json.optBoolean("overhangs", base.overhangs),
            firstLayerExtrusionWidthMm = optNullable(json, "firstLayerExtrusionWidthMm"),
            perimeterExtrusionWidthMm = optNullable(json, "perimeterExtrusionWidthMm"),
            externalPerimeterExtrusionWidthMm = optNullable(json, "externalPerimeterExtrusionWidthMm"),
            infillExtrusionWidthMm = optNullable(json, "infillExtrusionWidthMm"),
            solidInfillExtrusionWidthMm = optNullable(json, "solidInfillExtrusionWidthMm"),
            topInfillExtrusionWidthMm = optNullable(json, "topInfillExtrusionWidthMm"),
            extraKeys = parseExtraKeys(json.optJSONObject("extraKeys")),
            supportMaterial = json.optBoolean("supportMaterial", base.supportMaterial),
            supportThresholdAngleDegrees = json.optDouble("supportThresholdAngleDegrees", base.supportThresholdAngleDegrees),
            supportPattern = json.optString("supportPattern", base.supportPattern),
            supportInterface = json.optBoolean("supportInterface", base.supportInterface),
            supportInterfaceLayers = json.optInt("supportInterfaceLayers", base.supportInterfaceLayers),
            printSpeedMmPerSecond = json.optDouble("printSpeedMmPerSecond", base.printSpeedMmPerSecond),
            externalPerimeterSpeedMmPerSecond = json.optDouble("externalPerimeterSpeedMmPerSecond", base.externalPerimeterSpeedMmPerSecond),
            infillSpeedMmPerSecond = json.optDouble("infillSpeedMmPerSecond", base.infillSpeedMmPerSecond),
            firstLayerSpeedMmPerSecond = json.optDouble("firstLayerSpeedMmPerSecond", base.firstLayerSpeedMmPerSecond),
            travelSpeedMmPerSecond = json.optDouble("travelSpeedMmPerSecond", base.travelSpeedMmPerSecond),
            nozzleTemperatureC = json.optInt("nozzleTemperatureC", base.nozzleTemperatureC),
            firstLayerTemperatureC = json.optInt("firstLayerTemperatureC", base.firstLayerTemperatureC),
            bedTemperatureC = json.optInt("bedTemperatureC", base.bedTemperatureC),
            firstLayerBedTemperatureC = json.optInt("firstLayerBedTemperatureC", base.firstLayerBedTemperatureC),
            fanSpeedPercent = json.optInt("fanSpeedPercent", base.fanSpeedPercent),
            retractionLengthMm = json.optDouble("retractionLengthMm", base.retractionLengthMm),
            retractionSpeedMmPerSecond = json.optDouble("retractionSpeedMmPerSecond", base.retractionSpeedMmPerSecond),
            retractionMinTravelMm = json.optDouble("retractionMinTravelMm", base.retractionMinTravelMm),
            retractLiftMm = json.optDouble("retractLiftMm", base.retractLiftMm),
            useFirmwareRetraction = json.optBoolean("useFirmwareRetraction", base.useFirmwareRetraction),
            extrusionMultiplierPercent = json.optDouble("extrusionMultiplierPercent", base.extrusionMultiplierPercent),
        )
    }.getOrNull()

    /**
     * Applies [values] to [base]: a key the document carries replaces the base value, a key it
     * omits keeps the base value, and an explicit null on one of the nullable extrusion widths
     * restores "automatic" (PrusaSlicer decides). Catalog keys are never part of a preset.
     */
    fun mergeValues(base: PrusaSliceSettings, values: JSONObject): PrusaSliceSettings = base.copy(
        layerHeightMm = values.optDouble("layerHeightMm", base.layerHeightMm),
        firstLayerHeightMm = values.optDouble("firstLayerHeightMm", base.firstLayerHeightMm),
        perimeters = values.optInt("perimeters", base.perimeters),
        topSolidLayers = values.optInt("topSolidLayers", base.topSolidLayers),
        bottomSolidLayers = values.optInt("bottomSolidLayers", base.bottomSolidLayers),
        thinWalls = values.optBoolean("thinWalls", base.thinWalls),
        externalPerimetersFirst = values.optBoolean("externalPerimetersFirst", base.externalPerimetersFirst),
        fillDensityPercent = values.optDouble("fillDensityPercent", base.fillDensityPercent),
        fillPattern = values.optString("fillPattern", base.fillPattern),
        skirtLoops = values.optInt("skirtLoops", base.skirtLoops),
        skirtHeightLayers = values.optInt("skirtHeightLayers", base.skirtHeightLayers),
        skirtDistanceMm = values.optDouble("skirtDistanceMm", base.skirtDistanceMm),
        brimWidthMm = values.optDouble("brimWidthMm", base.brimWidthMm),
        overhangs = values.optBoolean("overhangs", base.overhangs),
        firstLayerExtrusionWidthMm = mergedNullable(values, "firstLayerExtrusionWidthMm", base.firstLayerExtrusionWidthMm),
        perimeterExtrusionWidthMm = mergedNullable(values, "perimeterExtrusionWidthMm", base.perimeterExtrusionWidthMm),
        externalPerimeterExtrusionWidthMm = mergedNullable(values, "externalPerimeterExtrusionWidthMm", base.externalPerimeterExtrusionWidthMm),
        infillExtrusionWidthMm = mergedNullable(values, "infillExtrusionWidthMm", base.infillExtrusionWidthMm),
        solidInfillExtrusionWidthMm = mergedNullable(values, "solidInfillExtrusionWidthMm", base.solidInfillExtrusionWidthMm),
        topInfillExtrusionWidthMm = mergedNullable(values, "topInfillExtrusionWidthMm", base.topInfillExtrusionWidthMm),
        supportMaterial = values.optBoolean("supportMaterial", base.supportMaterial),
        supportThresholdAngleDegrees = values.optDouble("supportThresholdAngleDegrees", base.supportThresholdAngleDegrees),
        supportPattern = values.optString("supportPattern", base.supportPattern),
        supportInterface = values.optBoolean("supportInterface", base.supportInterface),
        supportInterfaceLayers = values.optInt("supportInterfaceLayers", base.supportInterfaceLayers),
        printSpeedMmPerSecond = values.optDouble("printSpeedMmPerSecond", base.printSpeedMmPerSecond),
        externalPerimeterSpeedMmPerSecond = values.optDouble("externalPerimeterSpeedMmPerSecond", base.externalPerimeterSpeedMmPerSecond),
        infillSpeedMmPerSecond = values.optDouble("infillSpeedMmPerSecond", base.infillSpeedMmPerSecond),
        firstLayerSpeedMmPerSecond = values.optDouble("firstLayerSpeedMmPerSecond", base.firstLayerSpeedMmPerSecond),
        travelSpeedMmPerSecond = values.optDouble("travelSpeedMmPerSecond", base.travelSpeedMmPerSecond),
        nozzleTemperatureC = values.optInt("nozzleTemperatureC", base.nozzleTemperatureC),
        firstLayerTemperatureC = values.optInt("firstLayerTemperatureC", base.firstLayerTemperatureC),
        bedTemperatureC = values.optInt("bedTemperatureC", base.bedTemperatureC),
        firstLayerBedTemperatureC = values.optInt("firstLayerBedTemperatureC", base.firstLayerBedTemperatureC),
        fanSpeedPercent = values.optInt("fanSpeedPercent", base.fanSpeedPercent),
        retractionLengthMm = values.optDouble("retractionLengthMm", base.retractionLengthMm),
        retractionSpeedMmPerSecond = values.optDouble("retractionSpeedMmPerSecond", base.retractionSpeedMmPerSecond),
        retractionMinTravelMm = values.optDouble("retractionMinTravelMm", base.retractionMinTravelMm),
        retractLiftMm = values.optDouble("retractLiftMm", base.retractLiftMm),
        useFirmwareRetraction = values.optBoolean("useFirmwareRetraction", base.useFirmwareRetraction),
        extrusionMultiplierPercent = values.optDouble("extrusionMultiplierPercent", base.extrusionMultiplierPercent),
    )

    private fun mergedNullable(json: JSONObject, key: String, base: Double?): Double? = when {
        !json.has(key) -> base
        json.isNull(key) -> null
        else -> json.optDouble(key, Double.NaN).takeIf { it.isFinite() } ?: base
    }

    private fun optNullable(json: JSONObject, key: String): Double? =
        if (json.has(key) && !json.isNull(key)) json.optDouble(key, Double.NaN).takeIf { it.isFinite() } else null

    private fun parseExtraKeys(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = json.optString(key, "")
        }
        return result
    }
}
