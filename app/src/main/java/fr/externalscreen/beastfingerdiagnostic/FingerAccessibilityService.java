package fr.externalscreen.beastfingerdiagnostic;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

/**
 * Hosts the outline/cursor layer as TYPE_ACCESSIBILITY_OVERLAY (ordinary app
 * overlays are stacked BELOW the soft keyboard) and, in gesture mode, injects
 * taps / long presses / drags / back. It reads no window content.
 */
public final class FingerAccessibilityService extends AccessibilityService {
    static volatile FingerAccessibilityService instance;

    private GestureDescription.StrokeDescription dragStroke;
    private float dragX, dragY, pendX, pendY;
    private boolean dragBusy, dragEndPending, dragActive;
    private float jitter = 0.5f;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        FingerService s = FingerService.instance;
        if (s != null) s.onAccessibilityChanged();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(android.content.Intent intent) {
        release();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        release();
        super.onDestroy();
    }

    private void release() {
        if (instance == this) {
            instance = null;
            FingerService s = FingerService.instance;
            if (s != null) s.onAccessibilityChanged();
        }
    }

    // ------------------------------------------------------------------ gestures (main thread)
    void tap(float x, float y, long durationMs) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        dispatchGesture(new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(p, 0, durationMs)).build(), null, null);
    }

    void back() { performGlobalAction(GLOBAL_ACTION_BACK); }

    void dragStart(float x, float y) {
        dragActive = true; dragEndPending = false;
        dragX = x; dragY = y; pendX = x; pendY = y;
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x + 0.5f, y + 0.5f);
        dragX += 0.5f; dragY += 0.5f;
        dragStroke = new GestureDescription.StrokeDescription(p, 0, 30, true);
        send(dragStroke);
    }

    void dragMove(float x, float y) {
        if (!dragActive) return;
        pendX = x; pendY = y;
        if (!dragBusy) step(false);
    }

    void dragEnd(float x, float y) {
        if (!dragActive) return;
        pendX = x; pendY = y;
        dragEndPending = true;
        if (!dragBusy) step(true);
    }

    private void step(boolean last) {
        if (dragStroke == null) return;
        Path p = new Path();
        p.moveTo(dragX, dragY);
        float nx = pendX, ny = pendY;
        if (Math.abs(nx - dragX) < 0.5f && Math.abs(ny - dragY) < 0.5f) { jitter = -jitter; nx = dragX + jitter; } // still: wiggle 0.5 px in place
        p.lineTo(nx, ny);
        dragX = nx; dragY = ny;
        dragStroke = dragStroke.continueStroke(p, 0, 30, !last);
        if (last) dragActive = false;
        send(dragStroke);
    }

    private void send(GestureDescription.StrokeDescription s) {
        dragBusy = true;
        boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { next(); }
            @Override public void onCancelled(GestureDescription g) { dragActive = false; dragStroke = null; dragBusy = false; }
        }, null);
        if (!ok) { dragActive = false; dragStroke = null; dragBusy = false; }
    }

    private void next() {
        dragBusy = false;
        if (!dragActive) { dragStroke = null; return; }
        if (dragEndPending) step(true);
        else if (Math.abs(pendX - dragX) > 0.5f || Math.abs(pendY - dragY) > 0.5f) step(false);
        else {
            // keep the finger "down" while the hand holds still
            step(false);
        }
    }
}
