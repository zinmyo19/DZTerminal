package com.zynelabs.dzterminal

import android.app.Application
import android.content.Context
import android.system.Os
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * First-run setup of the bundled Linux environment.
 *
 * - Extracts assets/bootstrap-aarch64.zip (the stock Termux bootstrap,
 *   built by the Termux project) into <files>/usr, following the same
 *   extraction rules Termux itself uses (SYMLINKS.txt, chmod 0700 for
 *   bin/ and friends).
 * - Runs a host-side permission repair: the extraction's
 *   hardcoded chmod list covers only bin/, libexec, lib/apt helpers
 *   and the second-stage script, but the zip marks ~395 files
 *   executable. Files created by FileOutputStream land at 0600 under
 *   the app umask, so the rest lost their exec bits. The repair SETS
 *   every mode exactly (masked to 0777): dirs -> 0755, files -> their
 *   zip mode masked to 0777, bin/bash -> 0755. It also clears any
 *   setuid/setgid/sticky bits (v1.0.4's repair OR-ed the hex literal
 *   0x700 — octal 03700 — onto bin/bash, which SELinux/proot refuse
 *   to exec). The repair runs on every Console start (idempotent).
 * - Runs the bootstrap second-stage script (postinst maintainer
 *   scripts) UNDER PROOT (v1.0.13). dpkg is an ELF binary with its
 *   config path COMPILED IN (the fake Termux prefix) — no text
 *   patch can fix a binary's compiled-in path, so direct exec
 *   failed with "error opening configuration directory ...
 *   Permission denied" and setup stayed PARTIAL forever. Under
 *   proot, the fake path resolves through the bind onto OUR
 *   prefix files, so dpkg finds its config and the postinsts run.
 *
 * v1.0.17: THE TREE STAYS FAKE. v1.0.6–v1.0.16 ran the session on
 * direct exec (host paths) and patched the whole tree fake->host
 * for it, while the second stage ran under proot (fake paths)
 * and needed the patch reverted first. That dual-context design
 * kept breaking on helpers: apt-key's shebang got host-patched,
 * so apt inside proot could not execute it and `apt update`
 * reported the repo "not signed" (v1.0.16). Now the SESSION
 * runs under proot too (SessionManager), so the guest always
 * sees a PRISTINE Termux tree (all fake paths) and every
 * binary's compiled-in fake path resolves through the bind.
 * There is NO whole-tree host patch anymore, NO proot wrappers
 * (removed by removeProotWrappers, restoring the real tools),
 * and the one-time migrateTreeToFake reverts any host patching
 * left by <=v1.0.16. The second-stage uid capture is hardened
 * (patchSecondStageUid): the Bionic linker warning used to
 * pollute `id -u` and fail the uid validation, so the second
 * stage never completed and postinsts never ran.
 * - Creates <files>/usr/home/PYTHON with a sample hello.py.
 *
 * proot itself is NOT copied here: it ships in the APK as the native
 * library lib/arm64-v8a/libdzproot.so. Since v1.0.17 the SESSION
 * runs under proot (fake-path bind) as well as the second stage.
 * The Debug "Test proot" probe also runs it.
 *
 * Setup state is persisted in <files>/.dz-setup-state ("OK" /
 * "PARTIAL: ..." / "FAILED: ...") so a PARTIAL prefix is detected
 * across process restarts. A pre-1.0.4 install has no state file and
 * is treated as unknown -> needs repair. The second stage's output is
 * persisted in <files>/.dz-second-stage.log for the Debug screen.
 *
 * Nothing here reads or writes the installed Termux app or its data.
 * The prefix lives entirely under this app's own private files dir.
 */
object PrefixSetup {

    private const val TAG = "DZTerminal-Prefix"
    private const val MODE_700 = 448 // 0700 octal (= 0x1C0 hex)
    // Permission constants. Kotlin has NO octal literals, so these are
    // hex values with the octal meaning in the comment. NEVER write
    // 0x700 meaning "0700": hex 0x700 is setgid+sticky+owner-read
    // (octal 03700). v1.0.4's repair used 0x700 that way and set
    // bin/bash to 03700, which SELinux/proot refuse to exec (EACCES).
    private const val PERM_MASK = 0x1FF // 0777
    private const val PERM_DIR = 0x1ED // 0755
    private const val PERM_BASH = 0x1ED // 0755

    /** chmod with the mode masked to 0777 — setuid/setgid/sticky can never be set through here. */
    private fun chmodPerms(path: String, mode: Int) {
        Os.chmod(path, mode and PERM_MASK)
    }
    private const val SETUP_STATE_FILE = ".dz-setup-state"
    private const val SECOND_STAGE_LOG_FILE = ".dz-second-stage.log"

    fun isInstalled(context: Context): Boolean {
        val bash = File(DzTerminalApp.hostUsr(context.applicationContext as Application), "bin/bash")
        return bash.exists() && bash.canExecute()
    }

