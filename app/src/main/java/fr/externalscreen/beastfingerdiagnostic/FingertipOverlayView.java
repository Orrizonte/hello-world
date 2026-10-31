package fr.externalscreen.beastfingerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import java.util.Collections;
import java.util.List;

/**
 * TOUCH-THROUGH overlay that draws only small feathered fingertip patches.
 * It uses Viture's *virtual full-display coordinate system* but is physically
 * a tightly cropped overlay window around detected tips. No full-screen
 * transparent surface is created (which made Honor's extended display go black).
 */
public final class FingertipOverlayView extends View {
    private List<FingertipDetector.Patch> tips=Collections.emptyList();
    private final Paint brush=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private int opacity=65,patchScale=110,shiftX=0,shiftY=0;
    private boolean mirror;
    private float sourcePhoneAspect=-1f;
    private boolean preservePhoneAspect;
    private int virtualW=1920,virtualH=1080,originX=0,originY=0;

    public FingertipOverlayView(Context ctx){
        super(ctx);
        setBackgroundColor(android.graphics.Color.TRANSPARENT);
    }
    public void setProjectedPhoneAspect(float aspect,boolean preserve){
        sourcePhoneAspect=aspect;
        preservePhoneAspect=preserve && aspect>.2f && aspect<5f;
        invalidate();
    }
    public void setVirtualGeometry(int width,int height){
        virtualW=Math.max(1,width);
        virtualH=Math.max(1,height);
        invalidate();
    }
    public void setWindowOrigin(int x,int y){
        originX=x;originY=y;
        invalidate();
    }
    public void setSettings(int percent,int scale,int dx,int dy,boolean mirrored){
        opacity=Math.max(10,Math.min(75,percent));
        patchScale=Math.max(40,Math.min(180,scale));
        shiftX=dx;shiftY=dy;mirror=mirrored;
        invalidate();
    }
    private RectF projection(){
        RectF r=new RectF(0,0,virtualW,virtualH);
        if(preservePhoneAspect){
            float displayAspect=virtualW/(float)virtualH;
            if(displayAspect>sourcePhoneAspect){
                float width=virtualH*sourcePhoneAspect;
                r.left=(virtualW-width)/2f;
                r.right=r.left+width;
            }else{
                float height=virtualW/sourcePhoneAspect;
                r.top=(virtualH-height)/2f;
                r.bottom=r.top+height;
            }
        }
        return r;
    }
    private RectF patchRect(FingertipDetector.Patch p,RectF screen){
        float density=getResources().getDisplayMetrics().density;
        float x=screen.left+(mirror?1f-p.x:p.x)*screen.width()+shiftX*density;
        float y=screen.top+p.y*screen.height()-shiftY*density;
        float rx=Math.max(12f,p.rx*screen.width()*patchScale/100f);
        float ry=Math.max(10f,p.ry*screen.height()*patchScale/100f);
        return new RectF(x-rx,y-ry,x+rx,y+ry);
    }
    /**
     * Bounding box within Viture physical pixels. The window manager only
     * receives this small non-touchable rectangle, never the whole display.
     */
    public Rect bounds(List<FingertipDetector.Patch> candidates){
        if(candidates==null || candidates.isEmpty())return null;
        RectF src=projection();
        RectF total=null;
        for(FingertipDetector.Patch p:candidates){
            RectF r=patchRect(p,src);
            if(total==null)total=new RectF(r);
            else total.union(r);
        }
        if(total==null)return null;
        int padding=16;
        int left=Math.max(0,(int)Math.floor(total.left)-padding);
        int top=Math.max(0,(int)Math.floor(total.top)-padding);
        int right=Math.min(virtualW,(int)Math.ceil(total.right)+padding);
        int bottom=Math.min(virtualH,(int)Math.ceil(total.bottom)+padding);
        if(right-left<2 || bottom-top<2)return null;
        return new Rect(left,top,right,bottom);
    }
    public void showTips(List<FingertipDetector.Patch> current){
        List<FingertipDetector.Patch> previous=tips;
        tips=current == null?Collections.emptyList():current;
        invalidate();
        postDelayed(()->{
            for(FingertipDetector.Patch p:previous)p.release();
        },350);
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        brush.setAlpha(Math.round(opacity*255f/100f));
        RectF scene=projection();
        for(FingertipDetector.Patch p:tips){
            if(p.image.isRecycled())continue;
            RectF box=patchRect(p,scene);
            box.offset(-originX,-originY);
            c.drawBitmap(p.image,null,box,brush);
        }
    }
    public void clear(){showTips(Collections.emptyList());}
}
