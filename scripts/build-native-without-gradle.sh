#!/usr/bin/env bash
set -euo pipefail
SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
BUILD_TOOLS="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
NDK="$SDK/ndk/27.2.12479018"
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang++"
OUT="$PWD/manual-build"
mkdir -p "$OUT"/{classes,dex,apk,resources}
cat app/src/main/AndroidManifest.xml | sed 's|<manifest xmlns:android=|<manifest package="fr.externalscreen.beastfingerdiagnostic" xmlns:android=|' > "$OUT/AndroidManifest.xml"
echo "=== compile Java directly against Android 35 ==="
javac -encoding UTF-8 -source 17 -target 17 \
    -classpath "$ANDROID_JAR" -d "$OUT/classes" \
    app/src/main/java/fr/externalscreen/beastfingerdiagnostic/*.java
echo "=== dex Java classes ==="
find "$OUT/classes" -type f -name '*.class' -print0 | \
    xargs -0 "$BUILD_TOOLS/d8" --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex"
echo "=== compile JNI C++ using NDK Clang, no vendor binary ==="
"$CLANG" -std=c++17 -O2 -shared -fPIC -D__ANDROID__ \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    -Wl,-z,max-page-size=16384 \
    -o "$OUT/libbeast_cam_bridge.so" \
    app/src/main/cpp/beast_cam_bridge.cpp -ldl -llog
echo "=== package manifest using aapt2 ==="
"$BUILD_TOOLS/aapt2" link \
    -I "$ANDROID_JAR" \
    --manifest "$OUT/AndroidManifest.xml" \
    --min-sdk-version 26 --target-sdk-version 35 \
    -o "$OUT/apk/app-debug.apk"
python3 - "$OUT" <<'PY'
import sys, pathlib,zipfile
root=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(root/'apk/app-debug.apk','a') as apk:
    apk.write(root/'dex/classes.dex', 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
    apk.write(root/'libbeast_cam_bridge.so', 'lib/arm64-v8a/libbeast_cam_bridge.so', compress_type=zipfile.ZIP_DEFLATED)
print('Unsigned app APK ready', (root/'apk/app-debug.apk').stat().st_size)
PY
mkdir -p "$OUT/signing-tools"
cp "$BUILD_TOOLS/lib/apksigner.jar" "$OUT/signing-tools/"
cp "$BUILD_TOOLS/zipalign" "$OUT/signing-tools/"
echo "=== complete: unsigned app + signing tools ==="
