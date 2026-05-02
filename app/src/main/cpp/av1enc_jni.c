/*
 * JNI bridge for the svtav1_enc wrapper.
 *
 * One-to-one mapping from the C API in svtav1_wrap.h onto the Kotlin
 * Av1Encoder class.  All YUV input is passed as a single contiguous I420
 * direct ByteBuffer (Y plane | U plane | V plane); strides come in as
 * separate arguments.  Output packets are returned as a small Java object
 * that owns the SVT-AV1 buffer header pointer for later release.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "svtav1_wrap.h"

#define LOG_TAG "Av1EncJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

/* Cached method/field IDs for the Packet struct. */
static jclass    g_packet_cls   = NULL;
static jfieldID  g_fid_payload  = NULL;
static jfieldID  g_fid_pts      = NULL;
static jfieldID  g_fid_isKey    = NULL;
static jfieldID  g_fid_handle   = NULL;
static jfieldID  g_fid_status   = NULL;

static int ensure_packet_class(JNIEnv *env) {
    if (g_packet_cls) return 0;
    jclass local = (*env)->FindClass(env, "com/camerauploader/Av1Encoder$Packet");
    if (!local) return -1;
    g_packet_cls  = (jclass)(*env)->NewGlobalRef(env, local);
    g_fid_payload = (*env)->GetFieldID(env, g_packet_cls, "payload", "[B");
    g_fid_pts     = (*env)->GetFieldID(env, g_packet_cls, "pts",     "J");
    g_fid_isKey   = (*env)->GetFieldID(env, g_packet_cls, "isKey",   "Z");
    g_fid_handle  = (*env)->GetFieldID(env, g_packet_cls, "nativeHandle", "J");
    g_fid_status  = (*env)->GetFieldID(env, g_packet_cls, "status",  "I");
    return (g_fid_payload && g_fid_pts && g_fid_isKey
            && g_fid_handle && g_fid_status) ? 0 : -1;
}

JNIEXPORT jlong JNICALL
Java_com_camerauploader_Av1Encoder_nativeOpen(JNIEnv *env, jclass clazz,
                                              jint width, jint height,
                                              jint crf, jint encMode,
                                              jint parallelism) {
    Av1EncConfig cfg;
    av1enc_config_default(&cfg);
    cfg.width       = (uint32_t)width;
    cfg.height      = (uint32_t)height;
    cfg.crf         = (uint32_t)crf;
    cfg.enc_mode    = (uint32_t)encMode;
    cfg.parallelism = (uint32_t)parallelism;
    Av1Encoder *enc = av1enc_open(&cfg);
    if (!enc) {
        LOGE("av1enc_open failed for %dx%d crf=%d", width, height, crf);
        return 0;
    }
    return (jlong)(uintptr_t)enc;
}

JNIEXPORT void JNICALL
Java_com_camerauploader_Av1Encoder_nativeClose(JNIEnv *env, jclass clazz,
                                               jlong handle) {
    if (!handle) return;
    av1enc_close((Av1Encoder *)(uintptr_t)handle);
}

/*
 * Common send path.  buf is a direct ByteBuffer holding the I420 frame
 * (Y plane first, then U, then V) in a single contiguous allocation.
 */
static jint send_common(JNIEnv *env, jlong handle, jobject buf,
                        jint yStride, jint uvStride, jlong pts,
                        int isFirst) {
    if (!handle || !buf) return -1;
    uint8_t *base = (uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (!base) {
        LOGE("send_common: ByteBuffer is not direct");
        return -1;
    }
    Av1Encoder *enc = (Av1Encoder *)(uintptr_t)handle;
    if (isFirst) {
        return (jint)av1enc_send_first_frame(enc, base,
                                             (uint32_t)yStride,
                                             (uint32_t)uvStride);
    }
    return (jint)av1enc_send_frame(enc, base,
                                   (uint32_t)yStride,
                                   (uint32_t)uvStride,
                                   (int64_t)pts);
}

JNIEXPORT jint JNICALL
Java_com_camerauploader_Av1Encoder_nativeSendFirstFrame(JNIEnv *env, jclass clazz,
                                                        jlong handle, jobject buf,
                                                        jint yStride, jint uvStride) {
    return send_common(env, handle, buf, yStride, uvStride, 0, 1);
}

JNIEXPORT jint JNICALL
Java_com_camerauploader_Av1Encoder_nativeSendFrame(JNIEnv *env, jclass clazz,
                                                   jlong handle, jobject buf,
                                                   jint yStride, jint uvStride,
                                                   jlong pts) {
    return send_common(env, handle, buf, yStride, uvStride, pts, 0);
}

JNIEXPORT jint JNICALL
Java_com_camerauploader_Av1Encoder_nativeSendEos(JNIEnv *env, jclass clazz,
                                                 jlong handle) {
    if (!handle) return -1;
    return (jint)av1enc_send_eos((Av1Encoder *)(uintptr_t)handle);
}

/*
 * Block until the encoder produces a packet, then copy the payload into a
 * fresh byte[] and stash the SVT-AV1 buffer pointer so Kotlin can release it
 * later.  The copy is unavoidable here — JNI byte arrays cannot wrap a
 * foreign-owned region.  In practice the cost is negligible compared to the
 * encode itself.
 */
JNIEXPORT void JNICALL
Java_com_camerauploader_Av1Encoder_nativeGetPacket(JNIEnv *env, jclass clazz,
                                                   jlong handle, jobject pktObj) {
    if (ensure_packet_class(env) != 0 || !handle || !pktObj) {
        if (pktObj) (*env)->SetIntField(env, pktObj, g_fid_status, -1);
        return;
    }
    Av1Encoder *enc = (Av1Encoder *)(uintptr_t)handle;
    Av1Packet pkt;
    memset(&pkt, 0, sizeof(pkt));

    Av1GetPacketResult r = av1enc_get_packet(enc, &pkt);
    (*env)->SetIntField(env, pktObj, g_fid_status, (jint)r);
    if (r != AV1ENC_PKT_OK) {
        return;
    }

    jbyteArray arr = (*env)->NewByteArray(env, (jsize)pkt.payload_size);
    if (!arr) {
        av1enc_packet_free(&pkt);
        (*env)->SetIntField(env, pktObj, g_fid_status, (jint)AV1ENC_PKT_ERROR);
        return;
    }
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize)pkt.payload_size,
                               (const jbyte *)pkt.payload);
    (*env)->SetObjectField(env, pktObj, g_fid_payload, arr);
    (*env)->SetLongField  (env, pktObj, g_fid_pts,     (jlong)pkt.pts);
    (*env)->SetBooleanField(env, pktObj, g_fid_isKey,  pkt.is_key ? JNI_TRUE : JNI_FALSE);

    /*
     * We've copied the payload, so we can release the SVT-AV1 buffer now
     * rather than handing the pointer back to Kotlin.  This keeps Kotlin
     * code entirely free of native lifetime concerns.
     */
    av1enc_packet_free(&pkt);
    (*env)->DeleteLocalRef(env, arr);
}
