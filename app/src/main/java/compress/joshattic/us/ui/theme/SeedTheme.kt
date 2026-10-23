package compress.joshattic.us.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils

private fun hsl(h: Float, s: Float, l: Float): Color {
    val hue = ((h % 360f) + 360f) % 360f
    return Color(ColorUtils.HSLToColor(floatArrayOf(hue, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))
}

/** Builds a complete Material 3 colour scheme from a single seed colour. */
fun schemeFromSeed(seed: Int, dark: Boolean): ColorScheme {
    val hslValues = FloatArray(3)
    ColorUtils.colorToHSL(seed, hslValues)
    val h = hslValues[0]
    val s = hslValues[1].coerceIn(0.35f, 0.85f)

    return if (!dark) {
        lightColorScheme(
            primary = hsl(h, s, 0.40f),
            onPrimary = Color.White,
            primaryContainer = hsl(h, s, 0.90f),
            onPrimaryContainer = hsl(h, s, 0.12f),
            secondary = hsl(h + 20f, s * 0.5f, 0.40f),
            onSecondary = Color.White,
            secondaryContainer = hsl(h + 20f, s * 0.5f, 0.90f),
            onSecondaryContainer = hsl(h + 20f, s * 0.5f, 0.12f),
            tertiary = hsl(h + 60f, s * 0.6f, 0.40f),
            onTertiary = Color.White,
            tertiaryContainer = hsl(h + 60f, s * 0.6f, 0.90f),
            onTertiaryContainer = hsl(h + 60f, s * 0.6f, 0.12f),
            background = hsl(h, 0.30f, 0.97f),
            surface = hsl(h, 0.30f, 0.97f),
            surfaceContainerLow = hsl(h, 0.28f, 0.95f),
            surfaceContainer = hsl(h, 0.26f, 0.93f),
            surfaceContainerHigh = hsl(h, 0.24f, 0.91f),
            surfaceContainerHighest = hsl(h, 0.22f, 0.89f),
            onBackground = hsl(h, 0.15f, 0.10f),
            onSurface = hsl(h, 0.15f, 0.10f),
            onSurfaceVariant = hsl(h, 0.12f, 0.30f),
            outline = hsl(h, 0.10f, 0.45f),
            outlineVariant = hsl(h, 0.15f, 0.80f),
            surfaceTint = hsl(h, s, 0.40f)
        )
    } else {
        darkColorScheme(
            primary = hsl(h, s, 0.75f),
            onPrimary = hsl(h, s, 0.15f),
            primaryContainer = hsl(h, s, 0.25f),
            onPrimaryContainer = hsl(h, s, 0.90f),
            secondary = hsl(h + 20f, s * 0.5f, 0.75f),
            onSecondary = hsl(h + 20f, s * 0.5f, 0.15f),
            secondaryContainer = hsl(h + 20f, s * 0.5f, 0.25f),
            onSecondaryContainer = hsl(h + 20f, s * 0.5f, 0.90f),
            tertiary = hsl(h + 60f, s * 0.6f, 0.75f),
            onTertiary = hsl(h + 60f, s * 0.6f, 0.15f),
            tertiaryContainer = hsl(h + 60f, s * 0.6f, 0.25f),
            onTertiaryContainer = hsl(h + 60f, s * 0.6f, 0.90f),
            background = hsl(h, 0.25f, 0.07f),
            surface = hsl(h, 0.25f, 0.07f),
            surfaceContainerLow = hsl(h, 0.22f, 0.10f),
            surfaceContainer = hsl(h, 0.22f, 0.12f),
            surfaceContainerHigh = hsl(h, 0.20f, 0.16f),
            surfaceContainerHighest = hsl(h, 0.20f, 0.20f),
            onBackground = hsl(h, 0.15f, 0.90f),
            onSurface = hsl(h, 0.15f, 0.90f),
            onSurfaceVariant = hsl(h, 0.12f, 0.75f),
            outline = hsl(h, 0.10f, 0.55f),
            outlineVariant = hsl(h, 0.12f, 0.30f),
            surfaceTint = hsl(h, s, 0.75f)
        )
    }
}

/**
 * Finds the most prominent vivid colour of an image: pixels are grouped by hue,
 * weighted by saturation and brightness, and the heaviest group is averaged.
 * Returns null if the image cannot be read.
 */
fun extractSeedColor(context: Context, uri: Uri): Int? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > 256) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap: Bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val buckets = 24
        val weight = FloatArray(buckets)
        val sumR = FloatArray(buckets)
        val sumG = FloatArray(buckets)
        val sumB = FloatArray(buckets)
        val count = IntArray(buckets)
        val tmp = FloatArray(3)
        val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 64)

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                ColorUtils.colorToHSL(pixel, tmp)
                val sat = tmp[1]
                val light = tmp[2]
                if (light in 0.12f..0.92f) {
                    val w = sat * (1f - kotlin.math.abs(light - 0.5f) * 1.2f).coerceAtLeast(0.05f)
                    val b = ((tmp[0] / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
                    weight[b] += w
                    sumR[b] += android.graphics.Color.red(pixel)
                    sumG[b] += android.graphics.Color.green(pixel)
                    sumB[b] += android.graphics.Color.blue(pixel)
                    count[b]++
                }
                x += step
            }
            y += step
        }
        bitmap.recycle()

        var best = -1
        for (i in 0 until buckets) {
            if (count[i] > 0 && (best == -1 || weight[i] > weight[best])) best = i
        }
        if (best == -1) {
            // Grey / very dark or very light image: fall back to a neutral blue-grey seed
            return android.graphics.Color.rgb(96, 125, 139)
        }
        android.graphics.Color.rgb(
            (sumR[best] / count[best]).toInt(),
            (sumG[best] / count[best]).toInt(),
            (sumB[best] / count[best]).toInt()
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
