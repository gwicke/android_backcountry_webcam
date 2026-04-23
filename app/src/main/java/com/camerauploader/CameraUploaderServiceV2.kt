package com.camerauploader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val NOTIFICATION_ID = 1
private const val CHANNEL_ID = "camera_uploader_channel"
private const val TAG = "CameraUploaderService"

class CameraUploaderServiceV2 : Service(), LifecycleOwner {
    private var cameraProvider: ProcessCameraProvider? = null

    // Cache of capture results
    private val captureCache = HashMap<Long, CaptureResult>()

    // ── LifecycleOwner for CameraX ────────────────────────────────────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Capturing image.."))
        setupCamera()
        return START_NOT_STICKY
    }
    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            this.cameraProvider = cameraProviderFuture.get()
            startCaptureProcess()
        }, ContextCompat.getMainExecutor(this))
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCaptureProcess() {
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        // Set up imageCapture
        val savedSize = SettingsManager.getResolution(applicationContext)
        val imageCapture = ImageCapture.Builder()
            .setFlashMode(ImageCapture.FLASH_MODE_OFF) // Disable flash
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(85)
            .setTargetRotation(Surface.ROTATION_90)
            .apply {
                if (savedSize != null) {
                    setResolutionSelector(
                        androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                            .setResolutionFilter{ sizes, _ ->
                                return@setResolutionFilter sizes.sortedByClosestTo(savedSize)
                            }
                            .build()
                    )
                }
            }
            .build()

        // 2. Set up a dummy ImageAnalysis to monitor exposure metadata
        val analysisBuilder = ImageAnalysis.Builder()

        // Use Camera2Interop to listen for CaptureResults
        val extender = Camera2Interop.Extender(analysisBuilder)
        extender.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                // Store the CaptureResult using its timestamp, for access in the analyzer
                val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                if (timestamp != null) {
                    captureCache[timestamp] = result
                }
            }
        })

        val imageAnalysis = analysisBuilder
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(Surface.ROTATION_90)
            .build()

        // Use Camera2Interop to ensure AF/AE/AWB are continuous and efficient
        this.cameraProvider?.unbindAll()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        val camera = this.cameraProvider?.bindToLifecycle(
            this,
            cameraSelector,
            imageAnalysis,
            imageCapture
        )
        updateNotification("Locking AF + AE…")

        // Monitor metadata before taking the shot
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
            val timestamp = imageProxy.imageInfo.timestamp
            imageProxy.close()
            // Retrieve the matching CaptureResult from your cache
            val captureResult = captureCache.remove(timestamp)
            Log.d(TAG, "captureResult: ${captureResult}")
            val iso = captureResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val shutterSpeed = captureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L // in nanoseconds
            val afState = captureResult?.get(CaptureResult.CONTROL_AF_STATE)
            val aeState = captureResult?.get(CaptureResult.CONTROL_AE_STATE)

            // 3. "Too Dark" Logic: skip if ISO is maxed or shutter is too slow
            // Example: Skip if ISO > 3200 or Shutter > 1/10th second (100,000,000 ns)
            if (iso > 3200 || shutterSpeed > 100_000_000L) {
                stopSelf() // Shutdown to save solar power
                Log.d(TAG, "Dark!!")
                return@setAnalyzer
            }
            if (afState != CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                && afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                Log.d(TAG, "AF not converged")
                return@setAnalyzer
            }
            if (aeState != null
                && aeState != CaptureResult.CONTROL_AE_STATE_CONVERGED
                && aeState != CaptureResult.CONTROL_AE_STATE_LOCKED) {
                Log.d(TAG, "AE not converged")
                return@setAnalyzer
            }

            // All good. Capture the actual photo.

            imageAnalysis.clearAnalyzer()
            takePhoto(imageCapture)
        }
    }

    private fun takePhoto(imageCapture: ImageCapture) {
        updateNotification("Capturing image...")
        Log.d(TAG, "Capturing image...")
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bytes = runCatching { imageProxyToBytes(image) }.getOrNull()
                    image.close()
                    if (bytes != null) {
                        uploadImage(bytes)
                    }
                }
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "takePicture failed: ${exc.message}", exc)
                    stopSelf()
                }
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Image conversion
    // ─────────────────────────────────────────────────────────────────────────

    private fun imageProxyToBytes(image: ImageProxy): ByteArray {
        val buffer = image.planes[0].buffer
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    // Battery level
    private fun getBatteryLevel(): Float? {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            applicationContext.registerReceiver(null, ifilter)
        }
        return batteryStatus?.let { intent ->
            val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            level * 100 / scale.toFloat()
        }
    }
    // Image upload
    private fun uploadImage(jpeg: ByteArray) {
        val uploadUrl = SettingsManager.getUploadUrl(this)
        val secs = SettingsManager.getIntervalSeconds(this)
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        updateNotification("Uploading ${jpeg.size / 1024} KB…")
        Log.d(TAG, "Uploading ${jpeg.size} bytes to $uploadUrl")

        val timestamp = System.currentTimeMillis()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", "capture_$timestamp.jpg",
                jpeg.toRequestBody("image/jpeg".toMediaType())
            )
            .addFormDataPart("timestamp", timestamp.toString())
            .addFormDataPart("batlevel", getBatteryLevel().toString())
            .build()

        val requestBuilder = Request.Builder().url(uploadUrl).post(body)
        SettingsManager.getBasicAuthHeader(this)?.let { requestBuilder.header("Authorization", it) }

        try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "Upload OK: ${response.code}")
                    updateNotification(
                        "Last upload: ${android.text.format.DateFormat.format("HH:mm:ss", timestamp)} ✓"
                    )
                } else {
                    Log.w(TAG, "Upload failed: HTTP ${response.code}")
                    updateNotification("Upload failed (HTTP ${response.code}) — retry in ${secs}s")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Upload IOException", e)
            updateNotification("Upload error — retry in ${secs}s")
        }
        stopSelf()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification helper
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Camera Uploader", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Background camera upload service" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
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
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
