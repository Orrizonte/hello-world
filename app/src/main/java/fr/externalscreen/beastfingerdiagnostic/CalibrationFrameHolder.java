package fr.externalscreen.beastfingerdiagnostic;

import android.graphics.Bitmap;

/**
 * Same-process, memory-only camera snapshot for calibration.
 * Avoids Android Binder's ~1 MB broadcast limit and keeps the camera's native
 * 1920x1080 picture, rather than shrinking it into a portrait dialog.
 */
public final class CalibrationFrameHolder {
    private static Bitmap frame;
    private CalibrationFrameHolder() {}
    public static synchronized void publish(Bitmap cameraFrame) {
        Bitmap fresh = cameraFrame.copy(Bitmap.Config.ARGB_8888, false);
        if (frame != null && frame != fresh && !frame.isRecycled()) frame.recycle();
        frame=fresh;
    }
    public static synchronized Bitmap peek() {
        return frame;
    }
    public static synchronized void clear(Bitmap expected) {
        if (frame == expected) {
            frame=null;
            if (expected != null && !expected.isRecycled()) expected.recycle();
        }
    }
}
