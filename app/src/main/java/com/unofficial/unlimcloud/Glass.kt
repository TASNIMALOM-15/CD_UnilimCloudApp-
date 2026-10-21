package com.unofficial.unlimcloud

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// =================================================================== ব্যাকড্রপ (পুরো অ্যাপের পেছনের দৃশ্য)

/** অ্যাপের ব্যাকগ্রাউন্ডের সব তথ্য; গ্লাস-সারফেসগুলো এটি থেকেই "ঝাপসা পেছনের দৃশ্য" আঁকে। */
class Backdrop(
    val source: BgSource,
    val baseColors: List<Color>,
    val accent: Color,
    val displayImage: ImageBitmap?,
    val glassImage: ImageBitmap?,
    val fit: BgFit,
    val overlay: Color,     // ব্যবহারকারীর উজ্জ্বলতা-ওভারলে
    val scrim: Color,       // লেখা পড়ার উপযোগী রাখতে স্বয়ংক্রিয় আবরণ
    val rootSize: Size
)

val LocalBackdrop = compositionLocalOf<Backdrop?> { null }
val LocalIsDark = compositionLocalOf { false }
val LocalGlassAlpha = compositionLocalOf { 0.25f }

private fun DrawScope.drawImageFit(img: ImageBitmap, fit: BgFit, w: Float, h: Float) {
    val bw = img.width.toFloat()
    val bh = img.height.toFloat()
    val scale = when (fit) {
        BgFit.COVER -> max(w / bw, h / bh)
        BgFit.CONTAIN -> min(w / bw, h / bh)
        BgFit.CENTER -> 1f
    }
    val dw = bw * scale
    val dh = bh * scale
    drawImage(
        image = img,
        dstOffset = IntOffset(((w - dw) / 2f).roundToInt(), ((h - dh) / 2f).roundToInt()),
        dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
        filterQuality = FilterQuality.Medium
    )
}

fun DrawScope.drawBackdrop(b: Backdrop, w: Float, h: Float, glass: Boolean) {
    val full = Size(w, h)
    drawRect(Brush.linearGradient(b.baseColors, start = Offset.Zero, end = Offset(w, h)), size = full)
    if (b.source != BgSource.IMAGE) {
        // নরম আলোর দুটি ছোপ, যাতে গ্লাসের পেছনে দেখার মতো কিছু থাকে
        drawRect(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                center = Offset(w * 0.15f, h * 0.12f), radius = w * 0.9f
            ), size = full
        )
        drawRect(
            Brush.radialGradient(
                listOf(b.accent.copy(alpha = 0.28f), Color.Transparent),
                center = Offset(w * 0.9f, h * 0.85f), radius = w * 1.0f
            ), size = full
        )
    } else {
        val img = if (glass) b.glassImage else b.displayImage
        if (img != null) drawImageFit(img, b.fit, w, h)
    }
    if (b.overlay.alpha > 0f) drawRect(b.overlay, size = full)
    if (b.scrim.alpha > 0f) drawRect(b.scrim, size = full)
}

// =================================================================== গ্লাস সারফেস

