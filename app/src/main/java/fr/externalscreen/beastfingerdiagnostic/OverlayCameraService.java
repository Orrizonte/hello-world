package fr.externalscreen.beastfingerdiagnostic;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.hardware.usb.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service for continuous USB camera streaming while other Android apps
 * (including the native IME) are in use. Non-touchable overlay preserves input.
 * All pixel data remains on device, with no storage or network permissions.
 */
public final class OverlayCameraService extends Service {
    public static final String ACTION_START = "fr.externalscreen.beastfingerdiagnostic.START";
    public static final String ACTION_STOP = "fr.externalscreen.beastfingerdiagnostic.STOP";
    public static final String ACTION_CONFIG = "fr.externalscreen.beastfingerdiagnostic.CONFIG";
    public static final String ACTION_SNAPSHOT = "fr.externalscreen.beastfingerdiagnostic.SNAPSHOT";
    public static final String ACTION_CALIBRATION_FRAME = "fr.externalscreen.beastfingerdiagnostic.CALIBRATION_FRAME";
    public static final String ACTION_STATUS = "fr.externalscreen.beastfingerdiagnostic.STATUS";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_ALPHA = "alpha";
    public static final String EXTRA_SCALE = "scale";
    public static final String EXTRA_X = "x";
    public static final String EXTRA_Y = "y";
    public static final String EXTRA_FLIP = "flip";
    public static final String EXTRA_TARGET = "target";
    private static final int NOTIFICATION_ID = 701;
    private static final String CHANNEL_ID = "beast_fingercam_active";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService usbWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService decodeWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean decodeBusy = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong lastAnalysisMs = new java.util.concurrent.atomic.AtomicLong(0);
    private volatile boolean running;
    private boolean starting;
    private UsbDeviceConnection connection;
    private WindowManager windowManager;
    private FingertipOverlayView tipsView;
    private WindowManager.LayoutParams overlayParams;
    private FingertipDetector detector;
    private volatile boolean snapshotRequested;
    private String detectorError;
    private int lastTipCount = -1;
    private int noTipFrames = 0;
    private int currentDisplayId = -1;
    private int alpha = 65;
    private int scale = 110;
    private int offsetX = 0;
    private int offsetY = 0;
    private int targetDisplay = 0; // safe mirrored-phone mode by default
    private boolean flip;
    private int frames;

