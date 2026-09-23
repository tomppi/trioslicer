package com.tomppi.enderslicer.engine

enum class LayerEventType {
    PAUSE,
    FILAMENT_CHANGE,
    NOZZLE_TEMPERATURE,
    BED_TEMPERATURE,
    FAN_SPEED,
    SPEED_FACTOR,
    FLOW_FACTOR,
    RETRACTION,
    PRESSURE_ADVANCE,
    JUNCTION_DEVIATION,
    CAMERA_TRIGGER,
    MESSAGE,
    CUSTOM_GCODE,
}

enum class LayerEventSource { USER }

data class LayerEvent(
    val id: String,
    val layerNumber: Int,
    val zMm: Float,
    val type: LayerEventType,
    val value: Double? = null,
    val secondaryValue: Double? = null,
    val text: String = "",
    val source: LayerEventSource = LayerEventSource.USER,
    val label: String = "",
) {
    init {
        require(id.isNotBlank()) { "Layer event id cannot be blank" }
        require(layerNumber in -100_000..1_000_000) { "Layer event number is outside the supported range" }
        require(zMm.isFinite()) { "Layer event Z height must be finite" }
        value?.let { require(it.isFinite()) { "Layer event value must be finite" } }
        secondaryValue?.let { require(it.isFinite()) { "Layer event secondary value must be finite" } }
    }

    fun displayName(): String = when (type) {
        LayerEventType.PAUSE -> "Pause print"
        LayerEventType.FILAMENT_CHANGE -> "Filament change"
        LayerEventType.NOZZLE_TEMPERATURE -> "Nozzle ${format(value)} °C"
        LayerEventType.BED_TEMPERATURE -> "Bed ${format(value)} °C"
        LayerEventType.FAN_SPEED -> "Fan ${format(value)}%"
        LayerEventType.SPEED_FACTOR -> "Speed ${format(value)}%"
        LayerEventType.FLOW_FACTOR -> "Flow ${format(value)}%"
        LayerEventType.RETRACTION -> "Retraction ${format(value)} mm"
        LayerEventType.PRESSURE_ADVANCE -> "Pressure advance K${format(value)}"
        LayerEventType.JUNCTION_DEVIATION -> "Junction deviation ${format(value)} mm"
        LayerEventType.CAMERA_TRIGGER -> "Camera trigger"
        LayerEventType.MESSAGE -> "Message: ${text.take(36)}"
        LayerEventType.CUSTOM_GCODE -> "Custom G-code"
    }

    private fun format(number: Double?): String = number
        ?.let { formatDecimal(it, 3) }
        ?: "?"
}
