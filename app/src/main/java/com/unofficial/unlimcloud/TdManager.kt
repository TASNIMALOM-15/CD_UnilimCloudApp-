package com.unofficial.unlimcloud

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

enum class Auth { NeedCredentials, Starting, WaitPhone, WaitCode, WaitPassword, Ready, Unsupported }

enum class Filter { ALL, DOCS, MEDIA, AUDIO }

enum class Kind { DOC, MEDIA, AUDIO }

data class CloudFile(
    val messageId: Long,
    val fileId: Int,
    val name: String,
    val size: Long,
    val date: Int,
    val kind: Kind
)

data class Transfer(val fileId: Int, val name: String, val progress: Float, val upload: Boolean)

/**
 * TDLib (Telegram-এর অফিসিয়াল লাইব্রেরি) এর মাধ্যমে "Saved Messages"-কে ক্লাউড স্টোরেজ হিসেবে ব্যবহার করে।
 */
object TdManager {

    val auth = MutableStateFlow(Auth.Starting)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    val files = MutableStateFlow<List<CloudFile>>(emptyList())
    val loading = MutableStateFlow(false)
    val hasMore = MutableStateFlow(false)
    val currentFilter = MutableStateFlow(Filter.ALL)
    val transfers = MutableStateFlow<Map<Int, Transfer>>(emptyMap())

    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    private var client: Client? = null
    private var chatId = 0L
    private var savedApiId = 0
    private var savedApiHash = ""
    private var pendingReset = false

    private var generation = 0
    private var oldestMessageId = 0L

    private val downloads = ConcurrentHashMap<Int, String>()      // fileId -> name
    private val uploadFileIds = ConcurrentHashMap<Long, Int>()    // temp messageId -> fileId
    private val uploadTempDirs = ConcurrentHashMap<Long, File>()  // temp messageId -> temp dir

    private const val PAGE_SIZE = 50

