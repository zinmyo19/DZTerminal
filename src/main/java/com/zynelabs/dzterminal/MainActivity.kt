package com.zynelabs.dzterminal

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import kotlin.math.floor
import kotlin.math.min

/**
 * Home screen — QPython-home-style layout:
 * big center shield logo button + shield cards.
 * v1.0.49: TEN wide cards (2 x 5) per the approved mockup.
 * v1.0.31: shield Home per the approved final mockup.
 * The Community card was removed from Home (CommunityActivity
 * still exists, just unreachable from Home — reversible).
 */
class MainActivity : Activity() {

    /** Export/Import action waiting on the storage-permission grant. */
    private var pendingFileAction: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<ImageView>(R.id.home_terminal_button).setOnClickListener {
            startActivity(Intent(this, ConsoleActivity::class.java))
        }
        // v1.0.42: "Settings" is back in the gear menu — MINIMAL:
        // only the Console font-size control (slider). No Debug,
        // no About inside it (About stays here in the menu).
        findViewById<View>(R.id.home_gear).setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add("Settings")
            popup.menu.add("About")
            // v1.0.28: Export / Import home files <-> shared
            // storage (file-manager visibility). Lives in the
            // Home menu — the most discoverable spot.
            popup.menu.add("Export to file manager")
            popup.menu.add("Import from file manager")
            popup.setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Settings" ->
                        startActivity(Intent(this, SettingsActivity::class.java))
                    "About" -> showAbout()
                    "Export to file manager" -> runFileAction("export")
                    "Import from file manager" -> runFileAction("import")
                }
                true
            }
            popup.show()
        }
        // v1.0.50: the old Console card duplicated the big shield
        // (both opened the Console) — it is now the Sessions card.
        // The shield still opens the Console directly.
        card(R.id.card_sessions).setOnClickListener {
            startActivity(Intent(this, SessionsActivity::class.java))
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
        // v1.0.49 batch 1: Quick Run / Snippets / Backup screens.
        card(R.id.card_quickrun).setOnClickListener {
            startActivity(Intent(this, QuickRunActivity::class.java))
        }
        card(R.id.card_snippets).setOnClickListener {
            startActivity(Intent(this, SnippetsActivity::class.java))
        }
        card(R.id.card_backup).setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }
        // Batch 2/3 cards are visible per the approved mockup;
        // their screens land in the next builds.
        for (id in intArrayOf(R.id.card_clone, R.id.card_pkg, R.id.card_qr)) {
            card(id).setOnClickListener {
                Toast.makeText(this, "Coming in the next update", Toast.LENGTH_SHORT).show()
            }
        }
        // v1.0.44: scale the shield block to fit the window
        // (see fitHomeToScreen). Runs on every layout pass but
        // only touches layout params when a size actually
        // changes, and is exactly the v1.0.43 design on tall
        // screens, so it settles after the first pass.
        findViewById<View>(R.id.home_root).viewTreeObserver
            .addOnGlobalLayoutListener { fitHomeToScreen() }
    }

    private fun card(id: Int): View = findViewById(id)

    /**
     * v1.0.44: RESPONSIVE Home. The v1.0.43 layout was a fixed
     * ~637dp stack; on a short screen (~560-600dp usable, e.g.
     * the wife's Oppo, even at the smallest system display
     * size) it overflowed — the Programs/QPYPI row was clipped
     * and the gear row (the only path to Settings/About/
     * Export/Import) ended up at the very bottom edge,
     * untappable.
     *
     * Construction (activity_home.xml): the gear row is the
     * root's LAST child and the middle column (home_content)
     * is layout_height=0dp + layout_weight=1, so the gear sits
     * at the bottom of the window BY CONSTRUCTION and the
     * middle absorbs all size error. This method then scales
     * the middle's flexible block — big shield (200x220) +
     * card rows (v1.0.49: five wide rows of 158x80) — to fit
     * the middle column's
     * MEASURED height: scale = (content height - the column's
     * fixed padding/gaps) / design, clamped to [0.62, 1.0].
     * Width and height scale by the SAME factor, so shields
     * shrink proportionally and never squash (the ImageView
     * is fitCenter; card backgrounds stretch with their views
     * at the preserved aspect). A width guard covers narrow
     * screens. On tall screens the scale is exactly 1.0 and
     * the sizes written are the v1.0.43 design sizes; the
     * freed space collects below the cards, above the
     * bottom-pinned gear — sizes never stretch.
     */
    private fun fitHomeToScreen() {
        val content = findViewById<View>(R.id.home_content)
        val availH = content.height
        if (availH <= 0) return
        val density = resources.displayMetrics.density
        fun dpF(v: Float) = v * density

        fun bottomMargin(id: Int) =
            (findViewById<View>(id).layoutParams as LinearLayout.LayoutParams).bottomMargin

        // Fixed inside the middle column (px): its vertical
        // padding + the four 4dp row gaps (rows 1-4). Header
        // and gear row are NOT in this math — they are outside
        // the weighted column and fit by construction.
        val fixedPx = content.paddingTop + content.paddingBottom +
            bottomMargin(R.id.home_row1) + bottomMargin(R.id.home_row2) +
            bottomMargin(R.id.home_row3) + bottomMargin(R.id.home_row4)
        // Flexible block at design size (px): shield 220 +
        // five wide card rows of 80 (v1.0.49 ten-card Home).
        val designFlexPx = dpF(220f + 80f * 5)
        var scale = (availH - fixedPx) / designFlexPx
        // Narrow-screen guard: card row = 2 wide cards +
        // 12dp gap, big shield = 200dp, both inside the
        // column padding.
        val availW = content.width - content.paddingStart - content.paddingEnd
        if (availW > 0) {
            scale = min(scale, availW / dpF(158f * 2 + 12f))
            scale = min(scale, availW / dpF(200f))
        }
        val s = scale.coerceIn(0.62f, 1.0f)
        // Floor (never round) so the scaled stack can never
        // exceed the available height by a rounding pixel.
        setSize(
            R.id.home_terminal_button,
            floor(dpF(200f) * s).toInt(), floor(dpF(220f) * s).toInt()
        )
        val cardW = floor(dpF(158f) * s).toInt()
        val cardH = floor(dpF(80f) * s).toInt()
        for (id in intArrayOf(
            R.id.card_sessions, R.id.card_editor,
            R.id.card_programs, R.id.card_qpypi,
            R.id.card_quickrun, R.id.card_snippets,
            R.id.card_clone, R.id.card_pkg,
            R.id.card_backup, R.id.card_qr
        )) {
            setSize(id, cardW, cardH)
        }
    }

    /** Writes a pixel size only when it actually changes (keeps the layout listener a no-op once settled). */
    private fun setSize(id: Int, wPx: Int, hPx: Int) {
        val v = findViewById<View>(id)
        val lp = v.layoutParams
        if (lp.width != wPx || lp.height != hPx) {
            lp.width = wPx
            lp.height = hPx
            v.layoutParams = lp
        }
    }

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
