package com.unofficial.unlimcloud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================ Download ট্যাব (আপলোড + ডাউনলোড)

@Composable
fun DownloadsScreen() {
    val transfers by TdManager.transfers.collectAsState()
    val failedUp by TdManager.failedUploads.collectAsState()
    val failedDown by TdManager.failedDownloads.collectAsState()
    val done by TdManager.recentDone.collectAsState()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            GlassTopBar(
                title = { Text("Download") },
                actions = {
                    if (done.isNotEmpty()) {
                        TextButton(onClick = { TdManager.clearDone() }) { Text("তালিকা মুছুন") }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            if (transfers.isEmpty() && failedUp.isEmpty() && failedDown.isEmpty() && done.isEmpty()) {
                item {
                    GlassCard {
                        Text("কোনো ডাউনলোড বা আপলোড নেই।", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (transfers.isNotEmpty()) {
                item { SectionHeader("চলমান") }
                items(transfers.values.toList(), key = { "t_${it.fileId}" }) { t ->
                    GlassSurface(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PathIcon(
                                if (t.upload) AppIcons.ARROW_UP else AppIcons.ARROW_DOWN,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.name + if (t.preparing) "  (প্রস্তুত হচ্ছে)" else "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(progress = t.progress, modifier = Modifier.fillMaxWidth())
                            }
                            IconButton(onClick = { TdManager.cancelTransfer(t) }) {
                                Icon(Icons.Default.Close, contentDescription = "বাতিল")
                            }
                        }
                    }
                }
            }

            if (failedUp.isNotEmpty() || failedDown.isNotEmpty()) {
                item { SectionHeader("ব্যর্থ", ErrorRed) }
                items(failedUp, key = { "fu_${it.id}" }) { f ->
                    FailedRow(
                        "আপলোড ব্যর্থ: " + f.name,
                        onRetry = { TdManager.retryUpload(f) },
                        onDiscard = { TdManager.discardFailed(f) }
                    )
                }
                items(failedDown, key = { "fd_${it.file.messageId}" }) { f ->
                    FailedRow(
                        "ডাউনলোড ব্যর্থ: " + f.file.name,
                        onRetry = { TdManager.retryDownload(f) },
                        onDiscard = { TdManager.discardDownload(f) }
                    )
                }
            }

            if (done.isNotEmpty()) {
                item { SectionHeader("সম্পন্ন") }
                items(done, key = { "d_${it.time}_${it.name}" }) { d ->
                    GlassSurface(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(22.dp),
                        withShadow = false
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PathIcon(
                                if (d.upload) AppIcons.ARROW_UP else AppIcons.ARROW_DOWN,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    (if (d.upload) "আপলোড সম্পন্ন" else "ডাউনলোড সম্পন্ন") + " • " + timeFmt.format(Date(d.time)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FailedRow(text: String, onRetry: () -> Unit, onDiscard: () -> Unit) {
    GlassSurface(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(ErrorRed),
                contentAlignment = Alignment.Center
            ) { Text("!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = ErrorRed,
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onRetry) { Text("আবার চেষ্টা") }
            TextButton(onClick = onDiscard) { Text("বাদ দিন") }
        }
    }
}
