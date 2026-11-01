#!/usr/bin/env bash
# Builds Beast Lab (SDK test app) WITHOUT the proprietary VITURE SDK:
# libglasses.so is appended to the APK locally before signing.
set -euo pipefail
SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"
TC="$SDK/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64"
OUT="$PWD/manual-build/lab"
rm -rf "$OUT"; mkdir -p "$OUT"/{classes,dex,apk}
"$TC/bin/aarch64-linux-android26-clang++" -std=c++17 -O2 -fPIC -shared -static-libstdc++ -Wall \
    -Wl,-z,max-page-size=16384 -o "$OUT/libbeast_lab.so" lab/cpp/lab_bridge.cpp -llog -ldl
"$TC/bin/llvm-strip" --strip-unneeded "$OUT/libbeast_lab.so"
"$BT/aapt2" link -I "$JAR" --manifest lab/AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 35 -o "$OUT/apk/base.apk"
javac -encoding UTF-8 --release 17 -Xlint:-options -classpath "$JAR" -d "$OUT/classes" lab/src/fr/externalscreen/beastlab/*.java
(cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$OUT/dex" "$OUT/classes.jar"
python3 - "$OUT" <<'PY'
import sys, zipfile, shutil, pathlib
o = pathlib.Path(sys.argv[1])
shutil.copy(o/'apk/base.apk', o/'apk/unaligned.apk')
with zipfile.ZipFile(o/'apk/unaligned.apk', 'a') as z:
    z.write(o/'dex/classes.dex', 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
    z.write(o/'libbeast_lab.so', 'lib/arm64-v8a/libbeast_lab.so', compress_type=zipfile.ZIP_DEFLATED)
PY
"$BT/zipalign" -f -p 4 "$OUT/apk/unaligned.apk" "$OUT/apk/beast-lab-unsigned.apk"
echo "=== lab done: $OUT/apk/beast-lab-unsigned.apk ==="

# Beast 6DoF Test: same code and native bridge, its own manifest (one-screen app)
"$BT/aapt2" link -I "$JAR" --manifest sensor/AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 35 -o "$OUT/apk/sensor-base.apk"
python3 - "$OUT" <<'PY'
import sys, zipfile, shutil, pathlib
o = pathlib.Path(sys.argv[1])
shutil.copy(o/'apk/sensor-base.apk', o/'apk/sensor-unaligned.apk')
with zipfile.ZipFile(o/'apk/sensor-unaligned.apk', 'a') as z:
    z.write(o/'dex/classes.dex', 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
    z.write(o/'libbeast_lab.so', 'lib/arm64-v8a/libbeast_lab.so', compress_type=zipfile.ZIP_DEFLATED)
PY
"$BT/zipalign" -f -p 4 "$OUT/apk/sensor-unaligned.apk" "$OUT/apk/beast-6dof-test-unsigned.apk"
echo "=== sensor done: $OUT/apk/beast-6dof-test-unsigned.apk ==="
