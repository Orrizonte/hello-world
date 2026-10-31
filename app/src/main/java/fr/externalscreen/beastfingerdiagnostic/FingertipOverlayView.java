package fr.externalscreen.beastfingerdiagnostic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.util.Collections;
import java.util.List;

/**
 * Native-resolution touch-through surface. The entire surface matches the
 * Viture output (typically 1920 x 1080). Only fingertip patches are painted.
 *
 * Mirrored phone content is usually letterboxed on a 16:9 projector. To avoid
 * falsely stretching the finger positions, the normalized phone coordinates
 * map to the centered aspect-preserved phone projection within the surface.
 */
public final class FingertipOverlayView extends View {
    private List<FingertipDetector.Patch> tips=Collections.emptyList();
    private final Paint brush=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private int opacity=65, patchScale=100, shiftX=0, shiftY=0;
    private boolean mirror;
    private float sourcePhoneAspect = -1f;
    private boolean preservePhoneAspect = false;

    public FingertipOverlayView(Context ctx){
        super(ctx);
        setBackgroundColor(android.graphics.Color.TRANSPARENT);
    }

    public void setProjectedPhoneAspect(float aspect, boolean preserve){
        sourcePhoneAspect=aspect;
        preservePhoneAspect=preserve && aspect>.2f && aspect<5f;
        invalidate();
    }

    public void setSettings(int percent,int scale,int dx,int dy,boolean mirrored){
        opacity=Math.max(10,Math.min(75,percent));
        patchScale=Math.max(40,Math.min(140,scale));
        shiftX=dx; shiftY=dy;
        mirror=mirrored;
        invalidate();
    }

    public void showTips(List<FingertipDetector.Patch> updated){
        List<FingertipDetector.Patch> old=tips;
        tips=updated == null?Collections.emptyList():updated;
        invalidate();
        // Drawing is asynchronous, so don't recycle source bitmaps synchronously.
        postDelayed(()->{
            for(FingertipDetector.Patch p:old)p.release();
        },350);
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        int w=getWidth(),h=getHeight();
        if(w<5||h<5)return;

        RectF projected=new RectF(0,0,w,h);
        if(preservePhoneAspect){
            float displayAspect=(float)w/h;
            if(displayAspect>sourcePhoneAspect){
                float projectedWidth=h*sourcePhoneAspect;
                projected.left=(w-projectedWidth)/2f;
                projected.right=projected.left+projectedWidth;
            }else{
                float projectedHeight=w/sourcePhoneAspect;
                projected.top=(h-projectedHeight)/2f;
                projected.bottom=projected.top+projectedHeight;
            }
        }

        brush.setAlpha(Math.round(opacity*255f/100f));
        float density=getResources().getDisplayMetrics().density;
        for(FingertipDetector.Patch p:tips){
            if(p.image.isRecycled())continue;
            float x=projected.left+(mirror?1f-p.x:p.x)*projected.width()+shiftX*density;
            float y=projected.top+p.y*projected.height()-shiftY*density;
            float rx=p.rx*projected.width()*patchScale/100f;
            float ry=p.ry*projected.height()*patchScale/100f;
            canvas.drawBitmap(p.image,null,new RectF(x-rx,y-ry,x+rx,y+ry),brush);
        }
    }

    public void clear(){showTips(Collections.emptyList());}
}
