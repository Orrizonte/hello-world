from pathlib import Path
base=Path("upstream")
activity=base/"app/src/main/java/fr/externalscreen/gamepadpointer/MainActivity.java"
service=base/"app/src/main/java/fr/externalscreen/gamepadpointer/ControllerService.java"
xml=base/"app/src/main/res/xml/controller_service.xml"
assert activity.exists() and service.exists() and xml.exists()

def edit(s,old,new,name):
    if old not in s: raise ValueError("Missing expected section: "+name)
    return s.replace(old,new,1)

a=activity.read_text()
a=edit(a,'''        add(root, "DEFILEMENT AU JOYSTICK DROIT", 19, 0xff8cd9ff);
        addSlider(root,"Sensibilite : ", "scroll_strength", 5, 100, 25, " %");
        addSlider(root,"Pause entre defilements : ", "scroll_delay_ms", 260, 900, 480, " ms");
        add(root,"Un geste de defilement lent limite l'inertie. Les applications peuvent toutefois ajouter leur propre inertie.", 14, 0xffdddddd);''',
'''        add(root, "VITESSE DU JOYSTICK GAUCHE", 19, 0xff8cd9ff);
        addSlider(root, "Vitesse du curseur : ", "pointer_speed", 5, 100, 50, " %");
        add(root, "VITESSE DU JOYSTICK DROIT", 19, 0xff8cd9ff);
        addSlider(root, "Vitesse de defilement : ", "scroll_speed", 5, 100, 30, " %");
        add(root, "Defilement sans geste glisse : arret des commandes des le retour du joystick au centre. Certaines applications animent elles-memes leur defilement. Si Honor ne rend pas la zone defilable accessible, le joystick ne fera pas defiler cette zone.", 14, 0xffdddddd);''',"sliders")
a=a.replace("Version 0.5 - Sans Shizuku","Version 0.6 - Sans Shizuku")
activity.write_text(a)

s=service.read_text()
s=edit(s,"import android.graphics.Canvas;", """import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.SparseArray;
import java.util.List;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;""","imports")
s=edit(s,'''        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;''',
'''        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;''',"flags")
s=edit(s,'''                    float speed=2f+22f*power;''',
'''                    // Independent slider for the LEFT stick pointer.
                    float gain=Math.max(5,Math.min(100,
                        prefs.getInt("pointer_speed",50)))/50f;
                    float speed=(2f+22f*power)*gain;''',"pointer speed")
s=edit(s,'''                if(!dragging && !scrollGestureInFlight && scroll!=0f &&
                        SystemClock.uptimeMillis()-lastScroll >
                        Math.max(260,prefs.getInt("scroll_delay_ms",480))) {''',
'''                final int scrollSpeed=Math.max(5,Math.min(100,
                    prefs.getInt("scroll_speed",30)));
                final long scrollInterval=750L-(scrollSpeed*6L);
                if(!dragging && scroll!=0f &&
                        SystemClock.uptimeMillis()-lastScroll > scrollInterval) {''',"scroll interval")
