package com.tomppi.enderslicer.profile

data class CuraIni(
    val sections: Map<String, Map<String, String>>,
) {
    operator fun get(section: String): Map<String, String> = sections[section].orEmpty()

    fun value(section: String, key: String): String? = sections[section]?.get(key)
}

object CuraIniParser {
    fun parse(text: String): CuraIni {
        val sections = linkedMapOf<String, LinkedHashMap<String, String>>()
        var currentSection = ""
        var currentKey: String? = null
        sections[currentSection] = linkedMapOf()

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.removeSuffix("\r")
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                // Cura writes intentional blank lines in multiline values as indented
                // whitespace-only lines. A truly empty line is only a separator.
                if (line.isNotEmpty() && line.first().isWhitespace()) {
                    currentKey?.let { key ->
                        val existing = sections.getValue(currentSection)[key].orEmpty()
                        sections.getValue(currentSection)[key] = "$existing\n"
                    }
                }
                return@forEach
            }

            if (line.firstOrNull()?.isWhitespace() == true && currentKey != null) {
                val key = requireNotNull(currentKey)
                val existing = sections.getValue(currentSection)[key].orEmpty()
                sections.getValue(currentSection)[key] =
                    if (existing.isEmpty()) line.trimStart() else "$existing\n${line.trimStart()}"
                return@forEach
            }

            if (trimmed.startsWith(";") || trimmed.startsWith("#")) {
                return@forEach
            }

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length - 1)
                sections.getOrPut(currentSection) { linkedMapOf() }
                currentKey = null
                return@forEach
            }

            val separator = line.indexOf('=')
            if (separator < 0) {
                return@forEach
            }

            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trimStart()
            sections.getOrPut(currentSection) { linkedMapOf() }[key] = value
            currentKey = key
        }

        return CuraIni(sections)
    }
}
