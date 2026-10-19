package com.termux.terminal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Display-only filter for the Bionic linker warning.
 *
 * <p>Every guest ELF exec prints this one line to stderr, and the PTY
 * merges it into the terminal output stream, so it showed up on EVERY
 * command and looked like an error (v1.0.18: the user read it as
 * "ls / cd don't work"). The warning itself is harmless — the linker
 * just could not open /linkerconfig/ld.config.txt — so instead of
 * fabricating a linker config inside the guest (an empty or
 * hand-written ld.config.txt risks breaking library loading), the
 * app strips the exact warning line from the displayed output.
 *
 * <p>Removes ONLY this exact byte sequence (plus one trailing newline,
 * "\n" or "\r\n", when present):
 * WARNING: linker: Warning: failed to find generated linker configuration
 * from "/linkerconfig/ld.config.txt"
 * Every other byte passes through untouched.
 *
 * <p>Cross-chunk safe: the warning can arrive SPLIT across PTY reads
 * (it has been observed mid-line, e.g. "94%WARNING: linker: ...",
 * because stderr/stdout interleave on the PTY). The filter holds
 * back a trailing tail that could be the start of a split warning
 * (a proper prefix of the warning, or a complete warning whose
 * trailing newline may arrive in the next chunk) and re-examines
 * it with the next chunk. Call {@link #flush()} at stream end to
 * emit any held bytes (a partial warning at stream end is not the
 * exact sequence, so it is emitted, not dropped).
 *
 * <p>Single-threaded: use only from the session's main-thread
 * handler (the only caller).
 */
public final class LinkerWarningFilter {

    /** The exact warning bytes (UTF-8, no trailing newline). */
    public static final byte[] WARNING = ("WARNING: linker: Warning: failed to find generated linker configuration " +
            "from \"/linkerconfig/ld.config.txt\"").getBytes(StandardCharsets.UTF_8);

    private static final byte[] EMPTY = new byte[0];

    /** Tail held back from the previous chunk (a possible split warning). */
    private byte[] mPending = EMPTY;

    /**
     * Filters one chunk. Returns the bytes to emit now (may be empty).
     * The returned array is newly allocated; the input is not modified.
     */
    public byte[] filter(byte[] data, int offset, int length) {
        if (length <= 0 && mPending.length == 0) return EMPTY;
        byte[] buf = new byte[mPending.length + length];
        System.arraycopy(mPending, 0, buf, 0, mPending.length);
        System.arraycopy(data, offset, buf, mPending.length, length);
        mPending = EMPTY;

        // Remove complete warnings; a warning ending exactly at the
        // end of buf is COPIED (not removed) so the hold-back below
        // can keep it until the next chunk decides the newline.
        byte[] out = removeWarnings(buf, true);

        // Hold back the longest trailing suffix that is a prefix of
        // WARNING (length 1..WARNING.length). A complete WARNING at
        // the end is held too: its trailing newline may arrive in
        // the next chunk and must be consumed with it.
        int hold = trailingWarningPrefixLength(out);
        if (hold > 0) {
            mPending = Arrays.copyOfRange(out, out.length - hold, out.length);
            out = Arrays.copyOf(out, out.length - hold);
        }
        return out;
    }

    /** Emits any held bytes at stream end (complete warnings removed). */
    public byte[] flush() {
        byte[] out = removeWarnings(mPending, false);
        mPending = EMPTY;
        return out;
    }

    /**
     * Removes complete WARNING occurrences from buf (plus one
     * following newline, "\n" or "\r\n", when present).
     * When holdTrailing is true, a WARNING ending exactly at the end
     * of buf is copied to the output instead of removed, so the
     * caller can hold it for the next chunk's newline decision.
     */
    private static byte[] removeWarnings(byte[] buf, boolean holdTrailing) {
        // Fast path: no occurrence.
        if (indexOf(buf, WARNING, 0) < 0) return Arrays.copyOf(buf, buf.length);
        byte[] out = new byte[buf.length];
        int outLen = 0;
        int pos = 0;
        while (true) {
            int at = indexOf(buf, WARNING, pos);
            if (at < 0) {
                System.arraycopy(buf, pos, out, outLen, buf.length - pos);
                outLen += buf.length - pos;
                break;
            }
            System.arraycopy(buf, pos, out, outLen, at - pos);
            outLen += at - pos;
            int after = at + WARNING.length;
            if (after == buf.length && holdTrailing) {
                // Trailing complete warning: copy it; the caller
                // holds it for the next chunk's newline decision.
                System.arraycopy(WARNING, 0, out, outLen, WARNING.length);
                outLen += WARNING.length;
                pos = buf.length;
                break;
            }
            if (after == buf.length - 1 && holdTrailing && buf[after] == (byte) '\r') {
                // Trailing warning + dangling CR: the LF may arrive
                // in the next chunk. Copy both; the caller holds
                // them so the split CRLF is consumed as one unit.
                // (If the next chunk does NOT start with LF, the CR
                // is emitted untouched — see removeWarnings' CRLF
                // check on the combined buffer.)
                System.arraycopy(WARNING, 0, out, outLen, WARNING.length);
                outLen += WARNING.length;
                out[outLen++] = (byte) '\r';
                pos = buf.length;
                break;
            }
            // Skip one trailing newline when present.
            if (after < buf.length && buf[after] == (byte) '\n') {
                after += 1;
            } else if (after + 1 < buf.length && buf[after] == (byte) '\r' && buf[after + 1] == (byte) '\n') {
                after += 2;
            }
            pos = after;
        }
        return Arrays.copyOf(out, outLen);
    }

    /**
     * Longest suffix of buf to hold back for the next chunk:
     * either a prefix of WARNING (length 1..WARNING.length; a
     * suffix equal to the complete WARNING counts — its trailing
     * newline may still arrive in the next chunk), or the complete
     * WARNING + a dangling CR (length WARNING.length + 1 — the LF
     * of a split CRLF may arrive in the next chunk). Returns 0
     * when there is nothing to hold.
     */
    private static int trailingWarningPrefixLength(byte[] buf) {
        // WARNING + dangling CR first (longer hold wins).
        if (buf.length >= WARNING.length + 1 && buf[buf.length - 1] == (byte) '\r') {
            boolean match = true;
            for (int i = 0; i < WARNING.length; i++) {
                if (buf[buf.length - 1 - WARNING.length + i] != WARNING[i]) {
                    match = false;
                    break;
                }
            }
            if (match) return WARNING.length + 1;
        }
        int max = Math.min(buf.length, WARNING.length);
        for (int k = max; k >= 1; k--) {
            boolean match = true;
            for (int i = 0; i < k; i++) {
                if (buf[buf.length - k + i] != WARNING[i]) {
                    match = false;
                    break;
                }
            }
            if (match) return k;
        }
        return 0;
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        if (needle.length == 0 || hay.length < needle.length) return -1;
        outer:
        for (int i = from; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