    /** Persistent setup state: "OK" / "PARTIAL: ..." / "FAILED: ..." or null (no record). */
    fun setupState(app: Application): String? {
        val f = File(app.filesDir, SETUP_STATE_FILE)
        return try {
            if (f.exists()) f.readText().trim().ifEmpty { null } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun writeSetupState(app: Application, text: String) {
        try {
            File(app.filesDir, SETUP_STATE_FILE).writeText(text)
        } catch (e: Exception) {
            Log.w(TAG, "Could not write setup state", e)
        }
    }

    /**
     * True when the prefix exists but setup did not complete cleanly
     * (PARTIAL state) or was installed by a version before the state
     * file existed (unknown state). In both cases Console start must
     * repair + retry the second stage before opening a session.
     * False when the prefix is not installed (full setup will run) or
     * state is OK.
     */
    fun needsRepair(app: Application): Boolean {
        if (!isInstalled(app)) return false
        val state = setupState(app) ?: return true // pre-1.0.4 install: unknown
        return state.startsWith("PARTIAL")
    }

    /** Last second-stage output (exit + tail), persisted for the Debug screen. */
    fun readSecondStageLog(app: Application): String? {
        val f = File(app.filesDir, SECOND_STAGE_LOG_FILE)
        return try {
            if (f.exists()) f.readText().ifEmpty { null } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun writeSecondStageLog(app: Application, text: String) {
        try {
            File(app.filesDir, SECOND_STAGE_LOG_FILE).writeText(text)
        } catch (e: Exception) {
            Log.w(TAG, "Could not write second-stage log", e)
        }
    }

    /** Runs setup on the calling thread. Returns a human-readable result. */
    fun setup(context: Context, log: (String) -> Unit): Result {
        val app = context.applicationContext as Application
        // Mirror every setup log line into DebugInfo for Settings > Debug.
        val emit: (String) -> Unit = { msg ->
            DebugInfo.appendSetupLog(msg)
            log(msg)
        }
        val filesDir = app.filesDir
        val usrDir = File(DzTerminalApp.hostUsr(app))
        val stagingDir = File(filesDir, "usr-staging")
        try {
            emit("Extracting Linux environment (first run, may take a minute)...")
            deleteRecursively(stagingDir)
            stagingDir.mkdirs()
            extractBootstrap(context, stagingDir, emit)
            if (usrDir.exists()) deleteRecursively(usrDir)
            if (!stagingDir.renameTo(usrDir)) {
                val r = Result(false, "Failed to move prefix into place.")
                DebugInfo.lastSetupResult = "FAILED: ${r.message}"
                writeSetupState(app, "FAILED: ${r.message}")
                return r
            }
            // Home + PYTHON folder + sample file.
            val homeDir = File(DzTerminalApp.hostHome(app))
            val pythonDir = File(homeDir, "PYTHON")
            pythonDir.mkdirs()
            val hello = File(pythonDir, "hello.py")
            if (!hello.exists()) {
                hello.writeText(
                    "# DZ Terminal sample\n" +
                    "print(\"Hello from DZ Terminal\")\n"
                )
            }
            // Dirs the guest needs inside OUR prefix (home/ = session
            // cwd, tmp/ = TMPDIR + PROOT_TMP_DIR).
            SessionManager.ensureGuestDirs(app)
            // Host-side permission repair BEFORE the second stage, so
            // the second stage's proot exec runs against a prefix
            // whose modes match the bootstrap zip (v1.0.4 fix).
            try {
                emit("Repairing file permissions from bootstrap...")
                val stats = repairPrefixPermissions(app, emit)
                emit("Permission repair: ${stats.dirsFixed} dirs, ${stats.filesFixed} files (of ${stats.filesSeen} files).")
            } catch (e: Exception) {
                Log.w(TAG, "Permission repair failed (continuing)", e)
                emit("Permission repair failed: ${e.message} — continuing.")
            }
            // proot ships as the native library libdzproot.so (see
            // DzTerminalApp.prootPath) — nothing to copy here. Since
            // v1.0.13 the second stage runs under proot (fake-path
            // bind); see runSecondStageInner.
            emit("Running bootstrap second stage...")
            val secondStage = runSecondStage(app, emit)
            if (!secondStage.success) {
                Log.w(TAG, "Second stage failed: exit=${secondStage.exitCode}")
                val r = Result(true, "Prefix installed. Second stage had errors (see log) — apt may still work.")
                DebugInfo.lastSetupResult = "PARTIAL: ${r.message}"
                writeSetupState(app, "PARTIAL: Second stage exit=${secondStage.exitCode} (see log)")
                return r
            }
            val r = Result(true, "Linux environment ready.")
            DebugInfo.lastSetupResult = "OK: ${r.message}"
            writeSetupState(app, "OK")
            return r
        } catch (e: Exception) {
            Log.e(TAG, "Prefix setup failed", e)
            val r = Result(false, "Setup failed: ${e.message}")
            DebugInfo.lastSetupResult = "FAILED: ${r.message}"
            writeSetupState(app, "FAILED: ${r.message}")
            return r
        }
    }

    /**
     * Repair path for a PARTIAL / unknown-state prefix (called from
     * Console start before opening a session): host-side permission
     * repair, then a real re-attempt of the second stage. Returns true
     * when the second stage now succeeds.
     */
    fun repairAndRetrySecondStage(app: Application, log: (String) -> Unit): Boolean {
        try {
            log("Repairing prefix permissions...")
            val stats = repairPrefixPermissions(app, log)
            log("Permission repair: ${stats.dirsFixed} dirs, ${stats.filesFixed} files.")
        } catch (e: Exception) {
            Log.w(TAG, "Permission repair failed", e)
            log("Permission repair failed: ${e.message}")
        }
        // The second-stage script creates its lock file BEFORE running
        // postinsts, so a failed run leaves a stale lock that makes any
        // re-run return 0 ("already run") without doing anything. A
        // PARTIAL setup means the run did NOT complete, so the lock is
        // stale: delete it so the retry really runs. (The script itself
        // documents this manual lock removal for re-runs after failure.
        // The two postinsts in this bootstrap — nano's
        // update-alternatives --install and termux-exec's LD_PRELOAD
        // setup — are idempotent, so a re-run after a mid-run failure
        // is safe.)
        val lock = File(
            DzTerminalApp.hostUsr(app),
            "etc/termux/bootstrap/termux-bootstrap-second-stage.sh.lock"
        )
        try {
            if (lock.exists() || symlinkExists(lock)) {
                if (lock.delete()) log("Removed stale second-stage lock (left by failed run).")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove stale lock", e)
            log("Could not remove stale lock: ${e.message}")
        }
        log("Re-running bootstrap second stage...")
        // v1.0.7: guarded — runSecondStage must never throw out of
        // here (a start failure is a PARTIAL result, not a crash).
        val secondStage = try {
            runSecondStage(app, log)
        } catch (t: Throwable) {
            Log.w(TAG, "Second-stage retry threw", t)
            log("Second stage failed to start: ${t.javaClass.simpleName}: ${t.message}")
            writeSetupState(app, "PARTIAL: Second stage start failed: ${t.message}")
            DebugInfo.lastSetupResult = "PARTIAL: Second stage start failed: ${t.message}"
            return false
        }
        if (secondStage.success) {
            writeSetupState(app, "OK")
            DebugInfo.lastSetupResult = "OK: Linux environment ready (repaired)."
            return true
        }
        writeSetupState(app, "PARTIAL: Second stage exit=${secondStage.exitCode} (see log)")
        DebugInfo.lastSetupResult = "PARTIAL: Second stage exit=${secondStage.exitCode} (see log)"
        return false
    }

    private fun extractBootstrap(context: Context, stagingDir: File, log: (String) -> Unit) {
        val symlinks = ArrayList<Pair<String, String>>()
        var entries = 0
        context.assets.open("bootstrap-aarch64.zip").use { raw ->
            ZipInputStream(raw).use { zip ->
                val buffer = ByteArray(16384)
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == "SYMLINKS.txt") {
                        val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val parts = line!!.split("\u2190")
                            if (parts.size != 2) throw RuntimeException("Malformed symlink line: $line")
                            val target = parts[0]
                            val linkPath = File(stagingDir, parts[1]).absolutePath
                            symlinks.add(Pair(target, linkPath))
                            File(linkPath).parentFile?.mkdirs()
                        }
                    } else {
                        val target = File(stagingDir, name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                var n: Int
                                while (zip.read(buffer).also { n = it } != -1) out.write(buffer, 0, n)
                            }
                            if (name.startsWith("bin/") || name.startsWith("libexec") ||
                                name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods") ||
                                name == "etc/termux/bootstrap/termux-bootstrap-second-stage.sh"
                            ) {
                                chmodPerms(target.absolutePath, MODE_700)
                            }
                        }
                        entries++
                        if (entries % 2000 == 0) log("Extracted $entries files...")
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        if (symlinks.isEmpty()) throw RuntimeException("No SYMLINKS.txt encountered")
        for ((target, linkPath) in symlinks) {
            val linkFile = File(linkPath)
            if (linkFile.exists() || symlinkExists(linkFile)) {
                // Skip pre-existing entries; bootstrap should not have any.
                continue
            }
            Os.symlink(target, linkPath)
        }
        log("Extracted $entries files, ${symlinks.size} symlinks.")
    }

    private fun symlinkExists(f: File): Boolean {
        return try {
            FileInputStream(f).close()
            false
        } catch (e: Exception) {
            // If open fails but lstat succeeds, it is a dangling symlink.
            try {
                Os.lstat(f.absolutePath)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * Host-side permission repair (plain file ops, no guest exec).
     * Modes are SET exactly (masked to 0777), never OR-ed on:
     * - Every directory -> 0755 (searchable; a parent dir lacking +x
     *   makes execve fail EACCES even when bash itself is +x).
     * - Every regular file -> its bootstrap-zip mode masked to 0777
     *   (extraction left non-hardcoded files at 0600 under the app
     *   umask); bin/bash is forced to 0755.
     * - Files not present in the zip (created by setup itself) keep
     *   their current mode with special bits stripped.
     * Symlinks are skipped (chmod would follow them). This repairs
     * prefixes damaged by v1.0.4, whose repair OR-ed the hex literal
     * 0x700 (setgid+sticky+owner-read, octal 03700) onto bin/bash.
     * Idempotent: chmod runs only when the current 12-bit mode differs.
     */
    data class RepairStats(val dirsFixed: Int, val filesFixed: Int, val filesSeen: Int)

    fun repairPrefixPermissions(app: Application, log: (String) -> Unit): RepairStats {
        val usr = File(DzTerminalApp.hostUsr(app))
        if (!usr.isDirectory) return RepairStats(0, 0, 0)
        val zipModes = readZipModes(app)
        if (zipModes.isEmpty()) {
            log("Permission repair: could not read bootstrap zip modes — dirs + special-bit strip only.")
        }
        var dirsFixed = 0
        var filesFixed = 0
        var filesSeen = 0

        fun visit(f: File) {
            val st = try {
                Os.lstat(f.absolutePath)
            } catch (_: Exception) {
                return
            }
            val current = st.st_mode and 0xFFF
            val type = st.st_mode and 0xF000
            if (type == 0xA000) return // symlink: chmod would follow it; skip
            if (type == 0x4000) {
                if (current != PERM_DIR) {
                    try {
                        chmodPerms(f.absolutePath, PERM_DIR)
                        dirsFixed++
                    } catch (_: Exception) {
                    }
                }
                f.listFiles()?.forEach { visit(it) }
            } else {
                filesSeen++
                val rel = f.relativeTo(usr).path.replace(File.separatorChar, '/')
                val desired = when {
                    rel == "bin/bash" -> PERM_BASH
                    zipModes.containsKey(rel) -> zipModes.getValue(rel)
                    else -> current and PERM_MASK // not in zip: keep mode, strip special bits
                }
                if (current != desired) {
                    try {
                        chmodPerms(f.absolutePath, desired)
                        filesFixed++
                    } catch (_: Exception) {
                    }
                }
            }
        }

        visit(usr)
        return RepairStats(dirsFixed, filesFixed, filesSeen)
    }

    /**
     * Reads the bootstrap zip's own Unix modes. Android's ZipEntry does
     * not expose external attributes, so the central directory is
     * parsed by hand (fixed 46-byte headers; external attrs at offset
     * 38, Unix mode in the upper 16 bits). Returns relPath -> (mode &
     * 0777) for every regular-file entry. The parser was verified
     * against an independent Python central-directory parse: e.g.
     * bin/bash reads 0100700 in both, i.e. the zip does NOT ship
     * setuid/setgid/sticky bits — v1.0.4's 03700 came from the repair's
     * 0x700 hex literal, not from this parser.
     */
    private fun readZipModes(app: Application): Map<String, Int> {
        val tmp = File(app.cacheDir, "bootstrap-modes.zip")
        try {
            app.assets.open("bootstrap-aarch64.zip").use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            }
            val bytes = tmp.readBytes()
            // Find the End Of Central Directory record (PK\x05\x06),
            // scanning backwards from EOF (comment may be up to 64K).
            var eocd = -1
            var i = bytes.size - 22
            val min = maxOf(0, bytes.size - 22 - 65536)
            while (i >= min) {
                if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() &&
                    bytes[i + 2] == 0x05.toByte() && bytes[i + 3] == 0x06.toByte()
                ) {
                    eocd = i
                    break
                }
                i--
            }
            if (eocd < 0) return emptyMap()
            fun u16(off: Int): Int =
                (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
            fun u32(off: Int): Long =
                (bytes[off].toLong() and 0xFF) or
                    ((bytes[off + 1].toLong() and 0xFF) shl 8) or
                    ((bytes[off + 2].toLong() and 0xFF) shl 16) or
                    ((bytes[off + 3].toLong() and 0xFF) shl 24)
            val totalEntries = u16(eocd + 10)
            var cdOff = u32(eocd + 16).toInt()
            val out = HashMap<String, Int>()
            repeat(totalEntries) {
                if (cdOff + 46 > bytes.size) return@repeat
                // Central directory file header signature PK\x01\x02
                if (!(bytes[cdOff] == 0x50.toByte() && bytes[cdOff + 1] == 0x4B.toByte() &&
                        bytes[cdOff + 2] == 0x01.toByte() && bytes[cdOff + 3] == 0x02.toByte())
                ) {
                    return@repeat
                }
                val nameLen = u16(cdOff + 28)
                val extraLen = u16(cdOff + 30)
                val commentLen = u16(cdOff + 32)
                val extAttrs = u32(cdOff + 38)
                val mode = ((extAttrs shr 16) and 0xFFFFL).toInt()
                val nameStart = cdOff + 46
                val nameEnd = nameStart + nameLen
                if (nameEnd <= bytes.size) {
                    val name = String(bytes, nameStart, nameLen, Charsets.UTF_8)
                    if (!name.endsWith("/")) out[name] = mode and PERM_MASK
                }
                cdOff = nameEnd + extraLen + commentLen
            }
            return out
        } catch (e: Exception) {
            Log.w(TAG, "Could not read bootstrap zip modes", e)
            return emptyMap()
        } finally {
            try {
                tmp.delete()
            } catch (_: Exception) {
            }
        }
    }

    /** Runs the second-stage script under PROOT (v1.0.13 — see runSecondStageInner). Output is fully captured (see runCaptured). */
    data class SecondStageResult(val exitCode: Int, val output: String, val timedOut: Boolean) {
        val success: Boolean get() = !timedOut && exitCode == 0
    }

    /**
     * v1.0.17: patchBootstrapPaths is DELETED. It rewrote the fake
     * Termux prefix onto the host prefix in the startup scripts
     * for the direct-exec session. Under the proot session the
     * startup files must stay FAKE — proot translates them
     * through the bind. Any host patching left by <=v1.0.16 is
     * reverted by migrateTreeToFake.
     */

    /**
     * v1.0.11: neutralize the two Termux-app-specific first-run
     * scripts in etc/profile.d by FILENAME (not by string matching).
     * - init-termux-properties.sh: mkdir/cp under the Termux app's
     *   data dir (its first-run setup). It must never run here: this
     *   app must never read/write the installed Termux app's data.
     * - 01-termux-bootstrap-second-stage-fallback.sh: re-runs the
     *   second stage against Termux paths on shells where the lock
     *   is missing. The app runs the second stage itself (patched);
     *   the fallback must never fire from a shell.
     * Both are overwritten with a no-op (a lone ":" builtin + a
     * comment that deliberately does NOT contain the fake Termux
     * path, so the Debug fake-path scan stays clean). Only the
     * extracted copies are touched, never the zip. Content-
     * independent: works no matter what string form the file has,
     * and even if a package update rewrote it after patching.
     * A symlink in place of the file is replaced by a regular file
     * (never followed — a link could point outside our prefix).
     */
    private val NEUTRALIZED_SCRIPTS = listOf(
        "etc/profile.d/init-termux-properties.sh",
        "etc/profile.d/01-termux-bootstrap-second-stage-fallback.sh"
    )

    private const val NEUTRALIZED_CONTENT =
        "# DZ Terminal: neutralized (v1.0.11). This Termux-app " +
            "first-run script must never run here.\n:\n"

    fun neutralizeTermuxAppScripts(app: Application) {
        val hostUsr = DzTerminalApp.hostUsr(app)
        for (rel in NEUTRALIZED_SCRIPTS) {
            val f = File(hostUsr, rel)
            try {
                // Symlink? Replace the link itself with a regular file.
                val st = try { Os.lstat(f.absolutePath) } catch (_: Exception) { null }
                if (st != null && (st.st_mode and 0xF000) == 0xA000) {
                    f.delete()
                }
                if (f.exists() && f.readText() != NEUTRALIZED_CONTENT) {
                    f.writeText(NEUTRALIZED_CONTENT)
                    Log.i(TAG, "Neutralized $rel")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not neutralize $rel", e)
            }
        }
    }

    /**
     * v1.0.17: REMOVE the proot wrappers and restore the real
     * tools. v1.0.14–v1.0.16 renamed each package tool
     * (pkg/apt/apt-get/apt-cache/dpkg/termux-change-repo) to
     * bin/<tool>.real and put a wrapper script at bin/<tool>
     * that re-exec'd it under proot, because the direct-exec
     * session could not resolve the tools' compiled-in fake
     * paths. Under the proot session the tools run INSIDE
     * proot already, so the wrappers are dead weight — and
     * worse, a surviving wrapper would nest proot inside the
     * session proot. Runs on every prepareForShell (cheap:
     * 6 marker checks) so a stale wrapper can never linger.
     * Per tool:
     * - bin/<tool> is a wrapper (marker) + bin/<tool>.real
     *   exists -> delete the wrapper, rename .real back to
     *   <tool>, then normalize the restored file to FAKE
     *   paths (insurance: .real files were kept fake, but a
     *   host-patched .real would break under proot).
     * - bin/<tool> is a wrapper + no .real -> delete the
     *   wrapper (broken beyond repair; the real tool is gone).
     * - bin/<tool> is NOT a wrapper + a stale .real sits next
     *   to it (package upgrade installed a fresh tool over
     *   the wrapper) -> delete the stale .real.
     * - bin/<tool> missing -> report missing.
     * Symlinks are never followed (a link must keep pointing
     * at its target).
     */
    private val PROOT_WRAPPED_TOOLS = listOf(
        "pkg", "apt", "apt-get", "apt-cache", "dpkg", "termux-change-repo"
    )
    private const val PROOT_WRAPPER_MARKER = "DZ_TERMINAL_PROOT_WRAPPER"

    fun removeProotWrappers(app: Application): String {
        val hostUsr = DzTerminalApp.hostUsr(app)
        val fake = DzTerminalApp.GUEST_PREFIX
        val bin = File(hostUsr, "bin")
        if (!bin.isDirectory) return "(prefix not installed)"
        val statuses = ArrayList<String>()
        for (tool in PROOT_WRAPPED_TOOLS) {
            val toolFile = File(bin, tool)
            val realFile = File(bin, "$tool.real")
            try {
                if (!toolFile.exists()) {
                    // No tool at all; a stale .real without its
                    // tool is dead weight — remove it so the
                    // next unpatch does not trip over it.
                    if (realFile.exists()) realFile.delete()
                    statuses.add("$tool: missing")
                    continue
                }
                val st = try {
                    Os.lstat(toolFile.absolutePath)
                } catch (_: Exception) {
                    null
                }
                if (st != null && (st.st_mode and 0xF000) == 0xA000) {
                    statuses.add("$tool: skipped (symlink)")
                    continue
                }
                val isWrapper = toolFile.isFile &&
                    toolFile.readText().contains(PROOT_WRAPPER_MARKER)
                if (isWrapper) {
                    if (realFile.exists()) {
                        toolFile.delete()
                        if (realFile.renameTo(toolFile)) {
                            normalizeRealToFake(toolFile, hostUsr, fake)
                            statuses.add("$tool: wrapper removed, real tool restored")
                        } else {
                            statuses.add("$tool: FAILED (restore rename)")
                        }
                    } else {
                        toolFile.delete()
                        statuses.add("$tool: wrapper removed (.real missing)")
                    }
                } else {
                    // Already the real tool. A stale .real next
                    // to it (fresh tool installed over the
                    // wrapper by a package upgrade) is removed.
                    if (realFile.exists()) {
                        realFile.delete()
                        statuses.add("$tool: real (stale .real removed)")
                    } else {
                        statuses.add("$tool: real")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove wrapper for $tool", e)
                statuses.add("$tool: FAILED (${e.message})")
            }
        }
        val result = statuses.joinToString(", ")
        DebugInfo.lastWrapperRemoval = result
        return result
    }

    /** Rewrites host prefix -> fake prefix in ONE text file (idempotent). Binary-safe (NUL check). */
    private fun normalizeRealToFake(f: File, hostUsr: String, fake: String) {
        try {
            if (!f.isFile || f.length() > 2L * 1024 * 1024) return
            val content = f.readBytes()
            if (content.indexOf(0.toByte()) >= 0) return // binary: never touch
            val text = String(content, Charsets.UTF_8)
            if (!text.contains(hostUsr)) return
            f.writeText(text.replace(hostUsr, fake))
        } catch (_: Exception) {
            // Leave the file as-is.
        }
    }

    /**
     * v1.0.17: one-time migration of a <=v1.0.16 prefix to the
     * always-fake tree. v1.0.6–v1.0.16 patched the whole tree
     * fake->host for the direct-exec session; under the proot
     * session those host paths are WRONG (proot translates a
     * host-path shebang by prepending the rootfs -> nonexistent
     * path -> "cannot execute: required file not found" — the
     * v1.0.15/1.0.16 wrapper failure class). The unpatch
     * reverts every host path to fake. Nothing re-patches to
     * host anymore, so this runs once (flag file); a fresh
     * v1.0.17 install extracts an already-fake tree, so the
     * unpatch is a no-op there. The flag is written only after
     * the unpatch succeeds.
     */
    private const val TREE_FAKE_MIGRATED_FILE = ".dz-tree-fake-migrated"

    fun migrateTreeToFake(app: Application): String {
        val flag = File(app.filesDir, TREE_FAKE_MIGRATED_FILE)
        if (flag.exists()) return DebugInfo.lastTreeMigration.ifEmpty { "already migrated (flag present)" }
        val stats = unpatchWholeTreePaths(app)
        val text = "scanned=${stats.scanned} reverted=${stats.patched} stillDirty=${stats.stillDirty}"
        DebugInfo.lastWholeTreeUnpatch = text
        DebugInfo.lastTreeMigration = text
        try {
            flag.writeText(text)
        } catch (_: Exception) {
        }
        return text
    }

    /**
     * v1.0.17: harden the second-stage script's uid capture.
     * The stock script does `uid="$(id -u 2>&1)"` then rejects
     * the result unless it is all digits. Under proot the
     * Bionic linker warning ("failed to find generated linker
     * configuration from /linkerconfig/ld.config.txt") used to
     * land on stderr and pollute the capture, so the check
     * failed with "The uid 'WARNING: linker: ... 10627' ...
     * is not valid" and the second stage NEVER completed —
     * postinsts never ran (v1.0.16). The patch discards
     * stderr (2>/dev/null) and strips any residual non-digit
     * characters. It preserves `$?` semantics (no pipeline —
     * a failing `id` is still detected by the script's own
     * return-value check). Content patch, no paths involved,
     * so the unpatch walker can never revert it. Idempotent
     * (skips when already patched). Runs on every
     * prepareForShell: a fresh install extracts the pristine
     * script from the zip, so the patch must be re-applied.
     */
    fun patchSecondStageUid(app: Application) {
        val script = File(
            DzTerminalApp.hostUsr(app),
            "etc/termux/bootstrap/termux-bootstrap-second-stage.sh"
        )
        try {
            if (!script.isFile) return
            val content = script.readText()
            if (content.contains("id -u 2>/dev/null")) return // already patched
            val old = "uid=\"\$(id -u 2>&1)\""
            if (!content.contains(old)) {
                Log.w(TAG, "Second-stage uid line not found — uid patch skipped")
                return
            }
            val patched = content.replace(
                old,
                "uid=\"\$(id -u 2>/dev/null)\"\n\tuid=\"\${uid//[^0-9]/}\""
            )
            script.writeText(patched)
            Log.i(TAG, "Patched second-stage uid capture (stderr + non-digit strip)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not patch second-stage uid capture", e)
        }
    }

    /**
     * v1.0.27: shared-storage shortcuts inside the guest
     * (Termux parity — termux-setup-storage). Runs on every
     * Console start via prepareForShell (idempotent; creates
     * only what is missing, never deletes user data).
     *
     * - <prefix>/home/storage -> /storage/emulated/0
     *   (~/storage in the guest: `cd ~/storage` works, and
     *   `cd ~/storage/0` is NOT needed — ~/storage IS the
     *   shared-storage root, exactly like Termux).
     * - <prefix>/sdcard -> /storage/emulated/0
     *   (guest /sdcard: `ls /sdcard` works even if the
     *   proot -b /sdcard bind is ever dropped — the symlink
     *   resolves through the /storage bind. When the bind IS
     *   present it mounts over the symlink, same content.)
     * - <prefix>/storage is a REAL DIRECTORY, never a
     *   symlink: it is the bind target for proot's
     *   `-b /storage`, which provides /storage/emulated/0.
     *   A symlink here would double the path
     *   (/storage -> /storage/emulated/0, then
     *   /storage/emulated/0 -> .../emulated/0/emulated/0).
     *
     * The symlinks' target is an ABSOLUTE guest/host path
     * (/storage/emulated/0): under proot, opening
     * ~/storage/<file> translates to
     * <prefix>/home/storage/<file> and the host kernel
     * follows the symlink to /storage/emulated/0/<file> —
     * the app's own shared storage. Access is still gated
     * by the runtime storage permission (ConsoleActivity
     * requests it on Console start); without it the
     * kernel returns EACCES, symlink or bind alike.
     * Symlinks are created with Os.symlink (never followed);
     * a real file/dir already sitting at the link path is
     * left alone unless it is an EMPTY dir, which is
     * replaced (it cannot hold user data).
     */
    fun ensureStorageLinks(app: Application): String {
        val hostUsr = DzTerminalApp.hostUsr(app)
        val results = ArrayList<String>()
        // /storage bind target: real directory.
        try {
            val storageDir = File(hostUsr, "storage")
            val st = try { Os.lstat(storageDir.absolutePath) } catch (_: Exception) { null }
            if (st != null && (st.st_mode and 0xF000) == 0xA000) {
                // A symlink at /storage would break the bind's
                // /storage/emulated/0 path (see KDoc) — remove it.
                storageDir.delete()
                storageDir.mkdirs()
                results.add("storage: stray symlink removed, real dir created")
            } else if (storageDir.mkdirs() || storageDir.isDirectory) {
                results.add("storage: bind-target dir ok")
            } else {
                results.add("storage: FAILED (dir not created)")
            }
        } catch (e: Exception) {
            results.add("storage: FAILED (${e.message})")
        }
        // ~/storage symlink -> /storage/emulated/0
        results.add(ensureGuestSymlink(
            File(hostUsr, "home/storage"), "/storage/emulated/0", "home/storage"))
        // /sdcard symlink -> /storage/emulated/0
        results.add(ensureGuestSymlink(
            File(hostUsr, "sdcard"), "/storage/emulated/0", "sdcard"))
        val text = results.joinToString("; ")
        DebugInfo.lastStorageLinksStatus = text
        return text
    }

    /** Creates a guest symlink (link -> target) if missing, or
     *  repairs it when it points elsewhere. Returns one status
     *  line. Never follows the link; never deletes non-empty
     *  real dirs. */
    private fun ensureGuestSymlink(link: File, target: String, label: String): String {
        return try {
            val st = try { Os.lstat(link.absolutePath) } catch (_: Exception) { null }
            if (st != null) {
                if ((st.st_mode and 0xF000) == 0xA000) {
                    val current = try { Os.readlink(link.absolutePath) } catch (_: Exception) { null }
                    if (current == target) {
                        "$label: symlink -> $target (ok)"
                    } else {
                        link.delete()
                        Os.symlink(target, link.absolutePath)
                        "$label: symlink fixed -> $target"
                    }
                } else if (link.isDirectory && (link.listFiles()?.isEmpty() == true)) {
                    link.delete()
                    Os.symlink(target, link.absolutePath)
                    "$label: empty dir replaced with symlink -> $target"
                } else {
                    "$label: exists as real file/dir, left alone"
                }
            } else {
                link.parentFile?.mkdirs()
                Os.symlink(target, link.absolutePath)
                "$label: symlink created -> $target"
            }
        } catch (e: Exception) {
            "$label: FAILED (${e.message})"
        }
    }

    /**
     * v1.0.15: ensure the proot bind target exists inside OUR OWN
     * prefix. proot's `-b <hostPrefix>:/data/data/com.termux/files/usr`
     * bind needs the guest-side path to exist inside the rootfs
     * (-r <prefix>). The bootstrap zip does not ship a
     * `data/data/com.termux/files/usr` directory, so the bind target
     * is created here — inside OUR OWN data dir. The path only
     * MIRRORS the guest path's name; it is our own storage, never
     * the real Termux app's data dir. Also ensures <prefix>/tmp
     * exists (PROOT_TMP_DIR) and records both for Debug.
     * Idempotent; safe to call before every proot invocation.
     *
     * v1.0.18: also ensures the apt cache dirs. apt downloads
     * .debs to /data/data/com.termux/cache/apt/archives/partial
     * (in real Termux that is the app's cache dir, a sibling of
     * files/usr). Under our proot that fake path translates
     * (rootfs prepend) to <prefix>/data/data/com.termux/cache/...,
     * which does NOT exist — the bind only maps
     * <hostPrefix>:/data/data/com.termux/files/usr. Without
     * these dirs `pkg install` fails with "Archives directory
     * .../cache/apt/archives/partial is missing". The cache
     * lives inside OUR prefix (self-contained; survives app
     * cache clears) — the host app cache dir is NOT bound.
     */
    fun ensureProotBindTarget(app: Application): Boolean {
        val hostUsr = DzTerminalApp.hostUsr(app)
        try {
            File(hostUsr, "tmp").mkdirs()
        } catch (_: Exception) {
        }
        val ok = try {
            val target = File(hostUsr + DzTerminalApp.GUEST_PREFIX)
            target.mkdirs()
            target.isDirectory
        } catch (_: Exception) {
            false
        }
        DebugInfo.lastProotBindTargetExists = ok
        DebugInfo.lastProotTmpDir = "$hostUsr/tmp"
        // v1.0.18: apt cache dirs (see KDoc above). mkdirs
        // creates the parents too.
        DebugInfo.lastAptCacheDirExists = try {
            val partial = File(hostUsr + "/data/data/com.termux/cache/apt/archives/partial")
            partial.mkdirs()
            partial.isDirectory
        } catch (_: Exception) {
            false
        }
        return ok
    }

    /**
     * v1.0.17: ordering guarantee for every shell start. Runs
     * AFTER any extraction/repair/second-stage that could
     * (re)write the startup files, and BEFORE the shell reads
     * them: neutralize the Termux-app-specific scripts, remove
     * any stale proot wrappers (restoring the real tools),
     * ensure the proot bind target, migrate the tree to fake
     * (one-time), and harden the second-stage uid capture.
     * The tree is NEVER patched to host paths anymore — under
     * the proot session it stays fake. Called from
     * SessionManager.createSession (every Console start) and
     * from runSecondStageInner (every second-stage run).
     */
    fun prepareForShell(app: Application) {
        neutralizeTermuxAppScripts(app)
        // v1.0.17: wrappers removed BEFORE the migration
        // unpatch, so a restored tool is never mistaken for a
        // wrapper and the unpatch sees real tool files.
        removeProotWrappers(app)
        // v1.0.15: bind target for every proot invocation
        // (session + second stage).
        ensureProotBindTarget(app)
        // v1.0.27: shared-storage guest shortcuts (~/storage
        // + /sdcard symlinks, /storage bind-target dir) —
        // created before the shell starts so the first
        // prompt already sees them.
        ensureStorageLinks(app)
        // v1.0.17: one-time migration of a <=v1.0.16 prefix
        // (host-patched tree) back to fake.
        migrateTreeToFake(app)
        // v1.0.17: harden the second-stage uid capture on every
        // start (a fresh install extracts the pristine script).
        patchSecondStageUid(app)
    }

    /**
     * v1.0.17: whole-tree path rewrite, UNPATCH direction only
     * (host -> fake). The PATCH direction (fake -> host,
     * v1.0.12) is DELETED: under the proot session the tree
     * stays fake, and host-patched files break under proot
     * (proot translates a host-path shebang by prepending the
     * rootfs -> nonexistent path -> "cannot execute: required
     * file not found"). unpatchWholeTreePaths is used only by
     * the one-time migrateTreeToFake (reverting <=v1.0.16 host
     * patching). Same binary-safety and skip rules as before.
     */
    data class WholeTreePatchStats(val scanned: Int, val patched: Int, val stillDirty: Int)

    fun unpatchWholeTreePaths(app: Application): WholeTreePatchStats =
        rewriteTreePaths(app, DzTerminalApp.hostUsr(app), DzTerminalApp.GUEST_PREFIX)

    private fun rewriteTreePaths(app: Application, from: String, to: String): WholeTreePatchStats {
        val hostUsr = DzTerminalApp.hostUsr(app)
        val fakeBytes = from.toByteArray(Charsets.UTF_8)
        val hostBytes = to.toByteArray(Charsets.UTF_8)
        val usr = File(hostUsr)
        var scanned = 0
        var patched = 0
        var stillDirty = 0
        val maxSize = 2L * 1024 * 1024

        fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
            if (needle.isEmpty() || hay.size < needle.size) return -1
            outer@ for (i in from..hay.size - needle.size) {
                for (j in needle.indices) {
                    if (hay[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }

        fun replaceAll(content: ByteArray, needle: ByteArray, repl: ByteArray): ByteArray {
            val out = ArrayList<Byte>()
            var pos = 0
            while (true) {
                val at = indexOf(content, needle, pos)
                if (at < 0) {
                    for (i in pos until content.size) out.add(content[i])
                    break
                }
                for (i in pos until at) out.add(content[i])
                for (b in repl) out.add(b)
                pos = at + needle.size
            }
            val arr = ByteArray(out.size)
            for (i in out.indices) arr[i] = out[i]
            return arr
        }

        fun visit(f: File) {
            val st = try {
                Os.lstat(f.absolutePath)
            } catch (_: Exception) {
                return
            }
            val type = st.st_mode and 0xF000
            if (type == 0xA000) return // symlink: never follow
            if (type == 0x4000) {
                // Skip user data + scratch: never rewrite the user's own files.
                if (f.parentFile == usr && (f.name == "home" || f.name == "tmp")) return
                f.listFiles()?.forEach { visit(it) }
                return
            }
            if (type != 0x8000) return // not a regular file (fifo/socket/device): skip
            scanned++
            try {
                if (f.length() > maxSize) return
                val content = f.readBytes()
                // Any NUL byte => binary (ELF, .so, archives, terminfo...) — never patch.
                if (content.indexOf(0.toByte()) >= 0) return
                if (indexOf(content, fakeBytes, 0) < 0) return
                val updated = replaceAll(content, fakeBytes, hostBytes)
                FileOutputStream(f).use { it.write(updated) }
                // Verify: a file that still contains the fake path
                // after the write counts as stillDirty, not patched.
                if (indexOf(f.readBytes(), fakeBytes, 0) >= 0) {
                    stillDirty++
                } else {
                    patched++
                }
            } catch (_: Exception) {
                // Unreadable/unwritable file: leave it.
            }
        }

        if (usr.isDirectory) visit(usr)
        return WholeTreePatchStats(scanned, patched, stillDirty)
    }

    /**
     * v1.0.17: Debug check — the tree must stay FAKE for the
     * proot session. Scans the key startup + tool files and
     * reports per file: "fake" (correct — fake prefix, proot
     * translates it), "HOST-PATCHED" (wrong — a host path
     * left by <=v1.0.16 that proot cannot translate), or
     * "neutralized" (the two Termux-app first-run scripts,
     * intentionally no-ops). Binaries are not scanned (they
     * legitimately contain the baked-in path and are never
     * read as text).
     */
    fun checkTreeFake(app: Application): String {
        val hostUsr = DzTerminalApp.hostUsr(app)
        val fake = DzTerminalApp.GUEST_PREFIX
        val files = ArrayList<String>()
        files.add("$hostUsr/etc/profile")
        files.add("$hostUsr/etc/bash.bashrc")
        try {
            File("$hostUsr/etc/profile.d").listFiles()?.forEach { f ->
                if (f.isFile) files.add(f.absolutePath)
            }
        } catch (_: Exception) {
        }
        files.add("$hostUsr/bin/pkg")
        files.add("$hostUsr/bin/apt-key")
        val neutralized = setOf(
            "init-termux-properties.sh",
            "01-termux-bootstrap-second-stage-fallback.sh"
        )
        val results = ArrayList<String>()
        var checked = 0
        for (path in files) {
            try {
                val f = File(path)
                if (!f.isFile) continue
                checked++
                val content = f.readText()
                val name = f.name
                when {
                    name in neutralized && content.contains("neutralized (v1.0.11)") ->
                        results.add("$name: neutralized")
                    content.contains(hostUsr) ->
                        results.add("$name: HOST-PATCHED (wrong for proot)")
                    content.contains(fake) ->
                        results.add("$name: fake (correct)")
                    else ->
                        results.add("$name: no prefix refs")
                }
            } catch (_: Exception) {
            }
        }
        val bad = results.count { it.contains("HOST-PATCHED") }
        val prefix = if (bad == 0)
            "FAKE: $checked files checked, no host paths (tree is fake — correct for proot)"
        else
            "HOST-PATCHED: $bad file(s) still contain host paths (proot cannot translate them)"
        return prefix + "\n" + results.joinToString("\n")
    }

    private fun runSecondStage(app: Application, log: (String) -> Unit): SecondStageResult {
        // v1.0.7: the whole second-stage run is guarded — a start
        // failure (e.g. direct exec of bash denied) returns a failed
        // result, never a thrown exception.
        return try {
            runSecondStageInner(app, log)
        } catch (t: Throwable) {
            Log.w(TAG, "Second stage threw", t)
            val msg = "Second stage failed to start: ${t.javaClass.simpleName}: ${t.message}"
            log(msg)
            DebugInfo.lastSecondStageExit = -2
            DebugInfo.lastSecondStageOutput = msg
            writeSecondStageLog(app, "exit=-2\n$msg")
            SecondStageResult(-2, msg, false)
        }
    }

    private fun runSecondStageInner(app: Application, log: (String) -> Unit): SecondStageResult {
        SessionManager.ensureGuestDirs(app)
        // Neutralize + migrate + uid-patch before running
        // (see prepareForShell — v1.0.11 ordering guarantee).
        prepareForShell(app)
        // v1.0.13: run the second stage UNDER PROOT (not direct exec).
        // dpkg is an ELF binary with its config path COMPILED IN (the
        // fake Termux prefix) — no text patch can fix a binary's
        // compiled-in path, so direct exec failed with "error opening
        // configuration directory ... Permission denied" and setup
        // stayed PARTIAL forever. Under proot, the fake path resolves
        // through the bind onto OUR prefix files, so dpkg finds its
        // config and the postinsts run. The guest sees a PRISTINE
        // Termux tree (all fake paths): v1.0.17 the tree is ALWAYS
        // fake (no whole-tree host patch anymore), so no unpatch
        // is needed before the run.
        DebugInfo.lastSecondStageMode = "proot"
        log("Second stage: running under proot (fake-path bind onto our prefix).")
        val hostUsr = DzTerminalApp.hostUsr(app)
        val fake = DzTerminalApp.GUEST_PREFIX
        // v1.0.15: bind target must exist before proot runs.
        // Idempotent.
        ensureProotBindTarget(app)
        // Guest-view script path: proot translates it onto our prefix.
        // The script is still invoked via `bash <script>` (never exec'd
        // directly) to bypass its Termux shebang.
        val args = SessionManager.prootArgv(
            app,
            listOf("/bin/bash", "$fake/etc/termux/bootstrap/termux-bootstrap-second-stage.sh")
        )
        // v1.0.15: record the EXACT proot argv for Debug — no more
        // guessing about what proot was actually given.
        DebugInfo.lastSecondStageProotArgv = args.joinToString(" ")
        val pb = ProcessBuilder(args)
        pb.directory(File(hostUsr))
        SessionManager.applyEnv(pb, prootSecondStageEnv(hostUsr))
        val captured = SessionManager.runCaptured(pb, 180)
        val tail = captured.output.takeLast(4000)
        DebugInfo.lastSecondStageExit = captured.exitCode
        DebugInfo.lastSecondStageOutput = tail
        writeSecondStageLog(app, "exit=${captured.exitCode} timedOut=${captured.timedOut}\n$tail")
        log("Second stage exit=${captured.exitCode}" + if (captured.timedOut) " (timed out)" else "")
        if (tail.isNotBlank()) log("Second stage output:\n$tail")
        Log.i(TAG, "Second stage exit=${captured.exitCode} output tail:\n$tail")
        return SecondStageResult(captured.exitCode, tail, captured.timedOut)
    }

    /**
     * Guest-view environment for the proot second-stage run. All
     * paths are GUEST paths (the fake Termux prefix): under proot,
     * the fake path resolves through the bind onto OUR prefix
     * files. NO LD_PRELOAD: proot already translates paths, and
     * libtermux-exec would rewrite guest paths using
     * TERMUX__PREFIX and fight proot.
     *
     * v1.0.15: ONE host-path exception — PROOT_TMP_DIR. proot (the
     * HOST process) reads it from ITS OWN environment, before any
     * guest translation, and it must point at a dir the app can
     * write. Without it proot tries host /tmp (not writable by the
     * app on Android) and dies with "can't create temporary file"
     * before the guest exec runs — the v1.0.14 second-stage and
     * wrapper failure. The guest TMPDIR (fake path) below is
     * separate and stays guest.
     */
    private fun prootSecondStageEnv(hostUsr: String): Array<String> {
        val fake = DzTerminalApp.GUEST_PREFIX
        val list = ArrayList<String>()
        list.add("PREFIX=$fake")
        list.add("TERMUX__PREFIX=$fake")
        list.add("HOME=$fake/home")
        list.add("TERM=xterm-256color")
        list.add("COLORTERM=truecolor")
        list.add("LANG=C.UTF-8")
        list.add("PATH=$fake/bin:/system/bin:/vendor/bin")
        list.add("PWD=$fake/home")
        list.add("TMPDIR=$fake/tmp")
        list.add("SHELL=$fake/bin/bash")
        // The guest linker resolves this through proot's bind onto
        // OUR prefix lib dir (the binaries' baked-in RUNPATH is the
        // same fake path).
        list.add("LD_LIBRARY_PATH=$fake/lib")
        // v1.0.15: HOST path for the proot process itself (see KDoc).
        list.add("PROOT_TMP_DIR=$hostUsr/tmp")
        // Android passthroughs (same set SessionManager forwards).
        for (name in listOf(
            "BOOTCLASSPATH", "ANDROID_ROOT", "ANDROID_DATA",
            "EXTERNAL_STORAGE", "ANDROID_ART_ROOT", "DEX2OATBOOTCLASSPATH",
            "ANDROID_I18N_ROOT", "ANDROID_RUNTIME_ROOT", "ANDROID_TZDATA_ROOT"
        )) {
            val value = System.getenv(name)
            if (value != null) list.add("$name=$value")
        }
        list.add("ANDROID__BUILD_VERSION_SDK=" + android.os.Build.VERSION.SDK_INT)
        return list.toTypedArray()
    }

    private fun deleteRecursively(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursively(it) }
        f.delete()
    }

    data class Result(val ok: Boolean, val message: String)
}
