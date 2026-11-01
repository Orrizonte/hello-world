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
#include <vector>
#include <ctime>
#include <cmath>

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

// ---- 6DoF feasibility test: raw IMU + camera recording
std::mutex senMx;
FILE* imuFile = nullptr;
uint64_t imuN = 0, imuFirstTs = 0, imuLastTs = 0, imuFirstArr = 0, imuLastArr = 0, imuMaxGapNs = 0;
double accSum[3] = {0}, gyrSum[3] = {0}, gyrSq[3] = {0};
float imuLast[10] = {0};
H cam = nullptr;
std::vector<uint8_t> camBuf; uint64_t camTs = 0, camArr = 0, camSeq = 0, camW = 0, camH = 0, camN = 0, camTaken = 0, camFirstArr = 0, camLastArr = 0, camMaxGapNs = 0;
bool camFresh = false;

uint64_t nowNs() { timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return uint64_t(t.tv_sec) * 1000000000ull + t.tv_nsec; }

void imuRawCb(float* d, uint64_t ts, uint64_t vsync) {
    uint64_t a = nowNs();
    std::lock_guard<std::mutex> lk(senMx);
    if (imuN == 0) { imuFirstTs = ts; imuFirstArr = a; }
    else if (a - imuLastArr > imuMaxGapNs) imuMaxGapNs = a - imuLastArr;
    imuLastTs = ts; imuLastArr = a; imuN++;
    memcpy(imuLast, d, sizeof imuLast);
    for (int i = 0; i < 3; ++i) { gyrSum[i] += d[i]; gyrSq[i] += double(d[i]) * d[i]; accSum[i] += d[3 + i]; }
    if (imuFile) fprintf(imuFile, "%llu,%llu,%llu,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.4f,%.4f,%.4f,%.2f\n",
        (unsigned long long) ts, (unsigned long long) vsync, (unsigned long long) a,
        d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7], d[8], d[9]);
}

struct CamFrame { uint8_t* data; uint32_t size, width, height; int format; uint64_t timestamp; uint32_t sequence; };
void camCb(const CamFrame* f, void*) {
    uint64_t a = nowNs();
    std::lock_guard<std::mutex> lk(senMx);
    if (camN == 0) camFirstArr = a; else if (a - camLastArr > camMaxGapNs) camMaxGapNs = a - camLastArr;
    camLastArr = a; camN++;
    camBuf.assign(f->data, f->data + f->size);
    camTs = f->timestamp; camArr = a; camSeq = f->sequence; camW = f->width; camH = f->height; camFresh = true;
}

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

// freqs supported for raw IMU on this product, as a string
JNIEXPORT jstring JNICALL Java_fr_externalscreen_beastlab_LabBridge_nImuFreqs(JNIEnv* env, jclass, jint pid) {
    std::string o; std::string err; load(err);
    using F = int (*)(int, int, int);
    auto f = sym<F>("xr_device_provider_is_product_support_imu_frequency");
    const char* n[] = {"60", "90", "120", "240", "500", "1000"};
    for (int i = 0; i < 6; ++i) o += std::string(n[i]) + " Hz : " + (f ? std::to_string(f(pid, 0, i)) : "?") + "  ";
    return env->NewStringUTF(o.c_str());
}

JNIEXPORT jstring JNICALL Java_fr_externalscreen_beastlab_LabBridge_nSensorStart(JNIEnv* env, jclass, jstring dir, jint freq, jint camFd) {
    std::string o;
    {
        std::lock_guard<std::mutex> lk(senMx);
        imuN = 0; imuMaxGapNs = 0; camN = 0; camTaken = 0; camMaxGapNs = 0; camFresh = false;
        for (int i = 0; i < 3; ++i) accSum[i] = gyrSum[i] = gyrSq[i] = 0;
        const char* d = env->GetStringUTFChars(dir, nullptr);
        std::string path = std::string(d) + "/imu.csv";
        env->ReleaseStringUTFChars(dir, d);
        imuFile = fopen(path.c_str(), "w");
        if (imuFile) fprintf(imuFile, "ts_device,vsync,arrival_monotonic_ns,gx,gy,gz,ax,ay,az,mx,my,mz,temp\n");
        o += imuFile ? "fichier IMU ouvert\n" : "fichier IMU impossible\n";
    }
    if (handle) {
        using Reg = int (*)(H, void (*)(float*, uint64_t, uint64_t));
        using Open = int (*)(H, uint8_t, uint8_t);
        auto r = sym<Reg>("xr_device_provider_register_imu_raw_callback");
        auto op = sym<Open>("xr_device_provider_open_imu");
        o += fmt("register_imu_raw_callback : %d\n", r ? r(handle, imuRawCb) : -1001);
        o += fmt("open_imu(raw) : %d\n", op ? op(handle, 0 /*RAW*/, static_cast<uint8_t>(freq)) : -1001);
    } else o += "lunettes non connectées : pas d'IMU\n";
    if (camFd >= 0) {
        using Create = H (*)(int, int, int);
        using Start = int (*)(H, void (*)(const CamFrame*, void*), void*);
        auto c = sym<Create>("xr_camera_provider_create");
        auto st = sym<Start>("xr_camera_provider_start");
        if (!cam && c) cam = c(0x0C45, 0x6368, camFd);
        o += std::string("caméra create : ") + (cam ? "ok" : "ÉCHEC") + "\n";
        if (cam && st) o += fmt("caméra start : %d\n", st(cam, camCb, nullptr));
    } else o += "caméra non ouverte\n";
    return env->NewStringUTF(o.c_str());
}

