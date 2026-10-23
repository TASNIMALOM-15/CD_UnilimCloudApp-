package compress.joshattic.us.ui.glass

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import kotlinx.coroutines.delay
import androidx.media3.exoplayer.ExoPlayer
import android.content.ContextWrapper
import java.io.File
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import compress.joshattic.us.model.GlassSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The decoded background picture plus what we learned from it (average colour, brightness). */
class BgData(
    val image: ImageBitmap,
    val avg: Color,
    val luminance: Float,
    val videoPath: String? = null,
    val videoW: Float = 0f,   // size of the video as it should be shown (rotation applied)
    val videoH: Float = 0f
)

@Composable
fun rememberBgData(glass: GlassSettings): BgData? {
    var data by remember { mutableStateOf<BgData?>(null) }
    LaunchedEffect(glass.enabled, glass.imagePath, glass.imageVersion) {
        val path = glass.imagePath
        data = if (!glass.enabled || path == null) null else withContext(Dispatchers.IO) { loadBg(path, glass.isVideo) }
    }
    return data
}

private class VideoProbe(val frame: Bitmap?, val width: Int, val height: Int)

/** Reads the size of a video and its first picture (used for colours and as the still frame). */
private fun probeVideo(path: String): VideoProbe? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rotation == 90 || rotation == 270) {
            val t = w; w = h; h = t
        }
        val raw = try {
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Throwable) {
            null
        }
        val frame = raw?.let {
            val longest = maxOf(it.width, it.height)
            if (longest > 720) {
                val scale = 720f / longest
                Bitmap.createScaledBitmap(it, (it.width * scale).toInt().coerceAtLeast(1), (it.height * scale).toInt().coerceAtLeast(1), true)
            } else it
        }
        VideoProbe(frame, w, h)
    } catch (e: Throwable) {
        null
    } finally {
        try { retriever.release() } catch (_: Throwable) {}
    }
}

private fun loadBg(path: String, isVideo: Boolean): BgData? {
    return try {
        var videoW = 0f
        var videoH = 0f
        val bmp = if (isVideo) {
            val probe = probeVideo(path) ?: return null
            videoW = probe.width.toFloat()
            videoH = probe.height.toFloat()
            // Even if this phone cannot give a first picture, the video itself may still play.
            probe.frame ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF303030.toInt()) }
        } else {
            BitmapFactory.decodeFile(path) ?: return null
        }
        val small = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        var r = 0L
        var g = 0L
        var b = 0L
        val count = small.width * small.height
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val px = small.getPixel(x, y)
                r += (px shr 16) and 0xFF
                g += (px shr 8) and 0xFF
                b += px and 0xFF
            }
        }
        small.recycle()
        val rf = r / count / 255f
        val gf = g / count / 255f
        val bf = b / count / 255f
        BgData(bmp.asImageBitmap(), Color(rf, gf, bf), 0.2126f * rf + 0.7152f * gf + 0.0722f * bf, if (isVideo) path else null, videoW, videoH)
    } catch (e: Throwable) {
        null
    }
}

/** Works out the glass look from the user's choices and the picture (adaptive contrast). */
@Composable
fun rememberGlassStyle(glass: GlassSettings, bg: BgData?, systemDark: Boolean): GlassStyle {
    if (!glass.enabled) return GlassStyle()
    val baseLum = bg?.luminance ?: if (systemDark) 0.08f else 0.92f
    val effLum = if (glass.dim >= 0f) baseLum * (1f - glass.dim) else baseLum + (1f - baseLum) * (-glass.dim)
    val dark = when (glass.textMode) {
        1 -> true
        2 -> false
        else -> effLum < 0.5f
    }
    val avg = bg?.avg ?: Color(0xFF0B3C4A)
    val spread = maxOf(avg.red, avg.green, avg.blue) - minOf(avg.red, avg.green, avg.blue)
    val accent = when {
        spread < 0.08f -> Color(0xFF5BC0BE)                          // grey picture: calm teal
        dark -> lerp(avg, Color.White, 0.35f)
        else -> lerp(avg, Color.Black, 0.10f)
    }
    val tint = if (dark) lerp(Color.White, avg, 0.18f) else Color.White
    return GlassStyle(enabled = true, alpha = glass.alpha, tint = tint, accent = accent, dark = dark)
}

