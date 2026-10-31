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
            Bitmap full=CalibrationFrameHolder.peek();
            if(full==null || full.isRecycled()){
                report("Capture de calibration absente ; recommence.");
                return;
            }
            showCameraDiagnostic(full);
        }
    };
    private boolean calibrationReceiverRegistered;
    private boolean initialized;
    private int alpha, scale, posX, posY, target;
    private boolean mirror;
    private static final String PREFS = "settings.v084";

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
        title.setText("BEAST FINGERCAM  v0.8.4");
        title.setTextSize(23);
        title.setTextColor(0xffffffff);
        page.addView(title);

        TextView introduction = new TextView(this);
        introduction.setText("UNIQUEMENT les doigts au-dessus de l'écran physique " +
            "du téléphone. La détection directe de peau en mouvement fonctionne " +
            "même si la paume est hors champ. Aucun calibrage.");
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

        TextView automatic=label("AUTOMATIQUE : La caméra des Beast détecte " +
            "le téléphone et uniquement les bouts de doigts au-dessus de son écran. " +
            "Aucun calibrage manuel, aucun geste simulé. " +
            "Si le téléphone sort du champ, les doigts disparaissent.");
        automatic.setTextColor(0xffa4ffd8);
        page.addView(automatic);

        TextView modeTitle = label("Afficher la surimpression sur");
        page.addView(modeTitle);
        screenSpinner = new Spinner(this);
        String[] modes = {
            "Téléphone / miroir (RECOMMANDÉ)",
            "VITURE Beast / écran externe indépendant (test)"
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
        scaleSlider = addSlider(page, "TAILLE DES BOUTS DE DOIGTS", 40, 180, scale,
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
            "1. Branche les Beast, puis DÉMARRER.\n" +
            "2. Ouvre le clavier Android natif et tape normalement.\n" +
            "3. Les doigts apparaissent uniquement lorsque le téléphone et " +
            "leur silhouette sont visibles dans le champ de la caméra.\n" +
            "4. Détection en temps réel, sans calibrage ni main entière à montrer.\n" +
            "5. En cas d'écran vide, le bouton photo de DIAGNOSTIC montre " +
            "ponctuellement ce que filme la caméra.\n\n" +
            "Lecture du diagnostic : téléphone=OUI signifie écran localisé ; " +
            "doigt peau>0 et peau mobile>0 indiquent des pixels candidats ; " +
            "zones doigts>0 signifie que des fragments ont été isolés. " +
            "Une caméra RGB ne peut pas mesurer la distance exacte à la vitre.");
        help.setTextColor(0xffb8cde1);
        help.setTextSize(13);
        page.addView(help);

        TextView logTitle = label("DIAGNOSTIC");
        page.addView(logTitle);
        Button preview=new Button(this);
        preview.setText("VÉRIFIER LE CHAMP DE LA CAMÉRA (PHOTO)");
        page.addView(preview);
        preview.setOnClickListener(v->{
            if(!getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("running",false)){
                report("Démarre d'abord la caméra.");
                return;
            }
            try{
                startService(new Intent(this,OverlayCameraService.class)
                    .setAction(OverlayCameraService.ACTION_SNAPSHOT));
            }catch(RuntimeException e){
                report("Impossible de voir la caméra : "+e.getMessage());
            }
        });
        TextView previewHelp=label("Ce bouton sert uniquement au diagnostic : " +
            "photo temporaire de ce que voient les Beast. Rien n'est sauvegardé. " +
            "Il n'affiche pas la vidéo par-dessus le clavier.");
        page.addView(previewHelp);
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

    private void showCameraDiagnostic(Bitmap snapshot){
        ImageView preview=new ImageView(this);
        preview.setImageBitmap(snapshot);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setAdjustViewBounds(true);
        preview.setBackgroundColor(android.graphics.Color.BLACK);
        int height=Math.max(dp(190),getResources().getDisplayMetrics().heightPixels/2);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);
        scroll.addView(preview,new android.widget.ScrollView.LayoutParams(
            android.widget.ScrollView.LayoutParams.MATCH_PARENT,height));
        new AlertDialog.Builder(this)
            .setTitle("Caméra des Beast — photo de diagnostic")
            .setMessage("Vérifie si les doigts au-dessus de l'écran sont visibles " +
                "dans cette photo. Elle n'est pas enregistrée.")
            .setView(scroll)
            .setPositiveButton("FERMER",(dialog,which)->{})
            .setOnDismissListener(dialog->{
                preview.setImageDrawable(null);
                CalibrationFrameHolder.clear(snapshot);
            })
            .show();
        report("Photo caméra affichée : "+snapshot.getWidth()+"×"+
            snapshot.getHeight()+" (aucun calibrage nécessaire).");
    }

    private void showCalibration(Bitmap snapshot) {
        final android.app.Dialog[] controllerDialog=new android.app.Dialog[1];
        final android.app.Presentation[] beastPresentation=new android.app.Presentation[1];
        final PhoneCalibrationView[] beastPreview=new PhoneCalibrationView[1];

        PhoneCalibrationView controller=new PhoneCalibrationView(this,snapshot,pts->{
            android.content.SharedPreferences.Editor saved=
                getSharedPreferences(PREFS,MODE_PRIVATE).edit();
            for(int i=0;i<pts.length;i++)saved.putFloat("phone_quad_"+i,pts[i]);
            saved.putBoolean("phone_quad_ready",true).apply();
            report("Calibrage validé : quatre coins de l'écran enregistrés (source "+
                snapshot.getWidth()+"×"+snapshot.getHeight()+").");
            if(controllerDialog[0]!=null)controllerDialog[0].dismiss();
        });
        controller.setProgressListener((points,completed)->{
            if(beastPreview[0]!=null)beastPreview[0].setPreviewPoints(points,completed);
            report("Calibration : coin "+completed+"/4 sélectionné.");
        });

        // The phone remains the touch controller. In the glasses, show the
        // SAME native 16:9 camera image fitted to the real external display.
        try {
            DisplayManager dm=(DisplayManager)getSystemService(DISPLAY_SERVICE);
            android.view.Display external=null;
            for(android.view.Display d:dm.getDisplays()){
                if(d.getDisplayId()==android.view.Display.DEFAULT_DISPLAY)continue;
                if(external==null)external=d;
                if(d.getName().toLowerCase(java.util.Locale.ROOT).contains("viture")){
                    external=d;break;
                }
            }
            if(external!=null){
                android.app.Presentation p=new android.app.Presentation(this,external);
                PhoneCalibrationView projected=
                    new PhoneCalibrationView(p.getContext(),snapshot,null);
                p.getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
                p.setContentView(projected);
                p.show();
                if(p.getWindow()!=null){
                    p.getWindow().setLayout(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT);
                }
                beastPresentation[0]=p;
                beastPreview[0]=projected;
                android.graphics.Point screen=new android.graphics.Point();
                external.getRealSize(screen);
                report("CALIBRAGE PROJETÉ SUR BEAST : sortie "+screen.x+"×"+
                    screen.y+" ; source caméra "+snapshot.getWidth()+"×"+
                    snapshot.getHeight()+" (pas de réduction).");
            }else report("Pas d'écran externe : calibration sur téléphone.");
        }catch(RuntimeException error){
            report("Présentation sur Beast indisponible : "+error+
                ". Calibrage possible sur le téléphone.");
        }

        android.app.Dialog dialog=new android.app.Dialog(this);
        controllerDialog[0]=dialog;
        int width=getResources().getDisplayMetrics().widthPixels;
        int height=getResources().getDisplayMetrics().heightPixels*9/10;
        controller.setMinimumHeight(height);
        dialog.setTitle("Calibrer la zone du téléphone — 4 coins");
        dialog.setContentView(controller,new android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,height));
        dialog.setOnDismissListener(ignored->{
            if(beastPresentation[0]!=null){
                try{beastPresentation[0].dismiss();}catch(RuntimeException ignored2){}
            }
            CalibrationFrameHolder.clear(snapshot);
            report("Calibrage fermé : retour à la projection normale.");
        });
        dialog.show();
        if(dialog.getWindow()!=null)dialog.getWindow().setLayout(width,height+dp(45));
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
        alpha = p.getInt("alpha", 65);
        scale = p.getInt("scale", 110);
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
