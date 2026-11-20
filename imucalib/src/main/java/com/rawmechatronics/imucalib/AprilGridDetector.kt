package com.rawmechatronics.imucalib

import android.media.Image
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import org.opencv.objdetect.RefineParameters
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * AprilGrid detection for the camera-IMU calibration target.
 *
 * The scanner engine only knows ChArUco, so imucalib carries its own AprilTag
 * detector (OpenCV APRILTAG_36h11) and solves the tag corners against the known
 * Kalibr AprilGrid geometry. The target used here is 6x6 tags, 22 mm tags,
 * tagSpacing 0.3 (the A4 /root/imu-calib-target PDF).
 */
class AprilGridDetector(
    private val tagCols: Int = 6,
    private val tagRows: Int = 6,
    private val tagSizeM: Double = 0.022,
    private val tagSpacing: Double = 0.3,
    private val fx: Double,
    private val fy: Double,
    private val cx: Double,
    private val cy: Double,
    dist: DoubleArray,
) {
    data class Corner(val u: Float, val v: Float, val x: Double, val y: Double)

    data class Frame(
        val tsNs: Long,
        val tags: Int,
        val pose16: DoubleArray?,
        val rms: Double,
        val corners: List<Corner>,
    )

    @Volatile var last: Frame? = null
        private set
    @Volatile var lastTags: Int = 0
        private set

    private val k = Mat(3, 3, CvType.CV_64F).apply {
        put(0, 0, fx); put(0, 2, cx)
        put(1, 1, fy); put(1, 2, cy)
        put(2, 2, 1.0)
    }
    private val d = MatOfDouble(*dist.copyOf(4))
    // OpenCV's default adaptive-threshold window is too large for the 1440x1080 stream:
    // it detected 0 tags on a perfectly visible target (the 640x480 stream was fine).
    // Small windows find 29 of 36 tags at 1440x1080; subpixel refinement keeps the corners.
    private val detector = ArucoDetector(
        Objdetect.getPredefinedDictionary(Objdetect.DICT_APRILTAG_36h11),
        DetectorParameters().apply {
            set_adaptiveThreshWinSizeMin(3)
            set_adaptiveThreshWinSizeMax(23)
            set_adaptiveThreshWinSizeStep(2)
            set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX)
        },
        RefineParameters(),
    )

    /** Copy the camera Y plane into an 8-bit single-channel Mat. */
    fun gray(img: Image): Mat {
        val w = img.width
        val h = img.height
        val plane = img.planes[0]
        val out = ByteArray(w * h)
        // The native engine has already read this buffer; copy from a clear duplicate so its
        // position/limit cannot make the row reads underflow.
        val buf = plane.buffer.duplicate()
        buf.clear()
        for (row in 0 until h) {
            buf.position(row * plane.rowStride)
            buf.get(out, row * w, w)
        }
        return Mat(h, w, CvType.CV_8UC1).apply { put(0, 0, out) }
    }

    fun detect(gray: Mat, tsNs: Long): Frame {
        val cornerMats = ArrayList<Mat>()
        val ids = Mat()
        detector.detectMarkers(gray, cornerMats, ids)
        val count = minOf(cornerMats.size, ids.total().toInt())
        val idArr = IntArray(ids.total().toInt())
        if (idArr.isNotEmpty()) ids.get(0, 0, idArr)

        // Kalibr's AprilGrid tag id is row-major; tag corners are ordered TL,TR,BR,BL
        // in the board frame. OpenCV returns AprilTag corners in a different order
        // (measured: detected[0..3] = TR,TL,BL,BR), so map them explicitly.
        val cornerOrder = intArrayOf(1, 0, 3, 2)
        val obj = ArrayList<Point3>()
        val imgPts = ArrayList<Point>()
        val corners = ArrayList<Corner>()
        var tags = 0
        for (i in 0 until count) {
            val id = idArr[i]
            if (id < 0 || id >= tagCols * tagRows) continue
            val buf = FloatArray(8)
            cornerMats[i].get(0, 0, buf)
            val r = id / tagCols
            val c = id % tagCols
            val x0 = c * (1.0 + tagSpacing) * tagSizeM
            val y0 = r * (1.0 + tagSpacing) * tagSizeM
            val board = arrayOf(
                doubleArrayOf(x0, y0),
                doubleArrayOf(x0 + tagSizeM, y0),
                doubleArrayOf(x0 + tagSizeM, y0 + tagSizeM),
                doubleArrayOf(x0, y0 + tagSizeM),
            )
            tags++
            for (k in 0 until 4) {
                val b = board[cornerOrder[k]]
                val u = buf[2 * k]
                val v = buf[2 * k + 1]
                obj.add(Point3(b[0], b[1], 0.0))
                imgPts.add(Point(u.toDouble(), v.toDouble()))
                corners.add(Corner(u, v, b[0], b[1]))
            }
        }

        var pose16: DoubleArray? = null
        var rms = Double.NaN
        if (obj.size >= 8) {
            val op = MatOfPoint3f(); op.fromList(obj)
            val ip = MatOfPoint2f(); ip.fromList(imgPts)
            val rvec = Mat(); val tvec = Mat()
            val ok = Calib3d.solvePnP(op, ip, k, d, rvec, tvec, false, Calib3d.SOLVEPNP_IPPE)
            if (ok) {
                val proj = MatOfPoint2f()
                // projectPoints wants a Mat for the image points; use the MatOfPoint2f overload
                Calib3d.projectPoints(op, rvec, tvec, k, d, proj)
                val p = proj.toArray()
                var ss = 0.0
                for (j in 0 until obj.size) {
                    val du = p[j].x - imgPts[j].x
                    val dv = p[j].y - imgPts[j].y
                    ss += du * du + dv * dv
                }
                rms = kotlin.math.sqrt(ss / obj.size)
                val rot = Mat()
                Calib3d.Rodrigues(rvec, rot)
                val rr = DoubleArray(9)
                rot.get(0, 0, rr)
                val tt = DoubleArray(3)
                tvec.get(0, 0, tt)
                pose16 = doubleArrayOf(
                    rr[0], rr[1], rr[2], tt[0],
                    rr[3], rr[4], rr[5], tt[1],
                    rr[6], rr[7], rr[8], tt[2],
                    0.0, 0.0, 0.0, 1.0,
                )
            }
        }
        val frame = Frame(tsNs, tags, pose16, rms, corners)
        last = frame
        lastTags = tags
        return frame
    }
}

