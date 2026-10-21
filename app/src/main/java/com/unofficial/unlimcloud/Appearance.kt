package com.unofficial.unlimcloud

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream

enum class BgSource { DEFAULT, GRADIENT, IMAGE }

enum class BgFit(val label: String) { COVER("Cover"), CONTAIN("Contain"), CENTER("Center") }

enum class ThemeMode(val label: String) { AUTO("অটো"), LIGHT("লাইট"), DARK("ডার্ক") }

data class GradientDef(val name: String, val colors: List<Color>)

/** ব্যাকগ্রাউন্ড ও গ্লাস-ডিজাইনের সব ব্যবহারকারী-সেটিংস। */
data class AppearanceState(
    val source: BgSource = BgSource.DEFAULT,
    val gradientIndex: Int = 0,
    val imageVersion: Int = 0,        // নতুন ছবি বসালে বাড়ে, যাতে লাইভ রিলোড হয়
    val fit: BgFit = BgFit.COVER,
    val blur: Float = 0f,             // ০..১
    val brightness: Float = 0f,       // -০.৫ (গাঢ়) .. +০.৫ (উজ্জ্বল)
    val glassAlpha: Float = 0.25f,    // গ্লাসের স্বচ্ছতা/টিন্ট
    val themeMode: ThemeMode = ThemeMode.AUTO
)

class BackdropData(val display: ImageBitmap?, val glass: ImageBitmap?, val luminance: Float) {
    companion object {
        val EMPTY = BackdropData(null, null, -1f)
    }
}

object Appearance {

