package com.zynelabs.dzterminal

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Read-only diagnostics for the Settings > Debug screen.
 *
 * In-memory only (cleared when the app process dies). The setup log is
 * appended here by PrefixSetup; the last session's exit info is recorded
 * by ConsoleActivity when a session finishes.
 */
object DebugInfo {

    private const val MAX_SETUP_LINES = 120

    private val setupLogLines = ArrayDeque<String>()

    @Volatile var lastSetupResult: String? = null
    @Volatile var lastSessionExitCode: Int? = null
    @Volatile var lastSessionEndedAt: Long = 0L
    @Volatile var lastSessionTranscriptTail: String = ""
    // Second stage (v1.0.4): exit code + captured output tail. In-memory
    // for this process; the persistent copy lives in
    // <files>/.dz-second-stage.log (see PrefixSetup).
    @Volatile var lastSecondStageExit: Int? = null
    @Volatile var lastSecondStageOutput: String = ""
    // Latest session-start failure (exception while opening a
    // session — shown in Debug's "Last session" section so a failed
    // start is recorded, not left as "(no finished session yet)").
    @Volatile var lastSessionError: String? = null
    // Latest probe result (Debug screen buttons: Test proot / Test bash).
    @Volatile var lastSelfTest: String = ""
    // Second-stage exec mode (v1.0.13: "proot") + whole-tree
    // un-patch stats (host -> fake, one-time v1.0.17 migration).
    @Volatile var lastSecondStageMode: String = ""
    @Volatile var lastWholeTreeUnpatch: String = ""
    // v1.0.17: wrapper-removal status (removeProotWrappers, every
    // Console start) + one-time tree migration stats.
    @Volatile var lastWrapperRemoval: String = ""
    @Volatile var lastTreeMigration: String = ""
    // v1.0.15: PROOT_TMP_DIR value handed to proot (host path),
    // whether the proot bind-target dir exists inside our prefix,
    // and the EXACT second-stage proot argv — so a proot failure
    // is evidence, not a guess about what proot was actually given.
    @Volatile var lastProotTmpDir: String = ""
    @Volatile var lastProotBindTargetExists: Boolean = false
    @Volatile var lastSecondStageProotArgv: String = ""
    // v1.0.17: whether /linkerconfig was bound into the guest
    // (kills the Bionic linker warning that polluted `id -u`).
    @Volatile var lastLinkerconfigBound: Boolean = false
    // v1.0.18: /linkerconfig bind status, honestly reported:
    // "bound" vs "skipped (not readable)" vs "" (not checked).
    // v1.0.17's boolean alone was misleading — the bind was
    // attempted but proot could not sanitize it (app cannot
    // read host /linkerconfig), so it never took effect.
    @Volatile var lastLinkerconfigStatus: String = ""
    // v1.0.18: whether the apt cache dir exists inside OUR
    // prefix (<prefix>/data/.../cache/apt/archives/partial).
    // apt downloads .debs there; without it `pkg install`
    // fails with "Archives directory ... is missing".
    @Volatile var lastAptCacheDirExists: Boolean = false
    // v1.0.27: status of the shared-storage guest shortcuts
    // (PrefixSetup.ensureStorageLinks — ~/storage + /sdcard
    // symlinks, /storage bind-target dir), set on every
    // Console start via prepareForShell.
    @Volatile var lastStorageLinksStatus: String = ""

    /** v1.0.28: last Export / Import summaries (Home menu <-> shared storage). */
    @Volatile var lastExportSummary: String = ""
    @Volatile var lastImportSummary: String = ""

    @Synchronized
    fun appendSetupLog(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        setupLogLines.addLast("[$stamp] $line")
        while (setupLogLines.size > MAX_SETUP_LINES) setupLogLines.removeFirst()
    }

    @Synchronized
    fun setupLogTail(): String =
        if (setupLogLines.isEmpty()) "(no setup log in this session)"
        else setupLogLines.joinToString("\n")

    fun onSessionFinished(exitCode: Int, transcriptTail: String) {
        lastSessionExitCode = exitCode
        lastSessionEndedAt = System.currentTimeMillis()
        lastSessionTranscriptTail = transcriptTail
    }

    fun sessionEndedText(): String {
        val code = lastSessionExitCode ?: return "(no finished session yet)"
        val whenText = if (lastSessionEndedAt > 0)
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(lastSessionEndedAt))
        else "?"
        return "exit=$code at $whenText"
    }

    /**
     * v1.0.22: per-session drain proof. One line per tab: bytes the
     * reader pulled off the PTY, bytes the handler appended to the
     * emulator, bytes currently queued, and how long ago the last
     * drain ran. A stalled background tab shows its cause directly:
     * queue GROWING + appended FROZEN = drain choke; queue EMPTY +
     * read FROZEN = guest/proot choke. The current tab is marked *.
     * v1.0.23: also shows fg= (whether the session is the attached
     * tab — background tabs drain throttled) and lastInput= (when
     * the app last wrote input TO the guest — separates "typing
     * didn't reach the session" from "echo didn't come back").
     */
    fun sessionDrainStats(app: DzTerminalApp): String {
        if (app.sessions.isEmpty()) return "(no sessions)"
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        app.sessions.forEachIndexed { i, s ->
            val lastDrain = s.lastDrainTimeMs
            val lastDrainText = if (lastDrain == 0L) "never"
                else "%.1fs ago".format((now - lastDrain) / 1000.0)
            val lastInput = s.lastInputWriteTimeMs
            val lastInputText = if (lastInput == 0L) "never"
                else "%.1fs ago".format((now - lastInput) / 1000.0)
            sb.append("Tab ${i + 1}${if (i == app.currentSessionIndex) " *" else ""}: ")
                .append("fg=${s.isForeground} ")
                .append("running=${s.isRunning} pid=${s.pid} ")
                .append("read=${s.bytesReadFromPty}B ")
                .append("appended=${s.bytesAppendedToEmulator}B ")
                .append("queue=${s.queueDepth}B ")
                .append("lastDrain=$lastDrainText ")
                .append("lastInput=$lastInputText ")
                .append("msgs=${s.handlerMessageCount}\n")
        }
        return sb.toString().trimEnd()
    }
}
