package com.unofficial.unlimcloud

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.io.File

private const val TAB_GALLERY = 0
private const val TAB_FOLDERS = 1
private const val TAB_DOWNLOAD = 2
private const val TAB_MENU = 3

internal fun launchFileAction(ctx: Context, a: FileAction) {
    try {
        val file = File(a.path)
        if (!file.exists()) {
            TdManager.message.value = "ফাইলটি পাওয়া যায়নি, আবার চেষ্টা করুন"
            return
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val ext = a.name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"

        val base = if (a.share) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mime) }
        }
        base.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        base.clipData = ClipData.newRawUri(a.name, uri)

        val chooser = Intent.createChooser(base, if (a.share) "শেয়ার করুন" else "খুলুন")
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        TdManager.message.value = "এই ফাইল খোলার মতো কোনো অ্যাপ নেই"
    } catch (e: Exception) {
        TdManager.message.value = "ফাইল খোলা যায়নি: ${e.message}"
    }
}

// ============================================================ নিচের ৪ ট্যাবের কাঠামো

@Composable
fun MainShell(onPick: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_GALLERY) }
    var openFolder by rememberSaveable { mutableStateOf<String?>(null) }

    val transfers by TdManager.transfers.collectAsState()
    val failedUp by TdManager.failedUploads.collectAsState()
    val failedDown by TdManager.failedDownloads.collectAsState()
    val message by TdManager.message.collectAsState()
    val pending by TdManager.pendingAction.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // অ্যাপ সামনে থাকলেই ফাইল খোলা/শেয়ার করা যায় (ব্যাকগ্রাউন্ড থেকে অ্যাক্টিভিটি শুরু নিষিদ্ধ)
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumed = true
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                resumed = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(pending, resumed) {
        val a = pending
        if (a != null && resumed) {
            TdManager.pendingAction.value = null
            launchFileAction(context, a)
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            TdManager.message.value = null
        }
    }

    BackHandler(enabled = tab == TAB_FOLDERS && openFolder != null) { openFolder = null }

    val hasFailed = failedUp.isNotEmpty() || failedDown.isNotEmpty()
    val hasActivity = transfers.isNotEmpty() || hasFailed

    Scaffold(containerColor = Color.Transparent, 
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            GlassBottomBar(
                selected = tab,
                onSelect = { i ->
                    if (i == TAB_FOLDERS && tab == TAB_FOLDERS) openFolder = null
                    tab = i
                },
                items = listOf(
                    NavEntry("Gallery") { PathIcon(AppIcons.IMAGE) },
                    NavEntry("Folders") { PathIcon(AppIcons.FOLDER) },
                    NavEntry("Download") {
                        BadgedBox(badge = {
                            if (hasActivity) {
                                Badge(
                                    containerColor = if (hasFailed) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                                )
                            }
                        }) { PathIcon(AppIcons.DOWNLOAD) }
                    },
                    NavEntry("Menu") { Icon(Icons.Default.Menu, contentDescription = null) }
                )
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                TAB_GALLERY -> FileBrowser(
                    folder = null,
                    title = "Gallery",
                    onBack = null,
                    onPick = onPick,
                    onOpenTransfers = { tab = TAB_DOWNLOAD }
                )
                TAB_FOLDERS -> {
                    val of = openFolder
                    if (of == null) {
                        FoldersScreen(
                            onOpen = { openFolder = it },
                            onOpenTransfers = { tab = TAB_DOWNLOAD }
                        )
                    } else {
                        FileBrowser(
                            folder = of,
                            title = if (of.isEmpty()) "ফোল্ডারহীন ফাইল" else of,
                            onBack = { openFolder = null },
                            onPick = onPick,
                            onOpenTransfers = { tab = TAB_DOWNLOAD }
                        )
                    }
                }
                TAB_DOWNLOAD -> DownloadsScreen()
                else -> MenuScreen()
            }
        }
    }
}

