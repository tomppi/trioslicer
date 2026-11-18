package com.rawmechatronics.easyscanner

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

/**
 * Shows exactly what the camera model, laser plane and VIO are running with.
 * Read-only view of the files the engines load; plus a reset that makes the VIO
 * fall back to the calibrated defaults bundled in the APK.
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        col.setPadding(pad, pad, pad, pad)
        scroll.addView(col)

        val title = TextView(this)
        title.textSize = 18f
        title.text = "Calibration"
        col.addView(title)

        val guide = TextView(this)
        guide.textSize = 12f
        guide.setPadding(0, pad, 0, pad)
        guide.text = GUIDANCE
        col.addView(guide)

        body = TextView(this)
        body.textSize = 12f
        body.setPadding(0, pad, 0, pad)
        col.addView(body)

        val solve = Button(this)
        solve.text = "Solve & save from latest recording"
        solve.setOnClickListener {
            val res = CalibSolver.solve(this)
            val text = StringBuilder()
            text.append(if (res.ok) "OK: " else "FAILED: ").append(res.message).append("\n")
            for (l in res.lines) text.append(l).append('\n')
            body.text = text.toString() + "\n\n" + describe()
        }
        col.addView(solve)

        val refresh = Button(this)
        refresh.text = "Refresh"
        refresh.setOnClickListener { render() }
        col.addView(refresh)

        val reset = Button(this)
        reset.text = "Reset VIO calibration to defaults"
        reset.setOnClickListener {
            val dir = File(filesDir, "openvins-config")
            dir.listFiles()?.forEach { it.delete() }
            body.text = "VIO calibration reset. Restart tracking to reload the bundled defaults.\n\n" + describe()
        }
        col.addView(reset)

        setContentView(scroll)
        render()
    }

    private fun render() {
        body.text = describe()
    }

    private fun describe(): String {
        val sb = StringBuilder()
        sb.append("Camera (engine model, live_intrinsics.json)\n")
        val cam = readJson(File(filesDir, "live_intrinsics.json"))
        if (cam == null) {
            sb.append("  not present (vendor defaults in use)\n")
        } else {
            sb.append("  fx %.2f  fy %.2f  cx %.2f  cy %.2f\n".format(
                cam.optDouble("fx"), cam.optDouble("fy"), cam.optDouble("cx"), cam.optDouble("cy")))
            sb.append("  dist %s  size %dx%d  rms %s\n".format(
                cam.optJSONArray("dist")?.toString() ?: "?",
                cam.optInt("width"), cam.optInt("height"), cam.opt("rms_px") ?: "-"))
            sb.append("  source %s  focus %s\n".format(
                if (cam.optBoolean("calibrated")) "board" else "vendor",
                cam.opt("focus_diopters") ?: "-"))
        }

        sb.append("\nLaser plane (laser_plane.json)\n")
        val plane = readJson(File(filesDir, "laser_plane.json"))
        if (plane == null) {
            sb.append("  not calibrated\n")
        } else {
            sb.append("  n %s\n  d %s  rms %s\n".format(
                plane.optJSONArray("normal")?.toString() ?: "?",
                plane.opt("d") ?: "?", plane.opt("rms") ?: "-"))
            for (key in plane.keys()) {
                if (key !in setOf("normal", "n", "d", "rms")) sb.append("  ").append(key).append(": ").append(plane.opt(key)).append('\n')
            }
        }

        sb.append("\nVIO camera-IMU (kalibr_imucam_chain.yaml)\n")
        for (line in yamlLines("kalibr_imucam_chain.yaml")) {
            if (line.contains("T_cam_imu") || line.startsWith("    - [") || line.contains("distortion_coeffs") ||
                line.contains("intrinsics") || line.contains("timeshift") || line.contains("resolution")) {
                sb.append("  ").append(line.trim()).append('\n')
            }
        }

        sb.append("\nVIO IMU noise (kalibr_imu_chain.yaml)\n")
        for (line in yamlLines("kalibr_imu_chain.yaml")) {
            if (line.contains("noise_density") || line.contains("random_walk") || line.contains("update_rate")) {
                sb.append("  ").append(line.trim()).append('\n')
            }
        }

        sb.append("\nVIO estimator (estimator_config.yaml, selected)\n")
        for (line in yamlLines("estimator_config.yaml")) {
            val t = line.trim()
            if (t.startsWith("use_fej") || t.startsWith("calib_cam") || t.startsWith("try_zupt") ||
                t.startsWith("max_slam") || t.startsWith("max_msckf") || t.startsWith("num_pts") ||
                t.startsWith("track_frequency") || t.startsWith("init_max_disparity") ||
                t.startsWith("init_imu_thresh") || t.startsWith("up_msckf")) {
                sb.append("  ").append(t).append('\n')
            }
        }

        val rec = java.io.File(getExternalFilesDir(null) ?: filesDir, "tracker-debug")
            .listFiles { f -> f.isDirectory }?.maxByOrNull { it.name }
        sb.append("\nCalibration recording\n")
        if (rec == null) sb.append("  none yet — use Scan step -> Calib still / Calib board 30s\n")
        else {
            val csv = java.io.File(rec, "frames.csv")
            val rows = if (csv.exists()) csv.readLines().size - 1 else 0
            sb.append("  newest: ").append(rec.name).append(", frame rows: ").append(rows).append('\n')
        }

        sb.append("\nVIO model file directory: files/openvins-config\n")
        val dir = File(filesDir, "openvins-config")
        val names = dir.list() ?: emptyArray()
        sb.append("  files: ").append(if (names.isEmpty()) "none (defaults bundled in APK)" else names.joinToString(", ")).append('\n')
        return sb.toString()
    }

    private companion object {
        const val GUIDANCE = """How to calibrate the marker-free tracker
1. Camera + focus: fix the focus (Camera step) and lock it. The camera model
   (live_intrinsics.json) must show a board rms, ideally under 0.3 px.
2. IMU noise: put the phone on a table, do not touch it for 30 s. (Recording
   step — runs the IMU statistics.)
3. VIO camera-IMU: stand 30-60 cm from the ChArUco board. Press record, then for
   20-30 s move the phone in 3D: side to side, up/down, toward/away, and tilt
   yaw/pitch/roll +-30-45 deg, letting the board reach the image edges. Keep the
   whole board visible; avoid fast blur.
4. Scale check (optional, two boards): with boards ~1 m apart on the wall, walk
   slowly from one to the other and back (20-30 s), keeping a board in view.
5. Solve: the phone computes R_cam_imu, camera-IMU time offset, gyro bias, IMU
   noise and the VIO/board scale ratio, writes the kalibr files and shows the
   residuals (rotation rms in deg, time offset in ms, scale error %).
6. Verify: record one more board pass; scale error should stay near 0 % and
   rotation rms under ~0.5 deg. Then scan marker-free with Track VIO.

How to record the calibration data on this phone
- Scan step has two buttons: "Calib still 30s" (put the phone down, don't touch)
  and "Calib board 30s" (hold still 1 s, then move in front of the board).
- For the scale check, enable Track VIO before "Calib board 30s"; the solver then
  also compares the board path with the VIO path and prints the scale error.
- Two boards ~1 m apart: first record one board (calibration), then optionally
  walk from one board to the other and back while recording a second "Calib
  board 30s" pass to check scale/drift over the longer baseline.
- Then press "Solve & save from latest recording": the solver reads the newest
  session with enough board frames, computes R_cam_imu, time offset and IMU
  noise, writes them to files/openvins-config, and shows the residuals."""
    }

    private fun readJson(f: File): JSONObject? {
        if (!f.exists()) return null
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun yamlLines(name: String): List<String> {
        val dir = File(filesDir, "openvins-config")
        val f = File(dir, name)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
