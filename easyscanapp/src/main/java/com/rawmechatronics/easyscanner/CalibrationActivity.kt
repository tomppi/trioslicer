package com.rawmechatronics.easyscanner

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows exactly what the camera model, laser plane and VIO are running with.
 * Read-only view of the files the engines load; plus a reset that makes the VIO
 * fall back to the calibrated defaults bundled in the APK.
 */
class CalibrationActivity : android.app.Activity() {

    private lateinit var body: TextView

    private val REQUEST_LOAD = 41

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

        val load = Button(this)
        load.text = "Load calibrations… (YAML / JSON)"
        load.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            try {
                startActivityForResult(i, REQUEST_LOAD)
            } catch (e: Exception) {
                Toast.makeText(this, "No file picker: " + e.message, Toast.LENGTH_LONG).show()
            }
        }
        col.addView(load)

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

    @Deprecated("Activity API from before the result contracts")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_LOAD || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
        if (text == null) {
            body.text = "Could not read the selected file.\n\n" + describe()
            Toast.makeText(this, "Could not read the selected file", Toast.LENGTH_LONG).show()
            return
        }
        val msg = applyLoadedCalibration(text)
        body.text = msg + "\n\n" + describe()
        Toast.makeText(this, msg.lineSequence().first(), Toast.LENGTH_LONG).show()
    }

    /**
     * Auto-detect and install any calibration file exported by `imucalib`:
     * - `live_intrinsics.json` (fresh AprilGrid pinhole-radtan intrinsics),
     * - `imu.yaml` / `kalibr_imu_chain.yaml` (stationary Allan-deviation IMU noise densities), or
     * - `kalibr_imucam_chain.yaml` (camera-IMU extrinsics + timeshift).
     */
    private fun applyLoadedCalibration(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            return applyLoadedIntrinsicsJson(trimmed)
        }
        val looksLikeImuOnly = trimmed.contains("accelerometer_noise_density") &&
            trimmed.contains("gyroscope_noise_density") &&
            !trimmed.contains("T_cam_imu")
        if (looksLikeImuOnly) {
            return applyLoadedImuNoiseYaml(trimmed)
        }
        val v = VioCalibrationProfile.validate(text)
        if (!v.ok) return "Load failed: " + v.message
        val dir = File(filesDir, "openvins-config")
        dir.mkdirs()
        val target = File(dir, "kalibr_imucam_chain.yaml")
        var backup: String? = null
        if (target.exists()) {
            val bak = File(dir, "kalibr_imucam_chain.yaml.bak-load-" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
            try {
                target.copyTo(bak, overwrite = true)
                backup = bak.name
            } catch (_: Exception) {
            }
        }
        return try {
            target.writeText(text)
            val sb = StringBuilder()
            sb.append("Loaded camera-IMU calibration profile.\n")
            sb.append("  ").append(v.message).append('\n')
            sb.append("  saved to files/openvins-config/kalibr_imucam_chain.yaml\n")
            if (backup != null) sb.append("  previous profile kept as ").append(backup).append('\n')
            sb.append("  Restart tracking (leave EasyScan and start the Scanner again) so the VIO reloads it.")
            sb.toString()
        } catch (e: Exception) {
            "Load failed while writing the profile: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun applyLoadedIntrinsicsJson(text: String): String {
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            return "Load failed: invalid JSON (${e.message})"
        }
        val fx = o.optDouble("fx", Double.NaN)
        val fy = o.optDouble("fy", Double.NaN)
        val cx = o.optDouble("cx", Double.NaN)
        val cy = o.optDouble("cy", Double.NaN)
        val w = o.optInt("width", 0)
        val h = o.optInt("height", 0)
        if (!fx.isFinite() || !fy.isFinite() || !cx.isFinite() || !cy.isFinite() ||
            fx <= 10.0 || fy <= 10.0 || w < 160 || h < 120
        ) {
            return "Load failed: live_intrinsics.json is missing valid fx/fy/cx/cy/width/height"
        }
        val target = File(filesDir, "live_intrinsics.json")
        if (target.exists()) {
            val bak = File(filesDir, "live_intrinsics.json.bak-" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
            runCatching { target.copyTo(bak, overwrite = true) }
        }
        o.put("calibrated", true)
        return try {
            target.writeText(o.toString(2))
            String.format(
                Locale.US,
                "Loaded calibrated camera intrinsics (%dx%d, fx=%.2f fy=%.2f cx=%.2f cy=%.2f).\n" +
                    "  saved to files/live_intrinsics.json\n" +
                    "  Restart tracking so VIO and the scanner pick up the new camera model.",
                w, h, fx, fy, cx, cy,
            )
        } catch (e: Exception) {
            "Load failed while writing live_intrinsics.json: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun applyLoadedImuNoiseYaml(text: String): String {
        fun grab(key: String): Double? {
            val m = Regex("(?m)^\\s*$key\\s*:\\s*([+-]?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)").find(text)
            return m?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        }
        val accNoise = grab("accelerometer_noise_density")
            ?: return "Load failed: missing positive accelerometer_noise_density"
        val accWalk = grab("accelerometer_random_walk")
            ?: return "Load failed: missing positive accelerometer_random_walk"
        val gyroNoise = grab("gyroscope_noise_density")
            ?: return "Load failed: missing positive gyroscope_noise_density"
        val gyroWalk = grab("gyroscope_random_walk")
            ?: return "Load failed: missing positive gyroscope_random_walk"
        val rate = grab("update_rate") ?: 480.0

        val dir = File(filesDir, "openvins-config").apply { mkdirs() }
        val target = File(dir, "kalibr_imu_chain.yaml")
        if (target.exists()) {
            val bak = File(dir, "kalibr_imu_chain.yaml.bak-load-" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
            runCatching { target.copyTo(bak, overwrite = true) }
        }
        val yaml = String.format(
            Locale.US,
            "%YAML:1.0\n\n" +
                "imu0:\n" +
                "  T_i_b:\n" +
                "    - [1.0, 0.0, 0.0, 0.0]\n" +
                "    - [0.0, 1.0, 0.0, 0.0]\n" +
                "    - [0.0, 0.0, 1.0, 0.0]\n" +
                "    - [0.0, 0.0, 0.0, 1.0]\n" +
                "  accelerometer_noise_density: %.9g\n" +
                "  accelerometer_random_walk: %.9g\n" +
                "  gyroscope_noise_density: %.9g\n" +
                "  gyroscope_random_walk: %.9g\n" +
                "  rostopic: /imu0\n" +
                "  time_offset: 0.0\n" +
                "  update_rate: %.2f\n" +
                "  model: \"kalibr\"\n" +
                "  Tw:\n    - [1.0, 0.0, 0.0]\n    - [0.0, 1.0, 0.0]\n    - [0.0, 0.0, 1.0]\n" +
                "  R_IMUtoGYRO:\n    - [1.0, 0.0, 0.0]\n    - [0.0, 1.0, 0.0]\n    - [0.0, 0.0, 1.0]\n" +
                "  Ta:\n    - [1.0, 0.0, 0.0]\n    - [0.0, 1.0, 0.0]\n    - [0.0, 0.0, 1.0]\n" +
                "  R_IMUtoACC:\n    - [1.0, 0.0, 0.0]\n    - [0.0, 1.0, 0.0]\n    - [0.0, 0.0, 1.0]\n" +
                "  Tg:\n    - [0.0, 0.0, 0.0]\n    - [0.0, 0.0, 0.0]\n    - [0.0, 0.0, 0.0]\n",
            accNoise, accWalk, gyroNoise, gyroWalk, rate,
        )
        return try {
            target.writeText(yaml)
            String.format(
                Locale.US,
                "Loaded IMU noise profile (%.0f Hz, acc=%.3e / rw=%.3e, gyro=%.3e / rw=%.3e).\n" +
                    "  saved to files/openvins-config/kalibr_imu_chain.yaml\n" +
                    "  Restart tracking so VIO reloads the IMU noise parameters.",
                rate, accNoise, accWalk, gyroNoise, gyroWalk,
            )
        } catch (e: Exception) {
            "Load failed while writing kalibr_imu_chain.yaml: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun launchCapture(kind: String) {
        val i = android.content.Intent(this, MainActivity::class.java)
        i.putExtra("calib_capture", kind)
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(i)
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
                t.startsWith("init_imu_thresh") || t.startsWith("up_msckf") ||
                t.startsWith("init_dyn_use") || t.startsWith("init_window_time") ||
                t.startsWith("try_zupt") || t.startsWith("zupt_max_disparity") ||
                t.startsWith("zupt_noise_multiplier") || t.startsWith("zupt_chi2_multipler")) {
                sb.append("  ").append(t).append('\n')
            }
        }

        val rec = java.io.File(getExternalFilesDir(null) ?: filesDir, "tracker-debug")
            .listFiles { f -> f.isDirectory }?.maxByOrNull { it.name }
        sb.append("\nCalibration recording\n")
        if (rec == null) sb.append("  none yet — use the capture buttons above\n")
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
        const val GUIDANCE = """Camera-IMU Calibration Profiles
Capture an AprilGrid + IMU ROS 1 .bag with the imucalib app, solve it offline with Kalibr on your PC, then tap "Load calibrations…" to install any of:
• *-camchain-imucam.yaml / kalibr_imucam_chain.yaml (camera-IMU extrinsics & timeshift)
• imu.yaml / kalibr_imu_chain.yaml (stationary Allan-deviation IMU noise parameters)
• live_intrinsics.json (camera intrinsics)

Each file is auto-detected, validated, and saved as active (backing up the previous profile). Restart EasyScan after loading or resetting so VIO reloads it."""
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
