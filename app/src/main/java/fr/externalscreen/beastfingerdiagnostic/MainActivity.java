package fr.externalscreen.beastfingerdiagnostic;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int VID = 0x0C45;
    private static final int PID = 0x6368;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService usbWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private final StringBuilder history = new StringBuilder();
    private final String permissionAction = "fr.externalscreen.beastfingerdiagnostic.USB_PERMISSION";
    private UsbManager usb;
    private UsbDeviceConnection connection;
    private ImageView preview;
    private TextView diagnostics;
    private Button startButton;
    private volatile boolean startingOrRunning;
    private volatile boolean stopped = true;
    private int frames;
    private boolean registered;

    private static boolean isBeastCamera(UsbDevice d) {
        return d != null && d.getVendorId() == VID && d.getProductId() == PID;
    }
    private UsbDevice findCamera() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (isBeastCamera(d)) return d;
        }
        return null;
    }

    private final NativeCameraBridge.FrameListener frameListener = jpeg -> {
        if (stopped) return;
        final int count = ++frames;
        if (!decoding.compareAndSet(false, true)) return;
        decoder.execute(() -> {
            try {
                Bitmap img = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (img == null) {
                    if (count <= 3) report("Erreur : image MJPEG non décodable (" + jpeg.length + " octets).");
                    return;
                }
                final int width = img.getWidth();
                final int height = img.getHeight();
                main.post(() -> {
                    if (isFinishing() || isDestroyed() || stopped) {
                        img.recycle(); return;
                    }
                    preview.setImageBitmap(img);
                    if (count <= 3 || count % 90 == 0)
                        report("Flux vidéo : image " + count + " / " + width + "×" + height);
                });
            } catch (Exception e) {
                report("Erreur décodage : " + e);
            } finally {
                decoding.set(false);
            }
        });
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (permissionAction.equals(action)) {
                UsbDevice dev = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                    : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (isBeastCamera(dev) && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    report("Autorisation USB accordée.");
                    openCamera(dev);
                } else {
                    startingOrRunning = false;
                    startButton.setEnabled(true);
                    report("Autorisation USB refusée.");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice dev = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                    : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (isBeastCamera(dev)) {
                    report("Caméra Beast débranchée.");
                    stopCamera();
                }
            }
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        usb = (UsbManager)getSystemService(USB_SERVICE);
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(0xff0d1523);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(16), dp(12), dp(10));
        TextView title = new TextView(this);
        title.setText("VITURE BEAST • CAMERA USB 0.5");
        title.setTextSize(19);
        title.setTextColor(-1);
        root.addView(title);
        TextView detail = new TextView(this);
        detail.setText("Affichage brut de la caméra 1920×1080 MJPEG. Test préalable à l'affichage transparent des doigts.");
        detail.setTextColor(0xffb5d5ed);
        root.addView(detail);
        LinearLayout row = new LinearLayout(this);
        root.addView(row);
        Button scan = new Button(this);
        scan.setText("DÉTECTER");
        row.addView(scan, new LinearLayout.LayoutParams(0, dp(55), 1f));
        scan.setOnClickListener(v -> scan());
        startButton = new Button(this);
        startButton.setText("DÉMARRER");
        row.addView(startButton, new LinearLayout.LayoutParams(0, dp(55), 1f));
        startButton.setOnClickListener(v -> requestStart());
        Button stop = new Button(this);
        stop.setText("ARRÊTER");
        row.addView(stop, new LinearLayout.LayoutParams(0, dp(55), 1f));
        stop.setOnClickListener(v -> stopCamera());
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xff05080f);
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 6f));
        ScrollView scroll = new ScrollView(this);
        diagnostics = new TextView(this);
        diagnostics.setTextColor(0xff9af7ad);
        diagnostics.setTextSize(12);
        scroll.addView(diagnostics);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 4f));
        Button copy = new Button(this);
        copy.setText("COPIER LE DIAGNOSTIC");
        root.addView(copy);
        copy.setOnClickListener(v -> {
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Beast FingerCam USB log", history.toString()));
            report("Diagnostic copié dans le presse-papiers.");
        });
        setContentView(root);
        IntentFilter filter = new IntentFilter(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, filter);
        registered = true;
        report("Android " + Build.VERSION.RELEASE + " / " + Build.MODEL);
        report("SDK Viture : " + (NativeCameraBridge.available() ? "bibliothèque chargée" : NativeCameraBridge.loadError()));
        report("Aucune capture conservée ni envoyée sur Internet.");
        scan();
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }
    private void report(String line) {
        main.post(() -> {
            history.append(line).append('\n');
            if (history.length() > 15000) history.delete(0, 5000);
            if (diagnostics != null) diagnostics.setText(history.toString());
        });
    }
    private void scan() {
        report("— PÉRIPHÉRIQUES USB —");
        for (UsbDevice dev : usb.getDeviceList().values()) {
            report(String.format(Locale.US, "USB %04X:%04X / autorisation=%s",
                dev.getVendorId(), dev.getProductId(), usb.hasPermission(dev)));
        }
        report(findCamera() == null ? "Caméra Beast non détectée." : "Caméra Beast 0C45:6368 détectée !");
        DisplayManager dm = (DisplayManager)getSystemService(DISPLAY_SERVICE);
        for (Display disp : dm.getDisplays())
            report("Écran #" + disp.getDisplayId() + " : " + disp.getName());
    }
    private void requestStart() {
        if (startingOrRunning) { report("Démarrage déjà en cours."); return; }
        if (!NativeCameraBridge.available()) {
            report("Impossible : SDK Viture introuvable dans l'APK."); return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 30);
            return;
        }
        UsbDevice dev = findCamera();
        if (dev == null) { report("Caméra non détectée."); scan(); return; }
        startingOrRunning = true;
        startButton.setEnabled(false);
        if (!usb.hasPermission(dev)) {
            Intent i = new Intent(permissionAction).setPackage(getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(this, 0, i,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            usb.requestPermission(dev, pending);
            report("Accepte l'autorisation USB pour la caméra.");
        } else {
            openCamera(dev);
        }
    }
    private void openCamera(UsbDevice dev) {
        if (connection != null) { report("Connexion déjà ouverte."); return; }
        connection = usb.openDevice(dev);
        if (connection == null) {
            report("Échec : impossible d'ouvrir le périphérique USB.");
            startingOrRunning = false; startButton.setEnabled(true); return;
        }
        int descriptor = connection.getFileDescriptor();
        report("USB ouvert (fd=" + descriptor + "). Initialisation caméra…");
        stopped = false;
        frames = 0;
        usbWorker.execute(() -> {
            int code = NativeCameraBridge.start(descriptor, frameListener);
            main.post(() -> {
                if (code == 0) {
                    report("Caméra démarrée ! Place tes doigts sur ton clavier.");
                } else {
                    report("Erreur caméra Viture : " + code);
                    stopped = true;
                    startingOrRunning = false;
                    if (connection != null) {
                        connection.close(); connection = null;
                    }
                    startButton.setEnabled(true);
                }
            });
        });
    }
    private void stopCamera() {
        stopped = true;
        startButton.setEnabled(false);
        usbWorker.execute(() -> {
            NativeCameraBridge.stop();
            main.post(() -> {
                if (connection != null) {
                    connection.close();
                    connection = null;
                }
                startingOrRunning = false;
                if (!isDestroyed()) startButton.setEnabled(true);
                report("Caméra arrêtée.");
            });
        });
    }
    @Override public void onRequestPermissionsResult(int code, String[] names, int[] grants) {
        super.onRequestPermissionsResult(code, names, grants);
        if (code == 30) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
                requestStart();
            else report("Autorisation Android CAMERA refusée.");
        }
    }
    @Override protected void onDestroy() {
        if (registered) unregisterReceiver(receiver);
        stopped = true;
        usbWorker.execute(() -> {
            NativeCameraBridge.stop();
            if (connection != null) {
                connection.close();
                connection = null;
            }
        });
        usbWorker.shutdown();
        decoder.shutdown();
        super.onDestroy();
    }
}
