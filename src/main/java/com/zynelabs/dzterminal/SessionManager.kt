package com.zynelabs.dzterminal

import android.app.Application
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds the terminal session and the environment.
 *
 * Exec model (v1.0.17): PROOT session — the dual-context design ends.
 *
 * v1.0.6–v1.0.16 ran the interactive shell on DIRECT exec (host
 * paths) while the package tools / second stage ran under proot
 * (fake paths). That split kept breaking on every helper that
 * crosses the boundary: dpkg's compiled-in config path (v1.0.13),
 * apt-key's shebang (v1.0.16 — the whole-tree host patch had
 * rewritten it, so apt inside proot could not execute it, so
 * `apt update` reported the repo "not signed"), and next would
 * have been gpgv, dpkg-split, ... Whack-a-mole. Under a proot
 * session the tree stays FAKE (pristine Termux) and EVERY binary's
 * compiled-in fake path resolves through the fake->host bind —
 * bash, pkg, apt, dpkg, apt-key, gpgv, curl's CA bundle, the
 * SYS_BASHRC profile read — with no patching and no wrappers.
 *
 * The session runs proot with the PTY as stdin/stdout/stderr
 * (TerminalSession's JNI spawns proot exactly like it spawned
 * bash before; proot passes the fds through — this is what
 * proot-distro does). The guest env is PURE FAKE paths:
 * - NO LD_PRELOAD: libtermux-exec would rewrite guest paths
 *   using TERMUX__PREFIX and fight proot. Under proot the fake
 *   paths already resolve, so the preload is unnecessary.
 * - NO LD_LIBRARY_PATH: the bootstrap binaries' RUNPATH is the
 *   fake lib dir, which proot translates onto our prefix lib.
 * - NO host-path CA env vars (SSL_CERT_FILE etc.): curl's
 *   compiled-in CA path is the fake prefix, which resolves via
 *   the bind to OUR CA bundle. A host-path env var would break
 *   inside the guest.
 * - ONE host-path exception: PROOT_TMP_DIR. proot (the HOST
 *   process) reads it from ITS OWN env before any guest
 *   translation, and it must point at a dir the app can write
 *   (host /tmp is not writable by the app on Android — without
 *   it proot dies on "can't create temporary file").
 * - /linkerconfig is bound when it exists on the host: the
 *   Bionic linker opens /linkerconfig/ld.config.txt on every
 *   ELF exec; without the bind proot translates that to
 *   <prefix>/linkerconfig/ld.config.txt (nonexistent) and the
 *   linker prints "failed to find generated linker
 *   configuration" to stderr on EVERY command. That warning
 *   polluted the second-stage script's `id -u` capture
 *   (v1.0.16: "The uid 'WARNING: linker: ... 10627' ... is not
 *   valid"). The bind kills the warning at the source; the
 *   second-stage uid capture is ALSO hardened (PrefixSetup).
 *
 * Session startup: `bash --init-file <GUEST dz-init.sh>`.
 * dz-init.sh lives at <hostUsr>/dz-init.sh and is referenced by
 * its GUEST path so proot translates it. Its content sources
 * $PREFIX/etc/profile (fake under proot -> resolves), which
 * sources etc/bash.bashrc and ~/.bashrc. bash's compiled-in
 * SYS_BASHRC read now resolves via the bind too, so the old
 * "Permission denied" warning line is gone.
 */
object SessionManager {

    /** Exec mode reported in Debug. */
    const val EXEC_MODE = "proot"

    fun createSession(app: Application, client: TerminalSessionClient): TerminalSession {
        ensureGuestDirs(app)
        // Neutralize + migrate + uid-patch BEFORE the shell reads
        // anything — setup/repair only prepare when they run, but
        // the session must work even when setup state is already OK.
        PrefixSetup.prepareForShell(app)
        ensureSessionInitFile(app)
        return TerminalSession(
            DzTerminalApp.prootPath(app),
            DzTerminalApp.hostHome(app),
            prootArgv(app, listOf("/bin/bash", "--init-file", sessionInitFileGuestPath()), sessionWorkDir()).toTypedArray(),
            prootGuestEnv(app),
            1000,
            client
        )
    }

    /**
     * The session's guest working dir. v1.0.18: the guest HOME
     * path, NOT "/home". The shell starts with cwd == $HOME, so
     * bash shows `~` in the prompt. With -w /home the cwd was
     * guest /home while $HOME was the guest prefix home — the
     * same host dir via the bind, but different path strings,
     * so bash showed `/home` instead of `~`. The second stage
     * keeps -w /home (it does not use $HOME the same way).
     */
    fun sessionWorkDir(): String =
        DzTerminalApp.GUEST_PREFIX + "/home"

    /**
     * Full proot argv for a guest command: [proot, -r, hostUsr,
     * binds..., -w, workDir, guestArgv...]. Used by the session, the
     * second stage, the Test bash probe, and QPYPI one-shots.
     * workDir defaults to /home (second stage, probes, one-shots);
     * the interactive session passes sessionWorkDir() (guest HOME).
     */
    fun prootArgv(app: Application, guestArgv: List<String>, workDir: String = "/home"): List<String> {
        val hostUsr = DzTerminalApp.hostUsr(app)
        val args = ArrayList<String>()
        args.add(DzTerminalApp.prootPath(app))
        args.add("-r")
        args.add(hostUsr)
        args.addAll(prootBindArgs(hostUsr))
        args.add("-w")
        args.add(workDir)
        args.addAll(guestArgv)
        return args
    }

    /** The proot -b bind args. The first bind maps the fake guest prefix onto OUR prefix.
     *
     *  v1.0.27: the /sdcard + /storage binds are what make
     *  shared storage visible in the guest (`ls /sdcard`,
     *  `cd /storage/emulated/0`). They were present since
     *  earlier versions but never worked on their own: the
     *  app never held the runtime storage permission on
     *  Console start, so the proot process got EACCES on
     *  every shared-storage read, and the guest had no
     *  ~/storage shortcut. ConsoleActivity now requests
     *  the permission before starting the session, and
     *  PrefixSetup.ensureStorageLinks creates the
     *  ~/storage + /sdcard symlinks + the /storage
     *  bind-target dir on every Console start. */

    private fun prootBindArgs(hostUsr: String): List<String> {
        val fake = DzTerminalApp.GUEST_PREFIX
        val args = ArrayList<String>()
        fun bind(spec: String) {
            args.add("-b")
            args.add(spec)
        }
        bind("$hostUsr:$fake")
        bind("/dev")
        bind("/proc")
        bind("/sys")
        bind("/system")
        bind("/vendor")
        bind("/apex")
        bind("/system_ext")
        bind("/product")
        bind("/sdcard")
        bind("/storage")
        // /linkerconfig: the Bionic linker opens
        // /linkerconfig/ld.config.txt on every ELF exec. Without
        // this bind proot translates it to a nonexistent path
        // inside the prefix and the linker warns on every command
        // (and pollutes `id -u` captures).
        // v1.0.18: bind ONLY when the host dir is actually
        // READABLE. v1.0.17 bound it on exists() alone, but the
        // app cannot read host /linkerconfig, so proot printed
        // "can't sanitize binding /linkerconfig: Permission
        // denied" and the bind never took effect (while Debug
        // misleadingly said "bound: yes"). Unreadable ->
        // omit the bind silently; the uid fix already
        // neutralizes the warning's pollution.
        val linkerconfig = File("/linkerconfig")
        if (linkerconfig.exists() && linkerconfig.canRead()) {
            bind("/linkerconfig")
            DebugInfo.lastLinkerconfigBound = true
            DebugInfo.lastLinkerconfigStatus = "bound"
        } else {
            DebugInfo.lastLinkerconfigBound = false
            DebugInfo.lastLinkerconfigStatus = "skipped (not readable)"
        }
        return args
    }

    /** The session proot argv (for Debug display). */
    fun sessionProotArgv(app: Application): List<String> =
        prootArgv(app, listOf("/bin/bash", "--init-file", sessionInitFileGuestPath()), sessionWorkDir())

    /** Host-side path of the session init file (written by ensureSessionInitFile). */
    fun sessionInitFileHostPath(app: Application): String =
        DzTerminalApp.hostUsr(app) + "/dz-init.sh"

    /** Guest-side path of the session init file (used in the session argv — proot translates it). */
    fun sessionInitFileGuestPath(): String =
        DzTerminalApp.GUEST_PREFIX + "/dz-init.sh"

    /**
     * (Re)writes the session init wrapper. Tiny file, written on
     * every Console start so it is always current. It is READ by
     * bash (never exec'd), so no exec bit is needed. It references
     * $PREFIX and $HOME, both set in prootGuestEnv() to FAKE
     * paths — under proot they resolve through the bind.
     */
    fun ensureSessionInitFile(app: Application) {
        val content =
            "# DZ Terminal session init (v1.0.17). Replaces the old login-shell invocation.\n" +
            "# A login shell made bash read its COMPILED-IN profile path, and\n" +
            "# --init-file reads THIS file instead. It sources the prefix\n" +
            "# profile (\$PREFIX is the FAKE guest prefix under proot,\n" +
            "# resolved through the bind onto our prefix); the profile\n" +
            "# itself sources etc/bash.bashrc and ~/.bashrc for\n" +
            "# interactive shells. If the profile is missing, ~/.bashrc\n" +
            "# is sourced directly. Missing files are skipped silently\n" +
            "# so the shell always starts.\n" +
            "if [ -f \"\$PREFIX/etc/profile\" ]; then\n" +
            "  . \"\$PREFIX/etc/profile\"\n" +
            "elif [ -f \"\$HOME/.bashrc\" ]; then\n" +
            "  . \"\$HOME/.bashrc\"\n" +
            "fi\n"
        try {
            File(sessionInitFileHostPath(app)).writeText(content)
        } catch (_: Exception) {
            // If the wrapper cannot be written, the session still starts
            // (bash errors on the missing --init-file and shows a prompt).
        }
    }

    /**
     * Creates the dirs the guest needs inside OUR prefix: home/
     * (the session cwd) and tmp/ (TMPDIR + PROOT_TMP_DIR).
     * Idempotent.
     */
    fun ensureGuestDirs(app: Application) {
        val usr = File(DzTerminalApp.hostUsr(app))
        try {
            File(usr, "home").mkdirs()
            File(usr, "tmp").mkdirs()
        } catch (_: Exception) {
        }
    }

    /**
     * Guest-view environment for the PROOT session. All paths are
     * FAKE guest paths (the fake Termux prefix) — under proot they
     * resolve through the bind onto OUR prefix files. NO
     * LD_PRELOAD, NO LD_LIBRARY_PATH, NO host-path CA vars (see
     * the file KDoc). The single host-path entry is PROOT_TMP_DIR,
     * which proot reads from its OWN env before translating.
     */
    fun prootGuestEnv(app: Application): Array<String> {
        val hostUsr = DzTerminalApp.hostUsr(app)
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
        // v1.0.15: HOST path for the proot process itself (see file
        // KDoc). Without it proot tries host /tmp (not writable by
        // the app on Android) and dies with "can't create temporary
        // file" before the guest exec runs.
        list.add("PROOT_TMP_DIR=$hostUsr/tmp")
        // Android passthroughs Termux itself forwards. The
        // termux-exec postinst needs ANDROID__BUILD_VERSION_SDK (or
        // getprop); we pass the SDK directly.
        addIfPresent(list, "BOOTCLASSPATH")
        addIfPresent(list, "ANDROID_ROOT")
        addIfPresent(list, "ANDROID_DATA")
        addIfPresent(list, "EXTERNAL_STORAGE")
        addIfPresent(list, "ANDROID_ART_ROOT")
        addIfPresent(list, "DEX2OATBOOTCLASSPATH")
        addIfPresent(list, "ANDROID_I18N_ROOT")
        addIfPresent(list, "ANDROID_RUNTIME_ROOT")
        addIfPresent(list, "ANDROID_TZDATA_ROOT")
        list.add("ANDROID__BUILD_VERSION_SDK=" + android.os.Build.VERSION.SDK_INT)
        return list.toTypedArray()
    }

    private fun addIfPresent(list: ArrayList<String>, name: String) {
        val value = System.getenv(name)
        if (value != null) list.add("$name=$value")
    }

    /** Result of a captured ProcessBuilder run (stdout+stderr merged). */
    data class CapturedResult(val exitCode: Int, val output: String, val timedOut: Boolean)

    /**
     * Runs a ProcessBuilder to completion, merging stderr into stdout
     * and draining the output stream on a reader thread. The reader is
     * JOINED after waitFor() returns — reading the buffer immediately
     * after waitFor() without joining loses trailing output (the
     * v1.0.3 second-stage "exit=1 with no output" bug).
     *
     * v1.0.7: a START failure (e.g. direct exec of bash denied with
     * EACCES on-device) is returned as CapturedResult(-2, "START
     * FAILED: ...") instead of throwing. Before this, the thrown
     * IOException escaped repairAndRetrySecondStage into
     * ConsoleActivity's background thread, whose uncaught exception
     * killed the whole app process — the v1.0.6 "tap Console, app
     * exits" crash. A failed start is data, not a fatal error.
     */
    fun runCaptured(pb: ProcessBuilder, timeoutSeconds: Long): CapturedResult {
        pb.redirectErrorStream(true)
        val proc: Process
        try {
            proc = pb.start()
        } catch (e: Exception) {
            return CapturedResult(-2, "START FAILED: ${e.javaClass.simpleName}: ${e.message}", false)
        }
        val output = StringBuilder()
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    val buf = CharArray(8192)
                    var n: Int
                    while (r.read(buf).also { n = it } != -1) {
                        synchronized(output) {
                            output.append(buf, 0, n)
                            if (output.length > 30000) output.delete(0, output.length - 30000)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = try {
            proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            val out = synchronized(output) { output.toString() }
            return CapturedResult(-3, out + "\nINTERRUPTED: ${e.message}", false)
        }
        if (!finished) {
            proc.destroyForcibly()
            try { proc.waitFor(5, TimeUnit.SECONDS) } catch (_: Exception) {}
            try { reader.join(3000) } catch (_: Exception) {}
            val out = synchronized(output) { output.toString() }
            return CapturedResult(-1, out, true)
        }
        try { reader.join(5000) } catch (_: Exception) {}
        val out = synchronized(output) { output.toString() }
        return CapturedResult(proc.exitValue(), out, false)
    }

    /**
     * Debug probe: trivial proot run (`proot --help`). This only
     * proves the shipped proot binary itself executes.
     *
     * v1.0.15: PROOT_TMP_DIR is set to the HOST prefix tmp — proot
     * reads it from ITS OWN env, and host /tmp is not writable by
     * the app on Android (without it proot dies on "can't create
     * temporary file" before running anything).
     */
    fun runProotProbe(app: Application): CapturedResult {
        val hostUsr = DzTerminalApp.hostUsr(app)
        ensureGuestDirs(app)
        val pb = ProcessBuilder(DzTerminalApp.prootPath(app), "--help")
        pb.environment()["PROOT_TMP_DIR"] = "$hostUsr/tmp"
        DebugInfo.lastProotTmpDir = "$hostUsr/tmp"
        return runCaptured(pb, 30)
    }

    /**
     * Debug probe: the proot session path, minimal repro —
     * `proot ... /bin/bash -c 'echo DZ-BASH-OK; id -u'` with the
     * session guest env. If this fails, the Console session will
     * fail the same way; if it works, the session path is proven.
     */
    fun runProotBashProbe(app: Application): CapturedResult {
        ensureGuestDirs(app)
        PrefixSetup.prepareForShell(app)
        val pb = ProcessBuilder(prootArgv(app, listOf("/bin/bash", "-c", "echo DZ-BASH-OK; id -u")))
        pb.directory(File(DzTerminalApp.hostHome(app)))
        applyEnv(pb, prootGuestEnv(app))
        return runCaptured(pb, 30)
    }

    /** Applies a "KEY=VALUE" env array to a ProcessBuilder. */
    fun applyEnv(pb: ProcessBuilder, env: Array<String>) {
        val map = pb.environment()
        map.clear()
        for (entry in env) {
            val i = entry.indexOf('=')
            if (i > 0) map[entry.substring(0, i)] = entry.substring(i + 1)
        }
    }

    fun writeLine(session: TerminalSession?, line: String) {
        if (session == null || !session.isRunning) return
        val bytes = (line + "\r").toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }
}
