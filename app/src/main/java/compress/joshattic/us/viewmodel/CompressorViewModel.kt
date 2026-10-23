package compress.joshattic.us.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import compress.joshattic.us.BuildConfig
import compress.joshattic.us.R
import compress.joshattic.us.model.BatchTemplate
import compress.joshattic.us.model.CompressorUiState
import compress.joshattic.us.model.GlassSettings
import compress.joshattic.us.model.HistoryEntry
import compress.joshattic.us.model.SavedCombo
import compress.joshattic.us.model.SavedJob
import compress.joshattic.us.service.CompressionNotifier
import compress.joshattic.us.service.CompressionService
import compress.joshattic.us.model.FilenameSegment
import compress.joshattic.us.model.DefaultAudioConfig
import compress.joshattic.us.model.DefaultVideoConfig
import compress.joshattic.us.model.QualityPreset
import compress.joshattic.us.model.QualityPresetConfig
import compress.joshattic.us.model.TargetSizePreset
import compress.joshattic.us.utils.VolumeAudioProcessor
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
class CompressorViewModel(application: Application) : AndroidViewModel(application) {
    private data class VideoTrackInfo(
        val mimeType: String?,
        val width: Int,
        val height: Int,
        val frameRate: Float
    )

    private data class CompressionPlan(
        val outputVideoMimeType: String,
        val outputHeight: Int,
        val outputFps: Int,
        val warnings: List<String>,
        val blockingError: String?
    )

    private val _uiState = MutableStateFlow(CompressorUiState())
    val uiState = _uiState.asStateFlow()
    
    private val prefs: SharedPreferences by lazy {
        getApplication<Application>().getSharedPreferences("compressor_prefs", Context.MODE_PRIVATE)
    }

    private class TrackProbe(
        val video: VideoTrackInfo?,
        val audioMime: String?,
        val audioBitrate: Int,
        val aacProfile: Int
    ) {
        val hasAudio: Boolean get() = audioMime != null
    }

    companion object {
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private val cachedCodecInfos: List<android.media.MediaCodecInfo> by lazy {
            try {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        private const val PREF_CUSTOM_OUTPUT_TREE_URI = "custom_output_tree_uri"
        private const val PREF_CUSTOM_OUTPUT_FOLDER_NAME = "custom_output_folder_name"
        private const val PREF_SAVED_VERSION_CODE = "saved_app_version_code"
        private const val PREF_FILENAME_SEGMENTS = "filename_segments_v3"
        private const val PREF_SAVED_JOB = "saved_job_v1"
        private const val PREF_HISTORY = "history_v1"
        private const val PREF_THEME_SEED = "theme_seed_color"
        private const val PREF_CUSTOM_RES = "custom_resolutions_v1"
        private const val PREF_CUSTOM_FPS = "custom_fps_v1"
        private const val PREF_COMBOS = "saved_combos_v1"
        private const val MAX_HISTORY = 500
        private const val DEFAULT_FILENAME_SEGMENTS = "token:original_name|token:resolution"
        private val CURRENT_VERSION_CODE = BuildConfig.VERSION_CODE
        private const val PERSIST_URI_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }

    init {
        loadSettingsFromPrefs(initial = true)
        checkSupportedCodecs()
        clearCache()
    }

    /** Fills the UI state from SharedPreferences (app start, and again after an import). */
    private fun loadSettingsFromPrefs(initial: Boolean) {
        val saved = prefs.getLong("total_saved_bytes", 0L)
        val showBitrate = prefs.getBoolean("show_bitrate", false)
        val useMbps = prefs.getBoolean("use_mbps", false)
        val showStorageSaved = prefs.getBoolean("show_storage_saved", true)
        val showTargetSizePreset = prefs.getBoolean("show_target_size_preset", true)
        val autoSaveSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val autoSaveToPhotos = autoSaveSupported && prefs.getBoolean("auto_save_photos", true)
        val customOutputTreeUri = prefs.getString(PREF_CUSTOM_OUTPUT_TREE_URI, null)
        val customOutputFolderName = prefs.getString(PREF_CUSTOM_OUTPUT_FOLDER_NAME, null)
        val highConfig = loadQualityPresetConfig("preset_high", QualityPresetConfig(resolutionShortSide = 0, targetFps = 0, sizeRatio = 0.7f, audioBitrate = 320_000))
        val mediumConfig = loadQualityPresetConfig("preset_medium", QualityPresetConfig(resolutionShortSide = 1080, targetFps = 30, sizeRatio = 0.4f, audioBitrate = 192_000))
        val lowConfig = loadQualityPresetConfig("preset_low", QualityPresetConfig(resolutionShortSide = 720, targetFps = 30, sizeRatio = 0.2f, audioBitrate = 128_000))
        val sizePresetsList = loadTargetSizePresets()
        val defaultVideo = loadDefaultVideoConfig()
        val defaultAudio = loadDefaultAudioConfig()
        val filenameSegments = deserializeSegments(
            prefs.getString(PREF_FILENAME_SEGMENTS, DEFAULT_FILENAME_SEGMENTS) ?: DEFAULT_FILENAME_SEGMENTS
        )

        val savedVersionCode = prefs.getInt(PREF_SAVED_VERSION_CODE, -1)
        val showWhatsNew = if (!initial) {
            false
        } else if (savedVersionCode != -1) {
            CURRENT_VERSION_CODE > savedVersionCode
        } else {
            val isExistingUser = prefs.all.isNotEmpty()
            if (!isExistingUser) {
                prefs.edit().putInt(PREF_SAVED_VERSION_CODE, CURRENT_VERSION_CODE).apply()
            }
            isExistingUser
        }

        _uiState.update { it.copy(
            totalSavedBytes = saved, 
            showBitrate = showBitrate, 
            useMbps = useMbps,
            showStorageSaved = showStorageSaved,
            showTargetSizePreset = showTargetSizePreset,
            autoSaveToPhotos = autoSaveToPhotos,
            customOutputTreeUri = customOutputTreeUri,
            customOutputFolderName = customOutputFolderName,
            highPresetConfig = highConfig,
            mediumPresetConfig = mediumConfig,
            lowPresetConfig = lowConfig,
            targetSizePresets = sizePresetsList,
            defaultVideoConfig = defaultVideo,
            defaultAudioConfig = defaultAudio,
            filenameSegments = filenameSegments,
            history = loadHistory(),
            customResolutions = loadIntList(PREF_CUSTOM_RES),
            customFpsValues = loadIntList(PREF_CUSTOM_FPS),
            savedCombos = loadCombos(),
            themeSeedColor = if (prefs.contains(PREF_THEME_SEED)) prefs.getInt(PREF_THEME_SEED, 0) else null,
        ) }
        _uiState.update { it.copy(glass = loadGlass()) }
        if (initial) {
            _uiState.update { it.copy(resumeJob = loadSavedJob(), showWhatsNewDialog = showWhatsNew) }
        }
    }

    // ---------------------------------------------------------------------
    // Custom background + Liquid Glass
    // ---------------------------------------------------------------------

    private fun loadGlass(): GlassSettings {
        val path = prefs.getString("glass_image_path", null)?.takeIf { java.io.File(it).exists() }
        return GlassSettings(
            enabled = prefs.getBoolean("glass_enabled", false),
            imagePath = path,
            imageVersion = prefs.getLong("glass_image_version", 0L),
            blur = prefs.getFloat("glass_blur", 8f),
            dim = prefs.getFloat("glass_dim", 0.25f),
            fit = prefs.getInt("glass_fit", 0),
            alpha = prefs.getFloat("glass_alpha", 0.25f),
            textMode = prefs.getInt("glass_text_mode", 0),
            isVideo = prefs.getBoolean("glass_is_video", false),
            videoMuted = prefs.getBoolean("glass_video_muted", true),
            forcePlay = prefs.getBoolean("glass_force_play", false)
        )
    }

    /** Applies instantly (live preview, no restart) and is saved on the phone. */
    fun updateGlass(transform: (GlassSettings) -> GlassSettings) {
        val g = transform(_uiState.value.glass)
        _uiState.update { it.copy(glass = g) }
        prefs.edit {
            putBoolean("glass_enabled", g.enabled)
            if (g.imagePath != null) putString("glass_image_path", g.imagePath) else remove("glass_image_path")
            putLong("glass_image_version", g.imageVersion)
            putFloat("glass_blur", g.blur)
            putFloat("glass_dim", g.dim)
            putInt("glass_fit", g.fit)
            putFloat("glass_alpha", g.alpha)
            putInt("glass_text_mode", g.textMode)
            putBoolean("glass_is_video", g.isVideo)
            putBoolean("glass_video_muted", g.videoMuted)
            putBoolean("glass_force_play", g.forcePlay)
        }
    }

    fun dismissVideoBgWarning() {
        _uiState.update { it.copy(showVideoBgWarning = false) }
    }

    private class BackgroundTooLarge : Exception()

    /** Picture OR short video from the gallery. A private copy is kept inside the app. */
    fun setBackgroundImage(context: Context, uri: Uri) {
        val app = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val isVideo = (app.contentResolver.getType(uri) ?: "").startsWith("video/")
            val imageFile = java.io.File(app.filesDir, "bg_custom.jpg")
            val videoFile = java.io.File(app.filesDir, "bg_custom_video.mp4")
            var error: Int? = null
            try {
                if (isVideo) {
                    copyBackgroundVideo(app, uri, videoFile)
                    imageFile.delete()
                } else {
                    val bitmap = decodeBackground(app, uri, 1600) ?: throw IllegalStateException("decode failed")
                    java.io.FileOutputStream(imageFile).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                    bitmap.recycle()
                    videoFile.delete()
                }
            } catch (e: BackgroundTooLarge) {
                error = R.string.glass_video_too_large
            } catch (e: Throwable) {
                e.printStackTrace()
                error = R.string.glass_pick_failed
            }
            val failure = error
            withContext(Dispatchers.Main) {
                if (failure == null) {
                    val file = if (isVideo) videoFile else imageFile
                    updateGlass {
                        it.copy(
                            enabled = true,
                            imagePath = file.absolutePath,
                            imageVersion = System.currentTimeMillis(),
                            isVideo = isVideo
                        )
                    }
                    if (isVideo) _uiState.update { it.copy(showVideoBgWarning = true) }
                } else {
                    android.widget.Toast.makeText(app, app.getString(failure), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyBackgroundVideo(app: Context, uri: Uri, dest: java.io.File) {
        val maxBytes = 40L * 1024 * 1024 // small clips only
        val declared = try {
            app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) {
            -1L
        }
        if (declared > maxBytes) throw BackgroundTooLarge()
        val tmp = java.io.File(dest.parentFile, dest.name + ".tmp")
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(tmp).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw BackgroundTooLarge()
                        out.write(buffer, 0, n)
                    }
                }
            } ?: throw IllegalStateException("cannot read video")
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IllegalStateException("cannot save video")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun decodeBackground(app: Context, uri: Uri, maxSide: Int): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = app.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        var bitmap = decoded
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > maxSide) {
            val scale = maxSide.toFloat() / longest
            bitmap = android.graphics.Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true
            )
        }
        val orientation = try {
            app.contentResolver.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL
                )
            } ?: android.media.ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            android.media.ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) {
            val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
            bitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return bitmap
    }

