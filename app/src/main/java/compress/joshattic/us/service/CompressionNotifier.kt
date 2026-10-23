package compress.joshattic.us.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import compress.joshattic.us.MainActivity
import compress.joshattic.us.R

/** Builds and posts the live-progress / finished / interrupted notifications. */
object CompressionNotifier {
    const val CHANNEL_ID = "compression_progress"
    const val ALERT_CHANNEL_ID = "compression_alerts"
    const val PROGRESS_ID = 1001
    const val DONE_ID = 1002
    const val INTERRUPTED_ID = 1003
    const val EXTRA_AUTO_RESUME = "compress.joshattic.us.AUTO_RESUME"

    private const val PREFS_NAME = "compressor_prefs"
    private const val PREF_SAVED_JOB = "saved_job_v1"

    /** True while a compression is running inside this process. */
    @Volatile
    var isActive = false
        private set

    @Volatile private var startedAt = 0L
    @Volatile private var lastPercent = 0
    @Volatile private var lastName: String? = null
    @Volatile private var lastIndex = 1
    @Volatile private var lastTotal = 1

    fun markStarted() {
        if (!isActive) {
            isActive = true
            startedAt = System.currentTimeMillis()
        }
    }

    fun markStopped() {
        isActive = false
        startedAt = 0L
        lastPercent = 0
        lastName = null
    }

    fun hasSavedJob(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .contains(PREF_SAVED_JOB)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            manager.createNotificationChannel(channel)
        }
        if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                context.getString(R.string.notif_alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun openAppIntent(context: Context, autoResume: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (autoResume) putExtra(EXTRA_AUTO_RESUME, true)
        }
        return PendingIntent.getActivity(
            context,
            if (autoResume) 1 else 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildProgress(
        context: Context,
        percent: Int,
        name: String?,
        queueIndex: Int,
        queueTotal: Int
    ): Notification {
        ensureChannel(context)
        val title = if (queueTotal > 1) {
            context.getString(R.string.notif_compressing_queue, queueIndex, queueTotal)
        } else {
            context.getString(R.string.notif_compressing)
        }
        val text = if (name.isNullOrBlank()) "$percent%" else "$percent% • $name"
        val whenMs = if (startedAt > 0L) startedAt else System.currentTimeMillis()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setWhen(whenMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent(context))
            .build()
    }

    fun updateProgress(
        context: Context,
        percent: Int,
        name: String?,
        queueIndex: Int,
        queueTotal: Int
    ) {
        markStarted()
        lastPercent = percent
        lastName = name
        lastIndex = queueIndex
        lastTotal = queueTotal
        try {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            manager.notify(PROGRESS_ID, buildProgress(context, percent, name, queueIndex, queueTotal))
        } catch (e: SecurityException) {
            // Notification permission missing: ignore, compression still works
        }
    }

    /** Called by the service every 2 seconds: re-posts the latest progress (keep-alive heartbeat). */
    fun repost(context: Context) {
        try {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            manager.notify(
                PROGRESS_ID,
                buildProgress(context, lastPercent, lastName, lastIndex, lastTotal)
            )
        } catch (e: SecurityException) {
            // ignore
        }
    }

    fun showDone(context: Context, message: String) {
        try {
            ensureChannel(context)
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.notif_done_title))
                .setContentText(message)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context))
                .build()
            manager.notify(DONE_ID, notification)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    /** Shown when the system killed the app mid-compression. Tapping it continues the job. */
    fun showInterrupted(context: Context) {
        try {
            ensureChannel(context)
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            val reason = ProcessExitInfo.describeLast(context)
            val text = context.getString(R.string.notif_interrupted_text)
            val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(context.getString(R.string.notif_interrupted_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(if (reason == null) text else "$text\n\n$reason"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context, autoResume = true))
                .build()
            manager.notify(INTERRUPTED_ID, notification)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    fun clearInterrupted(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(INTERRUPTED_ID)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    fun clearProgress(context: Context) {
        markStopped()
        try {
            NotificationManagerCompat.from(context).cancel(PROGRESS_ID)
        } catch (e: SecurityException) {
            // ignore
        }
    }
}
