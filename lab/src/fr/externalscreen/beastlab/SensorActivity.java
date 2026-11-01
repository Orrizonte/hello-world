package fr.externalscreen.beastlab;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Beast 6DoF Test": one button, records 20 s of raw IMU + camera from the
 * VITURE Beast (bypass mode is switched on and off automatically) and saves a
 * zip in Downloads/BeastLab.
 */
public final class SensorActivity extends Activity {
    private static final String GL_PERM = "fr.externalscreen.beastsensors.GLASSES", CAM_PERM = "fr.externalscreen.beastsensors.CAM";
    private static final int VITURE_VID = 0x35CA, CAM_VID = 0x0C45, CAM_PID = 0x6368, RECORD_MS = 20000;

    private enum Step { IDLE, CONNECTING, BYPASS, CAMERA, COUNTDOWN, RECORDING, SAVING, RESTORING, DONE, FAILED }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private UsbManager usb;
    private UsbDeviceConnection conn, camConn;
    private boolean connected;
    private Step step = Step.IDLE;
    private TextView stepView, detailView, logView;
    private Button go, share;
    private Uri zipUri;
    private String zipName;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a) || UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                UsbDevice d = Build.VERSION.SDK_INT >= 33 ? i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class) : i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d == null || d.getVendorId() != VITURE_VID) return;
                boolean attached = UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a);
                log(String.format(Locale.US, "USB %s %04X:%04X", attached ? "branché" : "débranché", d.getVendorId(), d.getProductId()));
                if (!attached) forget();
                else if (step == Step.BYPASS || step == Step.RESTORING || step == Step.CONNECTING) main.postDelayed(SensorActivity.this::connectGlasses, 800);
                return;
            }
            boolean ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (GL_PERM.equals(a)) { log("permission lunettes : " + ok); if (ok) connectGlasses(); else fail("Accès USB aux lunettes refusé."); }
            if (CAM_PERM.equals(a)) { log("permission caméra : " + ok); if (ok) countdown(); else fail("Accès USB à la caméra refusé."); }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LabActivity.installCrashRecorder(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usb = getSystemService(UsbManager.class);
        IntentFilter f = new IntentFilter(GL_PERM);
        f.addAction(CAM_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_EXPORTED); else registerReceiver(rx, f);
        buildUi();
        log("Beast 6DoF Test 1.0 — " + Build.MANUFACTURER + " " + Build.MODEL + " Android " + Build.VERSION.RELEASE);
        log(LabBridge.LOAD_ERROR == null ? "SDK VITURE chargé" : "ERREUR SDK : " + LabBridge.LOAD_ERROR);
        File crash = new File(getFilesDir(), "last-crash.txt");
        if (crash.exists()) {
            try { log("plantage précédent :\n" + new String(java.nio.file.Files.readAllBytes(crash.toPath()), StandardCharsets.UTF_8)); } catch (Exception ignored) { }
            crash.delete();
        }
        show(Step.IDLE, "Branche les Beast, mets-les sur la tête, puis appuie sur le bouton.");
    }

    @Override protected void onDestroy() {
        if (connected) LabBridge.nDisconnect();
        if (conn != null) conn.close();
        if (camConn != null) camConn.close();
        unregisterReceiver(rx);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI
    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(20), dp(28), dp(20), dp(28));
        p.setBackgroundColor(0xff0f141c);
        sv.addView(p);
        setContentView(sv);

        TextView title = text("Test capteurs 6DoF", 26, 0xffffffff);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        p.addView(title);
        p.addView(text("20 secondes d'enregistrement de la caméra et des capteurs de mouvement des Beast.", 15, 0xffa9b8c8));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xff1b2533); bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(22);
        stepView = text("", 30, 0xffffffff);
        stepView.setTypeface(Typeface.DEFAULT_BOLD);
        stepView.setGravity(Gravity.CENTER);
        detailView = text("", 17, 0xffd6e2ee);
        detailView.setGravity(Gravity.CENTER);
        card.addView(stepView); card.addView(detailView);
        p.addView(card, lp);

        go = new Button(this);
        go.setText("Lancer le test"); go.setAllCaps(false); go.setTextSize(22);
        go.setOnClickListener(v -> start());
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, dp(76)); gp.topMargin = dp(22);
        p.addView(go, gp);

        share = new Button(this);
        share.setText("Envoyer le fichier"); share.setAllCaps(false); share.setTextSize(20);
        share.setVisibility(View.GONE);
        share.setOnClickListener(v -> shareZip());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(66)); sp.topMargin = dp(12);
        p.addView(share, sp);

        TextView how = text("Pendant l'enregistrement :\n• regarde autour de toi (pièce éclairée)\n• tourne la tête à gauche, à droite\n• avance puis recule d'un pas\n• penche-toi un peu", 16, 0xffa9b8c8);
        how.setPadding(0, dp(22), 0, 0);
        p.addView(how);

        Button copy = new Button(this);
        copy.setText("Copier le journal technique"); copy.setAllCaps(false); copy.setTextSize(14);
        copy.setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Beast 6DoF Test", log + "\n— SDK —\n" + LabBridge.nSdkLog()));
            detailView.setText("Journal copié.");
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.topMargin = dp(28);
        p.addView(copy, cp);
        logView = text("", 11, 0xff6f8295);
        p.addView(logView);
    }

    private void show(Step s, String detail) {
        step = s;
        String[] names = {"Prêt", "Connexion…", "Préparation…", "Caméra…", "", "Enregistrement", "Enregistrement du fichier…", "Remise en mode normal…", "Terminé ✓", "Échec"};
        if (s != Step.COUNTDOWN && s != Step.RECORDING) stepView.setText(names[s.ordinal()]);
        stepView.setTextColor(s == Step.DONE ? 0xff9be7b4 : s == Step.FAILED ? 0xffff8a80 : s == Step.RECORDING ? 0xffffd36b : 0xffffffff);
        detailView.setText(detail);
        boolean busy = s != Step.IDLE && s != Step.DONE && s != Step.FAILED;
        go.setEnabled(!busy);
        go.setText(s == Step.DONE || s == Step.FAILED ? "Recommencer" : "Lancer le test");
        share.setVisibility(s == Step.DONE && zipUri != null ? View.VISIBLE : View.GONE);
    }

    private void fail(String why) { log("ÉCHEC : " + why); show(Step.FAILED, why); }

    // ------------------------------------------------------------------ flow
    private void start() {
        if (LabBridge.LOAD_ERROR != null) { fail("SDK VITURE non chargé : " + LabBridge.LOAD_ERROR); return; }
        zipUri = null;
        show(Step.CONNECTING, "Accepte l'accès USB aux lunettes si Android le demande.");
        connectGlasses();
    }

    private UsbDevice find(int vid, int pid) {
        for (UsbDevice d : usb.getDeviceList().values()) if (d.getVendorId() == vid && (pid < 0 || d.getProductId() == pid)) return d;
        return null;
    }

    private void forget() {
        if (conn != null) { conn.close(); conn = null; }
        if (connected) { LabBridge.nForget(); connected = false; log("liaison oubliée"); }
    }

    private void connectGlasses() {
        if (step == Step.IDLE || step == Step.DONE || step == Step.FAILED) return;
        if (connected) { afterConnect(); return; }
        UsbDevice d = find(VITURE_VID, -1);
        if (d == null) { if (step == Step.CONNECTING) fail("Lunettes introuvables : branche les Beast."); return; }
        if (!usb.hasPermission(d)) {
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, new Intent(GL_PERM).setPackage(getPackageName()), fl));
            return;
        }
        conn = usb.openDevice(d);
        if (conn == null) { fail("Impossible d'ouvrir les lunettes."); return; }
        log(LabBridge.nConnect(d.getProductId(), conn.getFileDescriptor(), getCacheDir().getAbsolutePath()));
        connected = true;
        afterConnect();
    }

    private void afterConnect() {
        int mode = LabBridge.nCall("native_get_mode", 0, false);
        log("mode = " + mode + " (étape " + step + ")");
        if (step == Step.RESTORING) {
            if (mode == 0) { int r = LabBridge.nCall("native_set_mode", 1, true); log("retour natif → " + r); if (r != 0) finishFlow(); else main.postDelayed(this::finishFlow, 6000); }
            else finishFlow();
            return;
        }
        if (mode == 1) {
            show(Step.BYPASS, "Les lunettes passent en mode Bypass (l'image peut clignoter quelques secondes).");
            int r = LabBridge.nCall("native_set_mode", 0, true);
            log("set_mode(bypass) → " + r);
            if (r != 0) fail("Les lunettes ont refusé le mode Bypass (code " + r + ").");
            else main.postDelayed(() -> { if (step == Step.BYPASS && connected) checkStillThere(); }, 4000);
            return;
        }
        if (mode < 0) { forget(); main.postDelayed(this::connectGlasses, 1000); return; }
        UsbDevice cam = find(CAM_VID, CAM_PID);
        if (cam == null) { log("caméra 0C45:6368 introuvable : IMU seule"); countdown(); return; }
        if (usb.hasPermission(cam)) { countdown(); return; }
        show(Step.CAMERA, "Accepte l'accès USB à la caméra si Android le demande.");
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        usb.requestPermission(cam, PendingIntent.getBroadcast(this, 1, new Intent(CAM_PERM).setPackage(getPackageName()), fl));
    }

    /** Bypass switch without USB re-enumeration: just continue. */
    private void checkStillThere() {
        int m = LabBridge.nCall("native_get_mode", 0, false);
        log("vérification après bypass : mode = " + m);
        if (m == 0) afterConnect();
        else if (m < 0) { forget(); main.postDelayed(this::connectGlasses, 1000); }
    }

    private void countdown() {
        show(Step.COUNTDOWN, "Prépare-toi à bouger la tête");
        for (int i = 3; i >= 1; i--) {
            final int n = i;
            main.postDelayed(() -> { stepView.setText(String.valueOf(n)); stepView.setTextColor(0xffffffff); }, (3 - i) * 1000L);
        }
        main.postDelayed(this::record, 3000);
    }

    private void record() {
        UsbDevice glasses = find(VITURE_VID, -1);
        String freqs = LabBridge.nImuFreqs(glasses != null ? glasses.getProductId() : 0x1211);
        log("fréquences IMU brute : " + freqs);
        int freq = 0;
        String[] hz = {"60", "90", "120", "240", "500", "1000"};
        for (int i = 0; i < 6; i++) if (freqs.contains(hz[i] + " Hz : 1")) freq = i;
        int camFd = -1;
        UsbDevice cam = find(CAM_VID, CAM_PID);
        if (cam != null && usb.hasPermission(cam)) { camConn = usb.openDevice(cam); if (camConn != null) camFd = camConn.getFileDescriptor(); }
        final String stamp = new SimpleDateFormat("HHmmss", Locale.US).format(new Date());
        final File dir = new File(getCacheDir(), "capteurs-" + stamp), frames = new File(dir, "frames");
        frames.mkdirs();
        log("démarrage capteurs (IMU " + hz[freq] + " Hz, caméra fd " + camFd + ")\n" + LabBridge.nSensorStart(dir.getAbsolutePath(), freq, camFd));
        show(Step.RECORDING, "Bouge la tête : regarde autour, avance, recule, penche-toi.");
        final int f0 = freq;
        new Thread(() -> {
            long t0 = SystemClock.elapsedRealtime(), end = t0 + RECORD_MS, nextUi = 0;
            int saved = 0; boolean full = false; long bytes = 0;
            StringBuilder csv = new StringBuilder("ts_device,arrival_monotonic_ns,seq,width,height,file\n");
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inSampleSize = 2;
            while (SystemClock.elapsedRealtime() < end) {
                byte[] b = LabBridge.nCamTake();
                if (b == null) SystemClock.sleep(4);
                else try {
                    java.nio.ByteBuffer h = java.nio.ByteBuffer.wrap(b, 0, 40).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    long ts = h.getLong(), arr = h.getLong(), seq = h.getLong(), w = h.getLong(), hh = h.getLong();
                    if (!full) {
                        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(dir, "pleine-resolution.jpg"))) { fo.write(b, 40, b.length - 40); }
                        full = true;
                    }
                    bytes += b.length - 40;
                    android.graphics.Bitmap bm = BitmapFactory.decodeByteArray(b, 40, b.length - 40, o);
                    if (bm != null) {
                        String name = String.format(Locale.US, "%019d.jpg", arr);
                        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(frames, name))) { bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, fo); }
                        bm.recycle();
                        csv.append(ts).append(',').append(arr).append(',').append(seq).append(',').append(w).append(',').append(hh).append(',').append(name).append('\n');
                        saved++;
                    }
                } catch (Exception e) { final String m = e.toString(); main.post(() -> log("image : " + m)); }
                long now = SystemClock.elapsedRealtime();
                if (now >= nextUi) {
                    nextUi = now + 250;
                    final long left = (end - now + 999) / 1000;
                    final int n = saved;
                    main.post(() -> { stepView.setText(left + " s"); stepView.setTextColor(0xffffd36b); detailView.setText("Bouge la tête : regarde autour, avance, recule, penche-toi.\n\nimages caméra : " + n); });
                }
            }
            final int nSaved = saved; final long avg = saved > 0 ? bytes / saved : 0;
            main.post(() -> save(dir, stamp, csv, nSaved, avg, f0));
        }, "sensor-rec").start();
    }

    private void save(File dir, String stamp, StringBuilder csv, int saved, long avg, int freq) {
        show(Step.SAVING, "Quelques secondes…");
        String stats = LabBridge.nSensorStats();
        log("FIN\n" + stats + "\nimages enregistrées : " + saved + ", MJPEG moyen " + avg / 1024 + " Ko");
        log(LabBridge.nSensorStop(connected));
        if (camConn != null) { camConn.close(); camConn = null; }
        new Thread(() -> {
            String err = null;
            try {
                java.nio.file.Files.write(new File(dir, "frames.csv").toPath(), csv.toString().getBytes(StandardCharsets.UTF_8));
                java.nio.file.Files.write(new File(dir, "journal.txt").toPath(), (log + "\n— SDK —\n" + LabBridge.nSdkLog()).getBytes(StandardCharsets.UTF_8));
                zipName = "capteurs-" + stamp + ".zip";
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, zipName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BeastLab");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(getContentResolver().openOutputStream(uri))) {
                    java.util.ArrayDeque<File> q = new java.util.ArrayDeque<>(); q.add(dir);
                    while (!q.isEmpty()) {
                        File[] kids = q.poll().listFiles();
                        if (kids == null) continue;
                        for (File k : kids) {
                            if (k.isDirectory()) { q.add(k); continue; }
                            z.putNextEntry(new java.util.zip.ZipEntry(dir.toPath().relativize(k.toPath()).toString()));
                            z.write(java.nio.file.Files.readAllBytes(k.toPath()));
                            z.closeEntry();
                        }
                    }
                }
                zipUri = uri;
                deleteTree(dir);
            } catch (Exception e) { err = e.toString(); }
            final String fe = err;
            main.post(() -> {
                if (fe != null) log("zip impossible : " + fe); else log("fichier : Téléchargements/BeastLab/" + zipName);
                show(Step.RESTORING, "Les lunettes reviennent en mode normal (suivi de tête).");
                if (connected) afterConnect(); else connectGlasses();
            });
        }, "sensor-zip").start();
    }

    private void finishFlow() {
        if (step != Step.RESTORING) return;
        if (zipUri == null) { fail("L'enregistrement n'a pas pu être sauvegardé (voir le journal)."); return; }
        show(Step.DONE, "Fichier enregistré :\nTéléchargements/BeastLab/" + zipName + "\n\nAppuie sur « Envoyer le fichier » ou joins-le à ton message.");
    }

    private void shareZip() {
        if (zipUri == null) return;
        Intent i = new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, zipUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Envoyer " + zipName));
    }

    private static void deleteTree(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File c : k) deleteTree(c);
        f.delete();
    }

    private TextView text(String s, int sp, int c) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(c); t.setPadding(0, dp(6), 0, 0); return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void log(String m) {
        log.append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())).append("  ").append(m).append('\n');
        if (log.length() > 60000) log.delete(0, 15000);
        if (logView != null) logView.setText(log);
    }
}
