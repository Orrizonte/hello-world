#include <jni.h>
#include <dlfcn.h>
#include <atomic>
#include <mutex>
#include <cstdint>
#include <cstdio>

// The functions/struct below are the public Viture camera API ABI. The proprietary
// header and libglasses.so are intentionally NOT included in the public repository.
using CameraHandle = void*;
struct CameraFrame {
    uint8_t* data;
    uint32_t size;
    uint32_t width;
    uint32_t height;
    int32_t format;
    uint64_t timestamp;
    uint32_t sequence;
};
using CameraCallback = void(*)(const CameraFrame*, void*);
using CreateFn = CameraHandle(*)(int, int, int);
using StartFn = int(*)(CameraHandle, CameraCallback, void*);
using StopFn = int(*)(CameraHandle);
using DestroyFn = void(*)(CameraHandle);

namespace {
std::mutex guard;
void* sdk = nullptr;
CameraHandle camera = nullptr;
CreateFn createCamera = nullptr;
StartFn startCamera = nullptr;
StopFn stopCamera = nullptr;
DestroyFn destroyCamera = nullptr;
JavaVM *vm = nullptr;
jobject listener = nullptr;
jmethodID onFrame = nullptr;
std::atomic<uint64_t> delivered{0};

void frameCallback(const CameraFrame *frame, void*) {
    if (!frame || !frame->data || !frame->size || frame->size > 8000000 || frame->format != 0) return;
    // A diagnostic only: decode up to 15 preview frames per second.
    if (delivered.fetch_add(1, std::memory_order_relaxed) % 2 != 0) return;
    if (!vm || !listener || !onFrame) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    jint result = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
        attached = true;
    } else if (result != JNI_OK) return;
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(frame->size));
    if (bytes) {
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(frame->size),
            reinterpret_cast<const jbyte*>(frame->data));
        env->CallVoidMethod(listener, onFrame, bytes);
        env->DeleteLocalRef(bytes);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) vm->DetachCurrentThread();
}

bool resolveSdk() {
    if (sdk) return true;
    sdk = dlopen("libglasses.so", RTLD_NOW | RTLD_LOCAL);
    if (!sdk) return false;
    createCamera = reinterpret_cast<CreateFn>(dlsym(sdk, "xr_camera_provider_create"));
    startCamera = reinterpret_cast<StartFn>(dlsym(sdk, "xr_camera_provider_start"));
    stopCamera = reinterpret_cast<StopFn>(dlsym(sdk, "xr_camera_provider_stop"));
    destroyCamera = reinterpret_cast<DestroyFn>(dlsym(sdk, "xr_camera_provider_destroy"));
    return createCamera && startCamera && stopCamera && destroyCamera;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_fr_externalscreen_beastfingerdiagnostic_NativeCameraBridge_nativeStart(
    JNIEnv* env, jclass, jint fd, jobject callbackObject) {
    std::lock_guard<std::mutex> lock(guard);
    if (camera) return -100;
    if (fd < 0 || !callbackObject) return -101;
    if (!resolveSdk()) return -106;
    if (env->GetJavaVM(&vm) != JNI_OK) return -102;
    jclass cls = env->GetObjectClass(callbackObject);
    onFrame = env->GetMethodID(cls, "onJpegFrame", "([B)V");
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return -103; }
    if (!onFrame) return -103;
    listener = env->NewGlobalRef(callbackObject);
    if (!listener) return -104;
    delivered.store(0, std::memory_order_relaxed);
    camera = createCamera(0x0C45, 0x6368, fd);
    if (!camera) {
        env->DeleteGlobalRef(listener);
        listener = nullptr;
        onFrame = nullptr;
        return -105;
    }
    int rc = startCamera(camera, frameCallback, nullptr);
    if (rc != 0) {
        destroyCamera(camera); camera = nullptr;
        env->DeleteGlobalRef(listener); listener = nullptr; onFrame = nullptr;
    }
    return rc;
}

extern "C" JNIEXPORT void JNICALL
Java_fr_externalscreen_beastfingerdiagnostic_NativeCameraBridge_nativeStop(
    JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(guard);
    if (camera) {
        stopCamera(camera);
        destroyCamera(camera);
        camera = nullptr;
    }
    if (listener) {
        env->DeleteGlobalRef(listener);
        listener = nullptr;
    }
    onFrame = nullptr;
}
