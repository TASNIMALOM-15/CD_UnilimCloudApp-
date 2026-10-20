package compress.joshattic.us

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import compress.joshattic.us.service.CompressionNotifier
import compress.joshattic.us.ui.CompressorApp
import compress.joshattic.us.ui.theme.CompressorTheme
import compress.joshattic.us.viewmodel.CompressorViewModel

class MainActivity : ComponentActivity() {
    // The ViewModel is owned by the Application, so it survives closing the screen.
    private val viewModel: CompressorViewModel by lazy {
        ViewModelProvider(
            application as CompressorApplication,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[CompressorViewModel::class.java]
    }

    // Startup permission chain: notifications -> gallery/videos -> background (battery) access.
    private var permissionChainStarted = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askMediaPermission() }

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askBackgroundAccess() }

    private val batteryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startPermissionChain() {
        if (permissionChainStarted) return
        permissionChainStarted = true
        askNotificationPermission()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !isGranted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askMediaPermission()
        }
    }

    private fun askMediaPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (!isGranted(permission)) {
            mediaPermissionLauncher.launch(permission)
        } else {
            askBackgroundAccess()
        }
    }

    @SuppressLint("BatteryLife")
    private fun askBackgroundAccess() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                batteryLauncher.launch(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Opened from the "compression interrupted" notification: continue the unfinished job. */
    private fun handleResumeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(CompressionNotifier.EXTRA_AUTO_RESUME, false) != true) return
        intent.removeExtra(CompressionNotifier.EXTRA_AUTO_RESUME)
        CompressionNotifier.clearInterrupted(this)
        viewModel.resumeSavedJob(this)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("video/") != true) {
            return
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        if (uri != null) {
            viewModel.startSelection(this, listOf(uri))
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val isTablet = resources.getBoolean(R.bool.is_tablet)
        if (!isTablet) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        
        enableEdgeToEdge()

        // Handle the initial share intent. Later shares arrive through onNewIntent.
        handleShareIntent(intent)
        handleResumeIntent(intent)

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            CompressorTheme(seedColor = uiState.themeSeedColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CompressorApp(viewModel)
                }
            }
        }

        // Ask for everything the app needs as soon as it opens.
        window.decorView.post { startPermissionChain() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleResumeIntent(intent)
    }
}