/** Paints the picture (or a soft gradient) behind the whole app. */
@Composable
fun GlassHost(
    glass: GlassSettings,
    bg: BgData?,
    style: GlassStyle,
    videoPaused: Boolean = false,
    content: @Composable () -> Unit
) {
    if (!style.enabled) {
        content()
        return
    }
    val baseBrush = if (style.dark) {
        Brush.verticalGradient(listOf(Color(0xFF031A22), Color(0xFF0E4A5C)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFEAF6F8), Color(0xFFBFE3EC)))
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().background(baseBrush))
        if (bg != null) {
            val native31 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val image = if (!native31 && glass.blur > 0f) {
                remember(bg, glass.blur) { softened(bg.image, glass.blur) }
            } else bg.image
            var modifier = Modifier.fillMaxSize()
            if (glass.blur > 0f) {
                modifier = modifier.graphicsLayer { scaleX = 1.08f; scaleY = 1.08f }
                if (native31) modifier = modifier.blur(glass.blur.dp, BlurredEdgeTreatment.Unbounded)
            }
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = modifier,
                alignment = Alignment.Center,
                contentScale = when (glass.fit) {
                    1 -> ContentScale.Fit
                    2 -> ContentScale.None
                    else -> ContentScale.Crop
                },
                filterQuality = FilterQuality.Medium
            )
        }
        if (bg?.videoPath != null) {
            GlassVideo(bg.videoPath, glass, videoPaused, bg.videoW, bg.videoH)
        }
        val overlay = if (glass.dim >= 0f) Color.Black.copy(alpha = glass.dim) else Color.White.copy(alpha = -glass.dim)
        Box(modifier = Modifier.fillMaxSize().background(overlay))
        content()
    }
}

/** Cheap blur for old phones (Android 11 and below): shrink the picture, the GPU stretches it back softly. */
private fun softened(image: ImageBitmap, blur: Float): ImageBitmap {
    return try {
        val src = image.asAndroidBitmap()
        val f = 1f + blur / 3f
        val w = (src.width / f).toInt().coerceAtLeast(8)
        val h = (src.height / f).toInt().coerceAtLeast(8)
        Bitmap.createScaledBitmap(src, w, h, true).asImageBitmap()
    } catch (e: Throwable) {
        image
    }
}

/**
 * Looping background video. Sound is OFF by default.
 * Normal mode: pauses while the app is off-screen and while a compression runs.
 * "Forcefully play" mode: never pauses for compression, retries after errors and restarts a stalled video.
 */
@OptIn(UnstableApi::class)
@Composable
private fun GlassVideo(path: String, glass: GlassSettings, paused: Boolean, knownW: Float, knownH: Float) {
    val context = LocalContext.current
    val player = remember(path, glass.imageVersion) {
        val renderers = DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(true) // if the first decoder fails, try the next (e.g. software) one
        ExoPlayer.Builder(context.applicationContext, renderers).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            repeatMode = Player.REPEAT_MODE_ALL
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false // never steal audio focus from the user's music
            )
            volume = 0f
            playWhenReady = false
            prepare()
        }
    }

    // The picture surface must exist from the start: the decoder only wakes up once it has a surface.
    var videoW by remember(player) { mutableStateOf(knownW) }
    var videoH by remember(player) { mutableStateOf(knownH) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoW = videoSize.width * videoSize.pixelWidthHeightRatio
                    videoH = videoSize.height.toFloat()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Keep the still frame; the keep-alive loop below retries in force mode.
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val lifecycleOwner = remember(context) {
        generateSequence<android.content.Context>(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<LifecycleOwner>().firstOrNull()
    }
    var onScreen by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) onScreen = true
            else if (event == Lifecycle.Event.ON_STOP) onScreen = false
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    val shouldPlay = onScreen && (!paused || glass.forcePlay)
    LaunchedEffect(player, glass.videoMuted) { player.volume = if (glass.videoMuted) 0f else 1f }
    LaunchedEffect(player, shouldPlay) { player.playWhenReady = shouldPlay }
    LaunchedEffect(player, shouldPlay, glass.forcePlay) {
        // Force mode: every 2 seconds make sure it is really playing; if not, prepare and play again.
        while (glass.forcePlay && shouldPlay) {
            delay(2000)
            if (!player.isPlaying) {
                if (player.playerError != null || player.playbackState == Player.STATE_IDLE) player.prepare()
                player.playWhenReady = true
                player.play()
            }
        }
    }

    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val hasSize = videoW > 0f && videoH > 0f
        val aspect = if (hasSize) videoW / videoH else maxWidth / maxHeight
        val boxAspect = maxWidth / maxHeight
        val (w, h) = when {
            !hasSize -> maxWidth to maxHeight
            glass.fit == 1 -> if (boxAspect > aspect) (maxHeight * aspect) to maxHeight else maxWidth to (maxWidth / aspect)
            glass.fit == 2 -> with(density) { videoW.toDp() to videoH.toDp() }
            else -> if (boxAspect > aspect) maxWidth to (maxWidth / aspect) else (maxHeight * aspect) to maxHeight
        }
        val blurPx = glass.blur * density.density
        AndroidView(
            modifier = Modifier.requiredSize(w, h),
            factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
            update = { view ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    view.setRenderEffect(
                        if (blurPx > 0.5f) RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP) else null
                    )
                }
            }
        )
    }
}
