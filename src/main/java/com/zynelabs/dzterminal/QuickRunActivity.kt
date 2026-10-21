package com.zynelabs.dzterminal

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * v1.0.49: Quick Run — every script in the DZ home folder
 * (plus the QPython scripts folder when readable) in one flat
 * list. Long-press pins a favorite; favorites float to the
 * top. A tap runs the script in a FRESH Console tab, in the
 * script's own folder — the Programs behavior since v1.0.21.
 */
class QuickRunActivity : Activity() {

    private data class ScriptItem(val file: File, val fav: Boolean)

    private lateinit var listView: ListView
    private var items: List<ScriptItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quickrun)
        listView = findViewById(R.id.quickrun_list)
        findViewById<Button>(R.id.quickrun_menu_button).setOnClickListener { v -> showMenu(v) }
        listView.setOnItemClickListener { _, _, pos, _ -> runScript(items[pos].file) }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            toggleFavorite(items[pos].file)
            true
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Quick Run").setEnabled(false)
        popup.menu.add("Refresh")
        popup.menu.add("Home")
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Refresh" -> { refresh(); true }
                "Home" -> { finish(); true }
                else -> false
            }
        }
        popup.show()
    }

    // ---- data ----

    private fun favorites(): MutableSet<String> {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        return HashSet(prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet())
    }

    private fun toggleFavorite(f: File) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val favs = favorites()
        val path = f.absolutePath
        if (favs.contains(path)) {
            favs.remove(path)
            Toast.makeText(this, "Unpinned: " + f.name, Toast.LENGTH_SHORT).show()
        } else {
            favs.add(path)
            Toast.makeText(this, "Pinned: " + f.name, Toast.LENGTH_SHORT).show()
        }
        prefs.edit().putStringSet(KEY_FAVORITES, favs).apply()
        refresh()
    }

    private fun collectScripts(): List<File> {
        val found = LinkedHashMap<String, File>()
        val home = File(DzTerminalApp.hostHome(application as DzTerminalApp))
        if (home.isDirectory) {
            home.walkTopDown()
                .onEnter { dir -> dir == home || !dir.name.startsWith(".") }
                .filter { it.isFile && (it.name.endsWith(".py") || it.name.endsWith(".sh")) }
                .take(400)
                .forEach { found[it.absolutePath] = it }
        }
        val qp = File(QPYTHON_DIR)
        if (qp.isDirectory && qp.canRead()) {
            qp.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".py") }
                ?.forEach { found[it.absolutePath] = it }
        }
        return found.values.toList()
    }

    private fun refresh() {
        val favs = favorites()
        val scripts = collectScripts()
        items = scripts
            .map { ScriptItem(it, favs.contains(it.absolutePath)) }
            .sortedWith(
                compareByDescending<ScriptItem> { it.fav }
                    .thenBy { it.file.name.lowercase() }
            )
        listView.adapter = object : ArrayAdapter<ScriptItem>(this, 0, items) {
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val item = getItem(pos)!!
                val row = LinearLayout(context)
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(dp(14), dp(10), dp(14), dp(10))
                val star = TextView(context)
                star.text = if (item.fav) "★" else "☆"
                star.textSize = 18f
                star.setTextColor(getColor(if (item.fav) R.color.dz_green else R.color.dz_muted))
                row.addView(star)
                val stack = LinearLayout(context)
                stack.orientation = LinearLayout.VERTICAL
                stack.setPadding(dp(10), 0, 0, 0)
                val name = TextView(context)
                name.text = item.file.name
                name.textSize = 16f
                name.typeface = Typeface.DEFAULT_BOLD
                name.setTextColor(getColor(R.color.dz_white))
                val path = TextView(context)
                path.text = item.file.parent ?: ""
                path.textSize = 12f
                path.setTextColor(getColor(R.color.dz_muted))
                stack.addView(name)
                stack.addView(path)
                row.addView(stack)
                return row
            }
        }
        if (items.isEmpty()) {
            Toast.makeText(
                this,
                "No .py or .sh scripts found in your home folder",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---- run (mirrors ProgramsActivity.runScriptInNewTab) ----

    private fun runScript(f: File) {
        val runner = if (f.name.endsWith(".sh")) "bash" else "python3"
        val guest = guestPathFor(f.absolutePath)
        val dir = guest.substringBeforeLast('/', "").ifEmpty { "/" }
        val name = guest.substringAfterLast('/')
        val cmd = "cd \"${shellQuote(dir)}\" && $runner \"${shellQuote(name)}\""
        ConsoleActivity.openWithCommandInNewTab(this, cmd)
    }

    /** Maps a host absolute path onto the path the proot guest sees. */
    private fun guestPathFor(absPath: String): String {
        val hostUsr = DzTerminalApp.hostUsr(application as DzTerminalApp)
        return when {
            absPath == hostUsr -> DzTerminalApp.GUEST_PREFIX
            absPath.startsWith("$hostUsr/") ->
                DzTerminalApp.GUEST_PREFIX + absPath.removePrefix(hostUsr)
            else -> absPath
        }
    }

    /** Escapes backslash + double-quote for a quoted shell word. */
    private fun shellQuote(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "dz_quickrun"
        private const val KEY_FAVORITES = "favorites"
        private const val QPYTHON_DIR = "/sdcard/qpython/scripts3"
    }
}
