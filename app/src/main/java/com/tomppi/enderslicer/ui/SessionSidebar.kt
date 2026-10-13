package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.model.ExtraSettingSpec
import com.tomppi.enderslicer.model.ExtraSettingValidation
import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.profile.AllSettingsGroups
import com.tomppi.enderslicer.profile.CuraSettingDelta

/**
 * The built-in rows of the plate's session sidebar, and the list of rows the user
 * has chosen for each engine.
 *
 * The sidebar used to be four fixed values. They are still the default - someone
 * who never opens the picker sees exactly the four they always did - but each
 * engine now keeps its own ordered list, and a row may be one of these four or any
 * setting of that engine's catalogue. The built-in ids carry an "@", which is not a
 * character an engine key can hold, so a chosen list can name both without either
 * shadowing the other.
 */
internal const val SIDEBAR_LAYER_HEIGHT = "@layer_height"
internal const val SIDEBAR_INFILL = "@infill"
internal const val SIDEBAR_SUPPORTS = "@supports"
internal const val SIDEBAR_ADHESION = "@adhesion"

/** The four rows the sidebar has always shown, in the order it showed them. */
val DEFAULT_SIDEBAR_SETTINGS: List<String> = listOf(
    SIDEBAR_LAYER_HEIGHT,
    SIDEBAR_INFILL,
    SIDEBAR_SUPPORTS,
    SIDEBAR_ADHESION,
)

/** True when [id] names one of the four built-in rows rather than an engine setting. */
internal fun isBuiltInSidebarRow(id: String): Boolean = id in DEFAULT_SIDEBAR_SETTINGS

/** What the picker calls a built-in row, which is the label its own editor shows. */
internal fun builtInSidebarLabel(id: String): String = when (id) {
    SIDEBAR_LAYER_HEIGHT -> "Layer height"
    SIDEBAR_INFILL -> "Infill density"
    SIDEBAR_SUPPORTS -> "Supports"
    SIDEBAR_ADHESION -> "Adhesion"
    else -> id
}

/**
 * The sidebar's rows per engine: the settings differ between engines, so each
 * keeps its own list, and switching engine switches the sidebar with it.
 */
data class SidebarSettings(
    val cura: List<String> = DEFAULT_SIDEBAR_SETTINGS,
    val prusa: List<String> = DEFAULT_SIDEBAR_SETTINGS,
    val orca: List<String> = DEFAULT_SIDEBAR_SETTINGS,
) {
    fun forEngine(engine: SlicerEngine): List<String> = when (engine) {
        SlicerEngine.CURA -> cura
        SlicerEngine.PRUSA -> prusa
        SlicerEngine.ORCA -> orca
    }

    fun withRows(engine: SlicerEngine, rows: List<String>): SidebarSettings = when (engine) {
        SlicerEngine.CURA -> copy(cura = rows)
        SlicerEngine.PRUSA -> copy(prusa = rows)
        SlicerEngine.ORCA -> copy(orca = rows)
    }
}

/**
 * A stored list as it can honestly be used: lowercased like the engine keys are,
 * duplicates dropped, and anything that is neither a built-in row nor a setting
 * name the engine could take dropped with them. A list edited by hand, or written
 * by a build that knew other rows, restores as the rows that still mean something
 * rather than as a sidebar with holes in it.
 */
internal fun sanitizeSidebarRows(rows: List<String>): List<String> = rows
    .map { it.trim().lowercase() }
    .filter { isBuiltInSidebarRow(it) || ExtraSettingValidation.isValidKey(it) }
    .distinct()

/** One row of the sidebar: the id the user chose, and the value and editors it drew. */
internal data class SessionSidebarRow(val id: String, val value: SessionValue)

/** The test tag a sidebar row's tile carries, suffixed with the row's own id. */
internal const val SESSION_SIDEBAR_ROW_TAG_PREFIX = "session-sidebar-row:"

/**
 * The sidebar's rows for the engine that would slice, in the user's own order.
 *
 * A built-in id takes its value from [sessionValues], which is the hand-written
 * editor it has always had; anything else is built from that engine's catalogue
 * entry for the key, through the same metadata the settings screens use. A row
 * whose catalogue entry is gone, or that has no editor, is left out rather than
 * drawn dead - the picker still lists it, so it can be taken off.
 *
 * [values] is [sidebarEffectiveValues], which the caller remembers: the rail
 * recomposes on every state change and the map is built from the whole catalogue.
 */
