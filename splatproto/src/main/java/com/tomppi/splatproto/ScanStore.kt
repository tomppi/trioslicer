package com.tomppi.splatproto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A camera of one scan; [index] is the id the native core sees. */
data class ScanCamera(
    val index: Int, val label: String, val cameraId: String, val physicalId: String?,
    val sensorOrientation: Int, val intr: Intrinsics, val position: FloatArray?,
)

data class ScanImage(
    val file: String, val camera: Int, val rig: Int, val up: FloatArray,
    val timestampNs: Long, val exposureNs: Long, val iso: Int, val focusDiopters: Float, val torch: Boolean,
    /** Rolling-shutter readout time (SENSOR_ROLLING_SHUTTER_SKEW), 0 if unknown. */
    val rsSkewNs: Long = 0,
    /** Gyroscope rate in camera axes (rad/s) at capture, or null. */
    val gyro: FloatArray? = null,
)

/**
 * One scan on disk:
 * ```
 * scans/<yyyyMMdd-HHmmss>/scan.json, images/<n>.jpg     (capture)
 *                         report.json, sparse.ply, dense.ply, sparse/0/<model>.txt (reconstruction)
 * ```
 * The folder is also a COLMAP project: `colmap ... --image_path images --input_path sparse/0`.
 */
class Scan(val dir: File) {
    val name: String get() = dir.name
    val imagesDir get() = File(dir, "images")
    val meta get() = File(dir, "scan.json")
    val report get() = File(dir, "report.json")
    val dense get() = File(dir, "dense.ply")
    val sparse get() = File(dir, "sparse.ply")
    val bestCloud: File? get() = dense.takeIf { it.length() > 300 } ?: sparse.takeIf { it.exists() }

    fun imageCount(): Int = imagesDir.list()?.count { it.endsWith(".jpg") } ?: 0

    /** The user's name for the scan (scan.json "title"), or the folder's timestamp. */
    val title: String
        get() = runCatching { JSONObject(meta.readText()).optString("title") }.getOrNull()?.takeIf { it.isNotBlank() } ?: name

    fun rename(title: String) {
        val o = JSONObject(meta.readText())
        if (title.isBlank()) o.remove("title") else o.put("title", title.trim())
        val tmp = File(dir, "scan.json.tmp")
        tmp.writeText(o.toString(1))
        tmp.renameTo(meta)
    }

    /** Bytes on disk (images, clouds, model). */
    fun sizeBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun write(cameras: List<ScanCamera>, images: List<ScanImage>) {
        val o = JSONObject()
        o.put("format", 1)
        o.put("app", "Parallax ${BuildConfigCompat.versionName}")
        o.put("device", JSONObject(DeviceProbe.identity()))
        o.put("cameras", JSONArray().apply {
            for (c in cameras) put(JSONObject().apply {
                put("index", c.index); put("label", c.label); put("cameraId", c.cameraId)
                put("physicalId", c.physicalId ?: JSONObject.NULL); put("sensorOrientation", c.sensorOrientation)
                put("width", c.intr.width); put("height", c.intr.height)
                put("fx", c.intr.fx); put("fy", c.intr.fy); put("cx", c.intr.cx); put("cy", c.intr.cy)
                put("k1", c.intr.k1); put("k2", c.intr.k2); put("p1", c.intr.p1); put("p2", c.intr.p2); put("k3", c.intr.k3)
                put("calibrated", c.intr.calibrated)
                put("lensPosition", c.position?.let { p -> JSONArray().apply { p.forEach { put(it.toDouble()) } } } ?: JSONObject.NULL)
            })
        })
        o.put("images", JSONArray().apply {
            for (i in images) put(JSONObject().apply {
                put("file", i.file); put("camera", i.camera); put("rig", i.rig)
                put("up", JSONArray().apply { i.up.forEach { put(it.toDouble()) } })
                put("timestampNs", i.timestampNs); put("exposureNs", i.exposureNs); put("iso", i.iso)
                put("focusDiopters", i.focusDiopters.toDouble()); put("torch", i.torch)
                put("rollingShutterSkewNs", i.rsSkewNs)
                i.gyro?.let { g -> put("gyro", JSONArray().apply { g.forEach { put(it.toDouble()) } }) }
            })
        })
        val tmp = File(dir, "scan.json.tmp")
        tmp.writeText(o.toString(1))
        tmp.renameTo(meta)
    }

    data class Loaded(val cameras: List<ScanCamera>, val images: List<ScanImage>)

    fun load(): Loaded? = runCatching {
        val o = JSONObject(meta.readText())
        val cams = o.getJSONArray("cameras").let { a ->
            (0 until a.length()).map { k ->
                val c = a.getJSONObject(k)
                val pos = c.optJSONArray("lensPosition")?.let { p -> FloatArray(p.length()) { p.getDouble(it).toFloat() } }
                ScanCamera(
                    c.getInt("index"), c.getString("label"), c.getString("cameraId"),
                    c.optString("physicalId").takeIf { !c.isNull("physicalId") }, c.getInt("sensorOrientation"),
                    Intrinsics(c.getInt("width"), c.getInt("height"), c.getDouble("fx"), c.getDouble("fy"),
                        c.getDouble("cx"), c.getDouble("cy"), c.getDouble("k1"), c.getDouble("k2"),
                        c.getDouble("p1"), c.getDouble("p2"), c.getDouble("k3"), c.getBoolean("calibrated")),
                    pos,
                )
            }
        }
        val imgs = o.getJSONArray("images").let { a ->
            (0 until a.length()).map { k ->
                val i = a.getJSONObject(k)
                val up = i.getJSONArray("up")
                ScanImage(i.getString("file"), i.getInt("camera"), i.getInt("rig"),
                    FloatArray(3) { up.getDouble(it).toFloat() }, i.getLong("timestampNs"), i.getLong("exposureNs"),
                    i.getInt("iso"), i.getDouble("focusDiopters").toFloat(), i.getBoolean("torch"),
                    i.optLong("rollingShutterSkewNs", 0L),
                    i.optJSONArray("gyro")?.let { g -> FloatArray(3) { g.getDouble(it).toFloat() } })
            }
        }
        Loaded(cams, imgs)
    }.getOrNull()

    fun reportJson(): JSONObject? = runCatching { JSONObject(report.readText()) }.getOrNull()
}

object ScanStore {
    fun root(ctx: Context) = File(ctx.filesDir, "scans").apply { mkdirs() }

    fun create(ctx: Context): Scan {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val dir = File(root(ctx), stamp)
        File(dir, "images").mkdirs()
        return Scan(dir)
    }

    fun list(ctx: Context): List<Scan> =
        root(ctx).listFiles()?.filter { File(it, "scan.json").exists() }?.sortedByDescending { it.name }?.map { Scan(it) }
            .orEmpty()
}

/** Avoids depending on the generated BuildConfig (buildConfig is off by default in AGP 9). */
object BuildConfigCompat {
    var versionName: String = "?"
}
