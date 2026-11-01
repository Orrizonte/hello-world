package fr.externalscreen.beastlab;

/** JNI access to VITURE libglasses.so (added to the APK locally, never committed). */
final class LabBridge {
    static final String LOAD_ERROR;
    static {
        String e = null;
        try { System.loadLibrary("glasses"); } catch (Throwable t) { e = "libglasses.so : " + t.getMessage(); }
        try { System.loadLibrary("beast_lab"); } catch (Throwable t) { e = (e == null ? "" : e + " | ") + "libbeast_lab.so : " + t.getMessage(); }
        LOAD_ERROR = e;
    }
    interface StateListener { void onState(int id, int value); }
    static volatile StateListener listener;
    static void onState(int id, int value) { StateListener l = listener; if (l != null) l.onState(id, value); }

    static native String nConnect(int pid, int fd, String cacheDir);
    static native String nInfo();
    static native int nCall(String op, int arg, boolean withArg);
    static native int nImu(boolean open, int freq);
    static native float[] nPose();
    static native String nSdkLog();
    static native void nDisconnect();
}
