package fr.externalscreen.beastlab;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * "Real distance" prototype: the phone screen is captured (MediaProjection)
 * and drawn by us on the glasses' own display, side by side (one image per
 * eye) with an adjustable horizontal disparity = perceived distance. The
 * glasses keep their native head tracking (3DoF / smooth follow).
 */
public final class DepthService extends Service {
    static final String EXTRA_CODE = "code", EXTRA_DATA = "data";
    static volatile DepthService instance;
    static final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
    static volatile String stats = "";

    // Live settings (read every frame)
    static volatile float disparityPx = 0f;   // per eye, + = farther, - = nearer
    static volatile float scale = 0.8f;       // fraction of the eye viewport height
    static volatile float offsetY = 0f;       // fraction of viewport height
    static volatile boolean sbs = true;       // draw two eye images side by side
    static volatile boolean testPattern = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private WindowManager glassesWm;
    private SurfaceView view;
    private HandlerThread glThread;
    private Handler gl;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture capture;
    private Surface captureSurface;
    private int oesTex, program, aPos, aTex, uMvp, uSt, uPattern;
    private final float[] st = new float[16];
    private int surfW, surfH, capW, capH;
    private volatile boolean frameAvailable;
    private long framesIn, framesOut, statStart;

    static java.io.File eventFile;

