package fr.externalscreen.beastfingerdiagnostic;

/**
 * Mid-air hand control (Vision Pro / Quest conventions), pure Java.
 *
 *  - cursor     : index knuckle (landmark 5) blended with the wrist, so pinching
 *                 does not move the cursor; mapped from a central "comfort box"
 *                 of the camera image to the screen, 1€-filtered
 *  - pinch      : thumb tip (4) to index tip (8), relative to palm size,
 *                 with hysteresis
 *  - tap        : short pinch without movement
 *  - long press : pinch held ~0.6 s without movement
 *  - drag       : pinch + movement (scroll, swipe, move)
 *  - back       : thumb tip (4) to middle tip (12) pinch
 */
public final class GestureController {
    public enum Event { NONE, TAP, LONG_PRESS, DRAG_START, DRAG_MOVE, DRAG_END, BACK }

    public static final class Output {
        public boolean visible;
        public float x, y;          // screen px
        public float pinch;         // 0 (open) .. 1 (closed), for the cursor drawing
        public float holdProgress;  // 0..1 towards long press
        public boolean pinching, dragging;
        public Event event = Event.NONE;
    }

    /** thumb + middle pinch = back */
    public boolean backGesture = true;

    // tunables
    public float boxLeft = 0.18f, boxRight = 0.82f, boxTop = 0.15f, boxBottom = 0.80f;
    private static final float PINCH_ON = 0.24f, PINCH_OFF = 0.40f;
    /** pinch hold before the long press ("right click"), set from the settings slider */
    public long longPressMs = 1000;
    private static final long FREEZE_MS = 140;
    private static final float MOVE_START = 0.035f; // fraction of screen diagonal

    private final OneEuroFilter fx = new OneEuroFilter(0.7f, 0.006f, 1.0f);
    private final OneEuroFilter fy = new OneEuroFilter(0.7f, 0.006f, 1.0f);
    private float holdX = Float.NaN, holdY;
    private boolean pinching, dragging, longFired, backPinch;
    private long pinchStartMs, lastSeenMs;
    private float pinchX, pinchY;
    private int lostFrames, seenFrames, pinchVotes, releaseVotes;

    public void reset() {
        fx.reset(); fy.reset(); holdX = Float.NaN;
        seenFrames = 0; pinchVotes = 0; releaseVotes = 0;
        pinching = dragging = longFired = backPinch = false;
    }

