from pathlib import Path
root=Path("upstream/app/src/main")
p=root/"java/fr/externalscreen/gamepadpointer/ControllerService.java"
s=p.read_text()
a=s.index("        for(Display display:dm.getDisplays()) {",s.index("    private void updateTarget()"))
b=s.index("        boolean mirrorDetected=(next==null);",a)
s=s[:a]+'''        // A mirrored physical output may appear as a separate Display.
        // Only choose a separate display when it actually owns an interactive
        // application window. This is stronger evidence than display flags/names.
        android.util.SparseArray<java.util.List<android.view.accessibility.AccessibilityWindowInfo>> windows =
                getWindowsOnAllDisplays();
        if(windows!=null) {
            for(int i=0;i<windows.size();i++) {
                int id=windows.keyAt(i);
                if(id==Display.DEFAULT_DISPLAY) continue;
                Display candidate=dm.getDisplay(id);
                if(candidate==null)continue;
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> list=windows.valueAt(i);
                if(list==null)continue;
                boolean hasApp=false;
                for(android.view.accessibility.AccessibilityWindowInfo window:list) {
                    if(window!=null && window.getType()==
                       android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                        hasApp=true;
                        break;
                    }
                }
                if(hasApp) { next=candidate; break; }
            }
        }
'''+s[b:]
# Prevent the Android accessibility overlay from being considered a desktop app window.
# No app windows on an external output => mirror, cursor on default display.
p.write_text(s)
xml=root/"res/xml/controller_service.xml"
x=xml.read_text()
x=x.replace('android:accessibilityFlags="flagRequestFilterKeyEvents"',
            'android:accessibilityFlags="flagRequestFilterKeyEvents|flagRetrieveInteractiveWindows"\\n    android:canRetrieveWindowContent="true"')
xml.write_text(x)
# Ensure window enumeration remains enabled when capture state changes.
s=p.read_text()
s=s.replace('''        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;''',
            '''        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS |
                      AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;''',1)
p.write_text(s)
g=Path("upstream/app/build.gradle.kts");g.write_text(g.read_text().replace("versionCode = 16","versionCode = 17").replace("0.16-noshizuku","0.17-noshizuku"))
print("v0.17: detect desktop by real application windows on external display; mirror falls back to default.")
