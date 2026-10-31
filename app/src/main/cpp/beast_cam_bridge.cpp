// Beast camera bridge.
//
// Backend 1 (default): standard UVC through libusb + libuvc, opened from the
// file descriptor Android's UsbManager gives us. No proprietary code needed:
// VITURE documents the Beast pass-through camera as a UVC device.
//
// Backend 2 (fallback): VITURE's libglasses.so camera provider, loaded with
// dlopen() only if the user has added it to the APK themselves.
//
// Frames are delivered to Java as complete JPEG byte arrays (MJPEG streams)
// or as ARGB pixels (YUYV streams, downscaled by 2).

#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cstdlib>

#include "libusb.h"
#include "libuvc/libuvc.h"
#include "std_dht.h"

#define LOG_TAG "BeastCam"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------- JNI glue
JavaVM* gVm = nullptr;
jobject gListener = nullptr;
jmethodID gOnJpeg = nullptr;
jmethodID gOnArgb = nullptr;
pthread_key_t gDetachKey;
pthread_once_t gKeyOnce = PTHREAD_ONCE_INIT;
std::mutex gGuard;
std::atomic<bool> gRunning{false};
std::atomic<uint64_t> gFrames{0};
std::string gDescription;

void detachThread(void*) { if (gVm) gVm->DetachCurrentThread(); }
void makeKey() { pthread_key_create(&gDetachKey, detachThread); }

JNIEnv* threadEnv() {
    if (!gVm) return nullptr;
    JNIEnv* env = nullptr;
    jint r = gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (r == JNI_OK) return env;
    if (r != JNI_EDETACHED) return nullptr;
#ifdef __ANDROID__
    if (gVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;   // NDK jni.h
#else
    if (gVm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return nullptr;
#endif
    // Detach automatically when the native camera thread exits.
    pthread_once(&gKeyOnce, makeKey);
    pthread_setspecific(gDetachKey, env);
    return env;
}

// Insert standard Huffman tables if the MJPEG frame has none, so that
// Android's BitmapFactory can decode it.
bool hasDht(const uint8_t* d, size_t n, size_t* sosPos) {
    size_t i = 2;
    while (i + 4 <= n) {
        if (d[i] != 0xFF) return false;
        uint8_t m = d[i + 1];
        if (m == 0xC4) return true;
        if (m == 0xDA) { *sosPos = i; return false; }
        if (m == 0xD8 || (m >= 0xD0 && m <= 0xD7) || m == 0x01) { i += 2; continue; }
        size_t len = (static_cast<size_t>(d[i + 2]) << 8) | d[i + 3];
        i += 2 + len;
    }
    return false;
}

void deliverJpeg(const uint8_t* data, size_t size) {
    if (!gRunning.load() || !data || size < 4 || size > 12000000) return;
    if (data[0] != 0xFF || data[1] != 0xD8) return;
    JNIEnv* env = threadEnv();
    if (!env || !gListener || !gOnJpeg) return;
    size_t sos = 0;
    bool needTables = !hasDht(data, size, &sos) && sos > 0;
    size_t outSize = size + (needTables ? sizeof(kStdDht) : 0);
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(outSize));
    if (!bytes) { env->ExceptionClear(); return; }
    if (needTables) {
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(sos), reinterpret_cast<const jbyte*>(data));
        env->SetByteArrayRegion(bytes, static_cast<jsize>(sos), sizeof(kStdDht), reinterpret_cast<const jbyte*>(kStdDht));
        env->SetByteArrayRegion(bytes, static_cast<jsize>(sos + sizeof(kStdDht)),
                                static_cast<jsize>(size - sos), reinterpret_cast<const jbyte*>(data + sos));
    } else {
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(data));
    }
    env->CallVoidMethod(gListener, gOnJpeg, bytes);
    env->DeleteLocalRef(bytes);
    if (env->ExceptionCheck()) env->ExceptionClear();
    gFrames.fetch_add(1);
}

inline int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

