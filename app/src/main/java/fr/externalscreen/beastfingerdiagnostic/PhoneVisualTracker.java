package fr.externalscreen.beastfingerdiagnostic;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.SystemClock;

/**
 * Very small frame-to-frame visual phone tracker.
 *
 * This is NOT a detector. It only maintains an already detected phone for a
 * brief interval when the COCO model temporarily loses it behind the user's
 * fingers. A correlation search over a downsampled camera frame estimates
 * translation caused by head movement; stale estimates expire quickly.
 */
public final class PhoneVisualTracker {
    private static final int W=160;
    private int height;
    private int[] previous;
    private RectF previousRect;
    private long lastModelDetectionMs;
    private float error=Float.POSITIVE_INFINITY;
    private boolean active;

    public void reset(){
        previous=null;
        previousRect=null;
        lastModelDetectionMs=0;
        error=Float.POSITIVE_INFINITY;
        active=false;
    }

    private static int[] grey(Bitmap source,int targetHeight){
        Bitmap reduced=Bitmap.createScaledBitmap(source,W,targetHeight,true);
        int[] data=new int[W*targetHeight];
        reduced.getPixels(data,0,W,0,0,W,targetHeight);
        reduced.recycle();
        for(int i=0;i<data.length;i++){
            int px=data[i];
            int r=(px>>16)&255,g=(px>>8)&255,b=px&255;
            data[i]=(r*77+g*150+b*29)>>8;
        }
        return data;
    }

    /** Call after each reliable ML phone update. */
    public void learn(Bitmap frame,RectF detected){
        if(frame==null||detected==null)return;
        int h=Math.max(60,Math.round(frame.getHeight()*W/(float)frame.getWidth()));
        height=h;
        previous=grey(frame,h);
        previousRect=new RectF(detected);
        lastModelDetectionMs=SystemClock.elapsedRealtime();
        error=0f;
        active=true;
    }

    /**
     * Track the previous phone bounding rectangle by appearance similarity.
     * Returns null if the image no longer supports that location.
     */
    public RectF estimate(Bitmap frame) {
        active=false;
        if(frame==null||previous==null||previousRect==null||
            SystemClock.elapsedRealtime()-lastModelDetectionMs>1050L)
            return null;
        int h=Math.max(60,Math.round(frame.getHeight()*W/(float)frame.getWidth()));
        if(h!=height)return null;
        int[] current=grey(frame,h);
        int left=(int)(previousRect.left*W);
        int top=(int)(previousRect.top*h);
        int right=(int)(previousRect.right*W);
        int bottom=(int)(previousRect.bottom*h);
        if(right-left<7||bottom-top<9)return null;

        // Exclude outer frame/bezels and sample the interior phone image.
        int startX=left+Math.max(1,(right-left)/8);
        int endX=right-Math.max(1,(right-left)/8);
        int startY=top+Math.max(1,(bottom-top)/10);
        int endY=bottom-Math.max(1,(bottom-top)/10);
        if(endX-startX<4||endY-startY<5)return null;
        int maxShiftX=Math.min(14,Math.max(4,(right-left)/3));
        int maxShiftY=Math.min(12,Math.max(4,(bottom-top)/3));

        float best=Float.POSITIVE_INFINITY;
        int bestX=0,bestY=0;
        for(int sy=-maxShiftY;sy<=maxShiftY;sy++){
            for(int sx=-maxShiftX;sx<=maxShiftX;sx++){
                long total=0;
                int count=0;
                for(int j=0;j<9;j++){
                    int y=startY+(endY-startY)*j/8;
                    int ty=y+sy;
                    if(y<1||y>=h-1||ty<1||ty>=h-1)continue;
                    for(int i=0;i<7;i++){
                        int x=startX+(endX-startX)*i/6;
                        int tx=x+sx;
                        if(x<1||x>=W-1||tx<1||tx>=W-1)continue;
                        // Brightness difference plus local gradients:
                        // less sensitive to illumination and the user's hand.
                        int old=previous[y*W+x];
                        int now=current[ty*W+tx];
                        int oldGrad=Math.abs(old-previous[y*W+x+1])+
                            Math.abs(old-previous[(y+1)*W+x]);
                        int newGrad=Math.abs(now-current[ty*W+tx+1])+
                            Math.abs(now-current[(ty+1)*W+tx]);
                        total+=Math.abs(old-now) * 2 +
                            Math.abs(oldGrad-newGrad);
                        count++;
                    }
                }
                if(count<24)continue;
                float cost=total/(3f*count)+
                    .08f*(Math.abs(sx)+Math.abs(sy));
                if(cost<best){best=cost;bestX=sx;bestY=sy;}
            }
        }
        error=best;
        // Be conservative: if camera changes too much, display no contour
        // instead of extrapolating an incorrect phone.
        if(!Float.isFinite(best)||best>24f)return null;
        float dx=bestX/(float)W,dy=bestY/(float)h;
        RectF result=new RectF(previousRect);
        result.offset(dx,dy);
        if(result.left<-.01f||result.top<-.01f||
           result.right>1.01f||result.bottom>1.01f)return null;
        result.left=Math.max(0f,result.left);
        result.top=Math.max(0f,result.top);
        result.right=Math.min(1f,result.right);
        result.bottom=Math.min(1f,result.bottom);
        previous=current;
        previousRect=new RectF(result);
        active=true;
        return result;
    }
    public boolean active(){return active;}
    public float lastError(){return error;}
}
