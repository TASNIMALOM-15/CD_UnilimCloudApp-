package com.unofficial.unlimcloud

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    private const val CH_TRANSFER = "transfers"
    private const val CH_DONE = "done"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_TRANSFER, "ফাইল স্থানান্তর", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "সম্পন্ন ডাউনলোড", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** চলমান আপলোড/ডাউনলোডের স্থায়ী নোটিফিকেশন (foreground service-এর জন্য)। */
    fun transfer(ctx: Context, uploads: Int, downloads: Int, progressPercent: Int): Notification {
        val parts = ArrayList<String>()
        if (uploads > 0) parts.add("$uploads আপলোড")
        if (downloads > 0) parts.add("$downloads ডাউনলোড")
        val text = if (parts.isEmpty()) "প্রস্তুত হচ্ছে…" else parts.joinToString(" • ") + " চলছে"

        val openApp = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelAll = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, TransferService::class.java).setAction(TransferService.ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, CH_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Unlim Cloud")
            .setContentText(text)
            .setProgress(100, progressPercent.coerceIn(0, 100), parts.isEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, "সব বাতিল", cancelAll)
            .build()
    }

    /** একটি ডাউনলোড শেষ হলে সাধারণ নোটিফিকেশন। */
    @SuppressLint("MissingPermission")
    fun done(ctx: Context, title: String, text: String) {
        try {
            ensureChannels(ctx)
            val openApp = PendingIntent.getActivity(
                ctx, 2, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .build()
            NotificationManagerCompat.from(ctx).notify(text.hashCode(), n)
        } catch (e: Exception) {
            // নোটিফিকেশনের অনুমতি না থাকলে চুপচাপ বাদ
        }
    }
}
