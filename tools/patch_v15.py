from pathlib import Path
p=Path("upstream/app/src/main/java/fr/externalscreen/gamepadpointer")
a=p/"MainActivity.java";s=p/"ControllerService.java"
A=a.read_text();S=s.read_text()
start=A.index('        Button mirror = new Button(this);')
end=A.index('        Button settings = new Button(this);',start)
A=A[:start]+A[end:]
# Replace manual mirror setting listener with a passive display observer.
start=S.index('        if("mirror_mode".equals(key)) {')
end=S.index('        if("cursor_size_dp".equals(key)',start)
S=S[:start]+S[end:]
# Automatically classify presentation display vs mirrored output.
start=S.index('        if(prefs!=null && prefs.getBoolean("mirror_mode",false)) {')
end=S.index('        int nextId=next==null ? -1 : next.getDisplayId();',start)
S=S[:start]+'''        // Desktop mode normally exposes a separate presentation-capable display.
        // In mirror mode, the default display remains the touch/gesture target.
        for(Display display:dm.getDisplays()) {
            if(display.getDisplayId()==Display.DEFAULT_DISPLAY) continue;
            if((display.getFlags() & Display.FLAG_PRESENTATION)!=0) {
                next=display;
                break;
            }
        }
        boolean mirrorDetected=(next==null);
        if(mirrorDetected) next=dm.getDisplay(Display.DEFAULT_DISPLAY);
'''+S[end:]
# Avoid repeated window recreation on an unchanged target.
# Track mirror/desktop transitions independently of display id.
S=S.replace('''    private boolean captureEnabled=false;''','''    private boolean captureEnabled=false;
    private boolean mirrorCursor=false;''',1)
S=S.replace('''        if(targetId!=nextId || (next!=null && cursorView==null)) {''','''        if(targetId!=nextId || mirrorCursor!=mirrorDetected ||
           (next!=null && cursorView==null)) {''',1)
S=S.replace('''            target=next;
            targetId=nextId;''','''            target=next;
            targetId=nextId;
            mirrorCursor=mirrorDetected;''',1)
S=S.replace('''if(prefs!=null && prefs.getBoolean("mirror_mode",false)) {''','''if(mirrorCursor) {''')
# Do not put a full-screen overlay on the mirrored external display.
# Overlay remains a tiny non-touchable cursor on the default screen.
a.write_text(A);s.write_text(S)
g=Path("upstream/app/build.gradle.kts");g.write_text(g.read_text().replace("versionCode = 14","versionCode = 15").replace("0.14-noshizuku","0.15-noshizuku"))
m=Path("upstream/app/src/main/AndroidManifest.xml");m.write_text(m.read_text().replace("v0.14","v0.15"))
print("v0.15: automatic presentation detection, no manual toggle/recreate.")
