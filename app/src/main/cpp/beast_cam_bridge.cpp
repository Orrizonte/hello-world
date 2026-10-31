#include <jni.h>
#include <atomic>
#include <mutex>
#include <cstdint>
#include "viture_camera_provider.h"

namespace {
std::mutex guard;
JavaVM *vm = nullptr;
jobject listener = nullptr;
jmethodID onFrame = nullptr;
XRCameraProviderHandle camera = nullptr;
std::atomic<uint32_t> received{0};

void callback(const XRCameraFrame *frame, void *) {
    if (!frame || !frame->data || !frame->size || frame->size > 8000000) return;
    if ((received.fetch_add(1, std::memory_order_relaxed) % 3) != 0) return;
    if (!vm || !listener || !onFrame) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    jint state = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    } else if (state != JNI_OK) { return; }
    jbyteArray buffer = env->NewByteArray(static_cast<jsize>(frame->size));
    if (buffer) {
        env->SetByteArrayRegion(buffer, 0, static_cast<jsize>(frame->size),
            reinterpret_cast<const jbyte*>(frame->data));
        env->CallVoidMethod(listener, onFrame, buffer);
        env->DeleteLocalRef(buffer);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) vm->DetachCurrentThread();
}
}

extern "C" JNIEXPORT jint JNICALL
Java_fr_externalscreen_beastfingerdiagnostic_NativeCameraBridge_nativeStart(
    JNIEnv* env, jclass, jint usbFd, jobject callbackObject) {
    std::lock_guard<std::mutex> lock(guard);
    if (camera) return -100;
    if (usbFd < 0 || !callbackObject) return -101;
    if (env->GetJavaVM(&vm) != JNI_OK) return -102;
    jclass cls = env->GetObjectClass(callbackObject);
    jmethodID method = env->GetMethodID(cls, "onJpegFrame", "([B)V");
    env->DeleteLocalRef(cls);
    if (!method) { if (env->ExceptionCheck()) env->ExceptionClear(); return -103; }
    listener = env->NewGlobalRef(callbackObject);
    if (!listener) return -104;
    onFrame = method;
    received = 0;
    camera = xr_camera_provider_create(0x0C45, 0x6368, usbFd);
    if (!camera) {
        env->DeleteGlobalRef(listener); listener = nullptr; onFrame = nullptr;
        return -105;
    }
    int result = xr_camera_provider_start(camera, callback, nullptr);
    if (result != 0) {
        xr_camera_provider_destroy(camera); camera = nullptr;
        env->DeleteGlobalRef(listener); listener = nullptr; onFrame = nullptr;
    }
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_fr_externalscreen_beastfingerdiagnostic_NativeCameraBridge_nativeStop(
    JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(guard);
    if (camera) {
        xr_camera_provider_stop(camera);
        xr_camera_provider_destroy(camera);
        camera = nullptr;
    }
    if (listener) { env->DeleteGlobalRef(listener); listener = nullptr; }
    onFrame = nullptr;
}
