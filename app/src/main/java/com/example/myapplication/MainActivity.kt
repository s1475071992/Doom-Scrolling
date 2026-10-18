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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val gestureScrollController = GestureScrollController()
    
    private val PREFS_NAME = "ScrollablePrefs"
    private val KEY_ENABLED = "gesture_scrolling_enabled"
    
    // Create a mutable state that can be accessed by both Compose and the Analyzer
    private val _scrollStatus = mutableStateOf("就绪")
    val scrollStatusState: State<String> = _scrollStatus
    
    private val _isScrollingEnabledPersisted = mutableStateOf(false)
    val isScrollingEnabledState: State<Boolean> = _isScrollingEnabledPersisted

    private var isInForeground: Boolean = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted && !_isScrollingEnabledPersisted.value) {
            // Camera starts only when user enables the foreground service.
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
        GestureRuntime.init(this)

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
                "无障碍服务未连接，请到系统设置中开启"
            } else {
                "已就绪：保持起手式约半秒，提示确认后再移动"
            }
        }

        lifecycle.addObserver(lifecycleObserver)

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
                // Permission granted: remain idle without opening the camera.
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
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
            _scrollStatus.value = "摄像头识别已停止，请点击开启"
        }
    }

    override fun onStop() {
        super.onStop()
        isInForeground = false
        // No background start here: camera FGS must be created while visible.
        // The already-running service keeps the camera across apps.
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister status callback
        GestureDetectionService.setStatusCallback(null)
    }

    override fun onResume() {
        super.onResume()
        if (_isScrollingEnabledPersisted.value && !GestureDetectionService.isRunning()) {
            _isScrollingEnabledPersisted.value = false
            _scrollStatus.value = "识别服务已停止，请重新开启"
        }

        // Do not start a second preview while disabled. The camera should
        // remain fully closed until gesture control is explicitly enabled.
    }

    private fun enableScrolling() {
        // Must be invoked from the visible Activity after both permissions exist.
        val permissions = checkAllPermissions()
        if (!permissions.hasCamera || !permissions.hasAccessibility) {
            _scrollStatus.value = "请先授权相机与无障碍服务"
            return
        }
        if (ScrollAccessibilityService.getInstance() == null) {
            _scrollStatus.value = "无障碍服务尚未连接"
            return
        }
        try {
            // Release Activity camera BEFORE creating the FGS camera owner.
            gestureScrollController.reset()
            ScrollAccessibilityService.setEnabled(true)
            _isScrollingEnabledPersisted.value = true
            // The next call executes in the foreground; never from onStop/onPause.
            GestureDetectionService.start(this)
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, true).apply()
            _scrollStatus.value = "正在启动摄像头识别，可切换到其他应用"
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Unable to start camera service", e)
            _isScrollingEnabledPersisted.value = false
            ScrollAccessibilityService.setEnabled(false)
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, false).apply()
            _scrollStatus.value = "摄像头服务启动失败：${e.message}"
        }
    }

    private fun disableScrolling() {
        _isScrollingEnabledPersisted.value = false
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, false).apply()
        ScrollAccessibilityService.setEnabled(false)
        GestureDetectionService.stop(this)
        gestureScrollController.reset()
        _scrollStatus.value = "手势识别已停止"
        // Camera stays off in idle mode, reducing both power use and heating.
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
    val permissions = remember { mutableStateOf(checkPermissions()) }
    val notificationGranted = remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var notificationDenied by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationGranted.value = granted
        notificationDenied = !granted
        if (granted) onEnableScrolling()
    }
    // No idle camera preview: opening CameraX while gesture control is off
    // wastes energy and could race with the foreground recognition service.

    // Listen for lifecycle changes (like returning from settings) to refresh permissions
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permissions.value = checkPermissions()
                notificationGranted.value = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
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

    // Only the user-enabled foreground service opens the camera.

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "隔空手势",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Active camera is owned exclusively by the foreground service.
        // Do not bind a second preview/analysis use case to the service's camera.
        if (isScrollingEnabledState.value) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text("摄像头识别已开启，可以切换到其他应用。",
                    modifier = Modifier.padding(16.dp))
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("省电待机：摄像头已关闭",
                        style = MaterialTheme.typography.titleMedium)
                    Text("开启手势识别后才使用前置摄像头；关闭时不再持续预览。",
                        style = MaterialTheme.typography.bodySmall)
                }
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
                    text = "运行状态：${scrollStatus.value}",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // Debug telemetry, updated on main thread from the service.
        val snapshot = GestureDiagnostics.current
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("实时识别调试",
                    style = MaterialTheme.typography.titleMedium)
                Text("AI 模型推理 FPS：" + String.format(java.util.Locale.US, "%.1f", snapshot.fps))
                Text("摄像头实际采集：" + snapshot.cameraFps)
                Text("摄像头帧率策略：" + snapshot.cameraTarget)
                Text("识别手势：${snapshot.name}")
                Text("置信度: " + String.format(java.util.Locale.US,
                    "%.0f%%", snapshot.confidence * 100f))
                Text("状态: ${snapshot.state}")
                Text("最后动作: ${snapshot.lastAction}")
                Text("悬浮层: ${snapshot.overlay}")
                Text("AI 推理后端: ${snapshot.backend}")
                Text("芯片信息: ${snapshot.chipset}")
                Text("模型执行统计: ${snapshot.npuModels}")
                Text("硬件算子分配: ${snapshot.assignmentEvidence}")
                Text("最近回退原因: ${snapshot.fallbackReason}")
                Text("手势判断详情: ${snapshot.gestureTrace}")
                Text("设备状态: ${snapshot.battery}")
                if (isScrollingEnabledState.value) {
                    OutlinedButton(onClick = {
                        val service = ScrollAccessibilityService.getInstance()
                        if (service == null || !ScrollAccessibilityService.isEnabled()) {
                            GestureDiagnostics.action("无障碍服务未连接")
                        } else {
                            service.showGestureFeedback("✓ 跨应用悬浮提示测试")
                        }
                    }) { Text("测试跨应用悬浮提示") }
                }
            }
        }

        var powerSavingEnabled by remember { mutableStateOf(GestureRuntime.powerSaving) }
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("智能省电")
                Text("等待起手式：AI 推理约 1 FPS，并请求摄像头最低支持帧率；起手式确认／空鼠自动恢复高帧率。摄像头使用 YUV，只有选中推理帧才转 RGB；实际采集 FPS 取决于驱动。",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = powerSavingEnabled, onCheckedChange = {
                powerSavingEnabled = it
                GestureRuntime.setPowerSaving(context, it)
            })
        }

        var accelerationMode by remember {
            mutableStateOf(GestureRuntime.accelerationMode)
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("AI 加速模式",
                    style = MaterialTheme.typography.titleMedium)
                for (mode in AccelerationMode.entries) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = accelerationMode == mode,
                            onClick = {
                                accelerationMode = mode
                                GestureRuntime.setAccelerationMode(context, mode)
                            }
                        )
                        Text(mode.title, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "自动模式默认依次尝试 NPU → GPU → CPU；不可用或运行失败自动降级。" +
                        "NPU 仅代表 LiteRT 请求，芯片实际算子分配尚未经真机验证。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    if (isScrollingEnabledState.value)
                        "切换模式后，关闭并重新开启手势识别才会生效。"
                    else "下次开启识别时生效。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        var feedbackEnabled by remember { mutableStateOf(GestureRuntime.feedback) }
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("跨应用显示手势提示")
            Switch(checked = feedbackEnabled, onCheckedChange = {
                feedbackEnabled = it
                GestureRuntime.setFeedback(context, it)
            })
        }

        var airMouseEnabled by remember { mutableStateOf(GestureRuntime.airMouse) }
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("实验性空中鼠标")
                Text("食指移动光标，短捏合松开点击，捏合移动并松开完成拖动",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = airMouseEnabled, onCheckedChange = {
                airMouseEnabled = it
                GestureRuntime.setAirMouse(context, it)
            })
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
                        text = "需要相机权限",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { requestCameraPermission() }) {
                        Text("授权相机")
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
                        text = "需要无障碍权限才能在其他应用执行动作",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        }
                    ) {
                        Text("打开无障碍设置")
                    }
                }
            }
        }

        // A camera foreground service can run without POST_NOTIFICATIONS, but its
        // ongoing notification will not appear in the notification drawer.
        // Require the grant before enabling because the user explicitly wants
        // a visible persistent notification and an in-drawer stop control.
        if (!notificationGranted.value && Build.VERSION.SDK_INT >= 33) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("需要通知权限才能在通知栏显示持续运行状态和停止按钮")
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(
                            Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        })
                    }) { Text("打开应用通知设置") }
                    if (notificationDenied) {
                        Text("尚未授予通知权限。请在系统设置里允许通知。",
                            color = MaterialTheme.colorScheme.error)
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
                        if (notificationGranted.value) {
                            onEnableScrolling()
                        } else if (Build.VERSION.SDK_INT >= 33) {
                            // Start only after the permission result is delivered.
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
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
                        "关闭手势识别"
                    } else {
                        "开启手势识别"
                    }
                )
            }
        }

        // Hand pose/action configuration; Doom's accessibility permission screen is unchanged.
        Button(
            onClick = { context.startActivity(Intent(context, GestureMappingActivity::class.java)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("配置动作映射") }

        // Instructions
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "操作说明",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• 先在镜头前摆出规定起手式，系统会显示中文提示",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• 食指上翻：先握拳，起手式确认后只伸直食指",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• 食指下翻：先伸直食指，其余收拢；确认后弯下食指",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• 双指移动：食指、中指伸直，确认后向上下左右移动整只手",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• 单击：先张掌，看到 ✋ 后握拳；默认点击屏幕中心",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• 双击：先握拳，看到 ✊ 后张掌；默认双击屏幕中心",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
