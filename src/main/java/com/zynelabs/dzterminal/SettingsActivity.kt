package com.zynelabs.dzterminal

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/**
 * Settings: Debug + About. Reached from Home's 3-dot menu.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.settings_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.settings_version).text =
            "DZ Terminal " + DebugActivity.appVersion(this)
        findViewById<Button>(R.id.settings_debug).setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }
        findViewById<Button>(R.id.settings_about).setOnClickListener {
            AboutDialog.show(this)
        }
    }
}
