package com.tomppi.enderslicer.octoprint

import java.util.Locale
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject

internal const val OCTOPRINT_APP_NAME = "enderslicercura"

data class OctoPrintConfig(
    val baseUrl: String = "",
    val username: String = "",
    val snapshotUrlOverride: String = "",
    val pollIntervalSeconds: Int = 3,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()
}

enum class OctoPrintUploadAction {
    UPLOAD,
    UPLOAD_AND_SELECT,
    UPLOAD_AND_PRINT,
}

data class OctoPrintTemperature(
    val actual: Double? = null,
    val target: Double? = null,
    val offset: Double? = null,
)

data class OctoPrintPrinterState(
    val text: String = "Offline",
    val operational: Boolean = false,
    val printing: Boolean = false,
    val paused: Boolean = false,
    val pausing: Boolean = false,
    val cancelling: Boolean = false,
    val ready: Boolean = false,
    val error: Boolean = false,
    val closedOrError: Boolean = true,
    val tools: Map<String, OctoPrintTemperature> = emptyMap(),
    val bed: OctoPrintTemperature? = null,
    val chamber: OctoPrintTemperature? = null,
    val sdReady: Boolean = false,
)

data class OctoPrintJobState(
    val state: String = "Offline",
    val fileName: String? = null,
    val filePath: String? = null,
    val fileOrigin: String? = null,
    val fileSizeBytes: Long? = null,
    val estimatedPrintSeconds: Int? = null,
    val completionPercent: Double? = null,
    val filePosition: Long? = null,
    val elapsedSeconds: Int? = null,
    val remainingSeconds: Int? = null,
    val currentZ: Double? = null,
    val error: String? = null,
)

data class OctoPrintConnectionState(
    val state: String = "Closed",
    val port: String? = null,
    val baudrate: Int? = null,
    val printerProfile: String? = null,
    val ports: List<String> = emptyList(),
    val baudrates: List<Int> = emptyList(),
    val printerProfiles: List<String> = emptyList(),
    val portPreference: String? = null,
    val baudratePreference: Int? = null,
    val printerProfilePreference: String? = null,
    val autoConnect: Boolean = false,
)

data class OctoPrintFileEntry(
    val name: String,
    val path: String,
    val origin: String,
    val isFolder: Boolean,
    val sizeBytes: Long? = null,
    val dateEpochSeconds: Long? = null,
    val estimatedPrintSeconds: Int? = null,
    val filamentLengthMm: Double? = null,
    val successCount: Int? = null,
    val failureCount: Int? = null,
    val depth: Int = 0,
)

data class OctoPrintWebcamConfig(
    val snapshotUrl: String? = null,
    val streamUrl: String? = null,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val rotate90: Boolean = false,
)

/**
 * Printer mains power as reported by the OctoPrint PSU Control plugin.
 *
 * [supported] is false when the plugin is not installed, which is an ordinary
 * configuration rather than an error; [isOn] is null until a state has been
 * read back from the server.
 */
data class OctoPrintPowerState(
    val supported: Boolean = false,
    val isOn: Boolean? = null,
    val isPending: Boolean = false,
)

data class OctoPrintServerInfo(
    val apiVersion: String? = null,
    val serverVersion: String? = null,
    val displayText: String? = null,
    val userName: String? = null,
    val userIsAdmin: Boolean = false,
    val permissions: Set<String> = emptySet(),
)

