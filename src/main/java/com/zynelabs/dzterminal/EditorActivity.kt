package com.zynelabs.dzterminal

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import java.io.File

/**
 * Simple code editor: open a file, edit, save. Run sends it to the Console.
 */
class EditorActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val MENU_NAME = 1
        private const val MENU_RUN = 2
        private const val MENU_SAVE = 3
        private const val MENU_EXIT = 4
    }

    private lateinit var fileText: EditText
    private var file: File? = null
    /** Set when BACK just closed the keyboard; the next BACK exits.
     *  Re-armed (false) whenever the user taps the text again. */
    private var backClosedKeyboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        fileText = findViewById(R.id.editor_text)

        // v1.0.40: header bar removed — Run/Save live in the floating
        // ⋮ menu now (same pattern as the Console). The filename that
        // the header title showed appears as a disabled menu header.
        findViewById<Button>(R.id.editor_menu_button).setOnClickListener { v ->
            showMenu(v)
        }

        // Tapping the text means the keyboard is (coming) back —
        // re-arm the "first BACK closes keyboard" stage.
        fileText.setOnTouchListener { _, _ -> backClosedKeyboard = false; false }

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path != null) {
            openFile(File(path))
        } else {
            // Default: the sample file.
            val app = application as DzTerminalApp
            openFile(File(DzTerminalApp.hostHome(app), "PYTHON/hello.py"))
        }
    }

    /** Floating ⋮ menu — filename header + Run + Save + Exit.
     *  v1.0.48: "Home" replaced by an explicit Exit (no-save) item;
     *  the user asked for a plain way out that never writes. */
    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_NAME, 0, file?.name ?: "untitled").setEnabled(false)
        popup.menu.add(0, MENU_RUN, 1, "Run")
        popup.menu.add(0, MENU_SAVE, 2, "Save")
        popup.menu.add(0, MENU_EXIT, 3, "Exit")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RUN -> { runInConsole(); true }
                MENU_SAVE -> { save(); true }
                MENU_EXIT -> { exitWithoutSaving(); true }
                else -> false
            }
        }
        popup.show()
    }

    /** v1.0.47: BACK stage 1 closes the keyboard (only when it is
     *  really visible); stage 2 leaves via exitEditor(). The
     *  v1.0.40 isActive() check could loop forever — the EditText
     *  is the only focusable view, kept re-gaining focus, and BACK
     *  never reached finish(), trapping the user in the editor. */
    override fun onBackPressed() {
        if (!backClosedKeyboard && isKeyboardVisible()) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(fileText.windowToken, 0)
            backClosedKeyboard = true
        } else {
            exitEditor()
        }
    }

    private fun isKeyboardVisible(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val insets = fileText.rootWindowInsets
            if (insets != null) {
                return insets.isVisible(android.view.WindowInsets.Type.ime())
            }
        }
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val focus = currentFocus
        return focus != null && imm.isActive(focus)
    }

    /** Leave the editor. Clean buffer -> straight out. Dirty buffer
     *  -> Save / Discard / Cancel, so "not saved" really means gone
     *  (v1.0.47; the disk file is only ever written by Save/Run). */
    private fun exitEditor() {
        val f = file
        val disk = if (f != null && f.exists()) f.readText() else ""
        if (fileText.text.toString() == disk) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(f?.name ?: "untitled")
            .setMessage("Unsaved changes")
            .setPositiveButton("Save") { _, _ -> save(); finish() }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    /** v1.0.48: menu Exit — leave WITHOUT saving, ever. Clean
     *  buffer leaves at once; dirty buffer gets one confirm so a
     *  stray tap cannot throw work away silently. */
    private fun exitWithoutSaving() {
        val f = file
        val disk = if (f != null && f.exists()) f.readText() else ""
        if (fileText.text.toString() == disk) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(f?.name ?: "untitled")
            .setMessage("Exit without saving?")
            .setPositiveButton("Exit") { _, _ -> finish() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openFile(f: File) {
        file = f
        fileText.setText(if (f.exists()) f.readText() else "")
    }

    private fun save() {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            f.writeText(fileText.text.toString())
            Toast.makeText(this, "Saved: " + f.name, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun runInConsole() {
        val f = file ?: return
        save()
        // Host path: the Console shell sees HOST paths under direct
        // exec (v1.0.6 — no proot guest view anymore).
        val runner = if (f.name.endsWith(".py")) "python3" else "bash"
        ConsoleActivity.openWithCommand(this, "$runner \"${f.absolutePath}\"")
    }
}