    // ------------------------------------------------------------------ init

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("unlim_cloud", Context.MODE_PRIVATE)
        try {
            System.loadLibrary("tdjni")
        } catch (e: Throwable) {
            error.value = "TDLib লোড করা যায়নি: ${e.message}"
        }
        savedApiId = prefs.getInt("api_id", 0)
        savedApiHash = prefs.getString("api_hash", "") ?: ""
        if (savedApiId == 0 || savedApiHash.isBlank()) {
            auth.value = Auth.NeedCredentials
        } else {
            startClient()
        }
    }

    fun saveCredentials(apiId: Int, apiHash: String) {
        savedApiId = apiId
        savedApiHash = apiHash.trim()
        prefs.edit().putInt("api_id", apiId).putString("api_hash", savedApiHash).apply()
        error.value = null
        startClient()
    }

    fun resetCredentials() {
        prefs.edit().remove("api_id").remove("api_hash").apply()
        savedApiId = 0
        savedApiHash = ""
        error.value = null
        val c = client
        if (c == null) {
            auth.value = Auth.NeedCredentials
        } else {
            pendingReset = true
            send(TdApi.Close())
        }
    }

    @Synchronized
    private fun startClient() {
        if (client != null) return
        auth.value = Auth.Starting
        try {
            Client.execute(TdApi.SetLogVerbosityLevel().apply { newVerbosityLevel = 1 })
        } catch (e: Throwable) {
            // গুরুত্বপূর্ণ নয়
        }
        client = Client.create(Client.ResultHandler { obj -> onUpdate(obj) }, null, null)
    }

    // ---------------------------------------------------------------- TDLib I/O

    private fun send(function: TdApi.Function<*>, callback: ((TdApi.Object) -> Unit)? = null) {
        client?.send(function, Client.ResultHandler { obj ->
            if (obj is TdApi.Error) {
                reportError(obj)
            } else {
                callback?.invoke(obj)
            }
        })
    }

    private fun reportError(e: TdApi.Error) {
        error.value = "${e.message} (${e.code})"
        busy.value = false
        loading.value = false
    }

    private fun onUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> handleAuth(obj.authorizationState)
            is TdApi.UpdateFile -> handleFile(obj.file)
            is TdApi.UpdateMessageSendSucceeded -> {
                finishUpload(obj.oldMessageId)
                reload()
            }
            is TdApi.UpdateMessageSendFailed -> {
                finishUpload(obj.oldMessageId)
                message.value = "আপলোড ব্যর্থ: ${obj.error.message}"
            }
            is TdApi.UpdateNewMessage -> {
                // অন্য ডিভাইস/ওয়েবসাইট থেকে আসা ফাইল
                if (obj.message.chatId == chatId && obj.message.sendingState == null) reload()
            }
            is TdApi.UpdateDeleteMessages -> {
                if (obj.chatId == chatId && obj.isPermanent) reload()
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------- auth

    private fun handleAuth(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> setParameters()
            is TdApi.AuthorizationStateWaitPhoneNumber -> setAuth(Auth.WaitPhone)
            is TdApi.AuthorizationStateWaitCode -> setAuth(Auth.WaitCode)
            is TdApi.AuthorizationStateWaitPassword -> setAuth(Auth.WaitPassword)
            is TdApi.AuthorizationStateReady -> {
                setAuth(Auth.Ready)
                onReady()
            }
            is TdApi.AuthorizationStateLoggingOut -> {}
            is TdApi.AuthorizationStateClosing -> {}
            is TdApi.AuthorizationStateClosed -> {
                client = null
                chatId = 0L
                files.value = emptyList()
                transfers.value = emptyMap()
                if (pendingReset) {
                    pendingReset = false
                    auth.value = Auth.NeedCredentials
                } else {
                    Thread { startClient() }.start()
                }
            }
            else -> setAuth(Auth.Unsupported)
        }
    }

    private fun setAuth(a: Auth) {
        busy.value = false
        error.value = null
        auth.value = a
    }

    private fun setParameters() {
        val p = TdApi.SetTdlibParameters().apply {
            databaseDirectory = File(appContext.filesDir, "tdlib").absolutePath
            filesDirectory = File(appContext.filesDir, "tdfiles").absolutePath
            databaseEncryptionKey = ByteArray(0)
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = savedApiId
            apiHash = savedApiHash
            systemLanguageCode = "en"
            deviceModel = Build.MODEL
            systemVersion = Build.VERSION.RELEASE
            applicationVersion = "1.0.0"
        }
        send(p)
    }

    fun submitPhone(phone: String) {
        error.value = null
        busy.value = true
        send(TdApi.SetAuthenticationPhoneNumber().apply { phoneNumber = phone.trim() }) {
            busy.value = false
        }
    }

    fun submitCode(code: String) {
        error.value = null
        busy.value = true
        send(TdApi.CheckAuthenticationCode().apply { this.code = code.trim() }) {
            busy.value = false
        }
    }

    fun submitPassword(password: String) {
        error.value = null
        busy.value = true
        send(TdApi.CheckAuthenticationPassword().apply { this.password = password }) {
            busy.value = false
        }
    }

    fun logout() {
        send(TdApi.LogOut())
    }

    // ------------------------------------------------------------- file list

    private fun onReady() {
        send(TdApi.GetMe()) { me ->
            if (me is TdApi.User) {
                val req = TdApi.CreatePrivateChat().apply {
                    userId = me.id
                    force = false
                }
                send(req) { chat ->
                    if (chat is TdApi.Chat) {
                        chatId = chat.id
                        reload()
                    }
                }
            }
        }
    }

    fun setFilter(f: Filter) {
        if (currentFilter.value == f) return
        currentFilter.value = f
        reload()
    }

    fun reload() {
        if (chatId == 0L) return
        generation++
        oldestMessageId = 0L
        loadPage(0L, generation, reset = true)
    }

    fun loadMore() {
        if (chatId == 0L || loading.value || !hasMore.value) return
        loadPage(oldestMessageId, generation, reset = false)
    }

    private fun loadPage(from: Long, gen: Int, reset: Boolean) {
        loading.value = true
        val wanted = currentFilter.value
        val req = TdApi.SearchChatMessages().apply {
            chatId = this@TdManager.chatId
            query = ""
            fromMessageId = from
            offset = 0
            limit = PAGE_SIZE
            filter = when (wanted) {
                Filter.ALL -> TdApi.SearchMessagesFilterEmpty()
                Filter.DOCS -> TdApi.SearchMessagesFilterDocument()
                Filter.MEDIA -> TdApi.SearchMessagesFilterPhotoAndVideo()
                Filter.AUDIO -> TdApi.SearchMessagesFilterAudio()
            }
        }
        send(req) { obj ->
            if (gen != generation) return@send
            val msgs: Array<TdApi.Message> = when (obj) {
                is TdApi.FoundChatMessages -> obj.messages
                is TdApi.Messages -> obj.messages
                else -> emptyArray()
            }
            if (msgs.isNotEmpty()) oldestMessageId = msgs.last().id
            hasMore.value = msgs.isNotEmpty()
            val parsed = msgs.mapNotNull { toCloudFile(it) }
            files.update { old -> if (reset) parsed else old + parsed }
            loading.value = false
        }
    }

    private fun sizeOf(f: TdApi.File): Long = if (f.size > 0) f.size else f.expectedSize

    private fun toCloudFile(m: TdApi.Message): CloudFile? {
        return when (val c = m.content) {
            is TdApi.MessageDocument -> {
                val f = c.document.document
                CloudFile(m.id, f.id, c.document.fileName.ifBlank { "file_${m.id}" }, sizeOf(f), m.date, Kind.DOC)
            }
            is TdApi.MessagePhoto -> {
                val best = c.photo.sizes.maxByOrNull { it.width } ?: return null
                CloudFile(m.id, best.photo.id, "photo_${m.date}_${m.id % 100000}.jpg", sizeOf(best.photo), m.date, Kind.MEDIA)
            }
            is TdApi.MessageVideo -> {
                val f = c.video.video
                CloudFile(m.id, f.id, c.video.fileName.ifBlank { "video_${m.date}_${m.id % 100000}.mp4" }, sizeOf(f), m.date, Kind.MEDIA)
            }
            is TdApi.MessageAnimation -> {
                val f = c.animation.animation
                CloudFile(m.id, f.id, c.animation.fileName.ifBlank { "animation_${m.id % 100000}.mp4" }, sizeOf(f), m.date, Kind.MEDIA)
            }
            is TdApi.MessageAudio -> {
                val f = c.audio.audio
                CloudFile(m.id, f.id, c.audio.fileName.ifBlank { "audio_${m.id % 100000}.mp3" }, sizeOf(f), m.date, Kind.AUDIO)
            }
            is TdApi.MessageVoiceNote -> {
                val f = c.voiceNote.voice
                CloudFile(m.id, f.id, "voice_${m.date}_${m.id % 100000}.ogg", sizeOf(f), m.date, Kind.AUDIO)
            }
            else -> null
        }
    }

    // ----------------------------------------------------------------- upload

    fun upload(resolver: ContentResolver, uri: Uri) {
        if (chatId == 0L) {
            message.value = "এখনো প্রস্তুত নয়, একটু পরে চেষ্টা করুন"
            return
        }
        Thread {
            try {
                val name = sanitize(displayName(resolver, uri))
                val dir = File(appContext.cacheDir, "uploads/${System.nanoTime()}").apply { mkdirs() }
                val dst = File(dir, name)
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dst).use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException("ফাইল খোলা যায়নি")

                val content = TdApi.InputMessageDocument().apply {
                    document = TdApi.InputFileLocal().apply { path = dst.absolutePath }
                    disableContentTypeDetection = true
                }
                val req = TdApi.SendMessage().apply {
                    chatId = this@TdManager.chatId
                    inputMessageContent = content
                }
                send(req) { obj ->
                    if (obj is TdApi.Message) {
                        val c = obj.content
                        if (c is TdApi.MessageDocument) {
                            val fid = c.document.document.id
                            uploadFileIds[obj.id] = fid
                            uploadTempDirs[obj.id] = dir
                            transfers.update { it + (fid to Transfer(fid, name, 0f, true)) }
                        }
                    }
                }
            } catch (e: Exception) {
                message.value = "আপলোড শুরু করা যায়নি: ${e.message}"
            }
        }.start()
    }

    private fun finishUpload(tempMessageId: Long) {
        uploadFileIds.remove(tempMessageId)?.let { fid -> transfers.update { it - fid } }
        uploadTempDirs.remove(tempMessageId)?.deleteRecursively()
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) return c.getString(i) ?: "file"
            }
        }
        return "file_${System.currentTimeMillis()}"
    }

    // --------------------------------------------------------------- download

    fun download(f: CloudFile) {
        if (downloads.containsKey(f.fileId)) return
        downloads[f.fileId] = f.name
        transfers.update { it + (f.fileId to Transfer(f.fileId, f.name, 0f, false)) }
        val req = TdApi.DownloadFile().apply {
            fileId = f.fileId
            priority = 16
            offset = 0
            limit = 0
            synchronous = false
        }
        send(req) { obj ->
            if (obj is TdApi.File) handleFile(obj)
        }
    }

    private fun handleFile(f: TdApi.File) {
        val total = sizeOf(f)

        // আপলোড অগ্রগতি
        val t = transfers.value[f.id]
        if (t != null && t.upload) {
            val p = if (total > 0) (f.remote.uploadedSize.toFloat() / total).coerceIn(0f, 1f) else 0f
            transfers.update { it + (f.id to t.copy(progress = p)) }
            return
        }

        // ডাউনলোড অগ্রগতি
        val name = downloads[f.id] ?: return
        if (f.local.isDownloadingCompleted) {
            if (downloads.remove(f.id) != null) {
                transfers.update { it - f.id }
                saveToDownloads(f.local.path, name, f.id)
            }
        } else {
            val p = if (total > 0) (f.local.downloadedSize.toFloat() / total).coerceIn(0f, 1f) else 0f
            transfers.update { it + (f.id to Transfer(f.id, name, p, false)) }
        }
    }

    private fun saveToDownloads(path: String, name: String, fileId: Int) {
        Thread {
            try {
                val safe = sanitize(name)
                val ext = safe.substringAfterLast('.', "").lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                val src = File(path)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safe)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = appContext.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Downloads-এ ফাইল তৈরি করা যায়নি")
                    resolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    var dst = File(dir, safe)
                    var n = 1
                    while (dst.exists()) {
                        dst = File(dir, "${safe.substringBeforeLast('.', safe)} ($n)" +
                            (if (safe.contains('.')) "." + safe.substringAfterLast('.') else ""))
                        n++
                    }
                    src.inputStream().use { input -> FileOutputStream(dst).use { input.copyTo(it) } }
                }
                message.value = "\"$safe\" Downloads ফোল্ডারে সেভ হয়েছে"
                // TDLib-এর ক্যাশ কপি মুছে জায়গা বাঁচানো (মেসেজ মোছে না)
                send(TdApi.DeleteFile().apply { this.fileId = fileId })
            } catch (e: Exception) {
                message.value = "সেভ করা যায়নি: ${e.message}"
            }
        }.start()
    }

    // ----------------------------------------------------------------- delete

    fun delete(f: CloudFile) {
        val req = TdApi.DeleteMessages().apply {
            chatId = this@TdManager.chatId
            messageIds = longArrayOf(f.messageId)
            revoke = true
        }
        send(req) {
            files.update { list -> list.filterNot { it.messageId == f.messageId } }
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
}