s=edit(s,"                    swipe(scroll);","                    accessibleScroll(scroll,scrollSpeed);","action invocation")
start=s.index("    private void swipe(float scroll) {")
end=s.index("    @Override public void onAccessibilityEvent",start)
newCode=r'''    /**
     * No touch swipes: scrolling is requested with ACTION_SCROLL_DOWN/UP.
     * This cannot start Android touch-fling momentum. An app may still animate
     * a single accessibility scroll; we cannot override that from here.
     */
    private void accessibleScroll(float axis,int speed) {
        if(targetId<0 || dragging || Math.abs(axis)<0.18f)return;
        SparseArray<List<AccessibilityWindowInfo>> displays;
        try { displays=getWindowsOnAllDisplays(); }
        catch(Exception ex) { Log.w(TAG,"Cannot query external accessibility windows",ex);return; }
        if(displays==null)return;
        List<AccessibilityWindowInfo> windows=displays.get(targetId);
        if(windows==null)return;
        boolean down=axis>0f;
        Rect bounds=new Rect();
        AccessibilityNodeInfo fallback=null;
        for(AccessibilityWindowInfo window:windows) {
            try {
                window.getBoundsInScreen(bounds);
                if(!bounds.contains((int)cursorX,(int)cursorY))continue;
                AccessibilityNodeInfo root=window.getRoot();
                if(root==null)continue;
                AccessibilityNodeInfo node=findScrollableAt(root,
                    (int)cursorX,(int)cursorY,0);
                if(node!=null && performScrollOnNode(node,down,axis,speed))return;
                if(fallback==null)fallback=findAnyScrollable(root,0);
            } catch(Exception ex) {
                Log.w(TAG,"Accessibility node unavailable",ex);
            }
        }
        // Allow scrolling a full-screen list even when its interactive
        // children do not contain the exact cursor coordinates.
        if(fallback!=null)performScrollOnNode(fallback,down,axis,speed);
    }

    private boolean scrollActionAvailable(AccessibilityNodeInfo node) {
        for(AccessibilityNodeInfo.AccessibilityAction act:node.getActionList()) {
            int id=act.getId();
            if(id==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                    id==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ||
                    id==AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId() ||
                    id==AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId())
                return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findScrollableAt(AccessibilityNodeInfo node,
                                                  int x,int y,int depth) {
        if(node==null || depth>30)return null;
        Rect rect=new Rect();
        node.getBoundsInScreen(rect);
        if(!rect.contains(x,y))return null;
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo sub=findScrollableAt(node.getChild(i),x,y,depth+1);
            if(sub!=null)return sub;
        }
        return scrollActionAvailable(node) ? node : null;
    }

    private AccessibilityNodeInfo findAnyScrollable(AccessibilityNodeInfo node,int depth) {
        if(node==null || depth>30)return null;
        if(scrollActionAvailable(node))return node;
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo found=findAnyScrollable(node.getChild(i),depth+1);
            if(found!=null)return found;
        }
        return null;
    }

    private boolean performScrollOnNode(AccessibilityNodeInfo node,
                                         boolean down,float axis,int speed) {
        int directional=down
            ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()
            : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId();
        int generic=down
            ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        // Android 15+ supports partial-page scrolling when the target view
        // advertises granular scrolling. Older/custom widgets may ignore it.
        Bundle args=null;
        if(node.isGranularScrollingSupported()) {
            args=new Bundle();
            float amount=(0.035f+(speed/100f)*0.23f) *
                Math.max(0.25f,Math.min(1f,Math.abs(axis)));
            args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT,amount);
        }
        try {
            if(node.performAction(directional,args))return true;
            return node.performAction(generic,args);
        } catch(Exception ex) {
            Log.w(TAG,"Accessibility scroll rejected by MagicOS",ex);
            return false;
        }
    }

'''
s=s[:start]+newCode+s[end:]
service.write_text(s)
x=xml.read_text()
x=edit(x,'android:canRequestFilterKeyEvents="true"',
'''android:canRequestFilterKeyEvents="true"
    android:canRetrieveWindowContent="true"''',"accessibility capability")
x=edit(x,'android:accessibilityFlags="flagRequestFilterKeyEvents"',
'''android:accessibilityFlags="flagRequestFilterKeyEvents|flagRetrieveInteractiveWindows"''',"window capability")
xml.write_text(x)
gradle=base/"app/build.gradle.kts"
g=gradle.read_text().replace("versionCode = 5","versionCode = 6").replace('"0.5-noshizuku"','"0.6-noshizuku"')
gradle.write_text(g)
manifest=base/"app/src/main/AndroidManifest.xml"
manifest.write_text(manifest.read_text().replace("Manette – Écran externe v0.5","Manette – Écran externe v0.6"))
print("Version 0.6 ready: two sliders, no touch-fling scroll and Android accessibility scroll actions")
