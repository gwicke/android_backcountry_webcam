package com.camerauploader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transient foreground service: start → preflight → capture → upload → stop.
 *
 * ── Scheduling ───────────────────────────────────────────────────────────────
 * AlarmManager fires [AlarmReceiver] at each interval. The receiver starts
 * this service with [ACTION_CAPTURE]. The service stops itself after every
 * cycle so it holds no resources between captures.
 *
 * ── Preflight state machine (Camera2Basic pattern) ───────────────────────────
 * A [Preview] use case is bound with a Camera2Interop [CaptureCallback] that
 * reads actual CaptureResult metadata on every frame. The state machine mirrors
 * the canonical Camera2Basic approach:
 *
 *   STATE_PREVIEW
 *     Camera opened, pipeline running. 3A algorithms converge freely.
 *     After [PREVIEW_WARMUP_MS] we advance to STATE_WAITING_LOCK.
 *
 *   STATE_WAITING_LOCK
 *     AF trigger sent. CaptureCallback watches CONTROL_AF_STATE until
 *     AF_STATE_FOCUSED_LOCKED or AF_STATE_NOT_FOCUSED_LOCKED (or timeout).
 *     Then advances to STATE_WAITING_PRECAPTURE.
 *
 *   STATE_WAITING_PRECAPTURE
 *     AE_PRECAPTURE trigger sent. Watches CONTROL_AE_STATE until it enters
 *     a precapture state (PRECAPTURE, FLASH_REQUIRED, CONVERGED).
 *     Then advances to STATE_WAITING_NON_PRECAPTURE.
 *
 *   STATE_WAITING_NON_PRECAPTURE
 *     Waits for AE_STATE to leave precapture (CONVERGED or FLASH_REQUIRED).
 *     Then advances to STATE_PICTURE_TAKEN and fires [ImageCapture.takePicture].
 *
 *   STATE_PICTURE_TAKEN
 *     Terminal state. CaptureCallback is a no-op here.
 */
@ExperimentalCamera2Interop
class CameraUploaderService : Service(), LifecycleOwner {

    // ── Preflight state machine constants ────────────────────────────────────
    private object State {
        const val PREVIEW       = 0  // warmup: 3A running freely, no triggers sent yet
        const val WAITING_3A    = 1  // combined AF+AE+AWB trigger sent; watching CaptureResults
        const val PICTURE_TAKEN = 2  // terminal — CaptureCallback becomes a no-op
    }

    companion object {
        private const val TAG = "CameraUploaderService"
        private const val CHANNEL_ID = "camera_uploader_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CAPTURE = "com.camerauploader.ACTION_CAPTURE"

        /** Frames to let 3A run freely before triggering AF. */
        private const val PREVIEW_WARMUP_MS = 10L

        /**
         * Max ms to wait for combined AF+AE lock after the trigger.
         * Both AF and AE must converge within this window; if either times out
         * the capture proceeds anyway rather than hanging indefinitely.
         */
        private const val LOCK_TIMEOUT_MS = 3_000L

        /** Absolute cap on the entire preflight + capture pipeline. */
        private const val TOTAL_TIMEOUT_MS = 15_000L
    }

