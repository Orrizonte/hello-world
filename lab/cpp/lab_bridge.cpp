// Beast Lab JNI bridge. Talks to VITURE's libglasses.so through dlopen/dlsym so
// that this public repository never contains the proprietary SDK: the library
// is added to the APK locally, after the CI build.
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>

#define TAG "BeastLab"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {
using H = void*;
void* lib = nullptr;
H handle = nullptr;
JavaVM* vm = nullptr;
jclass bridgeCls = nullptr;
jmethodID onStateId = nullptr;
std::mutex logMx;
std::string sdkLog;
std::mutex poseMx;
float pose[7] = {0};
uint64_t poseCount = 0;

template <typename T> T sym(const char* n) { return lib ? reinterpret_cast<T>(dlsym(lib, n)) : nullptr; }

void logHook(int level, const char* tag, const char* msg) {
    std::lock_guard<std::mutex> lk(logMx);
    char head[16]; snprintf(head, sizeof head, "[%d] ", level);
    sdkLog += head; sdkLog += msg ? msg : ""; sdkLog += '\n';
    if (sdkLog.size() > 60000) sdkLog.erase(0, 20000);
}

void stateCb(int id, int value) {
    if (!vm || !bridgeCls || !onStateId) return;
    JNIEnv* env = nullptr; bool att = false;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        att = true;
    }
    env->CallStaticVoidMethod(bridgeCls, onStateId, id, value);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (att) vm->DetachCurrentThread();
}

void poseCb(float* d, uint64_t) {
    std::lock_guard<std::mutex> lk(poseMx);
    memcpy(pose, d, sizeof pose); poseCount++;
}

bool load(std::string& err) {
    if (lib) return true;
    lib = dlopen("libglasses.so", RTLD_NOW);
    if (!lib) { err = dlerror() ? dlerror() : "dlopen failed"; return false; }
    using SetHook = void (*)(void (*)(int, const char*, const char*));
    using SetLvl = void (*)(int);
    if (auto f = sym<SetHook>("xr_device_provider_set_log_hook")) f(logHook);
    if (auto f = sym<SetLvl>("xr_device_provider_set_log_level")) f(3);
    return true;
}

