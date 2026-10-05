package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.ExtraSettingSpec

/** A setting Cura says is not in effect, with the conditions it is waiting for. */
data class InactiveSetting(val spec: ExtraSettingSpec, val reason: String)

/**
 * The All-settings list, split by whether each entry is in effect.
 *
 * Only Cura can answer this. Its definitions carry an `enabled` expression per setting; the Orca
 * and Prusa catalogues carry no dependency information at all, so everything in them is reported
 * active rather than guessed at. Saying nothing is the honest answer for an engine that has not
 * said anything, and the alternative - inventing a heuristic from types and names - would put
 * settings in a group on the strength of a guess.
 */
object AllSettingsGroups {

    /**
     * The values the expressions are judged against: every catalogue default, overlaid by what
     * the app is actually going to send. A setting the app does not write keeps the definition's
     * default, which is what the engine will use for it.
     */
    fun effectiveValues(
        specs: List<ExtraSettingSpec>,
        appValues: Map<String, String>,
        extras: Map<String, String>,
    ): Map<String, String> {
        val values = LinkedHashMap<String, String>(specs.size)
        for (spec in specs) spec.defaultValue?.let { values[spec.key] = it }
        values.putAll(appValues)
        values.putAll(extras)
        return values
    }

    /** [specs] split into the ones in effect and the ones waiting on something, order kept. */
    fun split(
        specs: List<ExtraSettingSpec>,
        values: Map<String, String>,
    ): Pair<List<ExtraSettingSpec>, List<InactiveSetting>> {
        val active = ArrayList<ExtraSettingSpec>(specs.size)
        val inactive = ArrayList<InactiveSetting>()
        for (spec in specs) {
            val reason = CuraEnabled.inactiveReason(spec.enabledExpression) { values[it] }
            if (reason == null) active.add(spec) else inactive.add(InactiveSetting(spec, reason))
        }
        return active to inactive
    }
}