    val gradients = listOf(
        GradientDef("Aurora", listOf(Color(0xFF7F7FD5), Color(0xFF86A8E7), Color(0xFF91EAE4))),
        GradientDef("Sunset", listOf(Color(0xFFFF9966), Color(0xFFFF5E62))),
        GradientDef("Ocean", listOf(Color(0xFF2193B0), Color(0xFF6DD5ED))),
        GradientDef("Forest", listOf(Color(0xFF11998E), Color(0xFF38EF7D))),
        GradientDef("Violet", listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0))),
        GradientDef("Rose", listOf(Color(0xFFEE9CA7), Color(0xFFFFDDE1))),
        GradientDef("Midnight", listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)))
    )

    /** ডিফল্ট ব্যাকগ্রাউন্ড (লাইট ও ডার্ক) */
    val defaultLight = listOf(Color(0xFFE6EEFF), Color(0xFFF3E9FF), Color(0xFFE4FBF0))
    val defaultDark = listOf(Color(0xFF0E1420), Color(0xFF1A1233), Color(0xFF0A2A2B))

    val state = MutableStateFlow(AppearanceState())

    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    // সোর্স ছবি ক্যাশ (ব্লার স্লাইডার টানার সময় বারবার ডিকোড এড়াতে)
    private var cachedVersion = -1
    private var cachedBitmap: Bitmap? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        state.value = AppearanceState(
            source = enumOr(prefs.getString("source", null), BgSource.DEFAULT),
            gradientIndex = prefs.getInt("gradient", 0),
            imageVersion = prefs.getInt("version", 0),
            fit = enumOr(prefs.getString("fit", null), BgFit.COVER),
            blur = prefs.getFloat("blur", 0f),
            brightness = prefs.getFloat("brightness", 0f),
            glassAlpha = prefs.getFloat("glass", 0.25f),
            themeMode = enumOr(prefs.getString("theme", null), ThemeMode.AUTO)
        )
        // ছবি মুছে গেলে নিরাপদে ডিফল্টে ফেরা
        if (state.value.source == BgSource.IMAGE && !imageFile().exists()) {
            state.value = state.value.copy(source = BgSource.DEFAULT)
        }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        try {
            if (name == null) fallback else enumValueOf<T>(name)
        } catch (e: Exception) {
            fallback
        }

    private fun imageFile() = File(appContext.filesDir, "background.jpg")

    private fun save() {
        val s = state.value
        prefs.edit()
            .putString("source", s.source.name)
            .putInt("gradient", s.gradientIndex)
            .putInt("version", s.imageVersion)
            .putString("fit", s.fit.name)
            .putFloat("blur", s.blur)
            .putFloat("brightness", s.brightness)
            .putFloat("glass", s.glassAlpha)
            .putString("theme", s.themeMode.name)
            .apply()
    }

    /** সেটিং বদলান; সাথে সাথে পুরো অ্যাপে প্রয়োগ হয় ও ফোনে সংরক্ষিত হয়। */
    fun update(block: (AppearanceState) -> AppearanceState) {
        state.update(block)
        save()
    }

    fun reset() {
        try {
            imageFile().delete()
        } catch (e: Exception) {
            // গুরুত্বপূর্ণ নয়
        }
        cachedBitmap = null
        cachedVersion = -1
        state.value = AppearanceState()
        save()
    }

    /** গ্যালারির ছবি অ্যাপের ভেতরে ছোট করে কপি করে ব্যাকগ্রাউন্ড বানায়। */
    fun setImage(uri: Uri, done: (Boolean) -> Unit) {
        Thread {
            var ok = false
            try {
                val resolver = appContext.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val longSide = maxOf(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (longSide / (sample * 2) >= 1600) sample *= 2

                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                if (decoded != null) {
                    val orientation = try {
                        resolver.openInputStream(uri)?.use {
                            ExifInterface(it).getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL
                            )
                        } ?: ExifInterface.ORIENTATION_NORMAL
                    } catch (e: Exception) {
                        ExifInterface.ORIENTATION_NORMAL
                    }
                    val degrees = when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                    val finalBmp = if (degrees != 0f) {
                        val m = Matrix().apply { postRotate(degrees) }
                        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
                    } else {
                        decoded
                    }
                    FileOutputStream(imageFile()).use { out ->
                        finalBmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    update { it.copy(source = BgSource.IMAGE, imageVersion = it.imageVersion + 1) }
                    ok = true
                }
            } catch (e: Exception) {
                ok = false
            }
            done(ok)
        }.start()
    }

    // ------------------------------------------------------------ বিটম্যাপ প্রসেসিং

    /** সস্তা কিন্তু সব Android-এ চলা ব্লার: ছোট করে আবার বড় করা (bilinear)। */
    private fun blur(src: Bitmap, strength: Float): Bitmap {
        if (strength <= 0.01f) return src
        val factor = (2 + strength * 30f).toInt()
        val w = (src.width / factor).coerceAtLeast(2)
        val h = (src.height / factor).coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val mid = Bitmap.createScaledBitmap(
            small, (w * 3).coerceAtMost(src.width), (h * 3).coerceAtMost(src.height), true
        )
        return Bitmap.createScaledBitmap(mid, src.width, src.height, true)
    }

    private fun averageLuminance(src: Bitmap): Float {
        val tiny = Bitmap.createScaledBitmap(src, 8, 8, true)
        var sum = 0.0
        for (y in 0 until 8) for (x in 0 until 8) {
            val p = tiny.getPixel(x, y)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        }
        return (sum / 64.0).toFloat()
    }

    fun luminance(colors: List<Color>): Float =
        colors.map { 0.299f * it.red + 0.587f * it.green + 0.114f * it.blue }.average().toFloat()

    @Synchronized
    private fun sourceBitmap(version: Int): Bitmap? {
        if (cachedBitmap != null && cachedVersion == version) return cachedBitmap
        val f = imageFile()
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.absolutePath)
        cachedBitmap = bmp
        cachedVersion = version
        return bmp
    }

    /** ভারী কাজ (ডিকোড + ব্লার) — ব্যাকগ্রাউন্ড থ্রেডে ডাকতে হবে। */
    fun buildBackdropData(s: AppearanceState): BackdropData {
        if (s.source != BgSource.IMAGE) return BackdropData.EMPTY
        return try {
            val src = sourceBitmap(s.imageVersion) ?: return BackdropData.EMPTY
            val display = blur(src, s.blur)
            val glass = blur(src, 0.75f)
            BackdropData(display.asImageBitmap(), glass.asImageBitmap(), averageLuminance(src))
        } catch (e: Throwable) {
            BackdropData.EMPTY
        }
    }
}
