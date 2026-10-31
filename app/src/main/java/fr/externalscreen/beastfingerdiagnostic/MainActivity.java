package fr.externalscreen.beastfingerdiagnostic;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.Locale;

/**
 * Controls the camera overlay. Phone input is never simulated or intercepted.
 * To test with the native keyboard: start the overlay, go to a messaging app,
 * and type physically on the phone while looking at the Viture Beast display.
 */
public final class MainActivity extends Activity {
    private static final String USB_PERMISSION_ACTION =
        "fr.externalscreen.beastfingerdiagnostic.USB_PERMISSION";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private UsbManager usb;
    private TextView status;
    private TextView settingsSummary;
    private SeekBar alphaSlider, scaleSlider, xSlider, ySlider;
    private Spinner screenSpinner;
    private CheckBox mirrorCheck;
    private boolean receiversRegistered;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (OverlayCameraService.ACTION_STATUS.equals(intent.getAction()))
                report(intent.getStringExtra(OverlayCameraService.EXTRA_MESSAGE));
        }
    };
    private final BroadcastReceiver calibrationReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            byte[] jpeg = intent.getByteArrayExtra("jpeg");
            if (jpeg == null || jpeg.length == 0) { report("Image calibration vide."); return; }
            Bitmap snapshot = BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);
            if(snapshot == null) {report("Image de calibrage non décodable.");return;}
            showCalibration(snapshot);
        }
    };
    private boolean calibrationReceiverRegistered;
    private boolean initialized;
    private int alpha, scale, posX, posY, target;
    private boolean mirror;
    private static final String PREFS = "settings.v07";

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (USB_PERMISSION_ACTION.equals(action)) {
                UsbDevice device = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                    : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (isCamera(device) &&
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    && usb.hasPermission(device)) {
                    report("Autorisation USB accordée.");
                    startServiceNow();
                } else {
                    report("Autorisation USB non accordée.");
                }
            } else if (OverlayCameraService.ACTION_STATUS.equals(action)) {
                report(intent.getStringExtra(OverlayCameraService.EXTRA_MESSAGE));
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                report("Périphérique USB connecté.");
                scan();
            }
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usb = (UsbManager)getSystemService(USB_SERVICE);
        readSettings();
        buildUi();
        IntentFilter usbIntent = new IntentFilter(USB_PERMISSION_ACTION);
        usbIntent.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(events, usbIntent, Context.RECEIVER_EXPORTED);
        else registerReceiver(events, usbIntent);
        IntentFilter statusIntent = new IntentFilter(OverlayCameraService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(statusReceiver, statusIntent, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, statusIntent);
        IntentFilter calibrationFilter =
            new IntentFilter(OverlayCameraService.ACTION_CALIBRATION_FRAME);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(calibrationReceiver, calibrationFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(calibrationReceiver, calibrationFilter);
        calibrationReceiverRegistered = true;
        receiversRegistered = true;
        initialized = true;
        showSettings();
        report("Android " + Build.VERSION.RELEASE + " — " + Build.MODEL);
        report("SDK Viture : " + (NativeCameraBridge.available() ?
            "bibliothèque chargée" : NativeCameraBridge.loadError()));
        report("Les touches du téléphone ne sont pas modifiées.");
        report("Vidéo locale uniquement : aucun réseau ni enregistrement.");
        scan();
    }

    private void buildUi() {
        ScrollView scroller = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xff101827);
        page.setPadding(dp(16), dp(20), dp(16), dp(18));
        scroller.addView(page);
        setContentView(scroller);

        TextView title = new TextView(this);
        title.setText("BEAST FINGERCAM  v0.8");
        title.setTextSize(23);
        title.setTextColor(0xffffffff);
        page.addView(title);

        TextView introduction = new TextView(this);
        introduction.setText("Bouts des doigts UNIQUEMENT, sans fond ni mains complètes. " +
            "Calibrage de la zone de l'écran physique indispensable avant affichage.");
        introduction.setTextSize(14);
        introduction.setTextColor(0xffb8d4ee);
        page.addView(introduction);

        LinearLayout buttons = new LinearLayout(this);
        page.addView(buttons);
        Button scan = new Button(this);
        scan.setText("DÉTECTER");
        buttons.addView(scan, new LinearLayout.LayoutParams(0, dp(52), 1));
        scan.setOnClickListener(v -> scan());
        Button start = new Button(this);
        start.setText("DÉMARRER");
        buttons.addView(start, new LinearLayout.LayoutParams(0, dp(52), 1));
        start.setOnClickListener(v -> requestStart());
        Button stop = new Button(this);
        stop.setText("ARRÊTER");
        buttons.addView(stop, new LinearLayout.LayoutParams(0, dp(52), 1));
        stop.setOnClickListener(v -> stopOverlay());

        Button calibrate = new Button(this);
        calibrate.setText("CALIBRER L'ÉCRAN DU TÉLÉPHONE");
        page.addView(calibrate);
        calibrate.setOnClickListener(v -> {
            if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("running", false)) {
                report("Appuie d'abord sur DÉMARRER puis sur CALIBRER.");
                return;
            }
            report("Capture locale de calibration demandée.");
            try {
                startService(new Intent(this, OverlayCameraService.class)
                    .setAction(OverlayCameraService.ACTION_SNAPSHOT));
            } catch (RuntimeException e) { report("Calibration : " + e); }
        });
        TextView calibrationHelp = label(
            "Après le démarrage, regarde le téléphone en le laissant visible " +
            "depuis les lunettes. Sur la photo, touche les quatre coins de sa VITRE " +
            "dans l'ordre : haut gauche, haut droit, bas droit, bas gauche.");
        page.addView(calibrationHelp);

        TextView modeTitle = label("Afficher la surimpression sur");
        page.addView(modeTitle);
        screenSpinner = new Spinner(this);
        String[] modes = {
            "Téléphone / miroir (écran #0)",
            "VITURE Beast (écran externe)"
        };
        ArrayAdapter<String> modesAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, modes);
        screenSpinner.setAdapter(modesAdapter);
        screenSpinner.setSelection(target);
        page.addView(screenSpinner);
        screenSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                    View view, int position, long id) {
                target = position;
                onSettingChanged();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        alphaSlider = addSlider(page, "OPACITÉ", 10, 75, alpha,
            value -> { alpha = value; onSettingChanged(); });
        scaleSlider = addSlider(page, "TAILLE", 40, 140, scale,
            value -> { scale = value; onSettingChanged(); });
        xSlider = addSlider(page, "POSITION X (droite +)", -500, 500, posX,
            value -> { posX = value; onSettingChanged(); });
        ySlider = addSlider(page, "POSITION Y (haut +)", -500, 500, posY,
            value -> { posY = value; onSettingChanged(); });

        mirrorCheck = new CheckBox(this);
        mirrorCheck.setText("Inverser la vidéo horizontalement");
        mirrorCheck.setTextColor(0xffffffff);
        mirrorCheck.setChecked(mirror);
        page.addView(mirrorCheck);
        mirrorCheck.setOnCheckedChangeListener((buttonView, checked) -> {
            mirror = checked;
            onSettingChanged();
        });

        settingsSummary = new TextView(this);
        settingsSummary.setTextSize(13);
        settingsSummary.setTextColor(0xffa9e1ad);
        page.addView(settingsSummary);

        TextView help = new TextView(this);
        help.setText("MODE D'EMPLOI\n" +
            "1. Branche les Beast. Autorise USB / caméra / surimpression puis DÉMARRER.\n" +
            "2. Touche CALIBRER puis les quatre coins de l'écran du téléphone sur la photo.\n" +
            "3. Ouvre le clavier Android natif. Seuls les bouts des doigts dans la zone " +
            "de l'écran physique apparaissent en transparence.\n" +
            "4. Si tu bouges la tête ou le téléphone, calibre à nouveau.\n" +
            "5. ARRÊTER fonctionne aussi dans la notification.\n\n" +
            "Caméra monoculaire : cette version estime la zone 2D et ne peut pas " +
            "prouver un contact physique ou mesurer exactement la hauteur du doigt.");
        help.setTextColor(0xffb8cde1);
        help.setTextSize(13);
        page.addView(help);

        TextView logTitle = label("DIAGNOSTIC");
        page.addView(logTitle);
        status = new TextView(this);
        status.setTextColor(0xff91f7ba);
        status.setTextSize(12);
        status.setTextIsSelectable(true);
        page.addView(status);

        Button copy = new Button(this);
        copy.setText("COPIER LE DIAGNOSTIC");
        page.addView(copy);
        copy.setOnClickListener(v -> {
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Beast FingerCam v0.7", log.toString()));
            report("Diagnostic copié.");
        });
    }

    private void showCalibration(Bitmap snapshot) {
        final android.app.Dialog[] dialogHolder = new android.app.Dialog[1];
        PhoneCalibrationView surface = new PhoneCalibrationView(this, snapshot, pts -> {
            android.content.SharedPreferences.Editor editor =
                getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            for (int i=0;i<pts.length;i++) editor.putFloat("phone_quad_"+i,pts[i]);
            editor.putBoolean("phone_quad_ready",true).apply();
            report("Zone calibrée : 4 coins enregistrés. Filtrage des bouts de doigts actif.");
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
        });
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialogHolder[0] = dialog;
        int height = getResources().getDisplayMetrics().heightPixels*4/5;
        surface.setMinimumHeight(height);
        dialog.setTitle("Écran physique : 4 coins à toucher");
        dialog.setContentView(surface,new android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,height));
        dialog.setOnDismissListener(ignored -> { if(!snapshot.isRecycled()) snapshot.recycle(); });
        dialog.show();
        if (dialog.getWindow()!=null) dialog.getWindow().setLayout(
            getResources().getDisplayMetrics().widthPixels, height+dp(50));
    }

    private interface SliderListener { void changed(int value); }
    private SeekBar addSlider(LinearLayout parent, String title,
            int minimum, int maximum, int current, SliderListener listener) {
        TextView text = label(title);
        parent.addView(text);
        SeekBar seek = new SeekBar(this);
        seek.setMax(maximum-minimum);
        seek.setProgress(current-minimum);
        parent.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                listener.changed(progress + minimum);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        return seek;
    }
    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xffffffff);
        t.setTextSize(14);
        t.setPadding(0, dp(11), 0, 0);
        return t;
    }
    private int dp(int v) {
        return (int)(getResources().getDisplayMetrics().density*v+0.5f);
    }

    private void readSettings() {
        android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        alpha = p.getInt("alpha", 45);
        scale = p.getInt("scale", 80);
        posX = p.getInt("x", 0);
        posY = p.getInt("y", 0);
        target = p.getInt("target", 0);
        mirror = p.getBoolean("mirror", false);
    }
    private void onSettingChanged() {
        if (!initialized) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("alpha", alpha).putInt("scale", scale)
            .putInt("x", posX).putInt("y", posY).putInt("target", target)
            .putBoolean("mirror", mirror).apply();
        showSettings();
        // Only deliver adjustments to an already-running overlay.
        // Intent with ACTION_CONFIG does not launch a new service.
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("running", false)) {
            Intent values = buildServiceIntent(OverlayCameraService.ACTION_CONFIG);
            try { startService(values); } catch (RuntimeException e) {
                report("Réglages non transmis : " + e.getMessage());
            }
        }
    }
    private void showSettings() {
        if (settingsSummary == null) return;
        settingsSummary.setText("Opacité : " + alpha + "%  |  Taille : " + scale +
            "%  |  X : " + posX + "  |  Y : " + posY +
            "  |  Écran : " + (target == 0 ? "téléphone" : "Beast"));
    }
    private Intent buildServiceIntent(String action) {
        return new Intent(this, OverlayCameraService.class).setAction(action)
            .putExtra(OverlayCameraService.EXTRA_ALPHA, alpha)
            .putExtra(OverlayCameraService.EXTRA_SCALE, scale)
            .putExtra(OverlayCameraService.EXTRA_X, posX)
            .putExtra(OverlayCameraService.EXTRA_Y, posY)
            .putExtra(OverlayCameraService.EXTRA_FLIP, mirror)
            .putExtra(OverlayCameraService.EXTRA_TARGET, target);
    }

    private void report(String message) {
        if (message == null) return;
        main.post(() -> {
            log.append(message).append('\n');
            if (log.length() > 14000) log.delete(0, 4000);
            if (status != null) status.setText(log.toString());
        });
    }
    private static boolean isCamera(UsbDevice d) {
        return d != null && d.getVendorId() == 0x0C45 && d.getProductId() == 0x6368;
    }
    private UsbDevice findCamera() {
        for (UsbDevice dev : usb.getDeviceList().values()) {
            if (isCamera(dev)) return dev;
        }
        return null;
    }
    private void scan() {
        report("— PÉRIPHÉRIQUES USB —");
        for (UsbDevice d : usb.getDeviceList().values()) {
            report(String.format(Locale.US, "USB %04X:%04X / autorisation=%s",
                d.getVendorId(), d.getProductId(), usb.hasPermission(d)));
        }
        report(findCamera() != null ? "Caméra Beast détectée !" : "Caméra Beast non connectée.");
        DisplayManager dm = (DisplayManager)getSystemService(DISPLAY_SERVICE);
        for (android.view.Display d : dm.getDisplays())
            report("Écran #" + d.getDisplayId() + " : " + d.getName());
        report("Overlay Android : " + (Settings.canDrawOverlays(this) ?
            "autorisé" : "autorisation requise"));
    }
    private void requestStart() {
        if (!NativeCameraBridge.available()) {
            report("Erreur de chargement SDK : " + NativeCameraBridge.loadError());
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            report("Autorise l'affichage par-dessus les autres applications.");
            Intent overlayPage = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivity(overlayPage);
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 64);
            return;
        }
        UsbDevice camera = findCamera();
        if (camera == null) {
            report("Caméra USB introuvable. Branche les Beast et réessaie.");
            scan();
            return;
        }
        if (!usb.hasPermission(camera)) {
            report("Accepte la permission USB de la caméra.");
            Intent i = new Intent(USB_PERMISSION_ACTION).setPackage(getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(this, 0, i,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            usb.requestPermission(camera, pending);
            return;
        }
        startServiceNow();
    }
    private void startServiceNow() {
        Intent i = buildServiceIntent(OverlayCameraService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            report("Démarrage de la caméra flottante…");
        } catch (RuntimeException e) {
            report("Démarrage refusé : " + e);
        }
    }
    private void stopOverlay() {
        try {
            stopService(new Intent(this, OverlayCameraService.class));
            report("Demande d'arrêt du service caméra.");
        } catch (RuntimeException e) {
            report("Erreur d'arrêt : " + e);
        }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 64) {
            if (grants.length != 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
                requestStart();
            else report("Autorisation caméra refusée.");
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (initialized && status != null) showSettings();
    }
    @Override protected void onDestroy() {
        if (receiversRegistered) {
            unregisterReceiver(events);
            unregisterReceiver(statusReceiver);
        }
        if (calibrationReceiverRegistered) unregisterReceiver(calibrationReceiver);
        super.onDestroy();
    }
}
