package compress.joshattic.us.service

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Finds out WHY Android stopped the app (used in the "interrupted" notification and resume dialog). */
object ProcessExitInfo {
    private const val DIAG_PREFS = "diag"
    private const val KEY_FGS_ERROR = "fgs_error"

    fun recordServiceError(context: Context, text: String?) {
        try {
            context.applicationContext.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE)
                .edit().apply { if (text == null) remove(KEY_FGS_ERROR) else putString(KEY_FGS_ERROR, text) }
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun importanceName(value: Int): String = when {
        value <= 100 -> "app was on screen"
        value <= 125 -> "foreground service was running (good)"
        value <= 230 -> "visible / perceptible"
        value <= 300 -> "plain background service"
        else -> "cached background app (foreground service was NOT active)"
    }

    fun describeLast(context: Context): String? {
        val lines = mutableListOf<String>()
        try {
            val err = context.applicationContext.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_FGS_ERROR, null)
            if (err != null) lines += "Service error: $err"
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 5).firstOrNull()
                if (info != null) {
                    val reason = when (info.reason) {
                        1 -> "exited by itself"
                        2 -> "killed by signal (system / phone cleaner)"
                        3 -> "LOW MEMORY"
                        4 -> "app crash"
                        5 -> "native crash"
                        6 -> "app not responding"
                        7 -> "start-up failure"
                        8 -> "permission change"
                        9 -> "too much resource use"
                        10 -> "closed by user (swipe / force stop)"
                        11 -> "stopped by user"
                        12 -> "dependency died"
                        13 -> "other"
                        14 -> "frozen by system"
                        15 -> "package state change"
                        16 -> "app updated"
                        else -> "unknown (${info.reason})"
                    }
                    val time = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(Date(info.timestamp))
                    lines += "Reason: $reason"
                    lines += "Detail: ${info.description ?: "-"}"
                    lines += "State: ${importanceName(info.importance)}"
                    lines += "Memory used: ${info.rss / 1024} MB • $time"
                }
            } catch (_: Exception) {
            }
        }
        return if (lines.isEmpty()) null else lines.joinToString("\n")
    }
}

/** Opens the phone's own background / auto-start / battery screens (Infinix, Tecno, itel, others). */
object BackgroundSettings {
    fun open(context: Context) {
        val candidates = mutableListOf<Intent>()
        candidates += Intent().setComponent(
            ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity")
        )
        candidates += Intent().setComponent(
            ComponentName("com.transsion.phonemaster", "com.cyin.himgr.MainActivity")
        )
        candidates += Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        candidates += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
    }
}
