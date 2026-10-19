package com.zynelabs.dzterminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.system.Os
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Diagnostics: version, prefix/proot paths, permission details,
 * setup state + setup log tail, second-stage exit + output, last
 * session exit + output tail, exec mode + session argv, storage
 * state, and probe buttons. The Copy button puts the whole report
 * on the clipboard.
 *
 * v1.0.4: the prefix check now reports the PARENT dirs of the bash
 * path (<prefix> and <prefix>/bin) with raw modes — execve fails with
 * EACCES when a parent dir lacks the execute (search) bit even though
 * bash itself is +x — plus the second-stage exit/output and the exact
 * argv a session uses.
 *
 * v1.0.6: exec mode is DIRECT (Termux-style, no proot). Test proot
 * runs `proot --help` only; Test bash runs the direct-exec minimal
 * repro. Buttons have a ripple background + a Toast on tap, and are
 * always re-enabled in a finally block (v1.0.5 could leave them
 * disabled forever if the probe thread threw).
 *
 * v1.0.7: "Last crash" section (CrashLog: the global uncaught-
 * exception handler records every crash to files/crash.log) and
 * session-start errors in "Last session", so a failed start is
 * evidence instead of a blank screen.
 *
 * v1.0.13: Second-stage mode (proot) + whole-tree un-patch stats +
 * Patch proof (bin/pkg shebang on disk + bin/python* listing) so
 * "patched=0" is distinguishable from a broken scan.
 *
 * v1.0.15: proot section shows PROOT_TMP_DIR used, whether the
 * bind-target dir exists, and the EXACT proot argv for the second
 * stage + a sample wrapper invocation — v1.0.14's proot failures
 * ("can't create temporary file" from host /tmp) were diagnosed
 * from a screenshot, not from Debug, because Debug never showed
 * what proot was actually given.
 *
 * v1.0.17: the session runs under proot (no direct exec, no
 * wrappers, tree stays fake). Exec shows the session proot argv;
 * Tree state shows the fake-tree check (checkTreeFake) + wrapper
 * removal + one-time migration; Package tools shows the keyring
 * state (trusted.gpg.d entries). Test bash runs the proot session
 * path, not direct exec.
 */
class DebugActivity : Activity() {

