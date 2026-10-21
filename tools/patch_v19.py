from pathlib import Path
p=Path("upstream/app/src/main/java/fr/externalscreen/gamepadpointer/ControllerService.java")
s=p.read_text()
a=s.index('        // A mirrored physical output may appear as a separate Display.')
b=s.index('        int nextId=next==null ? -1 : next.getDisplayId();',a)
s=s[:a]+'''        // HONOR PNM-N49 / MagicOS observed in actual mirror/PC diagnostics:
        // MIRROR: built-in 2172x2352, external VITURE 1920x1080.
        // PC:     built-in 1152x691, external VITURE 1920x1080.
        // The secure mirror_built_in_display setting stays at 1 in BOTH modes.
        // Do not rely on external display ids (42/43 are ephemeral), names,
        // FLAG_PRESENTATION or accessibility window enumeration.
        Display builtIn=dm.getDisplay(Display.DEFAULT_DISPLAY);
        Point builtInSize=new Point();
        if(builtIn!=null) builtIn.getRealSize(builtInSize);
        boolean honor="HONOR".equalsIgnoreCase(android.os.Build.MANUFACTURER) &&
                      "PNM-N49".equalsIgnoreCase(android.os.Build.MODEL);
        boolean pcDetected=honor &&
                           builtInSize.x>0 && builtInSize.y>0 &&
                           Math.max(builtInSize.x,builtInSize.y)<=1400 &&
                           Math.min(builtInSize.x,builtInSize.y)<=900;
        // Do not mistake a rotated full-resolution phone screen for PC mode.
        if(pcDetected) {
            for(Display display:dm.getDisplays()) {
                if(display.getDisplayId()!=Display.DEFAULT_DISPLAY &&
                   display.getState()==Display.STATE_ON) {
                    next=display;
                    break;
                }
            }
        }
        boolean mirrorDetected=(next==null);
        if(mirrorDetected) next=builtIn;
'''+s[b:]
# Never create an overlay when there is no active display.
p.write_text(s)
g=Path("upstream/app/build.gradle.kts");g.write_text(g.read_text().replace("versionCode = 18","versionCode = 19").replace("0.18-noshizuku","0.19-noshizuku"))
print("v0.19: Honor-specific detection from measured built-in resolution; avoids display-id and window heuristics.")
