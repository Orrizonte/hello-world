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

/**
 * Calibrate the four corners of the actual physical phone screen in the Beast
 * camera image. This view can be shown both as a phone touch controller and
 * as a full-resolution passive preview projected on the Beast display.
 */
public final class PhoneCalibrationView extends View {
    public interface Complete { void onComplete(float[] points); }
    public interface Progress { void onProgress(float[] points, int completed); }
    private final Bitmap snapshot;
    private final Complete callback;
    private Progress progress;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float[] pts=new float[8];
    private final RectF imageBounds=new RectF();
    private int next;
    private float zoom=1f,panX=0f,panY=0f;
    private float lastMidX,lastMidY,lastDistance;
    private boolean multitouch;
    private static final String[] LABELS={
        "COIN HAUT GAUCHE","COIN HAUT DROIT","COIN BAS DROIT","COIN BAS GAUCHE"
    };

    public PhoneCalibrationView(Context ctx, Bitmap snapshot, Complete callback){
        super(ctx);
        this.snapshot=snapshot;
        this.callback=callback;
        Arrays.fill(pts,Float.NaN);
        setBackgroundColor(0xff111827);
    }
    public void setProgressListener(Progress progress){this.progress=progress;}
    public void setPreviewPoints(float[] points,int completed){
        Arrays.fill(pts,Float.NaN);
        for(int i=0;i<Math.min(completed,4);i++){
            pts[2*i]=points[2*i];
            pts[2*i+1]=points[2*i+1];
        }
        next=Math.min(completed,4);
        invalidate();
    }
    private static float clamp(float x,float lo,float hi){
        return Math.max(lo,Math.min(hi,x));
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        c.drawColor(0xff111827);
        boolean projected=(callback==null);
        float maxW=getWidth()*(projected?.985f:.97f);
        float maxH=getHeight()*(projected?.87f:.77f);
        float fit=Math.min(maxW/snapshot.getWidth(),maxH/snapshot.getHeight());
        float width=snapshot.getWidth()*fit*zoom;
        float height=snapshot.getHeight()*fit*zoom;
        float cx=getWidth()/2f+panX,cy=getHeight()/2f+panY;
        imageBounds.set(cx-width/2f,cy-height/2f,cx+width/2f,cy+height/2f);

        c.save();
        c.clipRect(0,0,getWidth(),getHeight());
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        p.setAlpha(255);
        c.drawBitmap(snapshot,null,imageBounds,p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f,getWidth()/600f));
        p.setColor(0xff00ffce);
        if(next>=2){
            Path line=new Path();
            for(int i=0;i<next;i++){
                float x=imageBounds.left+pts[2*i]*width;
                float y=imageBounds.top+pts[2*i+1]*height;
                if(i==0)line.moveTo(x,y);else line.lineTo(x,y);
            }
            if(next==4)line.close();
            c.drawPath(line,p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(Math.max(20f,Math.min(getWidth(),getHeight())*.035f));
        for(int i=0;i<next;i++){
            float x=imageBounds.left+pts[2*i]*width;
            float y=imageBounds.top+pts[2*i+1]*height;
            p.setColor(0xff00ffbb);
            c.drawCircle(x,y,Math.max(7f,getWidth()/220f),p);
            p.setColor(Color.BLACK);
            c.drawText(""+(i+1),x-5,y+6,p);
        }

        p.setColor(Color.WHITE);
        p.setTextSize(Math.max(20f,Math.min(getWidth(),getHeight())*.033f));
        float head=Math.max(34f,getHeight()*.055f);
        c.drawText(next<4?"ÉCRAN DU TÉLÉPHONE : "+LABELS[next]:"ZONE CALIBRÉE",
            12,head,p);
        p.setTextSize(Math.max(14f,Math.min(getWidth(),getHeight())*.021f));
        String footer=projected?
            "IMAGE CAMÉRA BEAST "+snapshot.getWidth()+"×"+snapshot.getHeight()+
                " • touche les coins sur le téléphone":
            "Pince avec 2 doigts pour zoomer / dézoomer ; touche ensuite le coin demandé.";
        c.drawText(footer,12,getHeight()-Math.max(13f,getHeight()*.02f),p);
        c.restore();
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(callback==null)return false; // external display is preview only
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount()>=2){
            multitouch=true;
            lastMidX=(e.getX(0)+e.getX(1))/2f;
            lastMidY=(e.getY(0)+e.getY(1))/2f;
            lastDistance=(float)Math.hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1));
            return true;
        }
        if(action==MotionEvent.ACTION_MOVE && e.getPointerCount()>=2){
            float midX=(e.getX(0)+e.getX(1))/2f;
            float midY=(e.getY(0)+e.getY(1))/2f;
            float dist=(float)Math.hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1));
            if(lastDistance>8f)zoom=clamp(zoom*(dist/lastDistance),1f,5f);
            panX+=midX-lastMidX;
            panY+=midY-lastMidY;
            lastMidX=midX;lastMidY=midY;lastDistance=dist;
            invalidate();
            return true;
        }
        if(action==MotionEvent.ACTION_DOWN)return true;
        if(action==MotionEvent.ACTION_POINTER_UP){
            multitouch=true;return true;
        }
        if(action==MotionEvent.ACTION_UP){
            if(multitouch){multitouch=false;return true;}
            if(next>=4 || !imageBounds.contains(e.getX(),e.getY()))return true;
            pts[2*next]=clamp((e.getX()-imageBounds.left)/imageBounds.width(),0f,1f);
            pts[2*next+1]=clamp((e.getY()-imageBounds.top)/imageBounds.height(),0f,1f);
            next++;
            invalidate();
            if(progress!=null)progress.onProgress(pts.clone(),next);
            if(next==4)postDelayed(()->callback.onComplete(pts.clone()),300);
            return true;
        }
        return true;
    }
}
