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
# Android transport classes used by MediaPipe's optional instrumentation.
for item in transport-api:3.0.0 transport-runtime:3.1.0 transport-backend-cct:3.1.0; do
    artifact="${item%%:*}"; version="${item#*:}"
    get_google com.google.android.datatransport "$artifact" "$version" aar "$ROOT/$artifact.aar"
    unzip -p "$ROOT/$artifact.aar" classes.jar > "$ROOT/jar/$artifact.jar"
done
# Runtime ancillary dependencies used by the transport backend.
for item in \
  'com.google.android.datatransport:transport-events:3.0.0' \
  'com.google.firebase:firebase-encoders:16.1.0' \
  'com.google.firebase:firebase-encoders-json:17.1.0'; do
    IFS=: read -r group artifact version <<< "$item"
    if get_google "$group" "$artifact" "$version" aar "$ROOT/$artifact.aar"; then
      unzip -p "$ROOT/$artifact.aar" classes.jar > "$ROOT/jar/$artifact.jar"
    else
      echo "Optional transport library not downloaded: $artifact"
    fi
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
