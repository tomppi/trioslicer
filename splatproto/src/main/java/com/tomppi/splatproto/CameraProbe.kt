package com.tomppi.splatproto

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Size
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Pinhole + Brown-Conrady intrinsics in pixels of one output stream (OpenCV convention). */
data class Intrinsics(
    val width: Int, val height: Int,
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val k1: Double = 0.0, val k2: Double = 0.0, val p1: Double = 0.0, val p2: Double = 0.0, val k3: Double = 0.0,
    /** True when taken from the factory calibration rather than from focal length and sensor size. */
    val calibrated: Boolean,
)

/** One rear lens the app can stream from. */
data class Lens(
    /** Camera to open. */
    val openId: String,
    /** Physical camera id inside a logical camera, or null for a standalone camera. */
    val physicalId: String?,
    val chars: CameraCharacteristics,
    val focalMm: Float,
    val equivalentMm: Float,
    val sensorOrientation: Int,
    /** Optical centre in the device frame (metres), from LENS_POSE_TRANSLATION. */
    val position: FloatArray?,
) {
    val key get() = physicalId?.let { "$openId/$it" } ?: openId
    fun yuvSizes(): List<Size> =
        chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
}

sealed interface CaptureMode {
    val label: String
    val primary: Lens

    data class Single(override val primary: Lens, override val label: String) : CaptureMode

    /** Two physical cameras of one logical camera, streamed by the same requests. */
    data class Rig(override val primary: Lens, val secondary: Lens, override val label: String) : CaptureMode
}

object CameraProbe {
    private const val FULL_FRAME_DIAGONAL_MM = 43.27f

