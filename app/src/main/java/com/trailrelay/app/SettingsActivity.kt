package com.trailrelay.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import android.widget.LinearLayout
import android.widget.TextView
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
        val summary = findViewById<TextView>(R.id.heading_offset_summary)
        fun updateOffset(value: Int) {
            preferences.edit { putInt(HEADING_OFFSET, value) }
            summary.text = getString(R.string.heading_offset_summary, value)
        }
        summary.text = getString(R.string.heading_offset_summary, preferences.getInt(HEADING_OFFSET, 0))
        findViewById<android.view.View>(R.id.heading_offset_row).setOnClickListener {
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = resources.getDimensionPixelSize(R.dimen.space_content)
                setPadding(pad, 0, pad, 0)
            }
            content.addView(TextView(this).apply { setText(R.string.heading_offset_dialog) })
            val value = TextView(this)
            content.addView(value)
            val slider = Slider(this).apply {
                valueFrom = -180f; valueTo = 180f; stepSize = 1f
                this.value = preferences.getInt(HEADING_OFFSET, 0).coerceIn(-180, 180).toFloat()
            }
            value.text = getString(R.string.heading_offset_summary, slider.value.toInt())
            slider.addOnChangeListener { _, degrees, _ ->
                value.text = getString(R.string.heading_offset_summary, degrees.toInt())
            }
            content.addView(slider)
            MaterialAlertDialogBuilder(this).setTitle(R.string.heading_offset).setView(content)
                .setPositiveButton(android.R.string.ok) { _, _ -> updateOffset(slider.value.toInt()) }
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.reset_zero) { _, _ -> updateOffset(0) }.show()
        }
        findViewById<MaterialSwitch>(R.id.show_offline_coverage).apply {
            isChecked = preferences.getBoolean(SHOW_OFFLINE_COVERAGE, false)
            setOnCheckedChangeListener { _, checked -> preferences.edit { putBoolean(SHOW_OFFLINE_COVERAGE, checked) } }
        }
    }

    companion object {
        const val PREFERENCES = "trailrelay_preferences"
        const val KEEP_SCREEN_AWAKE = "keep_screen_awake"
        const val HEADING_OFFSET = "heading_offset"
        const val SHOW_OFFLINE_COVERAGE = "show_offline_coverage"
    }
}
