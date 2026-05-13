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
    // Re-use buffers across conversions for efficiency
    var outBuf: ByteBuffer = ByteBuffer.allocate(0)
    var rowBuf: ByteArray = ByteArray(0)

    fun toI420(image: ImageProxy): Av1Streamer.Frame {
        val w = image.width
        val h = image.height
        val yStride = w
        val uvStride = w / 2
        val ySize = yStride * h
        val uvSize = uvStride * (h / 2)
        val planes = image.planes
        val yPlaneBuffer = planes[0].buffer
        val uPlaneBuffer = planes[1].buffer
        val vPlaneBuffer = planes[2].buffer

        // Check if the planes are already backed by a contiguous buffer
        if (planes[1].pixelStride == 1
            && yPlaneBuffer.hasArray()
            && yPlaneBuffer.array().size == ySize + 2 * uvSize
            && uPlaneBuffer.hasArray()
            && vPlaneBuffer.hasArray()
            && yPlaneBuffer.array() === planes[1].buffer.array()
            && yPlaneBuffer.array() === planes[2].buffer.array()
            && yPlaneBuffer.arrayOffset() == 0
        ) {
            val arrayBuf = ByteBuffer.wrap(
                planes[0].buffer.array(),
                0, ySize + 2 * uvSize
            )
            // Reuse buffer as-is
            return Av1Streamer.Frame(arrayBuf, w, h, yStride, uvStride)
        }

        if (outBuf.capacity() < ySize + 2 * uvSize) {
            outBuf = ByteBuffer.allocateDirect(ySize + 2 * uvSize)
        }

        // ── Y plane: tight-pack into [0..ySize); always contiguous
        outBuf.position(0)
        yPlaneBuffer.position(0)
        outBuf.put(yPlaneBuffer)

        // ── U plane ───────────────────────────────────────────────────────
        copyPlane(
            src = planes[1].buffer,
            srcRowStride = planes[1].rowStride,
            srcPixelStride = planes[1].pixelStride,
            width = w / 2, height = h / 2,
            dst = outBuf, dstOffset = ySize, dstRowStride = uvStride,
        )

        // ── V plane ───────────────────────────────────────────────────────
        copyPlane(
            src = planes[2].buffer,
            srcRowStride = planes[2].rowStride,
            srcPixelStride = planes[2].pixelStride,
            width = w / 2, height = h / 2,
            dst = outBuf, dstOffset = ySize + uvSize, dstRowStride = uvStride,
        )

        outBuf.position(0)
        return Av1Streamer.Frame(outBuf, w, h, yStride, uvStride)
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
        if (srcPixelStride == 1 && srcRowStride == width) {
            // Contiguous plane, copy in one go
            src.position(0)
            dst.position(dstOffset)
            dst.put(src)
        } else {
            // Interleaved, need to copy row by row
            if (rowBuf.size != width) {
                rowBuf = ByteArray(width)
            }
            for (row in 0 until height) {
                val rowStart = row * srcRowStride
                // Semi-planar (NV12/NV21): pixelStride == 2, samples interleaved.
                var srcIdx = rowStart
                for (col in 0 until width) {
                    rowBuf[col] = src.get(srcIdx)
                    srcIdx += srcPixelStride
                }
                val dstStart = dstOffset + row * dstRowStride
                dst.position(dstStart)
                dst.put(rowBuf, 0, width)
            }
        }
    }
}
