package com.tomppi.enderslicer.model

import android.content.res.AssetManager
import java.io.FileNotFoundException
import org.json.JSONArray
import org.json.JSONObject

/**
 * PrusaSlicer's own preset repository, as the shipped vendor bundle writes it.
 *
 * A vendor package is one or more YAML documents per file: the user-facing presets
 * (kind printer/print/filament) plus the hardware components they are conditioned on
 * (printer_config, tool, sheet, feeder) and the shared presets they inherit from. A preset's
 * values sit behind conditions on the hardware - printer model, nozzle diameter, MMU, sheet -
 * and a print profile is a *named variant* inside such a tree (e.g. "0.20mm SPEED @MK4 0.4HF").
 *
 * The engine's console only accepts an already-resolved configuration, so the app resolves
 * these conditions itself. scripts/prusa-presets-to-json.py re-expresses the YAML as JSON at
 * fetch time (PyYAML host-side) so no YAML parser has to ship in the APK.
 */
class PrusaPresetRepository private constructor(
    val vendor: Vendor?,
    val documents: List<Document>,
) {
    /** The bundle's own metadata and the defaults of every hardware feature. */
    data class Vendor(
        val id: String,
        val name: String,
        val version: String,
        val features: Map<String, Map<String, Feature>>,
    ) {
        data class Feature(val default: Any?, val userEditable: Boolean)
    }

    /**
     * One YAML document.
     *
     * @property values settings the document sets itself.
     * @property variants conditional branches; each branch may set values and nest further
     *   branches, which is how one printer document covers every model and nozzle.
     * @property matchMode the document's `match_mode` attribute: null (the default) means a
     *   branch list stops after the first conditional branch that holds, `all_matches` means
     *   every matching sibling applies.
     */
    class Document(
        val file: String,
        val index: Int,
        val kind: String,
        val id: String?,
        val name: String?,
        val condition: String?,
        val inherits: List<String>,
        val values: Map<String, Any?>,
        val variants: List<Variant>,
        val matchMode: String?,
        private val raw: Map<String, Any?>,
    ) {
        /** True when every matching sibling applies instead of only the first one. */
        val allMatches: Boolean get() = matchMode == ALL_MATCHES

        /** Hardware model of a printer document: the base model and the concrete model. */
        val baseModel: String? get() = (raw["model"] as? Map<*, *>)?.get("base_model") as? String
        val model: String? get() = (raw["model"] as? Map<*, *>)?.get("model") as? String

        /** A printer document's own overrides of the bundle's feature defaults. */
        fun featureOverrides(domain: String): Map<String, Any?> =
            ((raw["features"] as? Map<*, *>)?.get(domain) as? Map<*, *>)
                ?.mapNotNull { (key, value) ->
                    val entry = value as? Map<*, *> ?: return@mapNotNull null
                    (key as? String)?.let { it to entry["default"] }
                }
                ?.toMap()
                .orEmpty()

        val toolCount: Int? get() = (raw["tool_count"] as? Number)?.toInt()

        /** A printer_config document's printer reference. */
        val printer: String? get() = raw["printer"] as? String

        /** A printer_config document's tools, in order: the nozzle diameters it can carry. */
        val tools: List<Tool> get() = (raw["tools"] as? List<*>).orEmpty().mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            // The bundle states a tool as "0.4", "0.4HF" (a high-flow nozzle) or, in five MMU
            // configs, as the number 0.4.
            val stated = when (val raw = map["tool"]) {
                is String -> raw
                is Number -> raw.toString()
                else -> null
            } ?: return@mapNotNull null
            val highFlow = stated.endsWith("HF", ignoreCase = true)
            val diameter = stated.let { if (highFlow) it.dropLast(2) else it }.toDoubleOrNull()
                ?: return@mapNotNull null
            Tool(
                diameterMm = diameter,
                highFlow = highFlow || (map["high_flow"] as? Boolean) == true,
            )
        }

        /** A printer_config document's feeders (an MMU or none). */
        val feeders: List<String> get() = (raw["feeders"] as? List<*>).orEmpty().mapNotNull { entry ->
            when (entry) {
                is String -> entry
                is Map<*, *> -> entry["feeder"] as? String
                else -> null
            }
        }

        /** A printer_config document's sheet. */
        val sheet: String? get() = raw["sheet"] as? String

        fun rawValue(key: String): Any? = raw[key]

        override fun toString(): String = kind + " " + (id ?: name ?: file + "#" + index)
    }

    /** One nozzle a printer config can carry. */
    data class Tool(val diameterMm: Double?, val highFlow: Boolean)

    /** A conditional branch of a preset: values plus the branches nested inside it. */
    data class Variant(
        val condition: String?,
        val name: String?,
        val id: String?,
        val values: Map<String, Any?>,
        val variants: List<Variant>,
    ) {
        /** Names wrapped in stars are the bundle's own branch labels, not user-facing presets. */
        val isSelectable: Boolean
            get() = !name.isNullOrBlank() && !(name.startsWith("*") && name.endsWith("*"))
    }

    private val byIdIndex: Map<String, Document> by lazy {
        buildMap {
            for (document in documents) {
                document.id?.let { putIfAbsent(it, document) }
            }
        }
    }

    private val byKindIndex: Map<String, List<Document>> by lazy { documents.groupBy { it.kind } }

    fun byId(id: String): Document? = byIdIndex[id]

    fun ofKind(kind: String): List<Document> = byKindIndex[kind].orEmpty()

    /** The vendor bundle's default of one hardware feature, e.g. printer/supports_04_nozzle. */
    fun featureDefault(domain: String, feature: String): Any? =
        vendor?.features?.get(domain)?.get(feature)?.default

    companion object {
        /** The generated JSON view of the bundled repository. */
        const val ASSET = "prusa-presets.json"

        /** The `match_mode` value that keeps evaluating every matching sibling. */
        const val ALL_MATCHES = "all_matches"

        /**
         * The outcome of reading [ASSET].
         *
         * [read] collapses all of these to null, so a caller that can log has to use this to
         * tell a bundle that was never generated from one that is present but broken or empty.
         */
        sealed interface ReadResult {
            data class Loaded(val repository: PrusaPresetRepository) : ReadResult

            /** No [ASSET] in the APK (the fetch script never ran). */
            data object Absent : ReadResult

            /** [ASSET] is present but carries no documents. */
            data object Empty : ReadResult

            /** [ASSET] could not be read or parsed; [reason] is for the slice log. */
            data class Unreadable(val reason: String) : ReadResult
        }

        fun readResult(assets: AssetManager): ReadResult {
            val text = try {
                assets.open(ASSET).bufferedReader().use { it.readText() }
            } catch (error: FileNotFoundException) {
                return ReadResult.Absent
            } catch (error: Throwable) {
                return ReadResult.Unreadable(error.message ?: error.javaClass.simpleName)
            }
            val repository = try {
                parse(text)
            } catch (error: Throwable) {
                return ReadResult.Unreadable(error.message ?: error.javaClass.simpleName)
            }
            return if (repository.documents.isEmpty()) {
                ReadResult.Empty
            } else {
                ReadResult.Loaded(repository)
            }
        }

        fun read(assets: AssetManager): PrusaPresetRepository? =
            (readResult(assets) as? ReadResult.Loaded)?.repository

        fun parse(json: String): PrusaPresetRepository {
            val root = JSONObject(json)
            val vendors = root.optJSONArray("vendor") ?: JSONArray()
            val vendor = (0 until vendors.length()).mapNotNull { index ->
                vendors.optJSONObject(index)?.let(::vendor)
            }.firstOrNull()
            val array = root.optJSONArray("documents") ?: JSONArray()
            val documents = (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::document)
            }
            return PrusaPresetRepository(vendor, documents)
        }

        private fun vendor(json: JSONObject): Vendor {
            val features = json.optJSONObject("features")
            return Vendor(
                id = json.optString("id"),
                name = json.optString("name"),
                version = json.optString("version"),
                features = buildMap {
                    val domains = features?.keys()
                    while (domains != null && domains.hasNext()) {
                        val domain = domains.next()
                        val entries = features.optJSONObject(domain) ?: continue
                        val keys = entries.keys()
                        put(
                            domain,
                            buildMap {
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    val entry = entries.optJSONObject(key) ?: continue
                                    val editable = entry.has("user_editable") &&
                                        entry.optBoolean("user_editable", true)
                                    put(key, Vendor.Feature(value(entry.opt("default")), editable))
                                }
                            },
                        )
                    }
                },
            )
        }

        private fun document(json: JSONObject): Document = Document(
            file = json.optString("_file"),
            index = json.optInt("_index"),
            kind = json.optString("kind"),
            id = json.optString("id").takeIf { it.isNotEmpty() },
            name = json.optString("name").takeIf { it.isNotEmpty() },
            condition = json.optString("condition").takeIf { it.isNotEmpty() },
            inherits = json.optJSONArray("inherits").orEmptyStrings(),
            values = jsonObjectToMap(json.optJSONObject("values") ?: JSONObject()),
            variants = variantList(json.optJSONArray("variants")),
            matchMode = json.optString("match_mode").takeIf { it.isNotEmpty() },
            raw = jsonObjectToMap(json),
        )

        private fun variantList(array: JSONArray?): List<Variant> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                Variant(
                    condition = json.optString("condition").takeIf { it.isNotEmpty() },
                    name = json.optString("name").takeIf { it.isNotEmpty() },
                    id = json.optString("id").takeIf { it.isNotEmpty() },
                    values = jsonObjectToMap(json.optJSONObject("values") ?: JSONObject()),
                    variants = variantList(json.optJSONArray("variants")),
                )
            }
        }

        private fun JSONArray?.orEmptyStrings(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optString(it).takeIf { text -> text.isNotEmpty() } }
        }

        /** Plain Kotlin values, so the resolver and its tests never touch org.json. */
        internal fun value(raw: Any?): Any? = when (raw) {
            null, JSONObject.NULL -> null
            is JSONObject -> jsonObjectToMap(raw)
            is JSONArray -> (0 until raw.length()).map { value(raw.opt(it)) }
            is Int, is Long, is Double, is Boolean, is String -> raw
            is Number -> raw.toDouble()
            else -> raw.toString()
        }

        private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> = buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, value(json.opt(key)))
            }
        }
    }
}
