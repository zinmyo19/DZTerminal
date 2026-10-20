package com.zynelabs.dzterminal

import android.content.Context
import android.util.TypedValue

/**
 * v1.0.42: the ONE persisted Console font size, shared by pinch
 * zoom (ConsoleActivity's TerminalViewClient.onScale) and the
 * Settings slider — whichever changed last wins. ConsoleActivity
 * reads it at start and on resume; both writers go through here
 * so the two can never drift apart.
 *
 * The persisted unit is SP (what the user sees on the slider);
 * the vendored Termux renderer takes PIXELS, converted here the
 * same way ConsoleActivity always did (TypedValue.applyDimension).
 * DEFAULT_SP = 15 preserves the exact v1.0–v1.0.41 rendering.
 */
object FontPrefs {

    /** Same prefs file ConsoleActivity already uses (its PREFS const). */
    const val PREFS_FILE = "dz_terminal_prefs"
    private const val KEY_FONT_SIZE_SP = "font_size_sp_v1042"

    const val MIN_SP = 8
    const val MAX_SP = 40
    const val DEFAULT_SP = 15

    fun getSp(context: Context): Int =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getInt(KEY_FONT_SIZE_SP, DEFAULT_SP)
            .coerceIn(MIN_SP, MAX_SP)

    /** Clamps, persists, and returns the value actually stored. */
    fun setSp(context: Context, sp: Int): Int {
        val clamped = sp.coerceIn(MIN_SP, MAX_SP)
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_FONT_SIZE_SP, clamped).apply()
        return clamped
    }

    fun spToPx(context: Context, sp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
}
