package fr.externalscreen.beastfingerdiagnostic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.util.Collections;
import java.util.List;

/** Full-screen TOUCH-THROUGH view: paints ONLY small real-image fingertip patches. */
public final class FingertipOverlayView extends View {
    private List<FingertipDetector.Patch> tips = Collections.emptyList();
    private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private int opacity=65;
    private int patchScale=100;
    private int shiftX=0,shiftY=0;
    private boolean mirror;

    public FingertipOverlayView(Context ctx) {
        super(ctx);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setBackgroundColor(android.graphics.Color.TRANSPARENT);
    }
    public void setSettings(int percent, int scale, int dx, int dy, boolean mirrored) {
        opacity=Math.max(10,Math.min(75,percent));
        patchScale=Math.max(40,Math.min(140,scale));
        shiftX=dx;shiftY=dy;
        mirror=mirrored;
        invalidate();
    }
    public void showTips(List<FingertipDetector.Patch> updated) {
        List<FingertipDetector.Patch> old=tips;
        tips=updated == null ? Collections.emptyList() : updated;
        invalidate();
        // Delay recycling because the previous frame may still be being composited.
        postDelayed(()->{
            for (FingertipDetector.Patch p:old) p.release();
        },250);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w=getWidth(),h=getHeight();
        if(w<5||h<5)return;
        brush.setAlpha(Math.round(opacity*255f/100f));
        for(FingertipDetector.Patch p:tips){
            if(p.image.isRecycled())continue;
            float x=(mirror?1f-p.x:p.x)*w+shiftX*getResources().getDisplayMetrics().density;
            float y=p.y*h-shiftY*getResources().getDisplayMetrics().density;
            float rx=p.rx*w*patchScale/100f;
            float ry=p.ry*h*patchScale/100f;
            canvas.drawBitmap(p.image,null,new RectF(x-rx,y-ry,x+rx,y+ry),brush);
        }
    }
    public void clear() { showTips(Collections.emptyList()); }
}
