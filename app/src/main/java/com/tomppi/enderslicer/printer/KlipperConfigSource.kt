package com.tomppi.enderslicer.printer

/**
 * Where the configuration the printer is running came from.
 *
 * It decides one thing, and it is the thing a user needs to be able to trust: whether this
 * app may touch that file. A configuration the app seeded is the app's, and gets refreshed
 * when the app ships a fix to it; a configuration somebody brought is theirs, and is never
 * rewritten again - only exported, replaced by another import, or put back to the app's
 * own by the button that says so.
 */
data class KlipperConfigSource(
    /** True once a configuration has been imported; false while the app's own is running. */
    val imported: Boolean = false,
    /** The name of the file that was brought, for the screen to show. */
    val name: String = "",
    val atMillis: Long = 0L,
) {
    /** One line for the Configuration card. */
    fun describe(): String = when {
        !imported -> "The configuration this app ships with"
        name.isBlank() -> "Your configuration"
        else -> "Your configuration, from " + name
    }

    fun toText(): String = buildString {
        append("source=").append(if (imported) "imported" else "shipped").append('\n')
        append("name=").append(name).append('\n')
        append("at=").append(atMillis).append('\n')
    }

    companion object {
        val SHIPPED = KlipperConfigSource()

        fun from(text: String): KlipperConfigSource {
            val values = text.lines()
                .mapNotNull { line ->
                    val at = line.indexOf('=')
                    if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
                }
                .toMap()
            return KlipperConfigSource(
                imported = values["source"] == "imported",
                name = values["name"].orEmpty(),
                atMillis = values["at"]?.toLongOrNull() ?: 0L,
            )
        }
    }
}

/** What happened to a configuration somebody brought, for the screen to report. */
data class KlipperImportResult(
    /** The file that became the configuration. */
    val fileName: String = "",
    /** What the app had to change so this device could run it. */
    val changes: List<String> = emptyList(),
    /** What the file asks for that this device cannot supply. */
    val warnings: List<String> = emptyList(),
    /** Files brought along with it, for its includes. */
    val companions: List<String> = emptyList(),
)
