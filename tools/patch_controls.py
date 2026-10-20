from pathlib import Path

base=Path("upstream")
activity=base/"app/src/main/java/fr/externalscreen/gamepadpointer/MainActivity.java"
service=base/"app/src/main/java/fr/externalscreen/gamepadpointer/ControllerService.java"
assert activity.exists() and service.exists(), "Expected v0.4 source not generated"

def change(s, old, new, label):
    if old not in s:
        raise RuntimeError("Source layout changed: "+label)
    return s.replace(old,new,1)

a=activity.read_text()
a=change(a,"import android.view.Display;", "import android.view.Display;\nimport android.view.KeyEvent;", "KeyEvent import")
controls=r'''
        add(root, "COMMANDES DE LA MANETTE", 19, 0xff8cd9ff);
        add(root, "Attribue une fonction a chaque bouton. Les reglages sont sauvegardes automatiquement.", 14, Color.WHITE);
        addBinding(root, "A", KeyEvent.KEYCODE_BUTTON_A, "drag");
        addBinding(root, "B", KeyEvent.KEYCODE_BUTTON_B, "back");
        addBinding(root, "X", KeyEvent.KEYCODE_BUTTON_X, "click");
        addBinding(root, "Y", KeyEvent.KEYCODE_BUTTON_Y, "none");
        addBinding(root, "L1", KeyEvent.KEYCODE_BUTTON_L1, "long");
        addBinding(root, "R1", KeyEvent.KEYCODE_BUTTON_R1, "double");
        addBinding(root, "L2", KeyEvent.KEYCODE_BUTTON_L2, "none");
        addBinding(root, "R2", KeyEvent.KEYCODE_BUTTON_R2, "none");

        add(root, "DEFILEMENT AU JOYSTICK DROIT", 19, 0xff8cd9ff);
        addSlider(root,"Sensibilite : ", "scroll_strength", 5, 100, 25, " %");
        addSlider(root,"Pause entre defilements : ", "scroll_delay_ms", 260, 900, 480, " ms");
        add(root,"Un geste de defilement lent limite l'inertie. Les applications peuvent toutefois ajouter leur propre inertie.", 14, 0xffdddddd);
        add(root,"A maintenu puis joystick gauche : glisser-deposer. Sur un bureau qui interprete les gestes tactiles comme le deplacement d'objets, maintenir sur du vide peut tracer un rectangle de selection.", 14, 0xffdddddd);

'''
a=change(a,"        Button settings = new Button(this);",controls+"        Button settings = new Button(this);","controls UI")
a=change(a,'add(root, "Commandes en mode souris :\\nJoystick gauche : curseur\\nA : clic (appui tactile)\\nB : Retour\\nX : appui long (alternative au clic droit)\\nJoystick droit : defilement par geste\\nR1 : double clic\\nSELECT + START : mode souris ON/OFF", 16, Color.WHITE);',
'add(root, "Par defaut : X = clic gauche, A maintenu = glisser-deposer, B = Retour, R1 = double clic. SELECT + START = activer ou desactiver le mode souris.", 16, Color.WHITE);',
"obsolete help")
methods=r'''
    private final String[] actionNames={
        "Aucune action","Clic gauche","Maintenir / glisser / selection",
        "Appui long (equivalent tactile du clic droit)","Retour",
        "Double clic","Accueil"
    };
    private final String[] actionIds={
        "none","click","drag","long","back","double","home"
    };

    private void addBinding(LinearLayout root,String name,int keycode,String fallback) {
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        TextView title=add(row,"Bouton "+name,15,Color.WHITE);
        title.setPadding(0,0,0,0);
        Spinner spinner=new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,actionNames));
        String value=prefs.getString("map_"+keycode,fallback);
        int selection=0;
        for(int i=0;i<actionIds.length;i++) if(actionIds[i].equals(value)) selection=i;
        spinner.setSelection(selection);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,int position,long id) {
                prefs.edit().putString("map_"+keycode, actionIds[position]).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        row.addView(spinner);
        root.addView(row);
    }

    private void addSlider(LinearLayout root,String title,String key,
                           int min,int max,int fallback,String suffix) {
        int current=prefs.getInt(key,fallback);
        TextView valueLabel=add(root,title+current+suffix,15,Color.WHITE);
        valueLabel.setPadding(0,0,0,0);
        SeekBar seek=new SeekBar(this);
        seek.setMax(max-min);
        seek.setProgress(Math.max(0,Math.min(max-min,current-min)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) {
                int value=min+progress;
                valueLabel.setText(title+value+suffix);
                if(fromUser)prefs.edit().putInt(key,value).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        root.addView(seek);
    }

'''
a=change(a,"    private int colorIndex(int value) {",methods+"    private int colorIndex(int value) {","UI methods")
a=a.replace("Version 0.4 - Sans Shizuku","Version 0.5 - Sans Shizuku")
activity.write_text(a)

