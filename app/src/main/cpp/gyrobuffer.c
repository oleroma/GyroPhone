#include <jni.h>
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

/* Copies the first [size] bytes into [dst]; false when the buffer could not be locked. */
JNIEXPORT jboolean JNICALL FN(nativeRead)(JNIEnv *env, jclass cls, jlong handle, jbyteArray dst, jint size) {
    AHardwareBuffer *buf = (AHardwareBuffer *) (intptr_t) handle;
    void *addr = NULL;
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, &addr) != 0 || addr == NULL) {
        return JNI_FALSE;
    }
    (*env)->SetByteArrayRegion(env, dst, 0, size, (const jbyte *) addr);
    AHardwareBuffer_unlock(buf, NULL);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL FN(nativeRelease)(JNIEnv *env, jclass cls, jlong handle) {
    if (handle != 0) AHardwareBuffer_release((AHardwareBuffer *) (intptr_t) handle);
}
