from pathlib import Path
p=Path("upstream/app/src/main/java/fr/externalscreen/gamepadpointer/ControllerService.java")
s=p.read_text()
old='''        if(should==captureEnabled) {
            if(cursorView!=null) {
                if(enabled) showCursor(); else hideCursor();
            }
            return;
        }'''
assert old in s
s=s.replace(old,'''        if(should==captureEnabled) {
            // Polling displays must never reset the cursor inactivity timer.
            if(!enabled) hideCursor();
            return;
        }''')
old='''        if(enabled) showCursor(); else hideCursor();
    }

    private void addCursor'''
assert old in s
s=s.replace(old,'''        // Only actual controller activity may reveal the cursor.
        if(!enabled) hideCursor();
    }

    private void addCursor''')
p.write_text(s)
g=Path("upstream/app/build.gradle.kts");g.write_text(g.read_text().replace("versionCode = 12","versionCode = 13").replace("0.12-noshizuku","0.13-noshizuku"))
m=Path("upstream/app/src/main/AndroidManifest.xml");m.write_text(m.read_text().replace("v0.12","v0.13"))
print("v0.13 cursor timeout: polling no longer calls showCursor")
