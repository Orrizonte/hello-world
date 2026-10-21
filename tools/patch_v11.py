from pathlib import Path
p=Path("upstream/app/src/main/java/fr/externalscreen/gamepadpointer")
a=p/"MainActivity.java";s=p/"ControllerService.java"
A=a.read_text();S=s.read_text()
start=A.index('        Button modeButton = new Button(this);')
end=A.index('        Button settings = new Button(this);',start)
A=A[:start]+A[end:]
A=A.replace('SELECT + START = activer ou desactiver le mode souris.','START et SELECT sont reserves aux applications et jeux.')
A=A.replace('SELECT + START : mode souris ON/OFF','START et SELECT : commandes normales de la manette')
S=S.replace('''        enabled=prefs.getBoolean("mouse_enabled",true);
''','')
start=S.index('        if("mouse_enabled".equals(key)) {')
end=S.index('        if("cursor_size_dp"',start)
S=S[:start]+S[end:]
start=S.index('        if(code==KeyEvent.KEYCODE_BUTTON_SELECT || code==KeyEvent.KEYCODE_BUTTON_START) {')
end=S.index('        if(!enabled || targetId<0 || cursorView==null)',start)
S=S[:start]+'''        // START and SELECT are never consumed by the accessibility service.
        // Let Moonlight, games and Android handle their native key events.
        if(code==KeyEvent.KEYCODE_BUTTON_SELECT ||
           code==KeyEvent.KEYCODE_BUTTON_START) return false;
'''+S[end:]
# No synthetic toggles or interception of these two buttons.
S=S.replace('private boolean enabled=true, selectHeld=false, startHeld=false, comboLatch=false;','private boolean enabled=true;')
a.write_text(A);s.write_text(S)
g=Path("upstream/app/build.gradle.kts");g.write_text(g.read_text().replace("versionCode = 10","versionCode = 11").replace("0.10-noshizuku","0.11-noshizuku"))
m=Path("upstream/app/src/main/AndroidManifest.xml");m.write_text(m.read_text().replace("v0.10","v0.11"))
print("v0.11: Start and Select always passed through; no Moonlight mode UI.")
