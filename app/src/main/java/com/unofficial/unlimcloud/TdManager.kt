package com.unofficial.unlimcloud

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

enum class Auth { NeedCredentials, Starting, WaitPhone, WaitCode, WaitPassword, Ready, Unsupported }

enum class Filter { ALL, DOCS, MEDIA, AUDIO }

enum class Kind { DOC, MEDIA, AUDIO }

enum class SortMode(val label: String) {
    DATE_DESC("নতুন আগে"),
    DATE_ASC("পুরনো আগে"),
    NAME_ASC("নাম A → Z"),
    NAME_DESC("নাম Z → A"),
    SIZE_DESC("বড় আগে"),
    SIZE_ASC("ছোট আগে")
}

/** ডাউনলোড শেষে কী করা হবে */
enum class DownloadAction { SAVE, OPEN, SHARE }

private class DownloadJob(val name: String, val action: DownloadAction)

/** ডাউনলোড শেষ হলে UI-কে ফাইল খুলতে/শেয়ার করতে বলার অনুরোধ */
data class FileAction(val path: String, val name: String, val share: Boolean)

data class CloudFile(
    val messageId: Long,
    val fileId: Int,
    val name: String,
    val size: Long,
    val date: Int,
    val kind: Kind,
    val mini: ByteArray? = null,     // মেসেজের সাথেই আসা অতি ছোট (কম রেজোলিউশনের) প্রিভিউ
    val thumbFileId: Int = 0,        // আসল থাম্বনেইল ফাইলের আইডি (০ = নেই)
    val thumbPath: String = ""       // থাম্বনেইল আগে থেকেই নামানো থাকলে তার পাথ
)

data class Transfer(
    val fileId: Int,
    val name: String,
    val progress: Float,
    val upload: Boolean,
    val messageId: Long = 0L,      // আপলোডের ক্ষেত্রে অস্থায়ী মেসেজ আইডি
    val preparing: Boolean = false // true হলে ফাইল এখনো ক্যাশে কপি হচ্ছে/অপেক্ষায়
)

