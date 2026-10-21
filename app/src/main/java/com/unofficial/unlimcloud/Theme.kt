package com.unofficial.unlimcloud

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2E9E3F),
    onPrimary = Color.White,
    secondary = Color(0xFF65DE69),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF111418),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111418),
    onSurfaceVariant = Color(0xFF4A505C),
    error = Color(0xFFD32F2F),
    outlineVariant = Color(0x22000000)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5BE37A),
    onPrimary = Color(0xFF00210A),
    secondary = Color(0xFF65DE69),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFECEFF4),
    surface = Color(0xFF171B22),
    onSurface = Color(0xFFECEFF4),
    onSurfaceVariant = Color(0xFFB4BAC6),
    error = Color(0xFFFF6B6B),
    outlineVariant = Color(0x33FFFFFF)
)

/**
 * পুরো অ্যাপের মূল কাঠামো: ব্যাকগ্রাউন্ড আঁকে, থিম (লাইট/ডার্ক) ঠিক করে, গ্লাস-সারফেসগুলোকে ব্যাকড্রপ জোগায়।
 * সেটিং বদলালে সাথে সাথে (রিস্টার্ট ছাড়াই) প্রয়োগ হয়।
 */
@Composable
fun AppRoot(content: @Composable () -> Unit) {
    val ap by Appearance.state.collectAsState()
    val systemDark = isSystemInDarkTheme()

    // ভারী কাজ (ছবি ডিকোড + ব্লার) ব্যাকগ্রাউন্ড থ্রেডে
    val data by produceState(BackdropData.EMPTY, ap.source, ap.imageVersion, ap.blur) {
        value = withContext(Dispatchers.Default) { Appearance.buildBackdropData(ap) }
    }

    val gradient = Appearance.gradients.getOrNull(ap.gradientIndex)
    val baseLum: Float = when (ap.source) {
        BgSource.IMAGE -> data.luminance
        BgSource.GRADIENT -> gradient?.let { Appearance.luminance(it.colors) } ?: -1f
        BgSource.DEFAULT -> -1f
    }

    // উজ্জ্বলতা-ওভারলের পর ব্যাকগ্রাউন্ডের আনুমানিক উজ্জ্বলতা (dynamic background sampling)
    val b = ap.brightness
    val lum: Float = when {
        baseLum < 0f -> -1f
        b < 0f -> baseLum * (1f + b)
        else -> baseLum * (1f - b) + b
    }

    val dark = when (ap.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> if (lum < 0f) systemDark else lum < 0.5f
    }

    // লেখা পড়ার উপযোগী রাখতে স্বয়ংক্রিয় আবরণ (adaptive contrast)
    val scrim: Color = when {
        lum < 0f -> Color.Transparent
        !dark -> Color.White.copy(alpha = ((0.62f - lum) / (1f - lum)).coerceIn(0f, 0.75f))
        else -> Color.Black.copy(alpha = (1f - 0.38f / lum.coerceAtLeast(0.01f)).coerceIn(0f, 0.75f))
    }
    val overlay: Color = when {
        b < 0f -> Color.Black.copy(alpha = -b)
        b > 0f -> Color.White.copy(alpha = b)
        else -> Color.Transparent
    }

    val scheme = if (dark) DarkScheme else LightScheme
    val baseColors = when {
        ap.source == BgSource.GRADIENT && gradient != null -> gradient.colors
        dark -> Appearance.defaultDark
        else -> Appearance.defaultLight
    }

    var rootSize by remember { mutableStateOf(Size.Zero) }
    val backdrop = Backdrop(
        source = ap.source,
        baseColors = baseColors,
        accent = scheme.primary,
        displayImage = data.display,
        glassImage = data.glass,
        fit = ap.fit,
        overlay = overlay,
        scrim = scrim,
        rootSize = rootSize
    )

    // স্ট্যাটাস/নেভিগেশন বারের আইকনের রঙ থিম অনুযায়ী
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalBackdrop provides backdrop,
            LocalIsDark provides dark,
            LocalGlassAlpha provides ap.glassAlpha,
            LocalContentColor provides scheme.onBackground
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { rootSize = Size(it.width.toFloat(), it.height.toFloat()) }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawBackdrop(backdrop, size.width, size.height, glass = false)
                }
                // ব্যাকগ্রাউন্ড সিস্টেম বারের পেছনেও যায়; বিষয়বস্তু বারের ভেতরে থাকে
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    content()
                }
            }
        }
    }
}
