package com.zynelabs.dzterminal

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * QPYPI: install a Python package with pip inside the prefix.
 * Runs `proot ... /bin/bash <fake>/bin/pip install <name>` (v1.0.17
 * — the proot session path, not direct exec) and shows output.
 * pip is a script with a Termux shebang, so it is run THROUGH
 * bash under proot, not exec'd directly. Requires Python
 * (pkg install python) first — the screen says so.
 */
class QpypiActivity : Activity() {

    private lateinit var outputText: TextView
    private lateinit var scrollView: ScrollView
    @Volatile private var installing = false

    // v1.0.19: the exact Bionic linker warning line (same sequence
    // the Console's LinkerWarningFilter strips from the PTY stream).
    // QPYPI reads pip's output line-by-line, so the warning arrives
    // as one whole line here — skip exactly that line, nothing else.
    // The warning is harmless display noise, not an error.
    private val LINKER_WARNING =
        "WARNING: linker: Warning: failed to find generated linker configuration from \"/linkerconfig/ld.config.txt\""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qpypi)
        val input = findViewById<EditText>(R.id.qpypi_input)
        outputText = findViewById(R.id.qpypi_output)
        scrollView = findViewById(R.id.qpypi_scroll)

        findViewById<Button>(R.id.qpypi_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.qpypi_install).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                appendLine("Enter a package name first.")
                return@setOnClickListener
            }
            if (!name.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.\\-+]*"))) {
                appendLine("Invalid package name: $name")
                return@setOnClickListener
            }
            install(name)
        }
    }

    private fun install(name: String) {
        if (installing) {
            appendLine("An install is already running.")
            return
        }
        val app = application as DzTerminalApp
        val hostUsr = DzTerminalApp.hostUsr(app)
        val pipHost = File(hostUsr, "bin/pip")
        if (!PrefixSetup.isInstalled(this) || !pipHost.exists()) {
            appendLine("pip is not installed yet.")
            appendLine("Open Console and run:  pkg install python")
            appendLine("Then come back and try again.")
            return
        }
        installing = true
        appendLine("$ pip install $name")
        Thread {
            try {
                val fake = DzTerminalApp.GUEST_PREFIX
                val args = SessionManager.prootArgv(
                    app,
                    listOf("/bin/bash", "$fake/bin/pip", "install", name)
                )
                val pb = ProcessBuilder(args)
                SessionManager.applyEnv(pb, SessionManager.prootGuestEnv(app))
                pb.redirectErrorStream(true)
                val proc = pb.start()
                proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line != LINKER_WARNING) {
                            runOnUiThread { appendLine(line) }
                        }
                    }
                }
                proc.waitFor()
                runOnUiThread { appendLine("Done (exit ${proc.exitValue()}).") }
            } catch (e: Exception) {
                runOnUiThread { appendLine("Failed: " + e.message) }
            } finally {
                installing = false
            }
        }.start()
    }

    private fun appendLine(line: String) {
        outputText.append(line + "\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
