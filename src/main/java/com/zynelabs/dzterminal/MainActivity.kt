package com.zynelabs.dzterminal

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.PopupMenu
import android.widget.Toast

/**
 * Home screen — QPython-home-style layout:
 * big center ">_" terminal button + 5 cards.
 */
class MainActivity : Activity() {

    /** Export/Import action waiting on the storage-permission grant. */
    private var pendingFileAction: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<Button>(R.id.home_terminal_button).setOnClickListener {
            startActivity(Intent(this, ConsoleActivity::class.java))
        }
        findViewById<Button>(R.id.home_menu).setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add("Settings")
            popup.menu.add("About")
            // v1.0.28: Export / Import home files <-> shared
            // storage (file-manager visibility). Lives in the
            // Home 3-dot menu — the most discoverable spot.
            popup.menu.add("Export to file manager")
            popup.menu.add("Import from file manager")
            popup.setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Settings" -> startActivity(Intent(this, SettingsActivity::class.java))
                    "About" -> showAbout()
                    "Export to file manager" -> runFileAction("export")
                    "Import from file manager" -> runFileAction("import")
                }
                true
            }
            popup.show()
        }
        card(R.id.card_console).setOnClickListener {
            startActivity(Intent(this, ConsoleActivity::class.java))
        }
        card(R.id.card_editor).setOnClickListener {
            startActivity(Intent(this, EditorActivity::class.java))
        }
        card(R.id.card_programs).setOnClickListener {
            startActivity(Intent(this, ProgramsActivity::class.java))
        }
        card(R.id.card_qpypi).setOnClickListener {
            startActivity(Intent(this, QpypiActivity::class.java))
        }
        card(R.id.card_community).setOnClickListener {
            startActivity(Intent(this, CommunityActivity::class.java))
        }
    }

    private fun card(id: Int): View = findViewById(id)

    /**
     * v1.0.28: Export / Import home files <-> shared storage.
     * Permission: same ask-once rule as ConsoleActivity — the
     * shared prefs file + key are shared, so the prompt fires
     * at most once across Home + Console. If denied, no
     * re-prompt: a hint Toast only.
     */
    private fun runFileAction(action: String) {
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runFileActionInBackground(action)
            return
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_STORAGE_PERM_ASKED, false)) {
            prefs.edit().putBoolean(KEY_STORAGE_PERM_ASKED, true).apply()
            pendingFileAction = action
            Toast.makeText(
                this,
                "DZ Terminal needs Files and media permission to reach your shared storage /sdcard",
                Toast.LENGTH_LONG
            ).show()
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                STORAGE_PERM_REQUEST
            )
        } else {
            Toast.makeText(
                this,
                "grant Files and media in App info to use Export / Import",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != STORAGE_PERM_REQUEST) return
        val action = pendingFileAction
        pendingFileAction = null
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted && action != null) {
            runFileActionInBackground(action)
        } else if (!granted) {
            Toast.makeText(
                this,
                "grant Files and media in App info to use Export / Import",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Runs the copy on a background thread with a progress dialog, then shows the result. */
    private fun runFileActionInBackground(action: String) {
        val progress = AlertDialog.Builder(this)
            .setMessage(if (action == "export") "Exporting files…" else "Importing files…")
            .setCancelable(false)
            .create()
        progress.show()
        Thread {
            try {
                val app = application as DzTerminalApp
                if (action == "export") {
                    val result = HomeFileSync.exportHome(app)
                    runOnUiThread {
                        progress.dismiss()
                        AlertDialog.Builder(this)
                            .setTitle("Export done")
                            .setMessage(result.summary())
                            .setPositiveButton("Open in file manager") { _, _ -> openExportFolder() }
                            .setNegativeButton("OK", null)
                            .show()
                    }
                } else {
                    val result = HomeFileSync.importFromShared(app)
                    runOnUiThread {
                        progress.dismiss()
                        AlertDialog.Builder(this)
                            .setTitle("Import done")
                            .setMessage(result.summary())
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("Failed")
                        .setMessage(e.message ?: e.javaClass.simpleName)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    /**
     * Opens the system file manager at /storage/emulated/0/DZTerminal/
     * via the DocumentsUI content URI. Falls back to a Toast with
     * the path when no handler exists.
     */
    private fun openExportFolder() {
        try {
            val uri = Uri.parse(
                "content://com.android.externalstorage.documents/document/primary%3ADZTerminal"
            )
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Open Files and go to ${HomeFileSync.EXPORT_DIR_DISPLAY}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showAbout() {
        AboutDialog.show(this)
    }

    companion object {
        /** Shared with ConsoleActivity: the storage-permission ask-once flag lives here. */
        private const val PREFS = "dz_terminal_prefs"
        private const val KEY_STORAGE_PERM_ASKED = "storage_perm_asked_v1027"
        private const val STORAGE_PERM_REQUEST = 1004
    }
}