internal fun sessionSidebarRows(
    engine: SlicerEngine,
    summary: PlateSessionSummary,
    state: MainUiState,
    chosen: List<String>,
    specs: List<ExtraSettingSpec>,
    values: Map<String, String>,
    onSettings: (String, (SlicerSettings) -> SlicerSettings) -> Unit,
    onPrusaSettings: (String, (PrusaSliceSettings) -> PrusaSliceSettings) -> Unit,
    onOrcaSettings: (String, (OrcaSliceSettings) -> OrcaSliceSettings) -> Unit,
    onExtraSetting: (String, String) -> Unit,
): List<SessionSidebarRow> {
    val builtIn = sessionValues(engine, summary, state, onSettings, onPrusaSettings, onOrcaSettings)
    val byKey = specs.associateBy { it.key.lowercase() }
    return chosen.mapNotNull { id ->
        when (id) {
            SIDEBAR_LAYER_HEIGHT -> SessionSidebarRow(id, builtIn.layerHeight)
            SIDEBAR_INFILL -> SessionSidebarRow(id, builtIn.infill)
            SIDEBAR_SUPPORTS -> SessionSidebarRow(id, builtIn.supports)
            SIDEBAR_ADHESION -> SessionSidebarRow(id, builtIn.adhesion)
            else -> {
                val spec = byKey[id] ?: return@mapNotNull null
                val current = values[spec.key] ?: values[spec.key.lowercase()]
                val value = catalogueSidebarValue(spec, current) { next -> onExtraSetting(spec.key, next) }
                value?.let { SessionSidebarRow(id, it) }
            }
        }
    }
}

/**
 * What the engine will actually be given for every catalogue key: the app's own
 * resolved values where it has them, overlaid by the added settings - the same map
 * the All-settings sheet judges Cura's expressions against, so a sidebar row and
 * that sheet never disagree about a key's value.
 */
internal fun sidebarEffectiveValues(
    engine: SlicerEngine,
    specs: List<ExtraSettingSpec>,
    state: MainUiState,
): Map<String, String> = when (engine) {
    SlicerEngine.CURA -> AllSettingsGroups.effectiveValues(
        specs = specs,
        appValues = CuraSettingDelta.standaloneValues(state.settings),
        extras = state.extraCuraSettings,
    )

    SlicerEngine.PRUSA -> AllSettingsGroups.effectiveValues(specs, emptyMap(), state.extraPrusaSettings)
    SlicerEngine.ORCA -> AllSettingsGroups.effectiveValues(specs, emptyMap(), state.extraOrcaSettings)
}

/**
 * Why [spec] cannot have a sidebar row, or null when it can.
 *
 * A sidebar row is an editor: a slider for a number or a two-way choice for a
 * switch. The catalogue's text settings - G-code snippets, vectors, per-extruder
 * lists - have no such editor, and a row that opened onto a dead field would be
 * worse than not offering it. The picker says this sentence instead.
 */
internal fun sidebarEditorRefusal(spec: ExtraSettingSpec): String? = when {
    spec.boolean || spec.numeric -> null
    else -> "no sidebar editor: this is a text setting, not a number or a switch"
}

/**
 * One catalogue setting as a sidebar row: a switch for a boolean, a slider for a
 * number, and nothing for anything else. The editor writes the engine's own key
 * through the same added-settings path the All-settings sheet uses, so a key both
 * screens offer is written to one place and read back from it, and the value shown
 * is what the engine would really be given for it.
 */
internal fun catalogueSidebarValue(
    spec: ExtraSettingSpec,
    current: String?,
    set: (String) -> Unit,
): SessionValue? {
    val shown = current?.takeIf { it.isNotBlank() } ?: spec.defaultValue.orEmpty()
    val label = spec.label.ifBlank { spec.key }
    if (spec.boolean) {
        val on = shown.isTrue()
        return SessionValue(
            label = label,
            value = if (on) "On" else "Off",
            detail = spec.key,
            editors = listOf(
                SessionValueEditor(
                    section = "",
                    editor = SessionEditor.Choice(
                        options = listOf("Off", "On"),
                        selectedIndex = if (on) 1 else 0,
                        // The same "true"/"false" the curated switches write: the
                        // engine reads a switch's own spelling and silently treats
                        // every other one as false.
                        set = { index -> set(if (index == 1) "true" else "false") },
                    ),
                ),
            ),
        )
    }
    if (!spec.numeric) return null
    val value = shown.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
    val decimals = if (shown.contains('.') || value % 1.0 != 0.0) 2 else 0
    return SessionValue(
        label = label,
        value = numberText(value, decimals),
        detail = spec.key,
        editors = listOf(
            SessionValueEditor(
                section = "",
                editor = SessionEditor.Number(
                    value = value,
                    range = sidebarNumberRange(spec, value),
                    step = if (decimals == 0) 1.0 else 0.05,
                    decimals = decimals,
                    unit = "",
                    set = { next -> set(numberText(next, decimals)) },
                ),
            ),
        ),
    )
}

/**
 * The band a catalogue number's slider covers.
 *
 * The catalogue's own minimum and maximum when it declares them; otherwise a band
 * around the value in force, wide enough to move in both directions - a range the
 * catalogue does not state is not a range the editor may invent narrowly. The
 * value itself is always inside the band, so opening the editor never drags a
 * setting somewhere else.
 */
internal fun sidebarNumberRange(
    spec: ExtraSettingSpec,
    value: Double,
): ClosedFloatingPointRange<Float> {
    val minimum = spec.minimum ?: minOf(0.0, value)
    val maximum = spec.maximum ?: maxOf(value * 2.0, value + 10.0)
    return minOf(minimum, value).toFloat()..maxOf(maximum, value).toFloat()
}

/** The spellings the engines read as true; everything else is false to them too. */
private fun String.isTrue(): Boolean = trim().lowercase() in setOf("true", "on", "yes", "1")
