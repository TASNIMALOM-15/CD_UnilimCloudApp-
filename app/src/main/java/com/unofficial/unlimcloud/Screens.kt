package com.unofficial.unlimcloud

import androidx.compose.foundation.shape.RoundedCornerShape
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

internal fun formatSize(bytes: Long): String {
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

internal fun formatDate(seconds: Int): String =
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
        GlassSurface(shape = RoundedCornerShape(28.dp)) {
            Image(painterResource(R.drawable.splash_logo), contentDescription = null, modifier = Modifier.padding(20.dp).width(180.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Telegram API সেটআপ", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "ব্রাউজারে my.telegram.org খুলে ফোন নম্বর দিয়ে লগইন করুন → \"API development tools\" → একটি অ্যাপ তৈরি করুন। " +
                "সেখানে পাওয়া api_id ও api_hash নিচে বসান। এগুলো শুধু আপনার ফোনেই থাকবে, গোপন রাখুন।",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(colors = glassFieldColors(), 
            value = apiId,
            onValueChange = { apiId = it.filter { c -> c.isDigit() } },
            label = { Text("api_id") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(colors = glassFieldColors(), 
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
        GlassButton(primary = true, 
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
        GlassSurface(shape = RoundedCornerShape(28.dp)) {
            Image(painterResource(R.drawable.splash_logo), contentDescription = null, modifier = Modifier.padding(20.dp).width(180.dp))
        }
        Spacer(Modifier.height(24.dp))

        when (auth) {
            Auth.WaitPhone -> {
                Text("Telegram নম্বর দিয়ে লগইন", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("দেশের কোডসহ নম্বর দিন, যেমন +8801XXXXXXXXX", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(colors = glassFieldColors(), 
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("ফোন নম্বর") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                ErrorText()
                Spacer(Modifier.height(16.dp))
                GlassButton(primary = true, 
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
                OutlinedTextField(colors = glassFieldColors(), 
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isDigit() } },
                    label = { Text("কোড") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                ErrorText()
                Spacer(Modifier.height(16.dp))
                GlassButton(primary = true, 
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
                OutlinedTextField(colors = glassFieldColors(), 
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
                GlassButton(primary = true, 
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