    fun lenses(manager: CameraManager): List<Lens> {
        val out = mutableListOf<Lens>()
        val seenPhysical = mutableSetOf<String>()
        for (id in manager.cameraIdList) {
            val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
            val physical = if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) {
                c.physicalCameraIds
            } else emptySet()
            if (physical.size >= 2) {
                for (pid in physical) {
                    val pc = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                    lensOf(id, pid, pc)?.let { out += it; seenPhysical += pid }
                }
            }
            // Standalone cameras (and logical cameras themselves, which stream "auto" lens choice).
            if (id !in seenPhysical) lensOf(id, null, c)?.let { out += it }
        }
        return out
    }

    private fun lensOf(openId: String, physicalId: String?, c: CameraCharacteristics): Lens? {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val eq = focal * FULL_FRAME_DIAGONAL_MM / hypot(size.width, size.height)
        val pos = c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)?.takeIf { it.size == 3 }
        return Lens(openId, physicalId, c, focal, eq, c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, pos)
    }

    /** Capture modes, best first: a two-camera rig when available, then each lens on its own. */
    fun modes(lenses: List<Lens>): List<CaptureMode> {
        if (lenses.isEmpty()) return emptyList()
        // The "main" lens: a standalone or physical lens closest to a 26 mm equivalent.
        val singles = lenses.filter { it.yuvSizes().isNotEmpty() || it.physicalId != null }
        val main = singles.filter { it.physicalId == null }.minByOrNull { abs(it.equivalentMm - 26f) }
            ?: singles.minByOrNull { abs(it.equivalentMm - 26f) } ?: lenses.first()
        fun zoomLabel(l: Lens) = (l.equivalentMm / main.equivalentMm).let { z ->
            if (abs(z - 1f) < 0.08f) "1×" else if (z < 1f) "%.1f×".format(z) else "${z.roundToInt()}×"
        }
        val out = mutableListOf<CaptureMode>()
        // Rig: the main physical lens plus the widest other physical lens of the same logical camera.
        val byLogical = lenses.filter { it.physicalId != null }.groupBy { it.openId }
        for ((_, group) in byLogical) {
            val primary = group.minByOrNull { abs(it.equivalentMm - 26f) } ?: continue
            val secondary = group.filter { it !== primary }.minByOrNull { it.equivalentMm } ?: continue
            out += CaptureMode.Rig(primary, secondary, "${zoomLabel(primary)} + ${zoomLabel(secondary)}")
            break
        }
        // Single lenses by zoom label. A standalone (or logical) camera is preferred over a
        // physical stream of the same focal length: every device supports streaming it.
        for ((label, group) in singles.groupBy { zoomLabel(it) }.toSortedMap(compareBy { lbl -> singles.first { zoomLabel(it) == lbl }.equivalentMm })) {
            val pick = group.firstOrNull { it.physicalId == null } ?: group.first()
            out += CaptureMode.Single(pick, label)
        }
        // Rig first, then the main lens, then the others.
        return out.sortedBy { if (it is CaptureMode.Rig) 0 else if (it.primary === main) 1 else 2 }
    }

    /** Largest YUV size up to 1920 px wide, preferring 4:3, available on every given lens. */
    fun chooseSize(vararg lenses: Lens): Size {
        // Physical cameras may not publish their own stream map; they then follow the logical one.
        val maps = lenses.map { l -> l.yuvSizes().map { it.width to it.height }.toSet() }.filter { it.isNotEmpty() }
        val common = if (maps.isEmpty()) emptySet() else maps.reduce { a, b -> a intersect b }
        val candidates = common.filter { it.first <= 1920 && it.second <= 1920 }
        val best = candidates.filter { it.first * 3 == it.second * 4 }.maxByOrNull { it.first * it.second }
            ?: candidates.maxByOrNull { it.first * it.second }
            ?: (1280 to 960)
        return Size(best.first, best.second)
    }

    /**
     * Intrinsics of a [size] stream. Uses LENS_INTRINSIC_CALIBRATION (pre-correction active
     * array pixels) and LENS_DISTORTION when present, otherwise focal length / pixel pitch. The
     * stream is the active array, centre-cropped to the stream's aspect ratio and scaled.
     */
    fun intrinsics(lens: Lens, size: Size): Intrinsics {
        val c = lens.chars
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, size.width, size.height)
        val pre = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) ?: active
        val calib = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val dist = c.get(CameraCharacteristics.LENS_DISTORTION)
        val calibrated = calib != null && calib.size >= 4 && calib[0] > 1f && calib[1] > 1f
        var fx: Double
        var fy: Double
        var cxA: Double
        var cyA: Double
        if (calibrated) {
            fx = calib[0].toDouble(); fy = calib[1].toDouble()
            // Pre-correction coordinates -> active-array coordinates.
            cxA = calib[2] + (pre.left - active.left).toDouble()
            cyA = calib[3] + (pre.top - active.top).toDouble()
        } else {
            val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
            val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)!!
            fx = lens.focalMm / phys.width * pixels.width.toDouble()
            fy = lens.focalMm / phys.height * pixels.height.toDouble()
            cxA = active.width() / 2.0 - 0.5
            cyA = active.height() / 2.0 - 0.5
        }
        val aw = active.width().toDouble()
        val ah = active.height().toDouble()
        val streamAspect = size.width.toDouble() / size.height
        val (cropW, cropH) = if (streamAspect > aw / ah) aw to aw / streamAspect else ah * streamAspect to ah
        val ox = (aw - cropW) / 2
        val oy = (ah - cropH) / 2
        val s = size.width / cropW
        fx *= s; fy *= s
        val cx = (cxA - ox + 0.5) * s - 0.5
        val cy = (cyA - oy + 0.5) * (size.height / cropH) - 0.5
        // LENS_DISTORTION is [k1, k2, k3, p1, p2]; OpenCV wants (k1, k2, p1, p2, k3).
        return if (calibrated && dist != null && dist.size >= 5) {
            Intrinsics(size.width, size.height, fx, fy, cx, cy,
                dist[0].toDouble(), dist[1].toDouble(), dist[3].toDouble(), dist[4].toDouble(), dist[2].toDouble(), true)
        } else {
            Intrinsics(size.width, size.height, fx, fy, cx, cy, calibrated = calibrated)
        }
    }
}
