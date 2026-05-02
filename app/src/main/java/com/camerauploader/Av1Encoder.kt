package com.camerauploader

import java.nio.ByteBuffer

/**
 * Thin Kotlin wrapper around the native svtav1_enc bridge (libav1enc_jni.so).
 *
 * Lifecycle mirrors the C API:
 *   open() -> sendFirstFrame() -> sendFrame()* -> sendEos() -> close()
 *
 * Output is consumed via getPacket() on a separate thread.  SVT-AV1 is
 * internally thread-safe; the send and receive threads do not need any
 * external locking.
 *
 * Frame buffers must be direct ByteBuffers laid out as I420:
 *   Y plane (width * height)        with stride yStride
 *   U plane (width/2 * height/2)    with stride uvStride
 *   V plane (width/2 * height/2)    with stride uvStride
 * stored back-to-back in the same buffer.
 */
class Av1Encoder private constructor(
    val width: Int,
    val height: Int,
    private var handle: Long,
) {
    /** Status codes mirror Av1GetPacketResult in the C header. */
    object Status {
        const val OK    = 0
        const val EOS   = 1
        const val ERROR = -1
    }

    /** Filled in by nativeGetPacket(). */
    class Packet {
        @JvmField var payload: ByteArray? = null
        @JvmField var pts: Long = 0
        @JvmField var isKey: Boolean = false
        @JvmField var nativeHandle: Long = 0
        @JvmField var status: Int = Status.ERROR

        fun reset() {
            payload = null
            pts = 0
            isKey = false
            nativeHandle = 0
            status = Status.ERROR
        }
    }

    fun sendFirstFrame(buf: ByteBuffer, yStride: Int, uvStride: Int): Int {
        require(buf.isDirect) { "Av1Encoder requires a direct ByteBuffer" }
        return nativeSendFirstFrame(handle, buf, yStride, uvStride)
    }

    fun sendFrame(buf: ByteBuffer, yStride: Int, uvStride: Int, pts: Long = -1): Int {
        require(buf.isDirect) { "Av1Encoder requires a direct ByteBuffer" }
        return nativeSendFrame(handle, buf, yStride, uvStride, pts)
    }

    fun sendEos(): Int = nativeSendEos(handle)

    /** Blocks until the next packet is available or the stream ends. */
    fun getPacket(into: Packet) {
        into.reset()
        nativeGetPacket(handle, into)
    }

    fun close() {
        val h = handle
        handle = 0
        if (h != 0L) nativeClose(h)
    }

    companion object {
        init {
            System.loadLibrary("av1enc_jni")
        }

        /**
         * @param crf         AV1 CRF [0..63]; lower = higher quality.
         * @param encMode     SVT-AV1 preset [0..10]; higher = faster.
         * @param parallelism 0 = auto, 1..6 increasing parallelism / memory.
         */
        fun open(
            width: Int,
            height: Int,
            crf: Int = 35,
            encMode: Int = 9,
            parallelism: Int = 1,
        ): Av1Encoder? {
            val h = nativeOpen(width, height, crf, encMode, parallelism)
            if (h == 0L) return null
            return Av1Encoder(width, height, h)
        }

        @JvmStatic private external fun nativeOpen(
            width: Int, height: Int, crf: Int, encMode: Int, parallelism: Int,
        ): Long
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeSendFirstFrame(
            handle: Long, buf: ByteBuffer, yStride: Int, uvStride: Int,
        ): Int
        @JvmStatic private external fun nativeSendFrame(
            handle: Long, buf: ByteBuffer, yStride: Int, uvStride: Int, pts: Long,
        ): Int
        @JvmStatic private external fun nativeSendEos(handle: Long): Int
        @JvmStatic private external fun nativeGetPacket(handle: Long, into: Packet)
    }
}