JNIEXPORT jstring JNICALL Java_fr_externalscreen_beastlab_LabBridge_nSensorStats(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lk(senMx);
    char b[900];
    double durA = imuN > 1 ? (imuLastArr - imuFirstArr) / 1e9 : 0, durC = camN > 1 ? (camLastArr - camFirstArr) / 1e9 : 0;
    double n = imuN ? double(imuN) : 1;
    double gStd = 0; for (int i = 0; i < 3; ++i) { double m = gyrSum[i] / n; gStd += gyrSq[i] / n - m * m; }
    double am = std::sqrt((accSum[0] / n) * (accSum[0] / n) + (accSum[1] / n) * (accSum[1] / n) + (accSum[2] / n) * (accSum[2] / n));
    snprintf(b, sizeof b,
        "IMU : %llu échantillons, %.0f /s, plus grand trou %.1f ms, horloge appareil %llu→%llu\n"
        "   dernier gyro %.4f %.4f %.4f | accel %.3f %.3f %.3f (|moy| %.3f) | magnéto %.1f %.1f %.1f | temp %.1f | bruit gyro %.4f\n"
        "Caméra : %llu images, %.1f img/s, plus grand trou %.0f ms, %llux%llu, dernier horodatage %llu, séq %llu, images enregistrées %llu",
        (unsigned long long) imuN, durA > 0 ? imuN / durA : 0, imuMaxGapNs / 1e6, (unsigned long long) imuFirstTs, (unsigned long long) imuLastTs,
        imuLast[0], imuLast[1], imuLast[2], imuLast[3], imuLast[4], imuLast[5], am, imuLast[6], imuLast[7], imuLast[8], imuLast[9], std::sqrt(gStd > 0 ? gStd : 0),
        (unsigned long long) camN, durC > 0 ? camN / durC : 0, camMaxGapNs / 1e6, (unsigned long long) camW, (unsigned long long) camH,
        (unsigned long long) camTs, (unsigned long long) camSeq, (unsigned long long) camTaken);
    return env->NewStringUTF(b);
}

// latest unseen camera frame: 40-byte header (ts, arrival, seq, w, h as int64 LE) + MJPEG
JNIEXPORT jbyteArray JNICALL Java_fr_externalscreen_beastlab_LabBridge_nCamTake(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lk(senMx);
    if (!camFresh) return nullptr;
    camFresh = false; camTaken++;
    jbyteArray a = env->NewByteArray(40 + (jsize) camBuf.size());
    int64_t h[5] = {(int64_t) camTs, (int64_t) camArr, (int64_t) camSeq, (int64_t) camW, (int64_t) camH};
    env->SetByteArrayRegion(a, 0, 40, reinterpret_cast<jbyte*>(h));
    env->SetByteArrayRegion(a, 40, (jsize) camBuf.size(), reinterpret_cast<jbyte*>(camBuf.data()));
    return a;
}

JNIEXPORT jstring JNICALL Java_fr_externalscreen_beastlab_LabBridge_nSensorStop(JNIEnv* env, jclass, jboolean linkAlive) {
    std::string o;
    if (handle && linkAlive) {
        using Close = int (*)(H, uint8_t);
        if (auto c = sym<Close>("xr_device_provider_close_imu")) o += fmt("close_imu(raw) : %d\n", c(handle, 0));
    }
    if (cam) {
        using HI = int (*)(H);
        using HV = void (*)(H);
        if (auto f = sym<HI>("xr_camera_provider_stop")) o += fmt("caméra stop : %d\n", f(cam));
        if (auto f = sym<HV>("xr_camera_provider_destroy")) f(cam);
        cam = nullptr;
    }
    std::lock_guard<std::mutex> lk(senMx);
    if (imuFile) { fclose(imuFile); imuFile = nullptr; }
    return env->NewStringUTF(o.c_str());
}
}
