package com.zynelabs.dzterminal

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.widget.SeekBar
import android.widget.TextView

/**
 * v1.0.42: Settings is back — MINIMAL by the user's design. Its
 * ONLY content is the Console font-size control: a slider, the
 * current size, and a live terminal-style preview line.
 *
 * Deliberately NOT here:
 * - Debug — removed for good in v1.0.41; do not resurrect it.
 * - About — it already lives in the Home gear menu; duplicating
 *   it here is exactly why the old Settings screen was deleted.
 *
 * The slider range is FontPrefs.MIN_SP..MAX_SP — the SAME bounds
 * and the SAME persisted value pinch zoom uses in the Console,
 * so the two always agree (whichever changed last wins). Moving
 * the slider applies + persists immediately; a running Console
 * picks the value up when it resumes (ConsoleActivity.onResume),
 * a fresh Console reads it at start.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val valueLabel = findViewById<TextView>(R.id.settings_font_value)
        val preview = findViewById<TextView>(R.id.settings_font_preview)
        val seek = findViewById<SeekBar>(R.id.settings_font_seek)

        fun show(sp: Int) {
            valueLabel.text = "$sp sp"
            preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        }

        val current = FontPrefs.getSp(this)
        seek.max = FontPrefs.MAX_SP - FontPrefs.MIN_SP
        seek.progress = current - FontPrefs.MIN_SP
        show(current)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // Applies + persists immediately (FontPrefs clamps
                // to the pinch bounds and returns the stored value).
                show(FontPrefs.setSp(this@SettingsActivity, FontPrefs.MIN_SP + progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }
}