std::string fmt(const char* f, int v) { char b[160]; snprintf(b, sizeof b, f, v); return b; }
}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_fr_externalscreen_beastlab_LabBridge_nConnect(JNIEnv* env, jclass cls, jint pid, jint fd, jstring cacheDir) {
    env->GetJavaVM(&vm);
    if (!bridgeCls) bridgeCls = static_cast<jclass>(env->NewGlobalRef(cls));
    onStateId = env->GetStaticMethodID(bridgeCls, "onState", "(II)V");
    std::string out, err;
    if (!load(err)) return env->NewStringUTF(("libglasses.so introuvable : " + err).c_str());
    using GetV = const char* (*)();
    if (auto v = sym<GetV>("GetVersionString")) out += std::string("SDK ") + v() + "\n";
    using I1 = int (*)(int);
    using MN = int (*)(int, char*, int*);
    if (auto f = sym<I1>("xr_device_provider_is_product_id_valid")) out += fmt("PID valide : %d\n", f(pid));
    if (auto f = sym<I1>("xr_device_provider_is_product_support_native_dof")) out += fmt("DoF natif supporté : %d\n", f(pid));
    if (auto f = sym<MN>("xr_device_provider_get_market_name")) {
        char name[64] = {0}; int len = sizeof name;
        if (f(pid, name, &len) == 0) out += std::string("Modèle : ") + name + "\n";
    }
    using Create = H (*)(int, int);
    using Init = int (*)(H, const char*, const char*);
    using HI = int (*)(H);
    using RegState = int (*)(H, void (*)(int, int));
    auto create = sym<Create>("xr_device_provider_create");
    if (!create) return env->NewStringUTF((out + "symbole create absent").c_str());
    handle = create(pid, fd);
    if (!handle) return env->NewStringUTF((out + "create a échoué").c_str());
    if (auto f = sym<RegState>("xr_device_provider_register_state_callback")) out += fmt("callback état : %d\n", f(handle, stateCb));
    const char* dir = env->GetStringUTFChars(cacheDir, nullptr);
    if (auto f = sym<Init>("xr_device_provider_initialize")) out += fmt("initialize : %d\n", f(handle, nullptr, dir));
    env->ReleaseStringUTFChars(cacheDir, dir);
    if (auto f = sym<HI>("xr_device_provider_start")) out += fmt("start : %d\n", f(handle));
    if (auto f = sym<HI>("xr_device_provider_get_device_type")) out += fmt("type d'appareil : %d (1 = Gen2)\n", f(handle));
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL Java_fr_externalscreen_beastlab_LabBridge_nInfo(JNIEnv* env, jclass) {
    if (!handle) return env->NewStringUTF("non connecté");
    using HI = int (*)(H);
    using MaxF = int (*)(H, int, int*);
    using VerF = int (*)(H, char*, int*);
    using WearF = int (*)(H, uint8_t*);
    std::string o;
    const char* getters[][2] = {
        {"native_get_mode", "mode (0 bypass, 1 natif)"}, {"native_get_dof", "DoF natif (0, 1=3DoF, 2=smooth)"},
        {"native_get_display_mode", "mode d'affichage natif"}, {"native_get_display_distance", "distance (1-10)"},
        {"native_get_display_size", "taille (0-4)"}, {"native_get_side_mode", "side mode"},
        {"get_display_mode", "mode d'affichage bypass"}, {"get_brightness_level", "luminosité"},
        {"get_volume_level", "volume"}, {"get_duty_cycle", "duty cycle"}};
    for (auto& g : getters) {
        auto f = sym<HI>((std::string("xr_device_provider_") + g[0]).c_str());
        o += std::string(g[1]) + " : " + (f ? std::to_string(f(handle)) : std::string("symbole absent")) + "\n";
    }
    if (auto f = sym<MaxF>("xr_device_provider_get_value_max")) {
        for (int id = 0; id < 4; ++id) { int m = -1; int r = f(handle, id, &m); o += fmt("value_max id=%d", id) + fmt(" → %d", m) + fmt(" (rc %d)\n", r); }
    }
    if (auto f = sym<VerF>("xr_device_provider_get_glasses_version")) {
        char b[256] = {0}; int len = sizeof b;
        int r = f(handle, b, &len);
        o += fmt("version lunettes (rc %d) : ", r) + std::string(b) + "\n";
    }
    if (auto f = sym<WearF>("xr_device_provider_get_wear_status")) { uint8_t w = 255; int r = f(handle, &w); o += fmt("porté : %d", w) + fmt(" (rc %d)\n", r); }
    return env->NewStringUTF(o.c_str());
}

// op: name of an int setter (e.g. "native_set_display_distance"), or a no-arg action
JNIEXPORT jint JNICALL Java_fr_externalscreen_beastlab_LabBridge_nCall(JNIEnv* env, jclass, jstring op, jint arg, jboolean withArg) {
    if (!handle) return -1000;
    const char* c = env->GetStringUTFChars(op, nullptr);
    std::string name = std::string("xr_device_provider_") + c;
    env->ReleaseStringUTFChars(op, c);
    if (withArg) {
        using HII = int (*)(H, int);
        auto f = sym<HII>(name.c_str());
        return f ? f(handle, arg) : -1001;
    }
    using HI = int (*)(H);
    auto f = sym<HI>(name.c_str());
    return f ? f(handle) : -1001;
}

JNIEXPORT jint JNICALL Java_fr_externalscreen_beastlab_LabBridge_nImu(JNIEnv*, jclass, jboolean open, jint freq) {
    if (!handle) return -1000;
    using Reg = int (*)(H, void (*)(float*, uint64_t));
    using Open = int (*)(H, uint8_t, uint8_t);
    using Close = int (*)(H, uint8_t);
    if (open) {
        auto r = sym<Reg>("xr_device_provider_register_imu_pose_callback");
        auto o = sym<Open>("xr_device_provider_open_imu");
        if (!r || !o) return -1001;
        r(handle, poseCb);
        return o(handle, 1 /*POSE*/, static_cast<uint8_t>(freq));
    }
    auto c = sym<Close>("xr_device_provider_close_imu");
    return c ? c(handle, 1) : -1001;
}

JNIEXPORT jfloatArray JNICALL Java_fr_externalscreen_beastlab_LabBridge_nPose(JNIEnv* env, jclass) {
    float out[8];
    { std::lock_guard<std::mutex> lk(poseMx); memcpy(out, pose, sizeof pose); out[7] = static_cast<float>(poseCount); }
    jfloatArray a = env->NewFloatArray(8);
    env->SetFloatArrayRegion(a, 0, 8, out);
    return a;
}

JNIEXPORT jstring JNICALL Java_fr_externalscreen_beastlab_LabBridge_nSdkLog(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lk(logMx);
    return env->NewStringUTF(sdkLog.c_str());
}

JNIEXPORT void JNICALL Java_fr_externalscreen_beastlab_LabBridge_nForget(JNIEnv*, jclass) {
    handle = nullptr; // leaked on purpose: the SDK thread may still reference it
}

JNIEXPORT void JNICALL Java_fr_externalscreen_beastlab_LabBridge_nDisconnect(JNIEnv*, jclass) {
    if (!handle) return;
    using HI = int (*)(H);
    using HV = void (*)(H);
    if (auto f = sym<HI>("xr_device_provider_stop")) f(handle);
    if (auto f = sym<HI>("xr_device_provider_shutdown")) f(handle);
    if (auto f = sym<HV>("xr_device_provider_destroy")) f(handle);
    handle = nullptr;
}
}
