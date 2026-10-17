package com.example.myapplication

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var handDetectionAnalyzer: HandDetectionAnalyzer? = null
    private val gestureScrollController = GestureScrollController()
    
    private val PREFS_NAME = "ScrollablePrefs"
    private val KEY_ENABLED = "gesture_scrolling_enabled"
    
    // Create a mutable state that can be accessed by both Compose and the Analyzer
    private val _scrollStatus = mutableStateOf("Ready")
    val scrollStatusState: State<String> = _scrollStatus
    
    private val _isScrollingEnabledPersisted = mutableStateOf(false)
    val isScrollingEnabledState: State<Boolean> = _isScrollingEnabledPersisted

    private var isInForeground: Boolean = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted && !_isScrollingEnabledPersisted.value) {
            startCamera()
        } else {
            // Permission denied
        }
    }

    private val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
            val newState = checkAllPermissions()
            // We'll use a local state in the UI to react to this
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Load persisted state
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isScrollingEnabledPersisted.value =
            prefs.getBoolean(KEY_ENABLED, false) && GestureDetectionService.isRunning()
        
        // Register status callback for service updates (will be used when service starts)
        GestureDetectionService.setStatusCallback(object : GestureDetectionService.StatusCallback {
            override fun onStatusChanged(status: String) {
                runOnUiThread {
                    android.util.Log.d("MainActivity", "Status updated from service: $status")
                    _scrollStatus.value = status
                }
            }
        })

        // Auto-start service if it was previously enabled
        if (_isScrollingEnabledPersisted.value) {
            ScrollAccessibilityService.setEnabled(true)
            _scrollStatus.value = if (ScrollAccessibilityService.getInstance() == null) {
                "Accessibility Service NOT connected. Please enable it in Settings."
            } else {
                "Ready - Set your gesture mappings and show a hand pose."
            }
        }

        lifecycle.addObserver(lifecycleObserver)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        scrollStatus = scrollStatusState,
                        isScrollingEnabledState = isScrollingEnabledState,
                        onEnableScrolling = { enableScrolling() },
                        onDisableScrolling = { disableScrolling() },
                        requestCameraPermission = { requestCameraPermission() },
                        checkPermissions = { checkAllPermissions() }
                    )
                }
            }
        }
    }

    private fun checkAllPermissions(): PermissionState {
        val hasCamera = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val hasAccessibility = isAccessibilityServiceEnabled()

        return PermissionState(hasCamera, hasAccessibility)
    }

    private fun requestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startCamera()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCamera() {
        if (_isScrollingEnabledPersisted.value || !isInForeground) return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                // Do not contend with the foreground camera service.
                if (!_isScrollingEnabledPersisted.value && isInForeground) {
                    bindCameraUseCases()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStart() {
        super.onStart()
        isInForeground = true
        // Never stop a running gesture service when returning to this Activity.
        // A stale persisted flag (e.g. after process death) must not imply that
        // a background camera service is still alive.
        if (_isScrollingEnabledPersisted.value && !GestureDetectionService.isRunning()) {
            _isScrollingEnabledPersisted.value = false
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, false).apply()
            ScrollAccessibilityService.setEnabled(false)
            _scrollStatus.value = "Camera service stopped. Tap Enable to restart."
        }
    }

    override fun onStop() {
        super.onStop()
        isInForeground = false
        // No background start here: camera FGS must be created while visible.
        // The already-running service keeps the camera across apps.
        if (!_isScrollingEnabledPersisted.value) {
            cameraProvider?.unbindAll()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister status callback
        GestureDetectionService.setStatusCallback(null)
        cameraExecutor.shutdown()
        handDetectionAnalyzer?.release()
    }

    override fun onResume() {
        super.onResume()

        // The Activity must never reopen the camera while our FGS owns it.
        if (!_isScrollingEnabledPersisted.value &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCameraUseCases()
        }
    }

    private var previewView: PreviewView? = null

    fun setupPreview(view: PreviewView) {
        previewView = view
        bindCameraUseCases()
    }

    private fun bindCameraUseCases() {
        // Preview-only when disabled; all active gesture detection belongs to FGS.
        if (_isScrollingEnabledPersisted.value || !isInForeground) return
        val provider = cameraProvider
        if (provider == null) {
            startCamera()
            return
        }
        val view = previewView ?: return
        try {
            provider.unbindAll()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(view.surfaceProvider)
            }
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to start idle camera preview", e)
        }
    }

    private fun enableScrolling() {
        // Must be invoked from the visible Activity after both permissions exist.
        val permissions = checkAllPermissions()
        if (!permissions.hasCamera || !permissions.hasAccessibility) {
            _scrollStatus.value = "Grant Camera and Accessibility before enabling."
            return
        }
        if (ScrollAccessibilityService.getInstance() == null) {
            _scrollStatus.value = "Accessibility service is not connected yet."
            return
        }
        try {
            // Release Activity camera BEFORE creating the FGS camera owner.
            cameraProvider?.unbindAll()
            handDetectionAnalyzer?.release()
            handDetectionAnalyzer = null
            gestureScrollController.reset()
            ScrollAccessibilityService.setEnabled(true)
            _isScrollingEnabledPersisted.value = true
            // The next call executes in the foreground; never from onStop/onPause.
            GestureDetectionService.start(this)
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, true).apply()
            _scrollStatus.value = "Camera service starting — you can switch to any app."
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Unable to start camera service", e)
            _isScrollingEnabledPersisted.value = false
            ScrollAccessibilityService.setEnabled(false)
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, false).apply()
            _scrollStatus.value = "Camera service could not start: ${e.message}"
            bindCameraUseCases()
        }
    }

    private fun disableScrolling() {
        _isScrollingEnabledPersisted.value = false
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, false).apply()
        ScrollAccessibilityService.setEnabled(false)
        GestureDetectionService.stop(this)
        gestureScrollController.reset()
        _scrollStatus.value = "Gesture service stopped."
        // Allow the service time to release its CameraX use case.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!_isScrollingEnabledPersisted.value && isInForeground) bindCameraUseCases()
        }, 400L)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

}

