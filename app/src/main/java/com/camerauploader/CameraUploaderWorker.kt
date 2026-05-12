package com.camerauploader

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@ExperimentalCamera2Interop
class CameraUploaderWorker(
    val cameraProvider: ProcessCameraProvider, val lifecycleRegistry: LifecycleRegistry,
    val lifeOwner: LifecycleOwner,
    val applicationContext: android.content.Context,
    val updateNotification: (String)-> Unit) {

    // ── Preflight state machine constants ────────────────────────────────────
    private object State {
        const val PREVIEW       = 0  // warmup: 3A running freely, no triggers sent yet
        const val WAITING_3A    = 1  // combined AF+AE+AWB trigger sent; watching CaptureResults
        const val PICTURE_TAKEN = 2  // terminal — CaptureCallback becomes a no-op
    }

    companion object {
        private const val TAG = "CameraUploaderWorker"
        const val ACTION_CAPTURE = "com.camerauploader.ACTION_CAPTURE"
    }

    // ── HTTP client ───────────────────────────────────────────────────────────
    private val customExecutor = Executors.newFixedThreadPool(1);
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dispatcher(Dispatcher(customExecutor))
        .build()

    lateinit var imageCapture: ImageCapture
    private var yuvAnalysis: ImageAnalysis? = null

    private val uploadMode: SettingsManager.UploadMode
        get() = SettingsManager.getUploadMode(applicationContext)


    // ─────────────────────────────────────────────────────────────────────────
    // Outer cycle
    // ─────────────────────────────────────────────────────────────────────────

    fun run() {
        Log.d(TAG, "Capture cycle started (mode=$uploadMode)")
        updateNotification("Preflight — warming up 3A…")

        // ── Build use cases ───────────────────────────────────────────

        // Preview: carries the Camera2Interop CaptureCallback that
        // drives the 3A state machine.  Frames are discarded via a
        // null-consuming SurfaceTexture — we only need the ISP running.
        val previewBuilder = Preview.Builder()
        installCaptureCallback(previewBuilder)

        // Resolution selection
        val savedSize = SettingsManager.getResolution(applicationContext)
        val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setResolutionFilter{ sizes, _ ->
                return@setResolutionFilter sizes.sortedByClosestTo(savedSize)
            }
            .build()

        val preview = previewBuilder
            .setResolutionSelector(resolutionSelector)
            .build().also { p ->
            p.setSurfaceProvider { req ->
                val st = android.graphics.SurfaceTexture(0).apply {
                    setDefaultBufferSize(req.resolution.width, req.resolution.height)
                }
                val surface = android.view.Surface(st)
                req.provideSurface(
                    surface,
                    CurrentThreadExecutor()
                ) { surface.release(); st.release() }
            }
        }

        // The secondary use case is mode-specific: JPEG mode uses
        // ImageCapture; AV1 mode uses ImageAnalysis to grab a single YUV
        // frame after 3A converges.  Either way we go through the same
        // preflight state machine — AV1 streaming is still alarm-driven,
        // one frame per interval.
        val secondaryUseCase: androidx.camera.core.UseCase = when (uploadMode) {
            SettingsManager.UploadMode.JPEG -> {
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(85)
                    // horizontal orientation; the default ROTATION_0 is portrait
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    .setTargetRotation(android.view.Surface.ROTATION_90)
                    .setResolutionSelector(resolutionSelector)
                    .build()
                imageCapture
            }
            SettingsManager.UploadMode.AV1 -> {
                yuvAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setResolutionSelector(resolutionSelector)
                    .setTargetRotation(android.view.Surface.ROTATION_90)
                    .build()
                yuvAnalysis!!
            }
        }

        try {
            cameraProvider.unbindAll()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            val camera = cameraProvider.bindToLifecycle(
                lifeOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview, secondaryUseCase
            )

            triggerAfAeLock(camera)

        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            shutdownCamera()
        }
    }

    private fun upload(capturedBytes: ByteArray?) {
        // ── Upload ────────────────────────────────────────────────────────────
        val secs = SettingsManager.getIntervalSeconds(applicationContext)
        if (capturedBytes != null) {
            customExecutor.execute { uploadImage(capturedBytes) }
        } else {
            Log.w(TAG, "Capture produced no bytes")
            updateNotification("Capture failed — retry in ${secs}s")
        }
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
            }
        )
    }


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
        val afReady = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
            || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED

        // Check for darkness
        val isTooDark = iso != null && iso > 1600
            && exposureTimeNs != null && exposureTimeNs > 33_333_333
        if (isTooDark or (aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)) {
            updateNotification("Too dark! (iso=$iso exposure=$exposureTimeNs)")
            Log.d(TAG, "Too dark! (iso=$iso exposure=$exposureTimeNs ae=$aeState)")
            return
        }

        // AE ready: converged, flash queued, or timed out.
        // CONVERGED is the common case; FLASH_REQUIRED means the device has
        // decided to fire flash — that is also an acceptable settled state.
        val aeReady = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
            || aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
            || (Build.VERSION.SDK_INT < 29 && aeState == null)

        if (afReady && aeReady) {
            Log.d(TAG, "3A converged in ${elapsed}ms (af=$afState ae=$aeState)")

            captureState.set(State.PICTURE_TAKEN)
            when (uploadMode) {
                SettingsManager.UploadMode.JPEG -> shootJpegAndShutdown(imageCapture)
                SettingsManager.UploadMode.AV1  -> shootYuvAndShutdown()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State machine trigger (called on main thread after warmup delay)
    // ─────────────────────────────────────────────────────────────────────────

    private fun triggerAfAeLock(
        camera: Camera
    ) {
        updateNotification("Locking AF + AE…")
        Log.d(TAG, "State → WAITING_3A")

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

    private fun shootJpegAndShutdown(
        imageCapture: ImageCapture
    ) {
        updateNotification("Capturing image…")
        Log.d(TAG, "State → PICTURE_TAKEN — firing takePicture")

        imageCapture.takePicture(
            CurrentThreadExecutor(),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bytes = runCatching { imageProxyToBytes(image) }.getOrNull()
                    image.close()
                    shutdownCamera()
                    upload(bytes)
                }
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "takePicture failed: ${exc.message}", exc)
                    shutdownCamera()
                }
            }
        )
    }


    private fun shootYuvAndShutdown() {
        val analysis = yuvAnalysis ?: run {
            Log.e(TAG, "shootYuvAndShutdown: no ImageAnalysis bound")
            shutdownCamera()
            return
        }
        updateNotification("Capturing YUV frame…")
        Log.d(TAG, "State → PICTURE_TAKEN — grabbing one YUV frame")

        val grabbed = AtomicInteger(0)
        analysis.setAnalyzer(CurrentThreadExecutor()) { image ->
            if (grabbed.compareAndSet(0, 1)) {
                val frame = try {
                    Yuv420Converter.toI420(image)
                } catch (t: Throwable) {
                    Log.e(TAG, "YUV → I420 conversion failed", t)
                    updateNotification("AV1 conversion failed")
                    null
                } finally {
                    image.close()
                    analysis.clearAnalyzer()
                    shutdownCamera()
                }
                if (frame != null) {
                    updateNotification("Encoding AV1 frame (${frame.width}x${frame.height})…")
                    val obus = Av1Streamer.encodeToObus(frame)
                    if (obus != null) {
                        uploadAv1Frame(obus, frame.width, frame.height)
                    } else {
                        updateNotification("AV1 encoding failed")
                    }
                }
            } else {
                image.close()
            }
        }
    }



    private fun shutdownCamera() {
        runCatching { cameraProvider.unbindAll() }
        captureState.set(State.PREVIEW)
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

    private fun uploadImage(jpeg: ByteArray) {
        val ctx = applicationContext
        val secs = SettingsManager.getIntervalSeconds(ctx)
        val uploadUrl = SettingsManager.getUploadUrl(ctx)
        if (uploadUrl.isBlank()) {
            updateNotification("No URL — tap icon to configure.")
            return
        }
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
            }
        } catch (e: IOException) {
            Log.e(TAG, "Upload IOException", e)
            updateNotification("Upload error — retry in ${secs}s")
        }
    }

    private fun uploadAv1Frame(obus: ByteArray, width: Int, height: Int) {
        val ctx = applicationContext
        val secs = SettingsManager.getIntervalSeconds(ctx)
        val uploadUrl = SettingsManager.getUploadUrl(ctx)
        if (uploadUrl.isBlank()) {
            updateNotification("No URL — tap icon to configure.")
            return
        }
        updateNotification("Uploading AV1 ${obus.size / 1024} KB…")
        Log.d(TAG, "Uploading AV1 ${obus.size} bytes to $uploadUrl")

        val timestamp = System.currentTimeMillis()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", "capture_$timestamp.obu",
                obus.toRequestBody("video/AV1".toMediaType())
            )
            .addFormDataPart("timestamp", timestamp.toString())
            .addFormDataPart("batlevel", getBatteryLevel().toString())
            .addFormDataPart("width", width.toString())
            .addFormDataPart("height", height.toString())
            .build()

        val requestBuilder = Request.Builder().url(uploadUrl).post(body)
        SettingsManager.getBasicAuthHeader(ctx)?.let { requestBuilder.header("Authorization", it) }

        try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "AV1 upload OK: ${response.code}")
                    updateNotification(
                        "Last upload: ${android.text.format.DateFormat.format("HH:mm:ss", timestamp)} ✓"
                    )
                } else {
                    Log.w(TAG, "AV1 upload failed: HTTP ${response.code}")
                    updateNotification("AV1 upload failed (HTTP ${response.code}) — retry in ${secs}s")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "AV1 upload IOException", e)
            updateNotification("AV1 upload error — retry in ${secs}s")
        }
    }

}


class CurrentThreadExecutor : Executor {
    override fun execute(r: Runnable) {
        r.run()
    }
}


