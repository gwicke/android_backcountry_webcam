package com.camerauploader

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns one long-lived AV1 streaming session:
 *
 *   camera worker
 *      └── submitFrame(I420 buffer)
 *               └── frameQueue ──► encoder thread ──► Av1Encoder
 *                                                          │
 *                                                          ▼
 *                                  packetQueue ◄── packet reader thread
 *                                          │
 *                                          ▼
 *                            chunked POST RequestBody.writeTo(sink)
 *
 * One session runs until the chunked POST fails or the streamer is stopped.
 * On failure the encoder is reopened and a fresh POST is started — the new
 * stream begins with a key frame so the server can decode from byte zero.
 *
 * The OkHttpClient is supplied externally so it can be shared with other
 * uploads and so connection pooling / keep-alive applies across reconnects.
 */
class Av1Streamer(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val updateNotification: (String) -> Unit,
) {
    /**
     * A single I420 frame ready to feed the encoder.  buf must be a direct
     * ByteBuffer holding Y|U|V planes back-to-back, with the given strides.
     */
    data class Frame(
        val buf: ByteBuffer,
        val width: Int,
        val height: Int,
        val yStride: Int,
        val uvStride: Int,
    )

    private val running = AtomicBoolean(false)
    private var sessionThread: Thread? = null

    /**
     * Unbounded queue.  Frame arrival is alarm-driven (one frame every
     * `interval_seconds`, typically minutes), so the encoder never falls
     * behind in steady state.  We rely on the encoder running synchronously
     * with the producer rather than dropping frames.
     */
    private val frameQueue = LinkedBlockingQueue<Frame>()

    /**
     * Hand a freshly captured frame to the streamer.  Lazily starts a session
     * on the first frame; subsequent calls just push onto the queue.  If the
     * queue is full the oldest frame is dropped — for a slow-moving webcam
     * latency matters more than retaining every frame.
     */
    fun submitFrame(frame: Frame) {
        ensureStarted()
        frameQueue.put(frame)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // Push a poison frame so the session loop wakes up.
        frameQueue.clear()
        sessionThread?.interrupt()
        sessionThread = null
    }

    private fun ensureStarted() {
        if (running.get()) return
        synchronized(this) {
            if (running.get()) return
            running.set(true)
            sessionThread = Thread(::sessionLoop, "Av1StreamerSession").also { it.start() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session loop — open encoder + POST, run until either fails, repeat.
    // ─────────────────────────────────────────────────────────────────────────

    private fun sessionLoop() {
        var backoffMs = 0L
        while (running.get()) {
            if (backoffMs > 0) {
                try { Thread.sleep(backoffMs) }
                catch (_: InterruptedException) { return }
            }

            // Wait for a frame so we know the resolution to open the encoder
            // with.  The first queued frame is the one we'll send as keyframe.
            // No timeout — the alarm-driven producer may take minutes between
            // frames; we just park here until a frame arrives or stop() runs.
            val firstFrame = try { frameQueue.take() }
                catch (_: InterruptedException) { return }

            val enc = Av1Encoder.open(firstFrame.width, firstFrame.height)
            if (enc == null) {
                Log.e(TAG, "Failed to open AV1 encoder")
                updateNotification("AV1 encoder init failed")
                backoffMs = 10_000L
                continue
            }

            updateNotification("AV1 streaming — connecting…")
            val ok = runOneSession(enc, firstFrame)
            try { enc.close() } catch (_: Throwable) {}

            backoffMs = if (ok) 0L else 5_000L
            if (!ok && running.get()) {
                updateNotification("AV1 stream lost — reconnecting in ${backoffMs/1000}s")
            }
        }
    }

    /**
     * Run one encode + upload session until the upload fails or returns.
     * @return true if shut down cleanly, false if it died of an error.
     */
    private fun runOneSession(enc: Av1Encoder, firstFrame: Frame): Boolean {
        val packetQueue = LinkedBlockingQueue<ByteArray>()
        val sessionAlive = AtomicBoolean(true)

        // ── Sender: drain frameQueue, push to encoder ──────────────────────
        // Frames arrive on the alarm cadence (typically minutes apart), so
        // the sender just blocks on take() indefinitely between them.  It
        // exits only when the session is torn down (interrupt) or the
        // encoder rejects a frame.
        val sender = Thread({
            try {
                var r = enc.sendFirstFrame(firstFrame.buf, firstFrame.yStride, firstFrame.uvStride)
                if (r != 0) { sessionAlive.set(false); return@Thread }
                while (sessionAlive.get() && running.get()) {
                    val f = try { frameQueue.take() }
                        catch (_: InterruptedException) { break }
                    r = enc.sendFrame(f.buf, f.yStride, f.uvStride, -1)
                    if (r != 0) { sessionAlive.set(false); break }
                }
            } finally {
                try { enc.sendEos() } catch (_: Throwable) {}
            }
        }, "Av1Sender").apply { start() }

        // ── Reader: pull packets, push to chunked POST writeTo() ───────────
        val reader = Thread({
            val pkt = Av1Encoder.Packet()
            while (true) {
                enc.getPacket(pkt)
                when (pkt.status) {
                    Av1Encoder.Status.OK -> pkt.payload?.let { packetQueue.put(it) }
                    Av1Encoder.Status.EOS -> { packetQueue.put(EOS); break }
                    else -> { packetQueue.put(EOS); break }
                }
            }
        }, "Av1Reader").apply { start() }

        // ── HTTP: one long chunked POST whose writeTo() drains packetQueue ─
        val url = SettingsManager.getUploadUrl(context)
        if (url.isBlank()) {
            sessionAlive.set(false)
            sender.interrupt(); reader.interrupt()
            updateNotification("AV1: no upload URL configured")
            return false
        }

        val body = object : RequestBody() {
            override fun contentType() = "video/AV1".toMediaType()
            override fun contentLength(): Long = -1L           // forces chunked
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) {
                var firstChunk = true
                while (true) {
                    val chunk = try { packetQueue.take() }
                        catch (_: InterruptedException) { return }
                    if (chunk === EOS) return
                    sink.write(chunk)
                    sink.flush()
                    if (firstChunk) {
                        firstChunk = false
                        updateNotification("AV1 streaming — connected")
                    }
                }
            }
        }

        val builder = Request.Builder().url(url).post(body)
            .header("X-Stream-Format", "av1-obus")
            .header("X-Stream-Width", firstFrame.width.toString())
            .header("X-Stream-Height", firstFrame.height.toString())
        SettingsManager.getBasicAuthHeader(context)?.let {
            builder.header("Authorization", it)
        }

        var clean = false
        try {
            httpClient.newCall(builder.build()).execute().use { resp ->
                clean = resp.isSuccessful
                Log.i(TAG, "AV1 stream POST closed: HTTP ${resp.code}")
                if (!clean) {
                    updateNotification("AV1 stream rejected (HTTP ${resp.code})")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "AV1 stream POST failed: ${e.message}")
            clean = false
        } finally {
            sessionAlive.set(false)
            sender.interrupt()
            // The reader is parked inside getPacket(); sender already issued
            // sendEos() so the reader will exit on its own EOS.  Just join.
            try { sender.join(2_000) } catch (_: InterruptedException) {}
            try { reader.join(5_000) } catch (_: InterruptedException) {}
        }
        return clean
    }

    companion object {
        private const val TAG = "Av1Streamer"
        private val EOS = ByteArray(0)
    }
}

