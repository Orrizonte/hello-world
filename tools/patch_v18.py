from pathlib import Path
p=Path("upstream/app/src/main/java/fr/externalscreen/gamepadpointer/MainActivity.java")
s=p.read_text()
anchor='        Button settings = new Button(this);'
assert anchor in s
s=s.replace(anchor,'''        add(root, "DIAGNOSTIC MAGICOS (lecture seule)", 19, 0xff8cd9ff);
        add(root, "Enregistre d'abord en mode miroir, puis en mode PC. Aucun reglage systeme n'est modifie.", 14, Color.WHITE);
        Button mirrorSnap=new Button(this);
        mirrorSnap.setText("1. Capturer le mode miroir");
        mirrorSnap.setOnClickListener(v->saveProjectionSnapshot("mirror"));
        root.addView(mirrorSnap);
        Button desktopSnap=new Button(this);
        desktopSnap.setText("2. Capturer le mode PC");
        desktopSnap.setOnClickListener(v->saveProjectionSnapshot("desktop"));
        root.addView(desktopSnap);
        Button shareReport=new Button(this);
        shareReport.setText("3. Comparer et partager le diagnostic");
        shareReport.setOnClickListener(v->shareProjectionComparison());
        root.addView(shareReport);
'''+anchor,1)
anchor='    private int colorIndex(int value) {'
assert anchor in s
methods=r'''    private String projectionSnapshot() {
        StringBuilder b=new StringBuilder();
        b.append("SDK=").append(android.os.Build.VERSION.SDK_INT)
         .append(" manufacturer=").append(android.os.Build.MANUFACTURER)
         .append(" model=").append(android.os.Build.MODEL).append("\n");
        String[] keys={
          "mirror_built_in_display","external_display_mode","desktop_mode",
          "desktop_mode_enabled","projection_mode","wireless_projection_mode",
          "multi_display_mode","pc_mode","pc_mode_enabled",
          "force_desktop_mode_on_external_displays",
          "development_force_desktop_mode_on_external_displays",
          "enable_freeform_support","overlay_display_devices"
        };
        for(String key:keys) {
            b.append("setting.").append(key).append("=");
            b.append("system:").append(readSetting(0,key)).append(" ");
            b.append("secure:").append(readSetting(1,key)).append(" ");
            b.append("global:").append(readSetting(2,key)).append("\n");
        }
        android.hardware.display.DisplayManager manager=getSystemService(android.hardware.display.DisplayManager.class);
        for(android.view.Display d:manager.getDisplays()) {
            android.graphics.Point size=new android.graphics.Point();
            d.getRealSize(size);
            b.append("display id=").append(d.getDisplayId())
             .append(" name=").append(d.getName())
             .append(" flags=").append(d.getFlags())
             
             .append(" size=").append(size.x).append("x").append(size.y)
             .append(" state=").append(d.getState()).append("\n");
        }
        return b.toString();
    }
    private String readSetting(int namespace,String key) {
        try {
            String value=namespace==0 ? android.provider.Settings.System.getString(getContentResolver(),key)
              : namespace==1 ? android.provider.Settings.Secure.getString(getContentResolver(),key)
              : android.provider.Settings.Global.getString(getContentResolver(),key);
            return value==null ? "(absent)" : value.replace("\n"," ");
        } catch(Exception e) {
            return "(inaccessible:"+e.getClass().getSimpleName()+")";
        }
    }
    private void saveProjectionSnapshot(String mode) {
        prefs.edit().putString("diagnostic_"+mode,projectionSnapshot()).apply();
        android.widget.Toast.makeText(this,"Diagnostic "+mode+" enregistre",android.widget.Toast.LENGTH_SHORT).show();
    }
    private void shareProjectionComparison() {
        String mirror=prefs.getString("diagnostic_mirror",null);
        String desktop=prefs.getString("diagnostic_desktop",null);
        if(mirror==null || desktop==null) {
            android.widget.Toast.makeText(this,"Capture les deux modes d'abord",android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        java.util.Map<String,String> a=diagnosticMap(mirror),b=diagnosticMap(desktop);
        StringBuilder out=new StringBuilder("DIAGNOSTIC PROJECTION HONOR - DIFFERENCES\n");
        java.util.Set<String> keys=new java.util.TreeSet<>();
        keys.addAll(a.keySet());keys.addAll(b.keySet());
        int count=0;
        for(String key:keys) {
            String x=a.get(key),y=b.get(key);
            if(!java.util.Objects.equals(x,y)) {
                out.append(key).append("\n  MIROIR: ").append(x)
                   .append("\n  PC: ").append(y).append("\n");
                count++;
            }
        }
        out.append("\nNombre de differences: ").append(count);
        out.append("\n\n--- MIROIR ---\n").append(mirror)
           .append("\n--- PC ---\n").append(desktop);
        android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(android.content.Intent.EXTRA_SUBJECT,"Diagnostic modes miroir et PC Honor");
        intent.putExtra(android.content.Intent.EXTRA_TEXT,out.toString());
        startActivity(android.content.Intent.createChooser(intent,"Partager le diagnostic"));
    }
    private java.util.Map<String,String> diagnosticMap(String snapshot) {
        java.util.Map<String,String> map=new java.util.LinkedHashMap<>();
        for(String line:snapshot.split("\n")) {
            if(line.startsWith("setting.")) {
                int i=line.indexOf('=');
                if(i>0)map.put(line.substring(0,i),line.substring(i+1));
            } else if(line.startsWith("display id=")) {
                int i=line.indexOf(" name=");
                if(i>0)map.put(line.substring(0,i),line.substring(i+1));
            }
        }
        return map;
    }

'''
s=s.replace(anchor,methods+anchor,1)
p.write_text(s)
g=Path("upstream/app/build.gradle.kts");g.write_text(g.read_text().replace("versionCode = 17","versionCode = 18").replace("0.17-noshizuku","0.18-noshizuku"))
print("v0.18: read-only Honor projection snapshot comparison and Android share sheet")
