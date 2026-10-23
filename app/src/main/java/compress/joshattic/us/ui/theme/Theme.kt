package compress.joshattic.us.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import compress.joshattic.us.ui.glass.GlassStyle
import compress.joshattic.us.ui.glass.LocalGlass
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary                = A16BadgeAppGreen,
    onPrimary              = CompressorDarkGreenBackground,
    primaryContainer       = CompressorDarkPrimaryContainer,
    onPrimaryContainer     = CompressorDarkOnPrimaryContainer,
    secondary              = CompressorDarkSecondary,
    onSecondary            = CompressorDarkOnSecondary,
    secondaryContainer     = CompressorDarkSecondaryContainer,
    onSecondaryContainer   = CompressorDarkOnSecondaryContainer,
    tertiary               = CompressorDarkTertiary,
    onTertiary             = CompressorDarkOnTertiary,
    tertiaryContainer      = CompressorDarkTertiaryContainer,
    onTertiaryContainer    = CompressorDarkOnTertiaryContainer,
    background             = CompressorDarkGreenBackground,
    surface                = CompressorDarkGreenSurface,
    surfaceContainer       = CompressorDarkGreenSurfaceContainer,
    surfaceContainerHigh   = CompressorDarkGreenSurfaceContainerHigh,
    surfaceContainerHighest= CompressorDarkGreenSurfaceContainerHighest,
    surfaceContainerLow    = CompressorDarkGreenSurfaceContainerLow,
    onBackground           = CompressorDarkGreenOnSurface,
    onSurface              = CompressorDarkGreenOnSurface,
    onSurfaceVariant       = CompressorDarkGreenOnSurfaceVariant,
    outline                = CompressorDarkGreenOutline,
    outlineVariant         = CompressorDarkGreenOutlineVariant,
    surfaceTint            = A16BadgeAppGreen,
)

private val LightColorScheme = lightColorScheme(
    primary                = A16BadgeAppGreen,
    onPrimary              = A16BadgeAppGreenOnBadge,
    primaryContainer       = CompressorLightPrimaryContainer,
    onPrimaryContainer     = CompressorLightOnPrimaryContainer,
    secondary              = CompressorLightSecondary,
    onSecondary            = CompressorLightOnSecondary,
    secondaryContainer     = CompressorLightSecondaryContainer,
    onSecondaryContainer   = CompressorLightOnSecondaryContainer,
    tertiary               = CompressorLightTertiary,
    onTertiary             = CompressorLightOnTertiary,
    tertiaryContainer      = CompressorLightTertiaryContainer,
    onTertiaryContainer    = CompressorLightOnTertiaryContainer,
    background             = CompressorLightGreenBackground,
    surface                = CompressorLightGreenSurface,
    surfaceContainer       = CompressorLightGreenSurfaceContainer,
    surfaceContainerHigh   = CompressorLightGreenSurfaceContainerHigh,
    surfaceContainerHighest= CompressorLightGreenSurfaceContainerHighest,
    surfaceContainerLow    = CompressorLightGreenSurfaceContainerLow,
    onBackground           = CompressorLightGreenOnSurface,
    onSurface              = CompressorLightGreenOnSurface,
    onSurfaceVariant       = CompressorLightGreenOnSurfaceVariant,
    outline                = CompressorLightGreenOutline,
    outlineVariant         = CompressorLightGreenOutlineVariant,
    surfaceTint            = A16BadgeAppGreen,
)

@Composable
fun CompressorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    seedColor: Int? = null,
    glass: GlassStyle = GlassStyle(),
    content: @Composable () -> Unit
) {
    // With Liquid Glass on, the picture decides if text is light or dark (adaptive contrast).
    val isDark = if (glass.enabled) glass.dark else darkTheme
    val baseScheme = when {
        seedColor != null -> schemeFromSeed(seedColor, isDark)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val dynamicScheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (!isDark) {
                val lightAccentBg = dynamicScheme.surfaceTint.copy(alpha = 0.08f).compositeOver(dynamicScheme.surfaceContainerLow)
                dynamicScheme.copy(
                    background = lightAccentBg,
                    surface = lightAccentBg
                )
            } else dynamicScheme
        }
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }

    val colorScheme = if (glass.enabled) glassScheme(baseScheme, glass) else baseScheme
    val style = if (glass.enabled) {
        glass.copy(dialogColor = baseScheme.surfaceContainerHigh.copy(alpha = 0.90f))
    } else glass

    val view = LocalView.current
    if (glass.enabled && !view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(LocalGlass provides style) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Makes every surface see-through (tinted glass), so the picture shows behind the whole app. */
private fun glassScheme(base: ColorScheme, g: GlassStyle): ColorScheme {
    fun tint(mult: Float) = g.tint.copy(alpha = (g.alpha * mult).coerceIn(0.04f, 0.9f))
    return base.copy(
        background = Color.Transparent,
        surface = Color.Transparent,
        surfaceContainerLowest = tint(0.4f),
        surfaceContainerLow = tint(0.8f),
        surfaceContainer = tint(1.0f),
        surfaceContainerHigh = tint(1.35f),
        surfaceContainerHighest = tint(1.7f),
        surfaceVariant = tint(1.2f),
        primaryContainer = base.primaryContainer.copy(alpha = 0.62f),
        secondaryContainer = base.secondaryContainer.copy(alpha = 0.62f),
        tertiaryContainer = base.tertiaryContainer.copy(alpha = 0.62f)
    )
}