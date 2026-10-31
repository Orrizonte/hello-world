#!/usr/bin/env bash
# Build-time only network use: download Apache-2.0 MediaPipe and official model.
# On-device APK declares NO INTERNET permission. The Viture SDK remains local.
set -euo pipefail
ROOT="$PWD/manual-build/mediapipe"
mkdir -p "$ROOT"/{jar,native,asset}
MP_VERSION=0.10.32
get_google() {
    local group="$1" artifact="$2" version="$3" ext="$4" dest="$5"
    local path="${group//.//}/$artifact/$version/$artifact-$version.$ext"
    curl -fLsS --retry 3 --max-time 65 "https://dl.google.com/dl/android/maven2/$path" -o "$dest"
}
get_maven() {
    local group="$1" artifact="$2" version="$3"
    local path="${group//.//}/$artifact/$version/$artifact-$version.jar"
    echo "Fetching $group:$artifact:$version"
    curl -fLsS --retry 3 --max-time 65 "https://repo.maven.apache.org/maven2/$path" -o "$ROOT/jar/$artifact-$version.jar"
}
for name in tasks-vision tasks-core; do
    get_google com.google.mediapipe "$name" "$MP_VERSION" aar "$ROOT/$name.aar"
    unzip -p "$ROOT/$name.aar" classes.jar > "$ROOT/jar/$name.jar"
done
unzip -p "$ROOT/tasks-vision.aar" jni/arm64-v8a/libmediapipe_tasks_vision_jni.so > "$ROOT/native/libmediapipe_tasks_vision_jni.so"
curl -fLsS --retry 3 --max-time 90 \
    'https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task' \
    -o "$ROOT/asset/hand_landmarker.task"

# Direct runtime dependencies declared by Google's AAR POM files.
get_maven com.google.guava guava 27.0.1-android
get_maven com.google.guava failureaccess 1.0.1
get_maven com.google.guava listenablefuture 9999.0-empty-to-avoid-conflict-with-guava
get_maven com.google.protobuf protobuf-javalite 4.26.1
get_maven com.google.flogger flogger 0.6
get_maven com.google.flogger flogger-system-backend 0.6
get_maven com.google.code.findbugs jsr305 3.0.2
get_maven org.checkerframework checker-qual 2.5.2
get_maven com.google.errorprone error_prone_annotations 2.2.0
get_maven com.google.j2objc j2objc-annotations 1.1
# CRITICAL: TransportRuntime_Factory extends javax.inject.Provider.
# Without javax.inject, Android reports NoClassDefFoundError for its Dagger Factory.
get_maven javax.inject javax.inject 1
get_maven org.checkerframework checker-compat-qual 2.5.5
# Android transport classes used by MediaPipe's optional instrumentation.
for item in transport-api:3.0.0 transport-runtime:3.1.0 transport-backend-cct:3.1.0; do
    artifact="${item%%:*}"; version="${item#*:}"
    get_google com.google.android.datatransport "$artifact" "$version" aar "$ROOT/$artifact.aar"
    unzip -p "$ROOT/$artifact.aar" classes.jar > "$ROOT/jar/$artifact.jar"
done
# DataTransport dependencies are loaded indirectly by MediaPipe. These are JARs,
# not AARs. Fetch explicitly so Android doesn't crash during model creation.
for dependency in \
  "com.google.firebase:firebase-encoders:16.1.0" \
  "com.google.firebase:firebase-encoders-proto:16.0.0"; do
    IFS=: read -r group artifact version <<< "$dependency"
    url_maven="https://repo.maven.apache.org/maven2/${group//.//}/$artifact/$version/$artifact-$version.jar"
    url_google="https://dl.google.com/dl/android/maven2/${group//.//}/$artifact/$version/$artifact-$version.jar"
    output="$ROOT/jar/$artifact-$version.jar"
    if curl -fLsS --retry 2 --max-time 30 "$url_google" -o "$output"; then
      echo "Google jar: $artifact"
    elif curl -fLsS --retry 2 --max-time 30 "$url_maven" -o "$output"; then
      echo "Maven jar: $artifact"
    else
      echo "Optional Firebase transport jar not available: $artifact"
      rm -f "$output"
    fi
  done

# Firebase JSON encoders are packaged as Android AARs, not JARs.
get_google com.google.firebase firebase-encoders-json 17.1.0 aar "$ROOT/firebase-encoders-json.aar"
unzip -p "$ROOT/firebase-encoders-json.aar" classes.jar > "$ROOT/jar/firebase-encoders-json.jar"
# Prove core classes really exist before compiling, not merely referenced.
for check in \
  "javax/inject/Provider.class" \
  "com/google/firebase/encoders/DataEncoder.class" \
  "com/google/firebase/encoders/json/JsonDataEncoderBuilder.class" \
  "com/google/firebase/encoders/proto/ProtobufEncoder.class"; do
    found=false
    for jarfile in "$ROOT"/jar/*.jar; do
      if unzip -l "$jarfile" "$check" 2>/dev/null | grep -q "$check"; then
        found=true; break
      fi
    done
    if [ "$found" != true ]; then
      echo "MISSING runtime class: $check" >&2
      exit 17
    fi
    echo "Verified bundled: $check"
  done

cat > "$ROOT/asset/MEDIAPIPE-LICENSE-NOTICE.txt" <<'EOF'
MediaPipe Tasks for Android and hand_landmarker.task model.
Copyright 2026 Google LLC and MediaPipe Authors.
Licensed under the Apache License, Version 2.0:
https://www.apache.org/licenses/LICENSE-2.0
Original sample: https://github.com/google-ai-edge/mediapipe-samples
Google Android artifacts: com.google.mediapipe:tasks-vision:0.10.32 and tasks-core:0.10.32
EOF
ls -lh "$ROOT/asset/" "$ROOT/native/" "$ROOT/jar/" | head -45
