package com.tomppi.enderslicer.modelling

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * What the user touched, in the engine's own terms.
 *
 * The viewer picks triangles; this is the answer about the exact geometry. [index] indexes
 * the shape's own face, edge or vertex list, and is what the other CAD commands take.
 */
data class CadPick(
    val kind: String,
    val index: Int?,
    val x: Int,
    val y: Int,
    val normal: List<Double>? = null,
    val areaMm2: Double? = null,
    val centre: List<Double>? = null,
) {
    val isSomething: Boolean get() = kind == "face" || kind == "edge" || kind == "vertex"

    /** What to tell the agent, so it asks the engine rather than guessing from a picture. */
    fun asPrompt(x: Int, y: Int): String = when {
        isSomething && kind == "face" ->
            "The user picked face #$index in the CAD engine's own view (its area is " +
                "$areaMm2 mm2 and its normal is $normal). "
        isSomething ->
            "The user picked $kind #$index in the CAD engine's own view. "
        else ->
            "The user tapped the CAD view at ($x, $y) and the engine found nothing under " +
                "it. "
    }
}

/**
 * Renders the CAD view *in* the CAD engine and brings the pixels back.
 *
 * The engine listens on loopback for the MCP protocol, so the app drives it exactly as the
 * external agent does - which is what makes one view possible. What the user sees is the
 * engine's own render through the engine's own camera, and a tap is the engine's own pick
 * against the exact B-rep. There is no second camera to keep in step and no mesh to translate
 * through.
 *
 * The camera persists between calls: the engine only re-frames when [view] is asked to reset,
 * so a drag accumulates the way it would in a desktop CAD application.
 *
 * Pixels come back through a file rather than the socket, for the reason [EnginePreviewClient]
 * records: the command channel returns the Python's stdout as one string, and base64-ing a PNG
 * into it trades a small file write for a larger copy through an 8 KB receive buffer. The
 * engine already writes the file - that is how the agent shows the user a render - so this
 * reuses a path that exists rather than adding a second one.
 */
/**
 * The engine took longer to answer than the caller was willing to wait.
 *
 * Its own type because the request is still real: the engine queues what it accepted and runs it
 * whether or not this client is still connected - a command abandoned by a timed-out client was
 * measurably executed anyway. So a timed-out camera move has already happened, and a caller that
 * puts the motion back and sends it again applies one drag twice.
 */
class CadEngineTimeoutException(message: String, cause: Throwable?) :
    IllegalStateException(message, cause)

