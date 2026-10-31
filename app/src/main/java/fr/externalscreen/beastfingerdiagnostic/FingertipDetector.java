package fr.externalscreen.beastfingerdiagnostic;

import android.content.Context;
import android.graphics.Bitmap;
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
 * The only rendered content is real-camera image of the 5 fingertips when they
 * project INTO the phone's automatically recognized screen region.
 * Two independent offline MediaPipe tasks run on the same reduced camera frame:
 * ObjectDetector -> phone position; HandLandmarker -> finger-tip positions.
 * Camera coordinates are mapped relative to the CURRENT phone location, so
 * head motion never invalidates a saved calibration.
 */
public final class FingertipDetector implements AutoCloseable {
    private final HandLandmarker handLandmarker;
    private final AutoPhoneTracker phoneTracker;
    private final PhoneFingerSegmenter fingerSegmenter = new PhoneFingerSegmenter();
    private final TipTemporalFilter temporalFilter = new TipTemporalFilter();
    private long lastVideoTimeMs;
    private final Context context;
    public static final int[] TIPS={4,8,12,16,20};
    public static final int[] NEAR_TIPS={3,7,11,15,19};
    private static final int DETECT_WIDTH=640;
    private volatile int lastHands;
    private volatile int lastInside;
    private volatile int lastCandidates;
    private volatile boolean phoneVisible;
    private volatile float lastPhoneConfidence;
    private volatile int lastObjects;
    private volatile String lastPhoneLabel="aucun";
    private volatile int lastSkinPixels;
    private volatile int lastMovingPixels;
    private volatile int lastFallbackRegions;
    private volatile int lastPhonePixels;
    private volatile boolean lastBackgroundReady;

    public int lastHands(){return lastHands;}
    public int lastInside(){return lastInside;}
    public int lastCandidates(){return lastCandidates;}
    public boolean hasCalibration(){return true;} // nothing to calibrate
    public boolean phoneVisible(){return phoneVisible;}
    public float phoneConfidence(){return lastPhoneConfidence;}
    public int objectCandidates(){return lastObjects;}
    public String phoneLabel(){return lastPhoneLabel;}
    public int skinPixels(){return lastSkinPixels;}
    public int movingPixels(){return lastMovingPixels;}
    public int fallbackRegions(){return lastFallbackRegions;}
    public int screenSamples(){return lastPhonePixels;}
    public boolean backgroundReady(){return lastBackgroundReady;}

    public static final class Patch {
        public final Bitmap image;
        public float x,y,rx,ry;
        Patch(Bitmap image,float x,float y,float rx,float ry){
            this.image=image;this.x=x;this.y=y;this.rx=rx;this.ry=ry;
        }
        public void release(){if(!image.isRecycled())image.recycle();}
    }

    public FingertipDetector(Context context){
        this.context=context.getApplicationContext();
        BaseOptions model=BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task").build();
        HandLandmarker.HandLandmarkerOptions opts=
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(model)
                .setRunningMode(RunningMode.VIDEO)
                .setMinTrackingConfidence(.52f)
                .setNumHands(2)
                .setMinHandDetectionConfidence(.40f)
                .setMinHandPresenceConfidence(.40f)
                .build();
        HandLandmarker hands=HandLandmarker.createFromOptions(this.context,opts);
        try {
            phoneTracker=new AutoPhoneTracker(this.context);
        } catch(Throwable error){
            hands.close();
            if(error instanceof RuntimeException)throw (RuntimeException)error;
            throw new IllegalStateException("Initialisation du modèle téléphone",error);
        }
        handLandmarker=hands;
    }