    fun resetGlass(context: Context) {
        try {
            java.io.File(context.applicationContext.filesDir, "bg_custom.jpg").delete()
            java.io.File(context.applicationContext.filesDir, "bg_custom_video.mp4").delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        updateGlass { GlassSettings() }
    }

    fun dismissWhatsNewDialog() {
        prefs.edit().putInt(PREF_SAVED_VERSION_CODE, CURRENT_VERSION_CODE).apply()
        _uiState.update { it.copy(showWhatsNewDialog = false) }
    }

    fun showWhatsNewDialog() {
        _uiState.update { it.copy(showWhatsNewDialog = true) }
    }
    
    internal fun checkSupportedCodecs() {
        val allCodecsEnabled = prefs.getBoolean("all_codecs_enabled", false)
        val allCodecsUnlocked = prefs.getBoolean("all_codecs_unlocked", false)
        val supported = mutableListOf<String>()

        if (allCodecsEnabled) {
            supported.addAll(getDeviceEncoders())
        } else {
            supported.add(MimeTypes.VIDEO_H264)
            if (hasEncoder(MimeTypes.VIDEO_H265)) {
                supported.add(MimeTypes.VIDEO_H265)
            }
            if (hasEncoder(MimeTypes.VIDEO_AV1)) {
                supported.add(MimeTypes.VIDEO_AV1)
            }
        }
        
        _uiState.update { 
            var newCodec = it.videoCodec
            if (!supported.contains(newCodec)) {
                newCodec = when {
                    supported.contains(MimeTypes.VIDEO_H265) -> MimeTypes.VIDEO_H265
                    supported.contains(MimeTypes.VIDEO_H264) -> MimeTypes.VIDEO_H264
                    supported.isNotEmpty() -> supported.first()
                    else -> MimeTypes.VIDEO_H264
                }
            }
            it.copy(
                supportedCodecs = supported, 
                videoCodec = newCodec, 
                useH265 = newCodec == MimeTypes.VIDEO_H265,
                allCodecsEnabled = allCodecsEnabled,
                allCodecsUnlocked = allCodecsUnlocked
            ) 
        }
    }

    internal fun getDeviceEncoders(): List<String> {
        val codecs = mutableSetOf<String>()
        try {
            for (info in cachedCodecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.startsWith("video/", ignoreCase = true)) {
                        codecs.add(type.lowercase())
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val preferred = listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1)
        return codecs.toList().sortedWith { a, b ->
            val indexA = preferred.indexOf(a)
            val indexB = preferred.indexOf(b)
            when {
                indexA != -1 && indexB != -1 -> indexA.compareTo(indexB)
                indexA != -1 -> -1
                indexB != -1 -> 1
                else -> a.compareTo(b)
            }
        }
    }

    fun isSoftwareCodec(mimeType: String): Boolean {
        try {
            var hasHardware = false
            var hasSoftware = false
            for (info in cachedCodecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                    val isSW = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        info.isSoftwareOnly
                    } else {
                        val name = info.name.lowercase()
                        name.startsWith("c2.android") || name.startsWith("omx.google")
                    }
                    if (isSW) {
                        hasSoftware = true
                    } else {
                        hasHardware = true
                    }
                }
            }
            return hasSoftware && !hasHardware
        } catch (e: Exception) {
            return false
        }
    }

    fun enableAllCodecsFeature() {
        prefs.edit {
            putBoolean("all_codecs_enabled", true)
            putBoolean("all_codecs_unlocked", true)
        }
        checkSupportedCodecs()
    }

    fun disableAllCodecsFeature() {
        prefs.edit {
            putBoolean("all_codecs_enabled", false)
            putBoolean("all_codecs_unlocked", false)
        }
        checkSupportedCodecs()
    }

    internal fun hasEncoder(mimeType: String): Boolean {
        try {
            for (info in cachedCodecInfos) {
                if (!info.isEncoder) continue

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (info.isSoftwareOnly) {
                        continue
                    }
                } else {
                    val name = info.name.lowercase()
                    if (name.startsWith("c2.android")) {
                        continue
                    }
                }

                if (info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                    return true
                }
            }
        } catch(e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private var compressionJob: Job? = null
    private var compressionStartedAtMs = 0L
    private var activeTransformer: Transformer? = null
    private var lastProbeUri: Uri? = null
    private var lastProbeResult: TrackProbe? = null

    private suspend fun probeTracksCached(context: Context, uri: Uri): TrackProbe =
        withContext(Dispatchers.IO) {
            val cached = lastProbeResult
            if (cached != null && lastProbeUri == uri) {
                cached
            } else {
                val fresh = probeTracks(context, uri)
                lastProbeUri = uri
                lastProbeResult = fresh
                fresh
            }
        }

    fun updateSelectedUri(
        context: Context,
        uri: Uri,
        keepBusy: Boolean = false,
        onLoaded: (() -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var size = 0L
            var width = 0
            var height = 0
            var bitrate = 0
            var audioBitrate = 0
            var fps = 30f
            var videoMime: String? = null
            var duration = 0L
            var originalName: String? = null

            try {
                val probe = probeTracks(context, uri).also {
                    lastProbeUri = uri
                    lastProbeResult = it
                }
                audioBitrate = probe.audioBitrate
                videoMime = probe.video?.mimeType
                val videoInfo = probe.video

                val cursor = context.contentResolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )
                if (cursor != null && cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        originalName = resolveRealDisplayName(context, uri, cursor.getString(nameIndex))
                    }
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                    cursor.close()
                }
                if (size <= 0L) {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use {
                        size = it.statSize
                    }
                }

                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, uri)

                width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0

                val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) {
                    val temp = width
                    width = height
                    height = temp
                }

                bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

                val fpsStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                fps = fpsStr?.toFloatOrNull() ?: 0f
                if (fps <= 0f && videoInfo != null && videoInfo.frameRate > 0f) {
                    fps = videoInfo.frameRate
                }
                if (fps <= 0f) {
                    fps = 30f
                }

                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val current = _uiState.value
            val videoConfig = current.defaultVideoConfig
            val audioConfig = current.defaultAudioConfig

            val defaultTargetMb = if (size > 0) (size / (1024.0 * 1024.0) * videoConfig.defaultSizeRatio).toFloat().coerceAtLeast(0.1f) else 10f

            val preferredCodec = if (current.supportedCodecs.contains(videoConfig.defaultVideoCodec)) videoConfig.defaultVideoCodec
                                 else if (current.supportedCodecs.contains(MimeTypes.VIDEO_H265)) MimeTypes.VIDEO_H265
                                 else MimeTypes.VIDEO_H264

            val isVertical = height > width
            fun getTargetHeight(targetShortSide: Int): Int {
                if (width <= 0 || height <= 0) return height
                if (isVertical) {
                    val targetWidth = minOf(targetShortSide, width)
                    return (targetWidth.toDouble() * height / width).toInt()
                } else {
                    return minOf(targetShortSide, height)
                }
            }

            val targetHeight = if (videoConfig.defaultTargetResolutionHeight > 0) getTargetHeight(videoConfig.defaultTargetResolutionHeight) else height
            val targetFpsVal = if (videoConfig.defaultTargetFps > 0 && fps >= videoConfig.defaultTargetFps) videoConfig.defaultTargetFps else 0

