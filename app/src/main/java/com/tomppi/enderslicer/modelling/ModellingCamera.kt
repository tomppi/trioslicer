package com.tomppi.enderslicer.modelling

import org.json.JSONObject
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Who is allowed to move the camera right now. */
enum class CameraOwner {
    /** The modelling agent. This is the default, and the state work happens in. */
    AGENT,

    /** The user, who has taken the camera to look at something. */
    USER,
    ;

    val wire: String get() = name.lowercase()
}

/**
 * The single camera the user and the modelling agent share.
 *
 * The two sides look at the same model through different renderers - the app
 * draws it in its own GL viewport, the agent renders it with Cycles inside the
 * Blender engine - so they cannot share a matrix. They can share a *spec*, and
 * the app owns the mapping: it resolves the orbit into an eye/up pair already
 * carried into the model's own frame, so the agent only has to put a camera at
 * `target + eye` looking at `target` with `up`.
 *
 * [yawDeg]/[pitchDeg]/[distanceMm] are the turntable values the viewport itself
 * uses, which is what makes a change round-trip: the agent edits those, the
 * viewport adopts them, and its own next report agrees.
 */
data class ModellingCamera(
    val yawDeg: Float,
    val pitchDeg: Float,
    val distanceMm: Float,
    val targetX: Float,
    val targetY: Float,
    val targetZ: Float,
    val fovDeg: Float = DEFAULT_FOV_DEGREES,
    /**
     * The size the app is rendering this view at, so the agent's render and the
     * user's screen can be the same picture rather than merely the same camera.
     *
     * Published rather than fixed because it follows the view: it changes with
     * the device, the orientation, and whether the chat is expanded. Zero means
     * unknown, and a reader should pick its own size.
     */
    val width: Int = 0,
    val height: Int = 0,
    val owner: CameraOwner = CameraOwner.AGENT,
    /** Bumped on every write, so a reader can tell a new camera from a stale one. */
    val rev: Long = 0L,
) {

    /**
     * Camera position relative to the model centre, in the model's own frame.
     *
     * Spherical, because that is what a turntable *is*: [yawDeg] is the azimuth
     * around the model's vertical axis and [pitchDeg] is the elevation above its
     * horizon, so pitch always means "look from higher up" no matter which way
     * the model is turned.
     *
     * This started out as the app viewport's model rotation (`Rx(pitch) *
     * Rz(yaw)`) applied to a fixed eye, which is *not* the same thing: that
     * pitches about the world X axis, so once the azimuth is off zero, dragging
     * up and down swings the camera sideways instead of raising it. It also
     * inverted the control - positive pitch lowered the eye.
     */
    fun eyeOffset(): FloatArray {
        val azimuth = Math.toRadians(yawDeg.toDouble())
        val elevation = Math.toRadians(pitchDeg.toDouble())
        val horizontal = distanceMm.toDouble() * cos(elevation)
        return floatArrayOf(
            (horizontal * sin(azimuth)).toFloat(),
            (-horizontal * cos(azimuth)).toFloat(),
            (distanceMm.toDouble() * sin(elevation)).toFloat(),
        )
    }

    /**
     * World up, always.
     *
     * A turntable keeps the horizon level; tilting the up vector as the camera
     * rose is what would roll the view instead.
     */
    fun upVector(): FloatArray = floatArrayOf(0f, 0f, 1f)

    fun withOwner(owner: CameraOwner): ModellingCamera = copy(owner = owner, rev = rev + 1)

    /** The published frame size, or null when this camera predates it. */
    fun frameSize(): Pair<Int, Int>? =
        if (width > 0 && height > 0) width to height else null

    fun toJson(): JSONObject {
        val eye = eyeOffset()
        val up = upVector()
        return JSONObject()
            .put("yawDeg", yawDeg.toDouble())
            .put("pitchDeg", pitchDeg.toDouble())
            .put("distanceMm", distanceMm.toDouble())
            .put("fovDeg", fovDeg.toDouble())
            .put("width", width)
            .put("height", height)
            .put("target", doubleArrayOf(targetX.toDouble(), targetY.toDouble(), targetZ.toDouble()).toJson())
            .put("eye", eye.map(::toDouble).toJson())
            .put("up", up.map(::toDouble).toJson())
            .put("owner", owner.wire)
            .put("rev", rev)
    }

    companion object {
        const val DEFAULT_FOV_DEGREES = 42f

        fun fromJson(json: JSONObject): ModellingCamera {
            val target = json.optJSONArray("target")
            return ModellingCamera(
                yawDeg = json.optDouble("yawDeg", 0.0).toFloat(),
                pitchDeg = json.optDouble("pitchDeg", 0.0).toFloat(),
                distanceMm = json.optDouble("distanceMm", 0.0).toFloat(),
                targetX = target?.optDouble(0, 0.0)?.toFloat() ?: 0f,
                targetY = target?.optDouble(1, 0.0)?.toFloat() ?: 0f,
                targetZ = target?.optDouble(2, 0.0)?.toFloat() ?: 0f,
                fovDeg = json.optDouble("fovDeg", DEFAULT_FOV_DEGREES.toDouble()).toFloat(),
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                owner = if (json.optString("owner") == CameraOwner.USER.wire) CameraOwner.USER else CameraOwner.AGENT,
                rev = json.optLong("rev", 0L),
            )
        }

        private fun toDouble(value: Float): Double = value.toDouble()

        private fun DoubleArray.toJson(): org.json.JSONArray {
            val array = org.json.JSONArray()
            forEach(array::put)
            return array
        }

        private fun List<Double>.toJson(): org.json.JSONArray {
            val array = org.json.JSONArray()
            forEach(array::put)
            return array
        }
    }
}

/**
 * Reads and writes [ModellingCamera] where the agent can reach it.
 *
 * The file lives in the engine's own directory tree on purpose: the Blender
 * addon runs in this process as the same uid, so `bpy` code can read and write
 * it with plain `open()`, with no bridge and no protocol.
 */
object ModellingCameraStore {

    /** `files/blender/camera.json`, next to the engine's imports and exports. */
    fun fileFor(blenderDir: File): File = File(blenderDir, "camera.json")

    fun read(blenderDir: File): ModellingCamera? = runCatching {
        val file = fileFor(blenderDir)
        if (!file.isFile) return null
        ModellingCamera.fromJson(JSONObject(file.readText()))
    }.getOrNull()

    fun write(blenderDir: File, camera: ModellingCamera) {
        runCatching {
            blenderDir.mkdirs()
            val file = fileFor(blenderDir)
            // Write beside and rename: the agent polls this file, and a torn
            // read of a half-written camera is worse than no camera at all.
            val temp = File(blenderDir, "camera.json.tmp")
            temp.writeText(camera.toJson().toString())
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }
}
