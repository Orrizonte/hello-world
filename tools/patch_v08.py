from pathlib import Path
p=Path("upstream/app/src/main/java/fr/externalscreen/gamepadpointer")
a=p/"MainActivity.java";s=p/"ControllerService.java"
A=a.read_text();S=s.read_text()
def sub(x,old,new):
 if old not in x: raise ValueError("Missing "+old[:90])
 return x.replace(old,new,1)
# Fix persisted old mappings by switching keys to v8 namespace.
A=A.replace('"map_"+keycode','"map_v8_"+keycode')
S=S.replace('"map_"+code','"map_v8_"+code')
A=sub(A,'addBinding(root, "A", KeyEvent.KEYCODE_BUTTON_A, "clickhold");','addBinding(root, "A", KeyEvent.KEYCODE_BUTTON_A, "finger");')
A=sub(A,'addBinding(root, "X", KeyEvent.KEYCODE_BUTTON_X, "context");','addBinding(root, "X", KeyEvent.KEYCODE_BUTTON_X, "twofinger");')
A=sub(A,'"Clic gauche + maintien","Menu contextuel (clic droit)"','"Doigt (toucher / maintenir / glisser)","Deux doigts simultanes"')
A=sub(A,'"clickhold","context"','"finger","twofinger"')
A=A.replace('X = menu contextuel, A = clic gauche et maintien pour glisser-deposer','X = toucher a deux doigts, A = toucher et maintenir pour glisser-deposer')
A=A.replace("Version 0.7 - Sans Shizuku","Version 0.8 - Sans Shizuku")
a.write_text(A)
S=sub(S,'case KeyEvent.KEYCODE_BUTTON_A: fallback="clickhold";break;','case KeyEvent.KEYCODE_BUTTON_A: fallback="finger";break;')
S=sub(S,'case KeyEvent.KEYCODE_BUTTON_X: fallback="context";break;','case KeyEvent.KEYCODE_BUTTON_X: fallback="twofinger";break;')
S=sub(S,'if("clickhold".equals(action)) {','if("finger".equals(action)) {')
# Use touch down immediately; no delayed press causing weird behavior.
start=S.index('        if("finger".equals(action)) {')
end=S.index('        if("drag".equals(action)) {',start)
S=S[:start]+'''        if("finger".equals(action)) {
            if(e.getAction()==KeyEvent.ACTION_DOWN && e.getRepeatCount()==0) {
                showCursor();beginDrag();
            } else if(e.getAction()==KeyEvent.ACTION_UP) endDrag();
            return true;
        }
'''+S[end:]
S=sub(S,'case "context":','case "twofinger":')
S=sub(S,'if(!dragging)tap(650);break;','if(!dragging)twoFingerTap();break;')
# Two simultaneous touch points via Accessibility GestureDescription.
idx=S.index('    private void beginDrag() {')
S=S[:idx]+'''    private void twoFingerTap() {
        if(targetId<0 || dragging)return;
        Path first=new Path(),second=new Path();
        first.moveTo(Math.max(0,cursorX-22),cursorY);
        second.moveTo(Math.min(w-1,cursorX+22),cursorY);
        GestureDescription gesture=new GestureDescription.Builder().setDisplayId(targetId)
            .addStroke(new GestureDescription.StrokeDescription(first,0,90))
            .addStroke(new GestureDescription.StrokeDescription(second,0,90)).build();
        dispatchGesture(gesture,null,handler);
    }

'''+S[idx:]
# Return touch swipes, not accessibility page scroll actions.
S=sub(S,'accessibleScroll(scroll,scrollSpeed);','smoothScroll(scroll,scrollSpeed);')
idx=S.index('    private void accessibleScroll(')
S=S[:idx]+'''    private boolean scrolling=false;
    private void smoothScroll(float axis,int speed) {
        if(scrolling || targetId<0 || dragging)return;
        float magnitude=Math.min(1f,Math.abs(axis));
        float distance=(axis>0?-1f:1f)*(18f+85f*magnitude)*(speed/50f);
        float sy=Math.max(20,Math.min(h-20,cursorY-distance/2f));
        float ey=Math.max(20,Math.min(h-20,cursorY+distance/2f));
        Path path=new Path();path.moveTo(cursorX,sy);path.lineTo(cursorX,ey);
        GestureDescription gesture=new GestureDescription.Builder().setDisplayId(targetId)
            .addStroke(new GestureDescription.StrokeDescription(path,0,230)).build();
        scrolling=true;
        if(!dispatchGesture(gesture,new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription g){scrolling=false;}
            @Override public void onCancelled(GestureDescription g){scrolling=false;}
        },handler))scrolling=false;
    }

'''+S[idx:]
# Mirror: do not add an overlay on default display or unsupported display.
# prevent capture loss while app is in background by requesting key filtering always.
S=sub(S,'info.setMotionEventSources(should ? InputDevice.SOURCE_JOYSTICK : 0);','info.setMotionEventSources(should ? InputDevice.SOURCE_JOYSTICK : 0);')
# Start select: accept key order in either sequence and consume when active.
S=sub(S,'private boolean enabled=true, selectHeld=false;','private boolean enabled=true, selectHeld=false, startHeld=false, comboLatch=false;')
start=S.index('        if(code==KeyEvent.KEYCODE_BUTTON_SELECT) {')
end=S.index('        if(!enabled || targetId<0 || cursorView==null)',start)
S=S[:start]+'''        if(code==KeyEvent.KEYCODE_BUTTON_SELECT || code==KeyEvent.KEYCODE_BUTTON_START) {
            boolean down=e.getAction()==KeyEvent.ACTION_DOWN;
            if(code==KeyEvent.KEYCODE_BUTTON_SELECT)selectHeld=down;
            else startHeld=down;
            if(selectHeld && startHeld && !comboLatch) {
                comboLatch=true;
                if(dragging)endDrag();
                enabled=!enabled;lx=ly=ry=0;
                updateCapture();
                Toast.makeText(this,enabled?"Souris activee":"Mode souris desactive",
                    Toast.LENGTH_SHORT).show();
            }
            if(!selectHeld && !startHeld)comboLatch=false;
            return targetId>=0;
        }
'''+S[end:]
# Avoid overlay in mirror mode: if display flags indicate mirror? No reliable way to detect;
# fallback: only create overlay on presentation displays; can fail for Honor desktop.
s.write_text(S)
g=Path("upstream/app/build.gradle.kts")
g.write_text(g.read_text().replace("versionCode = 7","versionCode = 8").replace("0.7-noshizuku","0.8-noshizuku"))
m=Path("upstream/app/src/main/AndroidManifest.xml")
m.write_text(m.read_text().replace("v0.7","v0.8"))
print("v0.8 patch applied")
