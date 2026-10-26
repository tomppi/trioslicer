package com.tomppi.enderslicer.agent

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.tomppi.enderslicer.nativebridge.BlenderEngine
import com.tomppi.enderslicer.nativebridge.CadEngine
import com.tomppi.enderslicer.solidcraft.SolidCraftBridge
import com.tomppi.enderslicer.solidcraft.SolidCraftEngine
import java.io.File

/**
 * What a sheet row's Copy puts on the clipboard: the address and THAT engine's token, one line.
 *
 * The old hand-over was two copies - the address in the sheet, the token behind its own
 * biometric prompt - so the user had to paste the address somewhere, copy the token, and paste
 * both to the agent. This is the one-paste form. The line is deliberately plain text with one
 * space and no punctuation beyond the URL and `token=`, because it is pasted into an agent's chat:
 *
 *     tcp://192.168.1.42:9878 token=0123...cdef
 *
 * A row whose engine has never started has no token file yet, so there is nothing honest to
 * copy: [line] answers [Ready.MissingToken] with the sentence the row shows, never a line with
 * an empty or made-up token.
 */
object AgentBridgeCopy {

    /** Shown on a row (and as the toast if it is somehow clicked) until the engine has run once. */
    const val MISSING_TOKEN_REASON =
        "start the engine first - its token is created when it starts"

    /** What a Copy can do, as data so the exact string is testable without a clipboard. */
    sealed interface Ready {
        /** One line, already trimmed: `<url> token=<token>`. */
        data class Line(val text: String) : Ready

        /** No token file yet: nothing may be copied, and [reason] says why. */
        data class MissingToken(val reason: String) : Ready
    }

    /**
     * The ready-to-paste line, or the named reason there is no token to put in it.
     *
     * The token is trimmed and must be non-blank; the result has exactly one space between the
     * URL and the token, no newline and no trailing whitespace - it is one line by construction,
     * so a test can pin the whole string byte for byte.
     */
    fun line(address: String, port: Int, token: String?): Ready {
        val value = token?.trim().orEmpty()
        if (value.isEmpty()) return Ready.MissingToken(MISSING_TOKEN_REASON)
        return Ready.Line("tcp://" + address + ":" + port + " token=" + value)
    }

    /**
     * A clipboard item carrying [text] marked sensitive, so Android keeps it out of the
     * clipboard preview and out of the share sheet's preview. The same flag the app's own
     * token-only copy uses. (The platform added the flag in Android 13; older releases have
     * no equivalent, and the clip is still a normal copy there.)
     */
    fun sensitiveClip(label: String, text: String): ClipData {
        val clip = ClipData.newPlainText(label, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        return clip
    }
}

/**
 * Each engine's own token, read from the file that engine actually writes.
 *
 * The paths are the engines' own: [BlenderEngine.tokenFile], [CadEngine.tokenFile] and
 * [SolidCraftEngine.tokenFile], with the config directories the app itself hands them. Nothing
 * here generates a token - a row may only offer what the engine has already written, which is
 * why a never-started engine has no line to copy.
 */
object AgentBridgeTokens {

    /** The token file for one agent bridge port, or null for a port that is not an engine. */
    fun file(context: Context, port: Int): File? {
        val app = context.applicationContext
        return when (port) {
            BlenderEngine.DEFAULT_MCP_PORT ->
                BlenderEngine.tokenFile(File(app.filesDir, "blender"))
            CadEngine.DEFAULT_PORT ->
                CadEngine.tokenFile(File(app.filesDir, "cad"))
            SolidCraftBridge.DEFAULT_PORT -> SolidCraftEngine.tokenFile(app)
            else -> null
        }
    }

    /** The token as the engine wrote it, or null when the file is absent, empty or unreadable. */
    fun read(context: Context, port: Int): String? = runCatching {
        file(context, port)?.takeIf { it.isFile }?.readText()?.trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** The ports whose token file exists right now, for the sheet's row availability. */
    fun available(context: Context): Set<Int> = AgentBridges.TARGETS
        .map { it.port }
        .filter { read(context, it) != null }
        .toSet()
}
