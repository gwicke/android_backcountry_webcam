package com.camerauploader

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Converts a CameraX YUV_420_888 [ImageProxy] into the contiguous I420
 * layout the SVT-AV1 wrapper expects:
 *
 *   buffer = [ Y plane (yStride * height) ]
 *            [ U plane (uvStride * height/2) ]
 *            [ V plane (uvStride * height/2) ]
 *
 * The result is a direct ByteBuffer so it can be passed by pointer through
 * JNI without a copy at the boundary.
 *
 * YUV_420_888 buffers from CameraX may be planar (I420), semi-planar
 * (NV12/NV21 with pixelStride == 2), and have arbitrary row strides.  This
 * routine handles all three by walking row by row and de-interleaving the
 * chroma planes when needed.
 */
object Yuv420Converter {

    fun toI420(image: ImageProxy): Av1Streamer.Frame {
        val w = image.width
        val h = image.height
        val yStride = w
        val uvStride = w / 2
        val ySize = yStride * h
        val uvSize = uvStride * (h / 2)
        val out = ByteBuffer.allocateDirect(ySize + 2 * uvSize)

        val planes = image.planes

        // ── Y plane: tight-pack into [0..ySize) ───────────────────────────
        copyPlane(
            src = planes[0].buffer,
            srcRowStride = planes[0].rowStride,
            srcPixelStride = planes[0].pixelStride,
            width = w, height = h,
            dst = out, dstOffset = 0, dstRowStride = yStride,
        )

        // ── U plane ───────────────────────────────────────────────────────
        copyPlane(
            src = planes[1].buffer,
            srcRowStride = planes[1].rowStride,
            srcPixelStride = planes[1].pixelStride,
            width = w / 2, height = h / 2,
            dst = out, dstOffset = ySize, dstRowStride = uvStride,
        )

        // ── V plane ───────────────────────────────────────────────────────
        copyPlane(
            src = planes[2].buffer,
            srcRowStride = planes[2].rowStride,
            srcPixelStride = planes[2].pixelStride,
            width = w / 2, height = h / 2,
            dst = out, dstOffset = ySize + uvSize, dstRowStride = uvStride,
        )

        out.position(0)
        return Av1Streamer.Frame(out, w, h, yStride, uvStride)
    }

    private fun copyPlane(
        src: ByteBuffer,
        srcRowStride: Int,
        srcPixelStride: Int,
        width: Int,
        height: Int,
        dst: ByteBuffer,
        dstOffset: Int,
        dstRowStride: Int,
    ) {
        val rowBuf = ByteArray(width)
        for (row in 0 until height) {
            val rowStart = row * srcRowStride
            if (srcPixelStride == 1 && srcRowStride >= width) {
                src.position(rowStart)
                src.get(rowBuf, 0, width)
            } else {
                // Semi-planar (NV12/NV21): pixelStride == 2, samples interleaved.
                var srcIdx = rowStart
                for (col in 0 until width) {
                    rowBuf[col] = src.get(srcIdx)
                    srcIdx += srcPixelStride
                }
            }
            val dstStart = dstOffset + row * dstRowStride
            dst.position(dstStart)
            dst.put(rowBuf, 0, width)
        }
    }
}