    public List<Patch> process(Bitmap original) {
        if(original==null)return Collections.emptyList();
        int w=original.getWidth(),h=original.getHeight();
        int resizedW=Math.min(DETECT_WIDTH,w);
        int resizedH=Math.max(1,Math.round((float)h*resizedW/w));
        Bitmap reduced=(resizedW==w)?original:
            Bitmap.createScaledBitmap(original,resizedW,resizedH,true);
        HandLandmarkerResult handResult;
        RectF phone;
        try {
            // The phone is tracked in every frame, not just once.
            phone=phoneTracker.locate(reduced);
            // Google MediaPipe sample: VIDEO mode reuses tracked landmarks,
            // rather than searching for a fresh palm on every USB camera image.
            long now=android.os.SystemClock.uptimeMillis();
            if(now<=lastVideoTimeMs)now=lastVideoTimeMs+1;
            lastVideoTimeMs=now;
            handResult=handLandmarker.detectForVideo(
                new BitmapImageBuilder(reduced).build(),now);
        }finally {
            if(reduced!=original)reduced.recycle();
        }
        lastHands=handResult.landmarks().size();
        lastCandidates=0;
        lastInside=0;
        lastObjects=phoneTracker.candidates();
        lastPhoneConfidence=phoneTracker.confidence();
        lastPhoneLabel=phoneTracker.label();
        phoneVisible=phone!=null;
        if(phone==null){
            fingerSegmenter.reset();
            temporalFilter.reset();
            lastSkinPixels=0;
            lastMovingPixels=0;
            lastFallbackRegions=0;
            lastPhonePixels=0;
            lastBackgroundReady=false;
            return Collections.emptyList();
        }

        RectF glass=AutoPhoneTracker.estimatedGlass(phone);
        if(glass==null || glass.width()<.02f || glass.height()<.03f)
            return Collections.emptyList();

        // Segmentation does not require palms, unlike HandLandmarker. This
        // fallback is designed for only-visible-fingers near a mobile screen.
        List<Patch> skinRegions=fingerSegmenter.extract(original,glass);
        lastSkinPixels=fingerSegmenter.skinPixels();
        lastMovingPixels=fingerSegmenter.movingPixels();
        lastFallbackRegions=fingerSegmenter.regions();
        lastPhonePixels=fingerSegmenter.sampledPixels();
        lastBackgroundReady=fingerSegmenter.ready();
        ArrayList<Patch> patches=new ArrayList<>();
        for(List<NormalizedLandmark> hand:handResult.landmarks()){
            if(hand.size()<21)continue;
            for(int finger=0;finger<5;finger++){
                lastCandidates++;
                NormalizedLandmark tip=hand.get(TIPS[finger]);
                NormalizedLandmark joint=hand.get(NEAR_TIPS[finger]);
                float u=tip.x(),v=tip.y();
                // Only show projected tips WITHIN the physical phone's screen.
                if(!glass.contains(u,v))continue;
                lastInside++;
                float px=(u-glass.left)/glass.width();
                float py=(v-glass.top)/glass.height();

                float distance=(float)Math.hypot((tip.x()-joint.x())*w,
                                                 (tip.y()-joint.y())*h);
                float radius=Math.max(9f,Math.min(30f,distance*.84f));
                int r=Math.max(7,Math.round(radius));
                int cx=Math.round(u*w),cy=Math.round(v*h);
                if(cx-r<0||cy-r<0||cx+r>=w||cy+r>=h)continue;
                // Extract ONLY a small fingertip image, never the whole hand.
                // Draw a translucent XR-style capsule around the detected
                // fingertip. No source camera pixels reach the overlay.
                Bitmap drawn=FingerOutlinePainter.landmark(
                    tip.x(),tip.y(),joint.x(),joint.y());
                // Project pixel size relative to the current apparent size
                // of the phone, not relative to the entire camera frame.
                float radiusX=Math.max(.009f,Math.min(.085f,r/(w*glass.width())));
                float radiusY=Math.max(.006f,Math.min(.11f,r/(h*glass.height())));
                patches.add(new Patch(drawn,px,py,radiusX,radiusY));
            }
        }
        // Trust MediaPipe fingertips when visible; add skin motion fragments
        // only when they do not duplicate a recognized fingertip position.
        // This handles a partly visible hand without showing 12 fragments.
        for(Patch skin:skinRegions){
            boolean duplicate=false;
            for(Patch ml:patches){
                if(Math.hypot(ml.x-skin.x,ml.y-skin.y)<.085f){
                    duplicate=true;break;
                }
            }
            if(!duplicate && patches.size()<5)
                patches.add(skin);
            else skin.release();
        }
        return temporalFilter.apply(patches);
    }

    private static Bitmap feather(Bitmap src){
        int w=src.getWidth(),h=src.getHeight();
        Bitmap dst=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(dst);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(src,0,0,p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        p.setShader(new RadialGradient(w/2f,h/2f,Math.min(w,h)/2f,
            new int[]{0xffffffff,0xffffffff,0x00ffffff},
            new float[]{0f,.52f,1f},Shader.TileMode.CLAMP));
        c.drawCircle(w/2f,h/2f,Math.min(w,h)/2f,p);
        p.setXfermode(null);
        p.setShader(null);
        return dst;
    }

    @Override public void close(){
        phoneTracker.close();
        handLandmarker.close();
    }
}
