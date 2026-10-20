package com.unofficial.unlimcloud

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.io.File
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ helpers

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return if (i == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", v, units[i])
}

private fun formatDate(seconds: Int): String =
    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(seconds * 1000L))

@Composable
private fun ErrorText() {
    val error by TdManager.error.collectAsState()
    error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
}

// ------------------------------------------------------------ API credentials

@Composable
fun SetupScreen() {
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(painterResource(R.drawable.splash_logo), contentDescription = null, modifier = Modifier.width(180.dp))
        Spacer(Modifier.height(24.dp))
        Text("Telegram API সেটআপ", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "ব্রাউজারে my.telegram.org খুলে ফোন নম্বর দিয়ে লগইন করুন → \"API development tools\" → একটি অ্যাপ তৈরি করুন। " +
                "সেখানে পাওয়া api_id ও api_hash নিচে বসান। এগুলো শুধু আপনার ফোনেই থাকবে, গোপন রাখুন।",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = apiId,
            onValueChange = { apiId = it.filter { c -> c.isDigit() } },
            label = { Text("api_id") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiHash,
            onValueChange = { apiHash = it.trim() },
            label = { Text("api_hash") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        localError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val id = apiId.toIntOrNull()
                if (id == null || id <= 0 || apiHash.length < 8) {
                    localError = "সঠিক api_id ও api_hash দিন"
                } else {
                    localError = null
                    TdManager.saveCredentials(id, apiHash)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("সংরক্ষণ করুন") }
    }
}

// --------------------------------------------------------------------- login

@Composable
fun AuthScreen(auth: Auth) {
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val busy by TdManager.busy.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(painterResource(R.drawable.splash_logo), contentDescription = null, modifier = Modifier.width(180.dp))
        Spacer(Modifier.height(24.dp))

        when (auth) {
            Auth.WaitPhone -> {
                Text("Telegram নম্বর দিয়ে লগইন", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("দেশের কোডসহ নম্বর দিন, যেমন +8801XXXXXXXXX", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("ফোন নম্বর") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                ErrorText()
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { TdManager.submitPhone(phone) },
                    enabled = !busy && phone.length >= 6,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("কোড পাঠান") }
            }

            Auth.WaitCode -> {
                Text("ভেরিফিকেশন কোড", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Telegram অ্যাপে (অথবা SMS-এ) আসা কোডটি লিখুন।", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isDigit() } },
                    label = { Text("কোড") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                ErrorText()
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { TdManager.submitCode(code) },
                    enabled = !busy && code.length >= 4,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("নিশ্চিত করুন") }
            }

            Auth.WaitPassword -> {
                Text("Two-step verification", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("আপনার Telegram অ্যাকাউন্টের পাসওয়ার্ড দিন।", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("পাসওয়ার্ড") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                ErrorText()
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { TdManager.submitPassword(password) },
                    enabled = !busy && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("লগইন") }
            }

            else -> {
                Text(
                    "এই অ্যাকাউন্টের জন্য প্রয়োজনীয় ধাপ (যেমন নতুন অ্যাকাউন্ট খোলা বা ইমেইল ভেরিফিকেশন) অ্যাপে সমর্থিত নয়। " +
                        "আগে অফিসিয়াল Telegram অ্যাপে লগইন করা অ্যাকাউন্ট ব্যবহার করুন।",
                    style = MaterialTheme.typography.bodyMedium
                )
                ErrorText()
            }
        }

        if (busy) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { TdManager.resetCredentials() }) { Text("API credentials বদলান") }
    }
}

// ---------------------------------------------------------------------- home

private fun launchFileAction(ctx: Context, a: FileAction) {
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(onPick: () -> Unit) {
    val files by TdManager.files.collectAsState()
    val loading by TdManager.loading.collectAsState()
    val hasMore by TdManager.hasMore.collectAsState()
    val filter by TdManager.currentFilter.collectAsState()
    val transfers by TdManager.transfers.collectAsState()
    val failed by TdManager.failedUploads.collectAsState()
    val message by TdManager.message.collectAsState()
    val columns by TdManager.viewColumns.collectAsState()
    val sort by TdManager.sortMode.collectAsState()
    val pending by TdManager.pendingAction.collectAsState()
    val context = LocalContext.current

    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var viewMenuOpen by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var queryText by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var selected by remember { mutableStateOf<CloudFile?>(null) }
    var toDelete by remember { mutableStateOf<List<CloudFile>>(emptyList()) }
    val focus = remember { FocusRequester() }

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
    LaunchedEffect(queryText) {
        delay(350)
        TdManager.setQuery(queryText.trim())
    }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }

    val visible = remember(files, sort, queryText) {
        val q = queryText.trim()
        val filtered = if (q.isEmpty()) files else files.filter { it.name.contains(q, ignoreCase = true) }
        TdManager.applySort(filtered, sort)
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

    Scaffold(
        topBar = {
            if (inSelection) {
                TopAppBar(
                    title = { Text("${selectedFiles.size} টি নির্বাচিত") },
                    navigationIcon = {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "নির্বাচন বাতিল")
                        }
                    },
                    actions = {
                        TextButton(onClick = { selection = visible.map { it.messageId }.toSet() }) { Text("সব") }
                        TextButton(onClick = {
                            selectedFiles.forEach { TdManager.download(it) }
                            selection = emptySet()
                        }) { Text("ডাউনলোড") }
                        IconButton(onClick = { toDelete = selectedFiles }) {
                            Icon(Icons.Default.Delete, contentDescription = "মুছুন")
                        }
                    }
                )
            } else {
                TopAppBar(
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
                            Text("Unlim Cloud")
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
                        Box {
                            IconButton(onClick = { viewMenuOpen = true }) { GridIcon(columns != 0) }
                            DropdownMenu(expanded = viewMenuOpen, onDismissRequest = { viewMenuOpen = false }) {
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
                        IconButton(onClick = { TdManager.reload() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "রিফ্রেশ")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "মেনু")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("লগআউট") },
                                    onClick = {
                                        menuOpen = false
                                        confirmLogout = true
                                    }
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!inSelection) {
                FloatingActionButton(onClick = onPick) {
                    Icon(Icons.Default.Add, contentDescription = "আপলোড")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    Filter.ALL to "সব",
                    Filter.DOCS to "ডকুমেন্ট",
                    Filter.MEDIA to "ছবি/ভিডিও",
                    Filter.AUDIO to "অডিও"
                ).forEach { (f, label) ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { TdManager.setFilter(f) },
                        label = { Text(label) }
                    )
                }
            }

            transfers.values.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (t.preparing) "ফাইল প্রস্তুত হচ্ছে: " else if (t.upload) "আপলোড: " else "ডাউনলোড: ") + t.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                        LinearProgressIndicator(progress = t.progress, modifier = Modifier.fillMaxWidth())
                    }
                    IconButton(onClick = { TdManager.cancelTransfer(t) }) {
                        Icon(Icons.Default.Close, contentDescription = "বাতিল")
                    }
                }
            }

            failed.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "ব্যর্থ: " + f.name,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { TdManager.retryUpload(f) }) { Text("আবার চেষ্টা") }
                    TextButton(onClick = { TdManager.discardFailed(f) }) { Text("বাদ দিন") }
                }
            }

            if (loading && files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("কোনো ফাইল নেই। + চেপে আপলোড করুন।", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (visible.isEmpty() && !hasMore) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("\"${queryText.trim()}\" নামে কিছু পাওয়া যায়নি।", style = MaterialTheme.typography.bodyMedium)
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

    // ---- একটি ফাইলের অ্যাকশন
    selected?.let { f ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Thumb(f, Modifier.fillMaxWidth().aspectRatio(1.8f), corner = 12.dp)
                    Spacer(Modifier.height(8.dp))
                    Text("${formatSize(f.size)} • ${formatDate(f.date)}", style = MaterialTheme.typography.bodyMedium)
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
                    ActionButton("মুছুন", danger = true) {
                        toDelete = listOf(f)
                        selected = null
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("বন্ধ") } }
        )
    }

    // ---- মোছার নিশ্চিতকরণ (একটি বা একাধিক)
    if (toDelete.isNotEmpty()) {
        val list = toDelete
        AlertDialog(
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

    if (confirmLogout) {
        AlertDialog(
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
}

@Composable
private fun ActionButton(label: String, danger: Boolean = false, onClick: () -> Unit) {
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
private fun GridIcon(isGrid: Boolean) {
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
