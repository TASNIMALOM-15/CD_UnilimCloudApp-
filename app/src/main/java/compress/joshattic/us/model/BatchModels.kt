package compress.joshattic.us.model

import android.net.Uri

/** One finished compression, shown in Settings -> History. */
data class HistoryEntry(
    val timestamp: Long,
    val name: String,
    val originalBytes: Long,
    val compressedBytes: Long,
    val resolution: String,
    val fps: Int,
    val uri: String? = null, // where the compressed video was saved (for thumbnail / playback)
    val durationMs: Long = 0L // how long the compression took
)

/** A saved Video-tab combination (codec + resolution + framerate). 0 = original. */
data class SavedCombo(
    val videoCodec: String,
    val resolutionShortSide: Int,
    val fps: Int
)

/**
 * Settings captured when a compression starts. They are re-applied to every following
 * video of a queue, and are also what gets restored when an interrupted job is resumed.
 */
data class BatchTemplate(
    val videoCodec: String,
    val resolutionShortSide: Int, // 0 = original
    val fps: Int,                 // 0 = original
    val audioBitrate: Int,
    val removeAudio: Boolean,
    val audioVolume: Float,
    val sizeRatio: Float          // target size / original size
)

/** An unfinished job found on disk when the app starts (e.g. after the phone switched off). */
data class SavedJob(
    val current: Uri,
    val pending: List<Uri>,
    val total: Int,
    val done: Int,
    val template: BatchTemplate
)
