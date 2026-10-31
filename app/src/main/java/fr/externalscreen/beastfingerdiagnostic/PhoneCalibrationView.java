package fr.externalscreen.beastfingerdiagnostic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.Arrays;

/** One-frame local calibration: tap physical phone screen corners in camera picture. */
public final class PhoneCalibrationView extends View {
    public interface Complete { void onComplete(float[] points); }
    private final Bitmap snapshot;
    private final Complete callback;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float[] pts = new float[8];
    private final RectF imageBounds = new RectF();
    private int next;
    private static final String[] LABELS = {"1. HAUT GAUCHE","2. HAUT DROIT","3. BAS DROIT","4. BAS GAUCHE"};

    public PhoneCalibrationView(Context ctx, Bitmap snapshot, Complete callback) {
        super(ctx);
        this.snapshot=snapshot;
        this.callback=callback;
        Arrays.fill(pts, Float.NaN);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xff101821);
        float maxW=getWidth()*.97f,maxH=getHeight()*.76f;
        float s=Math.min(maxW/snapshot.getWidth(),maxH/snapshot.getHeight());
        float w=s*snapshot.getWidth(),h=s*snapshot.getHeight();
        imageBounds.set((getWidth()-w)/2,(getHeight()-h)/2,
            (getWidth()+w)/2,(getHeight()+h)/2);
        p.setColor(Color.WHITE);p.setAlpha(255);
        canvas.drawBitmap(snapshot,null,imageBounds,p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);p.setColor(0xff04ffee);
        if(next>=2) {
            Path path=new Path();
            for(int i=0;i<next;i++){
                float x=imageBounds.left+pts[i*2]*w;
                float y=imageBounds.top+pts[i*2+1]*h;
                if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
            }
            if(next==4)path.close();
            canvas.drawPath(path,p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(23f*getResources().getDisplayMetrics().scaledDensity);
        for(int i=0;i<next;i++){
            float x=imageBounds.left+pts[i*2]*w;
            float y=imageBounds.top+pts[i*2+1]*h;
            p.setColor(0xff07ffb2);canvas.drawCircle(x,y,9f,p);
            p.setColor(Color.BLACK);canvas.drawText(""+(i+1),x-5,y+6,p);
        }
        p.setColor(Color.WHITE);
        String instruction=next<4 ? "TOUCHE LE COIN : "+LABELS[next]
            : "ZONE ENREGISTRÉE";
        p.setTextSize(16f*getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText(instruction,12,Math.max(24f,imageBounds.top-12),p);
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if(e.getAction()!=MotionEvent.ACTION_UP)return true;
        float x=e.getX(),y=e.getY();
        if(!imageBounds.contains(x,y)||next>=4)return true;
        pts[2*next]=(x-imageBounds.left)/imageBounds.width();
        pts[2*next+1]=(y-imageBounds.top)/imageBounds.height();
        next++;
        invalidate();
        if(next==4)postDelayed(()->callback.onComplete(pts.clone()),180);
        return true;
    }
}
