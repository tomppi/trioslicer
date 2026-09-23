package com.tomppi.enderslicer.profile

import org.json.JSONObject

/**
 * Reconstructs Cura's setting ownership from the imported definition stack.
 *
 * Some Cura archives repeat global values inside the extruder container. Those
 * duplicates must not be allowed to shadow the global stack. Conversely,
 * settings marked settable_per_extruder belong to the extruder stack. This
 * canonicalization happens before expression evaluation so every dependency is
 * calculated from the same baseline Cura would use.
 */
internal object CuraSettingScopeResolver {
    data class Overrides(
        val global: Map<String, String>,
        val extruder: Map<String, String>,
    )

    private data class ScopeDefinition(
        val settablePerExtruder: Boolean,
    )

    private data class Document(
        val parent: String?,
        val settings: Map<String, ScopeDefinition>,
    )

    fun canonicalize(
        profile: CuraEngineProfile,
        explicitDelta: Map<String, String>,
    ): Overrides {
        require(profile.usesProjectDefinitions) {
            "Cura setting scopes require a complete machine/extruder definition stack"
        }

        val documents = profile.definitionFiles.mapValues { (_, text) -> parse(text) }
        val cache = mutableMapOf<String, Map<String, ScopeDefinition>>()
        val machineDefinitions = resolveStack(
            requireNotNull(profile.machineDefinitionFileName),
            documents,
            cache,
            linkedSetOf(),
        )
        val extruderDefinitions = resolveStack(
            requireNotNull(profile.extruderDefinitionFileName),
            documents,
            cache,
            linkedSetOf(),
        )
        val combined = linkedMapOf<String, ScopeDefinition>().apply {
            putAll(machineDefinitions)
            putAll(extruderDefinitions)
        }

        val rawGlobal = linkedMapOf<String, String>().apply { putAll(profile.rawGlobalValues) }
        val rawExtruder = linkedMapOf<String, String>().apply { putAll(profile.rawExtruderValues) }

        // A manual edit is a delta on top of the imported baseline. Put it into
        // both candidate maps first; the ownership decision below routes it to
        // exactly one canonical scope.
        explicitDelta.forEach { (key, value) ->
            rawGlobal[key] = value
            rawExtruder[key] = value
        }

        val global = linkedMapOf<String, String>()
        val extruder = linkedMapOf<String, String>()
        val keys = linkedSetOf<String>().apply {
            addAll(rawGlobal.keys)
            addAll(rawExtruder.keys)
        }

        keys.forEach { key ->
            val machineOwns = key in machineDefinitions
            val definition = combined[key]
            val extruderOwned = when {
                definition?.settablePerExtruder == true -> true
                !machineOwns && key in extruderDefinitions -> true
                key !in rawGlobal && key in rawExtruder -> true
                else -> false
            }

            if (extruderOwned) {
                (rawExtruder[key] ?: rawGlobal[key])?.let { extruder[key] = it }
            } else {
                (rawGlobal[key] ?: rawExtruder[key])?.let { global[key] = it }
            }
        }

        return Overrides(global = global, extruder = extruder)
    }

    private fun resolveStack(
        rawName: String,
        documents: Map<String, Document>,
        cache: MutableMap<String, Map<String, ScopeDefinition>>,
        visiting: MutableSet<String>,
    ): Map<String, ScopeDefinition> {
        val name = normalizeName(rawName)
        cache[name]?.let { return it }
        check(visiting.add(name)) { "Cyclic Cura definition inheritance involving $name" }
        val document = documents[name] ?: error("Missing Cura definition required for scope resolution: $name")
        val result = linkedMapOf<String, ScopeDefinition>()
        document.parent?.let { parent ->
            result.putAll(resolveStack(parent, documents, cache, visiting))
        }
        result.putAll(document.settings)
        visiting.remove(name)
        cache[name] = result
        return result
    }

    private fun parse(text: String): Document {
        val root = JSONObject(text)
        val settings = linkedMapOf<String, ScopeDefinition>()
        // Machine/extruder definitions may place settings under "overrides"
        // (newer Cura format) or "settings"; merge both.
        root.optJSONObject("overrides")?.let { collect(it, settings) }
        root.optJSONObject("settings")?.let { collect(it, settings) }
        return Document(
            parent = root.optString("inherits").trim().ifEmpty { null },
            settings = settings,
        )
    }

    private fun collect(
        node: JSONObject,
        output: MutableMap<String, ScopeDefinition>,
    ) {
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val setting = node.optJSONObject(key) ?: continue
            output[key] = ScopeDefinition(
                settablePerExtruder = boolean(setting.opt("settable_per_extruder")) ?: false,
            )
            setting.optJSONObject("children")?.let { collect(it, output) }
        }
    }

    private fun boolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
        else -> null
    }

    private fun normalizeName(rawName: String): String {
        val name = rawName.substringAfterLast('/').substringAfterLast('\\')
        return if (name.endsWith(".def.json")) name else "$name.def.json"
    }
}
