#include <jni.h>
#include <stdint.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>

/*
 * The pointer's paint maps (LaserPaint) written straight into HardwareBuffers the GPU samples:
 * the phone's memory is shared by its CPU and GPU, so new paint costs a copy of only the rows it
 * changed, never a full upload. See PaintBuffers.kt.
 */
#define FN(name) Java_com_oleroma_gyrophone_ui_PaintBuffers_##name

static uint8_t *lockRows(AHardwareBuffer *buf, uint32_t *stride) {
    if (buf == NULL) return NULL;
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buf, &desc);
    *stride = desc.stride;
    void *addr = NULL;
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, &addr) != 0) return NULL;
    return (uint8_t *) addr;
}

/*
 * Rows [j0] .. [j1] of the six alpha maps into two RGBA buffers: [a] gets amount, fire, aim, glow
 * (r, g, b, a); [b] gets glowFire, glowAim (r, g). False when a buffer could not be locked.
 */
JNIEXPORT jboolean JNICALL FN(nativeWritePaint)(
    JNIEnv *env, jclass cls, jobject bufA, jobject bufB, jint width, jint j0, jint j1,
    jbyteArray amount, jbyteArray fire, jbyteArray aim, jbyteArray glow, jbyteArray glowFire, jbyteArray glowAim) {
    AHardwareBuffer *a = AHardwareBuffer_fromHardwareBuffer(env, bufA);
    AHardwareBuffer *b = AHardwareBuffer_fromHardwareBuffer(env, bufB);
    uint32_t sa = 0, sb = 0;
    uint8_t *pa = lockRows(a, &sa);
    if (pa == NULL) return JNI_FALSE;
    uint8_t *pb = lockRows(b, &sb);
    if (pb == NULL) {
        AHardwareBuffer_unlock(a, NULL);
        return JNI_FALSE;
    }
    jbyte *am = (*env)->GetPrimitiveArrayCritical(env, amount, NULL);
    jbyte *fi = (*env)->GetPrimitiveArrayCritical(env, fire, NULL);
    jbyte *ai = (*env)->GetPrimitiveArrayCritical(env, aim, NULL);
    jbyte *gl = (*env)->GetPrimitiveArrayCritical(env, glow, NULL);
    jbyte *gf = (*env)->GetPrimitiveArrayCritical(env, glowFire, NULL);
    jbyte *ga = (*env)->GetPrimitiveArrayCritical(env, glowAim, NULL);
    if (am && fi && ai && gl && gf && ga) {
        for (jint j = j0; j <= j1; j++) {
            const int src = j * width;
            uint8_t *ra = pa + (size_t) j * sa * 4;
            uint8_t *rb = pb + (size_t) j * sb * 4;
            for (jint i = 0; i < width; i++) {
                ra[0] = (uint8_t) am[src + i];
                ra[1] = (uint8_t) fi[src + i];
                ra[2] = (uint8_t) ai[src + i];
                ra[3] = (uint8_t) gl[src + i];
                rb[0] = (uint8_t) gf[src + i];
                rb[1] = (uint8_t) ga[src + i];
                rb[2] = 0;
                rb[3] = 0;
                ra += 4;
                rb += 4;
            }
        }
    }
    if (ga) (*env)->ReleasePrimitiveArrayCritical(env, glowAim, ga, JNI_ABORT);
    if (gf) (*env)->ReleasePrimitiveArrayCritical(env, glowFire, gf, JNI_ABORT);
    if (gl) (*env)->ReleasePrimitiveArrayCritical(env, glow, gl, JNI_ABORT);
    if (ai) (*env)->ReleasePrimitiveArrayCritical(env, aim, ai, JNI_ABORT);
    if (fi) (*env)->ReleasePrimitiveArrayCritical(env, fire, fi, JNI_ABORT);
    if (am) (*env)->ReleasePrimitiveArrayCritical(env, amount, am, JNI_ABORT);
    AHardwareBuffer_unlock(b, NULL);
    AHardwareBuffer_unlock(a, NULL);
    return JNI_TRUE;
}

/* Rows [j0] .. [j1] of the way colours (ARGB ints) into the RGBA buffer [w]. */
JNIEXPORT jboolean JNICALL FN(nativeWriteWay)(JNIEnv *env, jclass cls, jobject bufW, jint width, jint j0, jint j1, jintArray way) {
    AHardwareBuffer *w = AHardwareBuffer_fromHardwareBuffer(env, bufW);
    uint32_t sw = 0;
    uint8_t *pw = lockRows(w, &sw);
    if (pw == NULL) return JNI_FALSE;
    jint *c = (*env)->GetPrimitiveArrayCritical(env, way, NULL);
    if (c) {
        for (jint j = j0; j <= j1; j++) {
            const jint *s = c + j * width;
            uint8_t *r = pw + (size_t) j * sw * 4;
            for (jint i = 0; i < width; i++) {
                const uint32_t v = (uint32_t) s[i];
                r[0] = (uint8_t) (v >> 16);
                r[1] = (uint8_t) (v >> 8);
                r[2] = (uint8_t) v;
                r[3] = (uint8_t) (v >> 24);
                r += 4;
            }
        }
        (*env)->ReleasePrimitiveArrayCritical(env, way, c, JNI_ABORT);
    }
    AHardwareBuffer_unlock(w, NULL);
    return JNI_TRUE;
}
