package compress.joshattic.us.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the app process (and the CPU) alive while videos
 * are being compressed. Every 2 seconds it re-posts the notification and refreshes
 * the wake lock (heartbeat). Every ~30 seconds it re-arms a WorkManager "dead-man
 * switch": if the process is killed, that job fires and tells the user.
 */
class CompressionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ticks = 0

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                CompressionNotifier.repost(this@CompressionService)
                ensureWakeLock()
                if (ticks % WATCHDOG_EVERY_TICKS == 0) {
                    CompressionWatchdog.arm(this@CompressionService)
                }
                ticks++
            } catch (e: Exception) {
                e.printStackTrace()
            }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Android restarted this service after the app process was killed.
            // The compression engine died with the process, so just tell the user.
            if (CompressionNotifier.hasSavedJob(this)) {
                CompressionNotifier.showInterrupted(this)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        CompressionNotifier.markStarted()
        val notification = CompressionNotifier.buildProgress(this, 0, null, 1, 1)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    CompressionNotifier.PROGRESS_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(CompressionNotifier.PROGRESS_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        ensureWakeLock()
        CompressionWatchdog.arm(this)
        ticks = 1
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        return START_STICKY
    }

    private fun ensureWakeLock() {
        try {
            val lock = wakeLock ?: run {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Compressor:compression").apply {
                    setReferenceCounted(false)
                }.also { wakeLock = it }
            }
            if (!lock.isHeld) lock.acquire(6 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(heartbeat)
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val HEARTBEAT_MS = 2000L
        private const val WATCHDOG_EVERY_TICKS = 15 // 15 x 2s = 30s

        @Volatile
        private var running = false

        val isRunning: Boolean get() = running

        fun start(context: Context) {
            if (running) return
            try {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, CompressionService::class.java)
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            try {
                CompressionWatchdog.disarm(context)
                context.applicationContext.stopService(
                    Intent(context.applicationContext, CompressionService::class.java)
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
