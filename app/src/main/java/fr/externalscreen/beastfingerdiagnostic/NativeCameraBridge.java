package fr.externalscreen.beastfingerdiagnostic;

public final class NativeCameraBridge {
    private static final String error;
    static {
        String failure = null;
        try {
            System.loadLibrary("glasses"); // Added after GitHub build; not published to GitHub
            System.loadLibrary("beast_cam_bridge");
        } catch (UnsatisfiedLinkError e) {
            failure = e.toString();
        }
        error = failure;
    }
    private NativeCameraBridge() { }
    public interface FrameListener {
        void onJpegFrame(byte[] jpeg);
    }
    public static boolean available() { return error == null; }
    public static String loadError() { return error; }
    private static native int nativeStart(int fd, FrameListener listener);
    private static native void nativeStop();
    public static int start(int fd, FrameListener listener) {
        return available() ? nativeStart(fd, listener) : -110;
    }
    public static void stop() {
        if (available()) nativeStop();
    }
}
