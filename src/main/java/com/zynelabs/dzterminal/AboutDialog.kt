package com.zynelabs.dzterminal

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * About dialog — shared by Home's 3-dot menu and Settings.
 *
 * v1.0.29: adds a Contact card (Telegram + GitHub, both tappable
 * and opening in the browser). The license text is unchanged:
 * Termux GPL-3.0, jackpal Apache-2.0, DZ Terminal GPL-3.0.
 */
object AboutDialog {

    const val TELEGRAM_URL = "https://t.me/Dominic_aiBot"
    const val GITHUB_URL = "https://github.com/zinmyo19/DZTerminal"

    private const val LICENSE_TEXT =
        "Python + Linux terminal for Android.\n\n" +
        "Terminal engine based on Termux (GPL-3.0).\n" +
        "Terminal view/emulator code derived from the " +
        "jackpal Android Terminal Emulator (Apache-2.0).\n\n" +
        "DZ Terminal is released under GPL-3.0."

    fun show(activity: Activity) {
        val version = try {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        val title = if (version.isEmpty()) "DZ Terminal" else "DZ Terminal $version"

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(activity, 20)
            setPadding(pad, dp(activity, 8), pad, dp(activity, 4))
        }
        root.addView(TextView(activity).apply {
            text = LICENSE_TEXT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })

        // Contact card.
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            val pad = dp(activity, 16)
            setPadding(pad, dp(activity, 12), pad, dp(activity, 12))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(activity, 16)
            layoutParams = params
        }
        card.addView(TextView(activity).apply {
            text = "Contact"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(contactRow(activity, "Telegram", TELEGRAM_URL))
        card.addView(contactRow(activity, "GitHub", GITHUB_URL))
        root.addView(card)

        val scroll = ScrollView(activity).apply { addView(root) }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("OK", null)
            .show()
    }

    /** One contact row: label + tappable URL. Tapping either opens the URL in the browser. */
    private fun contactRow(activity: Activity, label: String, url: String): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(activity, 10)
            layoutParams = params
            isClickable = true
            setOnClickListener { openUrl(activity, url) }
        }
        row.addView(TextView(activity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(activity.resources.getColor(R.color.dz_muted, null))
        })
        row.addView(TextView(activity).apply {
            text = url
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(activity.resources.getColor(R.color.dz_green, null))
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            gravity = Gravity.START
        })
        return row
    }

    private fun openUrl(activity: Activity, url: String) {
        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            // No browser installed: tapping is a no-op rather than a crash.
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            activity.resources.displayMetrics
        ).toInt()
}