    private final NativeCameraBridge.FrameListener listener = bytes -> {
        if (!running) return;
        // ~12 FPS hand tracking; camera itself stays at the native 1920x1080.
        long now = SystemClock.elapsedRealtime();
        if (!snapshotRequested && now - lastAnalysisMs.get() < 82) return;
        if (!decodeBusy.compareAndSet(false, true)) return;
        lastAnalysisMs.set(now);
        int count = ++frames;
        decodeWorker.execute(() -> {
            Bitmap bitmap = null;
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1; // preserve full 1920x1080 camera detail
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap == null) {
                    if (count <= 3) status("Image MJPEG non décodable");
                    return;
                }
                if(snapshotRequested){
                    snapshotRequested=false;
                    try{
                        CalibrationFrameHolder.publish(bitmap);
                        sendBroadcast(new Intent(ACTION_CALIBRATION_FRAME)
                            .setPackage(getPackageName()));
                        status("APERCU CAMERA : "+bitmap.getWidth()+"×"+
                            bitmap.getHeight()+" pixels, sans enregistrement.");
                    }catch(RuntimeException e){
                        status("Aperçu photo : "+e.getMessage());
                    }
                }
                if (detector == null && detectorError == null) {
                    try {
                        detector = new FingertipDetector(getApplicationContext());
                        status("Détecteurs prêts : téléphone + doigts. Mode sans paume activé.");
                    } catch (Throwable e) {
                        StringBuilder trace=new StringBuilder(e.toString());
                        Throwable cause=e.getCause();
                        for(int i=0;cause!=null && i<3;i++,cause=cause.getCause())
                            trace.append(" → ").append(cause.toString());
                        detectorError=trace.toString();
                        status("Erreur initialisation MediaPipe : " + detectorError);
                    }
                }
                java.util.List<FingertipDetector.Patch> patches =
                    detector == null ? java.util.Collections.emptyList() : detector.process(bitmap);
                int tips=patches.size();
                final int sourceWidth=bitmap.getWidth(),sourceHeight=bitmap.getHeight();
                final int hands=detector==null ? -1 : detector.lastHands();
                final int candidates=detector==null ? 0 : detector.lastCandidates();
                final int inside=detector==null ? 0 : detector.lastInside();
                final boolean phoneSeen=detector!=null && detector.phoneVisible();
                final int objects=detector==null ? 0 : detector.objectCandidates();
                final float phoneScore=detector==null ? 0f : detector.phoneConfidence();
                final String phoneLabel=detector==null ? "aucun" : detector.phoneLabel();
                final int skin=detector==null ? 0 : detector.skinPixels();
                final int moving=detector==null ? 0 : detector.movingPixels();
                final int regions=detector==null ? 0 : detector.fallbackRegions();
                final boolean reference=detector!=null && detector.backgroundReady();
                main.post(() -> {
                    if (!running) {
                        for (FingertipDetector.Patch p:patches) p.release();
                        return;
                    }
                    if (tips > 0) {
                        noTipFrames=0;
                        if (tipsView == null) showOverlay();
                        if (tipsView != null && windowManager!=null && overlayParams!=null) {
                            android.graphics.Rect bounds=tipsView.bounds(patches);
                            if (bounds!=null) {
                                try {
                                    overlayParams.x=bounds.left;
                                    overlayParams.y=bounds.top;
                                    overlayParams.width=bounds.width();
                                    overlayParams.height=bounds.height();
                                    tipsView.setWindowOrigin(bounds.left,bounds.top);
                                    windowManager.updateViewLayout(tipsView,overlayParams);
                                    tipsView.showTips(patches);
                                } catch (RuntimeException windowError) {
                                    status("Fenêtre doigts : "+windowError);
                                    for(FingertipDetector.Patch p:patches)p.release();
                                    hideOverlay();
                                }
                            } else {
                                for(FingertipDetector.Patch p:patches)p.release();
                            }
                        } else {
                            for(FingertipDetector.Patch p:patches)p.release();
                        }
                    } else {
                        if (tipsView != null) {
                            tipsView.clear();
                            if (++noTipFrames>=12) hideOverlay();
                        }
                    }
                    if (tips != lastTipCount) {
                        if(tips>0 && lastTipCount<=0)
                            status("Doigts reconnus au-dessus de l'écran : "+tips);
                        lastTipCount=tips;
                    }
                    if (count<=3 || count%60==0 || (hands>0 && count%15==0)) {
                        status("Reconnaissance image "+count+" : camera "+sourceWidth+"×"+
                            sourceHeight+" | mains="+hands+" | pointes="+candidates+
                            " | téléphone="+(phoneSeen?"OUI":"NON")+
                            " | objet="+phoneLabel+
                            " | confiance="+(int)(phoneScore*100)+"%"+
                            " | objets="+objects+
                            " | doigt peau="+skin+" pixels"+
                            " | peau mobile="+moving+" pixels"+
                            " | zones doigts="+regions+
                            " | fond prêt="+reference+
                            " | points ML="+inside+
                            " | visibles="+tips+
                            " | surface "+(currentDisplayId>=0?"#"+currentDisplayId:"inactive"));
                    }
                });
            } catch (Throwable ex) {
                if (count <= 3 || count % 120 == 0)
                    status("Traitement doigts : " + ex.getClass().getSimpleName() +
                        ": " + ex.getMessage());
            } finally {
                if (bitmap != null) bitmap.recycle();
                decodeBusy.set(false);
            }
        });
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) return;
            UsbDevice dev = android.os.Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (dev != null && dev.getVendorId() == 0x0C45 && dev.getProductId() == 0x6368) {
                status("Caméra Beast débranchée : arrêt.");
                stopSelf();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
            .createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                "Beast FingerCam en cours", NotificationManager.IMPORTANCE_LOW));
        IntentFilter usbFilter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(receiver, usbFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, usbFilter);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_SNAPSHOT.equals(action)) {
            snapshotRequested=true;
            status("Capture photo diagnostic demandée (caméra Beast uniquement).");
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        updateValues(intent);
        if (ACTION_START.equals(action)) {
            if (starting || running) {
                updateOverlay(false);
                status("Caméra déjà active.");
                return START_NOT_STICKY;
            }
            if (!Settings.canDrawOverlays(this)) {
                status("Autorisation de superposition manquante.");
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            } catch (RuntimeException e) {
                status("Service premier plan impossible : " + e);
                stopSelf();
                return START_NOT_STICKY;
            }
            starting = true;
            getSharedPreferences("settings.v085", MODE_PRIVATE).edit().putBoolean("running", true).apply();
            // Never add any overlay window while there are no detected tips.
            // The previous full-screen external window obscured the Beast view.
            status("Affichage protégé : écran des Beast intact, fenêtre créée uniquement sur les doigts.");
            startUsbCamera();
        } else if (ACTION_CONFIG.equals(action)) {
            if (windowManager != null) updateOverlay(true);
            status("Cible surcouche : "+(targetDisplay==0?
                "téléphone en miroir (plus sûr)":"écran externe Beast")+".");
        }
        return START_NOT_STICKY;
    }

    private void updateValues(Intent intent) {
        alpha = clamp(intent.getIntExtra(EXTRA_ALPHA, alpha), 10, 75);
        scale = clamp(intent.getIntExtra(EXTRA_SCALE, scale), 40, 180);
        // Automatic phone-relative position only; never use legacy X/Y offsets.
        offsetX = 0;
        offsetY = 0;
        flip = intent.getBooleanExtra(EXTRA_FLIP, flip);
        targetDisplay = clamp(intent.getIntExtra(EXTRA_TARGET, targetDisplay), 0, 1);
    }
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openAction = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, OverlayCameraService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Beast FingerCam")
            .setContentText("Caméra en surimpression — toucher le clavier normalement")
            .setOngoing(true)
            .setContentIntent(openAction)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter", stopAction)
            .build();
    }

    private Display chooseDisplay() {
        DisplayManager displays = (DisplayManager)getSystemService(DISPLAY_SERVICE);
        Display phone = displays.getDisplay(Display.DEFAULT_DISPLAY);
        if (targetDisplay == 0) return phone;
        Display alternative = null;
        for (Display disp : displays.getDisplays()) {
            if (disp.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            if (alternative == null) alternative = disp;
            if (disp.getName().toLowerCase(java.util.Locale.ROOT).contains("viture"))
                return disp;
        }
        if (alternative == null) status("Pas d'écran externe, affichage sur téléphone.");
        return alternative != null ? alternative : phone;
    }

    private void showOverlay() {
        hideOverlay();
        try {
            displayOverlayOn(chooseDisplay());
        } catch (RuntimeException problem) {
            status("Overlay externe indisponible (" + problem.getClass().getSimpleName()
                + "). Essai sur le téléphone.");
            try {
                DisplayManager displays = (DisplayManager)getSystemService(DISPLAY_SERVICE);
                displayOverlayOn(displays.getDisplay(Display.DEFAULT_DISPLAY));
            } catch (RuntimeException second) {
                status("Impossible d'afficher l'overlay : " + second);
                stopSelf();
            }
        }
    }

    private void displayOverlayOn(Display display) {
        if (display == null) throw new IllegalStateException("Aucun écran de projection");
        Context displayContext=createDisplayContext(display);
        Context windowContext=Build.VERSION.SDK_INT>=30
            ? displayContext.createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null)
            : displayContext;
        windowManager=(WindowManager)windowContext.getSystemService(WINDOW_SERVICE);
        tipsView=new FingertipOverlayView(windowContext);
        android.graphics.Point projected=new android.graphics.Point();
        display.getRealSize(projected);
        if(projected.x<=100 || projected.y<=100)
            throw new IllegalStateException("Dimensions d'écran invalides");
        currentDisplayId=display.getDisplayId();
        tipsView.setVirtualGeometry(projected.x,projected.y);
        tipsView.setSettings(alpha,scale,offsetX,offsetY,flip);
        if(display.getDisplayId()!=Display.DEFAULT_DISPLAY){
            Display phone=((DisplayManager)getSystemService(DISPLAY_SERVICE))
                .getDisplay(Display.DEFAULT_DISPLAY);
            if(phone!=null){
                android.graphics.Point phoneSize=new android.graphics.Point();
                phone.getRealSize(phoneSize);
                float aspect=phoneSize.y>0 ? phoneSize.x/(float)phoneSize.y : 1f;
                tipsView.setProjectedPhoneAspect(aspect,true);
                status("Écran projeté Viture : "+projected.x+"×"+projected.y+
                    "; contenu téléphone : "+phoneSize.x+"×"+phoneSize.y+
                    " (respect du ratio).");
            }
        }else{
            status("Mode miroir sécurisé : écran source "+projected.x+"×"+
                projected.y+"; projection des Beast conservée intacte.");
        }
        // Begin with ONE transparent physical pixel; size will be limited to
        // the bounding rectangle of the detected fingertips on each frame.
        overlayParams=new WindowManager.LayoutParams(
            1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT);
        overlayParams.gravity=Gravity.TOP|Gravity.LEFT;
        overlayParams.x=0;overlayParams.y=0;
        overlayParams.alpha=0.72f;
        overlayParams.setTitle("Beast FingerCam fingertip patch — small window");
        windowManager.addView(tipsView,overlayParams);
        status("Surcouche DOIGTS UNIQUEMENT sur #"+display.getDisplayId()+
            " : résolution "+projected.x+"×"+projected.y+
            "; fenêtre initiale 1×1, aucune surface noire.");
    }

    private void updateOverlay(boolean canSwitchDisplay) {
        if(windowManager==null || tipsView==null || overlayParams==null)return;
        if(canSwitchDisplay){
            Display desired=chooseDisplay();
            if(desired!=null && desired.getDisplayId()!=currentDisplayId){
                hideOverlay();
                // Recreated only if fingertips still exist in the next frame.
                return;
            }
        }
        tipsView.setSettings(alpha,scale,offsetX,offsetY,flip);
    }

    private void hideOverlay(){
        if(windowManager!=null && tipsView!=null){
            try{windowManager.removeViewImmediate(tipsView);}
            catch(RuntimeException ignored){}
        }
        if(tipsView!=null)tipsView.clear();
        tipsView=null;
        windowManager=null;
        overlayParams=null;
        currentDisplayId=-1;
    }

    private void startUsbCamera() {
        final UsbManager usb = (UsbManager)getSystemService(USB_SERVICE);
        UsbDevice cameraDev = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == 0x0C45 && d.getProductId() == 0x6368) {
                cameraDev = d;
                break;
            }
        }
        if (cameraDev == null) {
            status("Caméra 0C45:6368 introuvable.");
            stopSelf();
            return;
        }
        if (!usb.hasPermission(cameraDev)) {
            status("Autorisation USB absente : reviens dans l'application.");
            stopSelf();
            return;
        }
        if (!NativeCameraBridge.available()) {
            status("SDK Viture : " + NativeCameraBridge.loadError());
            stopSelf();
            return;
        }
        final UsbDevice selected = cameraDev;
        usbWorker.execute(() -> {
            connection = usb.openDevice(selected);
            if (connection == null) {
                status("Impossible d'ouvrir la caméra USB.");
                stopSelf();
                return;
            }
            int fd = connection.getFileDescriptor();
            frames = 0;
            running = true;
            status("Caméra USB ouverte (fd=" + fd + "). Démarrage…");
            int rc = NativeCameraBridge.start(fd, listener);
            starting = false;
            if (rc != 0) {
                running = false;
                status("Erreur du SDK Viture au démarrage : " + rc);
                stopSelf();
            } else {
                status("Caméra Viture active. Ouvre une application avec clavier.");
            }
        });
    }

    private void status(String message) {
        Intent status = new Intent(ACTION_STATUS).setPackage(getPackageName());
        status.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(status);
    }

    @Override public void onDestroy() {
        running = false;
        starting = false;
        getSharedPreferences("settings.v082", MODE_PRIVATE).edit().putBoolean("running", false).apply();
        hideOverlay();
        try { unregisterReceiver(receiver); } catch (RuntimeException ignored) { }
        usbWorker.execute(() -> {
            NativeCameraBridge.stop();
            if (connection != null) {
                connection.close();
                connection = null;
            }
        });
        usbWorker.shutdown();
        decodeWorker.execute(() -> { if (detector != null) { detector.close(); detector=null; } });
        decodeWorker.shutdown();
        super.onDestroy();
    }
    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
