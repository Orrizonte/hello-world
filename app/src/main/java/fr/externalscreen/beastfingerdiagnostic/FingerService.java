package fr.externalscreen.beastfingerdiagnostic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Camera -> vision -> outline overlay. Runs as a foreground service so it keeps
 * working while the user types in any app. Nothing is stored or sent anywhere.
 */
public final class FingerService extends Service {
    public static final String ACTION_START = "fr.externalscreen.beastfingerdiagnostic.START";
    public static final String ACTION_STOP = "fr.externalscreen.beastfingerdiagnostic.STOP";
    public static final String ACTION_CONFIG = "fr.externalscreen.beastfingerdiagnostic.CONFIG";
    public static final String ACTION_STATUS = "fr.externalscreen.beastfingerdiagnostic.STATUS";
    public static final String EXTRA_MESSAGE = "message";
    public static final String PREFS = "settings.v09";
    private static final String CHANNEL = "beast_fingers";

    static volatile FingerService instance;
    /** Latest annotated camera image for the in-app preview (null when not requested). */
    static final AtomicReference<Bitmap> preview = new AtomicReference<>();
    static volatile boolean previewWanted;
    /** Set by the activity: record the next frames into a zip in Downloads. */
    static volatile boolean recordRequest;
    private java.util.zip.ZipOutputStream rec;
    private java.io.File recFile;
    private StringBuilder recMeta;
    private int recCount;
    static volatile String stats = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object frameLock = new Object();
    private byte[] pendingJpeg;
    private int[] pendingArgb;
    private int pendingW, pendingH;
    private long pendingTimeNs;
    private volatile boolean running;
    private Thread processing;
    private UsbDeviceConnection connection;
    private final ArrayList<UsbInterface> claimed = new ArrayList<>();
    private HandLandmarkTracker hands;
    private String handsError;
    private final FrameProcessor processor = new FrameProcessor();
    private FingerOverlayView overlay;
    private WindowManager overlayWm;
    private boolean overlayIsA11y;
    private volatile int screenW = 1080, screenH = 2400, rotation = -1;
    private long lastFrameMs;
    private boolean triedOtherBackend;

    // settings
    private int strokeAlpha, strokeDp, frameDp;
    private boolean fillOn, frameOn, predictOn, rotate180, mirror, gestureMode;
    private final GestureController gestures = new GestureController();
    private long lastGestureLm;
    private String gestureDiag = "mode gestes";

    private final NativeCameraBridge.FrameListener cameraListener = new NativeCameraBridge.FrameListener() {
        @Override public void onJpegFrame(byte[] jpeg) {
            synchronized (frameLock) {
                pendingJpeg = jpeg; pendingArgb = null; pendingTimeNs = SystemClock.elapsedRealtimeNanos();
                frameLock.notify();
            }
        }
        @Override public void onArgbFrame(int[] argb, int width, int height) {
            synchronized (frameLock) {
                pendingArgb = argb; pendingJpeg = null; pendingW = width; pendingH = height;
                pendingTimeNs = SystemClock.elapsedRealtimeNanos();
                frameLock.notify();
            }
        }
    };