// YUYV 4:2:2 -> ARGB at half resolution (one output pixel per YUYV macropixel
// on every second line). Halving keeps the Java side cheap.
void deliverYuyv(const uint8_t* d, size_t size, int w, int h, size_t step) {
    if (!gRunning.load() || !d || w < 4 || h < 4) return;
    if (step == 0) step = static_cast<size_t>(w) * 2;
    if (size < step * static_cast<size_t>(h)) return;
    JNIEnv* env = threadEnv();
    if (!env || !gListener || !gOnArgb) return;
    int ow = w / 2, oh = h / 2;
    std::vector<jint> out(static_cast<size_t>(ow) * oh);
    for (int y = 0; y < oh; ++y) {
        const uint8_t* row = d + step * static_cast<size_t>(y * 2);
        for (int x = 0; x < ow; ++x) {
            const uint8_t* p = row + x * 4;
            int y0 = (p[0] + p[2]) >> 1, u = p[1] - 128, v = p[3] - 128;
            int c = y0 - 16 < 0 ? 0 : (y0 - 16) * 298;
            int r = clamp255((c + 409 * v + 128) >> 8);
            int g = clamp255((c - 100 * u - 208 * v + 128) >> 8);
            int b = clamp255((c + 516 * u + 128) >> 8);
            out[static_cast<size_t>(y) * ow + x] = static_cast<jint>(0xFF000000u | (r << 16) | (g << 8) | b);
        }
    }
    jintArray arr = env->NewIntArray(static_cast<jsize>(out.size()));
    if (!arr) { env->ExceptionClear(); return; }
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    env->CallVoidMethod(gListener, gOnArgb, arr, ow, oh);
    env->DeleteLocalRef(arr);
    if (env->ExceptionCheck()) env->ExceptionClear();
    gFrames.fetch_add(1);
}

// ------------------------------------------------------------- UVC backend
libusb_context* gUsb = nullptr;
uvc_context_t* gUvc = nullptr;
uvc_device_handle_t* gDev = nullptr;
pthread_t gEventThread;
std::atomic<bool> gEventsRunning{false};
bool gStreaming = false;

void* usbEvents(void*) {
    while (gEventsRunning.load()) {
        timeval tv{0, 100000};
        libusb_handle_events_timeout_completed(gUsb, &tv, nullptr);
    }
    return nullptr;
}

void uvcCallback(uvc_frame_t* frame, void*) {
    if (!frame || !frame->data) return;
    if (frame->frame_format == UVC_FRAME_FORMAT_MJPEG)
        deliverJpeg(static_cast<const uint8_t*>(frame->data), frame->data_bytes);
    else if (frame->frame_format == UVC_FRAME_FORMAT_YUYV)
        deliverYuyv(static_cast<const uint8_t*>(frame->data), frame->data_bytes,
                    static_cast<int>(frame->width), static_cast<int>(frame->height), frame->step);
}

struct Choice { uvc_frame_format fmt = UVC_FRAME_FORMAT_UNKNOWN; int w = 0, h = 0, fps = 0; double cost = 1e18; };

// Prefer MJPEG near 1280x720 at >= 25 fps: wide enough for precise fingers,
// small enough to decode in a few milliseconds.
Choice chooseMode(uvc_device_handle_t* dev, int wantW, int wantH) {
    Choice best;
    char line[160];
    for (const uvc_format_desc_t* f = uvc_get_format_descs(dev); f; f = f->next) {
        uvc_frame_format fmt = UVC_FRAME_FORMAT_UNKNOWN;
        if (f->bDescriptorSubtype == UVC_VS_FORMAT_MJPEG) fmt = UVC_FRAME_FORMAT_MJPEG;
        else if (f->bDescriptorSubtype == UVC_VS_FORMAT_UNCOMPRESSED &&
                 std::memcmp(f->fourccFormat, "YUY2", 4) == 0) fmt = UVC_FRAME_FORMAT_YUYV;
        for (const uvc_frame_desc_t* fr = f->frame_descs; fr; fr = fr->next) {
            int fps = 0;
            if (fr->intervals) {
                for (const uint32_t* iv = fr->intervals; *iv; ++iv) {
                    int v = static_cast<int>(10000000 / *iv);
                    if (v > fps) fps = v;
                }
            }
            if (fps == 0 && fr->dwDefaultFrameInterval) fps = static_cast<int>(10000000 / fr->dwDefaultFrameInterval);
            std::snprintf(line, sizeof line, "%s %dx%d@%d; ",
                          fmt == UVC_FRAME_FORMAT_MJPEG ? "MJPEG" : fmt == UVC_FRAME_FORMAT_YUYV ? "YUYV" : "autre",
                          fr->wWidth, fr->wHeight, fps);
            gDescription += line;
            if (fmt == UVC_FRAME_FORMAT_UNKNOWN) continue;
            double cost = std::abs(fr->wWidth - wantW) + std::abs(fr->wHeight - wantH);
            if (fps < 25) cost += 5000;
            if (fmt == UVC_FRAME_FORMAT_YUYV) cost += 3000 + (fr->wWidth > 800 ? 4000 : 0);
            if (cost < best.cost) {
                best.fmt = fmt; best.w = fr->wWidth; best.h = fr->wHeight;
                best.fps = fps > 30 ? 30 : (fps > 0 ? fps : 30); best.cost = cost;
            }
        }
    }
    return best;
}

