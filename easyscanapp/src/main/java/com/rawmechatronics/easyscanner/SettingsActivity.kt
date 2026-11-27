package com.rawmechatronics.easyscanner

import android.hardware.camera2.CameraManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.EditTextPreference
import androidx.preference.SwitchPreferenceCompat
import java.io.File

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padBelowBars(findViewById(android.R.id.content))
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, Fragment()).commit()
        }
    }

    class Fragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<androidx.preference.Preference>("calib_details")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), CalibrationActivity::class.java))
                true
            }
            val flood = File(requireContext().filesDir, "flood.txt")
            val floodValues = HashMap<String, String>()
            runCatching {
                if (flood.isFile) for (line in flood.readLines()) {
                    val p = line.split('=')
                    if (p.size == 2) floodValues[p[0].trim()] = p[1].trim()
                }
            }
            fun writeFlood() {
                runCatching {
                    flood.writeText(
                        "max_features=" + (floodValues["max_features"] ?: "100") + "\n" +
                            "min_dist=" + (floodValues["min_dist"] ?: "4") + "\n" +
                            "max_tracks=" + (floodValues["max_tracks"] ?: "100") + "\n" +
                            "reacq=" + (floodValues["reacq"] ?: "0") + "\n" +
                            "lg_threshold=" + (floodValues["lg_threshold"] ?: "0.05") + "\n",
                    )
                }
            }
            val specs = listOf(
                Triple("vio_max_features", "max_features", { v: String -> v.toIntOrNull()?.coerceIn(40, 400)?.toString() }),
                Triple("vio_min_dist", "min_dist", { v: String -> v.toFloatOrNull()?.coerceIn(2f, 60f)?.toString() }),
                Triple("vio_max_tracks", "max_tracks", { v: String -> v.toIntOrNull()?.coerceIn(20, 400)?.toString() }),
                Triple("vio_lg_threshold", "lg_threshold", { v: String -> v.toFloatOrNull()?.coerceIn(0.01f, 0.9f)?.toString() }),
            )
            for ((key, fileKey, normalize) in specs) {
                findPreference<EditTextPreference>(key)?.apply {
                    text = floodValues[fileKey]
                    setOnPreferenceChangeListener { _, value ->
                        val n = normalize(value?.toString() ?: return@setOnPreferenceChangeListener false)
                            ?: return@setOnPreferenceChangeListener false
                        floodValues[fileKey] = n
                        writeFlood()
                        true
                    }
                }
            }
            findPreference<SwitchPreferenceCompat>("lightglue_tracker")?.apply {
                val flag = File(requireContext().filesDir, "lightglue.txt")
                isChecked = !flag.exists() || flag.readText().trim() != "0"
                setOnPreferenceChangeListener { _, newValue ->
                    flag.writeText(if (newValue == true) "1" else "0")
                    true
                }
            }
            findPreference<SwitchPreferenceCompat>("klt_fallback")?.apply {
                val flag = File(requireContext().filesDir, "klt_fallback.txt")
                isChecked = !flag.exists() || flag.readText().trim() != "0"
                setOnPreferenceChangeListener { _, newValue ->
                    flag.writeText(if (newValue == true) "1" else "0")
                    true
                }
            }
            findPreference<androidx.preference.Preference>("tracking_selftest")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), TrackingSelfTestActivity::class.java))
                true
            }
            val mgr = requireContext().getSystemService(CameraManager::class.java)
            val fps = AppSettings.load(requireContext()).targetFps
            val cams = CameraInfo.backCameraIds(mgr).map { CameraInfo.query(mgr, it, fps, 0) }
            findPreference<ListPreference>("camera_id")?.apply {
                entries = cams.map { it.label }.toTypedArray()
                entryValues = cams.map { it.id }.toTypedArray()
                if (value == null && cams.isNotEmpty()) value = cams.first().id
            }
        }
    }
}
