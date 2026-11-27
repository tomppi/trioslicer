package com.rawmechatronics.easyscanner

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

class CaptureModesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun wizardAllowsSplatWithoutLaserCalibrationAndLocksLaserModes() {
        val w = Wizard()
        val uncalibrated = Readiness(
            boardConfirmed = true,
            focusLocked = false,
            calibrated = true,
            planeReady = false,
        )
        assertEquals(Step.FOCUS, w.update(uncalibrated))
        // Fusion and Laser Only require focus + laser plane.
        assertFalse(w.open(Step.FUSION, uncalibrated))
        assertFalse(w.open(Step.LASER_ONLY, uncalibrated))
        // Splat is always open even before calibration, and update() keeps it active.
        assertTrue(w.open(Step.SPLAT, uncalibrated))
        assertEquals(Step.SPLAT, w.step)
        assertEquals(Step.SPLAT, w.update(uncalibrated))

        val ready = Readiness(
            boardConfirmed = true,
            focusLocked = true,
            calibrated = true,
            planeReady = true,
        )
        assertTrue(w.open(Step.LASER_ONLY, ready))
        assertEquals(Step.LASER_ONLY, w.update(ready))

        // Finishing and starting a new scan returns to the mode just used.
        w.finished()
        assertEquals(Step.DONE, w.step)
        w.newScan(ready, returnTo = Step.LASER_ONLY)
        assertEquals(Step.LASER_ONLY, w.step)

        w.finished()
        w.newScan(uncalibrated, returnTo = Step.SPLAT)
        assertEquals(Step.SPLAT, w.step)
    }

    @Test
    fun splatCaptureSessionMotionTriggersAndWritesValidManifest() {
        val dir = tmp.newFolder("splat-session")
        val session = SplatCaptureSession(dir, maxPhotos = 10)

        fun poseAt(x: Double, yawDeg: Double = 0.0): RigidPose {
            val rad = Math.toRadians(yawDeg)
            val c = cos(rad); val s = sin(rad)
            val r = doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
            // world -> camera: t = -R * C
            val t = doubleArrayOf(-(r[0] * x), -(r[3] * x), 0.0)
            return RigidPose(r, t)
        }
        fun pose16(p: RigidPose): DoubleArray = doubleArrayOf(
            p.r[0], p.r[1], p.r[2], p.t[0],
            p.r[3], p.r[4], p.r[5], p.t[1],
            p.r[6], p.r[7], p.r[8], p.t[2],
            0.0, 0.0, 0.0, 1.0,
        )

        val p0 = poseAt(0.0)
        // First capture triggers immediately.
        assertTrue(session.shouldAutoCapture(1000L, p0))
        // While in flight, a second call must NOT overlap.
        assertFalse(session.shouldAutoCapture(1500L, poseAt(0.10)))

        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val e0 = session.recordStill(Still(fakeJpeg, 1_000_000L, 1920, 1440), pose16(p0))
        assertNotNull(e0)
        assertEquals(1, session.savedCount)

        // Small 1 cm movement should NOT trigger.
        assertFalse(session.shouldAutoCapture(2000L, poseAt(0.01)))
        // 5 cm movement (>= 4.5 cm) DOES trigger.
        assertTrue(session.shouldAutoCapture(2000L, poseAt(0.05)))
        val e1 = session.recordStill(Still(fakeJpeg, 2_000_000L, 1920, 1440), pose16(poseAt(0.05)))
        assertNotNull(e1)
        assertEquals(2, session.savedCount)

        // 12 deg rotation in place (>= 9 deg) DOES trigger.
        assertTrue(session.shouldAutoCapture(3000L, poseAt(0.05, yawDeg = 12.0)))
        session.onStillFailed()

        // Without VIO (pose == null), timed fallback triggers after >= 950 ms.
        assertFalse(session.shouldAutoCapture(3500L, null))
        assertTrue(session.shouldAutoCapture(4100L, null))
        session.onStillFailed()

        val manifest = session.writeManifest("0")
        assertTrue(manifest.isFile)
        val json = manifest.readText()
        assertTrue(json.contains("\"mode\": \"splat_only\""))
        assertTrue(json.contains("\"width\": 1920"))
        assertTrue(json.contains("\"height\": 1440"))
        assertTrue(json.contains("\"photo_count\": 2"))
        assertTrue(json.contains("\"splat_photos/s0000.jpg\""))
        assertTrue(json.contains("\"splat_photos/s0001.jpg\""))
        assertTrue(File(dir, "splat_photos/s0000.jpg").isFile)
        assertTrue(File(dir, "splat_photos/s0001.jpg").isFile)
    }

    @Test
    fun laserOnlyExporterWritesMetricPlyAndSplatFromVioPoses() {
        val sessionDir = tmp.newFolder("laser-only-session")
        val filesDir = tmp.newFolder("app-files")
        val laserDir = File(sessionDir, "laser").apply { mkdirs() }

        // Frame 0 at identity, frame 1 shifted +0.10 m along X (t_x = -0.10).
        val csv = File(sessionDir, "frames.csv")
        csv.writeText(
            "frame,ts_ns,scan_on,detected,corners,cam_x,cam_y,cam_z,roll,pitch,yaw,distance,reproj_rms,fx,fy,cx,cy,laser_state,laser_points,proc_ms,laser_cam_count,pose16,vio_pose16,jpeg\n" +
                "0,1000,1,0,0,0,0,0,0,0,0,400,0,500,500,320,240,2,2,5.0,2,,1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1,\n" +
                "1,2000,1,0,0,0,0,0,0,0,0,400,0,500,500,320,240,2,2,5.0,2,,1 0 0 -0.1 0 1 0 0 0 0 1 0 0 0 0 1,\n",
        )
        fun writeF32(name: String, ptsMm: FloatArray) {
            val bb = ByteBuffer.allocate(ptsMm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in ptsMm) bb.putFloat(v)
            File(laserDir, name).writeBytes(bb.array())
        }
        writeF32("f00000.f32", floatArrayOf(0f, 0f, 400f, 10f, 0f, 400f))
        writeF32("f00001.f32", floatArrayOf(0f, 0f, 400f, 10f, 0f, 400f))

        val res = LaserOnlyExporter.export(sessionDir, filesDir)
        assertEquals(2, res.frames)
        assertEquals(4, res.points)
        assertEquals("vio", res.poseSource)
        assertTrue(res.plyFile.isFile && res.plyFile.length() > 60L)
        assertNotNull(res.splatName)
        assertTrue(File(filesDir, "splats/${res.splatName}").isFile)
    }
}
