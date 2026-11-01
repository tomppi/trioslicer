package com.tomppi.enderslicer.scanruler.engine

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * The ScanRuler agent token: one per install in the app's private data, the same shape and
 * boundary as the Blender/CAD/SolidCraft tokens (32 hex characters, files/scanruler/).
 * The file stays out of the MCP tools' reach: it is only read by the app.
 */
object ScanRulerTokens {

    const val DIRECTORY = "scanruler"
    const val MCP_TOKEN_FILE = "scanruler_mcp_token.txt"

    fun home(context: Context): File = File(context.filesDir, DIRECTORY)

    fun mcpTokenFile(context: Context): File = File(home(context), MCP_TOKEN_FILE)

    /** Loads the token, creating a fresh one on first use. Null when the file cannot be written. */
    fun ensureMcpToken(context: Context): String? = runCatching {
        val file = mcpTokenFile(context)
        val existing = file.takeIf { it.isFile }?.readText()?.trim()
        if (!existing.isNullOrEmpty()) {
            existing
        } else {
            val created = UUID.randomUUID().toString().replace("-", "")
            file.parentFile?.mkdirs()
            file.writeText(created)
            file.setReadable(false, false)
            file.setReadable(true, true)
            created
        }
    }.getOrNull()
}
