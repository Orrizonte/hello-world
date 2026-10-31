#!/usr/bin/env bash
# Builds the Beast Fingers APK without Gradle (CI or any Linux box with the
# Android SDK/NDK). Steps: libusb+libuvc+JNI bridge (NDK clang), resources
# (aapt2), Java (javac + d8), packaging, zipalign. Signing is a separate step.
#
#   scripts/prepare-mediapipe.sh           # once: MediaPipe jars, .so, model
#   scripts/build-apk.sh <libusb-src> <libuvc-src>
set -euo pipefail
LIBUSB="$1"; LIBUVC="$2"
SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
NDK="${ANDROID_NDK:-$SDK/ndk/27.2.12479018}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
OUT="$PWD/manual-build"
MP="$OUT/mediapipe"
VERSION_CODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' app/src/main/AndroidManifest.xml)
VERSION_NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' app/src/main/AndroidManifest.xml)
rm -rf "$OUT"/{classes,dex,apk,res,native}
mkdir -p "$OUT"/{classes,dex,apk,res,native}

echo "=== native: JNI bridge + libuvc + libusb (arm64-v8a) ==="
CC="$TC/bin/aarch64-linux-android26-clang" CXX="$TC/bin/aarch64-linux-android26-clang++" \
  bash scripts/build-native-uvc.sh "$OUT/native/libbeast_cam_bridge.so" "$LIBUSB" "$LIBUVC"
"$TC/bin/llvm-readelf" -d "$OUT/native/libbeast_cam_bridge.so" | grep NEEDED
"$TC/bin/llvm-strip" --strip-unneeded "$OUT/native/libbeast_cam_bridge.so"
cp "$TC/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$OUT/native/"

echo "=== resources ==="
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/res/compiled.zip"
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest app/src/main/AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 35 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java "$OUT/gen" -o "$OUT/apk/base.apk" "$OUT/res/compiled.zip"

echo "=== java ==="
CP="$ANDROID_JAR"
for j in "$MP"/jar/*.jar; do CP="$CP:$j"; done
javac -encoding UTF-8 --release 17 -Xlint:-options -classpath "$CP" -d "$OUT/classes" \
    $(find "$OUT/gen" -name '*.java') app/src/main/java/fr/externalscreen/beastfingerdiagnostic/*.java
(cd "$OUT/classes" && jar cf "$OUT/app-classes.jar" .)
"$BT/d8" --release --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/app-classes.jar" "$MP"/jar/*.jar

echo "=== package ==="
python3 - "$OUT" <<'PY'
import sys, pathlib, zipfile, shutil
root = pathlib.Path(sys.argv[1])
src, dst = root/'apk/base.apk', root/'apk/unaligned.apk'
shutil.copy(src, dst)
with zipfile.ZipFile(dst, 'a') as apk:
    for dex in sorted((root/'dex').glob('classes*.dex')):
        apk.write(dex, dex.name, compress_type=zipfile.ZIP_DEFLATED)
    libs = {'libbeast_cam_bridge.so': root/'native/libbeast_cam_bridge.so',
            'libc++_shared.so': root/'native/libc++_shared.so',
            'libmediapipe_tasks_vision_jni.so': root/'mediapipe/native/libmediapipe_tasks_vision_jni.so'}
    opt = pathlib.Path('app/src/main/jniLibs/arm64-v8a/libglasses.so')   # optional, never committed
    if opt.exists():
        libs['libglasses.so'] = opt
        print('VITURE SDK libglasses.so included (local build)')
    for name, path in libs.items():
        apk.write(path, 'lib/arm64-v8a/' + name, compress_type=zipfile.ZIP_DEFLATED)
    apk.write(root/'mediapipe/asset/hand_landmarker.task', 'assets/hand_landmarker.task', compress_type=zipfile.ZIP_STORED)
    apk.write(root/'mediapipe/asset/MEDIAPIPE-LICENSE-NOTICE.txt', 'assets/MEDIAPIPE-LICENSE-NOTICE.txt', compress_type=zipfile.ZIP_DEFLATED)
print('unaligned APK', dst.stat().st_size)
PY
"$BT/zipalign" -f -p 4 "$OUT/apk/unaligned.apk" "$OUT/apk/beast-fingers-$VERSION_NAME-unsigned.apk"
"$BT/zipalign" -c 4 "$OUT/apk/beast-fingers-$VERSION_NAME-unsigned.apk"
echo "=== done: $OUT/apk/beast-fingers-$VERSION_NAME-unsigned.apk ==="
