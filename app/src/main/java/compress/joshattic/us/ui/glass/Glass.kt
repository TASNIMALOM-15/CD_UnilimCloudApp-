package compress.joshattic.us.ui.glass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog as M3AlertDialog
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.ElevatedCard as M3ElevatedCard
import androidx.compose.material3.FilledTonalButton as M3FilledTonalButton
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.material3.OutlinedCard as M3OutlinedCard
import androidx.compose.material3.Surface as M3Surface

/** Look of the Liquid Glass UI. `enabled = false` means: behave like normal Material 3. */
@Immutable
data class GlassStyle(
    val enabled: Boolean = false,
    val alpha: Float = 0.25f,
    val tint: Color = Color.White,
    val accent: Color = Color(0xFF5BC0BE),
    val dark: Boolean = true,
    val dialogColor: Color = Color(0xE6202428)
)

val LocalGlass = staticCompositionLocalOf { GlassStyle() }

private val GlassBorderBrush = Brush.linearGradient(
    colors = listOf(Color.White.copy(alpha = 0.60f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.30f))
)

fun glassBorder(): BorderStroke = BorderStroke(1.dp, GlassBorderBrush)

/** Soft top reflection, and (optionally) an inner glow at the bottom: the "illuminated glass" look. */
fun Modifier.glassSheen(shape: Shape, accent: Color, glow: Boolean): Modifier =
    this.clip(shape).drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.55f to Color.Transparent)
        )
        if (glow) {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.30f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height),
                    radius = size.width * 0.6f
                )
            )
        }
    }

/** A solid-ish colour for custom dialogs, so text stays readable on top of the picture. */
@Composable
fun dialogSurfaceColor(normal: Color): Color {
    val g = LocalGlass.current
    return if (g.enabled) g.dialogColor else normal
}

@Composable
private fun noCardElevation(): CardElevation = CardDefaults.cardElevation(
    defaultElevation = 0.dp, pressedElevation = 0.dp, focusedElevation = 0.dp,
    hoveredElevation = 0.dp, draggedElevation = 0.dp, disabledElevation = 0.dp
)

private fun isGlassFill(color: Color) = color.alpha in 0.02f..0.95f

// ---------------------------------------------------------------- Surface

@Composable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) {
    val g = LocalGlass.current
    if (g.enabled && isGlassFill(color)) {
        M3Surface(
            modifier = modifier.glassSheen(shape, g.accent, glow = false),
            shape = shape,
            color = color,
            contentColor = contentColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = border ?: glassBorder(),
            content = content
        )
    } else {
        M3Surface(modifier, shape, color, contentColor, tonalElevation, shadowElevation, border, content)
    }
}

@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val g = LocalGlass.current
    val source = interactionSource ?: remember { MutableInteractionSource() }
    if (g.enabled && isGlassFill(color)) {
        val pressed by source.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.96f else 1f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
            label = "glassPress"
        )
        M3Surface(
            onClick = onClick,
            modifier = modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .glassSheen(shape, g.accent, glow = true),
            enabled = enabled,
            shape = shape,
            color = color,
            contentColor = contentColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = border ?: glassBorder(),
            interactionSource = source,
            content = content
        )
    } else {
        M3Surface(onClick, modifier, enabled, shape, color, contentColor, tonalElevation, shadowElevation, border, source, content)
    }
}

// ---------------------------------------------------------------- Buttons

