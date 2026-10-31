package fr.externalscreen.beastfingerdiagnostic;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;
import java.util.List;

/**
 * Full-screen, non-touchable layer drawing only the finger outlines (and the
 * optional thin reference frame on the screen border). Redrawn every vsync
 * while fingers are visible, with a short motion prediction to hide latency.
 */
final class FingerOverlayView extends View {
    static final class Frame {
        final List<float[]> contours;
        final long timeNs;      // camera capture time (elapsedRealtimeNanos)
        final float vx, vy;     // px/s
        Frame(List<float[]> c, long t, float vx, float vy) { contours = c; timeNs = t; this.vx = vx; this.vy = vy; }
    }

    private volatile Frame frame;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint();
    private final Path path = new Path();
    private final int[] loc = new int[2];
    private int screenW = 1, screenH = 1;
    private int strokeAlpha = 235;
    private float strokeWidth = 6f;
    private boolean fillOn = true;
    private boolean frameOn = true;
    private float frameWidth = 26f;
    private boolean predict = true;
    private volatile boolean blink;

    /** While the screen is being searched, the frame blinks (verified by the camera). */
    void setBlink(boolean b) { if (b != blink) { blink = b; postInvalidateOnAnimation(); } }

    FingerOverlayView(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        border.setStyle(Paint.Style.STROKE);
        border.setColor(0xffe6e6e6);
        applyPaints();
    }

    void configure(int strokeAlpha, float strokeWidthPx, boolean fillOn, boolean frameOn, float frameWidthPx, boolean predict) {
        this.strokeAlpha = strokeAlpha;
        this.strokeWidth = strokeWidthPx;
        this.fillOn = fillOn;
        this.frameOn = frameOn;
        this.frameWidth = frameWidthPx;
        this.predict = predict;
        applyPaints();
        postInvalidateOnAnimation();
    }

    private void applyPaints() {
        stroke.setStrokeWidth(strokeWidth);
        stroke.setColor((strokeAlpha << 24) | 0xffffff);
        glow.setStrokeWidth(strokeWidth * 3.2f);
        glow.setColor(((strokeAlpha * 70 / 255) << 24) | 0x8fd8ff);
        glow.setMaskFilter(new BlurMaskFilter(Math.max(1f, strokeWidth * 1.4f), BlurMaskFilter.Blur.NORMAL));
        fill.setColor(((strokeAlpha * 30 / 255) << 24) | 0xffffff);
        border.setStrokeWidth(frameWidth);
    }

    void setScreenSize(int w, int h) {
        screenW = Math.max(1, w); screenH = Math.max(1, h);
        postInvalidateOnAnimation();
    }

    void show(Frame f) {
        frame = f;
        postInvalidateOnAnimation();
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        getLocationOnScreen(loc);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.translate(-loc[0], -loc[1]); // draw in display coordinates
        boolean frameVisible = !blink || (SystemClock.uptimeMillis() / 130) % 2 == 0;
        if (frameOn && frameWidth > 0 && frameVisible) {
            // white line on the very edge + black line inside it: a thin bright
            // ridge for the camera, whatever the room light or the UI theme
            float h = frameWidth / 2f;
            border.setColor(0xff000000);
            canvas.drawRect(3 * h, 3 * h, screenW - 3 * h, screenH - 3 * h, border);
            border.setColor(0xffffffff);
            canvas.drawRect(h, h, screenW - h, screenH - h, border);
        }
        Frame f = frame;
        boolean animate = false;
        if (f != null && !f.contours.isEmpty()) {
            float ageMs = (SystemClock.elapsedRealtimeNanos() - f.timeNs) / 1e6f;
            if (ageMs < 260) {
                float fade = ageMs < 160 ? 1f : 1f - (ageMs - 160) / 100f;
                float dx = 0, dy = 0;
                if (predict) {
                    float dt = Math.min(0.06f, Math.max(0f, ageMs / 1000f)) * 0.7f;
                    dx = clamp(f.vx * dt, 40); dy = clamp(f.vy * dt, 40);
                }
                path.rewind();
                for (float[] c : f.contours) addSmooth(path, c, dx, dy);
                if (fade < 1f) {
                    stroke.setAlpha((int) (strokeAlpha * fade));
                    glow.setAlpha((int) (strokeAlpha * 70 / 255 * fade));
                    fill.setAlpha((int) (strokeAlpha * 30 / 255 * fade));
                }
                if (fillOn) canvas.drawPath(path, fill);
                canvas.drawPath(path, glow);
                canvas.drawPath(path, stroke);
                if (fade < 1f) applyPaints();
                animate = true;
            }
        }
        canvas.restore();
        if (animate || blink) postInvalidateOnAnimation();
    }

    private static float clamp(float v, float m) { return Math.max(-m, Math.min(m, v)); }

    /** Closed curve through the midpoints of consecutive vertices (quadratic B-spline). */
    private static void addSmooth(Path p, float[] c, float dx, float dy) {
        int n = c.length / 2;
        if (n < 3) return;
        float mx = (c[2 * (n - 1)] + c[0]) / 2 + dx, my = (c[2 * (n - 1) + 1] + c[1]) / 2 + dy;
        p.moveTo(mx, my);
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float x = c[2 * i] + dx, y = c[2 * i + 1] + dy;
            float nx = (c[2 * i] + c[2 * j]) / 2 + dx, ny = (c[2 * i + 1] + c[2 * j + 1]) / 2 + dy;
            p.quadTo(x, y, nx, ny);
        }
        p.close();
    }
}
