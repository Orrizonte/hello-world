from pathlib import Path
p=Path("upstream/app/src/main/java/fr/externalscreen/gamepadpointer")
s=p/"ControllerService.java";t=s.read_text()
old='''        if("finger".equals(action)) {
            if(e.getAction()==KeyEvent.ACTION_DOWN && e.getRepeatCount()==0) {
                showCursor();beginDrag();
            } else if(e.getAction()==KeyEvent.ACTION_UP) endDrag();
            return true;
        }'''
new='''        if("finger".equals(action)) {
            if(e.getAction()==KeyEvent.ACTION_DOWN && e.getRepeatCount()==0) {
                showCursor();
                holdPressed=true;
                holdStarted=false;
                handler.removeCallbacks(holdTrigger);
                handler.postDelayed(holdTrigger,220);
            } else if(e.getAction()==KeyEvent.ACTION_UP) {
                holdPressed=false;
                handler.removeCallbacks(holdTrigger);
                if(holdStarted) endDrag();
                else if(!dragging) tap(65);
                holdStarted=false;
            }
            return true;
        }'''
assert old in t
t=t.replace(old,new,1)
# Don't let a prior press survive screen changes.
t=t.replace('''            holdPressed=false;
            handler.removeCallbacks(holdTrigger);''','''            holdPressed=false;
            holdStarted=false;
            handler.removeCallbacks(holdTrigger);''',1)
s.write_text(t)
g=Path("upstream/app/build.gradle.kts")
g.write_text(g.read_text().replace("versionCode = 8","versionCode = 9").replace("0.8-noshizuku","0.9-noshizuku"))
m=Path("upstream/app/src/main/AndroidManifest.xml")
m.write_text(m.read_text().replace("v0.8","v0.9"))
print("A: short press is a tap on release; long press keeps finger down and allows drag until release")
