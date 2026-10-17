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
        private const val CHANNEL_ID = "GestureDetectionChannel"
        private const val NOTIFICATION_ID = 1
        @Volatile private var instance: GestureDetectionService? = null
        @Volatile private var active = false

        fun isRunning(): Boolean = active && instance != null
        private var statusCallback: StatusCallback? = null

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
            if (status == lastStatusValue && now - lastStatusTimeMs < STATUS_THROTTLE_MS) {
                return
            }
            if (now - lastStatusTimeMs < STATUS_THROTTLE_MS) {
                return
            }
            lastStatusTimeMs = now
            lastStatusValue = status
            android.util.Log.d("GestureService", "Updating status: $status, callback: ${statusCallback != null}")
            statusCallback?.onStatusChanged(status)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        cameraExecutor = Executors.newSingleThreadExecutor()
        createNotificationChannel()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        if (active) return START_NOT_STICKY
        active = true
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        android.util.Log.d("GestureService", "Service started, callback set: ${statusCallback != null}")
        updateStatus("Service starting...")
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
                    if (gesture == null) {
                        gestureScrollController.processGesture(null)
                        updateStatus("WAITING FOR GESTURE")
                    } else {
                        gestureScrollController.processGesture(gesture)
                        updateStatus("GESTURE: ${gesture.gestureName}")
                    }
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor, handDetectionAnalyzer!!)
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                isCameraBound = true
                updateStatus("Camera active — gestures work when switching apps.")
            } catch (e: Exception) {
                android.util.Log.e("GestureService", "Camera binding failed", e)
                updateStatus("Camera error: ${e.message}")
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Scrollable Background Active")
            .setContentText("Gesture detection is running in the background.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Gesture Detection Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            serviceChannel.setShowBadge(false)
            serviceChannel.enableVibration(false)
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