/**
 * Liquid Glass: ঝাপসা পেছনের দৃশ্য + স্বচ্ছ টিন্ট + উপরের প্রতিফলন + হালকা হাইলাইট বর্ডার (+ ঐচ্ছিক গ্লো)।
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    tint: Color? = null,
    glow: Color? = null,
    strong: Boolean = false,
    withShadow: Boolean = true,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop = LocalBackdrop.current
    val dark = LocalIsDark.current
    val userAlpha = LocalGlassAlpha.current
    var pos by remember { mutableStateOf(Offset.Zero) }

    val baseTint = tint ?: if (dark) Color(0xFF1C2230) else Color.White
    var a = 0.15f + userAlpha * 1.2f
    if (strong) a += 0.25f
    if (tint != null) a += 0.1f
    val tintFinal = baseTint.copy(alpha = a.coerceIn(0.15f, 0.92f))

    val borderBrush = remember {
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.45f))
        )
    }
    val sheen = if (dark) 0.14f else 0.30f

    val shadowMod = when {
        !withShadow -> Modifier
        glow != null -> Modifier.shadow(14.dp, shape, ambientColor = glow, spotColor = glow)
        else -> Modifier.shadow(6.dp, shape)
    }

    Box(
        modifier
            .then(shadowMod)
            .clip(shape)
            .onGloballyPositioned { pos = it.positionInRoot() }
            .drawBehind {
                if (backdrop != null && backdrop.rootSize.width > 0f) {
                    translate(left = -pos.x, top = -pos.y) {
                        drawBackdrop(backdrop, backdrop.rootSize.width, backdrop.rootSize.height, glass = true)
                    }
                }
                drawRect(tintFinal)
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = sheen), Color.Transparent),
                        endY = size.height * 0.55f
                    )
                )
            }
            .border(1.dp, borderBrush, shape),
        contentAlignment = contentAlignment
    ) { content() }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(26.dp)
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

// =================================================================== বাটন, চিপ, FAB

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "glassButtonScale"
    )
    val green = MaterialTheme.colorScheme.primary
    GlassSurface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.5f
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        shape = CircleShape,
        tint = if (primary) green else null,
        glow = if (primary) green.copy(alpha = 0.6f) else null,
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompositionLocalProvider(
                LocalContentColor provides (if (primary) Color.White else LocalContentColor.current)
            ) { content() }
        }
    }
}

@Composable
fun GlassChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val green = MaterialTheme.colorScheme.primary
    GlassSurface(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        ),
        shape = CircleShape,
        tint = if (selected) green else null,
        glow = if (selected) green.copy(alpha = 0.5f) else null,
        withShadow = selected,
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            CompositionLocalProvider(
                LocalContentColor provides (if (selected) Color.White else LocalContentColor.current)
            ) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge) { label() }
            }
        }
    }
}

@Composable
fun GlassFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 450f),
        label = "glassFabScale"
    )
    val green = MaterialTheme.colorScheme.primary
    GlassSurface(
        modifier = modifier
            .size(64.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = CircleShape,
        tint = green,
        glow = green.copy(alpha = 0.7f),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
    }
}

// =================================================================== টুলবার

@Composable
fun GlassTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navigationIcon()
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)) { title() }
            }
            actions()
        }
    }
}

// =================================================================== নিচের নেভিগেশন (ভাসমান গ্লাস বার)

class NavEntry(val label: String, val icon: @Composable () -> Unit)

/** নির্বাচিত ট্যাবে আলোকিত গ্লাস-পিল, যা ট্যাব বদলালে তরলের মতো পিছলে যায়। */
@Composable
fun GlassBottomBar(selected: Int, onSelect: (Int) -> Unit, items: List<NavEntry>) {
    val green = MaterialTheme.colorScheme.primary
    val dark = LocalIsDark.current
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(70.dp),
        shape = CircleShape
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(5.dp)) {
            val itemW = maxWidth / items.size
            val offsetX by animateDpAsState(
                targetValue = itemW * selected,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = 320f),
                label = "navPill"
            )
            // আলোকিত গ্লাস-পিল
            Box(
                Modifier
                    .offset(x = offsetX)
                    .width(itemW)
                    .fillMaxHeight()
                    .shadow(10.dp, CircleShape, ambientColor = green, spotColor = green)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (dark) 0.30f else 0.80f),
                                green.copy(alpha = 0.40f)
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.65f), CircleShape)
            )
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { i, entry ->
                    val isSel = i == selected
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelect(i) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CompositionLocalProvider(
                            LocalContentColor provides LocalContentColor.current.copy(alpha = if (isSel) 1f else 0.72f)
                        ) {
                            entry.icon()
                            Spacer(Modifier.height(2.dp))
                            Text(
                                entry.label,
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

// =================================================================== ডায়ালগ ও মেনু

@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(32.dp),
                strong = true
            ) {
                Column(Modifier.padding(24.dp)) {
                    if (title != null) {
                        ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                        Spacer(Modifier.height(14.dp))
                    }
                    if (text != null) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                        Spacer(Modifier.height(14.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

/** DropdownMenu-কে স্বচ্ছ, গোলাকার গ্লাস-ধাঁচে সাজায় (পপআপ আলাদা উইন্ডো, তাই ব্লার ছাড়া স্বচ্ছ টিন্ট)। */
@Composable
fun GlassMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val dark = LocalIsDark.current
    val base = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = base.copy(
            surface = (if (dark) Color(0xFF1C2230) else Color.White).copy(alpha = 0.90f)
        ),
        shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(24.dp))
    ) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, content = content)
    }
}

// =================================================================== টেক্সট ফিল্ড

@Composable
fun glassFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White.copy(alpha = if (LocalIsDark.current) 0.08f else 0.35f),
    unfocusedContainerColor = Color.White.copy(alpha = if (LocalIsDark.current) 0.05f else 0.25f),
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f)
)