data class PermissionState(
    val hasCamera: Boolean,
    val hasAccessibility: Boolean
)

@Composable
fun MainScreen(
    scrollStatus: State<String>,
    isScrollingEnabledState: State<Boolean>,
    onEnableScrolling: () -> Unit,
    onDisableScrolling: () -> Unit,
    requestCameraPermission: () -> Unit,
    checkPermissions: () -> PermissionState
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? MainActivity
    val permissions = remember { mutableStateOf(checkPermissions()) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    // Listen for lifecycle changes (like returning from settings) to refresh permissions
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permissions.value = checkPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        permissions.value = checkPermissions()
    }

    LaunchedEffect(permissions.value.hasCamera, isScrollingEnabledState.value) {
        if (permissions.value.hasCamera && !isScrollingEnabledState.value) {
            activity?.setupPreview(previewView)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Scrollable",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Active camera is owned exclusively by the foreground service.
        // Do not bind a second preview/analysis use case to the service's camera.
        if (isScrollingEnabledState.value) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text("Camera recognition active in foreground service. You may switch apps.\n摄像头识别服务已开启，可以切换到其他应用。",
                    modifier = Modifier.padding(16.dp))
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            }
        }

        // Status
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Status: ${scrollStatus.value}",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // Permissions
        if (!permissions.value.hasCamera) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Camera permission required",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { requestCameraPermission() }) {
                        Text("Grant Camera Permission")
                    }
                }
            }
        }

        if (!permissions.value.hasAccessibility) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Accessibility permission required for system-wide scrolling",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        }
                    ) {
                        Text("Open Accessibility Settings")
                    }
                }
            }
        }

        // Control Button
        if (permissions.value.hasCamera && permissions.value.hasAccessibility) {
            Button(
                onClick = {
                    if (isScrollingEnabledState.value) {
                        onDisableScrolling()
                    } else {
                        // Request notification permission for Foreground Service on Android 13+
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                (context as? androidx.activity.ComponentActivity)?.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                            }
                        }
                        onEnableScrolling()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScrollingEnabledState.value) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            ) {
                Text(
                    text = if (isScrollingEnabledState.value) {
                        "Disable Gesture Scrolling"
                    } else {
                        "Enable Gesture Scrolling"
                    }
                )
            }
        }

        // Hand pose/action configuration; Doom's accessibility permission screen is unchanged.
        Button(
            onClick = { context.startActivity(Intent(context, GestureMappingActivity::class.java)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("手势映射 / Configure Gestures") }

        // Instructions
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Instructions:",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• Show a gesture clearly in the front camera",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• Map each gesture to an action using Hand Gestures",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• Hold the pose for a few frames; once per held pose",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• Move your hand away before repeating the same pose",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