    static void event(String s) {
        String line = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date()) + "  [profondeur] " + s;
        events.add(line);
        java.io.File f = eventFile;
        if (f != null) {
            // written immediately: survives a crash of the process
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) { w.write(line + "\n"); } catch (Exception ignored) { }
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        LabActivity.installCrashRecorder(this);
        eventFile = new java.io.File(getFilesDir(), "depth-events.txt");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            return start(intent);
        } catch (Throwable t) {
            event("ERREUR au démarrage : " + t);
            for (StackTraceElement el : t.getStackTrace()) { event("   at " + el); if (el.getClassName().startsWith("android.app.ActivityThread")) break; }
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private int start(Intent intent) {
        instance = this;
        if (intent == null) { event("relancé sans jeton : arrêt"); stopSelf(); return START_NOT_STICKY; }
        event("service démarré (Android " + Build.VERSION.SDK_INT + ")");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("depth", "Profondeur Beast", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "depth")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Beast Lab : écran recopié en 3D")
            .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, LabActivity.class), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(7, n);
        } catch (RuntimeException e) { event("service premier plan refusé : " + e); stopSelf(); return START_NOT_STICKY; }
        int code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED);
        Intent data = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(EXTRA_DATA, Intent.class) : intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (RuntimeException e) { event("capture refusée : " + e); stopSelf(); return START_NOT_STICKY; }
        if (projection == null) { event("capture d'écran non accordée"); stopSelf(); return START_NOT_STICKY; }
        event("MediaProjection obtenue");
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { event("capture arrêtée par Android"); main.post(DepthService.this::stopSelf); }
        }, main);
        boolean shown;
        try { shown = showOnGlasses(); } catch (Throwable e) { event("affichage lunettes : " + e); shown = false; }
        if (!shown) { stopSelf(); return START_NOT_STICKY; }
        return START_NOT_STICKY;
    }

    private Display findGlasses() {
        DisplayManager dm = getSystemService(DisplayManager.class);
        Display best = null;
        for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (best == null) best = d;
            if (d.getName() != null && d.getName().toLowerCase(Locale.ROOT).contains("viture")) best = d;
        }
        return best;
    }

    private boolean showOnGlasses() {
        Display d = findGlasses();
        if (d == null) { event("aucun écran externe « présentation » : les lunettes sont-elles branchées ?"); return false; }
        Display.Mode m = d.getMode();
        event(String.format(Locale.US, "écran des lunettes : #%d « %s » %dx%d @ %.0f Hz", d.getDisplayId(), d.getName(),
            m.getPhysicalWidth(), m.getPhysicalHeight(), m.getRefreshRate()));
        if (!android.provider.Settings.canDrawOverlays(this)) { event("autorisation « superposition » manquante"); return false; }
        Context wc = createDisplayContext(d).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        glassesWm = (WindowManager) wc.getSystemService(WINDOW_SERVICE);
        view = new SurfaceView(wc);
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { event("surface créée"); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
                event("surface " + w + "x" + hh);
                try { startGl(h.getSurface(), w, hh); } catch (Throwable e) { event("startGl : " + e); }
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) { event("surface détruite"); stopGl(); }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 30)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try {
            glassesWm.addView(view, lp);
            event("fenêtre ajoutée sur l'écran des lunettes");
        } catch (RuntimeException e) { event("fenêtre refusée : " + e); return false; }
        return true;
    }

    // ------------------------------------------------------------------ GL
    private void startGl(Surface out, int w, int h) {
        surfW = w; surfH = h;
        if (glThread == null) {
            glThread = new HandlerThread("depth-gl");
            glThread.start();
            gl = new Handler(glThread.getLooper());
        }
        gl.post(() -> {
            try {
                if (eglDisplay == EGL14.EGL_NO_DISPLAY) initEgl();
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, out, new int[]{EGL14.EGL_NONE}, 0);
                if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE)
                    throw new RuntimeException("surface EGL refusée (0x" + Integer.toHexString(EGL14.eglGetError()) + ")");
                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))
                    throw new RuntimeException("eglMakeCurrent (0x" + Integer.toHexString(EGL14.eglGetError()) + ")");
                if (program == 0) {
                    String r = GLES20.glGetString(GLES20.GL_RENDERER); // only valid with a current context
                    event("GPU : " + (r == null ? "?" : r));
                    initScene();
                }
                statStart = SystemClock.elapsedRealtime();
                gl.post(renderLoop);
            } catch (Throwable e) { event("GL : " + e); for (StackTraceElement el : e.getStackTrace()) { event("   at " + el); if (el.getClassName().startsWith("android.os.Looper")) break; } }
        });
    }

    private EGLConfig config;

    private void initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        EGL14.eglInitialize(eglDisplay, v, 0, v, 1);
        int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE};
        EGLConfig[] cfg = new EGLConfig[1]; int[] n = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attrs, 0, cfg, 0, 1, n, 0) || n[0] < 1 || cfg[0] == null)
            throw new RuntimeException("aucune configuration EGL (erreur 0x" + Integer.toHexString(EGL14.eglGetError()) + ")");
        config = cfg[0];
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT)
            throw new RuntimeException("contexte EGL refusé (0x" + Integer.toHexString(EGL14.eglGetError()) + ")");
        event("EGL initialisé " + v[0] + "." + v[1]);
    }

    private static final String VS = "attribute vec2 aPos; attribute vec2 aTex; uniform mat4 uMvp; uniform mat4 uSt; varying vec2 vTex;"
        + "void main(){ gl_Position = uMvp * vec4(aPos,0.0,1.0); vTex = (uSt * vec4(aTex,0.0,1.0)).xy; }";
    private static final String FS = "#extension GL_OES_EGL_image_external : require\n precision mediump float;"
        + "uniform samplerExternalOES uTex; uniform float uPattern; varying vec2 vTex;"
        + "void main(){ vec4 c = texture2D(uTex, vTex);"
        + " if (uPattern > 0.5) { float g = step(0.5, fract(vTex.x*8.0)) + step(0.5, fract(vTex.y*16.0)); c = vec4(vec3(mod(g,2.0)),1.0);"
        + "   if (vTex.x < 0.02 || vTex.x > 0.98 || vTex.y < 0.01 || vTex.y > 0.99) c = vec4(1.0,0.2,0.2,1.0); }"
        + " gl_FragColor = c; }";

    private void initScene() {
        program = link(VS, FS);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        uMvp = GLES20.glGetUniformLocation(program, "uMvp");
        uSt = GLES20.glGetUniformLocation(program, "uSt");
        uPattern = GLES20.glGetUniformLocation(program, "uPattern");
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        oesTex = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        // capture the phone's own screen
        Display phone = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Point p = new Point();
        phone.getRealSize(p);
        capW = p.x; capH = p.y;
        capture = new SurfaceTexture(oesTex);
        capture.setDefaultBufferSize(capW, capH);
        capture.setOnFrameAvailableListener(s -> { frameAvailable = true; framesIn++; }, gl);
        captureSurface = new Surface(capture);
        virtualDisplay = projection.createVirtualDisplay("beast-depth", capW, capH,
            getResources().getDisplayMetrics().densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, captureSurface, null, gl);
        event("capture " + capW + "x" + capH + (virtualDisplay != null ? " démarrée" : " IMPOSSIBLE"));
    }

    private final Runnable renderLoop = new Runnable() {
        @Override public void run() {
            if (eglSurface == EGL14.EGL_NO_SURFACE) return;
            try {
                if (frameAvailable) { frameAvailable = false; capture.updateTexImage(); capture.getTransformMatrix(st); }
                draw();
                EGL14.eglSwapBuffers(eglDisplay, eglSurface);
                framesOut++;
                long now = SystemClock.elapsedRealtime();
                if (now - statStart >= 1000) {
                    float sec = (now - statStart) / 1000f;
                    stats = String.format(Locale.FRANCE, "capture %.0f img/s | rendu %.0f img/s | surface lunettes %dx%d | %s | écart %.0f px/œil | taille %.0f %% | GL err %d",
                        framesIn / sec, framesOut / sec, surfW, surfH, sbs ? "3D côte à côte" : "2D", disparityPx, scale * 100, GLES20.glGetError());
                    framesIn = 0; framesOut = 0; statStart = now;
                }
            } catch (Throwable e) { event("rendu : " + e); return; }
            gl.postDelayed(this, 8); // vsync-throttled by eglSwapBuffers
        }
    };

    private final float[] mvp = new float[16];
    private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private void draw() {
        GLES20.glViewport(0, 0, surfW, surfH);
        GLES20.glClearColor(0, 0, 0, 1); // black = transparent in the glasses
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glUniformMatrix4fv(uSt, 1, false, st, 0);
        GLES20.glUniform1f(uPattern, testPattern ? 1f : 0f);
        int eyes = sbs ? 2 : 1;
        int eyeW = surfW / eyes;
        for (int e = 0; e < eyes; e++) {
            GLES20.glViewport(e * eyeW, 0, eyeW, surfH);
            // quad size in NDC of this eye viewport, keeping the phone aspect
            float hN = 2f * scale;
            float wN = hN * ((float) capW / capH) * ((float) surfH / eyeW);
            // disparity: left eye image moves left for "farther" (+), right eye moves right
            float shiftPx = sbs ? (e == 0 ? -disparityPx : disparityPx) : 0f;
            float cx = 2f * shiftPx / eyeW, cy = 2f * offsetY;
            float x0 = cx - wN / 2, x1 = cx + wN / 2, y0 = cy - hN / 2, y1 = cy + hN / 2;
            quad.clear();
            quad.put(new float[]{x0, y0, 0, 0, x1, y0, 1, 0, x0, y1, 0, 1, x1, y1, 1, 1});
            quad.position(0);
            android.opengl.Matrix.setIdentityM(mvp, 0);
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aPos);
            quad.position(2);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
    }

    private static int link(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs), f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private void stopGl() {
        if (gl == null) return;
        gl.removeCallbacks(renderLoop);
        gl.post(() -> {
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }
        });
    }

    @Override public void onDestroy() {
        instance = null;
        if (view != null && glassesWm != null) { try { glassesWm.removeViewImmediate(view); } catch (RuntimeException ignored) { } }
        stopGl();
        if (virtualDisplay != null) virtualDisplay.release();
        if (projection != null) projection.stop();
        if (glThread != null) {
            gl.post(() -> {
                if (captureSurface != null) captureSurface.release();
                if (capture != null) capture.release();
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                    EGL14.eglTerminate(eglDisplay);
                }
            });
            glThread.quitSafely();
        }
        event("arrêté");
        stats = "";
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
