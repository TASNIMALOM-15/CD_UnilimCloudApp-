package com.unofficial.unlimcloud

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


// ============================================================ উপরের স্ট্যাটাস আইকন

/**
 * চলমান আপলোড/ডাউনলোড থাকলে: সবুজ গোল প্রগ্রেস রিং + ভেতরে তীর (ডাউনলোডে ↓, আপলোডে ↑)।
 * সব শেষ হলে রিং পূর্ণ হয়ে একটু পরে মুছে যায়। কিছু ব্যর্থ হলে লাল (!)।
 */
@Composable
fun TransferStatusIcon(onClick: () -> Unit) {
    val transfers by TdManager.transfers.collectAsState()
    val completed by TdManager.completedCount.collectAsState()
    val failedUp by TdManager.failedUploads.collectAsState()
    val failedDown by TdManager.failedDownloads.collectAsState()

    val hasFailed = failedUp.isNotEmpty() || failedDown.isNotEmpty()
    val ups = transfers.values.count { it.upload }
    val downs = transfers.size - ups

    var lingering by remember { mutableStateOf(false) }
    var lastUp by remember { mutableStateOf(false) }
    var lastDown by remember { mutableStateOf(false) }

    LaunchedEffect(ups, downs) {
        if (ups + downs > 0) {
            lastUp = ups > 0
            lastDown = downs > 0
        }
    }
    LaunchedEffect(transfers.isEmpty()) {
        if (transfers.isEmpty()) {
            if (lingering) {
                delay(1200)      // রিং পূর্ণ অবস্থায় একটু দেখিয়ে মুছে ফেলা
                lingering = false
            }
        } else {
            lingering = true
        }
    }

    if (hasFailed) {
        IconButton(onClick = onClick) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(ErrorRed),
                contentAlignment = Alignment.Center
            ) {
                Text("!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
    } else if (transfers.isNotEmpty() || lingering) {
        val progress = if (transfers.isEmpty()) {
            1f
        } else {
            val sum = transfers.values.sumOf { TdManager.effectiveProgress(it).toDouble() }
            ((completed + sum) / (completed + transfers.size)).toFloat().coerceIn(0f, 1f)
        }
        val green = MaterialTheme.colorScheme.primary
        IconButton(onClick = onClick) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = progress,
                    modifier = Modifier.size(34.dp),
                    color = green,
                    trackColor = green.copy(alpha = 0.2f),
                    strokeWidth = 3.dp
                )
                if (lastUp && lastDown) {
                    Row {
                        PathIcon(AppIcons.ARROW_DOWN, tint = green, iconSize = 11.dp)
                        PathIcon(AppIcons.ARROW_UP, tint = green, iconSize = 11.dp)
                    }
                } else if (lastUp) {
                    PathIcon(AppIcons.ARROW_UP, tint = green, iconSize = 16.dp)
                } else {
                    PathIcon(AppIcons.ARROW_DOWN, tint = green, iconSize = 16.dp)
                }
            }
        }
    }
}

// ============================================================ Folders ট্যাব

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FoldersScreen(onOpen: (String) -> Unit, onOpenTransfers: () -> Unit) {
    val files by TdManager.files.collectAsState()
    val custom by TdManager.customFolders.collectAsState()
    val hasMore by TdManager.hasMore.collectAsState()

    // ফোল্ডার ও গণনার জন্য সব ফাইল লোড করা হয়
    DisposableEffect(Unit) {
        TdManager.setNeedAll(true)
        onDispose { TdManager.setNeedAll(false) }
    }

    val names = remember(files, custom) { TdManager.folderNames(files, custom) }
    val counts = remember(files) { files.groupingBy { it.folder }.eachCount() }
    val unfiled = counts[""] ?: 0

    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }

    Scaffold(containerColor = Color.Transparent, 
        topBar = {
            GlassTopBar(
                title = { Text("Folders") },
                actions = { TransferStatusIcon(onClick = onOpenTransfers) }
            )
        },
        floatingActionButton = {
            GlassFab(onClick = {
                newName = ""
                creating = true
            }) { Icon(Icons.Default.Add, contentDescription = "নতুন ফোল্ডার") }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (names.isEmpty() && unfiled == 0 && !hasMore) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("কোনো ফোল্ডার নেই। + চেপে নতুন ফোল্ডার বানান।", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(names, key = { "f_$it" }) { name ->
                FolderRow(
                    name = name,
                    count = counts[name] ?: 0,
                    onClick = { onOpen(name) },
                    onRename = {
                        renameText = name
                        renaming = name
                    },
                    onDelete = { deleting = name }
                )
            }
            if (unfiled > 0) {
                item(key = "unfiled") {
                    FolderRow(
                        name = "ফোল্ডারহীন ফাইল",
                        count = unfiled,
                        onClick = { onOpen("") },
                        onRename = null,
                        onDelete = null
                    )
                }
            }
            if (hasMore) {
                item(key = "loading") {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("সব ফাইল গণনা হচ্ছে…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (creating) {
        GlassAlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("নতুন ফোল্ডার") },
            text = {
                OutlinedTextField(colors = glassFieldColors(), 
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("ফোল্ডারের নাম") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (TdManager.createFolder(newName) != null) creating = false
                }) { Text("তৈরি করুন") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("বাতিল") } }
        )
    }

    renaming?.let { old ->
        GlassAlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("ফোল্ডারের নাম বদলান") },
            text = {
                OutlinedTextField(colors = glassFieldColors(), 
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    TdManager.renameFolder(old, renameText)
                    renaming = null
                }) { Text("সংরক্ষণ") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("বাতিল") } }
        )
    }

    deleting?.let { name ->
        GlassAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("\"$name\" ফোল্ডার মুছবেন?") },
            text = { Text("ভেতরের ফাইল মুছবে না, ফোল্ডারহীন হয়ে যাবে।") },
            confirmButton = {
                TextButton(onClick = {
                    TdManager.deleteFolder(name)
                    deleting = null
                }) { Text("মুছুন") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("বাতিল") } }
        )
    }
}

@Composable
private fun FolderRow(
    name: String,
    count: Int,
    onClick: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    var menu by remember { mutableStateOf(false) }
    GlassSurface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PathIcon(AppIcons.FOLDER, tint = Color(0xFFFFB300), iconSize = 40.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text("$count টি ফাইল", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onRename != null && onDelete != null) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "মেনু") }
                    GlassMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("নাম বদলান") }, onClick = {
                            menu = false
                            onRename()
                        })
                        DropdownMenuItem(text = { Text("মুছুন") }, onClick = {
                            menu = false
                            onDelete()
                        })
                    }
                }
            }
        }
    }
}
