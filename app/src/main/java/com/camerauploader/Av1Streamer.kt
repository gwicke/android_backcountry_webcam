package com.camerauploader

import java.nio.ByteBuffer

object Av1Streamer {
    data class Frame(
        val buf: ByteBuffer,
        val width: Int,
        val height: Int,
        val yStride: Int,
        val uvStride: Int,
    )
}