data class FailedUpload(val id: Long, val name: String, val dirPath: String)

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
    val failedUploads = MutableStateFlow<List<FailedUpload>>(emptyList())

    /** থাম্বনেইল: fileId -> লোকাল পাথ (নামানো শেষ হলে) */
    val thumbs = MutableStateFlow<Map<Int, String>>(emptyMap())

    /** ভিউ: 0 = তালিকা, ২–৫ = প্রতি লাইনে কতটি গ্রিড কলাম */
    val viewColumns = MutableStateFlow(0)

    val sortMode = MutableStateFlow(SortMode.DATE_DESC)
    val query = MutableStateFlow("")
    val pendingAction = MutableStateFlow<FileAction?>(null)

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

    private val downloads = ConcurrentHashMap<Int, DownloadJob>()  // fileId -> কাজ
    private var loadAll = false   // সাজানো/খোঁজার সময় সব পেজ লোড করা হয়
    private val uploadFileIds = ConcurrentHashMap<Long, Int>()    // temp messageId -> fileId
    private val uploadTempDirs = ConcurrentHashMap<Long, File>()  // temp messageId -> temp dir

    private const val PAGE_SIZE = 50

    // ফাইল কপি একটার পর একটা চলে, যাতে ফোনের স্টোরেজে চাপ না পড়ে
    private val copyExecutor = Executors.newSingleThreadExecutor()
    private val prepCounter = AtomicInteger(0)
    private val cancelledPreps: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val thumbRequested: MutableSet<Int> = ConcurrentHashMap.newKeySet()

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
        cleanOldUploadCache()
        viewColumns.value = prefs.getInt("view_columns", 3)
        sortMode.value = try {
            SortMode.valueOf(prefs.getString("sort_mode", SortMode.DATE_DESC.name) ?: SortMode.DATE_DESC.name)
        } catch (e: Exception) {
            SortMode.DATE_DESC
        }
        loadAll = sortMode.value != SortMode.DATE_DESC
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

    private fun send(
        function: TdApi.Function<*>,
        onError: (() -> Unit)? = null,
        callback: ((TdApi.Object) -> Unit)? = null
    ) {
        client?.send(function, Client.ResultHandler { obj ->
            if (obj is TdApi.Error) {
                reportError(obj)
                onError?.invoke()
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
                val tempId = obj.oldMessageId
                val fid = uploadFileIds[tempId]
                val name = fid?.let { transfers.value[it]?.name } ?: "file"
                val dir = uploadTempDirs[tempId]
                finishUpload(tempId, deleteFiles = false)   // আবার চেষ্টার জন্য ফাইল রেখে দেওয়া
                if (dir != null) failedUploads.update { it + FailedUpload(tempId, name, dir.absolutePath) }
                // ব্যর্থ মেসেজটি চ্যাট থেকে সরিয়ে ফেলা (চেষ্টা করা হয়, না পারলেও ক্ষতি নেই)
                send(TdApi.DeleteMessages().apply {
                    chatId = this@TdManager.chatId
                    messageIds = longArrayOf(obj.message.id)
                    revoke = true
                })
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
                failedUploads.value = emptyList()
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
            limit = if (loadAll) 100 else PAGE_SIZE
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
            val ready = parsed.filter { it.thumbFileId != 0 && it.thumbPath.isNotEmpty() }
            if (ready.isNotEmpty()) {
                thumbs.update { m -> m + ready.associate { it.thumbFileId to it.thumbPath } }
            }
            files.update { old -> if (reset) parsed else old + parsed }
            loading.value = false
            // সাজানো/খোঁজার জন্য সব ফাইল দরকার, তাই বাকি পেজ নিজে থেকেই লোড হয়
            if (loadAll && hasMore.value) loadPage(oldestMessageId, gen, false)
        }
    }

    // ------------------------------------------------------ search & sort

    fun setSort(mode: SortMode) {
        sortMode.value = mode
        prefs.edit().putString("sort_mode", mode.name).apply()
        refreshLoadAll()
    }

    /** খোঁজার শব্দ (ফিল্টার UI-তেই হয়; এখানে শুধু সব ফাইল লোড করার সিদ্ধান্ত) */
    fun setQuery(q: String) {
        if (query.value == q) return
        query.value = q
        refreshLoadAll()
    }

    private fun refreshLoadAll() {
        loadAll = sortMode.value != SortMode.DATE_DESC || query.value.isNotEmpty()
        if (loadAll) loadMore()
    }

    fun applySort(list: List<CloudFile>, mode: SortMode): List<CloudFile> = when (mode) {
        SortMode.DATE_DESC -> list.sortedWith(compareByDescending<CloudFile> { it.date }.thenByDescending { it.messageId })
        SortMode.DATE_ASC -> list.sortedWith(compareBy<CloudFile> { it.date }.thenBy { it.messageId })
        SortMode.NAME_ASC -> list.sortedBy { it.name.lowercase() }
        SortMode.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
        SortMode.SIZE_DESC -> list.sortedByDescending { it.size }
        SortMode.SIZE_ASC -> list.sortedBy { it.size }
    }

    private fun sizeOf(f: TdApi.File): Long = if (f.size > 0) f.size else f.expectedSize

    private fun build(
        m: TdApi.Message,
        file: TdApi.File,
        name: String,
        kind: Kind,
        mini: TdApi.Minithumbnail?,
        thumb: TdApi.File?
    ): CloudFile {
        val ready = thumb != null && thumb.local.isDownloadingCompleted && thumb.local.path.isNotEmpty()
        return CloudFile(
            messageId = m.id,
            fileId = file.id,
            name = name,
            size = sizeOf(file),
            date = m.date,
            kind = kind,
            mini = mini?.data,
            thumbFileId = thumb?.id ?: 0,
            thumbPath = if (ready) thumb!!.local.path else ""
        )
    }

    private fun toCloudFile(m: TdApi.Message): CloudFile? {
        return when (val c = m.content) {
            is TdApi.MessageDocument -> {
                val d = c.document
                build(m, d.document, d.fileName.ifBlank { "file_${m.id}" }, Kind.DOC, d.minithumbnail, d.thumbnail?.file)
            }
            is TdApi.MessagePhoto -> {
                val sizes = c.photo.sizes
                val best = sizes.maxByOrNull { it.width } ?: return null
                // গ্রিডের জন্য যথেষ্ট ছোট কিন্তু ঝাপসা নয়: ৩০০px বা তার বেশি সবচেয়ে ছোটটি
                val small = sizes.filter { it.width >= 300 }.minByOrNull { it.width } ?: best
                build(m, best.photo, "photo_${m.date}_${m.id % 100000}.jpg", Kind.MEDIA, c.photo.minithumbnail, small.photo)
            }
            is TdApi.MessageVideo -> {
                val v = c.video
                build(m, v.video, v.fileName.ifBlank { "video_${m.date}_${m.id % 100000}.mp4" }, Kind.MEDIA, v.minithumbnail, v.thumbnail?.file)
            }
            is TdApi.MessageAnimation -> {
                val a = c.animation
                build(m, a.animation, a.fileName.ifBlank { "animation_${m.id % 100000}.mp4" }, Kind.MEDIA, a.minithumbnail, a.thumbnail?.file)
            }
            is TdApi.MessageAudio -> {
                val a = c.audio
                build(m, a.audio, a.fileName.ifBlank { "audio_${m.id % 100000}.mp3" }, Kind.AUDIO, a.albumCoverMinithumbnail, a.albumCoverThumbnail?.file)
            }
            is TdApi.MessageVoiceNote -> {
                build(m, c.voiceNote.voice, "voice_${m.date}_${m.id % 100000}.ogg", Kind.AUDIO, null, null)
            }
            else -> null
        }
    }

    // -------------------------------------------------------------- thumbnails

    /** পর্দায় দেখা যাওয়া আইটেমের থাম্বনেইল নামায়। বারবার ডাকলেও একবারই নামে। */
    fun requestThumb(fileId: Int) {
        if (fileId == 0 || thumbs.value.containsKey(fileId)) return
        if (!thumbRequested.add(fileId)) return
        val req = TdApi.DownloadFile().apply {
            this.fileId = fileId
            priority = 24          // সম্পূর্ণ ফাইল ডাউনলোড (১৬)-এর আগে
            offset = 0
            limit = 0
            synchronous = false
        }
        send(req, onError = { thumbRequested.remove(fileId) }) { obj ->
            if (obj is TdApi.File) onThumbFile(obj)
        }
    }

    private fun onThumbFile(f: TdApi.File) {
        if (f.local.isDownloadingCompleted && f.local.path.isNotEmpty()) {
            thumbs.update { it + (f.id to f.local.path) }
        }
    }

    fun setViewColumns(n: Int) {
        viewColumns.value = n
        prefs.edit().putInt("view_columns", n).apply()
    }

    // ----------------------------------------------------------------- upload

    fun upload(resolver: ContentResolver, uri: Uri) {
        if (chatId == 0L) {
            message.value = "এখনো প্রস্তুত নয়, একটু পরে চেষ্টা করুন"
            return
        }
        val (rawName, size) = queryNameAndSize(resolver, uri)
        val name = sanitize(rawName)
        val prepId = -prepCounter.incrementAndGet()
        // কপি শুরুর আগেই তালিকায় দেখানো হয় (অপেক্ষায় থাকলেও), আর সার্ভিস চালু হয়
        addTransfer(Transfer(prepId, name, 0f, true, 0L, preparing = true))

        copyExecutor.execute {
            var dirRef: File? = null
            try {
                if (cancelledPreps.remove(prepId)) return@execute

                if (size > 0) {
                    val free = StatFs(appContext.cacheDir.absolutePath).availableBytes
                    if (free < size + 50L * 1024 * 1024) {
                        transfers.update { it - prepId }
                        message.value = "ফোনে পর্যাপ্ত খালি জায়গা নেই: $name"
                        return@execute
                    }
                }

                val dir = File(appContext.cacheDir, "uploads/${System.nanoTime()}").apply { mkdirs() }
                dirRef = dir
                val dst = File(dir, name)

                var copied = 0L
                var lastPct = -1
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dst).use { out ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            if (cancelledPreps.contains(prepId)) break
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (size > 0) {
                                val pct = (copied * 100 / size).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    setPrepProgress(prepId, copied.toFloat() / size)
                                }
                            }
                        }
                    }
                } ?: throw IllegalStateException("ফাইল খোলা যায়নি")

                if (cancelledPreps.remove(prepId)) {
                    dir.deleteRecursively()
                    return@execute
                }
                sendFile(dir, dst, name, prepId)
            } catch (e: Exception) {
                transfers.update { it - prepId }
                dirRef?.deleteRecursively()
                message.value = "আপলোড শুরু করা যায়নি: ${e.message}"
            }
        }
    }

    private fun setPrepProgress(id: Int, p: Float) {
        transfers.update { m ->
            val t = m[id]
            if (t != null) m + (id to t.copy(progress = p)) else m
        }
    }

    private fun sendFile(dir: File, dst: File, name: String, prepId: Int? = null) {
        // নতুন TDLib-এ: InputMessageDocument -> InputDocument -> InputFile
        val inner = TdApi.InputDocument().apply {
            document = TdApi.InputFileLocal().apply { path = dst.absolutePath }
            disableContentTypeDetection = true
        }
        val content = TdApi.InputMessageDocument().apply {
            document = inner
        }
        val req = TdApi.SendMessage().apply {
            chatId = this@TdManager.chatId
            inputMessageContent = content
        }
        send(req, onError = {
            prepId?.let { id -> transfers.update { it - id } }
            dir.deleteRecursively()
        }) { obj ->
            if (obj is TdApi.Message) {
                val c = obj.content
                if (c is TdApi.MessageDocument) {
                    val fid = c.document.document.id
                    uploadFileIds[obj.id] = fid
                    uploadTempDirs[obj.id] = dir
                    addTransfer(Transfer(fid, name, 0f, true, obj.id))
                }
            }
            // প্রকৃত আপলোড নিবন্ধনের পরই "প্রস্তুত হচ্ছে" সারিটি সরানো হয়
            prepId?.let { id -> transfers.update { it - id } }
        }
    }

    private fun cleanOldUploadCache() {
        Thread {
            try {
                val limit = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
                File(appContext.cacheDir, "uploads").listFiles()?.forEach {
                    if (it.lastModified() < limit) it.deleteRecursively()
                }
            } catch (e: Exception) {
                // গুরুত্বপূর্ণ নয়
            }
        }.start()
    }

    private fun finishUpload(tempMessageId: Long, deleteFiles: Boolean = true) {
        uploadFileIds.remove(tempMessageId)?.let { fid -> transfers.update { it - fid } }
        val dir = uploadTempDirs.remove(tempMessageId)
        if (deleteFiles) dir?.deleteRecursively()
    }

    /** ব্যর্থ আপলোড আবার পাঠানো */
    fun retryUpload(f: FailedUpload) {
        failedUploads.update { list -> list.filterNot { it.id == f.id } }
        Thread {
            val dir = File(f.dirPath)
            val file = dir.listFiles()?.firstOrNull()
            if (file == null || !file.exists()) {
                message.value = "ফাইলটি আর পাওয়া যাচ্ছে না, আবার নির্বাচন করুন"
            } else {
                sendFile(dir, file, f.name)
            }
        }.start()
    }

    fun discardFailed(f: FailedUpload) {
        failedUploads.update { list -> list.filterNot { it.id == f.id } }
        File(f.dirPath).deleteRecursively()
    }

    // ---------------------------------------------------------- transfers

    private fun addTransfer(t: Transfer) {
        val wasEmpty = transfers.value.isEmpty()
        transfers.update { it + (t.fileId to t) }
        if (wasEmpty) startTransferService()
    }

    private fun startTransferService() {
        try {
            ContextCompat.startForegroundService(appContext, Intent(appContext, TransferService::class.java))
        } catch (e: Exception) {
            // ব্যাকগ্রাউন্ড থেকে শুরু করতে না দিলে অ্যাপ খোলা থাকা অবস্থায় আপলোড চলবে
        }
    }

    fun cancelTransfer(t: Transfer) {
        if (t.preparing) {
            cancelledPreps.add(t.fileId)
            transfers.update { it - t.fileId }
            return
        }
        if (t.upload) {
            send(TdApi.DeleteMessages().apply {
                chatId = this@TdManager.chatId
                messageIds = longArrayOf(t.messageId)
                revoke = true
            })
            finishUpload(t.messageId)
        } else {
            downloads.remove(t.fileId)
            transfers.update { it - t.fileId }
            send(TdApi.CancelDownloadFile().apply {
                fileId = t.fileId
                onlyIfPending = false
            })
        }
    }

    fun cancelAll() {
        transfers.value.values.toList().forEach { cancelTransfer(it) }
    }

    private fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        resolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        return (name ?: "file_${System.currentTimeMillis()}") to size
    }

    // --------------------------------------------------------------- download

    fun download(f: CloudFile, action: DownloadAction = DownloadAction.SAVE) {
        if (downloads.containsKey(f.fileId)) return
        downloads[f.fileId] = DownloadJob(f.name, action)
        addTransfer(Transfer(f.fileId, f.name, 0f, false))
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
        if (thumbRequested.contains(f.id)) onThumbFile(f)
        val total = sizeOf(f)

        // আপলোড অগ্রগতি
        val t = transfers.value[f.id]
        if (t != null && t.upload) {
            val p = if (total > 0) (f.remote.uploadedSize.toFloat() / total).coerceIn(0f, 1f) else 0f
            transfers.update { m -> if (m.containsKey(f.id)) m + (f.id to t.copy(progress = p)) else m }
            return
        }

        // ডাউনলোড অগ্রগতি
        val job = downloads[f.id] ?: return
        val name = job.name
        if (f.local.isDownloadingCompleted) {
            if (downloads.remove(f.id) != null) {
                transfers.update { it - f.id }
                when (job.action) {
                    DownloadAction.SAVE -> saveToDownloads(f.local.path, name, f.id)
                    DownloadAction.OPEN -> pendingAction.value = FileAction(f.local.path, name, false)
                    DownloadAction.SHARE -> pendingAction.value = FileAction(f.local.path, name, true)
                }
            }
        } else {
            val p = if (total > 0) (f.local.downloadedSize.toFloat() / total).coerceIn(0f, 1f) else 0f
            transfers.update { m -> if (m.containsKey(f.id)) m + (f.id to Transfer(f.id, name, p, false)) else m }
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
                Notifications.done(appContext, "ডাউনলোড সম্পূর্ণ", safe)
                // TDLib-এর ক্যাশ কপি মুছে জায়গা বাঁচানো (মেসেজ মোছে না)
                send(TdApi.DeleteFile().apply { this.fileId = fileId })
            } catch (e: Exception) {
                message.value = "সেভ করা যায়নি: ${e.message}"
            }
        }.start()
    }

    // ----------------------------------------------------------------- delete

    fun delete(f: CloudFile) = deleteMany(listOf(f))

    fun deleteMany(list: List<CloudFile>) {
        if (list.isEmpty()) return
        val ids = list.map { it.messageId }
        ids.chunked(100).forEach { chunk ->
            send(TdApi.DeleteMessages().apply {
                chatId = this@TdManager.chatId
                messageIds = chunk.toLongArray()
                revoke = true
            })
        }
        val gone = ids.toSet()
        files.update { l -> l.filterNot { it.messageId in gone } }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
}
