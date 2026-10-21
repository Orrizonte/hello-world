from pathlib import Path
p=Path("upstream/app/src/main/java/fr/externalscreen/gamepadpointer/ControllerService.java")
s=p.read_text()
start=s.index("        // A mirrored physical output may appear as a separate Display.")
end=s.index("        boolean mirrorDetected=(next==null);",start)
s=s[:start]+'''        // HONOR MagicOS projection modes are distinguished by the display
        // configuration, NOT the external display ID, name or presentation flag.
        // On the measured PNM-N49: mirror keeps the unfolded internal display
        // in portrait (2172x2352), desktop switches its display configuration
        // to landscape (1152x691). Do not hard-code either resolution.
        Display builtIn=dm.getDisplay(Display.DEFAULT_DISPLAY);
        Point internalSize=new Point();
        if(builtIn!=null)builtIn.getRealSize(internalSize);
        boolean honor=android.os.Build.MANUFACTURER.equalsIgnoreCase("HONOR");
        boolean desktopLayout=honor && internalSize.x>internalSize.y;
        if(desktopLayout) {
            for(Display display:dm.getDisplays()) {
                if(display.getDisplayId()==Display.DEFAULT_DISPLAY)continue;
                next=display;
                break;
            }
        }
'''+s[end:]
# Prevent the mirror cursor from being rendered on an external display:
# it must be a tiny overlay on the built-in display.
# Avoid toggling overlays when the default display rotates but mode remains same.
p.write_text(s)
g=Path("upstream/app/build.gradle.kts");g.write_text(g.read_text().replace("versionCode = 18","versionCode = 19").replace("0.18-noshizuku","0.19-noshizuku"))
print("v0.19: Honor projection mode via built-in display orientation; external display id ignored.")
