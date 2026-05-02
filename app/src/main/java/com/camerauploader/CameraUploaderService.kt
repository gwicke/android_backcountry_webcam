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
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    // ── HTTP client (long-lived; shared by JPEG and AV1 paths) ───────────────
    // Owned by the service so the AV1 streamer can keep one connection open
    // across many camera captures and so connection pooling / keep-alive
    // applies between back-to-back uploads.
    private val httpDispatcher = Dispatcher(Executors.newFixedThreadPool(2))
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)   // long-running chunked POST
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .dispatcher(httpDispatcher)
        .build()

    // ── AV1 streaming session (lazily started) ───────────────────────────────
    private var av1Streamer: Av1Streamer? = null

    /**
     * Returns the singleton AV1 streamer for this service, opening it on
     * first use.  The streamer owns its own threads and the encoder; the
     * service owns the streamer.
     */
    @Synchronized
    fun getOrCreateAv1Streamer(): Av1Streamer {
        var s = av1Streamer
        if (s == null) {
            s = Av1Streamer(applicationContext, httpClient) { msg -> updateNotification(msg) }
            av1Streamer = s
        }
        return s
    }

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
        av1Streamer?.stop()
        av1Streamer = null
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