@Composable
private fun glassPressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "glassButtonPress"
    )
    return scale
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val g = LocalGlass.current
    val source = interactionSource ?: remember { MutableInteractionSource() }
    if (!g.enabled) {
        M3Button(
            onClick = onClick, modifier = modifier, enabled = enabled,
            shape = shape ?: ButtonDefaults.shape,
            colors = colors ?: ButtonDefaults.buttonColors(),
            elevation = elevation ?: ButtonDefaults.buttonElevation(),
            border = border, contentPadding = contentPadding,
            interactionSource = source, content = content
        )
        return
    }
    val scale = glassPressScale(source)
    val on = MaterialTheme.colorScheme.onSurface
    M3Button(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .glassSheen(CircleShape, g.accent, glow = true),
        enabled = enabled,
        shape = CircleShape,
        colors = colors ?: ButtonDefaults.buttonColors(
            containerColor = g.accent.copy(alpha = 0.42f),
            contentColor = on,
            disabledContainerColor = g.tint.copy(alpha = 0.10f),
            disabledContentColor = on.copy(alpha = 0.38f)
        ),
        elevation = null,
        border = border ?: glassBorder(),
        contentPadding = contentPadding,
        interactionSource = source,
        content = content
    )
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val g = LocalGlass.current
    val source = interactionSource ?: remember { MutableInteractionSource() }
    if (!g.enabled) {
        M3OutlinedButton(
            onClick = onClick, modifier = modifier, enabled = enabled,
            shape = shape ?: ButtonDefaults.outlinedShape,
            colors = colors ?: ButtonDefaults.outlinedButtonColors(),
            elevation = elevation,
            border = border ?: ButtonDefaults.outlinedButtonBorder(enabled),
            contentPadding = contentPadding,
            interactionSource = source, content = content
        )
        return
    }
    val scale = glassPressScale(source)
    val on = MaterialTheme.colorScheme.onSurface
    M3OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .glassSheen(CircleShape, g.accent, glow = false),
        enabled = enabled,
        shape = CircleShape,
        colors = colors ?: ButtonDefaults.outlinedButtonColors(
            containerColor = g.tint.copy(alpha = (g.alpha * 0.7f).coerceIn(0.05f, 0.6f)),
            contentColor = on,
            disabledContentColor = on.copy(alpha = 0.38f)
        ),
        elevation = null,
        border = border ?: glassBorder(),
        contentPadding = contentPadding,
        interactionSource = source,
        content = content
    )
}

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val g = LocalGlass.current
    val source = interactionSource ?: remember { MutableInteractionSource() }
    if (!g.enabled) {
        M3FilledTonalButton(
            onClick = onClick, modifier = modifier, enabled = enabled,
            shape = shape ?: ButtonDefaults.filledTonalShape,
            colors = colors ?: ButtonDefaults.filledTonalButtonColors(),
            elevation = elevation ?: ButtonDefaults.filledTonalButtonElevation(),
            border = border, contentPadding = contentPadding,
            interactionSource = source, content = content
        )
        return
    }
    val scale = glassPressScale(source)
    val on = MaterialTheme.colorScheme.onSurface
    M3FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .glassSheen(CircleShape, g.accent, glow = true),
        enabled = enabled,
        shape = CircleShape,
        colors = colors ?: ButtonDefaults.filledTonalButtonColors(
            containerColor = g.tint.copy(alpha = (g.alpha * 1.4f).coerceIn(0.1f, 0.7f)),
            contentColor = on,
            disabledContainerColor = g.tint.copy(alpha = 0.08f),
            disabledContentColor = on.copy(alpha = 0.38f)
        ),
        elevation = null,
        border = border ?: glassBorder(),
        contentPadding = contentPadding,
        interactionSource = source,
        content = content
    )
}

// ---------------------------------------------------------------- Cards

@Composable
fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val g = LocalGlass.current
    if (g.enabled) {
        M3Card(
            modifier = modifier.glassSheen(shape, g.accent, glow = false),
            shape = shape, colors = colors,
            elevation = noCardElevation(),
            border = border ?: glassBorder(), content = content
        )
    } else {
        M3Card(modifier, shape, colors, elevation, border, content)
    }
}

@Composable
fun ElevatedCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.elevatedShape,
    colors: CardColors = CardDefaults.elevatedCardColors(),
    elevation: CardElevation = CardDefaults.elevatedCardElevation(),
    content: @Composable ColumnScope.() -> Unit
) {
    val g = LocalGlass.current
    if (g.enabled) {
        M3Card(
            modifier = modifier.glassSheen(shape, g.accent, glow = false),
            shape = shape, colors = colors,
            elevation = noCardElevation(),
            border = glassBorder(), content = content
        )
    } else {
        M3ElevatedCard(modifier, shape, colors, elevation, content)
    }
}

@Composable
fun OutlinedCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.outlinedShape,
    colors: CardColors = CardDefaults.outlinedCardColors(),
    elevation: CardElevation = CardDefaults.outlinedCardElevation(),
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val g = LocalGlass.current
    val source = interactionSource ?: remember { MutableInteractionSource() }
    if (g.enabled) {
        M3Card(
            onClick = onClick,
            modifier = modifier.glassSheen(shape, g.accent, glow = false),
            enabled = enabled, shape = shape, colors = colors,
            elevation = noCardElevation(),
            border = border ?: glassBorder(),
            interactionSource = source, content = content
        )
    } else {
        M3OutlinedCard(
            onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
            colors = colors, elevation = elevation,
            border = border ?: CardDefaults.outlinedCardBorder(enabled),
            interactionSource = source, content = content
        )
    }
}

// ---------------------------------------------------------------- Dialog

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties()
) {
    val g = LocalGlass.current
    M3AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = if (g.enabled) g.dialogColor else containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = if (g.enabled) 0.dp else tonalElevation,
        properties = properties
    )
}
