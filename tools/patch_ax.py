from pathlib import Path
p=Path("upstream/app/src/main/java/fr/externalscreen/gamepadpointer")
a=p/"MainActivity.java"
s=p/"ControllerService.java"
A=a.read_text(); S=s.read_text()
def replace(text,old,new):
    if old not in text: raise RuntimeError("Missing: "+old[:80])
    return text.replace(old,new,1)
A=replace(A,'addBinding(root, "A", KeyEvent.KEYCODE_BUTTON_A, "drag");','addBinding(root, "A", KeyEvent.KEYCODE_BUTTON_A, "clickhold");')
A=replace(A,'addBinding(root, "X", KeyEvent.KEYCODE_BUTTON_X, "click");','addBinding(root, "X", KeyEvent.KEYCODE_BUTTON_X, "context");')
A=replace(A,'"Aucune action","Clic gauche","Maintenir / glisser / selection",','"Aucune action","Clic gauche","Clic gauche + maintien","Menu contextuel (clic droit)","Maintenir / glisser / selection",')
A=replace(A,'"none","click","drag","long","back","double","home"','"none","click","clickhold","context","drag","long","back","double","home"')
A=replace(A,'X = clic gauche, A maintenu = glisser-deposer','X = menu contextuel, A = clic gauche et maintien pour glisser-deposer')
A=A.replace("Version 0.6 - Sans Shizuku","Version 0.7 - Sans Shizuku")
a.write_text(A)

S=replace(S,'case KeyEvent.KEYCODE_BUTTON_A: fallback="drag";break;','case KeyEvent.KEYCODE_BUTTON_A: fallback="clickhold";break;')
S=replace(S,'case KeyEvent.KEYCODE_BUTTON_X: fallback="click";break;','case KeyEvent.KEYCODE_BUTTON_X: fallback="context";break;')
S=replace(S,'        if("drag".equals(action)) {','''        if("clickhold".equals(action)) {
            // Delay the tap: on quick release issue a normal left click.
            // When held past the threshold, begin a continued stroke until release.
            if(e.getAction()==KeyEvent.ACTION_DOWN && e.getRepeatCount()==0) {
                showCursor();
                holdPressed=true;
                holdStarted=false;
                handler.removeCallbacks(holdTrigger);
                handler.postDelayed(holdTrigger,260);
            } else if(e.getAction()==KeyEvent.ACTION_UP) {
                holdPressed=false;
                handler.removeCallbacks(holdTrigger);
                if(holdStarted) endDrag();
                else if(!dragging)tap(65);
                holdStarted=false;
            }
            return true;
        }
        if("drag".equals(action)) {''')
S=replace(S,'    private boolean dragging=false, dragBusy=false, dragReleaseRequested=false;','''    private boolean dragging=false, dragBusy=false, dragReleaseRequested=false;
    private boolean holdPressed=false, holdStarted=false;
    private final Runnable holdTrigger=()-> {
        if(holdPressed && enabled && targetId>=0) {
            holdStarted=true;
            beginDrag();
        }
    };''')
S=replace(S,'case "click": if(!dragging)tap(65);break;','''case "click": if(!dragging)tap(65);break;
                case "context":
                    // On non-root Android the accessibility API does not expose
                    // a real secondary-display mouse right-click. Long touch is
                    // the closest supported context-menu action.
                    if(!dragging)tap(650);break;''')
S=replace(S,'        handler.removeCallbacks(tick);','''        handler.removeCallbacks(holdTrigger);
        handler.removeCallbacks(tick);''')
S=replace(S,'        if(targetId!=nextId || (next!=null && cursorView==null)) {','''        if(targetId!=nextId || (next!=null && cursorView==null)) {
            holdPressed=false;
            handler.removeCallbacks(holdTrigger);''')
s.write_text(S)
g=Path("upstream/app/build.gradle.kts")
g.write_text(g.read_text().replace("versionCode = 6","versionCode = 7").replace("0.6-noshizuku","0.7-noshizuku"))
m=Path("upstream/app/src/main/AndroidManifest.xml")
m.write_text(m.read_text().replace("v0.6","v0.7"))
print("v0.7: A tap or held left touch, X context via long touch, configurable.")
