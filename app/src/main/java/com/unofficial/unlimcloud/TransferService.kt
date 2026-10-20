package com.unofficial.unlimcloud

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * আপলোড/ডাউনলোড চলাকালীন অ্যাপ ব্যাকগ্রাউন্ডে গেলেও প্রসেস বাঁচিয়ে রাখে।
 * কোনো স্থানান্তর না থাকলে নিজে থেকেই বন্ধ হয়ে যায়।
 */
class TransferService : Service() {

    companion object {
        const val ACTION_CANCEL_ALL = "com.unofficial.unlimcloud.CANCEL_ALL"
        private const val NOTIF_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        val first = Notifications.transfer(this, 0, 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, first)
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        scope.launch {
            TdManager.transfers.collect { map ->
                if (map.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val uploads = map.values.count { it.upload }
                    val downloads = map.size - uploads
                    val avg = (map.values.map { it.progress }.average() * 100).toInt()
                    nm.notify(NOTIF_ID, Notifications.transfer(this@TransferService, uploads, downloads, avg))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) TdManager.cancelAll()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
