package com.tomppi.enderslicer.profile

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal object CuraDefinitionResolver {
    data class Result(
        val globalValues: Map<String, String>,
        val extruderValues: Map<String, String>,
        val modelValues: Map<String, String>,
        val expressionCount: Int,
        val passes: Int,
    )

    private data class SettingDefinition(
        val defaultValue: Any?,
        val expression: String?,
        val settablePerMesh: Boolean?,
        val type: String?,
        val options: Set<String>?,
        val bestEffort: Boolean = false,
    )

    private data class DefinitionDocument(
        val parentName: String?,
        val settings: Map<String, SettingDefinition>,
    )

    fun resolve(
        definitionFiles: Map<String, String>,
        machineDefinitionFileName: String,
        extruderDefinitionFileName: String,
        globalOverrides: Map<String, String>,
        extruderOverrides: Map<String, String>,
        definitionExpressionKeys: Set<String> = DENSITY_DEPENDENT_EXPRESSION_KEYS,
    ): Result {
        require(definitionFiles.isNotEmpty()) { "No Cura definitions were available for expression resolution" }
        val documents = definitionFiles.mapValues { (name, content) ->
            runCatching { parseDocument(content, definitionExpressionKeys) }
                .getOrElse { throw IllegalArgumentException("Unable to parse Cura definition $name: ${it.message}", it) }
        }
        val stackCache = mutableMapOf<String, Map<String, SettingDefinition>>()
        val machineDefinitions = resolveDefinitionStack(
            fileName = normalizedDefinitionName(machineDefinitionFileName),
            documents = documents,
            cache = stackCache,
            visiting = linkedSetOf(),
        )
        val extruderOnlyDefinitions = resolveDefinitionStack(
            fileName = normalizedDefinitionName(extruderDefinitionFileName),
            documents = documents,
            cache = stackCache,
            visiting = linkedSetOf(),
        )
        val combinedExtruderDefinitions = linkedMapOf<String, SettingDefinition>().apply {
            putAll(machineDefinitions)
            putAll(extruderOnlyDefinitions)
        }

        val globalValues = defaults(machineDefinitions)
        val extruderValues = defaults(combinedExtruderDefinitions)
        val globalExpressions = expressions(machineDefinitions)
        val extruderExpressions = expressions(combinedExtruderDefinitions)
        val lockedGlobal = mutableSetOf<String>()
        val lockedExtruder = mutableSetOf<String>()

        val overrideProblems = linkedMapOf<String, String>()
        applyOverrides(globalOverrides, globalValues, globalExpressions, lockedGlobal, overrideProblems)

        // Cura's extruder stack inherits the selected global machine/quality
        // stack before applying extruder-specific containers. Re-apply those
        // global values here so per-extruder formulas do not evaluate against
        // unrelated definition defaults. This is especially important for tree
        // support: support_infill_rate depends on the globally selected
        // support_enable/support_structure values.
        applyOverrides(globalOverrides, extruderValues, extruderExpressions, lockedExtruder, overrideProblems)
        applyOverrides(extruderOverrides, extruderValues, extruderExpressions, lockedExtruder, overrideProblems)
        // A rejected formula would otherwise leave its setting at the default
        // while the rest of the profile resolved, which later reads as a
        // generic slice failure far from the profile that caused it.
        check(overrideProblems.isEmpty()) { reportedProblems(overrideProblems) }

        var passes = 0
        var changed: Boolean
        do {
            changed = false
            passes++
            changed = evaluateScope(
                expressions = globalExpressions,
                locked = lockedGlobal,
                localValues = globalValues,
                globalValues = globalValues,
                extruderValues = extruderValues,
            ) || changed
            changed = evaluateScope(
                expressions = extruderExpressions,
                locked = lockedExtruder,
                localValues = extruderValues,
                globalValues = globalValues,
                extruderValues = extruderValues,
            ) || changed
            check(passes < MAX_PASSES || !changed) {
                "Cura definition expressions did not converge after $MAX_PASSES passes"
            }
        } while (changed)

        val unresolved = linkedMapOf<String, String>()
        collectUnresolved(
            scope = "global",
            expressions = globalExpressions,
            locked = lockedGlobal,
            bestEffort = machineDefinitions.filterValues { it.bestEffort }.keys,
            localValues = globalValues,
            globalValues = globalValues,
            extruderValues = extruderValues,
            output = unresolved,
        )
        collectUnresolved(
            scope = "extruder",
            expressions = extruderExpressions,
            locked = lockedExtruder,
            bestEffort = combinedExtruderDefinitions.filterValues { it.bestEffort }.keys,
            localValues = extruderValues,
            globalValues = globalValues,
            extruderValues = extruderValues,
            output = unresolved,
        )
        check(unresolved.isEmpty()) { reportedProblems(unresolved) }

        validateResolvedScope("global", machineDefinitions, globalValues)
        validateResolvedScope("extruder", combinedExtruderDefinitions, extruderValues)

        val formattedGlobal = globalValues.mapValues { formatValue(it.value) }
        val formattedExtruder = extruderValues.mapValues { formatValue(it.value) }

        // CuraEngine evaluates mesh-sensitive settings from the model's own
        // settings stack. The resolved JSON transport does not infer this scope
        // from the definitions, so explicitly copy every setting marked
        // settable_per_mesh into the model section.
        val formattedModel = linkedMapOf<String, String>().apply {
            combinedExtruderDefinitions.forEach { (key, definition) ->
                if (definition.settablePerMesh != true) return@forEach
                val value = extruderValues[key] ?: globalValues[key] ?: return@forEach
                put(key, formatValue(value))
            }
        }

        return Result(
            globalValues = formattedGlobal,
            extruderValues = formattedExtruder,
            modelValues = formattedModel,
            expressionCount = globalExpressions.size + extruderExpressions.size,
            passes = passes,
        )
    }

    private fun resolveDefinitionStack(
        fileName: String,
        documents: Map<String, DefinitionDocument>,
        cache: MutableMap<String, Map<String, SettingDefinition>>,
        visiting: MutableSet<String>,
    ): Map<String, SettingDefinition> {
        cache[fileName]?.let { return it }
        check(visiting.add(fileName)) { "Cyclic Cura definition inheritance involving $fileName" }
        val document = documents[fileName]
            ?: error("Missing Cura definition required by the selected profile: $fileName")
        val result = linkedMapOf<String, SettingDefinition>()
        document.parentName?.let { parent ->
            result.putAll(
                resolveDefinitionStack(
                    fileName = normalizedDefinitionName(parent),
                    documents = documents,
                    cache = cache,
                    visiting = visiting,
                ),
            )
        }
        document.settings.forEach { (key, child) ->
            val parent = result[key]
            val childDefinesValue = child.defaultValue != null || child.expression != null
            result[key] = when {
                parent == null || childDefinesValue -> child.copy(
                    settablePerMesh = child.settablePerMesh ?: parent?.settablePerMesh,
                    type = child.type ?: parent?.type,
                    options = child.options ?: parent?.options,
                )
                else -> parent.copy(
                    settablePerMesh = child.settablePerMesh ?: parent.settablePerMesh,
                    type = child.type ?: parent.type,
                    options = child.options ?: parent.options,
                )
            }
        }
        visiting.remove(fileName)
        cache[fileName] = result
        return result
    }

    private fun parseDocument(
        content: String,
        definitionExpressionKeys: Set<String>,
    ): DefinitionDocument {
        val root = JSONObject(content)
        val settings = linkedMapOf<String, SettingDefinition>()
        // Newer Cura machine/extruder definitions (e.g. creality_base,
        // creality_ender3) place their settings under "overrides"; the base
        // fdmprinter/fdmextruder docs use "settings". Merge both, with the
        // "settings" section taking precedence when a key exists in both.
        root.optJSONObject("overrides")?.let { collectSettings(it, settings, definitionExpressionKeys) }
        root.optJSONObject("settings")?.let { collectSettings(it, settings, definitionExpressionKeys) }
        return DefinitionDocument(
            parentName = root.optString("inherits").trim().ifEmpty { null },
            settings = settings,
        )
    }

    private fun collectSettings(
        objectValue: JSONObject,
        output: MutableMap<String, SettingDefinition>,
        definitionExpressionKeys: Set<String>,
    ) {
        val keys = objectValue.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val setting = objectValue.optJSONObject(key) ?: continue
            val defaultValue = if (setting.has("default_value") && !setting.isNull("default_value")) {
                fromJson(setting.get("default_value"))
            } else {
                null
            }
            val rawValueExpression = setting.optString("value").trim()
            var bestEffort = false
            val expression = when {
                rawValueExpression.startsWith("=") -> rawValueExpression.removePrefix("=").trim()
                key in definitionExpressionKeys && rawValueExpression.isNotEmpty() -> rawValueExpression
                // Many Cura formulas (e.g. wall_line_count, support_infill_rate,
                // material_print_temperature_layer_0) are stored without the
                // leading '='. If the value parses as a formula it is evaluated;
                // plain text/literals stay as defaults because expressions()
                // now skips anything that does not parse.
                rawValueExpression.isNotEmpty() &&
                    isFormulaLike(rawValueExpression) -> {
                    bestEffort = true
                    rawValueExpression
                }
                // Cura also stores plain child references such as
                // cool_fan_speed_min = "cool_fan_speed" or
                // speed_roofing = "speed_topbottom". CuraEngine never evaluates
                // these (it reads the child key at its default_value), and
                // previously neither did this resolver, so the child silently
                // won its definition default. Treat a bare identifier as a
                // VariableExpr reference; the multi-pass evaluation resolves it
                // once the parent is known. Identifiers that never resolve stay
                // best-effort at their default, exactly like before.
                rawValueExpression.isNotEmpty() &&
                    isBareIdentifier(rawValueExpression) -> {
                    bestEffort = true
                    rawValueExpression
                }
                else -> null
            }
            val type = setting.optString("type")
                .trim()
                .lowercase()
                .ifEmpty { null }
            val options = setting.optJSONObject("options")?.let { optionObject ->
                buildSet {
                    // Cura definitions are not consistent about whether the
                    // stored engine value is the key or value of this map.
                    // Both sides are declared domain members; unknown values
                    // are still rejected.
                    val labels = optionObject.keys()
                    while (labels.hasNext()) {
                        val label = labels.next()
                        add(label)
                        val value = optionObject.opt(label)
                        if (value != null && value != JSONObject.NULL) add(value.toString())
                    }
                }
            }
            val settablePerMesh = if (setting.has("settable_per_mesh")) {
                booleanValue(setting.opt("settable_per_mesh"))
            } else {
                null
            }
            if (defaultValue != null || expression != null || type != null || options != null || settablePerMesh != null) {
                output[key] = SettingDefinition(
                    defaultValue = defaultValue,
                    expression = expression,
                    settablePerMesh = settablePerMesh,
                    type = type,
                    options = options,
                    bestEffort = bestEffort,
                )
            }
            setting.optJSONObject("children")?.let {
                collectSettings(it, output, definitionExpressionKeys)
            }
        }
    }

    /** True when a definition `value` looks like a formula rather than a literal. */
    private fun isFormulaLike(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.toDoubleOrNull() != null) return false
        if (trimmed.lowercase() in BOOLEAN_LITERALS) return false
        // A formula uses arithmetic/boolean/conditional syntax or a Cura
        // function call. Bare single identifiers are handled separately by
        // isBareIdentifier as setting references.
        if (trimmed.any { it in FORMULA_OPERATORS }) return true
        return trimmed.any { it == '(' } && trimmed.any { it == ')' }
    }

    /**
     * True when a definition `value` is a bare identifier naming another
     * setting (e.g. cool_fan_speed_min = "cool_fan_speed"). These are Cura
     * parent/child references, never option strings, in the pinned stacks
     * (verified: every bare identifier in the bundled definitions names a
     * known setting key).
     */
    private fun isBareIdentifier(value: String): Boolean {
        val trimmed = value.trim()
        if (!BARE_IDENTIFIER.matches(trimmed)) return false
        // Boolean/none literals and expression keywords (True, False, None,
        // 1/0/yes/no/on/off, and/or/not/if/else/in) are literals, not references;
        // a lower-case "false" override must stay a literal too.
        if (trimmed.lowercase() in BOOLEAN_LITERALS) return false
        return trimmed !in LITERAL_SPECIALS
    }

    private fun booleanValue(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
        else -> null
    }

    private fun fromJson(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONArray -> (0 until value.length()).map { index -> fromJson(value.get(index)) }
        is JSONObject -> linkedMapOf<String, Any?>().apply {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, fromJson(value.get(key)))
            }
        }
        is Number, is Boolean -> value
        is String -> normalize(value)
        else -> value.toString()
    }

    private fun defaults(definitions: Map<String, SettingDefinition>): LinkedHashMap<String, Any?> {
        return linkedMapOf<String, Any?>().apply {
            definitions.forEach { (key, definition) ->
                if (definition.defaultValue != null) put(key, normalize(definition.defaultValue))
            }
        }
    }

    private fun expressions(definitions: Map<String, SettingDefinition>): LinkedHashMap<String, CuraExpression> {
        return linkedMapOf<String, CuraExpression>().apply {
            definitions.forEach { (key, definition) ->
                definition.expression?.let { expression ->
                    // Definition files are not fully consistent: some settings
                    // whose "value" is intended as a formula are stored without
                    // the leading '=' marker. Parse defensively so a value that
                    // is really plain text (or a lone setting reference that
                    // only resolves later) does not abort resolution; it simply
                    // stays at its default and is resolved by the engine.
                    runCatching { CuraValueExpressionParser.parse(expression) }.getOrNull()?.let { put(key, it) }
                }
            }
        }
    }

    /**
     * Applies one scope's explicit values.
     *
     * A formula that will not parse is collected in [problems] instead of being
     * thrown from here: it came with the profile, and reporting it by name -
     * with the setting it belongs to - is what turns "the import swallowed a
     * stack overflow" into a slice that says which formula it refused.
     */
    private fun applyOverrides(
        overrides: Map<String, String>,
        values: MutableMap<String, Any?>,
        expressions: MutableMap<String, CuraExpression>,
        locked: MutableSet<String>,
        problems: MutableMap<String, String>,
    ) {
        overrides.forEach { (key, rawValue) ->
            val value = rawValue.trim()
            if (value.startsWith("=")) {
                runCatching { CuraValueExpressionParser.parse(value.removePrefix("=").trim()) }
                    .onSuccess { expression ->
                        expressions[key] = expression
                        locked.remove(key)
                    }
                    .onFailure { error ->
                        problems[key] = error.message ?: error::class.java.simpleName
                    }
            } else {
                values[key] = normalize(rawValue)
                expressions.remove(key)
                locked += key
            }
        }
    }

    private fun evaluateScope(
        expressions: Map<String, CuraExpression>,
        locked: Set<String>,
        localValues: MutableMap<String, Any?>,
        globalValues: Map<String, Any?>,
        extruderValues: Map<String, Any?>,
    ): Boolean {
        var changed = false
        val context = CuraEvaluationContext(localValues, globalValues, extruderValues)
        expressions.forEach { (key, expression) ->
            if (key in locked) return@forEach
            val evaluated = runCatching { expression.eval(context) }.getOrNull() ?: return@forEach
            if (!equivalent(localValues[key], evaluated)) {
                localValues[key] = evaluated
                changed = true
            }
        }
        return changed
    }

    private fun collectUnresolved(
        scope: String,
        expressions: Map<String, CuraExpression>,
        locked: Set<String>,
        bestEffort: Set<String>,
        localValues: Map<String, Any?>,
        globalValues: Map<String, Any?>,
        extruderValues: Map<String, Any?>,
        output: MutableMap<String, String>,
    ) {
        val context = CuraEvaluationContext(localValues, globalValues, extruderValues)
        expressions.forEach { (key, expression) ->
            if (key in locked) return@forEach
            // Best-effort formulas (definition values without a leading '=')
            // that fail to evaluate simply stay at their default value; only
            // explicit '='-prefixed overrides are required to resolve.
            if (key in bestEffort) return@forEach
            runCatching { expression.eval(context) }
                .onFailure { error -> output["$scope.$key"] = error.message ?: error::class.java.simpleName }
        }
    }

    /** One message for the settings that could not be resolved, whichever stage refused them. */
    private fun reportedProblems(problems: Map<String, String>): String =
        problems.entries.take(MAX_REPORTED_UNRESOLVED).joinToString(
            prefix = "Unable to resolve Cura definition expressions: ",
            separator = "; ",
        ) { (key, reason) -> "$key ($reason)" }

    private fun validateResolvedScope(
        scope: String,
        definitions: Map<String, SettingDefinition>,
        values: Map<String, Any?>,
    ) {
        definitions.forEach { (key, definition) ->
            if (key !in values) return@forEach
            validateResolvedValue(scope, key, definition, values[key])
        }
    }

    private fun validateResolvedValue(
        scope: String,
        key: String,
        definition: SettingDefinition,
        value: Any?,
    ) {
        fun invalid(expected: String): Nothing = throw IllegalArgumentException(
            "Resolved Cura setting has invalid type/domain: scope=$scope key=$key " +
                "declared=${definition.type ?: "unspecified"} value=${formatValue(value)} expected=$expected",
        )

        when (definition.type) {
            "bool", "boolean" -> {
                val valid = when (value) {
                    is Boolean -> true
                    is Number -> value.toDouble().isFinite() &&
                        (value.toDouble() == 0.0 || value.toDouble() == 1.0)
                    is String -> value.trim().lowercase() in BOOLEAN_LITERALS
                    else -> false
                }
                if (!valid) invalid("boolean or numeric 0/1")
            }
            "int", "integer" -> {
                val number = value as? Number ?: invalid("integer")
                val double = number.toDouble()
                if (!double.isFinite() || double % 1.0 != 0.0) invalid("finite integer")
            }
            "float", "double" -> {
                val number = value as? Number ?: invalid("number")
                if (!number.toDouble().isFinite()) invalid("finite number")
            }
            "enum" -> {
                val option = value as? String ?: invalid("enum option")
                val allowed = definition.options
                if (!allowed.isNullOrEmpty() && option !in allowed) {
                    invalid("one of ${allowed.sorted().joinToString()}")
                }
            }
            "str", "string" -> if (value !is String) invalid("string")
        }
    }

    private fun normalize(value: Any?): Any? {
        if (value !is String) return value
        val trimmed = value.trim()
        if (trimmed.equals("true", ignoreCase = true)) return true
        if (trimmed.equals("false", ignoreCase = true)) return false
        trimmed.toDoubleOrNull()?.let { return it }
        return value
    }

    private fun equivalent(left: Any?, right: Any?): Boolean {
        if (left is Number && right is Number) return abs(left.toDouble() - right.toDouble()) < 1e-8
        if (left is Collection<*> && right is Collection<*>) {
            return left.size == right.size && left.zip(right).all { (a, b) -> equivalent(a, b) }
        }
        if (left is Map<*, *> && right is Map<*, *>) {
            return left.keys == right.keys && left.keys.all { key -> equivalent(left[key], right[key]) }
        }
        return left == right
    }

    private fun formatValue(value: Any?): String = when (value) {
        null -> ""
        is Boolean -> value.toString().lowercase()
        is Byte, is Short, is Int, is Long -> value.toString()
        is Float, is Double -> formatNumber((value as Number).toDouble())
        is Number -> value.toString()
        is String -> value
        is Collection<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { item ->
            when (item) {
                is String -> JSONObject.quote(item)
                else -> formatValue(item)
            }
        }
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, item) ->
            "${JSONObject.quote(key.toString())}:${if (item is String) JSONObject.quote(item) else formatValue(item)}"
        }
        else -> value.toString()
    }

    private fun formatNumber(value: Double): String {
        if (value.isFinite() && abs(value - value.toLong()) < 1e-9) return value.toLong().toString()
        return value.toString()
    }

    private fun normalizedDefinitionName(rawName: String): String {
        val name = rawName.substringAfterLast('/').substringAfterLast('\\')
        return if (name.endsWith(".def.json")) name else "$name.def.json"
    }

    private val DENSITY_DEPENDENT_EXPRESSION_KEYS = setOf(
        "infill_line_distance",
        "infill_overlap",
        "infill_overlap_mm",
    )
    private val BOOLEAN_LITERALS = setOf("true", "false", "1", "0", "yes", "no", "on", "off")
    private val FORMULA_OPERATORS = charArrayOf('+', '-', '*', '/', '%', '=', '<', '>', '!')
    private val BARE_IDENTIFIER = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
    private val LITERAL_SPECIALS = setOf(
        "True", "False", "None", "and", "or", "not", "if", "else", "in",
    )
    private const val MAX_PASSES = 64
    private const val MAX_REPORTED_UNRESOLVED = 12
}
