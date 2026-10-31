package fr.externalscreen.beastfingerdiagnostic;

/** JNI access to the Beast camera: standard UVC first, VITURE SDK as fallback. */
public final class NativeCameraBridge {
    public static final int VENDOR_ID = 0x0C45;
    public static final int PRODUCT_ID = 0x6368;
    private static final String error;
    private static final boolean vitureSdkPresent;
    static {
        boolean sdk = false;
        try {
            // Optional: only present if the user injected VITURE's libglasses.so.
            System.loadLibrary("glasses");
            sdk = true;
        } catch (Throwable ignored) { }
        vitureSdkPresent = sdk;
        String failure = null;
        try {
            System.loadLibrary("beast_cam_bridge");
        } catch (Throwable e) {
            failure = e.toString();
        }
        error = failure;
    }
    private NativeCameraBridge() { }

    public interface FrameListener {
        /** Complete JPEG image (MJPEG stream). Called on the camera thread. */
        void onJpegFrame(byte[] jpeg);
        /** ARGB pixels (YUYV stream, already halved). Called on the camera thread. */
        void onArgbFrame(int[] argb, int width, int height);
    }

    public static boolean available() { return error == null; }
    public static boolean vitureSdkPresent() { return vitureSdkPresent; }
    public static String loadError() { return error; }

    private static native int nativeStart(int fd, FrameListener listener, int width, int height, boolean preferViture);
    private static native void nativeStop();
    private static native String nativeDescribe();
    private static native long nativeFrameCount();

    public static int start(int fd, FrameListener listener, boolean preferViture) {
        return available() ? nativeStart(fd, listener, 1280, 720, preferViture && vitureSdkPresent) : -110;
    }
    public static void stop() { if (available()) nativeStop(); }
    public static String describe() { return available() ? nativeDescribe() : String.valueOf(error); }
    public static long frameCount() { return available() ? nativeFrameCount() : 0; }
}
