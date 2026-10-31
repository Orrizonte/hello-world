package fr.externalscreen.beastfingerdiagnostic;

import android.content.Context;
import android.graphics.Bitmap;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.components.containers.Landmark;
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

    final int numHands;

    HandLandmarkTracker(Context context, int numHands) {
        this.numHands = numHands;
        BaseOptions base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build();
        HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(numHands)
            .setMinHandDetectionConfidence(0.55f)
            // gesture mode (1 hand): once found (palm needed), keep tracking even
            // when only the fingers stay in view
            .setMinHandPresenceConfidence(numHands == 1 ? 0.3f : 0.55f)
            .setMinTrackingConfidence(numHands == 1 ? 0.25f : 0.45f)
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
            int tw = Math.min(numHands == 1 ? 480 : 640, frame.getWidth());
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
                List<List<NormalizedLandmark>> all = r.landmarks();
                List<List<Landmark>> world = r.worldLandmarks();
                for (int hIdx = 0; hIdx < all.size(); hIdx++) {
                    List<NormalizedLandmark> hand = all.get(hIdx);
                    if (hand.size() < 21) continue;
                    // 42 image coordinates (px) + 63 metric world coordinates (m)
                    float[] lm = new float[105];
                    for (int i = 0; i < 21; i++) {
                        lm[2 * i] = hand.get(i).x() * fullW;
                        lm[2 * i + 1] = hand.get(i).y() * fullH;
                    }
                    if (world != null && hIdx < world.size() && world.get(hIdx).size() >= 21) {
                        List<Landmark> wl = world.get(hIdx);
                        for (int i = 0; i < 21; i++) {
                            lm[42 + 3 * i] = wl.get(i).x(); lm[43 + 3 * i] = wl.get(i).y(); lm[44 + 3 * i] = wl.get(i).z();
                        }
                    } else {
                        lm = java.util.Arrays.copyOf(lm, 42);
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
