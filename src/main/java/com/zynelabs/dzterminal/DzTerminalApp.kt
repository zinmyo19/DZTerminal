package com.zynelabs.dzterminal

import android.app.Application
import com.termux.terminal.TerminalSession

/**
 * Application-scoped state for DZ Terminal.
 *
 * The terminal session lives here (not in an Activity) so it keeps
 * running while the user visits other screens.
 *
 * Path model (v1.0.17): GUEST (fake) paths inside the prefix.
 * The prefix lives under this app's private data dir
 * (/data/data/com.zynelabs.dzterminal/files/usr) and processes
 * run against it UNDER PROOT — the session, the second stage,
 * and one-shots all go through proot's fake->host bind, so the
 * bootstrap binaries' compiled-in fake prefix
 * (/data/data/com.termux/files/usr) resolves onto OUR files.
 * GUEST_PREFIX is that baked-in path: it appears as the guest
 * prefix in proot binds/env and as the search string when
 * reverting host patching (migrateTreeToFake). The app NEVER
 * reads or writes the real Termux app's data dir.
 *
 * proot ships as a native library
 * (lib/arm64-v8a/libdzproot.so). Since v1.0.17 the SESSION runs
 * under proot (fake-path bind), as does the second stage (since
 * v1.0.13). The Debug "Test proot" probe also runs it.
 * (v1.0.6–v1.0.16 ran the session on direct exec with a
 * host-patched tree; the dual-context design kept breaking on
 * helpers — apt-key's host-patched shebang killed `apt update`
 * in v1.0.16 — so the session moved under proot and the tree
 * stays fake.)
 */
class DzTerminalApp : Application() {

    override fun onCreate() {
        // FIRST THING: install the crash catcher before anything else
        // runs (see CrashLog). It only records crashes, then delegates
        // to the previous handler so the process still dies normally.
        CrashLog.install(this)
        super.onCreate()
    }

    companion object {
        const val PACKAGE_NAME = "com.zynelabs.dzterminal"

        /**
         * The prefix path baked into the stock Termux bootstrap
         * binaries/scripts. Used ONLY as the search string when
         * patching bootstrap scripts onto the real host prefix —
         * never as a real filesystem path for this app.
         */
        const val GUEST_PREFIX = "/data/data/com.termux/files/usr"

        /** Host-side prefix path (where files really live). */
        fun hostUsr(app: Application): String = app.filesDir.absolutePath + "/usr"
        fun hostHome(app: Application): String = hostUsr(app) + "/home"

        /**
         * Path of the proot executable: the native library shipped in the
         * APK as lib/arm64-v8a/libdzproot.so, extracted by the installer
         * into nativeLibraryDir with exec permission. Since v1.0.17
         * the SESSION runs under proot (as does the second stage
         * since v1.0.13); the Debug "Test proot" probe also runs it.
         */
        fun prootPath(app: Application): String =
            app.applicationInfo.nativeLibraryDir + "/libdzproot.so"

        /** A command Editor/Programs asked the Console to run. Consumed once. */
        @Volatile
        var pendingConsoleCommand: String? = null

        /**
         * v1.0.21: when true, a pending Programs script-run opens a
         * FRESH Console tab (QPython behavior) instead of reusing
         * the current tab. Cleared together with
         * pendingConsoleCommand when consumed.
         */
        @Volatile
        var pendingConsoleCommandNewTab: Boolean = false
    }

    /**
     * Console sessions (v1.0.20: multiple tabs, cap 8 in
     * ConsoleActivity). The list lives here — Application scope —
     * so sessions keep running while the user visits other screens
     * (BACK to Home and back to Console re-attaches the current
     * one). v1.0.26: SessionService (foreground service + partial
     * wake lock) owns the process lifetime while >= 1 session is
     * running, so leaving the app / screen off no longer kills
     * them; the service stops when the last session ends.
     * Each TerminalSession owns its own
     * TerminalEmulator (screen + scrollback) and its own proot
     * bash process; switching tabs only re-attaches the view.
     * Mutated on the UI thread only (ConsoleActivity).
     */
    val sessions = mutableListOf<TerminalSession>()

    /** Index into [sessions] of the currently displayed session. */
    var currentSessionIndex = 0

    /** The currently displayed session, or null when there is none. */
    var session: TerminalSession?
        get() = sessions.getOrNull(currentSessionIndex)
        set(value) {
            if (value == null) return
            if (sessions.isEmpty()) {
                sessions.add(value)
                currentSessionIndex = 0
            } else {
                sessions[currentSessionIndex] = value
            }
        }
}
