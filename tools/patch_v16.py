from pathlib import Path
p=Path("upstream/app/src/main/java/fr/externalscreen/gamepadpointer/ControllerService.java")
s=p.read_text()
old='''            if((display.getFlags() & Display.FLAG_PRESENTATION)!=0) {
                next=display;
                break;
            }'''
new='''            // FLAG_PRESENTATION alone is also set on some mirrored outputs.
            // Only an own-content display is safe for an external cursor overlay.
            int flags=display.getFlags();
            if((flags & Display.FLAG_PRESENTATION)!=0 &&
               (flags & Display.FLAG_OWN_CONTENT_ONLY)!=0) {
                next=display;
                break;
            }'''
assert old in s
s=s.replace(old,new,1)
# Avoid rebuilding an overlay while changing display state unless target actually changes.
# Clear obsolete manual preference, since v15 removed the button.
s=s.replace('''        boolean mirrorDetected=(next==null);''','''        boolean mirrorDetected=(next==null);''',1)
# The circle is painted on the default display, and is mirrored only if the system
# mirrors accessibility overlays. This is a platform-dependent limitation.
p.write_text(s)
g=Path("upstream/app/build.gradle.kts");g.write_text(g.read_text().replace("versionCode = 15","versionCode = 16").replace("0.15-noshizuku","0.16-noshizuku"))
m=Path("upstream/app/src/main/AndroidManifest.xml");m.write_text(m.read_text().replace("v0.15","v0.16"))
print("v0.16: require OWN_CONTENT_ONLY before external overlay; safe fallback to default display")