data class OctoPrintUiState(
    val config: OctoPrintConfig = OctoPrintConfig(),
    val hasApiKey: Boolean = false,
    val serverInfo: OctoPrintServerInfo = OctoPrintServerInfo(),
    val printer: OctoPrintPrinterState = OctoPrintPrinterState(),
    val job: OctoPrintJobState = OctoPrintJobState(),
    val connection: OctoPrintConnectionState = OctoPrintConnectionState(),
    val power: OctoPrintPowerState = OctoPrintPowerState(),
    val files: List<OctoPrintFileEntry> = emptyList(),
    val freeBytes: Long? = null,
    val webcam: OctoPrintWebcamConfig = OctoPrintWebcamConfig(),
    val webcamFrame: ByteArray? = null,
    val webcamError: String? = null,
    val isRefreshing: Boolean = false,
    val isFileListRefreshing: Boolean = false,
    val isUploading: Boolean = false,
    val uploadProgress: Float? = null,
    val uploadFileName: String? = null,
    val authorizationPending: Boolean = false,
    val authorizationDialogUrl: String? = null,
    val authorizationDialogLaunchNonce: Long = 0L,
    val lastUpdatedEpochMillis: Long? = null,
    val statusMessage: String = "Configure OctoPrint to begin",
    val errorMessage: String? = null,
) {
    val isReady: Boolean get() = config.isConfigured && hasApiKey
    val isPrinting: Boolean get() = printer.printing || job.state.equals("Printing", ignoreCase = true)
    val isPaused: Boolean get() = printer.paused || job.state.equals("Paused", ignoreCase = true)
    val isTransitioning: Boolean get() =
        printer.pausing ||
            printer.cancelling ||
            job.state.equals("Pausing", ignoreCase = true) ||
            job.state.equals("Cancelling", ignoreCase = true)
    val hasActiveJob: Boolean get() = isPrinting || isPaused || isTransitioning
}