void closeUvc() {
    if (gDev) {
        if (gStreaming) uvc_stop_streaming(gDev);
        gStreaming = false;
        uvc_close(gDev);
        gDev = nullptr;
    }
    if (gEventsRunning.exchange(false)) pthread_join(gEventThread, nullptr);
    if (gUvc) { uvc_exit(gUvc); gUvc = nullptr; }
    if (gUsb) { libusb_exit(gUsb); gUsb = nullptr; }
}

int startUvc(int fd, int wantW, int wantH) {
    libusb_init_option opts[1];
    std::memset(opts, 0, sizeof opts);
    opts[0].option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY;
    int r = libusb_init_context(&gUsb, opts, 1);
    if (r != LIBUSB_SUCCESS) { gDescription += "libusb_init " + std::to_string(r) + "; "; gUsb = nullptr; return -201; }
    r = uvc_init(&gUvc, gUsb);
    if (r != UVC_SUCCESS) { gDescription += "uvc_init " + std::to_string(r) + "; "; closeUvc(); return -202; }
    gEventsRunning = true;
    if (pthread_create(&gEventThread, nullptr, usbEvents, nullptr) != 0) {
        gEventsRunning = false; closeUvc(); return -203;
    }
    r = uvc_wrap(fd, gUvc, &gDev);
    if (r != UVC_SUCCESS) {
        gDescription += std::string("uvc_wrap: ") + uvc_strerror(static_cast<uvc_error_t>(r)) + "; ";
        gDev = nullptr; closeUvc(); return -204;
    }
    Choice c = chooseMode(gDev, wantW, wantH);
    if (c.fmt == UVC_FRAME_FORMAT_UNKNOWN) { gDescription += "aucun format MJPEG/YUYV; "; closeUvc(); return -205; }
    uvc_stream_ctrl_t ctrl;
    r = uvc_get_stream_ctrl_format_size(gDev, &ctrl, c.fmt, c.w, c.h, c.fps);
    if (r != UVC_SUCCESS) {
        gDescription += std::string("négociation: ") + uvc_strerror(static_cast<uvc_error_t>(r)) + "; ";
        closeUvc(); return -206;
    }
    gRunning = true;
    r = uvc_start_streaming(gDev, &ctrl, uvcCallback, nullptr, 0);
    if (r != UVC_SUCCESS) {
        gRunning = false;
        gDescription += std::string("streaming: ") + uvc_strerror(static_cast<uvc_error_t>(r)) + "; ";
        closeUvc(); return -207;
    }
    gStreaming = true;
    char line[96];
    std::snprintf(line, sizeof line, "=> UVC %s %dx%d@%d",
                  c.fmt == UVC_FRAME_FORMAT_MJPEG ? "MJPEG" : "YUYV", c.w, c.h, c.fps);
    gDescription += line;
    LOGI("%s", gDescription.c_str());
    return 0;
}

