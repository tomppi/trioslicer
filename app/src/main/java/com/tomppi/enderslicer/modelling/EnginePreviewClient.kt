package com.tomppi.enderslicer.modelling

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/** What the engine's scene holds, as opposed to what the app last sent it. */
data class SceneSummary(
    val centreX: Float,
    val centreY: Float,
    val centreZ: Float,
    val radius: Float,
    val mesh: String,
    val vertices: Int,
    val faces: Int,
) {
    /** Short label for the modelling bar: the name and how big it is. */
    val label: String get() = mesh + " · " + faces / 1000 + "k faces"
}

/**
 * Renders the modelling preview *in* the Blender engine and brings the pixels
 * back.
 *
 * The engine runs inside this process and listens on loopback for the MCP
 * protocol, so the app can drive it exactly like the external agent does. That
 * is what makes one camera possible: what the user sees is the engine's own
 * render of its own scene through its own camera, so there is no second camera
 * to keep in step and no coordinate frame to translate between.
 *
 * Pixels come back through a file rather than the socket. The command channel
 * returns whatever the Python printed as one string, and base64-ing a PNG into
 * it would trade a small file write for a larger copy through an 8 KB receive
 * buffer. The file is in the app's own private directory and the engine runs as
 * the same uid, so neither side needs a permission to reach it.
 */
