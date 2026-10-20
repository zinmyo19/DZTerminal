package com.zynelabs.dzterminal

import android.app.Activity
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
        private const val MENU_HOME = 4
    }

    private lateinit var fileText: EditText
    private var file: File? = null

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

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path != null) {
            openFile(File(path))
        } else {
            // Default: the sample file.
            val app = application as DzTerminalApp
            openFile(File(DzTerminalApp.hostHome(app), "PYTHON/hello.py"))
        }
    }

    /** v1.0.40: floating ⋮ menu — filename header + Run + Save + Home. */
    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_NAME, 0, file?.name ?: "untitled").setEnabled(false)
        popup.menu.add(0, MENU_RUN, 1, "Run")
        popup.menu.add(0, MENU_SAVE, 2, "Save")
        popup.menu.add(0, MENU_HOME, 3, "Home")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RUN -> { runInConsole(); true }
                MENU_SAVE -> { save(); true }
                MENU_HOME -> { finish(); true }
                else -> false
            }
        }
        popup.show()
    }

    /** v1.0.40: system BACK replaces the removed header BACK —
     *  keyboard open -> close it first; closed -> Home. */
    override fun onBackPressed() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val focus = currentFocus
        if (focus != null && imm.isActive(focus)) {
            imm.hideSoftInputFromWindow(focus.windowToken, 0)
            focus.clearFocus()
        } else {
            super.onBackPressed()
        }
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
