from pathlib import Path
import shutil
import base64

base=Path("upstream")
app=base/"app"
src=app/"src/main"
for child in ("java","aidl","cpp","res"):
    p=src/child
    if p.exists(): shutil.rmtree(p)
pkg=src/"java/fr/externalscreen/gamepadpointer"
pkg.mkdir(parents=True,exist_ok=True)
res=src/"res/xml"
res.mkdir(parents=True,exist_ok=True)

(app/"build.gradle.kts").write_text(r'''
plugins {
    alias(libs.plugins.android.application)
}
android {
    namespace = "fr.externalscreen.gamepadpointer"
    compileSdk = 35
    defaultConfig {
        applicationId = "fr.externalscreen.gamepadpointer.noshizuku"
        minSdk = 34
        targetSdk = 35
        versionCode = 3
        versionName = "0.4.1-noshizuku"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
''')
(src/"AndroidManifest.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="Manette – Écran externe v0.4"
        android:icon="@drawable/app_icon"
        android:roundIcon="@drawable/app_icon"
        android:allowBackup="false" android:theme="@android:style/Theme.Material.NoActionBar"
        android:supportsRtl="true">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
        <service android:name=".ControllerService" android:exported="true"
            android:label="Curseur manette sur ecran externe"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService"/>
            </intent-filter>
            <meta-data android:name="android.accessibilityservice"
                android:resource="@xml/controller_service"/>
        </service>
    </application>
</manifest>
''')
(res/"controller_service.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:canRequestFilterKeyEvents="true"
    android:canPerformGestures="true"
    android:accessibilityFlags="flagRequestFilterKeyEvents"
    android:description="@string/service_description"/>
''')
values=src/"res/values"
values.mkdir(exist_ok=True)
(values/"strings.xml").write_text("""<?xml version="1.0" encoding="utf-8"?>
<resources><string name="service_description">Transforme une manette Android en curseur virtuel sur un ecran externe. Utilise les fonctions Android, sans Shizuku.</string></resources>
""")

(pkg/"MainActivity.java").write_text(r'''package fr.externalscreen.gamepadpointer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Display;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String PREFS="cursor_settings";
    TextView status;
    SharedPreferences prefs;

    private final String[] colorNames={"Blanc","Noir","Rouge","Vert","Bleu","Jaune","Cyan","Magenta"};
    private final int[] colorValues={Color.WHITE,Color.BLACK,Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW,Color.CYAN,Color.MAGENTA};
    private final String[] timeoutNames={"Jamais","2 secondes","5 secondes","10 secondes","30 secondes"};
    private final long[] timeoutValues={0L,2000L,5000L,10000L,30000L};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences(PREFS,MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(18,24,32));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(22);
        root.setPadding(p,p,p,p);
        scroll.addView(root);

        add(root, "MANETTE - ECRAN EXTERNE", 25, 0xff8cd9ff);
        add(root, "Version 0.4 - Sans Shizuku", 16, Color.WHITE);
        add(root, "Le curseur est dessine sur l'ecran externe et les reglages ci-dessous sont appliques immediatement au service.", 15, 0xffdddddd);
        status = add(root, "", 15, 0xff99f0b2);

        add(root, "REGLAGES DU CURSEUR", 19, 0xff8cd9ff);

        int currentSize=prefs.getInt("cursor_size_dp",20);
        TextView sizeLabel=add(root, "Taille : "+currentSize+" dp", 16, Color.WHITE);
        SeekBar sizeBar=new SeekBar(this);
        sizeBar.setMax(36); // 12..48 dp
        sizeBar.setProgress(Math.max(0,Math.min(36,currentSize-12)));
        sizeBar.setPadding(0,0,0,dp(14));
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar,int progress,boolean fromUser) {
                int value=12+progress;
                sizeLabel.setText("Taille : "+value+" dp");
                if(fromUser) prefs.edit().putInt("cursor_size_dp",value).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(sizeBar);

        add(root, "Couleur", 16, Color.WHITE);
        Spinner colorSpinner=new Spinner(this);
        ArrayAdapter<String> colors=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,colorNames);
        colorSpinner.setAdapter(colors);
        colorSpinner.setSelection(colorIndex(prefs.getInt("cursor_color",Color.WHITE)));
        colorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,int position,long id) {
                prefs.edit().putInt("cursor_color",colorValues[position]).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(colorSpinner);

        add(root, "Disparition apres inactivite", 16, Color.WHITE);
        Spinner timeoutSpinner=new Spinner(this);
        ArrayAdapter<String> timeouts=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,timeoutNames);
        timeoutSpinner.setAdapter(timeouts);
        timeoutSpinner.setSelection(timeoutIndex(prefs.getLong("cursor_hide_ms",5000L)));
        timeoutSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,int position,long id) {
                prefs.edit().putLong("cursor_hide_ms",timeoutValues[position]).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(timeoutSpinner);

        add(root, "Le curseur reapparait des que vous bougez le joystick ou utilisez une commande souris.", 14, 0xffdddddd);

        Button settings = new Button(this);
        settings.setText("Activer le service d'accessibilite");
        settings.setOnClickListener(v ->
             startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(settings);

        add(root, "Commandes en mode souris :\nJoystick gauche : curseur\nA : clic (appui tactile)\nB : Retour\nX : appui long (alternative au clic droit)\nJoystick droit : defilement par geste\nR1 : double clic\nSELECT + START : mode souris ON/OFF", 16, Color.WHITE);
        add(root, "Important : en mode souris, les directions du joystick sont interceptees. Pour rejouer normalement, desactivez le service dans Accessibilite ou utilisez SELECT + START.", 14, 0xffdddddd);
        setContentView(scroll);
    }

    private int colorIndex(int value) {
        for(int i=0;i<colorValues.length;i++) if(colorValues[i]==value)return i;
        return 0;
    }
    private int timeoutIndex(long value) {
        for(int i=0;i<timeoutValues.length;i++) if(timeoutValues[i]==value)return i;
        return 2;
    }

    @Override protected void onResume() {
        super.onResume();
        Display[] ds = getSystemService(DisplayManager.class).getDisplays();
        int other=0;
        StringBuilder s = new StringBuilder();
        for(Display d:ds) if(d.getDisplayId()!=Display.DEFAULT_DISPLAY) {
            other++;
            s.append("\nEcran externe : ").append(d.getName())
             .append(" (ID ").append(d.getDisplayId()).append(")");
        }
        boolean active = ControllerService.active;
        status.setText("Service : " + (active ? "actif" : "a activer dans Accessibilite")
          + "\nEcrans externes detectes : " + other + s);
    }

    private int dp(int v) { return (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,v,
            getResources().getDisplayMetrics()); }
    private TextView add(LinearLayout root,String text,int sp,int color) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextColor(color); t.setTextSize(sp);
        t.setPadding(0,0,0,dp(18));
        root.addView(t);
        return t;
    }
}
''')