class EnginePreviewClient(
    private val host: String = "127.0.0.1",
    private val port: Int = DEFAULT_PORT,
    /** The engine's shared secret, written by [BlenderEngine] before it starts. */
    private val tokenFile: File? = null,
) : Closeable {

    private var socket: Socket? = null

    /** Set by [close]; no command may open a socket after that. */
    @Volatile private var closed = false
    private var cachedToken: String? = null

    /**
     * [value] as a Python string literal.
     *
     * The path used to be pasted into `r'__PATH__'`: the handoff file names come
     * from the engine and the agent, so an apostrophe in one made every import a
     * syntax error (which importModelWhenReady then retried for two minutes), and a
     * crafted name was Python running in this process.
     */
    private fun pythonString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "'" + escaped + "'"
    }

    private fun token(): String? = cachedToken ?: runCatching {
        tokenFile?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()?.also { cachedToken = it }

    /**
     * One command, one JSON reply.
     *
     * The engine's reader accumulates bytes until they parse as JSON and
     * answers with a single object per command, so there is no framing to agree
     * on beyond "read until it parses".
     */
    @Synchronized
    private fun command(
        type: String,
        params: JSONObject,
        timeoutMs: Int = READ_TIMEOUT_MS,
    ): JSONObject {
        // A render blocked in a socket read survives disposal: the client is closed,
        // the read fails, and the retry below would otherwise connect a fresh socket
        // that nothing is left to close.
        check(!closed) { "The preview client is closed" }
        // Every command the app sends is engine activity: re-arm the keeper's lease
        // so a long import is not raced by a CPU that is suspending.
        com.tomppi.enderslicer.nativebridge.BlenderEngine.keepAwake()
        val body = JSONObject()
            .put("type", type)
            .put("params", params)
        // The engine refuses anything but ping without it, so a co-installed app
        // cannot reach this socket and run Python as us.
        token()?.let { body.put("token", it) }
        val payload = body.toString().toByteArray(Charsets.UTF_8)

        repeat(2) { attempt ->
            val active = ensureSocket(timeoutMs)
            try {
                active.getOutputStream().apply { write(payload); flush() }
                return JSONObject(readJson(active))
            } catch (error: Throwable) {
                // A dead socket is expected whenever the engine restarts with
                // the app, so drop it and reconnect once before giving up.
                discardSocket()
                if (attempt == 1) throw error
            }
        }
        error("unreachable")
    }

    private fun ensureSocket(timeoutMs: Int): Socket {
        check(!closed) { "The preview client is closed" }
        socket?.let {
            if (!it.isClosed && it.isConnected) {
                // Per command, not per socket: an import needs 180 s while a
                // render needs 20, and whichever connected first used to fix the
                // timeout for every later command on that connection.
                it.soTimeout = timeoutMs
                return it
            }
        }
        val created = Socket()
        created.tcpNoDelay = true
        created.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        // Generous: the first GPU render of a process compiles the Workbench
        // shaders and can take a few hundred milliseconds.
        created.soTimeout = timeoutMs
        socket = created
        return created
    }

    private fun discardSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun readJson(active: Socket): String = readReply(active.getInputStream())

    /**
     * One reply, bounded, and decoded only once it can plausibly be complete.
     *
     * A reply is a JSON control message, not a payload - the pixels come back
     * through a file - so a peer that holds the port open must not be able to
     * grow this buffer without end. Re-decoding and re-parsing the whole buffer
     * once per 16 KB chunk was also quadratic work for whoever streamed it, so
     * the decode only runs when the last non-space byte can close the object.
     *
     * Kotlin-visible rather than private because a unit test drives this with a
     * stream it can end; the engine's socket is not something a test can hold.
     */
    internal fun readReply(input: InputStream): String {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var lastSignificant = 0.toByte()
        while (true) {
            val read = input.read(chunk)
            if (read < 0) error("Engine closed the connection")
            buffer.write(chunk, 0, read)
            for (index in 0 until read) {
                val byte = chunk[index]
                if (byte !in JSON_WHITESPACE) lastSignificant = byte
            }
            if (buffer.size() > MAX_REPLY_BYTES) {
                error(
                    "Engine reply exceeded the $MAX_REPLY_BYTES byte limit; " +
                        "the engine is not answering with a control message",
                )
            }
            if (lastSignificant != CLOSING_BRACE) continue
            // Decode the whole buffer each time. Decoding each chunk on its own
            // split any character that straddled a 16 KB boundary into U+FFFD.
            val text = String(buffer.toByteArray(), Charsets.UTF_8)
            // The reply is a complete JSON object with no terminator, so the
            // only way to know it has all arrived is that it parses.
            if (runCatching { JSONObject(text) }.isSuccess) return text
        }
    }

    /**
     * Asks the engine to stop serving.
     *
     * The engine parks after this instead of returning from its start script:
     * returning ends Blender's background main, and Blender's teardown calls
     * exit(), which used to take the whole app process with it.
     */
    fun shutdownEngine(): Boolean = runCatching {
        command("shutdown", JSONObject())
        true
    }.getOrDefault(false)

    /**
     * Points the engine's own camera and renders one frame into [into].
     *
     * @return true when the file was written. The engine reports errors in the
     *   reply rather than throwing, so a failed render costs a frame instead of
     *   the connection.
     */
    fun renderPreview(camera: ModellingCamera, width: Int, height: Int, into: File): Boolean {
        val eye = camera.eyeOffset()
        val up = camera.upVector()
        val script = PREVIEW_SCRIPT
            .replace("__TARGET__", "${camera.targetX}, ${camera.targetY}, ${camera.targetZ}")
            .replace("__EYE__", "${eye[0]}, ${eye[1]}, ${eye[2]}")
            .replace("__UP__", "${up[0]}, ${up[1]}, ${up[2]}")
            .replace("__FOV__", "${camera.fovDeg}")
            .replace("__WIDTH__", width.toString())
            .replace("__HEIGHT__", height.toString())
            .replace("__PATH__", pythonString(into.absolutePath))
        val reply = command("execute_code", JSONObject().put("code", script))
        return reply.optString("status") == "success" && into.isFile
    }

    /**
     * The engine's own scene bounds: `[centreX, centreY, centreZ, radius]`.
     *
     * The app asks rather than deriving it from the mesh it happens to hold.
     * The engine is the scene, so its bounds are the right thing to orbit and to
     * frame - and they are in the same coordinates the agent's renders use,
     * which is the mistake that aimed the first agent render at the print bed
     * instead of at the model.
     */
    fun sceneBounds(): FloatArray? = sceneSummary()?.let {
        floatArrayOf(it.centreX, it.centreY, it.centreZ, it.radius)
    }

    /**
     * What the engine is actually holding, not what the app last sent it.
     *
     * The app's own model name is the file on the plate, and the two can differ:
     * the engine works on what it was given and the app on what came back. The
     * modelling screen names the engine's scene from here so the two are never
     * confused for one another.
     */
    fun sceneSummary(): SceneSummary? {
        val reply = command("execute_code", JSONObject().put("code", BOUNDS_SCRIPT))
        if (reply.optString("status") != "success") return null
        val text = reply.optJSONObject("result")?.optString("result").orEmpty().trim()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.optBoolean("empty", false)) return null
        return SceneSummary(
            centreX = json.optDouble("cx", 0.0).toFloat(),
            centreY = json.optDouble("cy", 0.0).toFloat(),
            centreZ = json.optDouble("cz", 0.0).toFloat(),
            radius = json.optDouble("radius", 0.0).toFloat(),
            mesh = json.optString("mesh"),
            vertices = json.optInt("verts", 0),
            faces = json.optInt("faces", 0),
        )
    }

    /**
     * Loads [model] into the engine, replacing whatever meshes it holds.
     *
     * "Send to Blender" used to mean "copy a file into the engine's import
     * directory", which left the engine still holding its default cube and the
     * preview correctly showing a cube. Sending a model now loads it.
     */
    fun importModel(model: File, sceneDir: File? = null): Boolean {
        val script = IMPORT_SCRIPT.replace("__PATH__", pythonString(model.absolutePath))
        val reply = command("execute_code", JSONObject().put("code", script), IMPORT_TIMEOUT_MS)
        val ok = reply.optString("status") == "success"
        Log.i(TAG, "import " + model.name + " -> " + (if (ok) "ok" else reply.toString().take(160)))
        if (ok && sceneDir != null) {
            val summary = reply.optJSONObject("result")?.optString("result").orEmpty().trim()
            writeSceneMarker(sceneDir, model, summary)
        }
        return ok
    }

    /**
     * Records that the scene was replaced from outside the engine.
     *
     * The app loads a model whenever the user uploads one, which deletes whatever
     * was in the scene - including objects an agent was mid-way through working
     * on. That happened: an agent's close-up failed with "'NoneType' object has
     * no attribute 'data'" because its Cube had been replaced underneath it
     * mid-turn. The marker is what lets a reader notice instead of discovering it
     * through a crash.
     */
    private fun writeSceneMarker(dir: File, model: File, summary: String) {
        runCatching {
            dir.mkdirs()
            val file = File(dir, SCENE_MARKER)
            val temp = File(dir, "$SCENE_MARKER.tmp")
            temp.writeText(
                JSONObject()
                    .put("rev", System.currentTimeMillis())
                    .put("source", "app-import")
                    .put("file", model.name)
                    .put("note", summary)
                    .toString(),
            )
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    /**
     * Loads [model], waiting for the engine to answer.
     *
     * The engine is started by the same action that publishes the handoff and
     * takes tens of seconds to boot, so a single attempt is a race the model
     * usually loses - and losing it is silent. Importing a 125 MB STL took 14.6
     * seconds on the device, which is also well past a render's patience.
     */
    fun importModelWhenReady(
        model: File,
        sceneDir: File? = null,
        timeoutMs: Long = IMPORT_WAIT_MS,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val ok = runCatching { importModel(model, sceneDir) }.getOrDefault(false)
            if (ok) return true
            if (System.currentTimeMillis() >= deadline) {
                Log.w(TAG, "import gave up after " + timeoutMs + " ms")
                return false
            }
            Thread.sleep(1_000)
        }
    }

    /**
     * True while the engine is still on the scene it boots with.
     *
     * Used to decide whether a published handoff should be loaded on the way in:
     * anything the agent has built means a real mesh is present, and re-importing
     * over that would throw its work away.
     */
    fun isOnDefaultScene(): Boolean? {
        // Null is "could not ask", which is not the same as "no": reading a failed
        // question as "the scene has content" is what stopped a handoff being
        // imported while the engine was still booting.
        val reply = runCatching {
            command("execute_code", JSONObject().put("code", DEFAULT_SCENE_SCRIPT))
        }.getOrNull() ?: return null
        if (reply.optString("status") != "success") return null
        val text = reply.optJSONObject("result")?.optString("result").orEmpty().trim()
        return text == "default"
    }

    /** Reads the frame [renderPreview] wrote. Null when it is unreadable. */
    fun readPreview(file: File): Bitmap? = runCatching {
        if (!file.isFile) null else BitmapFactory.decodeFile(file.absolutePath)
    }
        .onFailure { Log.w(TAG, "preview decode failed", it) }
        .getOrNull()

    override fun close() {
        closed = true
        discardSocket()
    }

    companion object {
        private const val TAG = "EnginePreview"

        /** Shared-state file recording the last time the app replaced the scene. */
        const val SCENE_MARKER = "scene.json"
        const val DEFAULT_PORT = 9876
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 20_000
        /** A 125 MB STL took 14.6 s to import on the device; leave real room. */
        private const val IMPORT_TIMEOUT_MS = 180_000
        /** How long to keep trying while the engine boots. */
        private const val IMPORT_WAIT_MS = 120_000L

        /**
         * Far above the largest real reply and far below a phone heap.
         *
         * The biggest control reply is the scene summary, a few hundred bytes;
         * a megabyte leaves room for a script's printed output while refusing a
         * peer that just keeps streaming.
         */
        private const val MAX_REPLY_BYTES = 1024 * 1024
        private val JSON_WHITESPACE = byteArrayOf(
            ' '.code.toByte(),
            '\n'.code.toByte(),
            '\r'.code.toByte(),
            '\t'.code.toByte(),
        )
        private val CLOSING_BRACE = '}'.code.toByte()

        /**
         * Places the camera the same way the shared-camera skill documents, then
         * renders with Workbench.
         *
         * Workbench rather than Cycles because it needs no lights and no
         * sampling: about 10 ms a frame against Cycles' 50 ms, which is the
         * difference between a view that updates under your finger and one that
         * does not.
         */
        private val DEFAULT_SCENE_SCRIPT = """
import bpy
meshes = [o for o in bpy.context.scene.objects if o.type == 'MESH']
untouched = (len(meshes) == 1 and meshes[0].name == 'Cube'
             and len(meshes[0].data.vertices) == 8)
print('default' if untouched else 'custom')
""".trimIndent()

        private val IMPORT_SCRIPT = """
import bpy
path = __PATH__
for obj in list(bpy.data.objects):
    if obj.type == 'MESH':
        bpy.data.objects.remove(obj, do_unlink=True)
bpy.ops.import_mesh.stl(filepath=path)
meshes = [o for o in bpy.context.scene.objects if o.type == 'MESH']
print('imported %d mesh(es)' % len(meshes))
""".trimIndent()

        private val BOUNDS_SCRIPT = """
import bpy, json
from mathutils import Vector
lo = Vector((1e18, 1e18, 1e18))
hi = Vector((-1e18, -1e18, -1e18))
meshes = []
for o in bpy.context.scene.objects:
    if o.type != 'MESH':
        continue
    meshes.append(o)
    for corner in o.bound_box:
        w = o.matrix_world @ Vector(corner)
        lo = Vector((min(lo.x, w.x), min(lo.y, w.y), min(lo.z, w.z)))
        hi = Vector((max(hi.x, w.x), max(hi.y, w.y), max(hi.z, w.z)))
if not meshes:
    print(json.dumps({'empty': True}))
else:
    c = (lo + hi) * 0.5
    print(json.dumps({
        'cx': c.x, 'cy': c.y, 'cz': c.z,
        'radius': (hi - lo).length * 0.5,
        'mesh': meshes[0].name if len(meshes) == 1 else '%d meshes' % len(meshes),
        'verts': sum(len(o.data.vertices) for o in meshes),
        'faces': sum(len(o.data.polygons) for o in meshes),
    }))
""".trimIndent()

        private val PREVIEW_SCRIPT = """
import bpy, math
from mathutils import Matrix, Vector
scene = bpy.context.scene
cam = scene.camera
if cam is None:
    cam = bpy.data.objects.new('look', bpy.data.cameras.new('look'))
    scene.collection.objects.link(cam)
    scene.camera = cam
target = Vector((__TARGET__))
loc = target + Vector((__EYE__))
fwd = (target - loc).normalized()
up = Vector((__UP__)).normalized()
right = fwd.cross(up).normalized()
up2 = right.cross(fwd)
cam.matrix_world = Matrix.Translation(loc) @ Matrix((right, up2, -fwd)).transposed().to_4x4()
cam.data.angle = math.radians(__FOV__)
cam.data.clip_start = 0.01
cam.data.clip_end = 100000.0
scene.render.engine = 'BLENDER_WORKBENCH'
scene.render.resolution_x = __WIDTH__
scene.render.resolution_y = __HEIGHT__
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = 'PNG'
scene.render.filepath = __PATH__
bpy.ops.render.render(write_still=True)
print('preview %dx__HEIGHT__')
""".trimIndent()
    }
}
