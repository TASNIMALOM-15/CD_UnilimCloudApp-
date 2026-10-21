package com.unofficial.unlimcloud

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal val ErrorRed = Color(0xFFD32F2F)

@Composable
internal fun SectionHeader(text: String, color: Color = Color.Unspecified) {
    Text(
        text,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color
    )
}

// ============================================================ Menu ট্যাব (সেটিংস ও লগআউট)

@Composable
fun MenuScreen() {
    val account by TdManager.accountName.collectAsState()
    val columns by TdManager.viewColumns.collectAsState()
    val sort by TdManager.sortMode.collectAsState()
    val context = LocalContext.current

    var confirmLogout by remember { mutableStateOf(false) }
    var confirmCreds by remember { mutableStateOf(false) }

    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { GlassTopBar(title = { Text("Menu") }) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassCard {
                SectionHeader("অ্যাকাউন্ট")
                Text(
                    if (account.isBlank()) "Telegram-এ লগইন করা আছে" else account,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "ফাইল সংরক্ষিত হয়: Telegram \"Saved Messages\"-এ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                GlassButton(onClick = { confirmLogout = true }) {
                    Text("লগআউট", color = ErrorRed)
                }
            }

            AppearanceCard()

            GlassCard {
                SectionHeader("ডিফল্ট ভিউ")
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0 to "তালিকা", 2 to "গ্রিড ২", 3 to "গ্রিড ৩", 4 to "গ্রিড ৪", 5 to "গ্রিড ৫").forEach { (n, label) ->
                        GlassChip(
                            selected = columns == n,
                            onClick = { TdManager.setViewColumns(n) },
                            label = { Text(label) }
                        )
                    }
                }
                SectionHeader("ডিফল্ট ক্রম")
                SortMode.values().forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { TdManager.setSort(m) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = sort == m, onClick = { TdManager.setSort(m) })
                        Text(m.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            GlassCard {
                SectionHeader("Telegram API")
                GlassButton(onClick = { confirmCreds = true }) { Text("api_id / api_hash বদলান") }
            }

            GlassCard {
                SectionHeader("অ্যাপ সম্পর্কে")
                Text("Unlim Cloud (Unofficial)" + if (version.isNotEmpty()) " • সংস্করণ $version" else "")
                Text(
                    "Telegram-এর অফিসিয়াল TDLib লাইব্রেরি দিয়ে চলে।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmLogout) {
        GlassAlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("লগআউট করবেন?") },
            text = { Text("এই ডিভাইস থেকে আপনার Telegram সেশন বন্ধ হবে। ফাইল মুছবে না।") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    TdManager.logout()
                }) { Text("লগআউট") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("বাতিল") } }
        )
    }

    if (confirmCreds) {
        GlassAlertDialog(
            onDismissRequest = { confirmCreds = false },
            title = { Text("API credentials বদলাবেন?") },
            text = { Text("অ্যাপ নতুন api_id ও api_hash চাইবে এবং আপনাকে আবার লগইন করতে হতে পারে।") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCreds = false
                    TdManager.resetCredentials()
                }) { Text("বদলান") }
            },
            dismissButton = { TextButton(onClick = { confirmCreds = false }) { Text("বাতিল") } }
        )
    }
}

// ============================================================ ডিজাইন ও ব্যাকগ্রাউন্ড

@Composable
private fun AppearanceCard() {
    val ap by Appearance.state.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            Appearance.setImage(uri) { ok ->
                if (!ok) TdManager.message.value = "ছবিটি ব্যাকগ্রাউন্ডে বসানো যায়নি"
            }
        }
    }

    GlassCard {
        SectionHeader("ডিজাইন ও ব্যাকগ্রাউন্ড")

        // লাইভ প্রিভিউ
        BackdropPreview(Modifier.fillMaxWidth().height(150.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            when (ap.source) {
                BgSource.DEFAULT -> "বর্তমান: ডিফল্ট"
                BgSource.GRADIENT -> "বর্তমান: গ্রেডিয়েন্ট (${Appearance.gradients.getOrNull(ap.gradientIndex)?.name ?: ""})"
                BgSource.IMAGE -> "বর্তমান: গ্যালারির ছবি"
            } + " • পরিবর্তন সাথে সাথে পুরো অ্যাপে প্রয়োগ হয়",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                primary = true
            ) { Text("গ্যালারি থেকে ছবি") }
            GlassButton(onClick = { Appearance.reset() }) { Text("রিসেট") }
        }

        SectionHeader("গ্রেডিয়েন্ট / ওয়ালপেপার")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Appearance.gradients.forEachIndexed { i, g ->
                val selected = ap.source == BgSource.GRADIENT && ap.gradientIndex == i
                val shape = RoundedCornerShape(16.dp)
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(shape)
                        .background(Brush.linearGradient(g.colors))
                        .border(
                            if (selected) 3.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                            shape
                        )
                        .clickable {
                            Appearance.update { it.copy(source = BgSource.GRADIENT, gradientIndex = i) }
                        }
                )
            }
        }

        if (ap.source == BgSource.IMAGE) {
            SectionHeader("ছবির ফিট")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BgFit.values().forEach { f ->
                    GlassChip(
                        selected = ap.fit == f,
                        onClick = { Appearance.update { it.copy(fit = f) } },
                        label = { Text(f.label) }
                    )
                }
            }
            LabeledSlider(
                label = "ব্যাকগ্রাউন্ড ব্লার",
                value = ap.blur,
                range = 0f..1f,
                shown = "${(ap.blur * 100).toInt()}%"
            ) { v -> Appearance.update { it.copy(blur = v) } }
        }

        LabeledSlider(
            label = "উজ্জ্বলতা (বামে গাঢ়, ডানে উজ্জ্বল)",
            value = ap.brightness,
            range = -0.5f..0.5f,
            shown = "${(ap.brightness * 100).toInt()}"
        ) { v -> Appearance.update { it.copy(brightness = v) } }

        LabeledSlider(
            label = "গ্লাসের স্বচ্ছতা (বামে বেশি স্বচ্ছ)",
            value = ap.glassAlpha,
            range = 0.05f..0.6f,
            shown = "${(ap.glassAlpha * 100).toInt()}%"
        ) { v -> Appearance.update { it.copy(glassAlpha = v) } }

        SectionHeader("থিম")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.values().forEach { m ->
                GlassChip(
                    selected = ap.themeMode == m,
                    onClick = { Appearance.update { it.copy(themeMode = m) } },
                    label = { Text(m.label) }
                )
            }
        }
        Text(
            "অটো: ব্যাকগ্রাউন্ডের উজ্জ্বলতা দেখে লাইট/ডার্ক বাছে। লেখা যাতে সবসময় পড়া যায়, তাই প্রয়োজনে হালকা আবরণ যোগ হয়।",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    shown: String,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(shown, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/** বর্তমান ব্যাকগ্রাউন্ডের ছোট লাইভ প্রিভিউ (সব সেটিং সহ)। */
@Composable
private fun BackdropPreview(modifier: Modifier = Modifier) {
    val backdrop = LocalBackdrop.current
    val shape = RoundedCornerShape(20.dp)
    Canvas(
        modifier
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.6f), shape)
    ) {
        if (backdrop != null) drawBackdrop(backdrop, size.width, size.height, glass = false)
    }
}