(pkg/"ControllerService.java").write_text(r'''package fr.externalscreen.gamepadpointer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * No Shizuku / no root: accessibility gesture injection on the external display,
 * with a configurable drawn accessibility overlay cursor.
 */
public class ControllerService extends AccessibilityService {
    private static final String TAG="ControllerNoShizuku";
    private static final String PREFS="cursor_settings";
    public static volatile boolean active=false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DisplayManager dm;
    private Display target;
    private int targetId=-1,w=1920,h=1080;
    private WindowManager wm;
    private WindowManager.LayoutParams overlayParams;
    private View cursorView;
    private float cursorX=960f,cursorY=540f,lx=0f,ly=0f,ry=0f;
    private boolean enabled=true, selectHeld=false;
    private long lastScroll=0L;
    private boolean captureEnabled=false;

    private SharedPreferences prefs;
    private int cursorSizeDp=20;
    private int cursorColor=Color.WHITE;
    private long hideDelayMs=5000L;
    private long lastCursorActivity=0L;
    private boolean cursorVisible=true;
    private float cursorDensity=1f;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener=(sp,key)->{
        if("cursor_size_dp".equals(key) || "cursor_color".equals(key) || "cursor_hide_ms".equals(key)) {
            loadSettings();
            applyCursorSettings();
        }
    };

    private final DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { updateTarget(); }
        @Override public void onDisplayRemoved(int id) { updateTarget(); }
        @Override public void onDisplayChanged(int id) { updateTarget(); }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        active=true;
        prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        loadSettings();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        dm = getSystemService(DisplayManager.class);
        dm.registerDisplayListener(listener,handler);
        updateTarget();
        lastCursorActivity=SystemClock.uptimeMillis();
        handler.post(tick);
        handler.postDelayed(checkDisplays,1800);
    }

    private void loadSettings() {
        if(prefs==null)return;
        cursorSizeDp=Math.max(12,Math.min(48,prefs.getInt("cursor_size_dp",20)));
        cursorColor=prefs.getInt("cursor_color",Color.WHITE);
        hideDelayMs=Math.max(0L,prefs.getLong("cursor_hide_ms",5000L));
    }

    private final Runnable checkDisplays = new Runnable() {
        @Override public void run() {
            updateTarget();
            handler.postDelayed(this,1800);
        }
    };

    private void updateTarget() {
        if(dm==null) return;
        Display next=null;
        for(Display display:dm.getDisplays()) {
            if(display.getDisplayId()!=Display.DEFAULT_DISPLAY) {next=display; break;}
        }
        int nextId=next==null ? -1 : next.getDisplayId();
        if(targetId!=nextId || (next!=null && cursorView==null)) {
            removeCursor();
            target=next;
            targetId=nextId;
            if(next!=null) {
                Point size=new Point();
                next.getRealSize(size);
                w=Math.max(size.x,1); h=Math.max(size.y,1);
                cursorX=w/2f; cursorY=h/2f;
                addCursor(next);
            } else {
                lx=ly=ry=0;
            }
        }
        updateCapture();
    }

    private void updateCapture() {
        boolean should = enabled && targetId>=0 && cursorView!=null;
        if(should==captureEnabled) {
            if(cursorView!=null) {
                if(enabled) showCursor(); else hideCursor();
            }
            return;
        }
        AccessibilityServiceInfo info=getServiceInfo();
        if(info==null)return;
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.setMotionEventSources(should ? InputDevice.SOURCE_JOYSTICK : 0);
        setServiceInfo(info);
        captureEnabled=should;
        if(enabled) showCursor(); else hideCursor();
    }

    private void addCursor(Display display) {
        try {
            Context dc=createDisplayContext(display);
            Context wc=dc.createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,null);
            wm=wc.getSystemService(WindowManager.class);
            cursorDensity=wc.getResources().getDisplayMetrics().density;
            final int size=Math.max(1,Math.round(cursorSizeDp*cursorDensity));

            cursorView=new View(wc) {
                final Paint paint = new Paint(3);
                @Override protected void onDraw(Canvas c) {
                    super.onDraw(c);
                    float unit=getWidth()/40f;
                    Path p=new Path();
                    p.moveTo(5*unit,2*unit);
                    p.lineTo(5*unit,32*unit);
                    p.lineTo(13*unit,25*unit);
                    p.lineTo(20*unit,38*unit);
                    p.lineTo(27*unit,35*unit);
                    p.lineTo(20*unit,22*unit);
                    p.lineTo(32*unit,21*unit);
                    p.close();

                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(cursorColor);
                    c.drawPath(p,paint);

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeJoin(Paint.Join.ROUND);
                    paint.setStrokeWidth(Math.max(1.2f,1.7f*unit));
                    paint.setColor(isBright(cursorColor) ? Color.BLACK : Color.WHITE);
                    c.drawPath(p,paint);
                }
            };

            overlayParams=new WindowManager.LayoutParams(size,size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                      WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            overlayParams.gravity=Gravity.TOP|Gravity.LEFT;
            overlayParams.x=(int)cursorX;
            overlayParams.y=(int)cursorY;
            wm.addView(cursorView,overlayParams);
            cursorVisible=true;
            lastCursorActivity=SystemClock.uptimeMillis();
            Log.i(TAG, "Overlay added to display="+targetId);
        } catch(Exception e) {
            Log.e(TAG, "Overlay unavailable on display="+targetId,e);
            cursorView=null;wm=null;overlayParams=null;
        }
    }

    private boolean isBright(int color) {
        double luminance=(0.299*Color.red(color))+(0.587*Color.green(color))+(0.114*Color.blue(color));
        return luminance>=150.0;
    }

    private void applyCursorSettings() {
        if(cursorView==null || wm==null || overlayParams==null)return;
        int size=Math.max(1,Math.round(cursorSizeDp*cursorDensity));
        overlayParams.width=size;
        overlayParams.height=size;
        try {
            wm.updateViewLayout(cursorView,overlayParams);
            cursorView.invalidate();
            showCursor();
        } catch(Exception e) {
            Log.e(TAG,"Cursor settings update failed",e);
        }
    }

    private void showCursor() {
        if(cursorView==null)return;
        lastCursorActivity=SystemClock.uptimeMillis();
        if(!cursorVisible) {
            cursorView.setVisibility(View.VISIBLE);
            cursorVisible=true;
        }
    }

    private void hideCursor() {
        if(cursorView!=null && cursorVisible) {
            cursorView.setVisibility(View.INVISIBLE);
            cursorVisible=false;
        }
    }

    private void removeCursor() {
        if(cursorView!=null && wm!=null) {
            try { wm.removeViewImmediate(cursorView); } catch(Exception ignored) { }
        }
        cursorView=null; wm=null; overlayParams=null; cursorVisible=false;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if(enabled && cursorView!=null) {
                float x=dead(lx),y=dead(ly);
                if(x!=0f || y!=0f) {
                    showCursor();
                    float power=Math.min(1f,(float)Math.hypot(x,y));
                    float speed=2f+22f*power;
                    cursorX=Math.max(0f,Math.min(w-1f,cursorX+x*speed));
                    cursorY=Math.max(0f,Math.min(h-1f,cursorY+y*speed));
                    overlayParams.x=(int)cursorX;
                    overlayParams.y=(int)cursorY;
                    try { wm.updateViewLayout(cursorView,overlayParams); }
                    catch(Exception e) { Log.e(TAG,"Cursor move failed",e); }
                }

                float scroll=dead(ry);
                if(scroll!=0f && SystemClock.uptimeMillis()-lastScroll>220) {
                    showCursor();
                    lastScroll=SystemClock.uptimeMillis();
                    swipe(scroll);
                }

                if(hideDelayMs>0 && cursorVisible &&
                   SystemClock.uptimeMillis()-lastCursorActivity>=hideDelayMs &&
                   dead(lx)==0f && dead(ly)==0f && dead(ry)==0f) {
                    hideCursor();
                }
            }
            handler.postDelayed(this,16);
        }
    };

    private float dead(float v) { return Math.abs(v)<0.18f ? 0f : v; }

    @Override public void onMotionEvent(MotionEvent e) {
        if(!enabled || targetId<0 || cursorView==null) return;
        if((e.getSource()&InputDevice.SOURCE_JOYSTICK)!=InputDevice.SOURCE_JOYSTICK
          || e.getActionMasked()!=MotionEvent.ACTION_MOVE) return;
        lx=e.getAxisValue(MotionEvent.AXIS_X);
        ly=e.getAxisValue(MotionEvent.AXIS_Y);
        InputDevice d=e.getDevice();
        if(d!=null && d.getMotionRange(MotionEvent.AXIS_RZ,InputDevice.SOURCE_JOYSTICK)!=null)
            ry=e.getAxisValue(MotionEvent.AXIS_RZ);
        else ry=e.getAxisValue(MotionEvent.AXIS_RY);
        if(dead(lx)!=0f || dead(ly)!=0f || dead(ry)!=0f) showCursor();
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        boolean gamepad=(e.getSource()&InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD ||
                        (e.getSource()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK;
        if(!gamepad)return false;
        int code=e.getKeyCode();

        if(code==KeyEvent.KEYCODE_BUTTON_SELECT) {
            selectHeld=e.getAction()==KeyEvent.ACTION_DOWN;
            return enabled && targetId>=0;
        }

        if(code==KeyEvent.KEYCODE_BUTTON_START && selectHeld) {
            if(e.getAction()==KeyEvent.ACTION_DOWN && e.getRepeatCount()==0) {
                enabled=!enabled;
                lx=ly=ry=0;
                updateCapture();
                Toast.makeText(this,enabled ? "Souris activee" : "Mode souris desactive",
                    Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        if(!enabled || targetId<0 || cursorView==null) return false;
        boolean mapped=code==KeyEvent.KEYCODE_BUTTON_A||
            code==KeyEvent.KEYCODE_BUTTON_B||
            code==KeyEvent.KEYCODE_BUTTON_X||
            code==KeyEvent.KEYCODE_BUTTON_R1;
        if(!mapped)return false;

        if(e.getAction()==KeyEvent.ACTION_DOWN && e.getRepeatCount()==0) {
            showCursor();
            switch(code) {
                case KeyEvent.KEYCODE_BUTTON_A: tap(70);break;
                case KeyEvent.KEYCODE_BUTTON_X: tap(650);break;
                case KeyEvent.KEYCODE_BUTTON_B: performGlobalAction(GLOBAL_ACTION_BACK);break;
                case KeyEvent.KEYCODE_BUTTON_R1:
                    tap(65);handler.postDelayed(()->tap(65),150);break;
            }
        }
        return true;
    }

    private void tap(long duration) {
        if(targetId<0)return;
        Path path=new Path();
        path.moveTo(cursorX,cursorY);
        GestureDescription gesture=new GestureDescription.Builder()
             .setDisplayId(targetId)
             .addStroke(new GestureDescription.StrokeDescription(path,0,duration))
             .build();
        boolean ok=dispatchGesture(gesture,null,null);
        if(!ok) Log.w(TAG,"Gesture rejected on display="+targetId);
    }

    private void swipe(float scroll) {
        if(targetId<0)return;
        float magnitude=Math.min(Math.abs(scroll),1f);
        float distance=(scroll>0 ? -1f : 1f)*(60f+120f*magnitude);
        float top=Math.max(20,Math.min(h-20,cursorY-distance/2f));
        float bottom=Math.max(20,Math.min(h-20,cursorY+distance/2f));
        Path path=new Path();
        path.moveTo(cursorX,top);
        path.lineTo(cursorX,bottom);
        GestureDescription gesture=new GestureDescription.Builder()
            .setDisplayId(targetId)
            .addStroke(new GestureDescription.StrokeDescription(path,0,150))
            .build();
        dispatchGesture(gesture,null,null);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() {
        active=false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(checkDisplays);
        if(dm!=null)dm.unregisterDisplayListener(listener);
        if(prefs!=null)prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        removeCursor();
        super.onDestroy();
    }
}
''')
print("Built standalone no-Shizuku Android variant (custom cursor + accessibility gestures).")
