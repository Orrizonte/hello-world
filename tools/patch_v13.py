from pathlib import Path
p=Path("upstream/app/src/main/java/fr/externalscreen/gamepadpointer/ControllerService.java")
s=p.read_text()
start=s.index("    private void updateCapture() {")
end=s.index("    private void addCursor(",start)
section=s[start:end]
# Remove all periodic showCursor calls, without altering controller capture.
section=section.replace("if(enabled) showCursor(); else hideCursor();","if(!enabled) hideCursor();")
assert "showCursor()" not in section
s=s[:start]+section+s[end:]
p.write_text(s)
g=Path("upstream/app/build.gradle.kts");g.write_text(g.read_text().replace("versionCode = 12","versionCode = 13").replace("0.12-noshizuku","0.13-noshizuku"))
m=Path("upstream/app/src/main/AndroidManifest.xml");m.write_text(m.read_text().replace("v0.12","v0.13"))
print("v0.13: periodic display polling no longer resets inactivity timer")
