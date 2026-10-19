package com.zynelabs.dzterminal

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Simple code editor: open a file, edit, save. Run sends it to the Console.
 */
class EditorActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
    }

    private lateinit var fileText: EditText
    private lateinit var titleText: TextView
    private var file: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        fileText = findViewById(R.id.editor_text)
        titleText = findViewById(R.id.editor_title)

        findViewById<Button>(R.id.editor_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.editor_save).setOnClickListener { save() }
        findViewById<Button>(R.id.editor_run).setOnClickListener { runInConsole() }

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path != null) {
            openFile(File(path))
        } else {
            // Default: the sample file.
            val app = application as DzTerminalApp
            openFile(File(DzTerminalApp.hostHome(app), "PYTHON/hello.py"))
        }
    }

    private fun openFile(f: File) {
        file = f
        titleText.text = f.name
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