    companion object {
        fun appVersion(context: Context): String {
            return try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                @Suppress("DEPRECATION")
                info.versionName + " (code " + info.versionCode + ")"
            } catch (_: Exception) {
                "?"
            }
        }
    }

    private lateinit var debugText: TextView
    private lateinit var testProotButton: Button
    private lateinit var testBashButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        debugText = findViewById(R.id.debug_text)
        testProotButton = findViewById(R.id.debug_test_proot)
        testBashButton = findViewById(R.id.debug_test_bash)

        val app = application as DzTerminalApp
        findViewById<Button>(R.id.debug_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.debug_copy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DZ Terminal debug", debugText.text))
            Toast.makeText(this, "Debug info copied", Toast.LENGTH_SHORT).show()
        }
        testProotButton.setOnClickListener {
            Toast.makeText(this, "Running proot probe...", Toast.LENGTH_SHORT).show()
            runProbe("proot --help") { SessionManager.runProotProbe(app) }
        }
        testBashButton.setOnClickListener {
            Toast.makeText(this, "Running proot bash probe...", Toast.LENGTH_SHORT).show()
            runProbe("bash -c echo (proot session path)") { SessionManager.runProotBashProbe(app) }
        }

        debugText.text = buildReport()
    }

    /**
     * Runs one probe on a background thread and refreshes the report
     * with the result. The buttons are ALWAYS re-enabled in a finally
     * block — v1.0.5 could leave them disabled forever if the probe
     * thread threw before reaching the UI update, which is one way
     * the buttons looked "dead" (the other: their transparent
     * background gives zero press feedback, so v1.0.6 gives them
     * the standard ripple background + a Toast on tap).
     */
    private fun runProbe(label: String, probe: () -> SessionManager.CapturedResult) {
        testProotButton.isEnabled = false
        testBashButton.isEnabled = false
        debugText.text = buildReport() + "\n--- Probe ---\nRunning [$label]...\n"
        Thread {
            try {
                val r = probe()
                val text = "Probe [$label]: exit=${r.exitCode} timedOut=${r.timedOut}\n" +
                    r.output.takeLast(3000).ifEmpty { "(no output)" }
                DebugInfo.lastSelfTest = text
            } catch (e: Exception) {
                DebugInfo.lastSelfTest = "Probe [$label] FAILED: ${e.message}"
            } finally {
                runOnUiThread {
                    debugText.text = buildReport()
                    testProotButton.isEnabled = true
                    testBashButton.isEnabled = true
                }
            }
        }.start()
    }

    /** "label: mode=755 read=yes exec=yes" — raw mode via Os.stat (no File.canXxx guessing). */
    private fun permLine(label: String, f: File): String {
        return try {
            val mode = Os.stat(f.absolutePath).st_mode and 0xFFF
            label + ": mode=" + Integer.toOctalString(mode).padStart(4, '0') +
                " read=" + (if (f.canRead()) "yes" else "no") +
                " exec=" + (if (f.canExecute()) "yes" else "no")
        } catch (e: Exception) {
            "$label: (stat failed: ${e.message})"
        }
    }

    /** First line of bin/pkg as it exists on disk — under proot it must be the FAKE shebang (a host shebang breaks under proot). */
    private fun pkgShebang(app: DzTerminalApp): String {
        return try {
            val f = File(DzTerminalApp.hostUsr(app), "bin/pkg")
            if (!f.isFile) "(bin/pkg missing)"
            else f.bufferedReader().readLine() ?: "(empty file)"
        } catch (e: Exception) {
            "(read failed: ${e.message})"
        }
    }

    /** bin/python* files present in the prefix (empty = python not installed / postinsts never ran). */
    private fun pythonFiles(app: DzTerminalApp): String {
        return try {
            val bin = File(DzTerminalApp.hostUsr(app), "bin")
            val names = bin.listFiles()?.map { it.name }?.filter { it.startsWith("python") }?.sorted() ?: emptyList()
            if (names.isEmpty()) "(none — python not installed)"
            else names.joinToString(", ")
        } catch (e: Exception) {
            "(read failed: ${e.message})"
        }
    }

    /**
     * apt keyring state: etc/apt/trusted.gpg.d entries. The
     * bootstrap ships these as SYMLINKS (via SYMLINKS.txt) to
     * the share/termux-keyring key files — no keyring .deb, no
     * keyring postinst. apt reads them through the proot bind.
     * Empty = keys missing -> apt reports repos "not signed".
     */
    private fun keyringState(app: DzTerminalApp): String {
        return try {
            val dir = File(DzTerminalApp.hostUsr(app), "etc/apt/trusted.gpg.d")
            val names = dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
            if (names.isEmpty()) "EMPTY — keyring not installed (apt will report repos not signed)"
            else "${names.size} entries: " + names.joinToString(", ")
        } catch (e: Exception) {
            "(read failed: ${e.message})"
        }
    }

    private fun buildReport(): String {
        val app = application as DzTerminalApp
        val prefixHost = DzTerminalApp.hostUsr(app)
        val prefixInstalled = PrefixSetup.isInstalled(this)
        val proot = DzTerminalApp.prootPath(app)
        val prootFile = File(proot)
        val sdcard = File("/sdcard")
        val bashFile = File(prefixHost, "bin/bash")
        val topEntries = File(prefixHost).listFiles()?.map { it.name }?.sorted()?.joinToString(", ") ?: "(unreadable)"
        val secondStage = if (DebugInfo.lastSecondStageOutput.isNotEmpty()) {
            "exit=${DebugInfo.lastSecondStageExit}\n" + DebugInfo.lastSecondStageOutput
        } else {
            PrefixSetup.readSecondStageLog(app) ?: "(not run yet)"
        }
        return StringBuilder()
            .append("DZ Terminal ").append(appVersion(this)).append("\n")
            .append("Package: ").append(packageName).append("\n")
            .append("\n--- Prefix ---\n")
            .append("Prefix path (host): ").append(prefixHost).append("\n")
            .append("Prefix installed: ").append(if (prefixInstalled) "yes" else "no").append("\n")
            .append("bin/bash exists: ").append(if (bashFile.exists()) "yes" else "no")
            .append(", executable: ").append(if (bashFile.canExecute()) "yes" else "no").append("\n")
            .append("Prefix top-level: ").append(topEntries).append("\n")
            .append("--- Parent-dir perms (execve needs +x on every parent) ---\n")
            .append(permLine("Prefix dir", File(prefixHost))).append("\n")
            .append(permLine("Prefix bin/", File(prefixHost, "bin"))).append("\n")
            .append(permLine("Prefix tmp/", File(prefixHost, "tmp"))).append("\n")
            .append(permLine("bin/bash", bashFile)).append("\n")
            .append("Last setup: ").append(DebugInfo.lastSetupResult ?: "(not run in this session)").append("\n")
            .append("Setup state (file): ").append(PrefixSetup.setupState(app) ?: "(none — pre-1.0.4 or never set up)").append("\n")
            .append("\n--- Second stage ---\n")
            .append("Mode: ").append(DebugInfo.lastSecondStageMode.ifEmpty { "(not run in this session — v1.0.13 runs it under proot)" }).append("\n")
            .append(secondStage).append("\n")
            .append("Whole-tree un-patch (v1.0.17, one-time migration of a <=v1.0.16 host-patched tree back to fake): ").append(DebugInfo.lastWholeTreeUnpatch.ifEmpty { "(not run in this session)" }).append("\n")
            .append("\n--- Exec ---\n")
            .append("Exec mode: ").append(SessionManager.EXEC_MODE).append(" (session runs under proot since v1.0.17)\n")
            .append("Session exec: ").append(SessionManager.sessionProotArgv(app).joinToString(" ")).append("\n")
            .append("Session cwd (-w, v1.0.18 — guest HOME, so the prompt shows `~`): ").append(SessionManager.sessionWorkDir()).append("\n")
            .append("Linker warning filter (v1.0.19 — strips the exact Bionic `WARNING: linker: ... /linkerconfig/ld.config.txt` line + trailing newline from the PTY output stream, cross-chunk safe; display-only, the warning itself is harmless): on\n")
            .append("Guest env: PREFIX/TERMUX__PREFIX/HOME/TMPDIR/SHELL/PATH all FAKE guest paths; NO LD_PRELOAD, NO LD_LIBRARY_PATH, NO host-path CA vars (curl's compiled-in fake CA path resolves via the bind). PROOT_TMP_DIR is the one host-path exception (proot reads it from its own env).\n")
            .append("\n--- Tree state (must stay FAKE for proot) ---\n")
            .append("v1.0.17: the tree is NEVER patched to host paths anymore. A host-patched file breaks under proot (proot translates a host-path shebang by prepending the rootfs -> nonexistent path). The two Termux-app first-run scripts in etc/profile.d stay neutralized to a no-op.\n")
            .append(PrefixSetup.checkTreeFake(app)).append("\n")
            .append("Wrapper removal (v1.0.17 — wrappers deleted, real tools restored): ")
            .append(DebugInfo.lastWrapperRemoval.ifEmpty { "(not run in this session)" }).append("\n")
            .append("One-time tree migration (v1.0.17 — revert <=v1.0.16 host patching): ")
            .append(DebugInfo.lastTreeMigration.ifEmpty { "(not run in this session)" }).append("\n")
            .append("\n--- Tree proof ---\n")
            .append("bin/pkg shebang (first line on disk — must be FAKE under proot): ").append(pkgShebang(app)).append("\n")
            .append("bin/python* files: ").append(pythonFiles(app)).append("\n")
            .append("\n--- Package tools (v1.0.17) ---\n")
            .append("NO wrappers: pkg/apt/dpkg/apt-key/gpgv run INSIDE the proot session, so their compiled-in fake paths resolve via the bind (v1.0.16's apt-key failure was the host-patched shebang).\n")
            .append("CA bundle (etc/tls/cert.pem): ")
            .append(
                if (File(prefixHost, "etc/tls/cert.pem").exists())
                    "exists — curl's compiled-in fake CA path resolves via the bind to our CA bundle"
                else "MISSING"
            ).append("\n")
            .append("apt keyring (etc/apt/trusted.gpg.d — symlinks to share/termux-keyring, created at extraction; apt reads them via the bind): ")
            .append(keyringState(app)).append("\n")
            .append("apt cache dir (<prefix>/data/data/com.termux/cache/apt/archives/partial — v1.0.18, apt downloads .debs here; missing it broke `pkg install`): ")
            .append(if (DebugInfo.lastAptCacheDirExists) "exists" else "missing / not checked yet").append("\n")
            .append("\n--- proot ---\n")
            .append("proot path: ").append(proot).append("\n")
            .append("proot exists: ").append(if (prootFile.exists()) "yes" else "no")
            .append(", executable: ").append(if (prootFile.canExecute()) "yes" else "no").append("\n")
            .append("Sessions run under proot since v1.0.17. The SECOND STAGE runs under proot since v1.0.13. Test proot runs `proot --help` only.\n")
            .append("\n--- proot (v1.0.15+) ---\n")
            .append("PROOT_TMP_DIR used for proot (host path — proot reads it from its own env; host /tmp is not writable by the app): ")
            .append(DebugInfo.lastProotTmpDir.ifEmpty { "(not run in this session)" }).append("\n")
            .append("Bind target (<prefix>/data/data/com.termux/files/usr, inside OUR OWN prefix) exists: ")
            .append(if (DebugInfo.lastProotBindTargetExists) "yes" else "no / not checked yet").append("\n")
            .append("/linkerconfig bind (v1.0.18 — bound only when the host dir is readable; unreadable -> omitted, no proot warning): ")
            .append(DebugInfo.lastLinkerconfigStatus.ifEmpty { "not checked yet" }).append("\n")
            .append("Second-stage proot argv: ")
            .append(DebugInfo.lastSecondStageProotArgv.ifEmpty { "(not run in this session)" }).append("\n")
            .append("\n--- Session drain (v1.0.22 — per-tab proof) ---\n")
            .append("read = bytes the reader pulled off the PTY; appended = bytes the handler fed the emulator; queue = bytes waiting. Stalled tab? queue GROWING + appended FROZEN = drain choke; queue EMPTY + read FROZEN = guest/proot choke. * = current tab.\n")
            .append(DebugInfo.sessionDrainStats(app)).append("\n")
            .append("\n--- Background keep-alive (v1.0.26) ---\n")
            .append("Foreground service + PARTIAL_WAKE_LOCK while >= 1 session runs; lock NEVER held with zero sessions. Service running: ")
            .append(if (SessionService.isServiceRunning) "yes" else "no")
            .append(", wake lock held: ")
            .append(if (SessionService.isWakeLockHeld) "yes" else "no").append("\n")
            .append("\n--- Last session ---\n")
            .append(DebugInfo.sessionEndedText()).append("\n")
            .append(
                if (DebugInfo.lastSessionError != null)
                    "Session start error: " + DebugInfo.lastSessionError + "\n"
                else ""
            )
            .append("\n--- Last crash ---\n")
            .append(
                // CrashLog survives process death (files/crash.log),
                // so a crash from the previous run shows up here.
                // v1.0.7: if the app exits on Console tap again,
                // reopen → Debug → Copy sends the stack trace.
                CrashLog.lastCrash(app) ?: "(no crash recorded)"
            ).append("\n")
            .append("\n--- Setup log tail ---\n")
            .append(DebugInfo.setupLogTail()).append("\n")
            .append("\n--- Last session output tail ---\n")
            .append(DebugInfo.lastSessionTranscriptTail.ifEmpty { "(none)" }).append("\n")
            .append("\n--- Probes ---\n")
            .append("(Test proot = `proot --help`. Test bash = proot session-path repro: `proot ... /bin/bash -c 'echo DZ-BASH-OK; id -u'` with the session guest env.)\n")
            .append(DebugInfo.lastSelfTest.ifEmpty { "(not run — tap Test proot / Test bash)" }).append("\n")
            .append("\n--- Storage ---\n")
            .append("File import: SAF document picker (no broad storage permission needed)\n")
            .append("/sdcard exists: ").append(if (sdcard.exists()) "yes" else "no").append("\n")
            .append("Storage permission (READ_EXTERNAL_STORAGE, v1.0.27 — requested once on Console start; WRITE requested with it so the shell can write to /sdcard): ")
            .append(if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) "granted" else "DENIED — grant Files and media in App info to use /sdcard").append("\n")
            .append("Guest storage shortcuts (v1.0.27 — PrefixSetup.ensureStorageLinks, every Console start): ")
            .append(DebugInfo.lastStorageLinksStatus.ifEmpty { "(not run in this session)" }).append("\n")
            .append("Session proot binds /sdcard + /storage (v1.0.27 — must both be in the session argv): ")
            .append(if (SessionManager.sessionProotArgv(app).contains("/sdcard") &&
                SessionManager.sessionProotArgv(app).contains("/storage")) "present" else "MISSING").append("\n")
            .append("Files export / import (v1.0.28 — Home 3-dot menu; home <-> /storage/emulated/0/DZTerminal; copy, never move; import conflicts -> .imported): \n")
            .append("Last export: ").append(DebugInfo.lastExportSummary.ifEmpty { "(not run in this session)" }).append("\n")
            .append("Last import: ").append(DebugInfo.lastImportSummary.ifEmpty { "(not run in this session)" }).append("\n")
            .toString()
    }
}
