package com.zynelabs.dzterminal

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * v1.0.49: Snippets — named shell commands stored in a small
 * TSV file under filesDir (name TAB command per line). A tap
 * runs the command in a FRESH Console tab. Long-press offers
 * Edit / Copy / Delete. Seeded with a few defaults on first
 * run.
 */
class SnippetsActivity : Activity() {

    private data class Snippet(val name: String, val command: String)

    private lateinit var listView: ListView
    private var snippets: MutableList<Snippet> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_snippets)
        listView = findViewById(R.id.snippets_list)
        findViewById<Button>(R.id.snippets_menu_button).setOnClickListener { v -> showMenu(v) }
        load()
        listView.setOnItemClickListener { _, _, pos, _ ->
            ConsoleActivity.openWithCommandInNewTab(this, snippets[pos].command)
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            showItemActions(snippets[pos], pos)
            true
        }
        render()
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Snippets").setEnabled(false)
        popup.menu.add("Add snippet")
        popup.menu.add("Home")
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Add snippet" -> { editDialog(null, -1); true }
                "Home" -> { finish(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun showItemActions(snippet: Snippet, pos: Int) {
        val actions = arrayOf("Edit", "Copy command", "Delete")
        AlertDialog.Builder(this)
            .setTitle(snippet.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> editDialog(snippet, pos)
                    1 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("snippet", snippet.command))
                        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        snippets.removeAt(pos)
                        save()
                        render()
                    }
                }
            }
            .show()
    }

    private fun editDialog(existing: Snippet?, pos: Int) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val nameInput = EditText(this)
        nameInput.hint = "Name"
        nameInput.setText(existing?.name ?: "")
        val cmdInput = EditText(this)
        cmdInput.hint = "Command"
        cmdInput.setText(existing?.command ?: "")
        box.addView(nameInput)
        box.addView(cmdInput)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add snippet" else "Edit snippet")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val cmd = cmdInput.text.toString().trim()
                if (name.isEmpty() || cmd.isEmpty()) {
                    Toast.makeText(
                        this,
                        "Name and command are both needed",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val s = Snippet(name, cmd)
                    if (pos >= 0) snippets[pos] = s else snippets.add(s)
                    save()
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- storage ----

    private fun storeFile(): File = File(filesDir, "snippets.tsv")

    private fun load() {
        val f = storeFile()
        if (!f.exists()) {
            snippets = DEFAULTS.toMutableList()
            save()
            return
        }
        snippets = f.readLines()
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else Snippet(line.substring(0, tab), line.substring(tab + 1))
            }
            .toMutableList()
    }

    private fun save() {
        val text = snippets.joinToString("\n") { it.name + "\t" + it.command }
        storeFile().writeText(text)
    }

    private fun render() {
        listView.adapter = object : ArrayAdapter<Snippet>(this, 0, snippets) {
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val s = getItem(pos)!!
                val stack = LinearLayout(context)
                stack.orientation = LinearLayout.VERTICAL
                stack.setPadding(dp(14), dp(10), dp(14), dp(10))
                val name = TextView(context)
                name.text = s.name
                name.textSize = 16f
                name.typeface = Typeface.DEFAULT_BOLD
                name.setTextColor(getColor(R.color.dz_white))
                val cmd = TextView(context)
                cmd.text = s.command
                cmd.textSize = 12f
                cmd.typeface = Typeface.MONOSPACE
                cmd.setTextColor(getColor(R.color.dz_muted))
                cmd.setSingleLine(true)
                cmd.ellipsize = android.text.TextUtils.TruncateAt.END
                stack.addView(name)
                stack.addView(cmd)
                return stack
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val DEFAULTS = listOf(
            Snippet("Update packages", "pkg update && pkg upgrade"),
            Snippet("Python version", "python3 --version"),
            Snippet("Disk usage", "df -h"),
            Snippet("List files", "ls -la"),
            Snippet("Pip packages", "pip list")
        )
    }
}
