package com.zynelabs.dzterminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Foreground service that keeps Console sessions alive in the
 * background (v1.0.26).
 *
 * The problem: with sessions held only in DzTerminalApp (v1.0.20),
 * leaving the app via Home / switching apps / screen off let
 * Android kill or suspend the process, and running sessions
 * disappeared. A foreground service raises the process to
 * foreground importance so the system does not kill it for
 * memory, and a PARTIAL_WAKE_LOCK keeps the CPU awake while the
 * screen is off so the PTY readers / proot processes / drain
 * watchdog keep running. The screen itself may sleep — the
 * lock is PARTIAL, not FULL/SCREEN_DIM.
 *
 * Lifetime rules:
 * - Started when the first session starts (ConsoleActivity calls
 *   [sync] after every session add / close / finish). NOT started
 *   at app launch with no session.
 * - While >= 1 session is running: foreground notification
 *   "DZ Terminal — N session(s) running" (N updated as sessions
 *   start/end) + wake lock held.
 * - When the last session ends (natural exit, tab close, or the
 *   notification's Stop action): wake lock released, service
 *   stopped. The lock is NEVER held with zero sessions running,
 *   so there is no idle battery drain.
 * - Stop action: kills ALL sessions (finishIfRunning on each),
 *   releases the lock, stops the service. The Console, if open,
 *   shows the "Session ended" banner for its current tab.
 * - onTaskRemoved does NOT stop the service: swiping the app
 *   away from recents must NOT kill running sessions — that is
 *   the whole point. START_STICKY: if the system still kills
 *   the process, the service restarts, sees zero live sessions
 *   (they died with the process), and stops itself.
 * - Natural session end while the app is backgrounded: no
 *   Activity exists to receive onSessionFinished, so the service
 *   re-checks the live-session count every 5 s and stops itself
 *   when it reaches zero.
 *
 * Permissions / targetSdk notes (verified, not assumed):
 * - WAKE_LOCK + FOREGROUND_SERVICE are both normal permissions:
 *   granted automatically at install, no runtime prompt.
 * - No foregroundServiceType attribute: the FGS-type requirement
 *   (and the FOREGROUND_SERVICE_* per-type permissions) applies
 *   to apps targeting API 29+ / 34. This app targets SDK 28,
 *   so no type is required or declared. Declaring one (e.g.
 *   dataSync) would demand an extra permission for no benefit.
 * - No POST_NOTIFICATIONS prompt: that runtime permission exists
 *   only for apps targeting API 33+. At targetSdk 28 the service
 *   notification is allowed by default. No permission flow added.
 */
class SessionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            refresh()
            if (polling) handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            stopAllSessions()
            return START_NOT_STICKY
        }
        // startForeground FIRST: after startForegroundService() the
        // system kills the service if startForeground is not called
        // within ~5 s. Nothing slow happens before this line.
        startForeground(NOTIF_ID, buildNotification(runningSessionCount()))
        acquireWakeLock()
        startPolling()
        // The count may already be zero (e.g. the session ended
        // between sync() and the service starting): refresh() stops
        // the service immediately instead of idling with a lock.
        refresh()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        polling = false
        handler.removeCallbacks(pollRunnable)
        releaseWakeLock()
        isServiceRunning = false
        super.onDestroy()
    }

    // onTaskRemoved deliberately NOT overridden to stop: swiping
    // the app away must not kill running sessions.

    /** Re-reads the live-session count; updates or stops accordingly. */
    private fun refresh() {
        val count = runningSessionCount()
        if (count <= 0) {
            releaseWakeLock()
            stopForeground(true)
            stopSelf()
            return
        }
        acquireWakeLock()
        startForeground(NOTIF_ID, buildNotification(count))
    }

    private fun runningSessionCount(): Int {
        val app = application as? DzTerminalApp ?: return 0
        // sessions is mutated on the UI thread only; reading it
        // from the service's main-looper poll is the same thread.
        return try {
            app.sessions.count { it.isRunning }
        } catch (_: Exception) {
            0
        }
    }

    private fun acquireWakeLock() {
        try {
            val existing = wakeLock
            if (existing != null) {
                if (!existing.isHeld) existing.acquire()
                isWakeLockHeld = existing.isHeld
                return
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val created = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DZTerminal:SessionWakeLock"
            )
            created.setReferenceCounted(false)
            wakeLock = created
            created.acquire()
            isWakeLockHeld = created.isHeld
        } catch (_: Exception) {
            isWakeLockHeld = false
        }
    }

    private fun releaseWakeLock() {
        try {
            val lock = wakeLock
            if (lock != null && lock.isHeld) {
                lock.release()
            }
        } catch (_: Exception) {
        } finally {
            isWakeLockHeld = false
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.postDelayed(pollRunnable, POLL_MS)
    }

    /** Stop action: kill every session, drop the lock, stop. */
    private fun stopAllSessions() {
        try {
            val app = application as? DzTerminalApp
            val snapshot = app?.sessions?.toList() ?: emptyList()
            for (session in snapshot) {
                try {
                    session.finishIfRunning()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        releaseWakeLock()
        stopForeground(true)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Terminal sessions",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Shows while DZ Terminal sessions are running in the background"
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(sessionCount: Int): Notification {
        val text = if (sessionCount <= 0) {
            "No sessions running"
        } else if (sessionCount == 1) {
            "DZ Terminal — 1 session running"
        } else {
            "DZ Terminal — $sessionCount sessions running"
        }
        val openIntent = Intent(this, ConsoleActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, SessionService::class.java).apply {
            action = ACTION_STOP_ALL
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("DZ Terminal")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopPending
                ).build()
            )
            .build()
    }

    companion object {
        const val CHANNEL_ID = "dz_terminal_sessions"
        const val ACTION_STOP_ALL = "com.zynelabs.dzterminal.action.STOP_ALL_SESSIONS"
        private const val NOTIF_ID = 1001
        private const val POLL_MS = 5000L

        /** True while the service instance exists (for Debug). */
        @Volatile var isServiceRunning: Boolean = false
            private set

        /** True while the partial wake lock is held (for Debug). */
        @Volatile var isWakeLockHeld: Boolean = false
            private set

        /**
         * The single entry point for session-count changes.
         * Call after a session starts, ends, or is closed.
         * Starts the service when >= 1 session is running;
         * tells a running service to stop when none are.
         * A no-op when the desired state already holds.
         */
        fun sync(context: Context) {
            val app = context.applicationContext as? DzTerminalApp ?: return
            val running = try {
                app.sessions.count { it.isRunning }
            } catch (_: Exception) {
                0
            }
            if (running > 0) {
                val intent = Intent(context.applicationContext, SessionService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.applicationContext.startForegroundService(intent)
                    } else {
                        context.applicationContext.startService(intent)
                    }
                } catch (_: Exception) {
                    // startForegroundService can throw if the app is
                    // in a restricted background state; the sessions
                    // keep running in-process regardless — the
                    // service is a keep-alive, not the session host.
                }
            } else if (isServiceRunning) {
                val stopIntent = Intent(context.applicationContext, SessionService::class.java).apply {
                    action = ACTION_STOP_ALL
                }
                try {
                    context.applicationContext.startService(stopIntent)
                } catch (_: Exception) {
                }
            }
        }
    }
}
