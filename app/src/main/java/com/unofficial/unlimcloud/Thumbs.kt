package com.unofficial.unlimcloud

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** ডিকোড করা থাম্বনেইলের মেমরি ক্যাশ (স্ক্রলের সময় বারবার ডিকোড এড়াতে)। */
object ThumbCache {
    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()   // KB হিসাবে, মোট মেমরির ~১/৮
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4 / 1024
    }

    fun peek(path: String?): ImageBitmap? = if (path == null) null else cache.get(path)

    suspend fun load(path: String, maxPx: Int = 420): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(path)?.let { return@withContext it }
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(path, opts) ?: return@withContext null
            val img = bmp.asImageBitmap()
            cache.put(path, img)
            img
        } catch (e: Throwable) {
            null
        }
    }

    fun decodeMini(bytes: ByteArray): ImageBitmap? = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (e: Throwable) {
        null
    }
}

private val VIDEO_EXT = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "flv", "wmv")
private val AUDIO_EXT = setOf("mp3", "m4a", "wav", "flac", "ogg", "opus", "aac", "wma", "amr")
private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg")

private class TileStyle(val color: Color, val label: String)

private fun styleFor(f: CloudFile): TileStyle {
    val ext = f.name.substringAfterLast('.', "").lowercase()
    val colorValue: Long = when {
        ext == "pdf" -> 0xFFE53935
        ext in setOf("doc", "docx", "odt", "rtf") -> 0xFF1E88E5
        ext in setOf("xls", "xlsx", "csv", "ods") -> 0xFF43A047
        ext in setOf("ppt", "pptx", "odp") -> 0xFFFB8C00
        ext in setOf("zip", "rar", "7z", "tar", "gz") -> 0xFF8E24AA
        ext == "apk" -> 0xFF2E7D32
        ext in setOf("txt", "md", "log") -> 0xFF757575
        ext in AUDIO_EXT -> 0xFFD81B60
        ext in VIDEO_EXT -> 0xFF3949AB
        ext in IMAGE_EXT -> 0xFF00897B
        f.kind == Kind.AUDIO -> 0xFFD81B60
        f.kind == Kind.MEDIA -> 0xFF3949AB
        else -> 0xFF546E7A
    }
    val label = when {
        ext in AUDIO_EXT || f.kind == Kind.AUDIO -> "♪"
        ext in VIDEO_EXT -> "▶"
        ext.isNotEmpty() -> ext.uppercase().take(4)
        else -> "FILE"
    }
    return TileStyle(Color(colorValue), label)
}

/** থাম্বনেইল না থাকলে/লোড হওয়ার আগে এটিই দেখায়: ফাইলের ধরন অনুযায়ী রঙিন আইকন-টাইল। */
@Composable
fun DefaultTile(f: CloudFile) {
    val style = remember(f.name, f.kind) { styleFor(f) }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(style.color),
        contentAlignment = Alignment.Center
    ) {
        val size = (maxWidth.value * 0.3f).coerceIn(9f, 30f)
        Text(
            style.label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = size.sp,
            maxLines = 1
        )
    }
}

/**
 * কখনও ফাঁকা দেখায় না। স্তরগুলো:
 *  ১) ফাইলের ধরন অনুযায়ী ডিফল্ট আইকন-টাইল (সবসময় থাকে)
 *  ২) মেসেজের সাথে আসা কম-রেজোলিউশনের প্রিভিউ (সাথে সাথে দেখায়)
 *  ৩) আসল থাম্বনেইল (নামা শেষ হলে ধীরে ধীরে ফুটে ওঠে)
 */
@Composable
fun Thumb(f: CloudFile, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    val thumbs by TdManager.thumbs.collectAsState()
    val path = thumbs[f.thumbFileId]

    LaunchedEffect(f.thumbFileId) { TdManager.requestThumb(f.thumbFileId) }

    val mini = remember(f.mini) { f.mini?.let { ThumbCache.decodeMini(it) } }
    val full by produceState<ImageBitmap?>(initialValue = ThumbCache.peek(path), key1 = path) {
        value = if (path == null) null else (ThumbCache.peek(path) ?: ThumbCache.load(path))
    }

    Box(modifier.clip(RoundedCornerShape(corner))) {
        DefaultTile(f)
        if (mini != null) {
            Image(
                bitmap = mini,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Crossfade(targetState = full, animationSpec = tween(250), label = "thumb") { bmp ->
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
