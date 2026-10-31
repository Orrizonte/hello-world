package fr.externalscreen.beastfingerdiagnostic;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Used ONLY to host the outline layer as TYPE_ACCESSIBILITY_OVERLAY. Ordinary
 * app overlays (TYPE_APPLICATION_OVERLAY) are stacked BELOW the soft keyboard,
 * so the outlines would disappear exactly over the keys. This service reads no
 * window content and receives no events.
 */
public final class FingerAccessibilityService extends AccessibilityService {
    static volatile FingerAccessibilityService instance;

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
}
