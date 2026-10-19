package com.zynelabs.dzterminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Full terminal screen. Sessions live in DzTerminalApp so they keep
 * running while the user visits other screens. v1.0.20: MULTIPLE
 * sessions (tabs, cap 8). Each session owns its own TerminalSession
 * (own proot bash + own TerminalEmulator screen/scrollback);
 * switching tabs only re-attaches the view — nothing is destroyed.
 */
class ConsoleActivity : Activity() {

    private lateinit var terminalView: TerminalView
    private lateinit var statusText: TextView
    private lateinit var tabBarContainer: LinearLayout
    private lateinit var tabBarScroll: android.widget.HorizontalScrollView
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Max Console sessions (tabs). "+" does nothing at the cap. */
    private val maxSessions = 8

    /** Sticky-modifier state for the extra-keys bar (v1.0.10). */
    private var ctrlArmed = false
    private var altArmed = false
    private var ctrlButton: Button? = null
    private var altButton: Button? = null
    private var rippleBgRes = 0

    /** Guards against overlapping session starts (rapid RESTART taps). */
    @Volatile private var startingSession = false

    /** v1.0.27: shared-storage permission (READ/WRITE_EXTERNAL_STORAGE).
     *  True while the one-time Console prompt is showing; the
     *  session starts from onRequestPermissionsResult. */
    private var storagePermPromptPending = false
    /** True when the permission was denied (asked once, never
     *  re-prompted) — a one-line hint is printed into a fresh
     *  shell instead (maybeDeliverStorageDeniedHint). */
    private var storageDeniedHintPending = false
    /** The denied hint is delivered at most once per activity. */
    private var storageHintDelivered = false

    /** True when there is no live session to type into (current tab by default). */
    private fun isSessionDead(session: TerminalSession? = (application as DzTerminalApp).session): Boolean {
        return session == null || !session.isRunning
    }

    /** The session shown in the current tab. */
    private fun currentSession(): TerminalSession? = (application as DzTerminalApp).session

