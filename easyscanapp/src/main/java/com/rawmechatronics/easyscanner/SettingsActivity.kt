package com.rawmechatronics.easyscanner

import android.hardware.camera2.CameraManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat

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
