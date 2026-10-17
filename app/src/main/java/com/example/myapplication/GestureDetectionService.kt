package com.example.myapplication

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GestureDetectionService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle = lifecycleRegistry

    private lateinit var cameraExecutor: ExecutorService
    private var handDetectionAnalyzer: HandDetectionAnalyzer? = null
    private val gestureScrollController = GestureScrollController()
    private var isCameraBound = false
    private var cameraProvider: ProcessCameraProvider? = null

    interface StatusCallback {
        fun onStatusChanged(status: String)
    }

    companion object {
        private const val CHANNEL_ID = "GestureDetectionChannel_v3"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.example.myapplication.STOP_CAMERA_FGS"
        @Volatile private var instance: GestureDetectionService? = null
        @Volatile private var active = false

        fun isRunning(): Boolean = active && instance != null
        @Volatile private var statusCallback: StatusCallback? = null

        private const val STATUS_THROTTLE_MS = 200L
        private var lastStatusTimeMs = 0L
        private var lastStatusValue: String? = null

        fun start(context: Context) {
            val intent = Intent(context, GestureDetectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GestureDetectionService::class.java)
            context.stopService(intent)
        }

        fun setStatusCallback(callback: StatusCallback?) {
            statusCallback = callback
        }

        private fun updateStatus(status: String) {
            val now = System.currentTimeMillis()
            // Never post identical status strings on every camera frame.
            // FPS and confidence have their own slower diagnostics channel.
            if (status == lastStatusValue) return
            if (now - lastStatusTimeMs < STATUS_THROTTLE_MS) return
            lastStatusTimeMs = now
            lastStatusValue = status
            statusCallback?.onStatusChanged(status)
        }
    }

    override fun onCreate() {
        super.onCreate()
        GestureRuntime.init(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        cameraExecutor = Executors.newSingleThreadExecutor()
        createNotificationChannel()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = createNotification()
        // Required before opening the camera. Notif channel is explicitly visible,
        // though Android 13+ still requires the user to grant POST_NOTIFICATIONS.
        startForeground(NOTIFICATION_ID, notification)
        if (active) return START_NOT_STICKY
        active = true
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        android.util.Log.d("GestureService", "Service started, callback set: ${statusCallback != null}")
        updateStatus("正在启动识别服务")
        startBackgroundDetection()
        // Do not restart a camera FGS from an invisible process.
        // User re-enables it explicitly from the visible Activity after a crash/reboot.
        return START_NOT_STICKY
    }

    private fun startBackgroundDetection() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!active || instance !== this) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                // Activity releases its preview when Enable is tapped.
                provider.unbindAll()
                handDetectionAnalyzer?.release()
                handDetectionAnalyzer = HandDetectionAnalyzer(this) { gesture ->
                    gestureScrollController.processGesture(gesture)
                    updateStatus(if (gesture == null) "等待手势动作"
                        else "已检测到手部，等待动作")
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(640, 480))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor, handDetectionAnalyzer!!)
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                isCameraBound = true
                updateStatus("摄像头识别运行中，可跨应用控制")
            } catch (e: Exception) {
                android.util.Log.e("GestureService", "Camera binding failed", e)
                updateStatus("摄像头运行错误：${e.message}")
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, GestureDetectionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("隔空手势正在运行")
            .setContentText("全局识别运行中 · 点击设置 · 可在这里停止")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止手势", stopPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "隔空手势持续通知",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            serviceChannel.setShowBadge(false)
            serviceChannel.enableVibration(false)
            serviceChannel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            serviceChannel.enableLights(false)
            serviceChannel.setSound(null, null)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        active = false
        ScrollAccessibilityService.setEnabled(false)
        GestureDiagnostics.reset()
        gestureScrollController.reset()
        getSharedPreferences("ScrollablePrefs", Context.MODE_PRIVATE)
            .edit().putBoolean("gesture_scrolling_enabled", false).apply()
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        
        // Release camera resources
        try {
            if (isCameraBound && cameraProvider != null) {
                cameraProvider?.unbindAll()
                isCameraBound = false
            }
        } catch (e: Exception) {
            android.util.Log.e("GestureService", "Error unbinding camera on destroy", e)
        }
        
        cameraExecutor.shutdown()
        handDetectionAnalyzer?.release()
        instance = null
    }
}
