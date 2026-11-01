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
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Display;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Beast Lab: drives the VITURE Beast through the official SDK and logs everything. */
public final class LabActivity extends Activity {
    private static final String USB_PERMISSION = "fr.externalscreen.beastlab.USB";
    private static final int VITURE_VID = 0x35CA;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private TextView logView, poseView, depthStats;
    private static final int REQ_CAPTURE = 41;
    private UsbManager usb;
    private UsbDeviceConnection conn;
    private boolean connected, imuOn, wantConnected;
    private final String[] stateNames = {"luminosité", "volume", "mode d'affichage", "film électrochrome", "DoF natif",
        "porté", "distance", "taille"};

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction()) || UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) {
                UsbDevice d = Build.VERSION.SDK_INT >= 33 ? i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                    : i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d == null || d.getVendorId() != VITURE_VID) return;
                boolean attached = UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction());
                log(String.format(Locale.US, "USB %s : %04X:%04X", attached ? "rebranché" : "débranché / réinitialisé", d.getVendorId(), d.getProductId()));
                if (!attached) dropLink();
                else if (wantConnected) main.postDelayed(() -> requestConnect(), 800);
                return;
            }
            if (USB_PERMISSION.equals(i.getAction())) {
                boolean ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                log("Permission USB : " + (ok ? "accordée" : "refusée"));
                if (ok) connect();
            }
        }
    };

    private final Runnable poseTick = new Runnable() {
        @Override public void run() {
            if (!imuOn) return;
            float[] p = LabBridge.nPose();
            poseView.setText(String.format(Locale.FRANCE, "IMU (%d échantillons)  roulis %.1f°  tangage %.1f°  lacet %.1f°\nquaternion w=%.3f x=%.3f y=%.3f z=%.3f",
                (long) p[7], p[0], p[1], p[2], p[3], p[4], p[5], p[6]));
            main.postDelayed(this, 100);
        }
    };

    static void installCrashRecorder(Context c) {
        final java.io.File f = new java.io.File(c.getFilesDir(), "last-crash.txt");
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(f))) {
                w.println(new Date() + " — thread " + t.getName());
                e.printStackTrace(w);
            } catch (Exception ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        installCrashRecorder(this);
        usb = getSystemService(UsbManager.class);
        IntentFilter f = new IntentFilter(USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_EXPORTED); else registerReceiver(rx, f);
        LabBridge.listener = (id, v) -> main.post(() ->
            log("ÉVÉNEMENT lunettes : " + (id >= 0 && id < stateNames.length ? stateNames[id] : "id " + id) + " = " + v));
        buildUi();
        log("Beast Lab 1.5 — Android " + Build.VERSION.RELEASE + " — " + Build.MANUFACTURER + " " + Build.MODEL);
        log(LabBridge.LOAD_ERROR == null ? "SDK VITURE chargé" : "ERREUR chargement : " + LabBridge.LOAD_ERROR);
        java.io.File crash = new java.io.File(getFilesDir(), "last-crash.txt");
        if (crash.exists()) {
            try { log("— PLANTAGE PRÉCÉDENT —\n" + new String(java.nio.file.Files.readAllBytes(crash.toPath()), StandardCharsets.UTF_8)); }
            catch (Exception ignored) { }
            crash.delete();
        }
        dumpExitReasons();
        java.io.File dl = new java.io.File(getFilesDir(), "depth-events.txt");
        if (dl.exists()) {
            try { log("— ÉTAPES DE LA RECOPIE (session précédente) —\n" + new String(java.nio.file.Files.readAllBytes(dl.toPath()), StandardCharsets.UTF_8)); }
            catch (Exception ignored) { }
            dl.delete();
        }
        dumpUsb();
        dumpDisplays();
        main.post(depthPoll);
    }

    @Override protected void onDestroy() {
        imuOn = false;
        if (connected) LabBridge.nDisconnect();
        if (conn != null) conn.close();
        unregisterReceiver(rx);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI
    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(14), dp(16), dp(14), dp(24));
        p.setBackgroundColor(0xff0f141c);
        sv.addView(p);
        setContentView(sv);
        p.addView(text("Beast Lab — test des commandes du SDK VITURE", 20, 0xffffffff));
        p.addView(text("Chaque action est notée dans le journal avec son code retour (0 = succès). "
            + "Regarde dans les lunettes ce qui change, puis envoie le journal.", 13, 0xffa9b8c8));

        p.addView(row(btn("1. Connecter", v -> requestConnect()), btn("Reconnecter", v -> { dropLink(); requestConnect(); }), btn("Lire l'état", v -> info())));

        p.addView(section("Mode des lunettes"));
        p.addView(row(btn("Natif (suivi des lunettes)", v -> call("native_set_mode", 1)),
            btn("Bypass (écran simple)", v -> call("native_set_mode", 0))));

        p.addView(section("Suivi (mode natif)"));
        p.addView(row(btn("0DoF", v -> call("native_set_dof", 0)), btn("3DoF", v -> call("native_set_dof", 1)),
            btn("Smooth follow", v -> call("native_set_dof", 2))));
        p.addView(btn("Recentrer", v -> call0("native_recenter_dof")));

        p.addView(section("Distance d'affichage (SDK : 1 à 10)"));
        p.addView(slider(1, 10, 5, "native_set_display_distance", "distance"));
        p.addView(section("Taille d'affichage (SDK : 0 Small … 4 Ultra)"));
        p.addView(row(btn("0", v -> call("native_set_display_size", 0)), btn("1", v -> call("native_set_display_size", 1)),
            btn("2", v -> call("native_set_display_size", 2)), btn("3", v -> call("native_set_display_size", 3)),
            btn("4", v -> call("native_set_display_size", 4))));

        p.addView(section("2D / 3D et position"));
        p.addView(row(btn("2D", v -> call("native_switch_dimension", 0)), btn("3D côte à côte", v -> call("native_switch_dimension", 1))));
        p.addView(row(btn("Side mode OFF", v -> call("native_set_side_mode", 0)), btn("Side mode ON", v -> call("native_set_side_mode", 1))));
        p.addView(row(btn("2D bypass", v -> call("switch_dimension", 0)), btn("3D bypass", v -> call("switch_dimension", 1))));

        p.addView(section("Vraie distance — prototype"));
        p.addView(text("L'appli recopie l'écran du téléphone et le dessine elle-même dans les lunettes, une image par œil. "
            + "L'écart entre les deux images règle la distance perçue. Le suivi de tête reste celui des lunettes (Natif + 3DoF / Smooth follow).", 12, 0xffa9b8c8));
        p.addView(row(btn("Démarrer la recopie", v -> startDepth()), btn("Arrêter", v -> stopService(new Intent(this, DepthService.class)))));
        p.addView(row(btn("Lunettes en 3D côte à côte", v -> { DepthService.sbs = true; call("native_switch_dimension", 1); }),
            btn("Lunettes en 2D", v -> { DepthService.sbs = false; call("native_switch_dimension", 0); })));
        p.addView(live("Écart par œil (− = plus près, + = plus loin)", -120, 120, 0, " px", v -> DepthService.disparityPx = v));
        p.addView(live("Taille de l'écran", 20, 100, 80, " %", v -> DepthService.scale = v / 100f));
        p.addView(live("Hauteur", -40, 40, 0, " %", v -> DepthService.offsetY = v / 100f));
        android.widget.CheckBox pattern = new android.widget.CheckBox(this);
        pattern.setText("Mire de test (damier) au lieu de l'écran");
        pattern.setTextColor(0xffffffff);
        pattern.setOnCheckedChangeListener((c, on) -> DepthService.testPattern = on);
        p.addView(pattern);
        depthStats = text("", 12, 0xff9be7b4);
        p.addView(depthStats);

        p.addView(section("Orientation de la tête (IMU du SDK)"));
        p.addView(row(btn("IMU 60 Hz", v -> imu(true, 0)), btn("IMU 500 Hz", v -> imu(true, 4)), btn("Arrêter IMU", v -> imu(false, 0))));
        poseView = text("IMU arrêtée", 13, 0xff9be7b4);
        p.addView(poseView);

        p.addView(section("Diagnostic"));
        p.addView(row(btn("Écrans Android", v -> dumpDisplays()), btn("USB", v -> dumpUsb()), btn("Journal SDK", v -> log("— JOURNAL SDK —\n" + LabBridge.nSdkLog()))));
        p.addView(row(btn("Copier le journal", v -> copy()), btn("Enregistrer (Téléchargements)", v -> save())));
        logView = text("", 12, 0xffd6e2ee);
        logView.setTextIsSelectable(true);
        p.addView(logView);
    }

    private View slider(int min, int max, int def, String op, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(label + " : " + def, 14, 0xffffffff);
        SeekBar s = new SeekBar(this);
        s.setMax(max - min); s.setProgress(def - min);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int v, boolean u) { t.setText(label + " : " + (v + min)); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { call(op, b.getProgress() + min); }
        });
        box.addView(t); box.addView(s);
        return box;
    }

    private interface IntSink { void set(int v); }

    private View live(String label, int min, int max, int def, String unit, IntSink sink) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(label + " : " + def + unit, 14, 0xffffffff);
        SeekBar s = new SeekBar(this);
        s.setMax(max - min); s.setProgress(def - min);
        sink.set(def);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int v, boolean u) { t.setText(label + " : " + (v + min) + unit); sink.set(v + min); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { log(label + " = " + (b.getProgress() + min) + unit); }
        });
        box.addView(t); box.addView(s);
        return box;
    }

    private void startDepth() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            log("Autorise « Afficher par-dessus les autres applis » pour Beast Lab, puis réappuie.");
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 3);
        android.media.projection.MediaProjectionManager mpm = getSystemService(android.media.projection.MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        log("Choisis « Tout l'écran » dans la demande de capture d'Android.");
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAPTURE) return;
        log("Retour de la demande de capture : code " + res + (data == null ? ", pas de jeton" : ", jeton reçu"));
        if (res != RESULT_OK || data == null) { log("Capture d'écran refusée."); return; }
        Intent i = new Intent(this, DepthService.class).putExtra(DepthService.EXTRA_CODE, res).putExtra(DepthService.EXTRA_DATA, data);
        try { startForegroundService(i); log("Recopie en cours de démarrage…"); }
        catch (RuntimeException e) { log("Démarrage refusé : " + e); }
    }

    private final Runnable depthPoll = new Runnable() {
        @Override public void run() {
            String e;
            while ((e = DepthService.events.poll()) != null) logRaw(e);
            if (depthStats != null) depthStats.setText(DepthService.instance != null ? DepthService.stats : "Recopie arrêtée");
            main.postDelayed(this, 500);
        }
    };

    private LinearLayout row(View... vs) {
        LinearLayout r = new LinearLayout(this);
        for (View v : vs) r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }

    private Button btn(String s, View.OnClickListener l) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(13);
        b.setOnClickListener(v -> { log("▶ " + s); l.onClick(v); });
        return b;
    }

    private TextView section(String s) { TextView t = text(s.toUpperCase(Locale.FRANCE), 13, 0xff7cc4ff); t.setPadding(0, dp(16), 0, dp(2)); return t; }

    private TextView text(String s, int sp, int c) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(c); t.setPadding(0, dp(3), 0, 0); return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ------------------------------------------------------------------ actions
    private UsbDevice findGlasses() {
        for (UsbDevice d : usb.getDeviceList().values()) if (d.getVendorId() == VITURE_VID) return d;
        return null;
    }

    /** Forget a link that died (the glasses re-enumerate their USB when switching mode). */
    private void dropLink() {
        if (!connected && conn == null) return;
        imuOn = false;
        main.removeCallbacks(poseTick);
        // Never call into libglasses on a link that died: its USB thread is still
        // running on the old descriptor and stop/destroy then crash (SIGSEGV seen
        // in the 1.4 traces). The stale handle is simply abandoned.
        if (conn != null) { conn.close(); conn = null; }
        connected = false;
        LabBridge.nForget();
        log("Liaison abandonnée (sans appel au SDK).");
    }

    private void requestConnect() {
        wantConnected = true;
        if (connected) { log("Déjà connecté."); return; }
        UsbDevice d = findGlasses();
        if (d == null) { log("Lunettes VITURE (VID 35CA) introuvables. Branche les Beast."); dumpUsb(); return; }
        if (usb.hasPermission(d)) { connect(); return; }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, new Intent(USB_PERMISSION).setPackage(getPackageName()), flags));
        log("Accepte l'accès USB aux lunettes…");
    }

    private void connect() {
        UsbDevice d = findGlasses();
        if (d == null || LabBridge.LOAD_ERROR != null) { log("Connexion impossible : " + LabBridge.LOAD_ERROR); return; }
        conn = usb.openDevice(d);
        if (conn == null) { log("openDevice a échoué"); return; }
        log(String.format(Locale.US, "Connexion à %04X:%04X (fd %d)…", d.getVendorId(), d.getProductId(), conn.getFileDescriptor()));
        String r = LabBridge.nConnect(d.getProductId(), conn.getFileDescriptor(), getCacheDir().getAbsolutePath());
        log(r);
        connected = true;
        info();
    }

    private void info() {
        if (!connected) { log("Connecte d'abord."); return; }
        log("— ÉTAT —\n" + LabBridge.nInfo());
    }

    private void call(String op, int arg) {
        if (!connected) { log("Connecte d'abord."); return; }
        int r = LabBridge.nCall(op, arg, true);
        log(op + "(" + arg + ") → " + code(r));
        if ("native_set_mode".equals(op) && r == 0) {
            log("Changement de mode : les lunettes vont peut-être réinitialiser leur USB, reconnexion automatique…");
            main.postDelayed(this::checkLink, 2500);
        }
        if (r == -5 || r == -3 || r == -2) linkLost();
    }

    private void call0(String op) {
        if (!connected) { log("Connecte d'abord."); return; }
        int r = LabBridge.nCall(op, 0, false);
        log(op + "() → " + code(r));
        if (r == -5 || r == -3 || r == -2) linkLost();
    }

    private void imu(boolean open, int freq) {
        if (!connected) { log("Connecte d'abord."); return; }
        int r = LabBridge.nImu(open, freq);
        log((open ? "open_imu(pose, fréquence " + freq + ")" : "close_imu") + " → " + code(r));
        imuOn = open && r == 0;
        if (r == -5 || r == -3 || r == -2) linkLost();
        main.removeCallbacks(poseTick);
        if (imuOn) main.post(poseTick); else poseView.setText("IMU arrêtée");
    }

    private void linkLost() {
        log("Liaison USB perdue : reconnexion…");
        dropLink();
        main.postDelayed(this::requestConnect, 1000);
    }

    /** After a mode switch: verify the link still answers, otherwise reconnect. */
    private void checkLink() {
        if (!connected) { requestConnect(); return; }
        int m = LabBridge.nCall("native_get_mode", 0, false);
        log("Vérification de la liaison : mode = " + code(m));
        if (m < 0) linkLost();
    }

    private static String code(int r) {
        switch (r) {
            case 0: return "0 (succès)";
            case -1: return "-1 (paramètre invalide)";
            case -2: return "-2 (USB indisponible)";
            case -3: return "-3 (erreur d'exécution USB)";
            case -4: return "-4 (non supporté / mauvais mode)";
            case -5: return "-5 (pas de réponse : liaison USB perdue)";
            case -7: return "-7 (refusé par les lunettes)";
            case -10: return "-10 (état invalide)";
            case -1000: return "non connecté";
            case -1001: return "fonction absente du SDK";
            default: return String.valueOf(r);
        }
    }

    /** Why did the previous runs end? (native crash, killed by Android, ANR…) */
    private void dumpExitReasons() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
            java.util.List<android.app.ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(getPackageName(), 0, 3);
            StringBuilder s = new StringBuilder("— FERMETURES PRÉCÉDENTES —\n");
            String[] names = {"inconnue", "fin normale", "signal", "mémoire", "plantage Java", "plantage natif", "ANR", "init", "permission",
                "trop de ressources", "demandée par l'utilisateur", "session utilisateur", "dépendance", "autre", "gelée", "paquet modifié", "paquet mis à jour"};
            for (android.app.ApplicationExitInfo e : list) {
                int r = e.getReason();
                s.append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(e.getTimestamp())))
                    .append("  raison ").append(r < names.length ? names[r] : String.valueOf(r))
                    .append(", statut ").append(e.getStatus()).append(", ").append(e.getDescription()).append('\n');
                if (r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || r == android.app.ApplicationExitInfo.REASON_CRASH
                        || r == android.app.ApplicationExitInfo.REASON_ANR) {
                    try (java.io.InputStream in = e.getTraceInputStream()) {
                        if (in != null) {
                            byte[] buf = new byte[6000]; int n = in.read(buf);
                            if (n > 0) s.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1)).append('\n');
                        }
                    } catch (Exception ignored) { }
                }
            }
            log(s.toString());
        } catch (Exception e) { log("raisons de fermeture illisibles : " + e); }
    }

    private void dumpUsb() {
        StringBuilder s = new StringBuilder("— USB —\n");
        for (UsbDevice d : usb.getDeviceList().values()) {
            s.append(String.format(Locale.US, "%04X:%04X %s / %s, permission %s\n", d.getVendorId(), d.getProductId(),
                d.getManufacturerName(), d.getProductName(), usb.hasPermission(d)));
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface itf = d.getInterface(i);
                s.append(String.format(Locale.US, "   itf %d classe %d/%d, %d endpoints\n", itf.getId(), itf.getInterfaceClass(),
                    itf.getInterfaceSubclass(), itf.getEndpointCount()));
            }
        }
        log(s.toString());
    }

    private void dumpDisplays() {
        DisplayManager dm = getSystemService(DisplayManager.class);
        StringBuilder s = new StringBuilder("— ÉCRANS —\n");
        for (Display d : dm.getDisplays()) {
            Display.Mode m = d.getMode();
            s.append(String.format(Locale.US, "#%d « %s » %dx%d @ %.0f Hz, flags 0x%x, état %d\n", d.getDisplayId(), d.getName(),
                m.getPhysicalWidth(), m.getPhysicalHeight(), m.getRefreshRate(), d.getFlags(), d.getState()));
        }
        Display[] pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        s.append("Écrans « présentation » utilisables par une appli : ").append(pres.length).append('\n');
        log(s.toString());
    }

    private void copy() {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Beast Lab", log + "\n— JOURNAL SDK —\n" + LabBridge.nSdkLog()));
        log("Journal copié.");
    }

    private void save() {
        try {
            String name = "beast-lab-" + new SimpleDateFormat("HHmmss", Locale.US).format(new Date()) + ".txt";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BeastLab");
            android.net.Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                o.write((log + "\n— JOURNAL SDK —\n" + LabBridge.nSdkLog()).getBytes(StandardCharsets.UTF_8));
            }
            log("Enregistré : Téléchargements/BeastLab/" + name);
        } catch (Exception e) { log("Enregistrement impossible : " + e); }
    }

    private void logRaw(String m) {
        log.append(m).append('\n');
        if (logView != null) logView.setText(log);
    }

    private void log(String m) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        log.append(t).append("  ").append(m).append('\n');
        if (log.length() > 40000) log.delete(0, 10000);
        if (logView != null) logView.setText(log);
    }
}