internal object OctoPrintJson {
    fun parseServerInfo(version: JSONObject, user: JSONObject?): OctoPrintServerInfo {
        fun stringArray(json: JSONObject?, name: String): Set<String> = buildSet {
            val array = json?.optJSONArray(name) ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
        val permissions = stringArray(user, "permissions")
        val groups = stringArray(user, "groups")
        return OctoPrintServerInfo(
            apiVersion = version.optString("api").takeIf(String::isNotBlank),
            serverVersion = version.optString("server").takeIf(String::isNotBlank),
            displayText = version.optString("text").takeIf(String::isNotBlank),
            userName = user?.optString("name")?.takeIf(String::isNotBlank),
            // The `admin` field was dropped from /api/currentuser in modern
            // OctoPrint; groups/permissions are the authoritative signal.
            userIsAdmin = (user?.optBoolean("admin", false) == true) ||
                "admins" in groups ||
                "ADMIN" in permissions,
            permissions = permissions,
        )
    }

    fun parsePrinter(root: JSONObject): OctoPrintPrinterState {
        val state = root.optJSONObject("state") ?: JSONObject()
        val flags = state.optJSONObject("flags") ?: JSONObject()
        val temperature = root.optJSONObject("temperature") ?: JSONObject()
        val tools = linkedMapOf<String, OctoPrintTemperature>()
        temperature.keys().forEach { key ->
            if (key.startsWith("tool")) {
                temperature.optJSONObject(key)?.let { tools[key] = parseTemperature(it) }
            }
        }
        return OctoPrintPrinterState(
            text = state.optString("text", "Unknown"),
            operational = flags.optBoolean("operational", false),
            printing = flags.optBoolean("printing", false),
            paused = flags.optBoolean("paused", false),
            pausing = flags.optBoolean("pausing", false),
            cancelling = flags.optBoolean("cancelling", false),
            ready = flags.optBoolean("ready", false),
            error = flags.optBoolean("error", false),
            closedOrError = flags.optBoolean("closedOrError", true),
            tools = tools,
            bed = temperature.optJSONObject("bed")?.let(::parseTemperature),
            chamber = temperature.optJSONObject("chamber")?.let(::parseTemperature),
            sdReady = root.optJSONObject("sd")?.optBoolean("ready", false) == true,
        )
    }

    fun parseJob(root: JSONObject): OctoPrintJobState {
        val job = root.optJSONObject("job") ?: JSONObject()
        val file = job.optJSONObject("file") ?: JSONObject()
        val progress = root.optJSONObject("progress") ?: JSONObject()
        return OctoPrintJobState(
            state = root.optString("state", "Unknown"),
            fileName = file.optString("name").takeIf(String::isNotBlank),
            filePath = file.optString("path").takeIf(String::isNotBlank),
            fileOrigin = file.optString("origin").takeIf(String::isNotBlank),
            fileSizeBytes = file.optLongOrNull("size"),
            estimatedPrintSeconds = job.optIntOrNull("estimatedPrintTime"),
            completionPercent = progress.optDoubleOrNull("completion"),
            filePosition = progress.optLongOrNull("filepos"),
            elapsedSeconds = progress.optIntOrNull("printTime"),
            remainingSeconds = progress.optIntOrNull("printTimeLeft"),
            currentZ = progress.optDoubleOrNull("currentZ") ?: root.optDoubleOrNull("currentZ"),
            error = root.optString("error").takeIf(String::isNotBlank),
        )
    }

    fun parseConnection(root: JSONObject): OctoPrintConnectionState {
        val current = root.optJSONObject("current") ?: JSONObject()
        val options = root.optJSONObject("options") ?: JSONObject()
        return OctoPrintConnectionState(
            state = current.optString("state", "Closed"),
            port = current.optString("port").takeIf(String::isNotBlank),
            baudrate = current.optIntOrNull("baudrate"),
            printerProfile = current.optString("printerProfile").takeIf(String::isNotBlank),
            ports = options.optStringList("ports"),
            baudrates = options.optIntList("baudrates"),
            printerProfiles = options.optJSONArray("printerProfiles")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index)
                        val id = item?.optString("id")?.takeIf(String::isNotBlank)
                        val name = item?.optString("name")?.takeIf(String::isNotBlank)
                        add(id ?: name ?: continue)
                    }
                }
            }.orEmpty(),
            portPreference = options.optString("portPreference").takeIf(String::isNotBlank),
            baudratePreference = options.optIntOrNull("baudratePreference"),
            printerProfilePreference = options.optString("printerProfilePreference").takeIf(String::isNotBlank),
            autoConnect = options.optBoolean("autoconnect", false),
        )
    }

    /** PSU Control's "isPSUOn" flag, or null when the response does not carry it. */
    fun parsePowerState(root: JSONObject): Boolean? =
        if (root.has("isPSUOn")) root.optBoolean("isPSUOn") else null

    fun parseFiles(root: JSONObject): Pair<List<OctoPrintFileEntry>, Long?> {
        val output = mutableListOf<OctoPrintFileEntry>()
        flattenFiles(root.optJSONArray("files") ?: JSONArray(), output, depth = 0, defaultOrigin = "local")
        return output to parseByteCount(root.opt("free"))
    }

    fun parseWebcam(root: JSONObject): OctoPrintWebcamConfig {
        val webcam = root.optJSONObject("webcam") ?: JSONObject()
        return OctoPrintWebcamConfig(
            snapshotUrl = webcam.optString("snapshotUrl").takeIf(String::isNotBlank),
            streamUrl = webcam.optString("streamUrl").takeIf(String::isNotBlank),
            flipHorizontal = webcam.optBoolean("flipH", false),
            flipVertical = webcam.optBoolean("flipV", false),
            rotate90 = webcam.optBoolean("rotate90", false),
        )
    }

    /**
     * Flattens the recursive `children` lists of OctoPrint's file API.
     *
     * The nesting is server-supplied, so it needs a ceiling: a hostile or
     * broken server can nest folders until the recursion overflows the stack,
     * and the StackOverflowError that produced reached the UI as a bare error
     * with no hint of where it came from. Real file lists nest a handful of
     * levels, so refusing past [MAX_FOLDER_DEPTH] costs nothing and says what
     * happened.
     */
    private fun flattenFiles(
        array: JSONArray,
        output: MutableList<OctoPrintFileEntry>,
        depth: Int,
        defaultOrigin: String,
    ) {
        check(depth <= MAX_FOLDER_DEPTH) {
            "OctoPrint file list nests more than $MAX_FOLDER_DEPTH folders deep; refusing to flatten it"
        }
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val type = item.optString("type")
            val isFolder = type == "folder" || item.has("children")
            val origin = item.optString("origin", defaultOrigin)
            val rawPath = item.optString("path", item.optString("name"))
            val path = runCatching {
                OctoPrintClient.normalizeRemotePath(rawPath, allowBlank = false)
            }.getOrNull() ?: continue
            val analysis = item.optJSONObject("gcodeAnalysis") ?: JSONObject()
            val filament = analysis.optJSONObject("filament") ?: JSONObject()
            val print = item.optJSONObject("print") ?: JSONObject()
            output += OctoPrintFileEntry(
                name = item.optString("display", item.optString("name", path.substringAfterLast('/'))),
                path = path,
                origin = origin,
                isFolder = isFolder,
                sizeBytes = item.optLongOrNull("size"),
                dateEpochSeconds = item.optLongOrNull("date"),
                estimatedPrintSeconds = analysis.optIntOrNull("estimatedPrintTime"),
                filamentLengthMm = filament.optDoubleOrNull("length"),
                successCount = print.optIntOrNull("success"),
                failureCount = print.optIntOrNull("failure"),
                depth = depth,
            )
            if (isFolder) {
                flattenFiles(item.optJSONArray("children") ?: JSONArray(), output, depth + 1, origin)
            }
        }
    }

    private fun parseByteCount(raw: Any?): Long? {
        if (raw == null || raw == JSONObject.NULL) return null
        if (raw is Number) return raw.toLong().takeIf { it >= 0L }
        val value = raw.toString().trim()
        value.toLongOrNull()?.let { return it.takeIf { bytes -> bytes >= 0L } }
        val match = Regex(
            pattern = """^([0-9]+(?:\.[0-9]+)?)\s*([KMGTPE]?I?B)$""",
            option = RegexOption.IGNORE_CASE,
        ).matchEntire(value) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].uppercase(Locale.US)
        val multiplier = when (unit) {
            "B" -> 1.0
            "KB" -> 1_000.0
            "MB" -> 1_000_000.0
            "GB" -> 1_000_000_000.0
            "TB" -> 1_000_000_000_000.0
            "PB" -> 1_000_000_000_000_000.0
            "EB" -> 1_000_000_000_000_000_000.0
            "KIB" -> 1_024.0
            "MIB" -> 1_048_576.0
            "GIB" -> 1_073_741_824.0
            "TIB" -> 1_099_511_627_776.0
            "PIB" -> 1_125_899_906_842_624.0
            "EIB" -> 1_152_921_504_606_846_976.0
            else -> return null
        }
        val bytes = amount * multiplier
        return bytes.takeIf {
            it.isFinite() && it >= 0.0 && it <= Long.MAX_VALUE.toDouble()
        }?.roundToLong()
    }

    private fun parseTemperature(root: JSONObject): OctoPrintTemperature = OctoPrintTemperature(
        actual = root.optDoubleOrNull("actual"),
        target = root.optDoubleOrNull("target"),
        offset = root.optDoubleOrNull("offset"),
    )

    /** Deeper than any real OctoPrint folder tree, shallow enough to recurse safely. */
    private const val MAX_FOLDER_DEPTH = 32
}

internal fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    val value = optDouble(name, Double.NaN)
    return value.takeUnless(Double::isNaN)
}

internal fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return runCatching { getInt(name) }.getOrNull()
}

internal fun JSONObject.optLongOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return runCatching { getLong(name) }.getOrNull()
}

private fun JSONObject.optStringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            array.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}

private fun JSONObject.optIntList(name: String): List<Int> {
    val array = optJSONArray(name) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            runCatching { array.getInt(index) }.getOrNull()?.let(::add)
        }
    }
}
