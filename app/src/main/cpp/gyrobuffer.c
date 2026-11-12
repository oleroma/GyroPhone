#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>

#define FN(name) Java_com_oleroma_gyrophone_sensor_HardwareBufferReader_##name

/* Takes a reference to the HardwareBuffer's native buffer; 0 on failure. */
JNIEXPORT jlong JNICALL FN(nativeAcquire)(JNIEnv *env, jclass cls, jobject hardwareBuffer) {
    AHardwareBuffer *buf = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (buf == NULL) return 0;
    AHardwareBuffer_acquire(buf);
    return (jlong) (intptr_t) buf;
}

/* A sensor event's size in the direct channel's ring (DirectReportParser.EVENT_SIZE). */
#define EVENT_SIZE 104

/*
 * Copies the first [size] bytes into [dst]; false when the buffer could not be locked. With
 * [check] >= 0 (a byte offset) only when the sensor event there is newer than counter [after]
 * (false otherwise): one lock for the check and the copy.
 */
JNIEXPORT jboolean JNICALL FN(nativeRead)(JNIEnv *env, jclass cls, jlong handle, jbyteArray dst, jint size, jint check, jlong after) {
    AHardwareBuffer *buf = (AHardwareBuffer *) (intptr_t) handle;
    void *addr = NULL;
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, &addr) != 0 || addr == NULL) {
        return JNI_FALSE;
    }
    if (check >= 0 && check + 16 <= size) {
        /* Little endian, as the parser reads it: int32 size at 0, uint32 counter at 12. */
        const uint8_t *e = (const uint8_t *) addr + check;
        int32_t len;
        uint32_t counter;
        memcpy(&len, e, sizeof len);
        memcpy(&counter, e + 12, sizeof counter);
        if (len != EVENT_SIZE || (jlong) counter <= after) {
            AHardwareBuffer_unlock(buf, NULL);
            return JNI_FALSE;
        }
    }
    (*env)->SetByteArrayRegion(env, dst, 0, size, (const jbyte *) addr);
    AHardwareBuffer_unlock(buf, NULL);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL FN(nativeRelease)(JNIEnv *env, jclass cls, jlong handle) {
    if (handle != 0) AHardwareBuffer_release((AHardwareBuffer *) (intptr_t) handle);
}
