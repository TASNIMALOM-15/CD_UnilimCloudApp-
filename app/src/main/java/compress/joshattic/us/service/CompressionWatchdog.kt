package compress.joshattic.us.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Dead-man switch. While compression runs, the service keeps pushing this job
 * ~90 seconds into the future. If the process dies, nobody pushes it any more,
 * so it fires in a fresh process and shows "compression was interrupted".
 */
object CompressionWatchdog {
    private const val WORK_NAME = "compression_watchdog"
    private const val DELAY_SECONDS = 90L

    fun arm(context: Context) {
        try {
            val request = OneTimeWorkRequest.Builder(CompressionWatchdogWorker::class.java)
                .setInitialDelay(DELAY_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun disarm(context: Context) {
        try {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class CompressionWatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        try {
            val ctx = applicationContext
            if (CompressionNotifier.isActive) {
                // App is alive but the service was killed: bring it back.
                if (!CompressionService.isRunning) CompressionService.start(ctx)
            } else if (CompressionNotifier.hasSavedJob(ctx)) {
                // Fresh process + unfinished job = the app was killed mid-compression.
                CompressionNotifier.showInterrupted(ctx)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Result.success()
    }
}

/** After a phone restart or app update: remind the user about an unfinished compression. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (CompressionNotifier.hasSavedJob(context)) {
                CompressionNotifier.showInterrupted(context)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
