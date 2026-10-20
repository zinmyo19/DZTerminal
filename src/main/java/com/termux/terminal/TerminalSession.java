package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * {@link #updateSize(int, int, int, int)} terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
     *
     * v1.0.21: 64 KiB, not 4 KiB. The reader thread BLOCKS in
     * ByteQueue.write() when the queue is full, and a full queue
     * backs up into the PTY buffer, which blocks the guest process
     * on write(). With two animation scripts running, a 4 KiB
     * queue filled on any main-thread hiccup and the BACKGROUND
     * tab's guest stalled until the user interacted (swipe/tab
     * switch). 64 KiB absorbs bursts so the reader keeps draining.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(65536);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the {@link #mTerminalFileDescriptor}.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(65536);

    /**
     * v1.0.22: per-session drain counters — Debug proof, not a guess.
     * The reader thread bumps mBytesReadFromPty for every chunk it
     * pulls off the PTY; the handler bumps mBytesAppendedToEmulator
     * + mLastDrainTimeMs + mHandlerMessageCount on every MSG_NEW_INPUT.
     * If a background tab ever stalls again, Debug shows whether the
     * DRAIN (queue growing, appended frozen) or the GUEST (queue
     * empty, read frozen) is the choke — no more theorizing.
     */
    private final AtomicLong mBytesReadFromPty = new AtomicLong();
    private final AtomicLong mBytesAppendedToEmulator = new AtomicLong();
    private final AtomicLong mHandlerMessageCount = new AtomicLong();
    private volatile long mLastDrainTimeMs = 0L;

    /**
     * v1.0.23: whether this session is the ATTACHED (visible) tab.
     * ConsoleActivity refreshes it on every tab switch / add / close /
     * resume. The drain handler uses it to PRIORITIZE the attached
     * session: background sessions drain at a throttled rate so a
     * background animation can never starve the current tab's
     * typing echo. Defaults to true — the first session is the
     * visible one until ConsoleActivity says otherwise.
     */
    private volatile boolean mIsForeground = true;

    /** v1.0.23: wall-clock ms of the last byte the app wrote TO the guest (typing / paste / extra keys). */
    private volatile long mLastInputWriteTimeMs = 0L;

    /**
     * v1.0.34: per-session input-path counters — Debug proof of where
     * typing goes. Incremented by TerminalView's InputConnection /
     * key-event paths (on the attached session) and by write().
     * If commitText counts climb but inputBytesWritten stays 0, the
     * break is between the IME and session.write; if commitText
     * itself stays 0, the IME never delivered to the view.
     */
    public volatile int inputCommitTextCalls;
    public volatile int inputSetComposingCalls;
    public volatile int inputDeleteSurroundingCalls;
    public volatile int inputSendKeyEventCalls;
    public volatile int inputKeyEventCalls;
    public volatile int inputConnectionCreations;
    public volatile long inputBytesWritten;
    /** Last committed text (truncated to 24 chars) — proof typing arrived. */
    public volatile String lastInputText = "";

    /** v1.0.23: set by ConsoleActivity whenever the attached tab changes. */
    public void setForeground(boolean foreground) {
        mIsForeground = foreground;
    }

    /** v1.0.23: true when this session is the attached (visible) tab. */
    public boolean isForeground() {
        return mIsForeground;
    }

    /** v1.0.23: wall-clock ms of the last input write (0 = no input yet). */
    public long getLastInputWriteTimeMs() {
        return mLastInputWriteTimeMs;
    }

    /** Bytes the reader thread has pulled off the PTY (all sessions, attached or not). */
    public long getBytesReadFromPty() {
        return mBytesReadFromPty.get();
    }

    /** Bytes the handler has appended to the emulator. */
    public long getBytesAppendedToEmulator() {
        return mBytesAppendedToEmulator.get();
    }

    /** MSG_NEW_INPUT messages the handler has processed. */
    public long getHandlerMessageCount() {
        return mHandlerMessageCount.get();
    }

    /** Wall-clock ms of the last drain that appended > 0 bytes (0 = never drained). */
    public long getLastDrainTimeMs() {
        return mLastDrainTimeMs;
    }

    /** Bytes currently waiting in the PTY -> emulator queue. */
    public int getQueueDepth() {
        return mProcessToTerminalIOQueue.storedBytes();
    }
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    int mShellPid;

    /** The exit status of the shell process. Only valid if ${@link #mShellPid} is -1. */
    int mShellExitStatus;

    /**
     * The file descriptor referencing the master half of a pseudo-terminal pair, resulting from calling
     * {@link JNI#createSubprocess(String, String, String[], String[], int[], int, int, int, int)}.
     */
    private int mTerminalFileDescriptor;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final Integer mTranscriptRows;


    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the attached pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns, cellWidthPixels, cellHeightPixels);
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);

        int[] processId = new int[1];
        mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns, cellWidthPixels, cellHeightPixels);
        mShellPid = processId[0];

        final FileDescriptor terminalFileDescriptorWrapped = wrapFileDescriptor(mTerminalFileDescriptor, mClient);

        new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                try (InputStream termIn = new FileInputStream(terminalFileDescriptorWrapped)) {
                    final byte[] buffer = new byte[4096];
                    while (true) {
                        int read = termIn.read(buffer);
                        if (read == -1) return;
                        mBytesReadFromPty.addAndGet(read);
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
                        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                    }
                } catch (Exception e) {
                    // Ignore, just shutting down.
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try (FileOutputStream termOut = new FileOutputStream(terminalFileDescriptorWrapped)) {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        termOut.write(buffer, 0, bytesToWrite);
                    }
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }.start();

        new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                int processExitCode = JNI.waitFor(mShellPid);
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
            }
        }.start();

        // v1.0.22: drain watchdog. The handler normally drains the
        // queue on every reader post, but if a drain is ever skipped
        // (main-thread stall, a handler message dropped under
        // pressure) a background tab's queue would sit full while the
        // reader blocks in ByteQueue.write() and the guest blocks on
        // PTY write() — exactly the "animation stops until I touch
        // the screen" symptom. The watchdog only fires when the queue
        // is provably non-empty AND no drain has run for >1s, so it
        // is a no-op while draining is healthy. Daemon thread; exits
        // when the session finishes (isRunning() goes false).
        Thread watchdog = new Thread("TermSessionDrainWatchdog[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                try {
                    while (isRunning()) {
                        Thread.sleep(1000);
                        if (mProcessToTerminalIOQueue.storedBytes() > 0
                                && System.currentTimeMillis() - mLastDrainTimeMs > 1000) {
                            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                        }
                    }
                } catch (InterruptedException e) {
                    // Session thread pool teardown — exit quietly.
                }
            }
        };
        watchdog.setDaemon(true);
        watchdog.start();

    }

    /** Write data to the shell process. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mShellPid > 0) {
            // v1.0.23: Debug proof that input REACHES the session —
            // typing echo can lag while input itself is instant, and
            // this timestamp separates the two (lastInput in Debug).
            mLastInputWriteTimeMs = System.currentTimeMillis();
            // v1.0.34: total bytes written to the PTY (Debug counter).
            inputBytesWritten += count;
            mTerminalToProcessIOQueue.write(data, offset, count);
        }
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by sending SIGKILL to the shell. */
    public void finishIfRunning() {
        if (isRunning()) {
            try {
                Os.kill(mShellPid, OsConstants.SIGKILL);
            } catch (ErrnoException e) {
                Logger.logWarn(mClient, LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
            }
        }
    }

    /** Cleanup resources when the process exits. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
        }

        // Stop the reader and writer threads, and close the I/O streams
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        JNI.close(mTerminalFileDescriptor);
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (IOException | SecurityException e) {
            Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor, TerminalSessionClient client) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                // For desktop java:
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | RuntimeException e) {
            // RuntimeException covers InaccessibleObjectException
            // (setAccessible on a hidden-API-blocked field) and
            // SecurityException — both must surface as a catchable
            // error, never a silent process kill.
            Logger.logStackTraceWithMessage(client, LOG_TAG, "Error accessing FileDescriptor#descriptor private field", e);
            // v1.0.7 (DZ Terminal): NEVER System.exit(1) here. The
            // vendored Termux code killed the whole app process on
            // a reflection failure, with no message anywhere. Throw
            // instead — ConsoleActivity catches it, shows it in the
            // Console UI, and records it in Debug.
            throw new IllegalStateException("Cannot access FileDescriptor#descriptor via reflection", e);
        }
        return result;
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[16 * 1024];

        /**
         * Max bytes drained from the queue per handler message
         * (v1.0.22: 64 KiB, was 256 KiB in v1.0.21). The loop below
         * drains until the queue is empty or this cap is hit;
         * leftover bytes stay queued and are picked up by the
         * self-pump re-post at the end of handleMessage. A 256 KiB
         * append burst held the single main thread for tens of ms,
         * delaying the OTHER session's handler messages and the draw
         * pass; 64 KiB slices interleave fairly between sessions.
         */
        private static final int MAX_DRAIN_PER_MESSAGE = 64 * 1024;

        /**
         * v1.0.23: BACKGROUND drain throttle. All sessions share the
         * single main Looper, and the attached tab's typing echo
         * (PTY echo -> reader -> queue -> this handler -> append ->
         * redraw) queues BEHIND whatever drain work the background
         * tabs post. With 2 tabs + a background animation, the
         * background drain + self-pump saturated the main thread, so
         * typed characters did not appear until Space (a commit)
         * forced a redraw — the "typing lags with >1 tab" symptom.
         * A background session therefore drains at most
         * BACKGROUND_DRAIN_MAX_BYTES per
         * BACKGROUND_DRAIN_MIN_INTERVAL_MS (~160 KB/s ceiling).
         * That keeps a background animation ADVANCING (never frozen,
         * never the v1.0.20 stall) while leaving the main thread
         * free for the attached tab's echo + draw. The attached tab
         * is never throttled.
         */
        private static final int BACKGROUND_DRAIN_MAX_BYTES = 16 * 1024;
        private static final long BACKGROUND_DRAIN_MIN_INTERVAL_MS = 100;

        /** v1.0.23: last wall-clock ms a throttled background drain ran (uptime). */
        private volatile long mLastBackgroundDrainMs = 0L;
        /** v1.0.23: one delayed background drain already outstanding — coalesces re-posts. */
        private volatile boolean mBackgroundDrainPending = false;

        /** v1.0.23: schedule ONE delayed background drain (coalesced — no pile-up). */
        private void scheduleBackgroundDrain(long delayMs) {
            if (mBackgroundDrainPending) return;
            mBackgroundDrainPending = true;
            mMainThreadHandler.sendEmptyMessageDelayed(MSG_NEW_INPUT, Math.max(0, delayMs));
        }

        /** v1.0.23: shared drain loop — appends up to maxBytes from the queue, returns bytes drained. */
        private int drainUpTo(int maxBytes) {
            int totalDrained = 0;
            while (totalDrained < maxBytes) {
                int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
                if (bytesRead <= 0) break;
                totalDrained += bytesRead;
                byte[] filtered = mLinkerWarningFilter.filter(mReceiveBuffer, 0, bytesRead);
                if (filtered.length > 0) {
                    mEmulator.append(filtered, filtered.length);
                }
            }
            return totalDrained;
        }

        // v1.0.19: display-only filter for the Bionic linker warning
        // (see LinkerWarningFilter). The PTY merges the linker's
        // stderr into the output stream, so the warning appeared on
        // EVERY command and looked like an error. The filter strips
        // only that exact sequence (+ one trailing newline), and is
        // cross-chunk safe (the warning can arrive split across
        // reads). Applied here — the single path from PTY bytes to
        // the emulator — so the Console display, transcript, and
        // Debug's "Last session output tail" all stay clean.
        final LinkerWarningFilter mLinkerWarningFilter = new LinkerWarningFilter();

        @Override
        public void handleMessage(Message msg) {
            // v1.0.21: DRAIN THE WHOLE QUEUE per message, not one
            // chunk. The old code read at most one 4 KiB chunk per
            // message while the reader posted one message per chunk
            // it wrote — so any main-thread hiccup left the queue
            // full, the reader blocked in ByteQueue.write(), the
            // PTY filled, and the guest process blocked on
            // write(). A background tab's animation "stopped" until
            // the user interacted. Draining until empty (capped)
            // keeps the reader — which runs continuously per
            // session, independent of attach/detach — unblocked.
            // notifyScreenUpdate() fires once per message; the
            // client redraws only for the attached session, so
            // background tabs update their emulators silently.
            //
            // v1.0.22: the drain runs for EVERY session, attached
            // or not — there is no attach-state gate anywhere in
            // this path (reader thread -> queue -> handler ->
            // append). Two hardening changes: (1) SELF-PUMP — if the
            // cap leaves bytes queued, re-post so the leftover
            // drains immediately instead of waiting for the
            // reader's next post; the re-post goes to the TAIL of
            // the Looper queue, so draws/touches posted before it
            // still run first. (2) The drain watchdog thread (see
            // initializeEmulator) kicks a drain if the queue is
            // non-empty and no drain has run for >1s. Together they
            // make "drains continuously regardless of attach state"
            // true by construction, and the per-session counters
            // (getBytesReadFromPty / getBytesAppendedToEmulator /
            // getQueueDepth / getLastDrainTimeMs) prove it in Debug.
            //
            // v1.0.23: PRIORITIZE the attached session. The drain
            // above is fair, but "fair" still lets a flooding
            // background animation consume most main-thread time
            // and starve the attached tab's typing echo + redraw.
            // Background drains are now time-throttled (see
            // BACKGROUND_DRAIN_* above); the attached tab drains
            // promptly and in full. The exit path is NEVER
            // throttled — a finished background tab must still
            // flush + print "[Process completed]" without waiting
            // for a throttle window.
            mHandlerMessageCount.incrementAndGet();
            boolean isExit = (msg.what == MSG_PROCESS_EXITED);
            if (!mIsForeground && !isExit) {
                // --- Background session: throttled drain. ---
                long now = SystemClock.uptimeMillis();
                if (now - mLastBackgroundDrainMs < BACKGROUND_DRAIN_MIN_INTERVAL_MS) {
                    scheduleBackgroundDrain(BACKGROUND_DRAIN_MIN_INTERVAL_MS - (now - mLastBackgroundDrainMs));
                    return;
                }
                mBackgroundDrainPending = false;
                mLastBackgroundDrainMs = now;
                int drained = drainUpTo(BACKGROUND_DRAIN_MAX_BYTES);
                if (drained > 0) {
                    mBytesAppendedToEmulator.addAndGet(drained);
                    mLastDrainTimeMs = System.currentTimeMillis();
                    notifyScreenUpdate();
                }
                if (mProcessToTerminalIOQueue.storedBytes() > 0) {
                    scheduleBackgroundDrain(BACKGROUND_DRAIN_MIN_INTERVAL_MS);
                }
            } else {
                // --- Attached session (or any session's exit flush): full drain, immediate self-pump. ---
                mBackgroundDrainPending = false;
                int totalDrained = drainUpTo(MAX_DRAIN_PER_MESSAGE);
                if (totalDrained > 0) {
                    mBytesAppendedToEmulator.addAndGet(totalDrained);
                    mLastDrainTimeMs = System.currentTimeMillis();
                    notifyScreenUpdate();
                }
                // Self-pump: the cap left bytes queued — drain them
                // now instead of waiting for the reader's next post.
                if (mProcessToTerminalIOQueue.storedBytes() > 0) {
                    mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                }
            }

            if (isExit) {
                int exitCode = (Integer) msg.obj;
                cleanupResources(exitCode);

                // Flush the linker-warning filter first, so any
                // bytes held back for a split-warning decision are
                // emitted BEFORE the "[Process completed...]" line.
                byte[] filterTail = mLinkerWarningFilter.flush();
                if (filterTail.length > 0) {
                    mEmulator.append(filterTail, filterTail.length);
                    notifyScreenUpdate();
                }

                String exitDescription = "\r\n[Process completed";
                if (exitCode > 0) {
                    // Non-zero process exit.
                    exitDescription += " (code " + exitCode + ")";
                } else if (exitCode < 0) {
                    // Negated signal.
                    exitDescription += " (signal " + (-exitCode) + ")";
                }
                exitDescription += " - press Enter]";

                byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