s=service.read_text()
s=change(s,"    private long lastScroll=0L;",'''    private long lastScroll=0L;
    private long lastJoystickEvent=0L;
    private boolean scrollGestureInFlight=false;

    // A sequence of continued accessibility strokes preserves one finger down.
    // No Shizuku: these are touch events, not real mouse button events.
    private boolean dragging=false, dragBusy=false, dragReleaseRequested=false;
    private GestureDescription.StrokeDescription dragStroke;
    private float dragLastX,dragLastY;
    private int dragDisplayId=-1;
''',"new fields")
s=change(s,"        if(targetId!=nextId || (next!=null && cursorView==null)) {",
'''        if(targetId!=nextId || (next!=null && cursorView==null)) {
            resetDrag();''', "display reset")
s=change(s,"                float scroll=dead(ry);\n                if(scroll!=0f && SystemClock.uptimeMillis()-lastScroll>220) {",
'''                // A stale axis value must never create indefinite scrolling.
                if(SystemClock.uptimeMillis()-lastJoystickEvent>450) ry=0f;
                float scroll=dead(ry);
                if(!dragging && !scrollGestureInFlight && scroll!=0f &&
                        SystemClock.uptimeMillis()-lastScroll >
                        Math.max(260,prefs.getInt("scroll_delay_ms",480))) {''',"scroll tick")
s=change(s,"        ly=e.getAxisValue(MotionEvent.AXIS_Y);",
'''        ly=e.getAxisValue(MotionEvent.AXIS_Y);
        lastJoystickEvent=SystemClock.uptimeMillis();''',"joystick freshness")