// ============================================================ ফাইল ব্রাউজার (Gallery ও ফোল্ডারের ভেতর)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileBrowser(
    folder: String?,                 // null = সব ফাইল (Gallery); নাম = সেই ফোল্ডার; "" = ফোল্ডারহীন
    title: String,
    onBack: (() -> Unit)?,
    onPick: (String) -> Unit,
    onOpenTransfers: () -> Unit
) {
    val files by TdManager.files.collectAsState()
    val loading by TdManager.loading.collectAsState()
    val hasMore by TdManager.hasMore.collectAsState()
    val filter by TdManager.currentFilter.collectAsState()
    val columns by TdManager.viewColumns.collectAsState()
    val sort by TdManager.sortMode.collectAsState()

    var viewMenuOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var queryText by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var selected by remember { mutableStateOf<CloudFile?>(null) }
    var toDelete by remember { mutableStateOf<List<CloudFile>>(emptyList()) }
    var moveTargets by remember { mutableStateOf<List<CloudFile>>(emptyList()) }
    val focus = remember { FocusRequester() }

    // ফোল্ডার দেখতে সব ফাইল লাগে; বেরিয়ে গেলে খোঁজার শব্দও মুছে ফেলা হয়
    DisposableEffect(folder) {
        if (folder != null) TdManager.setNeedAll(true)
        onDispose {
            if (folder != null) TdManager.setNeedAll(false)
            TdManager.setQuery("")
        }
    }
    LaunchedEffect(queryText) {
        delay(350)
        TdManager.setQuery(queryText.trim())
    }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }

    val visible = remember(files, sort, queryText, filter, folder) {
        var list = when (filter) {
            Filter.ALL -> files
            Filter.DOCS -> files.filter { it.kind == Kind.DOC }
            Filter.MEDIA -> files.filter { it.kind == Kind.MEDIA }
            Filter.AUDIO -> files.filter { it.kind == Kind.AUDIO }
        }
        if (folder != null) list = list.filter { it.folder == folder }
        val q = queryText.trim()
        if (q.isNotEmpty()) list = list.filter { it.name.contains(q, ignoreCase = true) }
        TdManager.applySort(list, sort)
    }
    val inSelection = selection.isNotEmpty()
    val selectedFiles = visible.filter { it.messageId in selection }
    val toggle: (Long) -> Unit = { id -> selection = if (id in selection) selection - id else selection + id }

    BackHandler(enabled = inSelection || searching) {
        if (inSelection) {
            selection = emptySet()
        } else {
            searching = false
            queryText = ""
        }
    }

    Scaffold(containerColor = Color.Transparent, 
        topBar = {
            if (inSelection) {
                GlassTopBar(
                    title = { Text("${selectedFiles.size}টি") },
                    navigationIcon = {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "নির্বাচন বাতিল")
                        }
                    },
                    actions = {
                        TextButton(onClick = { selection = visible.map { it.messageId }.toSet() }) { Text("সব") }
                        IconButton(onClick = {
                            selectedFiles.forEach { TdManager.download(it) }
                            selection = emptySet()
                        }) { PathIcon(AppIcons.DOWNLOAD) }
                        IconButton(onClick = { moveTargets = selectedFiles }) { PathIcon(AppIcons.FOLDER) }
                        IconButton(onClick = { toDelete = selectedFiles }) {
                            Icon(Icons.Default.Delete, contentDescription = "মুছুন")
                        }
                    }
                )
            } else {
                GlassTopBar(
                    title = {
                        if (searching) {
                            TextField(
                                value = queryText,
                                onValueChange = { queryText = it },
                                placeholder = { Text("ফাইলের নাম খুঁজুন") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                            )
                        } else {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "পিছনে")
                            }
                        }
                    },
                    actions = {
                        if (searching) {
                            IconButton(onClick = {
                                searching = false
                                queryText = ""
                            }) { Icon(Icons.Default.Close, contentDescription = "খোঁজা বন্ধ") }
                        } else {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Default.Search, contentDescription = "খুঁজুন")
                            }
                        }
                        TransferStatusIcon(onClick = onOpenTransfers)
                    }
                )
            }
        },
        floatingActionButton = {
            if (!inSelection) {
                GlassFab(onClick = { onPick(folder ?: "") }) {
                    Icon(Icons.Default.Add, contentDescription = "আপলোড")
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // ফিল্টার-চিপ এবং তার পরে গ্রিড/ক্রম আইকন
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        Filter.ALL to "সব",
                        Filter.DOCS to "ডকুমেন্ট",
                        Filter.MEDIA to "ছবি/ভিডিও",
                        Filter.AUDIO to "অডিও"
                    ).forEach { (f, label) ->
                        GlassChip(
                            selected = filter == f,
                            onClick = { TdManager.setFilter(f) },
                            label = { Text(label) }
                        )
                    }
                }
                Box {
                    IconButton(onClick = { viewMenuOpen = true }) { GridIcon(columns != 0) }
                    GlassMenu(expanded = viewMenuOpen, onDismissRequest = { viewMenuOpen = false }) {
                        Text(
                            "ভিউ",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        listOf(
                            0 to "তালিকা",
                            2 to "গ্রিড: প্রতি লাইনে ২টি",
                            3 to "গ্রিড: প্রতি লাইনে ৩টি",
                            4 to "গ্রিড: প্রতি লাইনে ৪টি",
                            5 to "গ্রিড: প্রতি লাইনে ৫টি"
                        ).forEach { (n, label) ->
                            DropdownMenuItem(
                                text = { Text((if (columns == n) "✓  " else "     ") + label) },
                                onClick = {
                                    viewMenuOpen = false
                                    TdManager.setViewColumns(n)
                                }
                            )
                        }
                        HorizontalDivider()
                        Text(
                            "ক্রম",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        SortMode.values().forEach { mode ->
                            DropdownMenuItem(
                                text = { Text((if (sort == mode) "✓  " else "     ") + mode.label) },
                                onClick = {
                                    viewMenuOpen = false
                                    TdManager.setSort(mode)
                                }
                            )
                        }
                    }
                }
            }

            // নিচে টানলে রিফ্রেশ
            PullRefreshBox(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onRefresh = { TdManager.reload() }
            ) {
                if (loading && files.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (visible.isEmpty() && !hasMore) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                files.isEmpty() -> "কোনো ফাইল নেই। + চেপে আপলোড করুন।"
                                queryText.isBlank() -> "এখানে কোনো ফাইল নেই।"
                                else -> "\"${queryText.trim()}\" নামে কিছু পাওয়া যায়নি।"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else if (columns == 0) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(visible, key = { it.messageId }) { f ->
                            FileRow(
                                f = f,
                                isSelected = f.messageId in selection,
                                inSelection = inSelection,
                                onClick = { if (inSelection) toggle(f.messageId) else selected = f },
                                onLongClick = { toggle(f.messageId) }
                            )
                            HorizontalDivider()
                        }
                        if (hasMore) {
                            item(key = "more") {
                                LaunchedEffect(files.size) { TdManager.loadMore() }
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        gridItems(visible, key = { it.messageId }) { f ->
                            GridCell(
                                f = f,
                                columns = columns,
                                isSelected = f.messageId in selection,
                                onClick = { if (inSelection) toggle(f.messageId) else selected = f },
                                onLongClick = { toggle(f.messageId) }
                            )
                        }
                        if (hasMore) {
                            item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                                LaunchedEffect(files.size) { TdManager.loadMore() }
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- একটি ফাইলের অ্যাকশন
    selected?.let { f ->
        GlassAlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Thumb(f, Modifier.fillMaxWidth().aspectRatio(1.8f), corner = 12.dp)
                    Spacer(Modifier.height(8.dp))
                    Text("${formatSize(f.size)} • ${formatDate(f.date)}", style = MaterialTheme.typography.bodyMedium)
                    if (f.folder.isNotEmpty()) {
                        Text("ফোল্ডার: ${f.folder}", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    ActionButton("খুলুন") {
                        TdManager.download(f, DownloadAction.OPEN)
                        selected = null
                    }
                    ActionButton("শেয়ার করুন") {
                        TdManager.download(f, DownloadAction.SHARE)
                        selected = null
                    }
                    ActionButton("Downloads-এ সংরক্ষণ") {
                        TdManager.download(f, DownloadAction.SAVE)
                        selected = null
                    }
                    ActionButton("ফোল্ডারে সরান") {
                        moveTargets = listOf(f)
                        selected = null
                    }
                    ActionButton("মুছুন", danger = true) {
                        toDelete = listOf(f)
                        selected = null
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("বন্ধ") } }
        )
    }

    if (moveTargets.isNotEmpty()) {
        MoveDialog(
            targets = moveTargets,
            onDismiss = {
                moveTargets = emptyList()
                selection = emptySet()
            }
        )
    }

    if (toDelete.isNotEmpty()) {
        val list = toDelete
        GlassAlertDialog(
            onDismissRequest = { toDelete = emptyList() },
            title = { Text(if (list.size == 1) "ফাইল মুছবেন?" else "${list.size}টি ফাইল মুছবেন?") },
            text = {
                Text(
                    (if (list.size == 1) "\"${list[0].name}\"" else "নির্বাচিত ফাইলগুলো") +
                        " Telegram-এর Saved Messages থেকেও মুছে যাবে।"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    TdManager.deleteMany(list)
                    toDelete = emptyList()
                    selection = emptySet()
                }) { Text("মুছুন") }
            },
            dismissButton = { TextButton(onClick = { toDelete = emptyList() }) { Text("বাতিল") } }
        )
    }
}

// ============================================================ ফোল্ডারে সরানোর ডায়ালগ

@Composable
private fun MoveDialog(targets: List<CloudFile>, onDismiss: () -> Unit) {
    val all by TdManager.files.collectAsState()
    val custom by TdManager.customFolders.collectAsState()
    val names = remember(all, custom) { TdManager.folderNames(all, custom) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (targets.size == 1) "ফোল্ডারে সরান" else "${targets.size}টি ফাইল সরান") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (creating) {
                    OutlinedTextField(colors = glassFieldColors(), 
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("নতুন ফোল্ডারের নাম") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    ActionButton("+ নতুন ফোল্ডার") { creating = true }
                    ActionButton("ফোল্ডার ছাড়া") {
                        TdManager.moveToFolder(targets, "")
                        onDismiss()
                    }
                    names.forEach { n ->
                        ActionButton("$FOLDER_MARK $n") {
                            TdManager.moveToFolder(targets, n)
                            onDismiss()
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(onClick = {
                    val n = TdManager.createFolder(newName)
                    if (n != null) {
                        TdManager.moveToFolder(targets, n)
                        onDismiss()
                    }
                }) { Text("তৈরি ও সরান") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (creating) creating = false else onDismiss() }) {
                Text(if (creating) "পিছনে" else "বাতিল")
            }
        }
    )
}

// ============================================================ ছোট UI অংশ

@Composable
internal fun ActionButton(label: String, danger: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    f: CloudFile,
    isSelected: Boolean,
    inSelection: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (inSelection) {
            Checkbox(checked = isSelected, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
        }
        Thumb(f, Modifier.size(56.dp), corner = 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${formatSize(f.size)} • ${formatDate(f.date)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    f: CloudFile,
    columns: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Thumb(f, Modifier.fillMaxSize(), corner = 4.dp)
        // কম কলামে নাম দেখানো হয়; বেশি কলামে জায়গা কম, তাই শুধু থাম্বনেইল
        if (columns <= 3) {
            Text(
                f.name,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall
            )
        }
        if (isSelected) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)))
            Text(
                "✓",
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** ভিউ বদলের আইকন: গ্রিড হলে ২×২ বর্গ, তালিকা হলে তিনটি দাগ। */
@Composable
internal fun GridIcon(isGrid: Boolean) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(22.dp)) {
        val corner = CornerRadius(2.dp.toPx())
        if (isGrid) {
            val cell = (size.width - 4.dp.toPx()) / 2f
            for (r in 0..1) for (c in 0..1) {
                drawRoundRect(
                    color,
                    topLeft = Offset(c * (cell + 4.dp.toPx()), r * (cell + 4.dp.toPx())),
                    size = Size(cell, cell),
                    cornerRadius = corner
                )
            }
        } else {
            val h = 4.dp.toPx()
            for (i in 0..2) {
                drawRoundRect(
                    color,
                    topLeft = Offset(0f, i * (h + 3.dp.toPx())),
                    size = Size(size.width, h),
                    cornerRadius = corner
                )
            }
        }
    }
}
