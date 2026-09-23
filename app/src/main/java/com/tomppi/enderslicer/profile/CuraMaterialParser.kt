package com.tomppi.enderslicer.profile

import org.w3c.dom.Element
import java.io.ByteArrayInputStream

internal object CuraMaterialParser {
    fun parse(xml: String, machineProduct: String?): Map<String, String> {
        val document = SecureXml.factory().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val result = linkedMapOf<String, String>()

        firstText(document.documentElement, "GUID")?.let { result["material_guid"] = it }
        firstText(document.documentElement, "brand")?.let { result["material_brand"] = it }
        firstText(document.documentElement, "material")?.let { result["material_type"] = it }
        firstText(document.documentElement, "density")?.let { result["material_density"] = it }
        firstText(document.documentElement, "diameter")?.let { result["material_diameter"] = it }

        val settingsNodes = document.getElementsByTagNameNS("*", "settings")
        for (index in 0 until settingsNodes.length) {
            val settings = settingsNodes.item(index) as? Element ?: continue
            directChildSettings(settings).forEach { (key, value) ->
                mapSetting(key, value)?.let { (curaKey, curaValue) -> result[curaKey] = curaValue }
            }

            if (machineProduct != null) {
                val machineNodes = settings.getElementsByTagNameNS("*", "machine")
                for (machineIndex in 0 until machineNodes.length) {
                    val machine = machineNodes.item(machineIndex) as? Element ?: continue
                    val identifiers = machine.getElementsByTagNameNS("*", "machine_identifier")
                    val matches = (0 until identifiers.length)
                        .mapNotNull { identifiers.item(it) as? Element }
                        .any { it.getAttribute("product") == machineProduct }
                    if (!matches) continue
                    directChildSettings(machine).forEach { (key, value) ->
                        mapSetting(key, value)?.let { (curaKey, curaValue) -> result[curaKey] = curaValue }
                    }
                }
            }
        }

        return result
    }

    private fun directChildSettings(parent: Element): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            if (child.localName != "setting") continue
            val key = child.getAttribute("key").trim()
            val value = child.textContent.trim()
            if (key.isNotEmpty() && value.isNotEmpty()) result += key to value
        }
        return result
    }

    private fun mapSetting(key: String, value: String): Pair<String, String>? {
        val curaKey = when (key.trim().lowercase()) {
            "print temperature" -> "material_print_temperature"
            "heated bed temperature" -> "material_bed_temperature"
            "standby temperature" -> "material_standby_temperature"
            "adhesion tendency" -> "material_adhesion_tendency"
            "surface energy" -> "material_surface_energy"
            "build volume temperature" -> "build_volume_temperature"
            "print cooling" -> "cool_fan_speed"
            "retraction speed" -> "retraction_speed"
            "retraction amount" -> "retraction_amount"
            else -> return null
        }
        // Cura material files store "print cooling" as True/False while the
        // engine's cool_fan_speed is a percentage; normalise the boolean.
        val normalized = if (curaKey == "cool_fan_speed") {
            when (value.trim().lowercase()) {
                "true", "1", "yes" -> "100"
                "false", "0", "no" -> "0"
                else -> value
            }
        } else {
            value
        }
        return curaKey to normalized
    }

    private fun firstText(root: Element, localName: String): String? {
        val nodes = root.getElementsByTagNameNS("*", localName)
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it)?.textContent?.trim() }
            .firstOrNull { it.isNotEmpty() }
    }
}
