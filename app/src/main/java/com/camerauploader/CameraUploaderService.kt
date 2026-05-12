package com.camerauploader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

class CameraUploaderService : Service(), LifecycleOwner {
    companion object {
        private const val TAG = "CameraUploaderService"
        private const val CHANNEL_ID = "camera_uploader_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CAPTURE = "com.camerauploader.ACTION_CAPTURE"
    }

    // ── Threads ───────────────────────────────────────────────────────────────
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler   // blocks on latch; runs upload
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    // ── Camera ───────────────────────────────────────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null

    // ── LifecycleOwner for CameraX ────────────────────────────────────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting capture…"))
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        setupCamera()
        workerThread = HandlerThread("CameraWorker").also { it.start() }
        workerHandler = Handler(workerThread.looper)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        postWorker()
        return START_STICKY
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun postWorker() {
        CameraUploaderWorker(
            cameraProvider!!, lifecycleRegistry, this,
            this,
            { s -> this.updateNotification(s) },
        ).run()
    }

    private fun setupCamera() {
        cameraProvider = ProcessCameraProvider.getInstance(this).get()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Camera Uploader", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Background camera upload service" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera Uploader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
