package com.camerauploader

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.util.Log
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.atomic.AtomicInteger

@ExperimentalCamera2Interop
class CameraUploaderWorker(
    val cameraProvider: ProcessCameraProvider,
    val lifecycleRegistry: LifecycleRegistry,
    val lifeOwner: LifecycleOwner,
    val applicationContext: android.content.Context,
    val service: CameraUploaderService,
    val updateNotification: (String) -> Unit,
) {

    // ── Preflight state machine constants ────────────────────────────────────
    private object State {
        const val PREVIEW       = 0
        const val WAITING_3A    = 1
        const val PICTURE_TAKEN = 2
    }

    companion object {
        private const val TAG = "CameraUploaderWorker"
        const val ACTION_CAPTURE = "com.camerauploader.ACTION_CAPTURE"
    }

    lateinit var imageCapture: ImageCapture

    val yuvConverter: Yuv420Converter = Yuv420Converter

    private val uploadMode: SettingsManager.UploadMode
        get() = SettingsManager.getUploadMode(applicationContext)

    // ─────────────────────────────────────────────────────────────────────────
    // Outer cycle
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("RestrictedApi")
    fun run() {
        Log.d(TAG, "Capture cycle started (mode=$uploadMode)")
        updateNotification("Preflight — warming up 3A…")

        val previewBuilder = Preview.Builder()
        installCaptureCallback(previewBuilder)

        val savedSize = SettingsManager.getResolution(applicationContext)
        val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setResolutionFilter { sizes, _ ->
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
                        ContextCompat.getMainExecutor(service)
                    ) { surface.release(); st.release() }
                }
            }
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setTargetRotation(android.view.Surface.ROTATION_90)
            .setResolutionSelector(resolutionSelector)
        val secondaryUseCase: androidx.camera.core.UseCase = when (uploadMode) {
            SettingsManager.UploadMode.JPEG -> {
                imageCapture = captureBuilder.setJpegQuality(85).build()
                imageCapture
            }
            SettingsManager.UploadMode.AV1 -> {
                imageCapture = captureBuilder.setBufferFormat(ImageFormat.YUV_420_888).build()
                imageCapture
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

    // ─────────────────────────────────────────────────────────────────────────
    // Camera2Interop: install CaptureCallback on Preview
    // ─────────────────────────────────────────────────────────────────────────

    private val captureState = AtomicInteger(State.PREVIEW)
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

    private fun processCaptureResult(result: CaptureResult) {
        if (captureState.get() != State.WAITING_3A) return

        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val elapsed = System.currentTimeMillis() - stateEnteredAt
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)

        val afReady = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
            || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED

        val isTooDark = iso != null && iso > 1600
            && exposureTimeNs != null && exposureTimeNs > 33_333_333
        if (isTooDark or (aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)) {
            updateNotification("Too dark! (iso=$iso exposure=$exposureTimeNs)")
            Log.d(TAG, "Too dark! (iso=$iso exposure=$exposureTimeNs ae=$aeState)")
            return
        }

        val aeReady = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
            || aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
            || (Build.VERSION.SDK_INT < 29 && aeState == null)

        if (afReady && aeReady) {
            Log.d(TAG, "3A converged in ${elapsed}ms (af=$afState ae=$aeState)")
            captureState.set(State.PICTURE_TAKEN)
            shootImageAndShutdown(imageCapture)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State machine trigger
    // ─────────────────────────────────────────────────────────────────────────

    private fun triggerAfAeLock(camera: Camera) {
        updateNotification("Locking AF + AE…")
        Log.d(TAG, "State → WAITING_3A")
        captureState.set(State.WAITING_3A)
        stateEnteredAt = System.currentTimeMillis()
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

    private fun shootImageAndShutdown(imageCapture: ImageCapture) {
        updateNotification("Capturing image…")
        Log.d(TAG, "State → PICTURE_TAKEN — firing takePicture")
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(service),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    if (uploadMode == SettingsManager.UploadMode.JPEG) {
                        val bytes = runCatching { imageProxyToBytes(image) }.getOrNull()
                        image.close()
                        shutdownCamera()
                        if (bytes != null) service.uploadJpeg(bytes)
                    } else if (uploadMode == SettingsManager.UploadMode.AV1) {
                        val frame = try {
                            yuvConverter.toI420(image)
                        } catch (t: Throwable) {
                            Log.e(TAG, "YUV → I420 conversion failed", t)
                            updateNotification("AV1 conversion failed")
                            null
                        } finally {
                            image.close()
                            shutdownCamera()
                        }
                        frame?.let { service.submitAv1Frame(it) }
                    }
                }
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "takePicture failed: ${exc.message}", exc)
                    shutdownCamera()
                }
            }
        )
    }


    private fun shutdownCamera() {
        // cameraProvider.unbindAll() and lifecycleRegistry must run on main thread
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
}