start=s.index("    @Override protected boolean onKeyEvent(KeyEvent e)")
end=s.index("    private void tap(long duration)",start)
newKeys=r'''    @Override protected boolean onKeyEvent(KeyEvent e) {
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
                if(enabled && dragging) endDrag();
                enabled=!enabled;
                lx=ly=ry=0;
                updateCapture();
                Toast.makeText(this,enabled?"Souris activee":"Mode souris desactive",
                    Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if(!enabled || targetId<0 || cursorView==null)return false;

        String fallback;
        switch(code) {
            case KeyEvent.KEYCODE_BUTTON_A: fallback="drag";break;
            case KeyEvent.KEYCODE_BUTTON_B: fallback="back";break;
            case KeyEvent.KEYCODE_BUTTON_X: fallback="click";break;
            case KeyEvent.KEYCODE_BUTTON_Y: fallback="none";break;
            case KeyEvent.KEYCODE_BUTTON_L1: fallback="long";break;
            case KeyEvent.KEYCODE_BUTTON_R1: fallback="double";break;
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2: fallback="none";break;
            default: return false;
        }
        String action=prefs.getString("map_"+code,fallback);
        if("none".equals(action))return false;
        if("drag".equals(action)) {
            if(e.getAction()==KeyEvent.ACTION_DOWN && e.getRepeatCount()==0) {
                showCursor(); beginDrag();
            } else if(e.getAction()==KeyEvent.ACTION_UP) endDrag();
            return true;
        }
        if(e.getAction()==KeyEvent.ACTION_DOWN && e.getRepeatCount()==0) {
            showCursor();
            switch(action) {
                case "click": if(!dragging)tap(65);break;
                case "long": if(!dragging)tap(620);break;
                case "back": performGlobalAction(GLOBAL_ACTION_BACK);break;
                case "home": performGlobalAction(GLOBAL_ACTION_HOME);break;
                case "double":
                    if(!dragging) {
                        tap(55);
                        handler.postDelayed(()-> {if(!dragging)tap(55);},155);
                    } break;
            }
        }
        return true;
    }

    private void beginDrag() {
        if(dragging || targetId<0)return;
        dragging=true;
        dragReleaseRequested=false;
        dragStroke=null;
        dragLastX=cursorX;dragLastY=cursorY;
        dragDisplayId=targetId;
        Path p=new Path();
        p.moveTo(dragLastX,dragLastY);
        GestureDescription.StrokeDescription stroke=
            new GestureDescription.StrokeDescription(p,0,70,true);
        submitDrag(stroke,false);
    }

    private void endDrag() {
        if(!dragging)return;
        dragReleaseRequested=true;
        if(!dragBusy)advanceDrag();
    }

    private void advanceDrag() {
        if(!dragging || dragBusy || dragStroke==null)return;
        if(targetId!=dragDisplayId) {resetDrag();return;}
        final boolean finish=dragReleaseRequested;
        final float nx=cursorX,ny=cursorY;
        Path p=new Path();
        p.moveTo(dragLastX,dragLastY);
        // A no-motion segment keeps touch held while waiting for joystick movement.
        p.lineTo(nx,ny);
        long duration=finish?35:70;
        GestureDescription.StrokeDescription next=
            dragStroke.continueStroke(p,0,duration,!finish);
        dragLastX=nx;dragLastY=ny;
        submitDrag(next,finish);
    }

    private void submitDrag(GestureDescription.StrokeDescription stroke,boolean last) {
        if(!dragging)return;
        dragBusy=true;
        GestureDescription gesture=new GestureDescription.Builder()
            .setDisplayId(dragDisplayId).addStroke(stroke).build();
        boolean submitted=dispatchGesture(gesture,new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription ignored) {
                dragBusy=false;
                dragStroke=stroke;
                if(last) {resetDrag();return;}
                if(dragging)handler.post(()->advanceDrag());
            }
            @Override public void onCancelled(GestureDescription ignored) {
                Log.w(TAG,"Hold gesture cancelled by the system");
                resetDrag();
            }
        },handler);
        if(!submitted) {
            Log.w(TAG,"Hold gesture refused on external display "+dragDisplayId);
            resetDrag();
        }
    }

    private void resetDrag() {
        dragging=false;dragBusy=false;dragReleaseRequested=false;
        dragStroke=null;dragDisplayId=-1;
    }

'''
s=s[:start]+newKeys+s[end:]
start=s.index("    private void swipe(float scroll) {")
end=s.index("    @Override public void onAccessibilityEvent",start)
newSwipe=r'''    private void swipe(float scroll) {
        if(targetId<0 || scrollGestureInFlight || dragging)return;
        float magnitude=Math.min(Math.abs(scroll),1f);
        float strength=Math.max(5,Math.min(100,prefs.getInt("scroll_strength",25)))/100f;
        // Small, slow gestures avoid touch-fling inertia on MagicOS.
        float distance=(scroll>0 ? -1f : 1f)*(12f+65f*magnitude)*strength;
        float top=Math.max(18,Math.min(h-18,cursorY-distance/2f));
        float bottom=Math.max(18,Math.min(h-18,cursorY+distance/2f));
        Path path=new Path();
        path.moveTo(cursorX,top);
        path.lineTo(cursorX,bottom);
        GestureDescription gesture=new GestureDescription.Builder()
            .setDisplayId(targetId)
            .addStroke(new GestureDescription.StrokeDescription(path,0,340))
            .build();
        scrollGestureInFlight=true;
        if(!dispatchGesture(gesture,new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gesture) {
                scrollGestureInFlight=false;
            }
            @Override public void onCancelled(GestureDescription gesture) {
                scrollGestureInFlight=false;
            }
        },handler)) scrollGestureInFlight=false;
    }

'''
s=s[:start]+newSwipe+s[end:]
s=change(s,"        handler.removeCallbacks(tick);\n        handler.removeCallbacks(checkDisplays);","        handler.removeCallbacks(tick);\n        handler.removeCallbacks(checkDisplays);\n        resetDrag();","destroy")
service.write_text(s)

build=base/"app/build.gradle.kts"
g=build.read_text().replace('versionCode = 3','versionCode = 5').replace('versionName = "0.4.1-noshizuku"','versionName = "0.5-noshizuku"')
build.write_text(g)
manifest=base/"app/src/main/AndroidManifest.xml"
m=manifest.read_text().replace('Manette – Écran externe v0.4','Manette – Écran externe v0.5')
manifest.write_text(m)
print("Applied v0.5 configurable button mapping, touch-hold drag and slower scrolling.")
