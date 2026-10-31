package fr.externalscreen.beastfingerdiagnostic;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Display;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;

/** Setup checklist, start/stop, appearance settings, live camera diagnostic. */
public final class MainActivity extends Activity {
    private static final String USB_PERMISSION = "fr.externalscreen.beastfingerdiagnostic.USB_PERMISSION";
    private static final String VERSION = "0.9.4";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private SharedPreferences prefs;
    private UsbManager usb;
    private TextView logView, statsView, checklist;
    private ImageView previewView;
    private CheckBox previewCheck;
    private boolean pendingStart;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (FingerService.ACTION_STATUS.equals(a)) {
                report(i.getStringExtra(FingerService.EXTRA_MESSAGE));
            } else if (USB_PERMISSION.equals(a)) {
                boolean ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                report(ok ? "Permission USB accordée." : "Permission USB refusée.");
                if (ok && pendingStart) start();
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                report("Périphérique USB branché.");
                refreshChecklist();
            }
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refreshChecklist();
            boolean running = FingerService.instance != null;
            statsView.setText(running ? FingerService.stats : "Arrêté.");
            if (previewCheck.isChecked()) {
                Bitmap b = FingerService.preview.get();
                if (b != null && !b.isRecycled()) previewView.setImageBitmap(b);
            }
            main.postDelayed(this, previewCheck.isChecked() ? 150 : 700);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(FingerService.PREFS, MODE_PRIVATE);
        usb = getSystemService(UsbManager.class);
        buildUi();
        IntentFilter f = new IntentFilter(FingerService.ACTION_STATUS);
        f.addAction(USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, f);
        report("Beast Fingers " + VERSION + " — Android " + Build.VERSION.RELEASE + " — " + Build.MANUFACTURER + " " + Build.MODEL);
        report("Bibliothèque native : " + (NativeCameraBridge.available() ? "OK" : NativeCameraBridge.loadError())
            + (NativeCameraBridge.vitureSdkPresent() ? " | SDK VITURE présent (secours)" : " | UVC direct (sans SDK)"));
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
    }

    @Override protected void onResume() {
        super.onResume();
        FingerService.previewWanted = previewCheck.isChecked();
        main.post(ticker);
    }

    @Override protected void onPause() {
        FingerService.previewWanted = false;
        main.removeCallbacks(ticker);
        super.onPause();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(18), dp(16), dp(24));
        page.setBackgroundColor(0xff0f141c);
        scroll.addView(page);
        setContentView(scroll);

        TextView title = text("Beast Fingers " + VERSION, 22, 0xffffffff);
        page.addView(title);
        page.addView(text("Contours transparents de tes doigts au-dessus du clavier, vus par la caméra des VITURE Beast. "
            + "Suivi automatique de l'écran et de la tête, aucun calibrage.", 14, 0xffb8c7d9));

        page.addView(section("Préparation"));
        checklist = text("", 14, 0xffe0e6ee);
        page.addView(checklist);
        LinearLayout row1 = new LinearLayout(this);
        row1.addView(button("Accessibilité", v -> openAccessibility()), weight());
        row1.addView(button("Superposition", v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName())))), weight());
        page.addView(row1);
        page.addView(text("Le service d'accessibilité sert uniquement à dessiner AU-DESSUS du clavier "
            + "(une surcouche normale passe dessous). Il ne lit rien. Si Android l'affiche grisé « paramètre restreint » : "
            + "Infos de l'appli ▸ ⋮ ▸ « Autoriser les paramètres restreints », puis réessaie.", 12, 0xff8fa3b8));

        LinearLayout row2 = new LinearLayout(this);
        row2.setPadding(0, dp(12), 0, 0);
        Button startB = button("DÉMARRER", v -> start());
        startB.setBackgroundColor(0xff1f8a4c); startB.setTextColor(0xffffffff);
        Button stopB = button("ARRÊTER", v -> stopService(new Intent(this, FingerService.class)));
        row2.addView(startB, weight());
        row2.addView(stopB, weight());
        page.addView(row2);
        statsView = text("", 12, 0xff9be7b4);
        page.addView(statsView);

        page.addView(section("Apparence"));
        page.addView(slider("Opacité du contour", "alpha", 20, 100, 92, "%"));
        page.addView(slider("Épaisseur du contour", "stroke", 1, 8, 3, " dp"));
        page.addView(check("Voile très léger à l'intérieur des doigts", "fill", true));
        page.addView(check("Prédiction du mouvement (réduit le retard)", "predict", true));

        page.addView(section("Repérage de l'écran"));
        page.addView(check("Cadre de repérage sur le bord de l'écran (indispensable en thème sombre)", "frame", true));
        page.addView(slider("Épaisseur du cadre", "frameWidth", 4, 16, 9, " dp"));
        page.addView(check("Caméra à l'envers (rotation 180°)", "rotate180", false));
        page.addView(check("Image caméra en miroir", "mirror", false));

        page.addView(section("Diagnostic"));
        previewCheck = new CheckBox(this);
        previewCheck.setText("Afficher ce que voit la caméra (vert = écran détecté, violet = contours)");
        previewCheck.setTextColor(0xffffffff);
        previewCheck.setOnCheckedChangeListener((cb, on) -> {
            FingerService.previewWanted = on;
            previewView.setVisibility(on ? View.VISIBLE : View.GONE);
            main.removeCallbacks(ticker); main.post(ticker);
        });
        page.addView(previewCheck);
        previewView = new ImageView(this);
        previewView.setAdjustViewBounds(true);
        previewView.setBackgroundColor(0xff000000);
        previewView.setVisibility(View.GONE);
        page.addView(previewView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        page.addView(button("Copier le journal", v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Beast Fingers",
                log + "\n" + FingerService.stats));
            report("Journal copié.");
        }));
        logView = text("", 12, 0xffa9b8c8);
        logView.setTextIsSelectable(true);
        page.addView(logView);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, dp(52), 1);
    }

    private TextView section(String s) {
        TextView t = text(s.toUpperCase(Locale.FRANCE), 13, 0xff7cc4ff);
        t.setPadding(0, dp(20), 0, dp(4));
        return t;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s); b.setOnClickListener(l); b.setAllCaps(false);
        return b;
    }

    private CheckBox check(String label, String key, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(label); c.setTextColor(0xffffffff);
        c.setChecked(prefs.getBoolean(key, def));
        c.setOnCheckedChangeListener((cb, on) -> { prefs.edit().putBoolean(key, on).apply(); pushConfig(); });
        return c;
    }

    private View slider(String label, String key, int min, int max, int def, String unit) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = text("", 14, 0xffffffff);
        int cur = prefs.getInt(key, def);
        t.setText(label + " : " + cur + unit);
        SeekBar s = new SeekBar(this);
        s.setMax(max - min);
        s.setProgress(cur - min);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean user) {
                t.setText(label + " : " + (p + min) + unit);
                if (user) { prefs.edit().putInt(key, p + min).apply(); pushConfig(); }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        box.addView(t); box.addView(s);
        return box;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void pushConfig() {
        if (FingerService.instance == null) return;
        try { startService(new Intent(this, FingerService.class).setAction(FingerService.ACTION_CONFIG)); }
        catch (RuntimeException ignored) { }
    }

    // ------------------------------------------------------------------ checklist / start
    private boolean accessibilityEnabled() {
        if (FingerAccessibilityService.instance != null) return true;
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String me = new ComponentName(this, FingerAccessibilityService.class).flattenToString();
        TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
        sp.setString(enabled);
        while (sp.hasNext()) if (sp.next().equalsIgnoreCase(me)) return true;
        return false;
    }

    private void refreshChecklist() {
        UsbDevice cam = FingerService.findCamera(usb);
        boolean a11y = accessibilityEnabled();
        boolean overlay = Settings.canDrawOverlays(this);
        boolean camPerm = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        StringBuilder s = new StringBuilder();
        s.append(cam != null ? "✅ Caméra des Beast branchée" + (usb.hasPermission(cam) ? "" : " (permission USB demandée au démarrage)")
            : "❌ Caméra des Beast non détectée (branche les lunettes)").append('\n');
        s.append(a11y ? "✅ Service d'accessibilité actif : contours au-dessus du clavier"
            : "⚠️ Service d'accessibilité inactif : sans lui les contours passent sous le clavier").append('\n');
        if (!a11y) s.append(overlay ? "✅ Superposition autorisée (mode de secours)" : "❌ Superposition non autorisée").append('\n');
        s.append(camPerm ? "✅ Autorisation caméra" : "• Autorisation caméra demandée au démarrage").append('\n');
        DisplayManager dm = getSystemService(DisplayManager.class);
        int ext = 0;
        for (Display d : dm.getDisplays()) if (d.getDisplayId() != Display.DEFAULT_DISPLAY) ext++;
        s.append(ext > 0 ? "ℹ️ Écran externe détecté : utilise le mode miroir (duplication) pour voir le clavier dans les lunettes"
            : "ℹ️ Affichage : miroir de l'écran du téléphone");
        checklist.setText(s);
    }

    private void openAccessibility() {
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch (RuntimeException e) { report("Réglages d'accessibilité introuvables : " + e.getMessage()); }
    }

    private void start() {
        pendingStart = false;
        if (!NativeCameraBridge.available()) { report("Bibliothèque native absente : " + NativeCameraBridge.loadError()); return; }
        if (!accessibilityEnabled() && !Settings.canDrawOverlays(this)) {
            report("Active le service d'accessibilité (recommandé) ou la superposition.");
            openAccessibility();
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            return;
        }
        UsbDevice cam = FingerService.findCamera(usb);
        if (cam == null) { report("Caméra des Beast introuvable. Branche les lunettes."); return; }
        if (!usb.hasPermission(cam)) {
            pendingStart = true;
            Intent i = new Intent(USB_PERMISSION).setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            usb.requestPermission(cam, PendingIntent.getBroadcast(this, 0, i, flags));
            report("Accepte l'accès USB à la caméra.");
            return;
        }
        if (FingerService.instance != null) { report("Déjà en marche."); return; }
        prefs.edit().putBoolean("running", true).apply();
        try {
            startForegroundService(new Intent(this, FingerService.class).setAction(FingerService.ACTION_START));
            report("Démarrage…");
        } catch (RuntimeException e) {
            report("Démarrage refusé : " + e.getMessage());
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] g) {
        super.onRequestPermissionsResult(code, p, g);
        if (code == 1) {
            if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) { if (pendingStart) start(); }
            else report("Autorisation caméra refusée : nécessaire pour la caméra USB.");
        }
    }

    private void report(String m) {
        if (m == null) return;
        main.post(() -> {
            log.append(m).append('\n');
            if (log.length() > 12000) log.delete(0, 3000);
            if (logView != null) logView.setText(log);
        });
    }
}
