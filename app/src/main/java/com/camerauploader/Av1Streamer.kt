package com.camerauploader

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object Av1Streamer {
    data class Frame(
        val buf: ByteBuffer,
        val width: Int,
        val height: Int,
        val yStride: Int,
        val uvStride: Int,
    )

    /** Encode [frame] to a concatenated OBU byte array, or null on failure. */
    fun encodeToObus(frame: Frame): ByteArray? {
        val enc = Av1Encoder.open(frame.width, frame.height) ?: run {
            Log.e(TAG, "Failed to open AV1 encoder")
            return null
        }
        return try {
            if (enc.sendFirstFrame(frame.buf, frame.yStride, frame.uvStride) != 0) {
                Log.e(TAG, "sendFirstFrame failed")
                return null
            }
            enc.sendEos()
            val out = ByteArrayOutputStream()
            val pkt = Av1Encoder.Packet()
            while (true) {
                enc.getPacket(pkt)
                when (pkt.status) {
                    Av1Encoder.Status.OK -> pkt.payload?.let { out.write(it) }
                    else -> break
                }
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Log.e(TAG, "AV1 encoding error", t)
            null
        } finally {
            enc.close()
        }
    }

    private const val TAG = "Av1Streamer"
}
