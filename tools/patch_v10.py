from pathlib import Path
p=Path("upstream/app/src/main/java/fr/externalscreen/gamepadpointer")
a=p/"MainActivity.java";s=p/"ControllerService.java"
A=a.read_text();S=s.read_text()
# User can toggle from the app, even after combo turns mouse mode off.
anchor='        Button settings = new Button(this);'
assert anchor in A
A=A.replace(anchor,'''        Button modeButton = new Button(this);
        modeButton.setText(prefs.getBoolean("mouse_enabled",true)
            ? "Desactiver la souris (Moonlight)" : "Activer la souris");
        modeButton.setOnClickListener(v -> {
            boolean next=!prefs.getBoolean("mouse_enabled",true);
            prefs.edit().putBoolean("mouse_enabled",next).apply();
            modeButton.setText(next ? "Desactiver la souris (Moonlight)" : "Activer la souris");
        });
        root.addView(modeButton);
'''+anchor,1)
S=S.replace('private boolean enabled=true, selectHeld=false, startHeld=false, comboLatch=false;',
'''private boolean enabled=true, selectHeld=false, startHeld=false, comboLatch=false;''')
S=S.replace('''        loadSettings();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);''','''        loadSettings();
        enabled=prefs.getBoolean("mouse_enabled",true);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);''',1)
S=S.replace('''    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener=(sp,key)->{
        if("cursor_size_dp"''','''    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener=(sp,key)->{
        if("mouse_enabled".equals(key)) {
            enabled=sp.getBoolean("mouse_enabled",true);
            lx=ly=ry=0;
            updateCapture();
        }
        if("cursor_size_dp"''',1)
# Don't consume start/select independently; consume only combo in active mouse mode.
start=S.index('        if(code==KeyEvent.KEYCODE_BUTTON_SELECT || code==KeyEvent.KEYCODE_BUTTON_START) {')
end=S.index('        if(!enabled || targetId<0 || cursorView==null)',start)
S=S[:start]+'''        if(code==KeyEvent.KEYCODE_BUTTON_SELECT || code==KeyEvent.KEYCODE_BUTTON_START) {
            boolean down=e.getAction()==KeyEvent.ACTION_DOWN;
            if(code==KeyEvent.KEYCODE_BUTTON_SELECT)selectHeld=down;
            else startHeld=down;
            if(enabled && targetId>=0 && selectHeld && startHeld && !comboLatch) {
                comboLatch=true;
                if(dragging)endDrag();
                prefs.edit().putBoolean("mouse_enabled",false).apply();
                Toast.makeText(this,"Souris desactivee : boutons rendus a Moonlight",
                    Toast.LENGTH_SHORT).show();
            }
            if(!selectHeld && !startHeld)comboLatch=false;
            // Android cannot undo the first button event after a combo is detected.
            // Never swallow Start/Select when mouse mode is disabled.
            return false;
        }
'''+S[end:]
# stop repeatedly show cursor while disabled
S=S.replace('''        if(should==captureEnabled) {
            if(cursorView!=null) {
                if(enabled) showCursor(); else hideCursor();
            }
            return;
        }''','''        if(should==captureEnabled) {
            if(cursorView!=null && !enabled) hideCursor();
            return;
        }''',1)
# updateCapture should no longer capture key events in disabled mode
S=S.replace('''        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;''','''        if(should) info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        else info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;''',1)
s.write_text(S);a.write_text(A)
g=Path("upstream/app/build.gradle.kts");g.write_text(g.read_text().replace("versionCode = 9","versionCode = 10").replace("0.9-noshizuku","0.10-noshizuku"))
m=Path("upstream/app/src/main/AndroidManifest.xml");m.write_text(m.read_text().replace("v0.9","v0.10"))
print("v0.10 Moonlight passthrough: disabled mode releases key filter, app button re-enables")