/** Writes the AprilGrid frames/points in the formats CalibrationSession reads. */
class AprilGridRecorder(dir: File) {
    private val frames = BufferedWriter(OutputStreamWriter(FileOutputStream(File(dir, "april_frames.csv")), Charsets.UTF_8))
    private val points = BufferedWriter(OutputStreamWriter(FileOutputStream(File(dir, "april_points.csv")), Charsets.UTF_8))
    private var index = 0

    init {
        frames.write("frame,ts_ns,scan_on,detected,corners,cam_x,cam_y,cam_z,roll,pitch,yaw,distance,reproj_rms,fx,fy,cx,cy,laser_state,laser_points,proc_ms,laser_cam_count,pose16,vio_pose16,jpeg\n")
        frames.flush()
        points.write("frame,ts_ns,x,y,z,u,v\n")
        points.flush()
    }

    @Synchronized
    fun write(f: AprilGridDetector.Frame) {
        val idx = index++
        val pose = f.pose16
        val row = StringBuilder(256)
        row.append(idx).append(',').append(f.tsNs).append(',')
            .append("0.0,")                       // scan_on (col 2)
            .append(if (pose != null) "1.0," else "0.0,") // detected (3)
            .append(f.tags).append(',')             // corners (4)
            .append("NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,") // cam + euler + distance + rms (5..12)
            .append("NaN,NaN,NaN,NaN,")             // fx fy cx cy (13..16)
            .append("0,0,0,0,")                     // laser columns (17..20)
        if (pose != null) for (k in 0 until 16) { if (k > 0) row.append(' '); row.append(pose[k]) }
        row.append(",, \n")
        frames.write(row.toString())
        if (pose != null) {
            for (c in f.corners) {
                points.write(idx.toString() + "," + f.tsNs + "," + c.x + "," + c.y + ",0.0," + c.u + "," + c.v + "\n")
            }
        }
        if (idx % 10 == 0) {
            frames.flush(); points.flush()
        }
    }

    @Synchronized
    fun close() {
        runCatching { frames.flush(); frames.close() }
        runCatching { points.flush(); points.close() }
    }
}
