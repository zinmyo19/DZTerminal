package com.zynelabs.dzterminal

import android.app.Activity
import android.content.Intent
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

/**
 * v1.0.50: Sessions — the running terminal sessions (the
 * Console's tabs) in one list. Sessions live on the
 * Application object (DzTerminalApp.sessions), so they keep
 * running while the user visits other screens. A tap sets
 * DzTerminalApp.currentSessionIndex and opens the Console,
 * whose startOrAttachSession() then attaches exactly that
 * tab. With no running session the screen bounces straight
 * to the Console (which starts a fresh one) after a Toast.
 *
 * Only RUNNING sessions are listed: a finished tab keeps its
 * transcript inside the Console, but it is not a place the
 * user can jump back into. Row numbers are the REAL tab
 * indices, so "Tab 3" here is tab 3 in the Console.
 */
class SessionsActivity : Activity() {

    private lateinit var listView: ListView

    /** Real indices into DzTerminalApp.sessions, running only. */
    private var shown: List<Int> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!collect()) {
            bounceToConsole()
            return
        }
        setContentView(R.layout.activity_sessions)
        listView = findViewById(R.id.sessions_list)
        findViewById<Button>(R.id.sessions_menu_button).setOnClickListener { v -> showMenu(v) }
        listView.setOnItemClickListener { _, _, pos, _ -> jumpTo(shown[pos]) }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::listView.isInitialized) {
            if (!collect()) {
                bounceToConsole()
            } else {
                render()
            }
        }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Sessions").setEnabled(false)
        popup.menu.add("Refresh")
        popup.menu.add("Home")
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Refresh" -> {
                    if (!collect()) bounceToConsole() else render()
                    true
                }
                "Home" -> { finish(); true }
                else -> false
            }
        }
        popup.show()
    }

    // ---- data ----

    /** Returns false when there is no running session at all. */
    private fun collect(): Boolean {
        val app = application as DzTerminalApp
        shown = app.sessions.indices.filter { app.sessions[it].isRunning }
        return shown.isNotEmpty()
    }

    private fun bounceToConsole() {
        Toast.makeText(this, "No running sessions", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, ConsoleActivity::class.java))
        finish()
    }

    private fun jumpTo(realIndex: Int) {
        val app = application as DzTerminalApp
        if (realIndex < 0 || realIndex >= app.sessions.size) {
            if (!collect()) bounceToConsole() else render()
            return
        }
        app.currentSessionIndex = realIndex
        startActivity(Intent(this, ConsoleActivity::class.java))
        finish()
    }

    private fun render() {
        val app = application as DzTerminalApp
        listView.adapter = object : ArrayAdapter<Int>(this, 0, shown) {
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val idx = getItem(pos)!!
                val session = app.sessions[idx]
                val current = idx == app.currentSessionIndex
                val row = LinearLayout(context)
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(dp(14), dp(10), dp(14), dp(10))
                val dot = TextView(context)
                dot.text = if (current) "●" else "○"
                dot.textSize = 14f
                dot.setTextColor(getColor(if (current) R.color.dz_green else R.color.dz_muted))
                row.addView(dot)
                val stack = LinearLayout(context)
                stack.orientation = LinearLayout.VERTICAL
                stack.setPadding(dp(10), 0, 0, 0)
                val title = session.title?.takeIf { it.isNotBlank() }
                val name = TextView(context)
                name.text = title ?: ("Session " + (idx + 1))
                name.textSize = 16f
                name.typeface = Typeface.DEFAULT_BOLD
                name.setTextColor(getColor(R.color.dz_white))
                val meta = TextView(context)
                meta.text = "Tab " + (idx + 1) + " · Running" +
                    if (current) " · Current tab" else ""
                meta.textSize = 12f
                meta.setTextColor(getColor(R.color.dz_muted))
                stack.addView(name)
                stack.addView(meta)
                row.addView(stack)
                return row
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