    /**
     * @param hands landmark arrays (42 floats, camera px); the largest hand is used
     * @param camW,camH camera image size
     * @param sw,sh screen size (px)
     */
    public Output update(java.util.List<float[]> hands, int camW, int camH, int sw, int sh, long nowMs) {
        Output o = new Output();
        float[] lm = pickHand(hands);
        if (lm == null) {
            // short dropouts (motion blur) do not cancel a drag immediately
            if (++lostFrames > 6) {
                if (dragging) o.event = Event.DRAG_END;
                reset();
            } else if (fx.ready()) {
                o.visible = true; o.x = fx.value(); o.y = fy.value(); o.pinching = pinching; o.dragging = dragging;
            }
            return o;
        }
        lostFrames = 0;
        lastSeenMs = nowMs;
        seenFrames++;
        float palm = dist(lm, 0, 9);
        if (palm < 4) return o;
        float pinchD, backD;
        if (lm.length >= 105) {
            // metric 3D distances (MediaPipe world landmarks): a thumb hidden
            // behind the index in the image is NOT a pinch
            float palmW = dist3(lm, 0, 9);
            if (palmW < 0.03f) return o;
            pinchD = dist3(lm, 4, 8) / palmW;
            backD = dist3(lm, 4, 12) / palmW;
        } else {
            pinchD = dist(lm, 4, 8) / palm;
            backD = dist(lm, 4, 12) / palm;
        }

        // cursor anchor: index knuckle pulled slightly towards the wrist
        float ax = 0.8f * lm[10] + 0.2f * lm[0], ay = 0.8f * lm[11] + 0.2f * lm[1];
        float u = (ax / camW - boxLeft) / (boxRight - boxLeft);
        float v = (ay / camH - boxTop) / (boxBottom - boxTop);
        u = Math.max(0, Math.min(1, u)); v = Math.max(0, Math.min(1, v));
        long ns = nowMs * 1_000_000L;
        float cx = fx.filter(u * sw, ns), cy = fy.filter(v * sh, ns);
        // dead zone: tiny tremor (< 0.6 % of the screen) does not move the cursor
        float dz = 0.006f * (float) Math.hypot(sw, sh);
        if (Float.isNaN(holdX) || Math.hypot(cx - holdX, cy - holdY) > dz) {
            if (!Float.isNaN(holdX)) {
                // follow, but keep the dead-zone lag out of fast moves
                float dd = (float) Math.hypot(cx - holdX, cy - holdY);
                holdX = cx - (cx - holdX) * dz / dd; holdY = cy - (cy - holdY) * dz / dd;
            } else { holdX = cx; holdY = cy; }
        }
        cx = holdX; cy = holdY;

        boolean wasPinching = pinching;
        // debounce: 2 consecutive frames to start, 2 to release; nothing during
        // the first frames after the hand appears (tracking still settling)
        if (seenFrames < 8) { pinchVotes = 0; }
        else if (!pinching) { pinchVotes = pinchD < PINCH_ON ? pinchVotes + 1 : 0; if (pinchVotes >= 2) { pinching = true; releaseVotes = 0; } }
        else { releaseVotes = pinchD > PINCH_OFF ? releaseVotes + 1 : 0; if (releaseVotes >= 2) { pinching = false; pinchVotes = 0; } }

        float diag = (float) Math.hypot(sw, sh);
        if (pinching && !wasPinching) {
            pinchStartMs = nowMs; pinchX = cx; pinchY = cy; longFired = false; dragging = false;
        }
        // during the first ms of a pinch the cursor is frozen (pinching jolts the hand)
        if (pinching && nowMs - pinchStartMs < FREEZE_MS) { cx = pinchX; cy = pinchY; }

        if (pinching) {
            float moved = (float) Math.hypot(cx - pinchX, cy - pinchY) / diag;
            if (!dragging && !longFired && moved > MOVE_START) {
                dragging = true; o.event = Event.DRAG_START;
                o.x = pinchX; o.y = pinchY; // drag starts where the pinch began
            } else if (dragging) {
                o.event = Event.DRAG_MOVE;
            } else if (!longFired && nowMs - pinchStartMs >= longPressMs) {
                longFired = true; o.event = Event.LONG_PRESS;
            }
            if (!dragging && !longFired) o.holdProgress = Math.min(1f, (nowMs - pinchStartMs) / (float) longPressMs);
        } else if (wasPinching) {
            if (dragging) o.event = Event.DRAG_END;
            else if (!longFired) { o.event = Event.TAP; o.x = pinchX; o.y = pinchY; } // where the pinch began
            dragging = false;
        }

        // back: thumb-middle pinch (only when the index is NOT pinched)
        if (backGesture && !pinching && seenFrames >= 8) {
            if (!backPinch && backD < PINCH_ON) { backPinch = true; if (o.event == Event.NONE) o.event = Event.BACK; }
            else if (backPinch && backD > PINCH_OFF) backPinch = false;
        }

        o.visible = true;
        if (o.event != Event.DRAG_START && o.event != Event.TAP) { o.x = cx; o.y = cy; }
        o.pinch = Math.max(0f, Math.min(1f, (PINCH_OFF + 0.15f - pinchD) / (PINCH_OFF + 0.15f - PINCH_ON)));
        o.pinching = pinching;
        o.dragging = dragging;
        return o;
    }

    private static float[] pickHand(java.util.List<float[]> hands) {
        if (hands == null || hands.isEmpty()) return null;
        float[] best = null; float bestSize = -1;
        for (float[] h : hands) {
            float s = dist(h, 0, 9);
            if (s > bestSize) { bestSize = s; best = h; }
        }
        return best;
    }

    private static float dist3(float[] lm, int a, int b) {
        float dx = lm[42 + 3 * a] - lm[42 + 3 * b], dy = lm[43 + 3 * a] - lm[43 + 3 * b], dz = lm[44 + 3 * a] - lm[44 + 3 * b];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** thumb-index opening relative to palm (3D when available) */
    private static float dist3or2(float[] lm, int a, int b) {
        return lm.length >= 105 ? dist3(lm, a, b) / Math.max(1e-3f, dist3(lm, 0, 9)) : dist(lm, a, b) / Math.max(1f, dist(lm, 0, 9));
    }

    private static float dist(float[] lm, int a, int b) {
        return (float) Math.hypot(lm[2 * a] - lm[2 * b], lm[2 * a + 1] - lm[2 * b + 1]);
    }
}
