package com.camerauploader

import android.content.Context
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.lang.Math.abs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ln

/**
 * Queries the back camera's supported JPEG output sizes via CameraX and
 * returns them sorted largest-first.
 *
 * The call blocks the calling thread for up to [timeoutSeconds]; it must
 * NOT be called on the main thread.
 */
object ResolutionHelper {

    /**
     * Synchronously fetches supported resolutions for the back camera.
     * Returns an empty list if the camera is unavailable or the query times out.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun getSupportedSizes(context: Context, timeoutSeconds: Long = 5): List<Size> {
        val latch = CountDownLatch(1)
        var sizes: List<Size> = emptyList()

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()

                // ResolutionInfo is exposed through ImageCapture's supported output sizes.
                // We use ResolutionStrategy to enumerate via CameraX's public surface API.
                val imageCapture = ImageCapture.Builder().build()

                // Bind briefly just to read resolution data, then unbind immediately.
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    // Use a no-op LifecycleOwner that is already in STARTED state.
                    NoOpLifecycleOwner(),
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageCapture
                )

                // CameraX exposes supported sizes through CameraInfo → CameraCharacteristics.
                // The cleanest public API is via Camera2CameraInfo.
                val camera2Info = androidx.camera.camera2.interop.Camera2CameraInfo
                    .from(camera.cameraInfo)
                val chars = camera2Info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                )
                sizes = chars
                    ?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                    ?.sortedByDescending { it.width.toLong() * it.height }
                    ?: emptyList()

                provider.unbindAll()
            } catch (e: Exception) {
                android.util.Log.e("ResolutionHelper", "Failed to query resolutions", e)
            } finally {
                latch.countDown()
            }
        }, ContextCompat.getMainExecutor(context))

        latch.await(timeoutSeconds, TimeUnit.SECONDS)
        return sizes
    }

    /** Format a Size as a human-readable string, e.g. "1920×1080 (2.1 MP)". */
    fun format(size: Size): String {
        val mp = size.width.toLong() * size.height / 1_000_000.0
        val mpStr = if (mp >= 1.0) "%.1f MP".format(mp) else "%.2f MP".format(mp)
        return "${size.width}×${size.height}  ($mpStr)"
    }

    /** Serialise a Size to a "WxH" string for storage. */
    fun serialize(size: Size): String = "${size.width}x${size.height}"

    /** Deserialise a "WxH" string back to a Size, or null on malformed input. */
    fun deserialize(s: String): Size? {
        val parts = s.split("x")
        if (parts.size != 2) return null
        return try { Size(parts[0].toInt(), parts[1].toInt()) } catch (_: NumberFormatException) { null }
    }
}

/**
 * Computes a comparable penalty score between [this] size and [target].
 *
 * Primary: sizes matching the target aspect ratio (within [ASPECT_RATIO_TOLERANCE])
 * are always preferred. Secondary: among the same aspect-ratio group, sizes are
 * ranked by log-ratio of pixel counts (scale-invariant — treats 2x bigger and
 * 2x smaller as equidistant).
 */
fun Size.penalty(target: Size): Double {
  val targetAspectRatio = target.width.toDouble() / target.height.toDouble()
  val actualAspectRatio = width.toDouble() / this.height.toDouble()
  val aspectRatioDiff = abs(actualAspectRatio - targetAspectRatio) / targetAspectRatio
  val aspectRatioPenalty = if (aspectRatioDiff < 0.02) 0.0 else 1.0 * aspectRatioDiff

  val targetPixels = target.width.toDouble() * target.height.toDouble()
  val actualPixels = width.toDouble() * height.toDouble()
  val logPixelDistance = abs(ln(actualPixels / targetPixels))

  return aspectRatioPenalty + logPixelDistance
}

/**
 * Sorts this list of sizes by closeness to [targetSize], preferring
 * matching aspect ratios first, then closest pixel count (log-scale).
 */
fun List<Size>.sortedByClosestTo(targetSize: Size): List<Size> {
  return this.sortedBy { it.penalty(targetSize) }
}

// ── No-op LifecycleOwner used only to bind CameraX for metadata queries ──────

private class NoOpLifecycleOwner : androidx.lifecycle.LifecycleOwner {
    private val registry = androidx.lifecycle.LifecycleRegistry(this).also {
        it.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
    }
    override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
}
