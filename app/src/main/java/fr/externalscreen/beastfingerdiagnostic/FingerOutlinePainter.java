package fr.externalscreen.beastfingerdiagnostic;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * FingerCam's only visual output is a DRAWN OUTLINE, never real camera pixels.
 * Inspired by the outline-hand representation used in XR interfaces. This
 * implementation is fully local Android Canvas code; no Quest SDK is copied.
 */
public final class FingerOutlinePainter {
    private static final int OUTLINE=0xffeffaff;
    private FingerOutlinePainter() {}

    /**
     * A smooth, short fingertip capsule. Rotate its long axis from the fingertip
     * toward the previous landmark (the finger joint). No skin texture/video.
     */
    public static Bitmap landmark(float tipX,float tipY,float jointX,float jointY) {
        int s=112;
        Bitmap image=Bitmap.createBitmap(s,s,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(image);
        c.translate(s*.5f,s*.5f);
        float dx=jointX-tipX,dy=jointY-tipY;
        float rotation=-(float)Math.toDegrees(Math.atan2(dx,dy));
        c.rotate(rotation);
        RectF contour=new RectF(-17f,-31f,17f,25f);
        Paint glow=new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(8.5f);
        glow.setColor(0x397bdfff);
        c.drawRoundRect(contour,17f,17f,glow);

        Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x10ffffff);
        c.drawRoundRect(contour,17f,17f,fill);

        Paint line=new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.8f);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(OUTLINE);
        c.drawRoundRect(contour,17f,17f,line);
        return image;
    }

    /**
     * Segmentation fallback: render ONLY the boundary of a persistent masked
     * component; avoid copying a single colour pixel from the camera.
     *
     * labels are component IDs from the foreground segmentation of the phone
     * image. Each outline is calculated from just its own ID, not the global
     * visited bitmap (which formerly caused noisy ghost contours).
     */
    public static Bitmap componentOutline(int[] labels,int id,int stride,
            int fullHeight,int left,int top,int right,int bottom) {
        int w=right-left+1,h=bottom-top+1;
        if(w<2 || h<2)return null;
        int[] pixels=new int[w*h];
        for(int y=top;y<=bottom;y++) {
            for(int x=left;x<=right;x++) {
                int pos=y*stride+x;
                if(labels[pos]!=id)continue;
                boolean edge=false;
                boolean nearEdge=false;
                for(int dy=-2;dy<=2;dy++) {
                    for(int dx=-2;dx<=2;dx++) {
                        int nx=x+dx,ny=y+dy;
                        boolean same=nx>=0 && nx<stride &&
                            ny>=0 && ny<fullHeight && labels[ny*stride+nx]==id;
                        if(!same) {
                            if(Math.abs(dx)<=1 && Math.abs(dy)<=1)edge=true;
                            nearEdge=true;
                        }
                    }
                }
                int alpha=edge?243:nearEdge?125:18;
                pixels[(y-top)*w+(x-left)]=(alpha<<24)|0x00effaff;
            }
        }
        Bitmap low=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
        // Bilinear upscale smooths the contour instead of huge pixel blocks.
        Bitmap smooth=Bitmap.createScaledBitmap(low,w*3,h*3,true);
        low.recycle();
        return smooth;
    }
}
