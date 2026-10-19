package com.zynelabs.dzterminal

import android.app.Application
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global crash catcher (v1.0.7).
 *
 * The user cannot easily get logcat, so the app must diagnose its own
 * crashes: the FIRST thing Application.onCreate does is install this
 * as the default uncaught-exception handler. On ANY uncaught
 * exception in ANY thread it appends timestamp + thread + full stack
 * trace + cause chain to <files>/crash.log (kept to the last 5
 * crashes), then delegates to the previous handler so the process
 * still dies normally (a crash is still a crash — this only records
 * it). The Debug screen shows the most recent entry, and Copy
 * includes it, so the NEXT crash arrives as evidence instead of
 * another blind round.
 *
 * The handler itself must NEVER throw: every step is wrapped in
 * try/catch, and a failure to write the log is silently dropped.
 * File access happens at crash time (not at install time), so
 * install() is safe to call before super.onCreate().
 */
object CrashLog {

    private const val FILE_NAME = "crash.log"
    private const val MAX_CRASHES = 5
    private const val MAX_ENTRY_CHARS = 8000
    private const val SEPARATOR = "===== CRASH "

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(app, thread, throwable)
            } catch (_: Throwable) {
                // Never throw inside the handler — the crash must
                // still proceed to the previous handler below.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // No previous handler: terminate like the runtime would.
                Process.killProcess(Process.myPid())
                @Suppress("DEPRECATION")
                System.exit(10)
            }
        }
    }

    private fun writeCrash(app: Application, thread: Thread, throwable: Throwable) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("$SEPARATOR$stamp ===== (thread: ${thread.name})")
        var cause: Throwable? = throwable
        var depth = 0
        while (cause != null && depth < 10) {
            if (depth > 0) pw.println("Caused by:")
            pw.println("${cause.javaClass.name}: ${cause.message}")
            for (frame in cause.stackTrace) pw.println("    at $frame")
            cause = cause.cause
            depth++
        }
        pw.flush()
        val entry = sw.toString().take(MAX_ENTRY_CHARS)
        val file = File(app.filesDir, FILE_NAME)
        val existing = try {
            if (file.exists()) file.readText() else ""
        } catch (_: Exception) {
            ""
        }
        val parts = existing.split(SEPARATOR).filter { it.isNotBlank() }
            .map { SEPARATOR + it.trimEnd() }
        val kept = (parts + entry).takeLast(MAX_CRASHES)
        try {
            file.writeText(kept.joinToString("\n\n") + "\n")
        } catch (_: Exception) {
        }
    }

    /** Most recent crash entry, or null when no crash has been recorded. */
    fun lastCrash(app: Application): String? {
        return try {
            val file = File(app.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val parts = file.readText().split(SEPARATOR).filter { it.isNotBlank() }
            if (parts.isEmpty()) null else (SEPARATOR + parts.last().trimEnd())
        } catch (_: Exception) {
            null
        }
    }
}