    // ── LifecycleOwner for CameraX ────────────────────────────────────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    // ── Threads ───────────────────────────────────────────────────────────────
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler   // blocks on latch; runs upload
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    // ── HTTP client ───────────────────────────────────────────────────────────
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting capture…"))
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        workerThread = HandlerThread("CameraWorker").also { it.start() }
        workerHandler = Handler(workerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        workerHandler.post { runCaptureAndUploadCycle() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        workerThread.quitSafely()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Outer cycle — blocks workerThread until capture + upload finish
    // ─────────────────────────────────────────────────────────────────────────

    private fun runCaptureAndUploadCycle() {
        Log.d(TAG, "Capture cycle started")
        updateNotification("Preflight — warming up 3A…")

        var capturedBytes: ByteArray? = null
        val done = CountDownLatch(1)

        mainHandler.post {
            val future = ProcessCameraProvider.getInstance(applicationContext)
            future.addListener({
                val provider = runCatching { future.get() }.getOrElse {
                    Log.e(TAG, "CameraProvider unavailable", it)
                    done.countDown(); return@addListener
                }

                // ── Build use cases ───────────────────────────────────────────

                // Preview: carries the Camera2Interop CaptureCallback that
                // drives the 3A state machine.  Frames are discarded via a
                // null-consuming SurfaceTexture — we only need the ISP running.
                val previewBuilder = Preview.Builder()
                installCaptureCallback(previewBuilder)
                val preview = previewBuilder.build().also { p ->
                    p.setSurfaceProvider { req ->
                        val st = android.graphics.SurfaceTexture(0).apply {
                            setDefaultBufferSize(req.resolution.width, req.resolution.height)
                        }
                        val surface = android.view.Surface(st)
                        req.provideSurface(
                            surface,
                            ContextCompat.getMainExecutor(applicationContext)
                        ) { surface.release(); st.release() }
                    }
                }

                // ImageCapture: honours the user-selected resolution.
                val savedSize = SettingsManager.getResolution(applicationContext)
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(85)
                    // horizontal orientation; the default ROTATION_0 is
                    // portrait
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    .setTargetRotation(android.view.Surface.ROTATION_90)
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

                try {
                    provider.unbindAll()
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                    val camera = provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                    )

                    // Kick off the state machine after the warmup delay.
                    // The CaptureCallback is already receiving results; we just
                    // let 3A run freely until PREVIEW_WARMUP_MS then trigger AF.
                    mainHandler.postDelayed({
                        triggerAfAeLock(camera, imageCapture, provider) { bytes ->
                            capturedBytes = bytes
                            done.countDown()
                        }
                    }, PREVIEW_WARMUP_MS)

                } catch (e: Exception) {
                    Log.e(TAG, "Camera bind failed", e)
                    shutdownCamera(provider)
                    done.countDown()
                    stopSelf();
                }
            }, ContextCompat.getMainExecutor(applicationContext))
        }

        done.await(TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // ── Upload ────────────────────────────────────────────────────────────
        val secs = SettingsManager.getIntervalSeconds(applicationContext)
        if (capturedBytes != null) {
            val url = SettingsManager.getUploadUrl(applicationContext)
            if (url.isBlank()) updateNotification("No URL — tap icon to configure.")
            else uploadImage(capturedBytes!!, url)
        } else {
            Log.w(TAG, "Capture produced no bytes")
            updateNotification("Capture failed — retry in ${secs}s")
        }
        stopSelf()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Camera2Interop: install CaptureCallback on Preview
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Attaches a [android.hardware.camera2.CameraCaptureSession.CaptureCallback]
     * to the Preview use case via Camera2Interop.  The callback is a read-only
     * observer of CaptureResults — the state machine itself is driven by
     * [triggerAfAeLock], which sends a single combined CameraControl command.
     *
     * The atomic [captureState] is the shared variable that coordinates the
     * callback with the trigger methods.  All writes happen on the camera
     * thread (inside onCaptureCompleted); all reads also happen on that thread
     * or inside the callbacks that schedule the next step on mainHandler.
     */
    private val captureState = AtomicInteger(State.PREVIEW)

    // Timestamps used to enforce per-state timeouts without a separate timer.
    @Volatile private var stateEnteredAt = 0L

    private fun installCaptureCallback(previewBuilder: Preview.Builder) {
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
            object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {

                override fun onCaptureCompleted(
                    session: android.hardware.camera2.CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    processCaptureResult(result)
                }

                // Partial results carry AF/AE state too; useful on slow devices.
                override fun onCaptureProgressed(
                    session: android.hardware.camera2.CameraCaptureSession,
                    request: CaptureRequest,
                    partialResult: CaptureResult
                ) {
                    processCaptureResult(partialResult)
                }
            }
        )
    }

    // Single continuation fired when both AF and AE are ready.
    @Volatile private var on3aReady: (() -> Unit)? = null

    /**
     * Core of the state machine, called on the camera thread for every frame.
     *
     * In STATE_WAITING_3A we check AF and AE independently and in parallel —
     * both must reach a settled state (or time out) before firing the capture.
     * This is more efficient than the sequential AF-then-AE approach because
     * on most devices both converge within the same handful of frames.
     */
    private fun processCaptureResult(result: CaptureResult) {
        if (captureState.get() != State.WAITING_3A) return

        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val elapsed = System.currentTimeMillis() - stateEnteredAt
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)

        // AF ready: locked, not-focused-locked, null (fixed-focus), or timed out.
        val afReady = afState == null
            || afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
            || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            || elapsed > LOCK_TIMEOUT_MS

        // Check for darkness
        val isTooDark = iso != null && iso > 1600
            && exposureTimeNs != null && exposureTimeNs > 33333333
        if (isTooDark or (aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)) {
            on3aReady = null
            stopSelf()
            return
        }

        // AE ready: converged, flash queued, or timed out.
        // CONVERGED is the common case; FLASH_REQUIRED means the device has
        // decided to fire flash — that is also an acceptable settled state.
        val aeReady = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
            || elapsed > LOCK_TIMEOUT_MS

        if (afReady && aeReady) {
            if (elapsed > LOCK_TIMEOUT_MS)
                Log.w(TAG, "3A lock timeout — shooting anyway (af=$afState ae=$aeState)")
            else
                Log.d(TAG, "3A converged in ${elapsed}ms (af=$afState ae=$aeState)")

            captureState.set(State.PICTURE_TAKEN)
            val cb = on3aReady; on3aReady = null
            mainHandler.post { cb?.invoke() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State machine trigger (called on main thread after warmup delay)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Issues a single [startFocusAndMetering] with FLAG_AF | FLAG_AE | FLAG_AWB
     * and enters STATE_WAITING_3A.  [processCaptureResult] then monitors every
     * CaptureResult for both AF lock and AE convergence in parallel; when both
     * are satisfied it fires [on3aReady] → [shootAndShutdown].
     */
    private fun triggerAfAeLock(
        camera: Camera,
        imageCapture: ImageCapture,
        provider: ProcessCameraProvider,
        onComplete: (ByteArray?) -> Unit
    ) {
        updateNotification("Locking AF + AE…")
        Log.d(TAG, "State → WAITING_3A")

        on3aReady = { shootAndShutdown(imageCapture, provider, onComplete) }
        captureState.set(State.WAITING_3A)
        stateEnteredAt = System.currentTimeMillis()

        // One combined metering action covers AF, AE, and AWB simultaneously.
        // Centre-weighted metering point is the conventional choice for stills.
        camera.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(
                SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f),
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AWB or FocusMeteringAction.FLAG_AE
            ).disableAutoCancel().build()
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Final capture
    // ─────────────────────────────────────────────────────────────────────────

    private fun shootAndShutdown(
        imageCapture: ImageCapture,
        provider: ProcessCameraProvider,
        onComplete: (ByteArray?) -> Unit
    ) {
        updateNotification("Capturing image…")
        Log.d(TAG, "State → PICTURE_TAKEN — firing takePicture")

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(applicationContext),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bytes = runCatching { imageProxyToBytes(image) }.getOrNull()
                    image.close()
                    shutdownCamera(provider)
                    onComplete(bytes)
                }
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "takePicture failed: ${exc.message}", exc)
                    shutdownCamera(provider)
                    onComplete(null)
                }
            }
        )
    }

    private fun shutdownCamera(provider: ProcessCameraProvider) {
        runCatching { provider.unbindAll() }
        captureState.set(State.PREVIEW)
        on3aReady = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
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

    // ─────────────────────────────────────────────────────────────────────────
    // Upload
    // ─────────────────────────────────────────────────────────────────────────

    private fun uploadImage(jpeg: ByteArray, uploadUrl: String) {
        val ctx = applicationContext
        val secs = SettingsManager.getIntervalSeconds(ctx)
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
        SettingsManager.getBasicAuthHeader(ctx)?.let { requestBuilder.header("Authorization", it) }

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
                stopSelf()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Upload IOException", e)
            updateNotification("Upload error — retry in ${secs}s")
            stopSelf()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification helpers
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


