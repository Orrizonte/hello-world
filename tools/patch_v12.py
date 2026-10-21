from pathlib import Path
p=Path("upstream/app/src/main/java/fr/externalscreen/gamepadpointer")
a=p/"MainActivity.java";s=p/"ControllerService.java"
A=a.read_text();S=s.read_text()
A=A.replace("START et SELECT sont reserves aux applications et jeux.","START ou SELECT seul : commandes normales. START + SELECT : activer/desactiver la capture souris.")
A=A.replace("START et SELECT : commandes normales de la manette","START ou SELECT seuls : commandes normales ; ensemble : bascule souris")
S=S.replace('private boolean enabled=true;','private boolean enabled=true, selectHeld=false, startHeld=false, comboLatch=false;')
old='''        // START and SELECT are never consumed by the accessibility service.
        // Let Moonlight, games and Android handle their native key events.
        if(code==KeyEvent.KEYCODE_BUTTON_SELECT ||
           code==KeyEvent.KEYCODE_BUTTON_START) return false;'''
new='''        // Single START or SELECT must pass to Moonlight unmodified.
        // Their combination toggles mouse capture, regardless of key order.
        if(code==KeyEvent.KEYCODE_BUTTON_SELECT || code==KeyEvent.KEYCODE_BUTTON_START) {
            boolean down=e.getAction()==KeyEvent.ACTION_DOWN;
            if(code==KeyEvent.KEYCODE_BUTTON_SELECT) selectHeld=down;
            else startHeld=down;
            if(selectHeld && startHeld && !comboLatch) {
                comboLatch=true;
                if(dragging)endDrag();
                enabled=!enabled;
                lx=ly=ry=0f;
                updateCapture();
                Toast.makeText(this,enabled?"Souris activee":"Capture souris desactivee",
                    Toast.LENGTH_SHORT).show();
            }
            if(!selectHeld && !startHeld) comboLatch=false;
            // Cannot retroactively cancel a single button already sent to Moonlight.
            return false;
        }'''
assert old in S
S=S.replace(old,new)
# Important: key filtering must remain active while mouse is off to detect combo.
S=S.replace('''        if(should) info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        else info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;''','''        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;''')
# When capture disabled, still let START/SELECT events reach service.
# Their normal events return false and other buttons are passed through.
a.write_text(A);s.write_text(S)
g=Path("upstream/app/build.gradle.kts");g.write_text(g.read_text().replace("versionCode = 11","versionCode = 12").replace("0.11-noshizuku","0.12-noshizuku"))
m=Path("upstream/app/src/main/AndroidManifest.xml");m.write_text(m.read_text().replace("v0.11","v0.12"))
print("v0.12: Start/Select individually pass through; combo toggles mouse mode.")