    /**
     * v1.0.23: tell every session whether it is the ATTACHED tab.
     * TerminalSession throttles BACKGROUND drains so a background
     * animation can never starve the current tab's typing echo —
     * that only works if the flag tracks the real attached tab.
     * Call after every add / switch / close / start / resume.
     */
    private fun refreshForegroundFlags() {
        val app = application as DzTerminalApp
        app.sessions.forEachIndexed { i, s -> s.isForeground = (i == app.currentSessionIndex) }
    }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            // Only the attached (current) session drives the view.
            // A background tab's emulator still updates itself, but
            // redrawing the view for it would paint the wrong tab.
            if (changedSession === currentSession()) {
                terminalView.onScreenUpdated()
            }
        }

        override fun onTitleChanged(changedSession: TerminalSession) {}

        override fun onSessionFinished(finishedSession: TerminalSession) {
            mainHandler.post {
                try {
                    val tail = finishedSession.emulator?.screen?.transcriptText?.takeLast(2000) ?: ""
                    DebugInfo.onSessionFinished(finishedSession.exitStatus, tail)
                } catch (_: Exception) {
                }
                // Only the current tab shows the "ended" banner; a
                // background tab ending stays silent until viewed.
                if (finishedSession === currentSession()) {
                    statusText.visibility = View.VISIBLE
                    statusText.text = "Session ended. Tap Restart (or press Enter) for a new shell."
                }
                // v1.0.26: a finished session may be the LAST live
                // one — tell the keep-alive service to re-check so
                // it drops the wake lock and stops itself.
                SessionService.sync(this@ConsoleActivity)
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DZ Terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(this@ConsoleActivity).toString()
            val bytes = text.toByteArray(Charsets.UTF_8)
            session.write(bytes, 0, bytes.size)
        }

        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {
            terminalView.onScreenUpdated()
        }

        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "", e) }
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = 1.0f // pinch-zoom off in v1
        override fun onSingleTapUp(e: MotionEvent) {
            terminalView.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
            // Enter after the session died restarts it (the "[Process
            // completed] - press Enter" prompt is otherwise a dead end:
            // the dead session drops all input).
            if (keyCode == KeyEvent.KEYCODE_ENTER && isSessionDead()) {
                restartSession()
                return true
            }
            return false
        }
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        // The extra-keys bar's sticky CTRL/ALT (v1.0.10): TerminalView
        // consults these when the SOFT keyboard sends a key, so
        // CTRL-armed + soft-keyboard D becomes 0x04 (Ctrl+D) inside
        // TerminalView.inputCodePoint. The disarm happens in
        // onCodePoint below, after the armed modifiers were applied
        // to that key.
        override fun readControlKey(): Boolean = ctrlArmed
        override fun readAltKey(): Boolean = altArmed
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            // Sticky modifiers apply to exactly one key: TerminalView
            // already folded the armed state into ctrlDown/altDown
            // before calling here, so disarm now for the NEXT key.
            disarmStickyModifiers()
            // Soft-keyboard Enter arrives here as a code point.
            if ((codePoint == 13 || codePoint == 10) && isSessionDead()) {
                restartSession()
                return true
            }
            return false
        }
        override fun onKeyConsumed() {
            // v1.0.25: KeyEvent-consumed keys (Enter, arrows, Tab, DEL
            // via handleKeyCode) never reach onCodePoint, so disarm
            // here too — otherwise an armed CTRL survives Enter and
            // leaks into the next keypress (Ctrl+Y at nano's Y/N
            // prompt, which nano ignores).
            disarmStickyModifiers()
        }
        override fun onEmulatorSet() {}
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "", e) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_console)
        terminalView = findViewById(R.id.terminal_view)
        statusText = findViewById(R.id.console_status)
        tabBarContainer = findViewById(R.id.tab_bar_container)
        tabBarScroll = findViewById(R.id.tab_bar_scroll)
        findViewById<Button>(R.id.console_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.console_restart).setOnClickListener { restartSession() }
        findViewById<Button>(R.id.tab_add).setOnClickListener { addSession() }
        val rippleValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleValue, true)
        rippleBgRes = rippleValue.resourceId
        buildExtraKeys()

        val textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics
        ).toInt()
        terminalView.setTextSize(textSizePx)
        terminalView.setTerminalViewClient(viewClient)

        // v1.0.27: shared-storage permission gate. The guest
        // shell reaches /sdcard + /storage/emulated/0 only when
        // the app holds READ_EXTERNAL_STORAGE at runtime
        // (targetSdk 28 legacy storage). Ask ONCE, before the
        // first session starts, with a short rationale; if the
        // user denied, never re-prompt — the session still
        // starts and a one-line hint is printed into it.
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !storagePermAsked()
        ) {
            markStoragePermAsked()
            storagePermPromptPending = true
            Toast.makeText(
                this,
                "DZ Terminal needs Files and media permission to reach your shared storage /sdcard",
                Toast.LENGTH_LONG
            ).show()
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                STORAGE_PERM_REQUEST
            )
            return
        }
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Asked before and denied (or revoked): no re-prompt.
            storageDeniedHintPending = true
        }
        startOrAttachSession()
    }

    /** True when the Console storage-permission prompt has already been shown once (persisted). */
    private fun storagePermAsked(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_STORAGE_PERM_ASKED, false)

    private fun markStoragePermAsked() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_STORAGE_PERM_ASKED, true).apply()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != STORAGE_PERM_REQUEST) return
        storagePermPromptPending = false
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // Denied: no re-prompt ever — one-line hint instead.
            storageDeniedHintPending = true
            Toast.makeText(
                this,
                "grant Files and media in App info to use /sdcard",
                Toast.LENGTH_LONG
            ).show()
        }
        startOrAttachSession()
    }

    /** The old onCreate tail: re-attach a surviving session or start the first one. */
    private fun startOrAttachSession() {
        val app = application as DzTerminalApp
        val existing = app.session
        if (existing != null) {
            // Sessions survived (BACK to Home and back, or a
            // config change): re-attach the current tab. A dead
            // current tab still attaches — its emulator holds the
            // "[Process completed]" transcript — and the banner
            // shows via updateStatusForCurrent().
            attach(existing)
            updateStatusForCurrent()
            rebuildTabs()
            refreshForegroundFlags()
            // reuses an existing tab — it opens a FRESH tab
            // (QPython behavior). The new tab's creation consumes
            // the pending command. At the 8-tab cap, drop the
            // command with a Toast instead of reusing a tab or
            // leaving a stale pending command to fire later.
            if (DzTerminalApp.pendingConsoleCommand != null &&
                DzTerminalApp.pendingConsoleCommandNewTab
            ) {
                if (app.sessions.size >= maxSessions) {
                    DzTerminalApp.pendingConsoleCommand = null
                    DzTerminalApp.pendingConsoleCommandNewTab = false
                    Toast.makeText(
                        this,
                        "Maximum $maxSessions sessions — close a tab first",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    addSession()
                }
            } else {
                consumePendingCommand()
            }
        } else {
            startSession(append = true)
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as DzTerminalApp
        val session = app.session
        if (session != null) {
            // attachSession -> TerminalSession.updateSize ->
            // initializeEmulator -> JNI.createSubprocess can throw
            // (e.g. reflection failure in wrapFileDescriptor). A
            // throw here used to be uncaught on the UI thread and
            // killed the app — record it and stay on the screen.
            try {
                attach(session)
            } catch (t: Throwable) {
                DebugInfo.lastSessionError = "onResume attach failed: ${t.javaClass.simpleName}: ${t.message}"
                statusText.visibility = View.VISIBLE
                statusText.text = "Could not attach session: ${t.message}"
            }
            updateStatusForCurrent()
            rebuildTabs()
            refreshForegroundFlags()
            // v1.0.26: make sure the keep-alive service is running
            // for the re-attached session (e.g. it was stopped
            // while the activity was away).
            SessionService.sync(this)
        }
        terminalView.requestFocus()
    }

    // ---- Extra-keys bar (v1.0.10, Termux-style) ----
    //
    // Two rows matching the Termux extra-keys layout the user screenshotted:
    //   Row 1: ESC / — HOME ↑ END PGUP
    //   Row 2: TAB CTRL ALT ← ↓ → PGDN
    // CTRL and ALT are STICKY: tap to arm (highlighted), they apply to the
    // next key, then disarm. Bytes go through the session's write path
    // (same as SessionManager.writeLine). Buttons are not focusable so
    // taps never steal focus from the terminal. For the SOFT keyboard,
    // the sticky state is exposed via viewClient.readControlKey() /
    // readAltKey() (TerminalView folds those into inputCodePoint) and
    // disarmed in viewClient.onCodePoint — that is what makes
    // CTRL-armed + soft-keyboard D send 0x04 (Ctrl+D, shell exits).
    // v1.0.25: KeyEvent-consumed keys (Enter, arrows, Tab, DEL) never
    // reach onCodePoint, so they disarm via viewClient.onKeyConsumed
    // instead — otherwise an armed CTRL survives Enter and leaks
    // into the next keypress.

    private data class ExtraKey(
        val label: String,
        val plain: String? = null,      // literal text to send ("/", "—")
        val singleByte: Int? = null,    // single byte to send (ESC, TAB)
        val csi: String? = null,        // CSI suffix after ESC [ ("A", "5~", ...)
        val modifier: Int = 0           // 1 = CTRL, 2 = ALT
    )

    private fun buildExtraKeys() {
        val container = findViewById<LinearLayout>(R.id.extra_keys_container)
        val row1 = listOf(
            ExtraKey("ESC", singleByte = 0x1B),
            ExtraKey("/", plain = "/"),
            ExtraKey("—", plain = "—"),
            ExtraKey("HOME", csi = "H"),
            ExtraKey("↑", csi = "A"),
            ExtraKey("END", csi = "F"),
            ExtraKey("PGUP", csi = "5~")
        )
        val row2 = listOf(
            ExtraKey("TAB", singleByte = 0x09),
            ExtraKey("CTRL", modifier = 1),
            ExtraKey("ALT", modifier = 2),
            ExtraKey("←", csi = "D"),
            ExtraKey("↓", csi = "B"),
            ExtraKey("→", csi = "C"),
            ExtraKey("PGDN", csi = "6~")
        )
        for (row in listOf(row1, row2)) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (key in row) {
                val btn = Button(this).apply {
                    text = key.label
                    // v1.0.11: the labels CLIPPED on a narrow screen
                    // (HOME -> "HO", PGUP -> "PGU", CTRL -> "CTR"):
                    // a default Button carries an 88dp minWidth and
                    // ~16dp side padding, so in a 7-across row only
                    // ~19dp was left for the text. Kill the minWidth,
                    // shrink padding + text so full labels fit.
                    textSize = 11f
                    minimumWidth = 0
                    minWidth = 0
                    minimumHeight = 0
                    minHeight = 0
                    setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.dz_white))
                    setBackgroundResource(rippleBgRes)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setOnClickListener { onExtraKey(key) }
                }
                if (key.modifier == 1) {
                    ctrlButton = btn
                }
                if (key.modifier == 2) {
                    altButton = btn
                }
                rowLayout.addView(
                    btn,
                    LinearLayout.LayoutParams(0, dpToPx(44), 1f)
                )
            }
            container.addView(
                rowLayout,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun onExtraKey(key: ExtraKey) {
        if (key.modifier == 1) {
            ctrlArmed = !ctrlArmed
            refreshModifierHighlights()
            return
        }
        if (key.modifier == 2) {
            altArmed = !altArmed
            refreshModifierHighlights()
            return
        }
        val base: ByteArray = when {
            key.plain != null -> key.plain.toByteArray(Charsets.UTF_8)
            key.singleByte != null -> byteArrayOf(key.singleByte.toByte())
            key.csi != null -> ("\u001B[" + key.csi).toByteArray(Charsets.UTF_8)
            else -> return
        }
        var out = base
        if (ctrlArmed) out = applyCtrl(out)
        if (altArmed) out = byteArrayOf(0x1B) + out
        val session = (application as DzTerminalApp).session
        if (session != null && session.isRunning) {
            try {
                session.write(out, 0, out.size)
            } catch (_: Throwable) {
            }
        }
        ctrlArmed = false
        altArmed = false
        refreshModifierHighlights()
    }

    /**
     * CTRL + key mapping. Single-byte key -> control byte (byte & 0x1F):
     * CTRL+D ('D' = 0x44) -> 0x04 (EOF), so at an empty shell prompt the
     * shell exits. CSI sequence -> xterm modified form
     * ESC [ <params> ; 5 <final> (CTRL+Up -> ESC [ 1;5 A). Multi-byte
     * non-CSI (e.g. the em-dash) passes through unchanged.
     */
    private fun applyCtrl(base: ByteArray): ByteArray {
        if (base.size == 1) {
            val b = base[0].toInt() and 0xFF
            return byteArrayOf((b and 0x1F).toByte())
        }
        val s = String(base, Charsets.UTF_8)
        if (s.startsWith("\u001B[") && s.length >= 3) {
            val final = s.last()
            if (final in '\u0040'..'\u007E') {
                val params = s.substring(2, s.length - 1)
                val newParams = if (params.isEmpty()) "1;5" else "$params;5"
                return ("\u001B[" + newParams + final).toByteArray(Charsets.UTF_8)
            }
        }
        return base
    }

    /** One-shot sticky-modifier disarm, shared by onCodePoint and onKeyConsumed. */
    private fun disarmStickyModifiers() {
        if (ctrlArmed || altArmed) {
            ctrlArmed = false
            altArmed = false
            refreshModifierHighlights()
        }
    }

    private fun refreshModifierHighlights() {
        fun style(btn: Button?, armed: Boolean) {
            if (btn == null) return
            if (armed) {
                // Armed: unmistakable — solid green background, black
                // BOLD text. Background + text color + weight all
                // change, so the state cannot be missed.
                btn.setBackgroundColor(getColor(R.color.dz_green))
                btn.setTextColor(getColor(R.color.dz_black))
                btn.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                // Unarmed: EXACTLY like every other extra key (white
                // text + ripple background, normal weight). Green is
                // reserved for the armed state ONLY — v1.0.10 gave
                // CTRL/ALT green text while unarmed, so they looked
                // armed all the time (the bug the user reported).
                btn.setBackgroundResource(rippleBgRes)
                btn.setTextColor(getColor(R.color.dz_white))
                btn.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
            btn.invalidate()
        }
        style(ctrlButton, ctrlArmed)
        style(altButton, altArmed)
    }

    private fun startSession(append: Boolean, replaceTarget: TerminalSession? = null) {
        if (startingSession) return
        startingSession = true
        statusText.visibility = View.VISIBLE
        statusText.text = "Starting Linux environment..."
        Thread {
            // v1.0.7: the WHOLE start flow is guarded. Before this,
            // only the final session creation was in try/catch — a
            // throw from the PrefixSetup calls above it (e.g. an
            // IOException from ProcessBuilder.start() in the second
            // stage, when direct exec of bash is denied) escaped as
            // an uncaught exception in this thread and Android
            // killed the whole app process: the v1.0.6 "tap
            // Console, app exits" crash. A start failure is now a
            // visible message + a Debug record, never a process death.
            try {
                startSessionInner(append, replaceTarget)
            } catch (t: Throwable) {
                DebugInfo.lastSessionError =
                    "Console start failed: ${t.javaClass.simpleName}: ${t.message}"
                runOnUiThread {
                    statusText.visibility = View.VISIBLE
                    statusText.text = "Could not start session: ${t.javaClass.simpleName}: ${t.message}"
                    Toast.makeText(this, "Session failed: ${t.message}", Toast.LENGTH_LONG).show()
                    startingSession = false
                }
            }
        }.start()
    }

    /**
     * The guarded body of startSession (runs on a background thread).
     * append=true: the new session becomes a NEW tab (first session,
     * or "+") and takes focus. append=false: it REPLACES the current
     * tab (RESTART) — pinned to [replaceTarget] BY IDENTITY, not by
     * index: if the user switched tabs (or closed the restarting
     * tab) while the new shell was starting, the new session must
     * not clobber whatever now sits at that index. If the target
     * is gone, the new session appends as a fresh tab instead, and
     * the view is NOT yanked back to it when another tab is showing.
     */
    private fun startSessionInner(append: Boolean, replaceTarget: TerminalSession?) {
        val app = application as DzTerminalApp
        if (!PrefixSetup.isInstalled(this)) {
            val result = PrefixSetup.setup(this) { msg ->
                runOnUiThread { statusText.text = msg }
            }
            if (!result.ok) {
                runOnUiThread {
                    statusText.text = result.message
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    startingSession = false
                }
                return
            }
        } else {
            // v1.0.5: re-apply correct modes on EVERY start
            // (idempotent — chmod runs only where modes differ).
            // This heals prefixes damaged by v1.0.4's 0x700 bug
            // even when the setup state says OK.
            runOnUiThread { statusText.text = "Checking prefix permissions..." }
            PrefixSetup.repairPrefixPermissions(app) { msg ->
                runOnUiThread { statusText.text = msg }
            }
            if (PrefixSetup.needsRepair(app)) {
                // Previous setup left the prefix PARTIAL (or the
                // install predates the setup-state file): re-attempt
                // the second stage. The repair inside
                // repairAndRetrySecondStage is a no-op here (modes
                // were just fixed above). The session still opens
                // afterwards either way — the session is the real test.
                runOnUiThread { statusText.text = "Previous setup was partial — retrying second stage..." }
                PrefixSetup.repairAndRetrySecondStage(app) { msg ->
                    runOnUiThread { statusText.text = msg }
                }
            }
        }
        runOnUiThread {
            try {
                val session = SessionManager.createSession(app, sessionClient)
                if (append) {
                    app.sessions.add(session)
                    app.currentSessionIndex = app.sessions.size - 1
                    attach(session)
                    statusText.visibility = View.GONE
                    rebuildTabs()
                    refreshForegroundFlags()
                    // v1.0.26: first / new live session -> start
                    // the keep-alive service + wake lock.
                    SessionService.sync(this@ConsoleActivity)
                    consumePendingCommand()
                    maybeDeliverStorageDeniedHint(session)
                } else {
                    val placedIndex = if (replaceTarget != null) {
                        val idx = app.sessions.indexOf(replaceTarget)
                        if (idx >= 0) {
                            app.sessions[idx] = session
                            idx
                        } else {
                            app.sessions.add(session)
                            app.sessions.size - 1
                        }
                    } else if (app.sessions.isEmpty()) {
                        app.sessions.add(session)
                        0
                    } else {
                        app.sessions[app.currentSessionIndex] = session
                        app.currentSessionIndex
                    }
                    // Only attach when the restarted tab is the one
                    // showing — otherwise just refresh the bar and
                    // leave the user's current tab alone.
                    if (placedIndex == app.currentSessionIndex) {
                        attach(session)
                        statusText.visibility = View.GONE
                        consumePendingCommand()
                    maybeDeliverStorageDeniedHint(session)
                    }
                    rebuildTabs()
                    refreshForegroundFlags()
                    SessionService.sync(this@ConsoleActivity)
                }
            } catch (t: Throwable) {
                DebugInfo.lastSessionError =
                    "Session creation failed: ${t.javaClass.simpleName}: ${t.message}"
                statusText.visibility = View.VISIBLE
                statusText.text = "Could not start session: ${t.message}"
                Toast.makeText(this, "Session failed: ${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                startingSession = false
            }
        }
    }

    private fun attach(session: TerminalSession) {
        // attachSession -> updateSize -> initializeEmulator ->
        // JNI.createSubprocess + wrapFileDescriptor. Throws are
        // caught by the callers (startSessionInner's UI block and
        // onResume) — attach itself does not swallow them, so the
        // failure is recorded in Debug instead of vanishing.
        terminalView.attachSession(session)
        statusText.visibility = View.GONE
    }

    private fun restartSession() {
        // RESTART restarts ONLY the current tab: its process is
        // killed and a fresh shell takes its slot; other tabs keep
        // running untouched.
        val app = application as DzTerminalApp
        if (app.sessions.isEmpty()) {
            startSession(append = true)
            return
        }
        val target = app.session
        target?.finishIfRunning()
        startSession(append = false, replaceTarget = target)
    }

    // ---- Session tabs (v1.0.20) ----
    //
    // One tab per session: number (1-based position) + close ×.
    // The tab bar is rebuilt from DzTerminalApp.sessions after every
    // add / close / switch / start / resume, so it can never drift
    // from the real session list. Tab numbers are POSITIONS, not
    // stable ids — closing tab 2 renumbers 3..N down by one, which
    // keeps the bar trivially in sync.

    /** "+" button: a new independent shell in a new tab (cap 8). */
    private fun addSession() {
        val app = application as DzTerminalApp
        if (app.sessions.size >= maxSessions) {
            Toast.makeText(this, "Maximum $maxSessions sessions", Toast.LENGTH_SHORT).show()
            return
        }
        startSession(append = true)
    }

    /** Tap a tab: show that session, preserving every tab's screen. */
    private fun switchTo(index: Int) {
        val app = application as DzTerminalApp
        if (index < 0 || index >= app.sessions.size) return
        if (index == app.currentSessionIndex) return
        app.currentSessionIndex = index
        val session = app.sessions[index]
        try {
            attach(session)
        } catch (t: Throwable) {
            DebugInfo.lastSessionError = "tab switch attach failed: ${t.javaClass.simpleName}: ${t.message}"
            statusText.visibility = View.VISIBLE
            statusText.text = "Could not attach session: ${t.message}"
        }
        updateStatusForCurrent()
        rebuildTabs()
        refreshForegroundFlags()
        terminalView.requestFocus()
        // The IME holds composing state for the previous tab's
        // input connection — restart it so typing goes to the
        // newly attached session (erase-all + resend-all protocol
        // in TerminalView then applies to the new tab, v1.0.13).
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(terminalView)
    }

    /** Tap ×: kill that session and drop its tab. Never drops the last one. */
    private fun closeSession(index: Int) {
        val app = application as DzTerminalApp
        if (app.sessions.size <= 1) return // always keep at least one session
        if (index < 0 || index >= app.sessions.size) return
        app.sessions[index].finishIfRunning()
        app.sessions.removeAt(index)
        // If the closed tab was before the current one, the current
        // tab shifts down one. If it WAS the current one, the
        // neighbour slides into its slot (next tab, or the previous
        // one when the last tab was closed).
        if (index < app.currentSessionIndex) {
            app.currentSessionIndex--
        } else if (index == app.currentSessionIndex) {
            if (app.currentSessionIndex >= app.sessions.size) {
                app.currentSessionIndex = app.sessions.size - 1
            }
        }
        val current = app.session
        if (current != null) {
            try {
                attach(current)
            } catch (t: Throwable) {
                DebugInfo.lastSessionError = "tab close attach failed: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        updateStatusForCurrent()
        rebuildTabs()
        refreshForegroundFlags()
        // v1.0.26: closing a tab may leave zero live sessions.
        SessionService.sync(this)
        terminalView.requestFocus()
    }

    /** Shows the "Session ended" banner iff the current tab is dead. */
    private fun updateStatusForCurrent() {
        val current = currentSession()
        if (current != null && !current.isRunning) {
            statusText.visibility = View.VISIBLE
            statusText.text = "Session ended. Tap Restart (or press Enter) for a new shell."
        } else {
            statusText.visibility = View.GONE
        }
    }

    /** Rebuilds the tab bar from the session list. */
    private fun rebuildTabs() {
        val app = application as DzTerminalApp
        tabBarContainer.removeAllViews()
        val count = app.sessions.size
        if (count == 0) {
            // No session yet (first start still running): show a
            // placeholder "1" so the bar is never empty.
            tabBarContainer.addView(makeTabView(0, selected = true, closeVisible = false))
            return
        }
        for (i in 0 until count) {
            tabBarContainer.addView(
                makeTabView(i, selected = (i == app.currentSessionIndex), closeVisible = (count > 1))
            )
        }
        // Keep the selected tab visible in the scrollable bar.
        val selected = app.currentSessionIndex
        tabBarScroll.post {
            val child = tabBarContainer.getChildAt(selected) ?: return@post
            tabBarScroll.smoothScrollTo(child.left - dpToPx(8), 0)
        }
    }

    /** One tab: number label + close ×. */
    private fun makeTabView(index: Int, selected: Boolean, closeVisible: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpToPx(10), dpToPx(4), dpToPx(4), dpToPx(4))
            setBackgroundColor(
                if (selected) getColor(R.color.dz_green) else getColor(R.color.dz_card)
            )
        }
        val label = TextView(this).apply {
            text = (index + 1).toString()
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (selected) getColor(R.color.dz_black) else getColor(R.color.dz_white))
            // v1.0.23: ≥12dp gap between the number and the × — the
            // old 4dp gap let a tap on the tab label land on the ×
            // and close the tab by accident. No close-confirmation
            // dialog: the × now has its own clearly separated hit area.
            setPadding(0, 0, dpToPx(12), 0)
        }
        row.addView(label)
        val close = Button(this).apply {
            text = "×"
            textSize = 14f
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            setPadding(dpToPx(4), 0, dpToPx(4), 0)
            setTextColor(if (selected) getColor(R.color.dz_black) else getColor(R.color.dz_muted))
            setBackgroundResource(android.R.color.transparent)
            isFocusable = false
            isFocusableInTouchMode = false
            visibility = if (closeVisible) View.VISIBLE else View.GONE
            setOnClickListener { closeSession(index) }
        }
        // v1.0.23: the × gets a FIXED 32×32dp hitbox with a 6dp
        // left margin (total number→× gap = 12 + 6 = 18dp) instead
        // of WRAP_CONTENT bleeding into the label — it fires only
        // on a deliberate tap on the × itself; taps on the number
        // hit the row (switch tab).
        val closeLp = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32))
        closeLp.leftMargin = dpToPx(6)
        row.addView(close, closeLp)
        row.setOnClickListener { switchTo(index) }
        row.isFocusable = false
        row.isFocusableInTouchMode = false
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, dpToPx(6), 0)
        row.layoutParams = lp
        return row
    }

    /**
     * v1.0.27: prints the one-line storage-denied hint into a
     * FRESH shell's output (once per activity instance) when the
     * user denied the storage permission: no re-prompt, just
     * "grant Files and media in App info to use /sdcard".
     * Same delayed-write pattern as consumePendingCommand
     * (PTY input buffers until the prompt is ready).
     */
    private fun maybeDeliverStorageDeniedHint(session: TerminalSession) {
        if (!storageDeniedHintPending || storageHintDelivered) return
        storageHintDelivered = true
        var tries = 0
        val poll = object : Runnable {
            override fun run() {
                if (session.isRunning && session.emulator != null) {
                    SessionManager.writeLine(session, "echo 'grant Files and media in App info to use /sdcard'")
                } else if (tries < 20) {
                    tries++
                    mainHandler.postDelayed(this, 250)
                }
            }
        }
        mainHandler.postDelayed(poll, 500)
    }

    /** Writes Editor/Programs' pending run command once the shell is up. */
    private fun consumePendingCommand() {
        val app = application as DzTerminalApp
        val cmd = DzTerminalApp.pendingConsoleCommand ?: return
        DzTerminalApp.pendingConsoleCommand = null
        DzTerminalApp.pendingConsoleCommandNewTab = false
        var tries = 0
        val poll = object : Runnable {
            override fun run() {
                val session = app.session
                if (session != null && session.isRunning && session.emulator != null) {
                    SessionManager.writeLine(session, cmd)
                } else if (tries < 20) {
                    tries++
                    mainHandler.postDelayed(this, 250)
                }
            }
        }
        mainHandler.postDelayed(poll, 500)
    }

    companion object {
        private const val PREFS = "dz_terminal_prefs"
        private const val KEY_STORAGE_PERM_ASKED = "storage_perm_asked_v1027"
        private const val STORAGE_PERM_REQUEST = 1004
        /** Opens the Console and runs a shell command once the shell is ready. */
        fun openWithCommand(context: Context, command: String) {
            DzTerminalApp.pendingConsoleCommand = command
            DzTerminalApp.pendingConsoleCommandNewTab = false
            context.startActivity(Intent(context, ConsoleActivity::class.java))
        }

        /**
         * v1.0.21: Programs script-run — opens the Console and runs
         * a shell command in a FRESH tab (QPython behavior), never
         * reusing an existing tab. When the Console has no sessions
         * yet, the first tab IS the fresh tab; otherwise onCreate
         * adds a new tab and the new tab's creation consumes the
         * pending command.
         */
        fun openWithCommandInNewTab(context: Context, command: String) {
            DzTerminalApp.pendingConsoleCommand = command
            DzTerminalApp.pendingConsoleCommandNewTab = true
            context.startActivity(Intent(context, ConsoleActivity::class.java))
        }
    }
}