            _uiState.update { state ->
                state.copy(
                    selectedUri = uri,
                    originalSize = size,
                    originalWidth = width,
                    originalHeight = height,
                    originalBitrate = bitrate,
                    originalAudioBitrate = audioBitrate,
                    originalFps = fps,
                    originalVideoMime = videoMime,
                    durationMs = duration,
                    originalName = originalName,
                    targetSizeMb = defaultTargetMb,
                    targetResolutionHeight = targetHeight,
                    targetFps = targetFpsVal,
                     videoCodec = preferredCodec,
                     useH265 = preferredCodec == MimeTypes.VIDEO_H265,
                     activePreset = QualityPreset.CUSTOM,
                     audioBitrate = audioConfig.defaultAudioBitrate,
                     audioBitrateLocked = false,
                     // A resolution / fps chosen in Settings -> Video must not be changed by auto-adjust
                     targetResolutionLocked = videoConfig.defaultTargetResolutionHeight > 0,
                     targetFpsLocked = videoConfig.defaultTargetFps > 0,
                     removeAudio = audioConfig.defaultRemoveAudio,
                    audioVolume = audioConfig.defaultAudioVolume,
                    isCompressing = keepBusy,
                    progress = 0f,
                    compressedUri = null,
                    compressedSize = 0L,
                    currentOutputSize = 0L,
                    error = null,
                    errorLog = null,
                    saveSuccess = false,
                    isSaving = false,
                    hasShared = false
                ).autoAdjust(defaultTargetMb)
            }
            onLoaded?.invoke()
        }
    }
    
    fun markAsShared() {
        _uiState.update { it.copy(hasShared = true) }
    }
    
    fun applyPreset(preset: QualityPreset) {
        if (preset == QualityPreset.CUSTOM) {
             _uiState.update { it.copy(activePreset = QualityPreset.CUSTOM) }
             return
        }
        
        val current = _uiState.value
        val isVertical = current.originalHeight > current.originalWidth
        
        fun getTargetHeight(targetShortSide: Int): Int {
            if (current.originalWidth <= 0 || current.originalHeight <= 0) return current.originalHeight
            
            if (isVertical) {
                val targetWidth = minOf(targetShortSide, current.originalWidth)
                return (targetWidth.toDouble() * current.originalHeight / current.originalWidth).toInt()
            } else {
                return minOf(targetShortSide, current.originalHeight)
            }
        }

        val config = when(preset) {
            QualityPreset.HIGH -> current.highPresetConfig
            QualityPreset.MEDIUM -> current.mediumPresetConfig
            QualityPreset.LOW -> current.lowPresetConfig
            else -> null
        }

        if (config != null) {
            val targetHeight = if (config.resolutionShortSide > 0) getTargetHeight(config.resolutionShortSide) else current.originalHeight
            val targetFpsVal = if (config.targetFps > 0 && current.originalFps >= config.targetFps) config.targetFps else 0
            val targetMb = (current.originalSize / (1024.0 * 1024.0) * config.sizeRatio).toFloat().coerceAtLeast(0.1f)

            _uiState.update { 
                it.copy(
                    activePreset = preset,
                    targetResolutionHeight = targetHeight,
                    targetFps = targetFpsVal,
                    targetSizeMb = targetMb,
                    audioBitrate = config.audioBitrate,
                    audioBitrateLocked = true,
                    targetResolutionLocked = true,
                    targetFpsLocked = true,
                    removeAudio = false
                ).autoAdjust(targetMb, lockAudioBitrate = true, allowUpward = false)
            }
        }
    }

    fun updateHighPresetConfig(config: QualityPresetConfig) {
        _uiState.update { it.copy(highPresetConfig = config) }
        saveQualityPresetConfig("preset_high", config)
    }

    fun updateMediumPresetConfig(config: QualityPresetConfig) {
        _uiState.update { it.copy(mediumPresetConfig = config) }
        saveQualityPresetConfig("preset_medium", config)
    }

    fun updateLowPresetConfig(config: QualityPresetConfig) {
        _uiState.update { it.copy(lowPresetConfig = config) }
        saveQualityPresetConfig("preset_low", config)
    }

    fun resetQualityPresets() {
        val defaultConfigHigh = QualityPresetConfig(resolutionShortSide = 0, targetFps = 0, sizeRatio = 0.7f, audioBitrate = 320_000)
        val defaultConfigMedium = QualityPresetConfig(resolutionShortSide = 1080, targetFps = 30, sizeRatio = 0.4f, audioBitrate = 192_000)
        val defaultConfigLow = QualityPresetConfig(resolutionShortSide = 720, targetFps = 30, sizeRatio = 0.2f, audioBitrate = 128_000)
        _uiState.update { it.copy(
            highPresetConfig = defaultConfigHigh,
            mediumPresetConfig = defaultConfigMedium,
            lowPresetConfig = defaultConfigLow
        ) }
        prefs.edit().remove("preset_high").remove("preset_medium").remove("preset_low").apply()
    }

    fun addTargetSizePreset(label: String, sizeMb: Float) {
        val newPreset = compress.joshattic.us.model.TargetSizePreset(
            id = "custom_" + System.currentTimeMillis(),
            sizeMb = sizeMb,
            label = label,
            isCustom = true
        )
        val newList = (_uiState.value.targetSizePresets + newPreset).sortedBy { it.sizeMb }
        _uiState.update { it.copy(targetSizePresets = newList) }
        saveTargetSizePresets(newList)
    }

    fun updateTargetSizePreset(id: String, label: String, sizeMb: Float) {
        val newList = _uiState.value.targetSizePresets.map { preset ->
            if (preset.id == id) preset.copy(label = label, sizeMb = sizeMb)
            else preset
        }.sortedBy { it.sizeMb }
        _uiState.update { it.copy(targetSizePresets = newList) }
        saveTargetSizePresets(newList)
    }

    fun deleteTargetSizePreset(id: String) {
        val newList = _uiState.value.targetSizePresets.filterNot { it.id == id }.sortedBy { it.sizeMb }
        _uiState.update { it.copy(targetSizePresets = newList) }
        saveTargetSizePresets(newList)
    }

    fun resetTargetSizePresets() {
        val newList = compress.joshattic.us.model.defaultTargetSizePresets.sortedBy { it.sizeMb }
        _uiState.update { it.copy(targetSizePresets = newList) }
        prefs.edit().remove("target_size_presets").apply()
    }

    fun updateDefaultVideoConfig(config: DefaultVideoConfig) {
        _uiState.update { it.copy(defaultVideoConfig = config) }
        saveDefaultVideoConfig(config)
    }

    fun resetDefaultVideoConfig() {
        val defaultConfig = DefaultVideoConfig()
        _uiState.update { it.copy(defaultVideoConfig = defaultConfig) }
        prefs.edit().remove("default_video_config").apply()
    }

    fun updateDefaultAudioConfig(config: DefaultAudioConfig) {
        _uiState.update { it.copy(defaultAudioConfig = config) }
        saveDefaultAudioConfig(config)
    }

    fun resetDefaultAudioConfig() {
        val defaultConfig = DefaultAudioConfig()
        _uiState.update { it.copy(defaultAudioConfig = defaultConfig) }
        prefs.edit().remove("default_audio_config").apply()
    }

    internal fun normalizeSegments(segments: List<FilenameSegment>): List<FilenameSegment> {
        val result = mutableListOf<FilenameSegment>()
        var currentText = ""
        for (seg in segments) {
            when (seg) {
                is FilenameSegment.Text -> {
                    currentText += seg.value
                }
                is FilenameSegment.Token -> {
                    result.add(FilenameSegment.Text(currentText))
                    currentText = ""
                    result.add(seg)
                }
            }
        }
        result.add(FilenameSegment.Text(currentText))
        return result
    }

    fun updateFilenameSegments(segments: List<FilenameSegment>) {
        val normalized = normalizeSegments(segments)
        _uiState.update { it.copy(filenameSegments = normalized) }
        prefs.edit().putString(PREF_FILENAME_SEGMENTS, serializeSegments(normalized)).apply()
    }

    fun insertFilenameTokenAt(index: Int, tokenKey: String) {
        val current = _uiState.value.filenameSegments
        // Each token can only be added once
        if (current.any { it is FilenameSegment.Token && it.key == tokenKey }) return
        val newList = current.toMutableList()
        val clampedIndex = index.coerceIn(0, newList.size)
        newList.add(clampedIndex, FilenameSegment.Token(tokenKey))
        updateFilenameSegments(newList)
    }

    fun removeFilenameSegmentAt(index: Int) {
        val current = _uiState.value.filenameSegments.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            updateFilenameSegments(current)
        }
    }

    fun moveFilenameSegment(fromIndex: Int, toIndex: Int) {
        val current = _uiState.value.filenameSegments.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            updateFilenameSegments(current)
        }
    }

    fun resetFilenamePattern() {
        val defaultSegments = listOf(
            FilenameSegment.Token("original_name"),
            FilenameSegment.Token("resolution")
        )
        updateFilenameSegments(defaultSegments)
    }

    internal fun serializeSegments(segments: List<FilenameSegment>): String {
        return segments
            .filter { it !is FilenameSegment.Text || it.value.isNotEmpty() }
            .joinToString("|") { seg ->
                when (seg) {
                    is FilenameSegment.Text -> "text:${seg.value}"
                    is FilenameSegment.Token -> "token:${seg.key}"
                }
            }
    }

    internal fun deserializeSegments(raw: String): List<FilenameSegment> {
        if (raw.isBlank()) return normalizeSegments(emptyList())
        val parsed = raw.split("|").mapNotNull { part ->
            when {
                part.startsWith("text:") -> {
                    val value = part.removePrefix("text:")
                    if (value.isNotEmpty()) FilenameSegment.Text(value) else null
                }
                part.startsWith("token:") -> {
                    val key = part.removePrefix("token:")
                    if (key.isNotEmpty()) FilenameSegment.Token(key) else null
                }
                else -> null
            }
        }
        return normalizeSegments(parsed)
    }

    fun generatePreviewFileName(state: CompressorUiState): String {
        return compressedOutputFileName(state)
    }

    internal fun compressedOutputFileName(state: CompressorUiState): String {
        val dateStr by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
        val timeStr by lazy { SimpleDateFormat("HH-mm-ss", Locale.US).format(Date()) }
        val randomStr by lazy { String.format(Locale.US, "%06d", (0..999999).random()) }

        val resStr by lazy {
            // Always report the SHORT side (1080p, 720p, 480p ...) even for portrait videos
            val h = if (state.targetResolutionHeight > 0) state.targetResolutionHeight else state.originalHeight
            val isVerticalVideo = state.originalHeight > state.originalWidth &&
                state.originalWidth > 0 && state.originalHeight > 0
            val shortSide = if (isVerticalVideo) {
                (h.toLong() * state.originalWidth / state.originalHeight).toInt()
            } else {
                h
            }
            if (shortSide > 0) "${shortSide}p" else "1080p"
        }

        val fpsStr by lazy {
            val fps = if (state.targetFps > 0) state.targetFps else state.originalFps.toInt()
            if (fps > 0) "${fps}fps" else "30fps"
        }

        val videoBitrateStr by lazy {
            val calcBitrate = state.targetBitrate
            if (calcBitrate > 0) {
                if (state.useMbps) {
                    val mbps = calcBitrate / 1_000_000f
                    String.format(Locale.US, "Video_%.1fMbps", mbps)
                } else {
                    val kbps = calcBitrate / 1000
                    "Video_${kbps}kbps"
                }
            } else {
                "Video_5Mbps"
            }
        }

        val audioBitrateStr by lazy {
            if (state.removeAudio) {
                "NoAudio"
            } else {
                val kbps = if (state.audioBitrate > 0) state.audioBitrate / 1000 else 128
                "Audio_${kbps}kbps"
            }
        }

        val encodingStr by lazy {
            when (state.videoCodec) {
                MimeTypes.VIDEO_H265 -> "H265"
                MimeTypes.VIDEO_H264 -> "H264"
                MimeTypes.VIDEO_AV1 -> "AV1"
                MimeTypes.VIDEO_VP9 -> "VP9"
                else -> state.videoCodec.substringAfterLast("/").uppercase(Locale.US)
            }
        }

        val audioStatusStr by lazy {
            if (state.removeAudio) "NoAudio" else "WithAudio"
        }

        val presetStr by lazy {
            when (state.activePreset) {
                QualityPreset.HIGH -> "High"
                QualityPreset.MEDIUM -> "Medium"
                QualityPreset.LOW -> "Low"
                QualityPreset.CUSTOM -> "Custom"
            }
        }

        val originalNameStr by lazy {
            state.originalName?.substringBeforeLast(".")?.takeIf { it.isNotBlank() } ?: "Compressed"
        }

        val parts = mutableListOf<String>()
        for (segment in state.filenameSegments) {
            val evaluated = when (segment) {
                is FilenameSegment.Text -> segment.value.trim()
                is FilenameSegment.Token -> when (segment.key) {
                    "original_name" -> originalNameStr
                    "compressed" -> "Compressed"
                    "date" -> dateStr
                    "time" -> timeStr
                    "random" -> randomStr
                    "resolution" -> resStr
                    "framerate", "fps" -> fpsStr
                    "bitrate" -> videoBitrateStr
                    "audio_bitrate" -> audioBitrateStr
                    "encoding", "codec" -> encodingStr
                    "audio_status" -> audioStatusStr
                    "preset" -> presetStr
                    else -> segment.key
                }
            }
            if (evaluated.isNotBlank()) {
                parts.add(evaluated)
            }
        }

        val rawName = parts.joinToString("_")
        val sanitized = rawName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')

        val finalStem = if (sanitized.isBlank()) "Compressed_${System.currentTimeMillis()}" else sanitized
        return "$finalStem.mp4"
    }

    private fun saveDefaultVideoConfig(config: DefaultVideoConfig) {
        val obj = JSONObject().apply {
            put("defaultVideoCodec", config.defaultVideoCodec)
            put("defaultTargetResolutionHeight", config.defaultTargetResolutionHeight)
            put("defaultTargetFps", config.defaultTargetFps)
            put("defaultSizeRatio", config.defaultSizeRatio.toDouble())
        }
        prefs.edit().putString("default_video_config", obj.toString()).apply()
    }

    private fun loadDefaultVideoConfig(): DefaultVideoConfig {
        val str = prefs.getString("default_video_config", null) ?: return DefaultVideoConfig()
        return try {
            val obj = JSONObject(str)
            DefaultVideoConfig(
                defaultVideoCodec = obj.optString("defaultVideoCodec", MimeTypes.VIDEO_H265),
                defaultTargetResolutionHeight = obj.optInt("defaultTargetResolutionHeight", 0),
                defaultTargetFps = obj.optInt("defaultTargetFps", 0),
                defaultSizeRatio = obj.optDouble("defaultSizeRatio", 0.7).toFloat()
            )
        } catch (e: Exception) {
            DefaultVideoConfig()
        }
    }

    private fun saveDefaultAudioConfig(config: DefaultAudioConfig) {
        val obj = JSONObject().apply {
            put("defaultAudioBitrate", config.defaultAudioBitrate)
            put("defaultRemoveAudio", config.defaultRemoveAudio)
            put("defaultAudioVolume", config.defaultAudioVolume.toDouble())
        }
        prefs.edit().putString("default_audio_config", obj.toString()).apply()
    }

    private fun loadDefaultAudioConfig(): DefaultAudioConfig {
        val str = prefs.getString("default_audio_config", null) ?: return DefaultAudioConfig()
        return try {
            val obj = JSONObject(str)
            DefaultAudioConfig(
                defaultAudioBitrate = obj.optInt("defaultAudioBitrate", 128_000),
                defaultRemoveAudio = obj.optBoolean("defaultRemoveAudio", false),
                defaultAudioVolume = obj.optDouble("defaultAudioVolume", 1.0).toFloat()
            )
        } catch (e: Exception) {
            DefaultAudioConfig()
        }
    }

    private fun saveQualityPresetConfig(key: String, config: QualityPresetConfig) {
        val obj = JSONObject().apply {
            put("resolutionShortSide", config.resolutionShortSide)
            put("targetFps", config.targetFps)
            put("sizeRatio", config.sizeRatio.toDouble())
            put("audioBitrate", config.audioBitrate)
            if (config.label != null) put("label", config.label) else put("label", JSONObject.NULL)
        }
        prefs.edit().putString(key, obj.toString()).apply()
    }

    private fun loadQualityPresetConfig(key: String, default: QualityPresetConfig): QualityPresetConfig {
        val str = prefs.getString(key, null) ?: return default
        return try {
            val obj = JSONObject(str)
            QualityPresetConfig(
                resolutionShortSide = obj.getInt("resolutionShortSide"),
                targetFps = obj.getInt("targetFps"),
                sizeRatio = obj.getDouble("sizeRatio").toFloat(),
                audioBitrate = obj.getInt("audioBitrate"),
                label = when {
                    !obj.has("label") || obj.isNull("label") -> null
                    else -> obj.optString("label", "").takeIf { it.isNotBlank() }
                }
            )
        } catch (e: Exception) {
            try {
                val parts = str.split(",")
                QualityPresetConfig(
                    resolutionShortSide = parts[0].toInt(),
                    targetFps = parts[1].toInt(),
                    sizeRatio = parts[2].toFloat(),
                    audioBitrate = parts[3].toInt(),
                    label = null
                )
            } catch (ex: Exception) {
                default
            }
        }
    }

    private fun saveTargetSizePresets(list: List<compress.joshattic.us.model.TargetSizePreset>) {
        val array = JSONArray()
        for (preset in list) {
            val obj = JSONObject().apply {
                put("id", preset.id)
                put("sizeMb", preset.sizeMb.toDouble())
                put("label", preset.label)
                put("isCustom", preset.isCustom)
            }
            array.put(obj)
        }
        prefs.edit().putString("target_size_presets", array.toString()).apply()
    }

    private fun loadTargetSizePresets(): List<compress.joshattic.us.model.TargetSizePreset> {
        val str = prefs.getString("target_size_presets", null) ?: return compress.joshattic.us.model.defaultTargetSizePresets.sortedBy { it.sizeMb }
        return try {
            val parsedList = if (str.startsWith("[")) {
                val array = JSONArray(str)
                val list = mutableListOf<compress.joshattic.us.model.TargetSizePreset>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        compress.joshattic.us.model.TargetSizePreset(
                            id = obj.getString("id"),
                            sizeMb = obj.getDouble("sizeMb").toFloat(),
                            label = obj.getString("label"),
                            isCustom = obj.optBoolean("isCustom", false)
                        )
                    )
                }
                list
            } else {
                // Legacy split parser fallback
                str.split(";\n", ";").mapNotNull { itemStr ->
                    val parts = itemStr.trim().split("|")
                    if (parts.size >= 4) {
                        compress.joshattic.us.model.TargetSizePreset(
                            id = parts[0],
                            sizeMb = parts[1].toFloat(),
                            label = parts[2],
                            isCustom = parts[3].trim().toBoolean()
                        )
                    } else null
                }
            }

            if (parsedList.isEmpty()) {
                compress.joshattic.us.model.defaultTargetSizePresets.sortedBy { it.sizeMb }
            } else {
                var needsSave = false
                val migrated = parsedList.map { preset ->
                    if (preset.id == "discord" && !preset.isCustom && preset.sizeMb == 10f) {
                        needsSave = true
                        preset.copy(sizeMb = 20f, label = "Discord")
                    } else {
                        preset
                    }
                }.toMutableList()

                if (migrated.none { it.id == "github" }) {
                    migrated.add(compress.joshattic.us.model.TargetSizePreset("github", 10f, "GitHub", isCustom = false))
                    needsSave = true
                }

                // Fix duplicate GitHub: discord was previously not renamable, so any
                // stored custom label for discord that equals "GitHub" is likely an
                // accidental rename that was previously invisible and now shows as duplicate.
                // Reset such cases to default.
                for (i in migrated.indices) {
                    val p = migrated[i]
                    if (p.id == "discord" && !p.isCustom) {
                        val labelLower = p.label.trim().lowercase()
                        if (labelLower == "github" || (labelLower.contains("github") && labelLower.contains("discord"))) {
                            migrated[i] = p.copy(label = "Discord")
                            needsSave = true
                        }
                    }
                }

                // Deduplicate by id (keep first occurrence)
                val seenIds = mutableSetOf<String>()
                val dedupedById = mutableListOf<compress.joshattic.us.model.TargetSizePreset>()
                for (preset in migrated) {
                    if (seenIds.add(preset.id)) {
                        dedupedById.add(preset)
                    } else {
                        needsSave = true
                    }
                }

                // Remove custom presets that duplicate a default preset's size+label
                // e.g., user previously added a custom 10MB "GitHub" before the default existed
                val defaultKeys = compress.joshattic.us.model.defaultTargetSizePresets
                    .associateBy { it.sizeMb to it.label.lowercase() }
                val nonCustomKeys = dedupedById.filter { !it.isCustom }
                    .associateBy { it.sizeMb to it.label.lowercase() }
                val finalList = mutableListOf<compress.joshattic.us.model.TargetSizePreset>()
                for (preset in dedupedById) {
                    if (preset.isCustom) {
                        val key = preset.sizeMb to preset.label.trim().lowercase()
                        if (key in nonCustomKeys || key in defaultKeys) {
                            needsSave = true
                            continue
                        }
                    }
                    finalList.add(preset)
                }

                val result = finalList.sortedBy { it.sizeMb }
                if (needsSave || result.size != migrated.size) {
                    saveTargetSizePresets(result)
                }
                result
            }
        } catch (e: Exception) {
            compress.joshattic.us.model.defaultTargetSizePresets.sortedBy { it.sizeMb }
        }
    }

    fun setTargetSizePreview(mb: Float) {
        _uiState.update { it.copy(targetSizeMb = mb, activePreset = QualityPreset.CUSTOM) }
    }

    fun setTargetSize(mb: Float) {
        _uiState.update { it.copy(targetSizeMb = mb, activePreset = QualityPreset.CUSTOM).autoAdjust(mb) }
    }

    fun setVideoCodec(codec: String) {
        _uiState.update { 
            it.copy(
                videoCodec = codec, 
                useH265 = codec == MimeTypes.VIDEO_H265, 
                activePreset = QualityPreset.CUSTOM
            ).withMinimumTarget()
        }
    }

    fun toggleShowBitrate() {
        _uiState.update { 
            val newValue = !it.showBitrate
            prefs.edit { putBoolean("show_bitrate", newValue) }
            it.copy(showBitrate = newValue)
        }
    }

    fun toggleBitrateUnit() {
        _uiState.update { 
            val newValue = !it.useMbps
            prefs.edit { putBoolean("use_mbps", newValue) }
            it.copy(useMbps = newValue)
        }
    }

    fun toggleShowStorageSaved() {
        _uiState.update { 
            val newValue = !it.showStorageSaved
            prefs.edit { putBoolean("show_storage_saved", newValue) }
            it.copy(showStorageSaved = newValue)
        }
    }

    fun toggleShowTargetSizePreset() {
        _uiState.update { 
            val newValue = !it.showTargetSizePreset
            prefs.edit { putBoolean("show_target_size_preset", newValue) }
            it.copy(showTargetSizePreset = newValue)
        }
    }

    fun toggleAutoSaveToPhotos() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        _uiState.update { 
            val newValue = !it.autoSaveToPhotos
            prefs.edit { putBoolean("auto_save_photos", newValue) }
            it.copy(autoSaveToPhotos = newValue)
        }
    }

    fun setCustomOutputFolder(context: Context, treeUri: Uri) {
        try {
            releasePersistedTreeUri(context, _uiState.value.customOutputTreeUri)
            context.contentResolver.takePersistableUriPermission(treeUri, PERSIST_URI_FLAGS)
            val folderName = resolveFolderDisplayName(context, treeUri)
            prefs.edit {
                putString(PREF_CUSTOM_OUTPUT_TREE_URI, treeUri.toString())
                putString(PREF_CUSTOM_OUTPUT_FOLDER_NAME, folderName)
            }
            _uiState.update {
                it.copy(
                    customOutputTreeUri = treeUri.toString(),
                    customOutputFolderName = folderName
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearCustomOutputFolder(context: Context) {
        releasePersistedTreeUri(context, _uiState.value.customOutputTreeUri)
        prefs.edit {
            remove(PREF_CUSTOM_OUTPUT_TREE_URI)
            remove(PREF_CUSTOM_OUTPUT_FOLDER_NAME)
        }
        _uiState.update {
            it.copy(customOutputTreeUri = null, customOutputFolderName = null)
        }
    }

    private fun releasePersistedTreeUri(context: Context, uriString: String?) {
        if (uriString.isNullOrBlank()) return
        try {
            val uri = Uri.parse(uriString)
            val stillHeld = context.contentResolver.persistedUriPermissions.any { it.uri == uri }
            if (stillHeld) {
                context.contentResolver.releasePersistableUriPermission(uri, PERSIST_URI_FLAGS)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resolveFolderDisplayName(context: Context, treeUri: Uri): String {
        DocumentFile.fromTreeUri(context, treeUri)?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val path = docId.substringAfter(':', missingDelimiterValue = docId)
            path.substringAfterLast('/').ifBlank { path }.ifBlank {
                getApplication<Application>().getString(R.string.output_location_default)
            }
        } catch (_: Exception) {
            getApplication<Application>().getString(R.string.output_location_default)
        }
    }

    /** Saves using the configured location: custom SAF folder, or default Photos gallery. */
    fun saveCompressedOutput(context: Context) {
        val treeUri = _uiState.value.customOutputTreeUri
        if (!treeUri.isNullOrBlank()) {
            saveToCustomTree(context, Uri.parse(treeUri))
        } else {
            saveToGallery(context)
        }
    }

    fun toggleRemoveAudio() {
        _uiState.update { 
            val temp = it.copy(removeAudio = !it.removeAudio, activePreset = QualityPreset.CUSTOM)
            if (temp.removeAudio) {
                 temp
            } else {
                 temp.autoAdjust(temp.targetSizeMb)    
            }
        }
    }

    fun setAudioBitrate(bitrate: Int) {
        _uiState.update {
            val temp = it.copy(
                audioBitrate = bitrate,
                audioBitrateLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
            temp.autoAdjust(temp.targetSizeMb, lockAudioBitrate = true)
        }
    }

    fun setAudioVolume(volume: Float) {
        _uiState.update { it.copy(audioVolume = volume, activePreset = QualityPreset.CUSTOM) }
    }

    fun setResolution(height: Int) {
        _uiState.update {
            val isVertical = it.originalHeight > it.originalWidth
            val mappedHeight = if (
                isVertical &&
                it.originalWidth > 0 &&
                it.originalHeight > 0 &&
                height > 0
            ) {
                (height.toLong() * it.originalHeight / it.originalWidth).toInt()
            } else {
                height
            }
            it.copy(
                targetResolutionHeight = mappedHeight,
                targetResolutionLocked = true,
                activePreset = QualityPreset.CUSTOM
            ).withMinimumTarget()
        }
    }

    fun setFps(fps: Int) {
        _uiState.update {
            it.copy(targetFps = fps, targetFpsLocked = true, activePreset = QualityPreset.CUSTOM)
                .withMinimumTarget()
        }
    }

    fun acceptSuggestedResolution() {
        _uiState.update { state ->
            val suggestion = state.suggestedForTarget()
            state.copy(
                targetResolutionHeight = suggestion.targetResolutionHeight,
                targetResolutionLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
        }
    }

    fun acceptSuggestedFps() {
        _uiState.update { state ->
            val suggestion = state.suggestedForTarget()
            state.copy(
                targetFps = suggestion.targetFps,
                targetFpsLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
        }
    }

    fun acceptSuggestedAudioBitrate() {
        _uiState.update { state ->
            val suggestion = state.suggestedForTarget()
            state.copy(
                audioBitrate = suggestion.audioBitrate,
                audioBitrateLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
        }
    }

    fun acceptAllSuggestions() {
        _uiState.update { state ->
            val suggestion = state.suggestedForTarget()
            state.copy(
                audioBitrate = suggestion.audioBitrate,
                targetResolutionHeight = suggestion.targetResolutionHeight,
                targetFps = suggestion.targetFps,
                audioBitrateLocked = true,
                targetResolutionLocked = true,
                targetFpsLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
        }
    }
    
    fun cancelCompression() {
        activeTransformer?.cancel()
        compressionJob?.cancel()
        val app = getApplication<Application>()
        CompressionService.stop(app)
        CompressionNotifier.clearProgress(app)
        clearSavedJob()
        _uiState.update {
            it.copy(
                isCompressing = false,
                progress = 0f,
                queuePending = emptyList(),
                queueTotal = 1,
                queueDone = 0,
                batchTemplate = null
            )
        }
    }
    
    private fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val outputDir = File(context.cacheDir, "compressed_videos")
                if (outputDir.exists()) {
                    outputDir.listFiles()?.forEach {
                        try { it.delete() } catch(e: Exception) {}
                    }
                }
            } catch(e: Exception) {
                 e.printStackTrace()
            }
        }
    }

    fun reset() {
        val current = _uiState.value
        val savedBytes = current.totalSavedBytes
        val supportedCodecs = current.supportedCodecs
        val showBitrate = current.showBitrate
        val useMbps = current.useMbps
        
        clearCache()
        clearSavedJob()
        CompressionService.stop(getApplication<Application>())

        val defaultCodec = if (supportedCodecs.contains(MimeTypes.VIDEO_H265)) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        val useH265 = defaultCodec == MimeTypes.VIDEO_H265
        
        _uiState.update {
            CompressorUiState(
                totalSavedBytes = savedBytes,
                supportedCodecs = supportedCodecs,
                showBitrate = showBitrate,
                useMbps = useMbps,
                showStorageSaved = current.showStorageSaved,
                showTargetSizePreset = current.showTargetSizePreset,
                autoSaveToPhotos = current.autoSaveToPhotos,
                customOutputTreeUri = current.customOutputTreeUri,
                customOutputFolderName = current.customOutputFolderName,
                allCodecsEnabled = current.allCodecsEnabled,
                allCodecsUnlocked = current.allCodecsUnlocked,
                highPresetConfig = current.highPresetConfig,
                mediumPresetConfig = current.mediumPresetConfig,
                lowPresetConfig = current.lowPresetConfig,
                targetSizePresets = current.targetSizePresets,
                defaultVideoConfig = current.defaultVideoConfig,
                defaultAudioConfig = current.defaultAudioConfig,
                videoCodec = defaultCodec,
                useH265 = useH265,
                filenameSegments = current.filenameSegments,
                history = current.history,
                customResolutions = current.customResolutions,
                customFpsValues = current.customFpsValues,
                savedCombos = current.savedCombos,
                themeSeedColor = current.themeSeedColor,
                glass = current.glass
            )
        }
    }

    // ---------------------------------------------------------------------
    // Queue / resume / history
    // ---------------------------------------------------------------------

    /** Called when the user picked one or more videos. The first is configured, the rest wait. */
    fun startSelection(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.forEach {
            try {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Not every picker allows persistable access; resume then needs a re-pick.
            }
        }
        _uiState.update {
            it.copy(
                queuePending = uris.drop(1),
                queueTotal = uris.size,
                queueDone = 0,
                batchTemplate = null
            )
        }
        updateSelectedUri(context, uris.first())
    }

    private fun launchBatchItem(context: Context, uri: Uri) {
        updateSelectedUri(context, uri, keepBusy = true) {
            val template = _uiState.value.batchTemplate
            if (template != null) {
                _uiState.update { it.withTemplate(template) }
            }
            startCompression(context)
        }
    }

    private fun snapshotTemplate(s: CompressorUiState): BatchTemplate {
        val isVertical = s.originalHeight > s.originalWidth && s.originalWidth > 0
        val shortSide = when {
            s.targetResolutionHeight <= 0 -> 0
            isVertical -> (s.targetResolutionHeight.toLong() * s.originalWidth / s.originalHeight).toInt()
            else -> s.targetResolutionHeight
        }
        val effectiveMb = maxOf(s.targetSizeMb, s.minimumSizeMb)
        val ratio = if (s.originalSize > 0) {
            (effectiveMb / (s.originalSize / (1024f * 1024f))).coerceIn(0.01f, 1f)
        } else {
            0.5f
        }
        return BatchTemplate(
            videoCodec = s.videoCodec,
            resolutionShortSide = shortSide,
            fps = s.targetFps,
            audioBitrate = s.audioBitrate,
            removeAudio = s.removeAudio,
            audioVolume = s.audioVolume,
            sizeRatio = ratio
        )
    }

    private fun persistSavedJob(current: Uri, pending: List<Uri>, total: Int, done: Int, t: BatchTemplate) {
        try {
            val o = JSONObject()
            o.put("current", current.toString())
            o.put("pending", JSONArray(pending.map { it.toString() }))
            o.put("total", total)
            o.put("done", done)
            o.put("codec", t.videoCodec)
            o.put("res", t.resolutionShortSide)
            o.put("fps", t.fps)
            o.put("abr", t.audioBitrate)
            o.put("noAudio", t.removeAudio)
            o.put("vol", t.audioVolume.toDouble())
            o.put("ratio", t.sizeRatio.toDouble())
            prefs.edit { putString(PREF_SAVED_JOB, o.toString()) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun clearSavedJob() {
        prefs.edit { remove(PREF_SAVED_JOB) }
    }

    private fun loadSavedJob(): SavedJob? {
        val raw = prefs.getString(PREF_SAVED_JOB, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val pendingArr = o.getJSONArray("pending")
            val pending = (0 until pendingArr.length()).map { Uri.parse(pendingArr.getString(it)) }
            SavedJob(
                current = Uri.parse(o.getString("current")),
                pending = pending,
                total = o.getInt("total"),
                done = o.getInt("done"),
                template = BatchTemplate(
                    videoCodec = o.getString("codec"),
                    resolutionShortSide = o.getInt("res"),
                    fps = o.getInt("fps"),
                    audioBitrate = o.getInt("abr"),
                    removeAudio = o.getBoolean("noAudio"),
                    audioVolume = o.getDouble("vol").toFloat(),
                    sizeRatio = o.getDouble("ratio").toFloat()
                )
            )
        } catch (e: Exception) {
            prefs.edit { remove(PREF_SAVED_JOB) }
            null
        }
    }

    /** User chose "Resume" for an unfinished job. That video restarts from its beginning. */
    fun resumeSavedJob(activityContext: Context) {
        val context = activityContext.applicationContext
        if (_uiState.value.isCompressing) return
        val job = _uiState.value.resumeJob ?: return
        _uiState.update { it.copy(resumeJob = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val accessible = try {
                context.contentResolver.openInputStream(job.current)?.use { true } ?: false
            } catch (e: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                if (!accessible) {
                    clearSavedJob()
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.resume_unavailable),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }
                _uiState.update {
                    it.copy(
                        queuePending = job.pending,
                        queueTotal = job.total,
                        queueDone = job.done,
                        batchTemplate = job.template
                    )
                }
                launchBatchItem(context, job.current)
            }
        }
    }

    fun discardSavedJob() {
        clearSavedJob()
        _uiState.update { it.copy(resumeJob = null) }
    }

    private fun loadHistory(): List<HistoryEntry> {
        val raw = prefs.getString(PREF_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                HistoryEntry(
                    timestamp = o.getLong("t"),
                    name = o.getString("n"),
                    originalBytes = o.getLong("o"),
                    compressedBytes = o.getLong("c"),
                    resolution = o.getString("r"),
                    fps = o.getInt("f"),
                    uri = o.optString("u", "").ifEmpty { null },
                    durationMs = o.optLong("d", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun resolutionLabel(s: CompressorUiState): String {
        val h = if (s.targetResolutionHeight > 0) s.targetResolutionHeight else s.originalHeight
        val vertical = s.originalHeight > s.originalWidth && s.originalWidth > 0
        val short = if (vertical) (h.toLong() * s.originalWidth / s.originalHeight).toInt() else h
        return if (short > 0) "${short}p" else "-"
    }

    private fun addHistoryEntry(s: CompressorUiState, name: String, originalBytes: Long, compressedBytes: Long, durationMs: Long) {
        val entry = HistoryEntry(
            timestamp = System.currentTimeMillis(),
            name = name,
            originalBytes = originalBytes,
            compressedBytes = compressedBytes,
            resolution = resolutionLabel(s),
            fps = if (s.targetFps > 0) s.targetFps else s.originalFps.toInt(),
            durationMs = durationMs
        )
        val updated = (listOf(entry) + _uiState.value.history).take(MAX_HISTORY)
        _uiState.update { it.copy(history = updated) }
        saveHistory(updated)
    }

    private fun saveHistory(list: List<HistoryEntry>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject()
                        .put("t", it.timestamp)
                        .put("n", it.name)
                        .put("o", it.originalBytes)
                        .put("c", it.compressedBytes)
                        .put("r", it.resolution)
                        .put("f", it.fps)
                        .put("u", it.uri ?: "")
                        .put("d", it.durationMs)
                )
            }
            prefs.edit { putString(PREF_HISTORY, arr.toString()) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Picks the app's colours from a gallery image. */
    fun setThemeFromImage(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val seed = compress.joshattic.us.ui.theme.extractSeedColor(context, uri)
            if (seed != null) {
                prefs.edit { putInt(PREF_THEME_SEED, seed) }
                _uiState.update { it.copy(themeSeedColor = seed) }
            }
        }
    }

    fun resetTheme() {
        prefs.edit { remove(PREF_THEME_SEED) }
        _uiState.update { it.copy(themeSeedColor = null) }
    }

    /** Links the newest not-yet-linked history entry to the file that was just saved. */
    private fun attachSavedOutput(name: String, uri: Uri) {
        val list = _uiState.value.history
        val index = list.indexOfFirst { it.uri == null }
        if (index < 0) return
        val updated = list.toMutableList()
        updated[index] = updated[index].copy(name = name, uri = uri.toString())
        _uiState.update { it.copy(history = updated) }
        saveHistory(updated)
    }

    /**
     * Gallery-picker URIs can report a numeric id instead of the real file name.
     * In that case look the real name up in MediaStore.
     */
    private fun resolveRealDisplayName(context: Context, uri: Uri, queried: String?): String? {
        val stem = queried?.substringBeforeLast(".")
        val looksSynthetic = stem.isNullOrBlank() || stem.all { it.isDigit() }
        if (!looksSynthetic) return queried
        try {
            val id = uri.lastPathSegment?.substringAfterLast(':')?.toLongOrNull()
            if (id != null) {
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.TITLE),
                    "${MediaStore.Video.Media._ID}=?",
                    arrayOf(id.toString()),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val display = c.getString(0)
                        val title = c.getString(1)
                        val displayStem = display?.substringBeforeLast(".")
                        if (!displayStem.isNullOrBlank() && !displayStem.all { it.isDigit() }) {
                            return display
                        }
                        if (!title.isNullOrBlank() && !title.all { it.isDigit() }) {
                            return "$title.mp4"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return queried
    }

    // ---- custom resolution / fps buttons and saved combos ----

    private fun loadIntList(key: String): List<Int> =
        (prefs.getString(key, "") ?: "").split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
            .distinct()

    private fun saveIntList(key: String, list: List<Int>) {
        prefs.edit { putString(key, list.joinToString(",")) }
    }

    fun addCustomResolution(value: Int) {
        val current = _uiState.value.customResolutions
        if (value <= 0 || value in current) return
        val updated = (current + value).sortedDescending()
        _uiState.update { it.copy(customResolutions = updated) }
        saveIntList(PREF_CUSTOM_RES, updated)
    }

    fun removeCustomResolution(value: Int) {
        val updated = _uiState.value.customResolutions.filter { it != value }
        _uiState.update { it.copy(customResolutions = updated) }
        saveIntList(PREF_CUSTOM_RES, updated)
    }

    fun addCustomFps(value: Int) {
        val current = _uiState.value.customFpsValues
        if (value <= 0 || value in current) return
        val updated = (current + value).sortedDescending()
        _uiState.update { it.copy(customFpsValues = updated) }
        saveIntList(PREF_CUSTOM_FPS, updated)
    }

    fun removeCustomFps(value: Int) {
        val updated = _uiState.value.customFpsValues.filter { it != value }
        _uiState.update { it.copy(customFpsValues = updated) }
        saveIntList(PREF_CUSTOM_FPS, updated)
    }

    private fun loadCombos(): List<SavedCombo> {
        val raw = prefs.getString(PREF_COMBOS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                SavedCombo(o.getString("c"), o.getInt("r"), o.getInt("f"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persistCombos(list: List<SavedCombo>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("c", it.videoCodec).put("r", it.resolutionShortSide).put("f", it.fps))
            }
            prefs.edit { putString(PREF_COMBOS, arr.toString()) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Saves the current codec + resolution + framerate as a one-tap combo. Returns false if it already exists. */
    fun saveCurrentCombo(): Boolean {
        val s = _uiState.value
        val vertical = s.originalHeight > s.originalWidth && s.originalWidth > 0 && s.originalHeight > 0
        val shortSide = when {
            s.targetResolutionHeight <= 0 || s.targetResolutionHeight == s.originalHeight -> 0
            vertical -> (s.targetResolutionHeight.toLong() * s.originalWidth / s.originalHeight).toInt()
            else -> s.targetResolutionHeight
        }
        val combo = SavedCombo(s.videoCodec, shortSide, s.targetFps)
        if (combo in s.savedCombos) return false
        val updated = s.savedCombos + combo
        _uiState.update { it.copy(savedCombos = updated) }
        persistCombos(updated)
        return true
    }

    fun deleteCombo(combo: SavedCombo) {
        val updated = _uiState.value.savedCombos.filter { it != combo }
        _uiState.update { it.copy(savedCombos = updated) }
        persistCombos(updated)
    }

    fun applyCombo(c: SavedCombo) {
        _uiState.update { s ->
            val vertical = s.originalHeight > s.originalWidth && s.originalWidth > 0 && s.originalHeight > 0
            val originalShort = if (vertical) s.originalWidth else s.originalHeight
            val height = if (c.resolutionShortSide > 0 && originalShort > 0 && c.resolutionShortSide < originalShort) {
                if (vertical) {
                    (c.resolutionShortSide.toLong() * s.originalHeight / s.originalWidth).toInt()
                } else {
                    c.resolutionShortSide
                }
            } else {
                0
            }
            val fpsValue = if (c.fps > 0 && s.originalFps >= c.fps) c.fps else 0
            val codec = if (s.supportedCodecs.contains(c.videoCodec)) c.videoCodec else s.videoCodec
            s.copy(
                videoCodec = codec,
                useH265 = codec == MimeTypes.VIDEO_H265,
                targetResolutionHeight = height,
                targetResolutionLocked = true,
                targetFps = fpsValue,
                targetFpsLocked = true,
                activePreset = QualityPreset.CUSTOM
            ).withMinimumTarget()
        }
    }

    // ---------------------------------------------------------------------
    // Import / Export (all settings, presets, history)
    // ---------------------------------------------------------------------

    private val transferSkipKeys = setOf(
        PREF_SAVED_JOB,
        PREF_SAVED_VERSION_CODE,
        PREF_CUSTOM_OUTPUT_TREE_URI,   // folder permission belongs to this install only
        PREF_CUSTOM_OUTPUT_FOLDER_NAME,
        "glass_image_path",           // the picture file belongs to this install only
        "glass_image_version",
        "glass_is_video"
    )

    fun exportData(context: Context, uri: Uri) {
        val app = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                val data = JSONObject()
                prefs.all.forEach { (key, value) ->
                    if (key in transferSkipKeys || value == null) return@forEach
                    val item = JSONObject()
                    when (value) {
                        is Boolean -> item.put("t", "b").put("v", value)
                        is Int -> item.put("t", "i").put("v", value)
                        is Long -> item.put("t", "l").put("v", value)
                        is Float -> item.put("t", "f").put("v", value.toDouble())
                        is String -> item.put("t", "s").put("v", value)
                        is Set<*> -> item.put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                        else -> return@forEach
                    }
                    data.put(key, item)
                }
                val root = JSONObject()
                    .put("app", "compressor-custom-backup")
                    .put("format", 1)
                    .put("exportedAt", System.currentTimeMillis())
                    .put("data", data)
                app.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(root.toString(2).toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("no output stream")
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    app,
                    app.getString(if (ok) R.string.transfer_export_ok else R.string.transfer_export_failed),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun importData(context: Context, uri: Uri) {
        val app = context.applicationContext
        if (_uiState.value.isCompressing) {
            android.widget.Toast.makeText(app, app.getString(R.string.transfer_busy), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                val text = app.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalStateException("cannot read file")
                val root = JSONObject(text)
                if (root.optString("app") != "compressor-custom-backup") throw IllegalArgumentException("wrong file")
                val data = root.getJSONObject("data")
                // Parse everything first, so a bad file never destroys current data.
                class Item(val key: String, val type: String, val json: JSONObject)
                val items = data.keys().asSequence()
                    .filter { it !in transferSkipKeys }
                    .map { Item(it, data.getJSONObject(it).getString("t"), data.getJSONObject(it)) }
                    .toList()
                prefs.edit(commit = true) {
                    prefs.all.keys.filter { it !in transferSkipKeys }.forEach { remove(it) }
                    items.forEach { item ->
                        when (item.type) {
                            "b" -> putBoolean(item.key, item.json.getBoolean("v"))
                            "i" -> putInt(item.key, item.json.getInt("v"))
                            "l" -> putLong(item.key, item.json.getLong("v"))
                            "f" -> putFloat(item.key, item.json.getDouble("v").toFloat())
                            "s" -> putString(item.key, item.json.getString("v"))
                            "ss" -> {
                                val arr = item.json.getJSONArray("v")
                                putStringSet(item.key, (0 until arr.length()).map { arr.getString(it) }.toSet())
                            }
                        }
                    }
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
            withContext(Dispatchers.Main) {
                if (ok) {
                    loadSettingsFromPrefs(initial = false)
                    checkSupportedCodecs()
                }
                android.widget.Toast.makeText(
                    app,
                    app.getString(if (ok) R.string.transfer_import_ok else R.string.transfer_import_failed),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun clearHistory() {
        _uiState.update { it.copy(history = emptyList()) }
        prefs.edit { remove(PREF_HISTORY) }
    }

    /** Copies a finished file to the chosen output location without touching the UI state. */
    private suspend fun copyToOutputLocation(context: Context, file: File, name: String): Uri? {
        val treeUriStr = _uiState.value.customOutputTreeUri
        return withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) return@withContext null
                if (!treeUriStr.isNullOrBlank()) {
                    val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr))
                    if (tree == null || !tree.canWrite()) return@withContext null
                    tree.findFile(name)?.takeIf { it.isFile }?.delete()
                    val target = tree.createFile("video/mp4", name) ?: return@withContext null
                    val out = context.contentResolver.openOutputStream(target.uri) ?: return@withContext null
                    out.use { o -> file.inputStream().use { it.copyTo(o, COPY_BUFFER_BYTES) } }
                    target.uri
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Compressor")
                    }
                    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val itemUri = context.contentResolver.insert(collection, values) ?: return@withContext null
                    val out = context.contentResolver.openOutputStream(itemUri) ?: return@withContext null
                    out.use { o -> file.inputStream().use { it.copyTo(o, COPY_BUFFER_BYTES) } }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    context.contentResolver.update(itemUri, values, null, null)
                    itemUri
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun failQueue(message: String) {
        val app = getApplication<Application>()
        CompressionService.stop(app)
        CompressionNotifier.clearProgress(app)
        clearSavedJob()
        _uiState.update {
            it.copy(isCompressing = false, error = message, errorLog = null, queuePending = emptyList())
        }
    }

    fun startCompression(
        context: Context,
        forcedOutputMimeType: String? = null,
        additionalWarnings: List<String> = emptyList(),
        allowHevcMuxerFallback: Boolean = true
    ): Job = viewModelScope.launch(Dispatchers.Main) {
        val context = context.applicationContext
        val currentState = _uiState.value
        val inputUri = currentState.selectedUri ?: return@launch

        val probe = probeTracksCached(context, inputUri)
        val plan = withContext(Dispatchers.IO) { buildCompressionPlan(probe, currentState) }
        if (plan.blockingError != null) {
            _uiState.update { it.copy(error = plan.blockingError, errorLog = null, isCompressing = false) }
            return@launch
        }

        val batchTemplate = currentState.batchTemplate ?: snapshotTemplate(currentState)
        persistSavedJob(
            inputUri,
            currentState.queuePending,
            currentState.queueTotal,
            currentState.queueDone,
            batchTemplate
        )
        CompressionService.start(context)
        CompressionNotifier.updateProgress(
            context, 0, currentState.originalName, currentState.queueDone + 1, currentState.queueTotal
        )

        _uiState.update {
            it.copy(
                batchTemplate = batchTemplate,
                isCompressing = true,
                progress = 0f,
                currentOutputSize = 0L,
                error = null,
                errorLog = null,
                compressedUri = null,
                saveSuccess = false,
                isSaving = false,
                warnings = plan.warnings + additionalWarnings
            )
        }

        val outputDir = File(context.cacheDir, "compressed_videos")
        outputDir.mkdirs()
        val outputFile = File(outputDir, compressedOutputFileName(currentState))
        if (outputFile.exists()) {
            outputFile.delete()
        }
        val outputPath = outputFile.absolutePath

        val targetBitrate = currentState.targetBitrate.toLong()

        val sourceAudioBitrate = probe.audioBitrate
        val audioBitrateToUse = if (currentState.audioBitrate == 0) {
            if (sourceAudioBitrate > 0) sourceAudioBitrate
            else if (currentState.originalAudioBitrate > 0) currentState.originalAudioBitrate else 128_000
        } else {
            currentState.audioBitrate
        }

        val videoMimeType = forcedOutputMimeType ?: plan.outputVideoMimeType

        val audioPassthrough = probe.hasAudio &&
            probe.audioMime == MimeTypes.AUDIO_AAC &&
            (probe.aacProfile == -1 ||
                probe.aacProfile == android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC) &&
            currentState.audioVolume == 1f &&
            !currentState.removeAudio &&
            sourceAudioBitrate > 0 &&
            (currentState.audioBitrate == 0 || currentState.audioBitrate == sourceAudioBitrate)

        val shouldIncludeAudio = !currentState.removeAudio && probe.hasAudio

        val decoderFactory = DefaultDecoderFactory.Builder(context)
            .setEnableDecoderFallback(true)
            .build()

        val cbrEncoderFactory = DefaultEncoderFactory.Builder(context)
            .setEnableFallback(true)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(targetBitrate.toInt())
                    .setBitrateMode(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    .build()
            )
            .setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder()
                    .setBitrate(audioBitrateToUse)
                    .build()
            )
            .build()

        val vbrEncoderFactory = DefaultEncoderFactory.Builder(context)
            .setEnableFallback(true)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(targetBitrate.toInt())
                    .setBitrateMode(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    .build()
            )
            .setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder()
                    .setBitrate(audioBitrateToUse)
                    .build()
            )
            .build()

        val isMediaTek = isMediaTekDeviceOrEncoder(videoMimeType)
        val primaryEncoderFactory = if (isMediaTek) vbrEncoderFactory else cbrEncoderFactory
        val fallbackEncoderFactory = if (isMediaTek) cbrEncoderFactory else vbrEncoderFactory
            
        val encoderFactory = object : androidx.media3.transformer.Codec.EncoderFactory {
            @Throws(androidx.media3.transformer.ExportException::class)
            override fun createForAudioEncoding(format: androidx.media3.common.Format, logSessionId: android.media.metrics.LogSessionId?): androidx.media3.transformer.Codec {
                return primaryEncoderFactory.createForAudioEncoding(format, logSessionId)
            }

            @Throws(androidx.media3.transformer.ExportException::class)
            override fun createForVideoEncoding(format: androidx.media3.common.Format, logSessionId: android.media.metrics.LogSessionId?): androidx.media3.transformer.Codec {
                val targetFps = if (plan.outputFps > 0) plan.outputFps.toFloat() else currentState.originalFps
                var modifiedFormatBuilder = format.buildUpon()
                if (targetFps > 0f) {
                    modifiedFormatBuilder.setFrameRate(targetFps)
                }
                if (format.colorInfo == null || !androidx.media3.common.ColorInfo.isTransferHdr(format.colorInfo)) {
                     modifiedFormatBuilder.setColorInfo(null)
                }
                val modifiedFormat = modifiedFormatBuilder.build()

                return try {
                    primaryEncoderFactory.createForVideoEncoding(modifiedFormat, logSessionId)
                } catch (e: androidx.media3.transformer.ExportException) {
                    fallbackEncoderFactory.createForVideoEncoding(modifiedFormat, logSessionId)
                }
            }

            override fun audioNeedsEncoding(): Boolean =
                !audioPassthrough && primaryEncoderFactory.audioNeedsEncoding()
            override fun videoNeedsEncoding(): Boolean = primaryEncoderFactory.videoNeedsEncoding()
        }

        val transformerBuilder = Transformer.Builder(context)
            .setVideoMimeType(videoMimeType)
            .setMaxDelayBetweenMuxerSamplesMs(30_000)
            .apply {
                if (!audioPassthrough) {
                    setAudioMimeType(MimeTypes.AUDIO_AAC)
                }
            }
            .setAssetLoaderFactory(androidx.media3.transformer.DefaultAssetLoaderFactory(context, decoderFactory, androidx.media3.common.util.Clock.DEFAULT, null))
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                     val finalSize = outputFile.length()
                     val savedBytes = currentState.originalSize - finalSize
                     var newTotal = _uiState.value.totalSavedBytes
                     
                     if (savedBytes > 0) {
                         newTotal += savedBytes
                         prefs.edit { putLong("total_saved_bytes", newTotal) }
                     }

                     val stateNow = _uiState.value
                     val tookMs = android.os.SystemClock.elapsedRealtime() - compressionStartedAtMs
                     addHistoryEntry(stateNow, outputFile.name, currentState.originalSize, finalSize, tookMs)
                     val pending = stateNow.queuePending

                     if (pending.isNotEmpty()) {
                         // More videos are waiting: save this one, then continue with the next.
                         compressionJob?.cancel()
                         _uiState.update {
                             it.copy(progress = 1f, totalSavedBytes = newTotal, compressedSize = finalSize)
                         }
                         val outputName = compressedOutputFileName(stateNow)
                         viewModelScope.launch(Dispatchers.Main) {
                             val savedUri = copyToOutputLocation(context, outputFile, outputName)
                             try { outputFile.delete() } catch (_: Exception) {}
                             if (savedUri != null) {
                                 attachSavedOutput(outputName, savedUri)
                             }
                             if (savedUri == null) {
                                 failQueue(
                                     getApplication<Application>().getString(
                                         R.string.error_save_failed, outputName
                                     )
                                 )
                                 return@launch
                             }
                             val next = pending.first()
                             _uiState.update {
                                 it.copy(queuePending = pending.drop(1), queueDone = it.queueDone + 1)
                             }
                             launchBatchItem(context, next)
                         }
                         return
                     }

                     val app = getApplication<Application>()
                     CompressionService.stop(app)
                     CompressionNotifier.clearProgress(app)
                     CompressionNotifier.showDone(app, app.getString(R.string.notif_done_text, stateNow.queueTotal))
                     clearSavedJob()

                     _uiState.update { 
                         it.copy(
                             isCompressing = false, 
                             progress = 1f, 
                             compressedUri = Uri.fromFile(outputFile),
                             compressedSize = finalSize,
                             totalSavedBytes = newTotal
                         ) 
                     }
                     val hasCustomLocation = !_uiState.value.customOutputTreeUri.isNullOrBlank()
                     val canSave = hasCustomLocation || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                     if (canSave && (_uiState.value.autoSaveToPhotos || stateNow.queueTotal > 1)) {
                         saveCompressedOutput(getApplication())
                     }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    val app = getApplication<Application>()
                    val errorLog = exportException.stackTraceToString()
                    val isHevcCsdMuxerFailure = videoMimeType == MimeTypes.VIDEO_H265 &&
                        errorLog.contains("csd-0", ignoreCase = true) &&
                        errorLog.contains("hvcC", ignoreCase = true)

                    if (allowHevcMuxerFallback && isHevcCsdMuxerFailure) {
                        activeTransformer = null
                        viewModelScope.launch(Dispatchers.Main) {
                            _uiState.update { it.copy(isCompressing = false) }
                            startCompression(
                                context = context,
                                forcedOutputMimeType = MimeTypes.VIDEO_H264,
                                additionalWarnings = additionalWarnings + app.getString(R.string.warning_codec_fallback_h264),
                                allowHevcMuxerFallback = false
                            )
                        }
                        return
                    }

                    CompressionService.stop(app)
                    CompressionNotifier.clearProgress(app)
                    clearSavedJob()

                    _uiState.update { 
                        val isCodecError = exportException.errorCode == ExportException.ERROR_CODE_DECODER_INIT_FAILED ||
                                           exportException.errorCode == ExportException.ERROR_CODE_ENCODER_INIT_FAILED
                        val isDecoderInitError = exportException.errorCode == ExportException.ERROR_CODE_DECODER_INIT_FAILED
                        val isEncoderInitError = exportException.errorCode == ExportException.ERROR_CODE_ENCODER_INIT_FAILED
                        val isMuxerError = exportException.errorCode == ExportException.ERROR_CODE_MUXING_FAILED
                        val isHuawei = android.os.Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)

                        val errorMsg = when {
                            isMuxerError && isHuawei -> app.getString(R.string.error_huawei_muxer)
                            isDecoderInitError -> app.getString(R.string.error_decoder_config_unsupported)
                            isEncoderInitError -> app.getString(R.string.error_encoder_config_unsupported)
                            isCodecError -> app.getString(R.string.error_codec_unsupported)
                            else -> exportException.localizedMessage ?: app.getString(R.string.error_unknown)
                        }

                        it.copy(
                            isCompressing = false, 
                            error = errorMsg,
                            errorLog = errorLog,
                            queuePending = emptyList()
                        ) 
                    }
                }
            })

        val transformer = transformerBuilder.build()
        
        activeTransformer = transformer
            
        val effectsList = mutableListOf<Effect>()

           if (plan.outputHeight > 0 && plan.outputHeight != currentState.originalHeight) {
             val aspectRatio = if (currentState.originalHeight > 0) currentState.originalWidth.toFloat() / currentState.originalHeight else 16f/9f
                var width = (plan.outputHeight * aspectRatio).toInt()
                var height = plan.outputHeight

              if (width % 2 != 0) width -= 1
              if (height % 2 != 0) height -= 1

              if (width > 0 && height > 0) {
                  effectsList.add(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))
              }
        }

        val mediaItem = MediaItem.fromUri(inputUri)
        val audioProcessors: List<androidx.media3.common.audio.AudioProcessor> = if (shouldIncludeAudio && !audioPassthrough) {
            listOf(VolumeAudioProcessor().apply { setVolume(currentState.audioVolume) })
        } else {
            emptyList()
        }
        val editedMediaItemBuilder = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(audioProcessors, effectsList))
            .setRemoveAudio(!shouldIncludeAudio)
        if (plan.outputFps > 0) {
            editedMediaItemBuilder.setFrameRate(plan.outputFps)
        }
        val editedMediaItem = editedMediaItemBuilder.build()

        var hdrMode = Composition.HDR_MODE_KEEP_HDR
        if (Build.MANUFACTURER.equals("Google", ignoreCase = true) && Build.MODEL.contains("Pixel 10")) {
             if (videoMimeType == MimeTypes.VIDEO_H265 || videoMimeType == MimeTypes.VIDEO_H264) {
                 val inputIsHdr = withContext(Dispatchers.IO) { isHdr(context, inputUri) }
                 if (inputIsHdr) {
                      hdrMode = Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
                      val warningMsg = getApplication<Application>().getString(R.string.warning_hdr_tone_mapped)
                      _uiState.update { it.copy(warnings = listOf(warningMsg)) }
                 }
             }
        }

        val sequence = createMediaItemSequence(editedMediaItem, shouldIncludeAudio)

        val composition = Composition.Builder(
            listOf(sequence)
        )
        .setHdrMode(hdrMode)
        .build()

        compressionStartedAtMs = android.os.SystemClock.elapsedRealtime()
        transformer.start(composition, outputPath)
        
        compressionJob = viewModelScope.launch {
            val progressHolder = androidx.media3.transformer.ProgressHolder()
            var lastNotifiedPercent = -1
            while (_uiState.value.isCompressing) {
                val state = transformer.getProgress(progressHolder)
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    val currentSize = outputFile.length()
                    _uiState.update { it.copy(progress = progressHolder.progress / 100f, currentOutputSize = currentSize) }
                    val percent = progressHolder.progress
                    if (percent != lastNotifiedPercent) {
                        lastNotifiedPercent = percent
                        val snapshot = _uiState.value
                        CompressionNotifier.updateProgress(
                            context, percent, snapshot.originalName, snapshot.queueDone + 1, snapshot.queueTotal
                        )
                    }
                }
                kotlinx.coroutines.delay(200)
            }
        }
    }

    internal fun createMediaItemSequence(
        editedMediaItem: EditedMediaItem,
        shouldIncludeAudio: Boolean
    ): EditedMediaItemSequence {
        return if (shouldIncludeAudio) {
            EditedMediaItemSequence.withAudioAndVideoFrom(listOf(editedMediaItem))
        } else {
            EditedMediaItemSequence.withVideoFrom(listOf(editedMediaItem))
        }
    }

    private fun probeTracks(context: Context, uri: Uri): TrackProbe {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var video: VideoTrackInfo? = null
            var audioMime: String? = null
            var audioBitrate = 0
            var aacProfile = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    video == null && mime.startsWith("video/") -> {
                        val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                        val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                        var frameRate = 0f
                        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            try {
                                frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                            } catch (e: Exception) {
                                try {
                                    frameRate = format.getFloat(MediaFormat.KEY_FRAME_RATE)
                                } catch (ignored: Exception) {}
                            }
                        }
                        video = VideoTrackInfo(mime, width, height, frameRate)
                    }
                    audioMime == null && mime.startsWith("audio/") -> {
                        audioMime = mime
                        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            audioBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE)
                        }
                        if (format.containsKey("aac-profile")) {
                            aacProfile = format.getInteger("aac-profile")
                        }
                    }
                }
                if (video != null && audioMime != null) break
            }
            return TrackProbe(video, audioMime, audioBitrate, aacProfile)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            extractor.release()
        }
        return TrackProbe(null, null, 0, -1)
    }

    private fun buildCompressionPlan(probe: TrackProbe, state: CompressorUiState): CompressionPlan {
        var outputMime = state.videoCodec
        var outputHeight = state.targetResolutionHeight
        var outputFps = state.targetFps
        val warnings = mutableListOf<String>()

        val sourceInfo = probe.video
        val sourceMime = sourceInfo?.mimeType ?: state.originalVideoMime
        val sourceWidth = sourceInfo?.width ?: 0
        val sourceHeight = sourceInfo?.height ?: 0
        val sourceFps = if ((sourceInfo?.frameRate ?: 0f) > 0f) sourceInfo!!.frameRate else state.originalFps

        if (!sourceMime.isNullOrBlank() && sourceWidth > 0 && sourceHeight > 0) {
            val decoderSupported = isCodecConfigurationSupported(
                mimeType = sourceMime,
                width = sourceWidth,
                height = sourceHeight,
                fps = sourceFps,
                encoder = false
            )
            if (!decoderSupported) {
                return CompressionPlan(
                    outputVideoMimeType = outputMime,
                    outputHeight = outputHeight,
                    outputFps = outputFps,
                    warnings = warnings,
                    blockingError = getApplication<Application>().getString(
                        R.string.error_decoder_config_unsupported_details,
                        sourceWidth,
                        sourceHeight,
                        sourceFps,
                        sourceMime.substringAfter("/")
                    )
                )
            }
        }

        val attemptedConfigs = mutableListOf<Triple<String, Int, Int>>()
        fun isCurrentOutputSupported(mime: String, height: Int, fps: Int): Boolean {
            val safeHeight = if (height > 0) height else state.originalHeight
            val safeFps = if (fps > 0) fps else state.originalFps.toInt()
            val aspectRatio = if (state.originalHeight > 0) state.originalWidth.toFloat() / state.originalHeight else 16f / 9f
            var outputWidth = (safeHeight * aspectRatio).toInt().coerceAtLeast(2)
            var outputActualHeight = safeHeight.coerceAtLeast(2)
            if (outputWidth % 2 != 0) outputWidth -= 1
            if (outputActualHeight % 2 != 0) outputActualHeight -= 1
            attemptedConfigs.add(Triple(mime, outputActualHeight, safeFps))
            return isCodecConfigurationSupported(
                mimeType = mime,
                width = outputWidth,
                height = outputActualHeight,
                fps = safeFps.toFloat(),
                encoder = true
            )
        }

        if (!isCurrentOutputSupported(outputMime, outputHeight, outputFps)) {
            if (outputMime != MimeTypes.VIDEO_H264 && isCurrentOutputSupported(MimeTypes.VIDEO_H264, outputHeight, outputFps)) {
                outputMime = MimeTypes.VIDEO_H264
                warnings.add(getApplication<Application>().getString(R.string.warning_codec_fallback_h264))
            } else {
                val fallbackHeights = listOf(1080, 720, 540, 480)
                    .filter { it in 2..state.originalHeight }
                    .ifEmpty { listOf(state.originalHeight.coerceAtLeast(2)) }
                val fallbackFps = listOf(30, 24)
                var supported = false

                for (heightCandidate in fallbackHeights) {
                    for (fpsCandidate in fallbackFps) {
                        if (isCurrentOutputSupported(MimeTypes.VIDEO_H264, heightCandidate, fpsCandidate)) {
                            outputMime = MimeTypes.VIDEO_H264
                            outputHeight = heightCandidate
                            outputFps = fpsCandidate
                            warnings.add(
                                getApplication<Application>().getString(
                                    R.string.warning_quality_fallback,
                                    outputHeight,
                                    outputFps
                                )
                            )
                            supported = true
                            break
                        }
                    }
                    if (supported) break
                }

                if (!supported) {
                    val attempted = attemptedConfigs
                        .joinToString(separator = ", ") { "${it.first.substringAfter("/")} ${it.second}p@${it.third}fps" }
                    return CompressionPlan(
                        outputVideoMimeType = outputMime,
                        outputHeight = outputHeight,
                        outputFps = outputFps,
                        warnings = warnings,
                        blockingError = getApplication<Application>().getString(
                            R.string.error_encoder_config_unsupported_details,
                            attempted
                        )
                    )
                }
            }
        }

        return CompressionPlan(
            outputVideoMimeType = outputMime,
            outputHeight = outputHeight,
            outputFps = outputFps,
            warnings = warnings,
            blockingError = null
        )
    }

    private fun isCodecConfigurationSupported(
        mimeType: String,
        width: Int,
        height: Int,
        fps: Float,
        encoder: Boolean
    ): Boolean {
        return try {
            val safeFps = kotlin.math.ceil(if (fps > 0f) fps.toDouble() else 30.0)
            cachedCodecInfos
                .asSequence()
                .filter { it.isEncoder == encoder }
                .filter { info -> info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
                .any { info ->
                    try {
                        val capabilities = info.getCapabilitiesForType(mimeType)
                        val videoCaps = capabilities.videoCapabilities ?: return@any false
                        videoCaps.areSizeAndRateSupported(width, height, safeFps) ||
                            videoCaps.areSizeAndRateSupported(height, width, safeFps)
                    } catch (_: Exception) {
                        false
                    }
                }
        } catch (_: Exception) {
            false
        }
    }

    private fun isMediaTekDeviceOrEncoder(mimeType: String): Boolean {
        try {
            val hardware = Build.HARDWARE.lowercase()
            val board = Build.BOARD.lowercase()
            val manufacturer = Build.MANUFACTURER.lowercase()
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.lowercase() else ""

            if (hardware.contains("mediatek") || board.contains("mediatek") || manufacturer.contains("mediatek") ||
                soc.contains("mediatek") || soc.contains("dimensity") ||
                hardware.matches(Regex(""".*mt\d{4}.*""")) || board.matches(Regex(""".*mt\d{4}.*"""))
            ) {
                return true
            }

            for (info in cachedCodecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                    val name = info.name.lowercase()
                    if (name.contains("mtk") || name.contains("mediatek")) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun isHdr(context: Context, uri: Uri): Boolean {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            if (Build.VERSION.SDK_INT >= 30) {
               val transfer = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)
               return transfer == "6" || transfer == "7"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch(e: Exception) {}
        }
        return false
    }

    fun saveToUri(context: Context, targetUri: Uri) {
        val currentState = _uiState.value
        if (currentState.isSaving) return
        val compressedUri = currentState.compressedUri ?: return

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(compressedUri.path!!)
                if (!file.exists()) {
                    _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_file_lost)) }
                    return@launch
                }
                
                context.contentResolver.openOutputStream(targetUri)?.use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out, COPY_BUFFER_BYTES)
                    }
                }
                 attachSavedOutput(compressedOutputFileName(currentState), targetUri)
                 _uiState.update { it.copy(saveSuccess = true) }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, e.message)) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun saveToCustomTree(context: Context, treeUri: Uri) {
        val currentState = _uiState.value
        if (currentState.isSaving) return
        val compressedUri = currentState.compressedUri ?: return

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(compressedUri.path!!)
                if (!file.exists()) {
                    _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_file_lost)) }
                    return@launch
                }

                val tree = DocumentFile.fromTreeUri(context, treeUri)
                if (tree == null || !tree.canWrite()) {
                    _uiState.update {
                        it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, "Folder not writable"))
                    }
                    return@launch
                }

                val targetName = compressedOutputFileName(currentState)
                tree.findFile(targetName)?.takeIf { it.isFile }?.delete()
                val target = tree.createFile("video/mp4", targetName)
                if (target == null) {
                    _uiState.update {
                        it.copy(error = getApplication<Application>().getString(R.string.error_gallery_entry))
                    }
                    return@launch
                }

                context.contentResolver.openOutputStream(target.uri)?.use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out, COPY_BUFFER_BYTES)
                    }
                } ?: run {
                    _uiState.update {
                        it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, "No output stream"))
                    }
                    return@launch
                }

                attachSavedOutput(targetName, target.uri)
                _uiState.update { it.copy(saveSuccess = true) }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update {
                    it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, e.message))
                }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun saveToGallery(context: Context) {
        val currentState = _uiState.value
        if (currentState.isSaving) return
        val compressedUri = currentState.compressedUri ?: return
        
        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(compressedUri.path!!)
                if (!file.exists()) {
                    _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_file_lost)) }
                    return@launch
                }

                val targetName = compressedOutputFileName(currentState)

                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, targetName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)

                    if (!containsKey(MediaStore.Video.Media.DATE_ADDED)) {
                        put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    }
                    if (!containsKey(MediaStore.Video.Media.DATE_MODIFIED)) {
                        put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Compressor")
                    }
                }

                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }

                val itemUri = context.contentResolver.insert(collection, values)
                
                if (itemUri != null) {
                    context.contentResolver.openOutputStream(itemUri).use { out ->
                        file.inputStream().use { input ->
                            input.copyTo(out!!, COPY_BUFFER_BYTES)
                        }
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Video.Media.IS_PENDING, 0)
                        context.contentResolver.update(itemUri, values, null, null)
                    }
                    
                    attachSavedOutput(targetName, itemUri)
                    _uiState.update { it.copy(saveSuccess = true) }
                } else {
                     _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_gallery_entry)) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, e.message)) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }
}
