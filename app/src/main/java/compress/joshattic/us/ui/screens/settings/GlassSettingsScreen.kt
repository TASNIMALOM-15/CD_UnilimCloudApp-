package compress.joshattic.us.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import compress.joshattic.us.R
import compress.joshattic.us.model.GlassSettings
import compress.joshattic.us.ui.components.SelectionChip
import compress.joshattic.us.ui.glass.Button
import compress.joshattic.us.ui.glass.OutlinedButton
import compress.joshattic.us.ui.glass.Surface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSettingsScreen(
    glass: GlassSettings,
    onBack: () -> Unit,
    onPickImage: () -> Unit,
    onChange: ((GlassSettings) -> GlassSettings) -> Unit,
    onReset: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.glass_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.glass_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            // Live preview: a glass card that uses exactly the current look
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        stringResource(R.string.glass_preview),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.glass_preview_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.glass_enable),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = glass.enabled, onCheckedChange = { v -> onChange { it.copy(enabled = v) } })
            }
            if (glass.isVideo) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.glass_video_sound),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = !glass.videoMuted, onCheckedChange = { on -> onChange { it.copy(videoMuted = !on) } })
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.glass_force_play), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.glass_force_play_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = glass.forcePlay, onCheckedChange = { on -> onChange { it.copy(forcePlay = on) } })
                }
            }
            Spacer(Modifier.height(12.dp))

            Button(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.glass_pick))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.glass_reset))
            }
            Spacer(Modifier.height(20.dp))

            SliderRow(stringResource(R.string.glass_blur), glass.blur, 0f..25f) { v -> onChange { it.copy(blur = v) } }
            SliderRow(stringResource(R.string.glass_brightness), glass.dim, -0.5f..0.7f) { v -> onChange { it.copy(dim = v) } }
            SliderRow(stringResource(R.string.glass_transparency), glass.alpha, 0.08f..0.5f) { v -> onChange { it.copy(alpha = v) } }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.glass_fit), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(R.string.glass_fit_cover, R.string.glass_fit_contain, R.string.glass_fit_center)
                    .forEachIndexed { index, label ->
                        SelectionChip(selected = glass.fit == index, onClick = { onChange { it.copy(fit = index) } },
                            label = stringResource(label))
                    }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.glass_text), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(R.string.glass_text_auto, R.string.glass_text_light, R.string.glass_text_dark)
                    .forEachIndexed { index, label ->
                        SelectionChip(selected = glass.textMode == index, onClick = { onChange { it.copy(textMode = index) } },
                            label = stringResource(label))
                    }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValue, valueRange = range)
}