// ---------------------------------------------------- VITURE SDK fallback
// Public ABI of viture_camera_provider (header not redistributed here).
using CameraHandle = void*;
struct VitureFrame { uint8_t* data; uint32_t size; uint32_t width; uint32_t height; int32_t format; uint64_t timestamp; uint32_t sequence; };
using VitureCallback = void (*)(const VitureFrame*, void*);
using CreateFn = CameraHandle (*)(int, int, int);
using StartFn = int (*)(CameraHandle, VitureCallback, void*);
using StopFn = int (*)(CameraHandle);
using DestroyFn = void (*)(CameraHandle);
void* gSdk = nullptr;
CameraHandle gViture = nullptr;
CreateFn vCreate = nullptr; StartFn vStart = nullptr; StopFn vStop = nullptr; DestroyFn vDestroy = nullptr;

void vitureCallback(const VitureFrame* f, void*) {
    if (f && f->data && f->format == 0) deliverJpeg(f->data, f->size);
}

int startViture(int fd) {
    if (!gSdk) {
        gSdk = dlopen("libglasses.so", RTLD_NOW | RTLD_LOCAL);
        if (!gSdk) { gDescription += "SDK VITURE absent; "; return -301; }
        vCreate = reinterpret_cast<CreateFn>(dlsym(gSdk, "xr_camera_provider_create"));
        vStart = reinterpret_cast<StartFn>(dlsym(gSdk, "xr_camera_provider_start"));
        vStop = reinterpret_cast<StopFn>(dlsym(gSdk, "xr_camera_provider_stop"));
        vDestroy = reinterpret_cast<DestroyFn>(dlsym(gSdk, "xr_camera_provider_destroy"));
    }
    if (!vCreate || !vStart || !vStop || !vDestroy) { gDescription += "SDK VITURE incomplet; "; return -302; }
    gViture = vCreate(0x0C45, 0x6368, fd);
    if (!gViture) { gDescription += "SDK VITURE: création refusée; "; return -303; }
    gRunning = true;
    int r = vStart(gViture, vitureCallback, nullptr);
    if (r != 0) {
        gRunning = false; vDestroy(gViture); gViture = nullptr;
        gDescription += "SDK VITURE start " + std::to_string(r) + "; ";
        return -304;
    }
    gDescription += "=> SDK VITURE (MJPEG)";
    return 0;
}

void stopAll() {
    gRunning = false;
    closeUvc();
    if (gViture) { vStop(gViture); vDestroy(gViture); gViture = nullptr; }
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_fr_externalscreen_beastfingerdiagnostic_NativeCameraBridge_nativeStart(
        JNIEnv* env, jclass, jint fd, jobject listener, jint wantW, jint wantH, jboolean preferViture) {
    std::lock_guard<std::mutex> lock(gGuard);
    if (gDev || gViture) return -100;
    if (fd < 0 || !listener) return -101;
    if (env->GetJavaVM(&gVm) != JNI_OK) return -102;
    jclass cls = env->GetObjectClass(listener);
    gOnJpeg = env->GetMethodID(cls, "onJpegFrame", "([B)V");
    gOnArgb = env->GetMethodID(cls, "onArgbFrame", "([III)V");
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck() || !gOnJpeg || !gOnArgb) { env->ExceptionClear(); return -103; }
    gListener = env->NewGlobalRef(listener);
    gDescription.clear();
    gFrames = 0;
    int r;
    if (preferViture) {
        r = startViture(fd);
        if (r != 0) r = startUvc(fd, wantW, wantH);
    } else {
        r = startUvc(fd, wantW, wantH);
        if (r != 0) { int v = startViture(fd); if (v == 0) r = 0; }
    }
    if (r != 0) { env->DeleteGlobalRef(gListener); gListener = nullptr; }
    return r;
}

extern "C" JNIEXPORT void JNICALL
Java_fr_externalscreen_beastfingerdiagnostic_NativeCameraBridge_nativeStop(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(gGuard);
    stopAll();
    if (gListener) { env->DeleteGlobalRef(gListener); gListener = nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL
Java_fr_externalscreen_beastfingerdiagnostic_NativeCameraBridge_nativeDescribe(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(gGuard);
    return env->NewStringUTF(gDescription.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_fr_externalscreen_beastfingerdiagnostic_NativeCameraBridge_nativeFrameCount(JNIEnv*, jclass) {
    return static_cast<jlong>(gFrames.load());
}
