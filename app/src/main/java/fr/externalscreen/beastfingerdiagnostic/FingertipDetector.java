package fr.externalscreen.beastfingerdiagnostic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Detects ONLY fingertips and extracts tiny real-image patches centered there.
 * Every fingertip is rejected unless projected into the calibrated phone screen
 * quadrilateral. No input gestures, entire hands, or video are sent to the
 * overlay. Detection is strictly on-device using the offline MediaPipe model.
 *
 * Phone screen position is initially unknown: until the user calibrates four
 * corners, the overlay stays empty by design (no erroneous fingertip positions).
 */
public final class FingertipDetector implements AutoCloseable {
    public static final String PREFS = "settings.v081";
    private final HandLandmarker model;
    private final Context context;
    public static final int[] TIPS = {4, 8, 12, 16, 20};
    public static final int[] NEAR_TIPS = {3, 7, 11, 15, 19};
    private static final int DETECT_WIDTH = 640;

    public static final class Patch {
        public final Bitmap image;
        public final float x, y, rx, ry;
        Patch(Bitmap image, float x, float y, float rx, float ry) {
            this.image=image;this.x=x;this.y=y;this.rx=rx;this.ry=ry;
        }
        public void release() {
            if (!image.isRecycled()) image.recycle();
        }
    }

    public FingertipDetector(Context context) {
        this.context = context.getApplicationContext();
        BaseOptions options = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task").build();
        HandLandmarker.HandLandmarkerOptions settings =
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(options)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.55f)
                .setMinHandPresenceConfidence(0.50f)
                .build();
        model = HandLandmarker.createFromOptions(this.context, settings);
    }

    /** Quad ordered top-left, top-right, bottom-right, bottom-left, normalized to camera image. */
    private float[] savedQuad() {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("phone_quad_ready", false)) return null;
        float[] p = new float[8];
        for (int i=0;i<8;i++) p[i] = prefs.getFloat("phone_quad_"+i, Float.NaN);
        for (float v : p) if (!Float.isFinite(v) || v<0 || v>1) return null;
        return p;
    }

    /**
     * Maps camera coordinates to physical phone screen coordinates using a
     * projective transform. Small image patches are cut from the real fingertips,
     * with feathered boundaries; surrounding video is never drawn.
     */
    public List<Patch> process(Bitmap original) {
        float[] quad=savedQuad();
        if (quad == null || original == null) return Collections.emptyList();
        int w=original.getWidth(), h=original.getHeight();
        int newW=Math.min(DETECT_WIDTH,w);
        int newH=Math.max(1,Math.round(h*newW/(float)w));
        Bitmap input = (newW == w) ? original : Bitmap.createScaledBitmap(original,newW,newH,true);
        HandLandmarkerResult result;
        try {
            result=model.detect(new BitmapImageBuilder(input).build());
        } finally {
            if (input != original) input.recycle();
        }
        float[] dst={0,0, 1,0, 1,1, 0,1};
        Matrix transform=new Matrix();
        // This is a full homography: the camera can see the phone at an angle.
        if (!transform.setPolyToPoly(quad,0,dst,0,4)) return Collections.emptyList();
        float quadWidth=(float)Math.hypot(quad[2]-quad[0],quad[3]-quad[1]);
        float quadHeight=(float)Math.hypot(quad[6]-quad[0],quad[7]-quad[1]);
        if (quadWidth < .05f || quadHeight < .05f) return Collections.emptyList();

        ArrayList<Patch> patches=new ArrayList<>();
        for (List<NormalizedLandmark> hand: result.landmarks()) {
            if (hand.size()<21) continue;
            for (int finger=0;finger<5;finger++) {
                NormalizedLandmark tip=hand.get(TIPS[finger]);
                NormalizedLandmark joint=hand.get(NEAR_TIPS[finger]);
                float u=tip.x(), v=tip.y();
                float[] uv={u,v};
                transform.mapPoints(uv);
                // Require the actual fingertip to project INSIDE the phone screen.
                // Exclude the very edges to suppress parts of fingers outside it.
                if (!(uv[0]>=0f && uv[0]<=1f && uv[1]>=0f && uv[1]<=1f))continue;
                float distance=(float)Math.hypot((tip.x()-joint.x())*w,
                                                (tip.y()-joint.y())*h);
                float sourceRadius=Math.max(10f,Math.min(38f,distance*1.15f));
                float radiusX=Math.max(.012f,Math.min(.10f,sourceRadius/(w*quadWidth)));
                float radiusY=Math.max(.008f,Math.min(.13f,sourceRadius/(h*quadHeight)));
                // Skip tip too close to frame edge: avoid invalid crops.
                int cx=Math.round(u*w),cy=Math.round(v*h),r=Math.round(sourceRadius);
                if (cx-r<0 || cy-r<0 || cx+r>=w || cy+r>=h)continue;
                Bitmap small = Bitmap.createBitmap(original,cx-r,cy-r,2*r,2*r);
                Bitmap feathered=feather(small);
                small.recycle();
                patches.add(new Patch(feathered,uv[0],uv[1],radiusX,radiusY));
            }
        }
        return patches;
    }

    private static Bitmap feather(Bitmap raw) {
        int w=raw.getWidth(),h=raw.getHeight();
        Bitmap output=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(output);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(raw,0,0,p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        p.setShader(new RadialGradient(w/2f,h/2f,Math.min(w,h)/2f,
            new int[]{0xffffffff,0xffffffff,0x00ffffff},
            new float[]{0.0f,.65f,1.0f},Shader.TileMode.CLAMP));
        canvas.drawCircle(w/2f,h/2f,Math.min(w,h)/2f,p);
        p.setXfermode(null);
        p.setShader(null);
        return output;
    }

    @Override public void close() { model.close(); }
}
