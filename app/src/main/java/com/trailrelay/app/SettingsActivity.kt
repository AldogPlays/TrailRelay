package com.trailrelay.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.materialswitch.MaterialSwitch
import com.trailrelay.app.ui.ContentShell

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContentShell.install(this, R.string.settings, R.layout.activity_settings, R.id.settings_root)
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        findViewById<MaterialSwitch>(R.id.keep_screen_awake).apply {
            isChecked = preferences.getBoolean(KEEP_SCREEN_AWAKE, false)
            setOnCheckedChangeListener { _, checked -> preferences.edit { putBoolean(KEEP_SCREEN_AWAKE, checked) } }
        }
    }

    companion object {
        const val PREFERENCES = "trailrelay_preferences"
        const val KEEP_SCREEN_AWAKE = "keep_screen_awake"
    }
}
