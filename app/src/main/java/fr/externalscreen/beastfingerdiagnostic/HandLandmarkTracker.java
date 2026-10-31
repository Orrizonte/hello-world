package fr.externalscreen.beastfingerdiagnostic;

import android.content.Context;
import android.graphics.Bitmap;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MediaPipe HandLandmarker (offline model in assets) on its own thread with
 * "latest frame wins": the segmentation never waits for it, it uses the most
 * recent skeleton as a spatial prior and as skin-colour sampling positions.
 */
final class HandLandmarkTracker implements AutoCloseable {
    private final HandLandmarker landmarker;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hands");
        t.setPriority(Thread.NORM_PRIORITY + 1);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile List<float[]> latest = Collections.emptyList();
    private volatile long latestTimeNs;
    private volatile long lastRunMs;
    private long lastTsMs;
    private volatile boolean closed;

    HandLandmarkTracker(Context context) {
        BaseOptions base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build();
        HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.45f)
            .setMinHandPresenceConfidence(0.45f)
            .setMinTrackingConfidence(0.45f)
            .build();
        landmarker = HandLandmarker.createFromOptions(context.getApplicationContext(), options);
    }

    /** Landmarks of the last processed frame: 42 floats per hand, camera pixels. */
    List<float[]> latest() { return latest; }
    long latestTimeNs() { return latestTimeNs; }
    long lastRunMs() { return lastRunMs; }

    /** Must be called with a bitmap the caller may reuse afterwards (a scaled copy is made). */
    void submit(Bitmap frame, int fullW, int fullH, long timeNs) {
        if (closed || !busy.compareAndSet(false, true)) return;
        final Bitmap small;
        try {
            int tw = Math.min(640, frame.getWidth());
            int th = Math.max(1, Math.round(frame.getHeight() * (tw / (float) frame.getWidth())));
            small = Bitmap.createScaledBitmap(frame, tw, th, true);
        } catch (RuntimeException e) {
            busy.set(false);
            return;
        }
        worker.execute(() -> {
            long t0 = android.os.SystemClock.elapsedRealtime();
            try {
                if (closed) return;
                long ts = timeNs / 1_000_000L;
                if (ts <= lastTsMs) ts = lastTsMs + 1;
                lastTsMs = ts;
                HandLandmarkerResult r = landmarker.detectForVideo(new BitmapImageBuilder(small).build(), ts);
                ArrayList<float[]> out = new ArrayList<>();
                for (List<NormalizedLandmark> hand : r.landmarks()) {
                    if (hand.size() < 21) continue;
                    float[] lm = new float[42];
                    for (int i = 0; i < 21; i++) {
                        lm[2 * i] = hand.get(i).x() * fullW;
                        lm[2 * i + 1] = hand.get(i).y() * fullH;
                    }
                    out.add(lm);
                }
                latest = out;
                latestTimeNs = timeNs;
            } catch (Throwable ignored) {
                latest = Collections.emptyList();
            } finally {
                small.recycle();
                lastRunMs = android.os.SystemClock.elapsedRealtime() - t0;
                busy.set(false);
            }
        });
    }

    @Override public void close() {
        closed = true;
        worker.execute(() -> { try { landmarker.close(); } catch (Throwable ignored) { } });
        worker.shutdown();
    }
}