class CadPreviewClient(
    private val host: String = "127.0.0.1",
    private val port: Int = DEFAULT_PORT,
    /** The engine's shared secret, written before it starts. */
    private val tokenFile: File? = null,
) : Closeable {

    private var socket: Socket? = null

    /**
     * Serialises commands.
     *
     * There is one socket and several callers - the frame loop, an import, the settings sheet -
     * and two writes interleaved on one stream mix their replies. That failure is quiet and
     * bizarre: a save came back as "the engine refused the viewer settings: null" while the
     * engine had never received it.
     */
    private val lock = Any()

    /** Set by [close]; no command may open a socket after that. */
    @Volatile
    private var closed = false
    private var cachedToken: String? = null

    /** The engine refuses anything without the token, ping included. */
    private fun token(): String? = cachedToken ?: runCatching {
        tokenFile?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()?.also { cachedToken = it }

    private fun command(
        type: String,
        params: JSONObject,
        timeoutMs: Int = READ_TIMEOUT_MS,
    ): JSONObject = synchronized(lock) {
        // One command at a time. The frame loop and the settings sheet both talk over this
        // socket, and interleaving them mixes two replies into one unparseable stream - which
        // is how a saved setting came back as "the engine refused the viewer settings: null"
        // while the engine had never heard of it.
        check(!closed) { "The CAD preview client is closed" }
        val body = JSONObject().put("type", type).put("params", params)
        token()?.let { body.put("token", it) }
        val payload = body.toString().toByteArray(Charsets.UTF_8)

        repeat(2) { attempt ->
            val active = ensureSocket(timeoutMs)
            try {
                active.getOutputStream().apply { write(payload); flush() }
                // synchronized() is inline, so this returns from command() itself.
                return JSONObject(readReply(active.getInputStream()))
            } catch (error: SocketTimeoutException) {
                // The engine has one thread and it is still running this command; the answer will
                // not come back on this socket, and a second attempt would only be refused as
                // "engine busy" while the first finishes - which is how a slow command came back
                // as a busy engine. A timeout is reported as what it is, and as its own type: the
                // command was accepted and the engine runs what it accepted whether or not this
                // client is still here, so a caller must not apply the same motion twice.
                discardSocket()
                throw CadEngineTimeoutException(
                    "the CAD engine did not answer within " + (timeoutMs / 1000) + "s", error)
            } catch (error: Throwable) {
                // A dead socket is expected whenever the engine restarts, so drop it and
                // reconnect once before giving up.
                discardSocket()
                if (attempt == 1) throw error
            }
        }
        error("unreachable")
    }

    private fun ensureSocket(timeoutMs: Int): Socket {
        check(!closed) { "The CAD preview client is closed" }
        socket?.let {
            if (!it.isClosed && it.isConnected) {
                // Per command rather than per socket: a render and a reset want different
                // patience, and whichever connected first used to fix it for every later one.
                it.soTimeout = timeoutMs
                return it
            }
        }
        val created = Socket()
        created.tcpNoDelay = true
        created.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        // Generous: the first render of a process builds the GL window and compiles shaders.
        created.soTimeout = timeoutMs
        socket = created
        return created
    }

    private fun discardSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    /** One reply, bounded, decoded only once it can plausibly be complete. */
    internal fun readReply(input: InputStream): String {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var lastSignificant = 0.toByte()
        while (true) {
            val read = input.read(chunk)
            if (read < 0) error("The CAD engine closed the connection")
            buffer.write(chunk, 0, read)
            for (index in 0 until read) {
                val byte = chunk[index]
                if (byte !in JSON_WHITESPACE) lastSignificant = byte
            }
            if (buffer.size() > MAX_REPLY_BYTES) {
                error("The CAD engine reply exceeded $MAX_REPLY_BYTES bytes")
            }
            if (lastSignificant != CLOSING_BRACE) continue
            val text = String(buffer.toByteArray(), Charsets.UTF_8)
            if (runCatching { JSONObject(text) }.isSuccess) return text
        }
    }

    /**
     * Moves the engine's camera, optionally picks, and renders one frame into [into].
     *
     * @return the answer to a pick that was asked for - including "nothing there", which is an
     *   answer the screen has a sentence for and cannot tell from a spinner if it is folded
     *   into null. Null means no pick was asked for, or no frame came back; a failed frame is
     *   reported that way rather than by throwing, so one bad frame does not take the screen
     *   down with it.
     */
    fun view(
        into: File,
        turnYaw: Float = 0f,
        turnPitch: Float = 0f,
        panDx: Float = 0f,
        panDy: Float = 0f,
        zoom: Float = 1f,

        selectX: Int? = null,
        selectY: Int? = null,
        reset: Boolean = false,
        shaded: Boolean = true,
        orientation: String? = null,
        antialiasing: Boolean? = null,
        width: Int = 0,
        height: Int = 0,
    ): CadPick? {
        val params = JSONObject()
            .put("filepath", into.absolutePath)
            .put("turn_yaw", turnYaw.toDouble())
            .put("turn_pitch", turnPitch.toDouble())
            .put("pan_dx", panDx.toDouble())
            .put("pan_dy", panDy.toDouble())
            .put("zoom", zoom.toDouble())
            .put("reset", reset)
            .put("shaded", shaded)
        if (orientation != null) params.put("orientation", orientation)
        if (antialiasing != null) params.put("antialiasing", antialiasing)
        if (width > 0) params.put("width", width)
        if (height > 0) params.put("height", height)
        selectX?.let { params.put("select_x", it) }
        selectY?.let { params.put("select_y", it) }

        val reply = command("view", params)
        if (reply.optString("status") != "success") {
            error(reply.optString("message").ifEmpty { "The CAD engine refused the view" })
        }
        val result = reply.optJSONObject("result") ?: return null
        val picked = result.optJSONObject("picked") ?: return null
        val normal = picked.optJSONArray("normal")?.let { array ->
            List(array.length()) { array.optDouble(it) }
        }
        val centre = picked.optJSONArray("centre")?.let { array ->
            List(array.length()) { array.optDouble(it) }
        }
        return CadPick(
            kind = picked.optString("kind"),
            index = picked.optInt("index", -1).takeIf { it >= 0 },
            x = picked.optInt("x"),
            y = picked.optInt("y"),
            normal = normal,
            areaMm2 = picked.optDouble("area_mm2", Double.NaN).takeIf { !it.isNaN() },
            centre = centre,
        )
    }

    /**
     * Tells the engine how to draw, and asks it what it is actually doing.
     *
     * The read-back is the point of the answer: several of these go through OCCT's rendering
     * path, which on this driver accepts a call and ignores it, so a setting is only known to
     * have taken effect by measuring a frame.
     *
     * @return the engine's own report, or null when it refused the whole request.
     */
    fun viewerSettings(settings: CadViewerSettings): JSONObject? {
        val params = JSONObject()
            .put("tessellation", settings.tessellation)
            .put("background", settings.background)
            .put("grid", settings.grid)
            .put("grid_step_mm", settings.gridStepMm.toDouble())
            .put("axes", settings.axes)
            .put("projection", settings.projection)
            .put("edges", settings.edges)
            .put("antialiasing", settings.antialiasing)
        val reply = command("viewer_settings", params, IMPORT_TIMEOUT_MS)
        if (reply.optString("status") != "success") {
            // Say what the engine actually answered. A refused setting that reports only
            // "null" is a diagnostic dead end, and this one cost an evening.
            Log.w(TAG, "viewer_settings refused: " + reply)
            return null
        }
        return reply.optJSONObject("result")
    }

    /**
     * Hands a file to the engine and returns the name it filed the shape under.
     *
     * The engine already imports STEP, BREP, STL, SVG and DXF; this is the app reaching that
     * path, so a part can arrive from storage the way it arrives from the agent.
     *
     * Replaces the scene by default. Importing is how a part arrives, and a viewport with no
     * outliner cannot show that a second part is now sitting inside the first.
     *
     * @return the shape name on success, or null when the engine refused - the reply carries
     *   the reason, and a failed import should cost a message rather than the screen.
     */
    fun importFile(
        file: File,
        name: String = "",
        unit: String = "mm",
        replace: Boolean = true,
    ): String? {
        val params = JSONObject()
            .put("filepath", file.absolutePath)
            .put("name", name)
            .put("unit", unit)
            .put("replace", replace)
        val reply = command("import_file", params, IMPORT_TIMEOUT_MS)
        if (reply.optString("status") != "success") {
            error(reply.optString("message").ifEmpty { "The CAD engine refused the import" })
        }
        return reply.optJSONObject("result")?.optString("shapes")?.takeIf { it.isNotEmpty() }
    }

    /**
     * Writes every named shape in the scene to its own STL in [directory].
     *
     * The app watches that directory and puts each file on the build plate as its own object, so
     * this is what "send the whole scene to the plate" means - the way to print several CAD parts
     * together. Each shape is written through a temporary name and renamed into place, exactly
     * like the engine's own exports, because a half-written STL is not a file the importer can
     * use.
     *
     * @return how many shapes were written, or null when the engine refused.
     */
    fun exportEveryShape(directory: File): Int? {
        val script = EXPORT_EVERY_SHAPE_SCRIPT.replace("__DIR__", pythonString(directory.absolutePath))
        val reply = command("execute_code", JSONObject().put("code", script), IMPORT_TIMEOUT_MS)
        if (reply.optString("status") != "success") {
            Log.w(TAG, "export every shape refused: " + reply.toString().take(200))
            return null
        }
        val text = reply.optJSONObject("result")?.optString("result").orEmpty().trim()
        return text.lineSequence().lastOrNull { it.trim().toIntOrNull() != null }?.trim()?.toIntOrNull()
    }

    /**
     * Loads a model into the engine, waiting for the engine to come up.
     *
     * The engine extracts 566 MB and binds its port over tens of seconds, so a single attempt is a
     * race a model usually loses - and losing it was silent. That is how a file picked from the
     * CAD files screen did nothing at all when it was picked before the engine was ready. The
     * Blender client has waited this way since it was written.
     *
     * Only a connection failure is retried. A refusal is not a race - the engine answered, and it
     * said no - so it comes back at once rather than making the user wait out the deadline for an
     * answer that has already arrived. The import itself returning null is a success: the engine
     * loaded the file and simply reported no shape names.
     */
    fun importFileWhenReady(
        file: File,
        name: String = "",
        unit: String = "mm",
        replace: Boolean = true,
        timeoutMs: Long = IMPORT_WAIT_MS,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                importFile(file = file, name = name, unit = unit, replace = replace)
                return true
            } catch (error: IOException) {
                if (System.currentTimeMillis() >= deadline) {
                    Log.w(TAG, "load gave up waiting for the engine: " + error.message)
                    return false
                }
                Thread.sleep(1_000)
            } catch (error: Throwable) {
                Log.w(TAG, "load refused: " + error.message)
                return false
            }
        }
    }

    /** Loads a frame the engine wrote. */
    fun readFrame(file: File): Bitmap? = runCatching {
        if (!file.isFile) null else BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    override fun close() {
        closed = true
        discardSocket()
    }

    /**
     * A Python string literal for [value].
     *
     * The scripts below embed a path in engine-side source, and a path is not a value the engine
     * parses - it is code. The same escape the Blender client uses, for the same reason.
     */
    private fun pythonString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "'" + escaped + "'"
    }

    companion object {
        private const val TAG = "CadPreviewClient"

        /** The CAD engine's MCP port. */
        const val DEFAULT_PORT = 9877

        /**
         * One STL per named shape in the scene, each published by renaming it into place.
         *
         * The scene helpers (shapes/get) and build123d's export_stl are what execute_code puts in
         * scope; writing through a temporary name is what keeps a half-written file from being
         * picked up as a model.
         */
        private val EXPORT_EVERY_SHAPE_SCRIPT = """
import os
base = __DIR__
published = 0
used = set()
for name in shapes():
    # Two shapes whose names differ only in characters this replaces would be written to one
    # path, and the earlier part would be gone before anything read it.
    stem = "".join(c if (c.isalnum() or c in "-_.") else "_" for c in str(name)) or "shape"
    unique = stem
    suffix = 2
    while unique in used:
        unique = "%s_%d" % (stem, suffix)
        suffix += 1
    used.add(unique)
    final = os.path.join(base, unique + ".stl")
    temp = final + ".part"
    # One shape the kernel cannot tessellate must not cost the others: report it and carry on, so
    # the count the app shows matches the parts that actually arrive.
    try:
        export_stl(get(name), temp)
    except Exception as error:
        print("failed: %s: %s" % (name, error))
        continue
    os.replace(temp, final)
    published += 1
print(published)
"""

        private const val CONNECT_TIMEOUT_MS = 5_000
        /** How long a load waits for the engine to bind its port. The engine is the slow one. */
        private const val IMPORT_WAIT_MS = 120_000L
        /** A frame is a render plus a PNG encode; measured at ~120 ms, so this is slack. */
        private const val READ_TIMEOUT_MS = 30_000
        /** Parsing a STEP or a large STL: slower than a frame, still bounded. */
        private const val IMPORT_TIMEOUT_MS = 120_000
        private const val MAX_REPLY_BYTES = 8 * 1024 * 1024

        /**
         * The frame size the engine renders, in pixels a side.
         *
         * It clamps each axis on its own to this range (cad_mcp_slim.py RENDER_MIN/RENDER_MAX)
         * and answers with the size it used. Asking for more produced a frame smaller than the
         * view, which ContentScale.Fit then letterboxed while taps were mapped as if it filled
         * the view - so the viewport clamps first, and knows the size it will get back.
         */
        const val FRAME_MIN = 64
        const val FRAME_MAX = 2048
        private val JSON_WHITESPACE = setOf(
            ' '.code.toByte(), '\n'.code.toByte(),
            '\r'.code.toByte(), '\t'.code.toByte(),
        )
        private const val CLOSING_BRACE = '}'.code.toByte()
    }
}
