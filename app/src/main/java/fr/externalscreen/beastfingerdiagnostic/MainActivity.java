package fr.externalscreen.beastfingerdiagnostic;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.display.DisplayManager;
import android.hardware.usb.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final AtomicBoolean decodeBusy = new AtomicBoolean();
    private final StringBuilder diagnostic = new StringBuilder();
    private final String usbAction = "fr.externalscreen.beastfingerdiagnostic.USB";
    private UsbManager usb;
    private UsbDeviceConnection connection;
    private ImageView preview;
    private TextView logText;
    private Button start;
    private volatile boolean active;
    private int frameCount;
    private boolean registered;

    private static boolean isCamera(UsbDevice d) {
        return d.getVendorId() == 0x0C45 && d.getProductId() == 0x6368;
    }
    private UsbDevice findCamera() {
        for (UsbDevice d : usb.getDeviceList().values()) if (isCamera(d)) return d;
        return null;
    }
    private final NativeCameraBridge.FrameListener frameListener = jpeg -> {
        int n = ++frameCount;
        if (!decodeBusy.compareAndSet(false, true)) return;
        decoder.execute(() -> {
            try {
                Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (b == null) {
                    if (n == 1) report("MJPEG reçu mais non décodable.");
                    return;
                }
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) { b.recycle(); return; }
                    preview.setImageBitmap(b);
                    if (n == 1 || n % 50 == 0)
                        report("Images caméra reçues : " + n + " / " + b.getWidth() + "x" + b.getHeight());
                });
            } catch (Exception e) { report("Décodage : " + e); }
            finally { decodeBusy.set(false); }
        });
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (usbAction.equals(intent.getAction())) {
                UsbDevice device = android.os.Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                    : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && isCamera(device) &&
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    open(device);
                else report("Autorisation USB refusée ou périphérique inattendu.");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                report("USB débranché."); stopCamera();
            }
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(dp(14), dp(20), dp(14), dp(12));
        root.setBackgroundColor(0xff111827);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("VITURE BEAST — CAMERA TEST v0.3");
        title.setTextSize(19); title.setTextColor(-1);
        root.addView(title);
        TextView note = new TextView(this);
        note.setText("Ce test affiche la vidéo brute. Le suivi et la transparence des doigts viendront ensuite.");
        note.setTextColor(0xffbbdefb);
        root.addView(note);
        LinearLayout buttons = new LinearLayout(this);
        root.addView(buttons);
        Button scan = new Button(this); scan.setText("DÉTECTER");
        buttons.addView(scan, new LinearLayout.LayoutParams(0, dp(52), 1));
        scan.setOnClickListener(v -> scan());
        start = new Button(this); start.setText("DÉMARRER");
        buttons.addView(start, new LinearLayout.LayoutParams(0, dp(52), 1));
        start.setOnClickListener(v -> requestStart());
        Button stop = new Button(this); stop.setText("ARRÊTER");
        buttons.addView(stop, new LinearLayout.LayoutParams(0, dp(52), 1));
        stop.setOnClickListener(v -> stopCamera());
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xff05080f);
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 5));
        ScrollView scroll = new ScrollView(this);
        logText = new TextView(this);
        logText.setTextSize(12); logText.setTextColor(0xff92ffbd);
        scroll.addView(logText);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 3));
        Button copy = new Button(this);
        copy.setText("COPIER LE DIAGNOSTIC");
        root.addView(copy);
        copy.setOnClickListener(v -> {
            ClipboardManager c = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            c.setPrimaryClip(ClipData.newPlainText("Beast diagnostic", diagnostic.toString()));
            report("Diagnostic copié.");
        });
        setContentView(root);
        IntentFilter filter = new IntentFilter(usbAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        registered = true;
        report("Android " + Build.VERSION.RELEASE + " / " + Build.MODEL);
        report("SDK natif : " + (NativeCameraBridge.available() ? "chargé" : NativeCameraBridge.loadError()));
        report("Aucun enregistrement ni transfert réseau.");
        scan();
    }
    private int dp(int a) { return (int)(a * getResources().getDisplayMetrics().density + 0.5f); }
    private void report(String message) {
        main.post(() -> {
            diagnostic.append(message).append('\n');
            if (diagnostic.length() > 12000) diagnostic.delete(0, 3000);
            if (logText != null) logText.setText(diagnostic.toString());
        });
    }
    private void scan() {
        report("-- PÉRIPHÉRIQUES USB --");
        for (UsbDevice d : usb.getDeviceList().values())
            report(String.format(Locale.US, "VID:%04X PID:%04X permission:%s",
                d.getVendorId(), d.getProductId(), usb.hasPermission(d)));
        report(findCamera() == null ? "Caméra Beast 0C45:6368 non détectée." : "Caméra Beast détectée !");
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        for (Display d : dm.getDisplays())
            report("Écran #" + d.getDisplayId() + " : " + d.getName());
    }
    private void requestStart() {
        if (active) { report("Déjà en cours."); return; }
        if (!NativeCameraBridge.available()) { report("SDK natif absent."); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 30);
            return;
        }
        UsbDevice d = findCamera();
        if (d == null) { scan(); return; }
        if (!usb.hasPermission(d)) {
            Intent i = new Intent(usbAction).setPackage(getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, i,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            usb.requestPermission(d, pi);
            report("Accepte l'autorisation USB de la caméra.");
        } else open(d);
    }
    private void open(UsbDevice d) {
        if (active) return;
        connection = usb.openDevice(d);
        if (connection == null) { report("Impossible d'ouvrir l'USB."); return; }
        start.setEnabled(false);
        final int fd = connection.getFileDescriptor();
        report("Ouverture USB fd=" + fd);
        worker.execute(() -> {
            int code = NativeCameraBridge.start(fd, frameListener);
            main.post(() -> {
                start.setEnabled(true);
                if (code == 0) {
                    active = true;
                    frameCount = 0;
                    report("Flux démarré : place tes mains sur le téléphone.");
                } else {
                    report("Échec caméra Viture, code : " + code);
                    connection.close(); connection = null;
                }
            });
        });
    }
    private void stopCamera() {
        start.setEnabled(false);
        worker.execute(() -> {
            NativeCameraBridge.stop();
            main.post(() -> {
                active = false;
                if (connection != null) { connection.close(); connection = null; }
                start.setEnabled(true);
                report("Caméra arrêtée.");
            });
        });
    }
    @Override public void onRequestPermissionsResult(int req, String[] p, int[] results) {
        super.onRequestPermissionsResult(req, p, results);
        if (req == 30 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) requestStart();
        else if (req == 30) report("Autorisation caméra refusée.");
    }
    @Override protected void onDestroy() {
        if (registered) unregisterReceiver(receiver);
        worker.execute(() -> {
            NativeCameraBridge.stop();
            if (connection != null) connection.close();
        });
        worker.shutdown(); decoder.shutdown();
        super.onDestroy();
    }
}