    private final BroadcastReceiver detach = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            UsbDevice d = Build.VERSION.SDK_INT >= 33
                ? i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                : i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (d != null && isCamera(d)) { status("Caméra débranchée : arrêt."); stopSelf(); }
        }
    };

    static boolean isCamera(UsbDevice d) {
        if (d == null) return false;
        if (d.getVendorId() == NativeCameraBridge.VENDOR_ID && d.getProductId() == NativeCameraBridge.PRODUCT_ID) return true;
        for (int i = 0; i < d.getInterfaceCount(); i++)
            if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        return false;
    }

    static UsbDevice findCamera(UsbManager usb) {
        UsbDevice any = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == NativeCameraBridge.VENDOR_ID && d.getProductId() == NativeCameraBridge.PRODUCT_ID) return d;
            if (any == null && isCamera(d)) any = d;
        }
        return any;
    }

    // ------------------------------------------------------------------ lifecycle
    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Contours des doigts", NotificationManager.IMPORTANCE_LOW));
        IntentFilter f = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(detach, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(detach, f);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) { stopSelf(); return START_NOT_STICKY; }
        loadSettings();
        if (ACTION_CONFIG.equals(action)) { applySettings(); return START_NOT_STICKY; }
        if (!ACTION_START.equals(action) || running) return START_NOT_STICKY;
        try {
            Notification n = notification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            else startForeground(42, n);
        } catch (RuntimeException e) {
            status("Service premier plan refusé : " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        processor.setFrameMode(frameOn);
        updateScreenGeometry();
        showOverlay();
        processing = new Thread(this::processLoop, "vision");
        processing.setPriority(Thread.MAX_PRIORITY - 1);
        processing.start();
        new Thread(() -> openCamera(false), "usb-open").start();
        main.postDelayed(watchdog, 3500);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        instance = null;
        main.removeCallbacksAndMessages(null);
        synchronized (frameLock) { frameLock.notifyAll(); }
        try { unregisterReceiver(detach); } catch (RuntimeException ignored) { }
        hideOverlay();
        closeCamera();
        if (hands != null) { hands.close(); hands = null; }
        preview.set(null);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("running", false).apply();
        status("Arrêté.");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, FingerService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Contours des doigts actifs")
            .setContentText("Caméra des Beast → contours sur l'écran")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Arrêter", stop).build())
            .build();
    }

    // ------------------------------------------------------------------ settings
    private void loadSettings() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        strokeAlpha = p.getInt("alpha", 92) * 255 / 100;
        strokeDp = p.getInt("stroke", 3);
        fillOn = p.getBoolean("fill", true);
        frameOn = p.getBoolean("frame", true);
        frameDp = p.getInt("frameWidth", 9);
        predictOn = p.getBoolean("predict", true);
        rotate180 = p.getBoolean("rotate180", false);
        mirror = p.getBoolean("mirror", false);
        gestureMode = "gestures".equals(p.getString("mode", "outlines"));
        gestures.backGesture = p.getBoolean("backMiddle", true);
        gestures.longPressMs = p.getInt("longPressMs", 1000);
        float sens = p.getInt("sensitivity", 100) / 100f; // bigger = smaller hand box
        float half = 0.32f / sens;
        gestures.boxLeft = 0.5f - half; gestures.boxRight = 0.5f + half;
        gestures.boxTop = 0.48f - half; gestures.boxBottom = 0.48f + half;
    }

    private void applySettings() {
        main.post(() -> {
            if (overlay == null) return;
            float d = getResources().getDisplayMetrics().density;
            overlay.configure(strokeAlpha, strokeDp * d, fillOn, frameOn && !gestureMode, frameDp * d, predictOn);
            if (gestureMode) overlay.show(new FingerOverlayView.Frame(new ArrayList<>(), 0, 0, 0));
            else overlay.setCursor(null);
        });
        gestures.reset();
        processor.setFrameMode(frameOn);
        processor.resetScreen();
    }

    // ------------------------------------------------------------------ overlay
    void onAccessibilityChanged() {
        main.post(() -> {
            if (!running) return;
            boolean a11y = FingerAccessibilityService.instance != null;
            if (overlay == null || a11y != overlayIsA11y) showOverlay();
        });
    }

    private void showOverlay() {
        hideOverlay();
        FingerAccessibilityService a11y = FingerAccessibilityService.instance;
        Context ctx;
        int type;
        if (a11y != null) {
            ctx = a11y; type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        } else if (Settings.canDrawOverlays(this)) {
            ctx = this; type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            status("Aucune surcouche possible : active le service d'accessibilité (recommandé) ou l'affichage par-dessus les applis.");
            return;
        }
        overlayWm = (WindowManager) ctx.getSystemService(WINDOW_SERVICE);
        overlay = new FingerOverlayView(ctx);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 30)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        else if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            // Android 12+: touches only pass through other apps' overlays whose
            // opacity is below the system limit (0.8 by default).
            float max = 0.8f;
            if (Build.VERSION.SDK_INT >= 31) {
                InputManager im = getSystemService(InputManager.class);
                if (im != null) max = Math.min(max, im.getMaximumObscuringOpacityForTouch());
            }
            lp.alpha = max;
        }
        lp.setTitle("Beast finger outlines");
        overlayIsA11y = a11y != null;
        try {
            overlayWm.addView(overlay, lp);
        } catch (RuntimeException e) {
            status("Surcouche refusée : " + e.getMessage());
            overlay = null; overlayWm = null;
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        overlay.configure(strokeAlpha, strokeDp * d, fillOn, frameOn && !gestureMode, frameDp * d, predictOn);
        overlay.setScreenSize(screenW, screenH);
        status(overlayIsA11y
            ? "Surcouche au-dessus du clavier (service d'accessibilité)."
            : "Surcouche standard : elle passe SOUS le clavier. Active le service d'accessibilité pour voir les doigts sur les touches.");
    }

    private void hideOverlay() {
        if (overlay != null && overlayWm != null) {
            try { overlayWm.removeViewImmediate(overlay); } catch (RuntimeException ignored) { }
        }
        overlay = null; overlayWm = null;
    }

    private void updateScreenGeometry() {
        DisplayManager dm = getSystemService(DisplayManager.class);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) return;
        Point p = new Point();
        d.getRealSize(p);
        int rot = d.getRotation();
        if (p.x != screenW || p.y != screenH || rot != rotation) {
            boolean first = rotation < 0;
            screenW = p.x; screenH = p.y; rotation = rot;
            if (!first) processor.resetScreen();
            main.post(() -> { if (overlay != null) overlay.setScreenSize(screenW, screenH); });
        }
    }

    // ------------------------------------------------------------------ camera
    private void openCamera(boolean preferViture) {
        UsbManager usb = getSystemService(UsbManager.class);
        UsbDevice dev = findCamera(usb);
        if (dev == null) { status("Caméra des Beast introuvable (USB)."); main.post(this::stopSelf); return; }
        if (!usb.hasPermission(dev)) { status("Permission USB manquante : relance depuis l'application."); main.post(this::stopSelf); return; }
        if (!NativeCameraBridge.available()) { status("Bibliothèque native : " + NativeCameraBridge.loadError()); main.post(this::stopSelf); return; }
        connection = usb.openDevice(dev);
        if (connection == null) { status("Ouverture USB impossible."); main.post(this::stopSelf); return; }
        // Detach any kernel driver from the video interfaces (claimed by this fd).
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface itf = dev.getInterface(i);
            if (itf.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO && connection.claimInterface(itf, true)) claimed.add(itf);
        }
        int rc = NativeCameraBridge.start(connection.getFileDescriptor(), cameraListener, preferViture);
        status(String.format(Locale.US, "Caméra %04X:%04X : %s (code %d)", dev.getVendorId(), dev.getProductId(),
            NativeCameraBridge.describe(), rc));
        if (rc != 0) { closeCamera(); status("Démarrage caméra impossible."); main.post(this::stopSelf); }
    }

    private void closeCamera() {
        NativeCameraBridge.stop();
        if (connection != null) {
            for (UsbInterface i : claimed) { try { connection.releaseInterface(i); } catch (RuntimeException ignored) { } }
            claimed.clear();
            connection.close();
            connection = null;
        }
    }

    /** No frame within a few seconds: try the other backend once (UVC <-> VITURE SDK). */
    private final Runnable watchdog = () -> {
        if (!running) return;
        if (NativeCameraBridge.frameCount() == 0 && !triedOtherBackend && NativeCameraBridge.vitureSdkPresent()) {
            triedOtherBackend = true;
            status("Aucune image en UVC : essai avec le SDK VITURE…");
            new Thread(() -> { closeCamera(); openCamera(true); }, "usb-retry").start();
        } else if (NativeCameraBridge.frameCount() == 0) {
            status("Aucune image reçue de la caméra. Débranche/rebranche les lunettes puis relance.");
        }
    };

    // ------------------------------------------------------------------ vision loop
    private void processLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        try {
            hands = new HandLandmarkTracker(this, gestureMode ? 1 : 2);
        } catch (Throwable t) {
            handsError = t.toString();
            status("MediaPipe indisponible (" + handsError + ") : suivi par couleur/fond seulement.");
        }
        Bitmap decoded = null;
        int[] argb = null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inMutable = true;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        long fpsStart = SystemClock.elapsedRealtime();
        int fpsCount = 0;
        float fps = 0, avgMs = 0;
        int geometryCounter = 0;
        while (running) {
            byte[] jpeg; int[] rawArgb; int rw, rh; long t;
            synchronized (frameLock) {
                while (running && pendingJpeg == null && pendingArgb == null) {
                    try { frameLock.wait(500); } catch (InterruptedException e) { return; }
                }
                if (!running) break;
                jpeg = pendingJpeg; rawArgb = pendingArgb; rw = pendingW; rh = pendingH; t = pendingTimeNs;
                pendingJpeg = null; pendingArgb = null;
            }
            long t0 = SystemClock.elapsedRealtime();
            try {
                int w, h;
                if (jpeg != null) {
                    opts.inBitmap = decoded;
                    Bitmap b;
                    try {
                        b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
                    } catch (IllegalArgumentException reuseFailed) {
                        opts.inBitmap = null;
                        b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
                    }
                    if (b == null) continue;
                    decoded = b;
                    w = b.getWidth(); h = b.getHeight();
                    if (argb == null || argb.length != w * h) argb = new int[w * h];
                    b.getPixels(argb, 0, w, 0, 0, w, h);
                } else {
                    w = rw; h = rh;
                    argb = rawArgb;
                    if (decoded == null || decoded.getWidth() != w || decoded.getHeight() != h)
                        decoded = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    if (!rotate180 && !mirror) decoded.setPixels(argb, 0, w, 0, 0, w, h);
                }
                if (rotate180 || mirror) {
                    transform(argb, w, h, rotate180, mirror);
                    decoded.setPixels(argb, 0, w, 0, 0, w, h);
                }
                if (++geometryCounter % 15 == 1) updateScreenGeometry();
                if (hands != null && hands.numHands != (gestureMode ? 1 : 2)) {
                    // gesture mode tracks one hand only: about twice as fast
                    hands.close();
                    try { hands = new HandLandmarkTracker(this, gestureMode ? 1 : 2); } catch (Throwable e) { hands = null; }
                }
                if (hands != null) hands.submit(decoded, w, h, t);
                List<float[]> lm = hands != null ? hands.latest() : new ArrayList<>();
                long lmTime = hands != null ? hands.latestTimeNs() : 0;
                // landmarks older than ~0.4 s are not a useful prior any more
                if (t - lmTime > 400_000_000L) lm = new ArrayList<>();
                FrameProcessor.Result r;
                FingerOverlayView ov = overlay;
                if (gestureMode) {
                    r = new FrameProcessor.Result();
                    r.timeNs = t;
                    if (lmTime != lastGestureLm) {
                        lastGestureLm = lmTime;
                        GestureController.Output g = gestures.update(lm, w, h, screenW, screenH, lmTime / 1_000_000L);
                        handleGesture(g);
                        gestureDiag = "mode gestes : " + (!g.visible ? "aucune main" : g.dragging ? "glisser" : g.pinching ? "pincement" : "main suivie")
                            + (g.event != GestureController.Event.NONE ? " → " + g.event : "");
                    }
                    r.diag = gestureDiag;
                } else {
                    r = processor.process(argb, w, h, lm, lmTime, screenW, screenH, t);
                    if (ov != null) { ov.setBlink(frameOn && r.searching); ov.show(new FingerOverlayView.Frame(r.contours, t, r.vx, r.vy)); }
                }
                lastFrameMs = SystemClock.elapsedRealtime();
                if (previewWanted) makePreview(decoded, r);
                if (recordRequest || rec != null) record(jpeg, decoded, t, lm, r);

                long dt = SystemClock.elapsedRealtime() - t0;
                avgMs = avgMs == 0 ? dt : avgMs * 0.9f + dt * 0.1f;
                fpsCount++;
                long now = SystemClock.elapsedRealtime();
                if (now - fpsStart >= 1000) {
                    fps = fpsCount * 1000f / (now - fpsStart);
                    fpsCount = 0; fpsStart = now;
                    stats = String.format(Locale.FRANCE, "%.0f img/s | %.0f ms/img | caméra %dx%d | mains %d (%s ms) | %s",
                        fps, avgMs, w, h, lm.size(), hands == null ? "—" : String.valueOf(hands.lastRunMs()), r.diag);
                }
            } catch (Throwable e) {
                status("Erreur traitement : " + e);
            }
        }
    }

    private void handleGesture(GestureController.Output g) {
        main.post(() -> {
            FingerOverlayView ov = overlay;
            if (ov != null) ov.setCursor(g);
            FingerAccessibilityService a = FingerAccessibilityService.instance;
            if (a == null) return;
            switch (g.event) {
                case TAP: a.tap(g.x, g.y, 40); break;
                case LONG_PRESS: a.tap(g.x, g.y, 700); break;
                case DRAG_START: a.dragStart(g.x, g.y); break;
                case DRAG_MOVE: a.dragMove(g.x, g.y); break;
                case DRAG_END: a.dragEnd(g.x, g.y); break;
                case BACK: a.back(); break;
                default: break;
            }
        });
    }

    private static void transform(int[] a, int w, int h, boolean rot180, boolean mirror) {
        if (rot180) {
            for (int i = 0, j = a.length - 1; i < j; i++, j--) { int t = a[i]; a[i] = a[j]; a[j] = t; }
        }
        if (mirror) {
            for (int y = 0; y < h; y++) {
                for (int i = y * w, j = y * w + w - 1; i < j; i++, j--) { int t = a[i]; a[i] = a[j]; a[j] = t; }
            }
        }
    }

    private long lastPreviewMs;
    private void makePreview(Bitmap frame, FrameProcessor.Result r) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastPreviewMs < 150) return;
        lastPreviewMs = now;
        int pw = 640, ph = Math.max(1, frame.getHeight() * pw / frame.getWidth());
        Bitmap b = Bitmap.createScaledBitmap(frame, pw, ph, true);
        if (!b.isMutable()) { Bitmap m = b.copy(Bitmap.Config.ARGB_8888, true); b.recycle(); b = m; }
        Canvas c = new Canvas(b);
        float s = pw / (float) frame.getWidth();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f);
        if (r.quad != null) {
            p.setColor(r.screenHeld ? 0xffffc107 : 0xff00e676);
            float[] q = r.quad;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                c.drawLine(q[2 * i] * s, q[2 * i + 1] * s, q[2 * j] * s, q[2 * j + 1] * s, p);
            }
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(q[0] * s, q[1] * s, 5, p); // top-left corner marker
            p.setStyle(Paint.Style.STROKE);
            // outlines mapped back into the camera image
            float[] scr = {0, 0, screenW, 0, screenW, screenH, 0, screenH};
            Homography s2c = Homography.fromQuads(scr, q);
            if (s2c != null) {
                p.setColor(0xffff40ff);
                float[] o = new float[2];
                for (float[] cont : r.contours) {
                    android.graphics.Path path = new android.graphics.Path();
                    for (int i = 0; i < cont.length; i += 2) {
                        s2c.map(cont[i], cont[i + 1], o, 0);
                        if (i == 0) path.moveTo(o[0] * s, o[1] * s); else path.lineTo(o[0] * s, o[1] * s);
                    }
                    path.close();
                    c.drawPath(path, p);
                }
            }
        }
        if (hands != null) {
            p.setColor(0xff40c4ff);
            p.setStyle(Paint.Style.FILL);
            for (float[] lm : hands.latest())
                for (int i = 0; i < 21; i++) c.drawCircle(lm[2 * i] * s, lm[2 * i + 1] * s, 2.5f, p);
        }
        preview.set(b); // never recycled here: the activity may still be drawing the previous one
    }

    // ------------------------------------------------------------------ diagnostic recording
    private void record(byte[] jpeg, Bitmap frame, long t, List<float[]> lm, FrameProcessor.Result r) {
        try {
            if (rec == null) {
                recordRequest = false;
                recFile = new java.io.File(getCacheDir(), "beast-diag.zip");
                rec = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(recFile));
                recMeta = new StringBuilder();
                recMeta.append("version ").append(MainActivity.VERSION).append(" model ").append(Build.MODEL)
                    .append(" screen ").append(screenW).append('x').append(screenH).append(" rot ").append(rotation)
                    .append(" frame ").append(frameOn).append(' ').append(frameDp).append("dp density ")
                    .append(getResources().getDisplayMetrics().density).append('\n');
                recCount = 0;
                status("Enregistrement du diagnostic (60 images)…");
            }
            String name = String.format(Locale.US, "f%03d.jpg", recCount);
            rec.putNextEntry(new java.util.zip.ZipEntry(name));
            if (jpeg != null) rec.write(jpeg);
            else frame.compress(Bitmap.CompressFormat.JPEG, 92, rec);
            rec.closeEntry();
            recMeta.append(name).append(" t=").append(t).append(" hands=").append(lm.size());
            for (float[] h : lm) {
                recMeta.append(" [");
                for (int i = 0; i < h.length; i++) recMeta.append(i == 0 ? "" : ",").append(String.format(Locale.US, i < 42 ? "%.1f" : "%.4f", h[i]));
                recMeta.append(']');
            }
            if (r.quad != null) {
                recMeta.append(" quad=");
                for (int i = 0; i < 8; i++) recMeta.append(i == 0 ? "" : ",").append(String.format(Locale.US, "%.1f", r.quad[i]));
            }
            recMeta.append(" | ").append(r.diag).append('\n');
            if (++recCount >= 60) finishRecording();
        } catch (Exception e) {
            status("Diagnostic : " + e);
            try { if (rec != null) rec.close(); } catch (Exception ignored) { }
            rec = null;
        }
    }

    private void finishRecording() throws Exception {
        rec.putNextEntry(new java.util.zip.ZipEntry("meta.txt"));
        rec.write(recMeta.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        rec.closeEntry();
        rec.close();
        rec = null;
        String name = "beast-diag-" + new java.text.SimpleDateFormat("HHmmss", Locale.US).format(new java.util.Date()) + ".zip";
        android.content.ContentValues cv = new android.content.ContentValues();
        cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/BeastFingers");
        android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new java.io.IOException("MediaStore refuse le fichier");
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri);
             java.io.InputStream in = new java.io.FileInputStream(recFile)) {
            byte[] buf = new byte[65536]; int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
        }
        recFile.delete();
        status("Diagnostic enregistré : Téléchargements/BeastFingers/" + name + " — envoie-le à Claude.");
    }

    private void status(String msg) {
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra(EXTRA_MESSAGE, msg));
    }
}
