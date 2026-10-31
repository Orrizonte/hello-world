package fr.externalscreen.beastfingerdiagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One camera frame in, finger outlines in phone-screen pixels out. Pure Java.
 *
 * screen quad (ScreenTracker) -> homographies -> FingerSegmenter (rectified)
 * Head motion is absorbed by re-detecting the screen every frame; the quad is
 * held for a short time if a frame misses it.
 */
public final class FrameProcessor {
    public static final class Result {
        public long timeNs;
        public boolean screenFound;
        public boolean screenHeld;
        public float[] quad;            // camera px TL,TR,BR,BL or null
        public List<float[]> contours = Collections.emptyList(); // screen px
        public int hands;
        public float vx, vy;            // hand motion, screen px / s
        public long trackMs;
        public String diag = "";
    }

    private final ScreenTracker tracker = new ScreenTracker();
    private final FingerSegmenter segmenter = new FingerSegmenter();
    private ScreenTracker.Quad last;
    private long lastQuadNs;
    private int[] luma;
    private final OneEuroFilter fvx = new OneEuroFilter(1.0f, 0.0f, 1.0f);
    private final OneEuroFilter fvy = new OneEuroFilter(1.0f, 0.0f, 1.0f);
    private float lastHx = Float.NaN, lastHy;
    private long lastHandNs;

    public FingerSegmenter segmenter() { return segmenter; }

    public void setFrameMode(boolean on) { if (tracker.frameMode != on) { tracker.frameMode = on; last = null; } }

    /** Forget the tracked screen (e.g. after the display rotated). */
    public void resetScreen() { last = null; }

    /**
     * @param hands landmark arrays (42 floats in camera px)
     * @param handTimeNs capture time of the frame the landmarks came from
     */
    public Result process(int[] argb, int w, int h, List<float[]> hands, long handTimeNs,
                          int screenW, int screenH, long timeNs) {
        long t0 = System.nanoTime();
        Result r = new Result();
        r.timeNs = timeNs;
        r.hands = hands == null ? 0 : hands.size();
        if (luma == null || luma.length != w * h) luma = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int p = argb[i];
            luma[i] = (77 * ((p >> 16) & 255) + 150 * ((p >> 8) & 255) + 29 * (p & 255)) >> 8;
        }
        float aspect = screenH / (float) screenW;
        ScreenTracker.Quad q = tracker.detect(luma, null, w, h, last, aspect, false);
        if (q != null && last != null) {
            // reject a sudden implausible jump unless it repeats
            float d = ScreenTracker.meanCornerDistance(q.pts, last.pts);
            float diag = (float) Math.hypot(last.pts[4] - last.pts[0], last.pts[5] - last.pts[1]);
            if (d > 0.6f * diag && timeNs - lastQuadNs < 150_000_000L) q = null;
        }
        if (q != null) {
            last = q; lastQuadNs = timeNs; r.screenFound = true;
        } else if (last != null && timeNs - lastQuadNs < 350_000_000L) {
            q = last; r.screenHeld = true;
        } else {
            last = null;
        }
        if (q == null) {
            r.trackMs = (System.nanoTime() - t0) / 1_000_000;
            r.diag = "écran non trouvé (seuil " + tracker.lastThreshold() + ", zones " + tracker.lastComponents() + ")";
            return r;
        }
        r.quad = q.pts.clone();
        float[] scr = {0, 0, screenW, 0, screenW, screenH, 0, screenH};
        Homography c2s = Homography.fromQuads(q.pts, scr);
        Homography s2c = Homography.fromQuads(scr, q.pts);
        if (c2s == null || s2c == null) { r.diag = "homographie dégénérée"; return r; }

        List<float[]> handsIn = hands == null ? new ArrayList<>() : hands;
        r.contours = segmenter.process(argb, w, h, s2c, c2s, handsIn, screenW, screenH);
        // Sanity: fingers never cover most of the screen; a huge silhouette means
        // the screen was mis-located or the background model is stale.
        float covered = 0;
        for (float[] c : r.contours) covered += Contours.area(c);
        if (Math.abs(covered) > 0.4f * screenW * screenH) {
            r.contours = Collections.emptyList(); // hide only: the models must keep learning
        }

        // hand velocity in screen space (for display-side prediction)
        if (!handsIn.isEmpty() && handTimeNs != lastHandNs) {
            float[] lm = handsIn.get(0), o = new float[2];
            float sx = 0, sy = 0;
            int[] tips = {4, 8, 12, 16, 20};
            for (int t : tips) { c2s.map(lm[2 * t], lm[2 * t + 1], o, 0); sx += o[0]; sy += o[1]; }
            sx /= 5; sy /= 5;
            if (!Float.isNaN(lastHx) && handTimeNs > lastHandNs) {
                float dt = (handTimeNs - lastHandNs) / 1e9f;
                if (dt > 0.005f && dt < 0.25f) {
                    fvx.filter((sx - lastHx) / dt, handTimeNs);
                    fvy.filter((sy - lastHy) / dt, handTimeNs);
                }
            }
            lastHx = sx; lastHy = sy; lastHandNs = handTimeNs;
        } else if (handsIn.isEmpty()) {
            lastHx = Float.NaN; fvx.reset(); fvy.reset();
        }
        r.vx = fvx.ready() ? fvx.value() : 0;
        r.vy = fvy.ready() ? fvy.value() : 0;
        r.trackMs = (System.nanoTime() - t0) / 1_000_000;
        FingerSegmenter s = segmenter;
        r.diag = (r.screenHeld ? "écran (maintenu)" : "écran OK") + (q.refined ? " sous-pixel" : "")
            + String.format(java.util.Locale.US, " cadre %.0f%%", 100 * tracker.lastRingSupport())
            + " | fond appris " + s.lastValidPct + "% | gain " + String.format(java.util.Locale.US, "%.2f", s.lastGain)
            + " | peau " + (s.skinTrained() ? "adaptée" : "générique")
            + " | zones doigts " + (s.lastComponents - s.lastRejected) + "/" + s.lastComponents
            + " | contours " + r.contours.size();
        return r;
    }
}
