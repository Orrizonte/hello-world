package fr.externalscreen.beastfingerdiagnostic;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private UsbManager usb;
    private CameraManager cameras;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private Surface surface;
    private String chosenId;
    private TextureView preview;
    private TextView logView;
    private final StringBuilder history = new StringBuilder();
    private boolean opening;

    private final CameraDevice.StateCallback state = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice c) {
            camera = c; opening = false;
            report("Camera2 : caméra ouverte " + c.getId());
            beginPreview(c);
        }
        @Override public void onDisconnected(CameraDevice c) {
            report("Caméra déconnectée.");
            c.close(); camera = null; opening = false;
        }
        @Override public void onError(CameraDevice c, int code) {
            report("Camera2 : erreur d'ouverture " + code);
            c.close(); camera = null; opening = false;
        }
    };
    private final TextureView.SurfaceTextureListener textureEvents =
        new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
                if (camera == null && chosenId != null && !opening) openSelected();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
                stopCamera(); return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture t) { }
        };
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        cameras = (CameraManager) getSystemService(CAMERA_SERVICE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff101724);
        root.setPadding(dp(14), dp(16), dp(14), dp(10));
        TextView title = new TextView(this);
        title.setText("BEAST FINGERCAM — TEST CAMÉRA 0.4");
        title.setTextColor(-1);
        title.setTextSize(19);
        root.addView(title);
        TextView info = new TextView(this);
        info.setText("Diagnostic Android Camera2. Tes doigts et le clavier ne sont pas encore superposés. L'application n'utilise pas la bibliothèque propriétaire Viture.");
        info.setTextColor(0xffa8d4f5);
        root.addView(info);
        LinearLayout buttons = new LinearLayout(this);
        root.addView(buttons);
        Button scan = new Button(this);
        scan.setText("DÉTECTER");
        buttons.addView(scan, new LinearLayout.LayoutParams(0, dp(54), 1));
        scan.setOnClickListener(v -> scan());
        Button start = new Button(this);
        start.setText("DÉMARRER");
        buttons.addView(start, new LinearLayout.LayoutParams(0, dp(54), 1));
        start.setOnClickListener(v -> startCamera());
        Button stop = new Button(this);
        stop.setText("ARRÊTER");
        buttons.addView(stop, new LinearLayout.LayoutParams(0, dp(54), 1));
        stop.setOnClickListener(v -> stopCamera());
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(textureEvents);
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 6));
        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextColor(0xff81f6af);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 4));
        Button copy = new Button(this);
        copy.setText("COPIER LE DIAGNOSTIC");
        root.addView(copy);
        copy.setOnClickListener(v -> {
            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
              .setPrimaryClip(ClipData.newPlainText("Beast FingerCam diagnostic", history.toString()));
            report("Diagnostic copié.");
        });
        setContentView(root);
        report("Android " + Build.VERSION.RELEASE + " / " + Build.MODEL);
        report("Application sans service réseau ni enregistrement vidéo.");
        scan();
    }
    private int dp(int x) { return (int)(x * getResources().getDisplayMetrics().density + 0.5f); }
    private void report(String text) {
        ui.post(() -> {
            history.append(text).append('\n');
            if (history.length() > 14000) history.delete(0, 4000);
            if (logView != null) logView.setText(history.toString());
        });
    }
    private String detectExternal() {
        String firstExternal = null;
        try {
            for (String id : cameras.getCameraIdList()) {
                CameraCharacteristics c = cameras.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                boolean external = facing != null &&
                    facing == CameraCharacteristics.LENS_FACING_EXTERNAL;
                report("Camera2 ID " + id + " / direction " + facing +
                    (external ? " / EXTERNE" : ""));
                if (external && firstExternal == null) firstExternal = id;
            }
        } catch (Exception e) {
            report("Erreur de liste Camera2 : " + e.toString());
        }
        return firstExternal;
    }
    private void scan() {
        report("— Périphériques USB —");
        boolean found = false;
        for (UsbDevice d : usb.getDeviceList().values()) {
            String line = String.format(java.util.Locale.US, "USB %04X:%04X / %s",
                d.getVendorId(), d.getProductId(), d.getDeviceName());
            report(line);
            if (d.getVendorId() == 0x0C45 && d.getProductId() == 0x6368) found = true;
        }
        report(found ? "Camera Beast 0C45:6368 trouvée." :
            "Camera Beast non visible en USB (il peut s'agir des autorisations).");
        chosenId = detectExternal();
        report(chosenId != null ? "Caméra externe Camera2 disponible : " + chosenId :
            "Aucune caméra externe Camera2. Il faudra le SDK USB Viture pour la vidéo.");
    }
    private void startCamera() {
        if (camera != null || opening) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 61);
            return;
        }
        chosenId = detectExternal();
        if (chosenId == null) {
            report("Impossible de démarrer : aucune caméra EXTERNE publiée par Android.");
            return;
        }
        openSelected();
    }
    private void openSelected() {
        if (chosenId == null || !preview.isAvailable() || camera != null || opening) return;
        try {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                report("Autorisation caméra manquante."); return;
            }
            opening = true;
            cameras.openCamera(chosenId, state, ui);
            report("Demande d'ouverture Camera2 : " + chosenId);
        } catch (Exception e) {
            opening = false;
            report("Impossible d'ouvrir : " + e.toString());
        }
    }
    private void beginPreview(CameraDevice c) {
        try {
            CameraCharacteristics cc = cameras.getCameraCharacteristics(c.getId());
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = new Size(640, 480);
            if (map != null) {
                Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                if (sizes != null && sizes.length > 0) {
                    best = sizes[0];
                    long score = Long.MAX_VALUE;
                    for (Size size : sizes) {
                        long delta = Math.abs(size.getWidth()-1280L) +
                            Math.abs(size.getHeight()-720L);
                        if (delta < score) { best = size; score = delta; }
                    }
                }
            }
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null) { report("Texture non disponible."); return; }
            texture.setDefaultBufferSize(best.getWidth(), best.getHeight());
            surface = new Surface(texture);
            CaptureRequest.Builder request = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(surface);
            Size chosenSize = best;
            c.createCaptureSession(Collections.singletonList(surface),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) {
                        session = s;
                        try {
                            s.setRepeatingRequest(request.build(), null, ui);
                            report("VIDÉO ACTIVE : " + chosenSize.getWidth() + "x" + chosenSize.getHeight());
                            report("Place tes doigts sur le clavier du téléphone et regarde l'aperçu.");
                        } catch (Exception e) { report("Aperçu : " + e.toString()); }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                        report("Camera2 : configuration du flux refusée.");
                    }
                }, ui);
        } catch (Exception e) { report("Création vidéo : " + e.toString()); }
    }
    private void stopCamera() {
        if (session != null) { session.close(); session = null; }
        if (surface != null) { surface.release(); surface = null; }
        if (camera != null) { camera.close(); camera = null; }
        opening = false;
        report("Caméra arrêtée.");
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 61) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startCamera();
            else report("Autorisation caméra refusée.");
        }
    }
    @Override protected void onDestroy() { stopCamera(); super.onDestroy(); }
}
