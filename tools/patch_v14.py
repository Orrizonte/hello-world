from pathlib import Path
p=Path("upstream/app/src/main/java/fr/externalscreen/gamepadpointer")
a=p/"MainActivity.java";s=p/"ControllerService.java"
A=a.read_text();S=s.read_text()
anchor='        Button settings = new Button(this);'
assert anchor in A
A=A.replace(anchor,'''        Button mirror = new Button(this);
        mirror.setText(prefs.getBoolean("mirror_mode",false)
            ? "Mode miroir actif (toucher pour revenir au mode PC)"
            : "Activer le curseur en mode miroir");
        mirror.setOnClickListener(v -> {
            boolean next=!prefs.getBoolean("mirror_mode",false);
            prefs.edit().putBoolean("mirror_mode",next).apply();
            mirror.setText(next ? "Mode miroir actif (toucher pour revenir au mode PC)"
                : "Activer le curseur en mode miroir");
        });
        root.addView(mirror);
'''+anchor,1)
# preference updates trigger a full display retarget, not just cursor appearance
S=S.replace('''        if("cursor_size_dp".equals(key)''','''        if("mirror_mode".equals(key)) {
            removeCursor();
            targetId=-1;
            captureEnabled=false;
            updateTarget();
        }
        if("cursor_size_dp".equals(key)''',1)
old='''        for(Display display:dm.getDisplays()) {
            if(display.getDisplayId()!=Display.DEFAULT_DISPLAY) {next=display; break;}
        }'''
new='''        if(prefs!=null && prefs.getBoolean("mirror_mode",false)) {
            next=dm.getDisplay(Display.DEFAULT_DISPLAY);
        } else {
            for(Display display:dm.getDisplays()) {
                if(display.getDisplayId()!=Display.DEFAULT_DISPLAY) {next=display; break;}
            }
        }'''
assert old in S
S=S.replace(old,new,1)
# transparent circle only for mirror; existing arrow for PC mode.
anchor='''                    float unit=getWidth()/40f;
                    Path p=new Path();'''
assert anchor in S
S=S.replace(anchor,'''                    if(prefs!=null && prefs.getBoolean("mirror_mode",false)) {
                        float radius=Math.min(getWidth(),getHeight())*0.40f;
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor((cursorColor & 0x00FFFFFF) | 0x38000000);
                        c.drawCircle(getWidth()/2f,getHeight()/2f,radius,paint);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(Math.max(2f,cursorDensity*1.5f));
                        paint.setColor((cursorColor & 0x00FFFFFF) | 0xDD000000);
                        c.drawCircle(getWidth()/2f,getHeight()/2f,radius,paint);
                        return;
                    }
                    float unit=getWidth()/40f;
                    Path p=new Path();''',1)
# updateTarget uses targetId=0; existing gesture setDisplayId(0) valid.
a.write_text(A);s.write_text(S)
g=Path("upstream/app/build.gradle.kts");g.write_text(g.read_text().replace("versionCode = 13","versionCode = 14").replace("0.13-noshizuku","0.14-noshizuku"))
m=Path("upstream/app/src/main/AndroidManifest.xml");m.write_text(m.read_text().replace("v0.13","v0.14"))
print("v0.14: opt-in mirror mode on default display, translucent circle cursor.")
