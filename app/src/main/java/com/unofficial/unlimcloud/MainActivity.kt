package com.unofficial.unlimcloud

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME_URL = "https://unlim-cloud.web.app"
        private const val BRIDGE_NAME = "AndroidBridge"
    }

    private lateinit var webView: WebView
    private lateinit var splash: View
    private lateinit var offlineView: View
    private lateinit var progressBar: ProgressBar

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var popupDialog: Dialog? = null
    private var popupWebView: WebView? = null
    private var hadError = false
    private var lastBackPress = 0L
    private var pendingAction: (() -> Unit)? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingAction
            pendingAction = null
            if (granted) action?.invoke() else toast(R.string.download_failed)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        splash = findViewById(R.id.splash)
        offlineView = findViewById(R.id.offline)
        progressBar = findViewById(R.id.progressBar)

        findViewById<Button>(R.id.retryButton).setOnClickListener {
            hadError = false
            offlineView.visibility = View.GONE
            splash.visibility = View.VISIBLE
            webView.loadUrl(HOME_URL)
        }

        setupWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(HOME_URL)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (offlineView.visibility == View.VISIBLE) {
                    finish()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPress < 2000) {
                        finish()
                    } else {
                        lastBackPress = now
                        toast(R.string.press_back_again)
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        // কিছু লগইন সার্ভিস WebView চিনে ফেললে সমস্যা করে, তাই "; wv" বাদ দেওয়া হলো
        s.userAgentString = s.userAgentString.replace("; wv", "")

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
    }

    private fun setupWebView() {
        configureWebView(webView)
        webView.addJavascriptInterface(DownloadBridge(), BRIDGE_NAME)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return handleUrl(request.url)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                hadError = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                if (!hadError) {
                    offlineView.visibility = View.GONE
                    splash.visibility = View.GONE
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    hadError = true
                    splash.visibility = View.GONE
                    offlineView.visibility = View.VISIBLE
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }

            // Telegram লগইন ইত্যাদির popup window এখানে সামলানো হয়
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                popupDialog?.dismiss()

                val popup = WebView(this@MainActivity)
                configureWebView(popup)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest
                    ): Boolean = handleUrl(request.url)
                }
                popup.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView) {
                        popupDialog?.dismiss()
                    }
                }

                val dialog = Dialog(
                    this@MainActivity,
                    android.R.style.Theme_DeviceDefault_Light_NoActionBar
                )
                dialog.setContentView(
                    popup,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                dialog.setOnDismissListener {
                    popupWebView?.destroy()
                    popupWebView = null
                    popupDialog = null
                }
                popupWebView = popup
                popupDialog = dialog
                dialog.show()
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                popupDialog?.dismiss()
            }
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        })
    }

    /** http/https ছাড়া অন্য লিংক (tg://, mailto:, intent: ...) অন্য অ্যাপে খোলে। */
    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https" || scheme == "about" ||
            scheme == "blob" || scheme == "data" || scheme == "javascript"
        ) {
            return false
        }
        try {
            val intent = if (scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // সংশ্লিষ্ট অ্যাপ ইনস্টল করা নেই — কিছু করার নেই
        }
        return true
    }

    // ---------------------------------------------------------------- Downloads

    private fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val mime = if (mimeType.isNullOrBlank()) "application/octet-stream" else mimeType
        val fileName = sanitize(URLUtil.guessFileName(url, contentDisposition, mime))

        if (url.startsWith("blob:")) {
            // blob: লিংক DownloadManager দিয়ে হয় না, তাই পেজের ভেতরে JS চালিয়ে ডেটা নিয়ে আসা হয়
            val js = "(function(){var x=new XMLHttpRequest();x.open('GET'," + JSONObject.quote(url) +
                ",true);x.responseType='blob';x.onload=function(){var r=new FileReader();" +
                "r.onloadend=function(){var d=r.result;" + BRIDGE_NAME +
                ".saveBase64(d.substring(d.indexOf(',')+1),x.response.type||" + JSONObject.quote(mime) +
                "," + JSONObject.quote(fileName) + ");};r.readAsDataURL(x.response);};x.send();})();"
            webView.evaluateJavascript(js, null)
            toast(R.string.download_started)
            return
        }

        withStoragePermission {
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mime)
                    .setTitle(fileName)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
                if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)

                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                toast(R.string.download_started)
            } catch (e: Exception) {
                // শেষ ভরসা: ব্রাউজারে খুলে দেওয়া
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    toast(R.string.download_failed)
                }
            }
        }
    }

    /** Android 9 ও তার নিচে Downloads-এ লিখতে runtime permission লাগে। */
    private fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingAction = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun saveFile(bytes: ByteArray, mime: String, name: String) {
        Thread {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("insert failed")
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    FileOutputStream(File(dir, name)).use { it.write(bytes) }
                }
                toast(R.string.download_saved)
            } catch (e: Exception) {
                toast(R.string.download_failed)
            }
        }.start()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "unlim_file" }

    private fun toast(resId: Int) {
        runOnUiThread { Toast.makeText(this, resId, Toast.LENGTH_LONG).show() }
    }

    /** JS থেকে blob ডেটা গ্রহণ করে ফাইল হিসেবে সেভ করে। */
    inner class DownloadBridge {
        @JavascriptInterface
        fun saveBase64(base64: String, mime: String, fileName: String) {
            val bytes = try {
                Base64.decode(base64, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                toast(R.string.download_failed)
                return
            }
            runOnUiThread {
                withStoragePermission { saveFile(bytes, mime, sanitize(fileName)) }
            }
        }
    }

    // ---------------------------------------------------------------- Lifecycle

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        popupDialog?.dismiss()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
