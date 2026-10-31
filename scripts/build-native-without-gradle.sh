#!/usr/bin/env bash
set -euo pipefail
SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
BUILD_TOOLS="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
NDK="$SDK/ndk/27.2.12479018"
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang++"
OUT="$PWD/manual-build"
MP="$OUT/mediapipe"
CP="$ANDROID_JAR"
for jarfile in "$MP"/jar/*.jar; do CP="$CP:$jarfile"; done
mkdir -p "$OUT"/{classes,dex,apk,resources}
cat app/src/main/AndroidManifest.xml | sed 's|<manifest xmlns:android=|<manifest package="fr.externalscreen.beastfingerdiagnostic" xmlns:android=|' > "$OUT/AndroidManifest.xml"
echo "=== compile Java directly against Android 35 ==="
javac -encoding UTF-8 -source 17 -target 17 \
    -classpath "$CP" -d "$OUT/classes" \
    app/src/main/java/fr/externalscreen/beastfingerdiagnostic/*.java
echo "=== dex Java classes ==="
(cd "$OUT/classes" && jar cf "$OUT/app-classes.jar" .)
"$BUILD_TOOLS/d8" --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" \
    "$OUT/app-classes.jar" "$MP"/jar/*.jar
echo "=== compile JNI C++ using NDK Clang, no vendor binary ==="
"$CLANG" -std=c++17 -O2 -shared -fPIC -static-libstdc++ -D__ANDROID__ \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    -Wl,-z,max-page-size=16384 \
    -o "$OUT/libbeast_cam_bridge.so" \
    app/src/main/cpp/beast_cam_bridge.cpp -ldl -llog
echo "=== verify native dependencies ==="
readelf -d "$OUT/libbeast_cam_bridge.so" | grep NEEDED
if readelf -d "$OUT/libbeast_cam_bridge.so" | grep -q 'libc++_shared.so'; then
  echo "ERROR: still needs libc++_shared.so" >&2
  exit 3
fi
echo "=== package manifest using aapt2 ==="
"$BUILD_TOOLS/aapt2" link \
    -I "$ANDROID_JAR" \
    --manifest "$OUT/AndroidManifest.xml" \
    --min-sdk-version 26 --target-sdk-version 35 \
    --version-code 10 --version-name 0.8.2 \
    -o "$OUT/apk/app-debug.apk"
python3 - "$OUT" <<'PY'
import sys, pathlib,zipfile
root=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(root/'apk/app-debug.apk','a') as apk:
    for dex in sorted((root/'dex').glob('classes*.dex')):
        apk.write(dex, dex.name, compress_type=zipfile.ZIP_DEFLATED)
    apk.write(root/'libbeast_cam_bridge.so', 'lib/arm64-v8a/libbeast_cam_bridge.so', compress_type=zipfile.ZIP_DEFLATED)
    apk.write(root/'mediapipe/native/libmediapipe_tasks_vision_jni.so',
              'lib/arm64-v8a/libmediapipe_tasks_vision_jni.so', compress_type=zipfile.ZIP_DEFLATED)
    apk.write(root/'mediapipe/asset/hand_landmarker.task',
              'assets/hand_landmarker.task', compress_type=zipfile.ZIP_STORED)
    apk.write(root/'mediapipe/asset/MEDIAPIPE-LICENSE-NOTICE.txt',
              'assets/MEDIAPIPE-LICENSE-NOTICE.txt', compress_type=zipfile.ZIP_DEFLATED)
print('Unsigned app APK ready', (root/'apk/app-debug.apk').stat().st_size)
PY
mkdir -p "$OUT/signing-tools"
cp "$BUILD_TOOLS/lib/apksigner.jar" "$OUT/signing-tools/"
cp "$BUILD_TOOLS/zipalign" "$OUT/signing-tools/"
echo "=== complete: unsigned app + signing tools ==="
