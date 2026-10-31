package fr.externalscreen.beastfingerdiagnostic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.SystemClock;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Detection;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;
import java.util.Locale;

/**
 * Real-time automatic phone detector for Viture's outward-facing RGB camera.
 *
 * No 4-point manual calibration, no recorded static camera-to-phone transform.
 * The screen follows a freshly recognized phone bounding box at runtime.
 * Failure to recognize the phone produces NO fingertip overlay (safe default).
 *
 * The model recognizes the whole phone, not the exact glass edge. A small
 * border inset prevents fingertips next to the handset from being shown.
 */
public final class AutoPhoneTracker implements AutoCloseable {
    private final ObjectDetector detector;
    private RectF current; // normalized camera image coordinates
    private long lastSeenMs;
    private volatile float lastConfidence;
    private volatile String lastLabel="aucun";
    private volatile int detections;
    private int frameNumber;
    private volatile boolean lowerCropUsed;

    public AutoPhoneTracker(Context context) {
        BaseOptions model=BaseOptions.builder()
            .setModelAssetPath("efficientdet_lite0_int8.tflite")
            .build();
        ObjectDetector.ObjectDetectorOptions opts=
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(model)
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(80)
                .setScoreThreshold(0.12f)
                .build();
        detector=ObjectDetector.createFromOptions(context.getApplicationContext(),opts);
    }

    /**
     * Called from the same background worker as HandLandmarker. Normalized
     * image rectangle remains valid when the user moves their head.
     */
    public RectF locate(Bitmap downscaled) {
        if(downscaled==null)return null;
        long now=SystemClock.elapsedRealtime();
        RectF found=null;
        float score=0f;
        String label="aucun";
        frameNumber++;
        lowerCropUsed=false;
        ObjectDetectorResult result=detector.detect(new BitmapImageBuilder(downscaled).build());
        detections=result.detections().size();
        PhoneCandidate chosen=bestPhone(result,downscaled.getWidth(),
            downscaled.getHeight(),0);
        // Extra scan of the lower field of view if a small phone was missed
        // among dozens of other COCO categories. No calibration is involved.
        if(chosen==null && frameNumber%2==0 && downscaled.getHeight()>120){
            int cropTop=downscaled.getHeight()/3;
            Bitmap crop=Bitmap.createBitmap(downscaled,0,cropTop,
                downscaled.getWidth(),downscaled.getHeight()-cropTop);
            try {
                ObjectDetectorResult bottom=detector.detect(
                    new BitmapImageBuilder(crop).build());
                detections+=bottom.detections().size();
                chosen=bestPhone(bottom,downscaled.getWidth(),
                    downscaled.getHeight(),cropTop);
                lowerCropUsed=true;
            }finally {
                crop.recycle();
            }
        }
        if(chosen!=null){
            found=chosen.box;
            score=chosen.score;
            label=chosen.label;
        }
        if(found!=null) {
            current=found;
            lastSeenMs=now;
            lastConfidence=score;
            lastLabel=label;
            return new RectF(found);
        }
        // Tiny grace period for single-frame occlusion from fingertips, but no
        // persistent stale calibration when the phone or the head moves away.
        if(current!=null && now-lastSeenMs<=650L)return new RectF(current);
        current=null;
        lastConfidence=0f;
        lastLabel="aucun";
        return null;
    }
    private static final class PhoneCandidate {
        final RectF box;
        final float score;
        final String label;
        PhoneCandidate(RectF b,float s,String l){box=b;score=s;label=l;}
    }

    private static PhoneCandidate bestPhone(ObjectDetectorResult detections,
            int fullWidth,int fullHeight,int cropTop) {
        PhoneCandidate best=null;
        for(Detection detection:detections.detections()){
            if(detection.categories().isEmpty())continue;
            Category cat=detection.categories().get(0);
            String name=cat.categoryName();
            if(name==null)continue;
            String clean=name.toLowerCase(Locale.ROOT).replace('_',' ');
            if(!(clean.equals("cell phone")||clean.equals("mobile phone")||
                clean.equals("cellphone")||clean.equals("phone")||
                clean.equals("smartphone")))continue;
            if(cat.score()<.16f)continue;
            RectF b=detection.boundingBox();
            if(b==null)continue;
            float l=Math.max(0f,b.left/fullWidth);
            float t=Math.max(0f,(b.top+cropTop)/fullHeight);
            float r=Math.min(1f,b.right/fullWidth);
            float bottom=Math.min(1f,(b.bottom+cropTop)/fullHeight);
            if(r-l<.018f||bottom-t<.027f||r-l>.9f||bottom-t>.94f)continue;
            float pixelAspect=(r-l)*fullWidth/Math.max(1f,(bottom-t)*fullHeight);
            // Most handheld phones are portrait, but modest tilt is allowed.
            if(pixelAspect<.16f || pixelAspect>2.4f)continue;
            if(best==null || cat.score()>best.score)
                best=new PhoneCandidate(new RectF(l,t,r,bottom),cat.score(),name);
        }
        return best;
    }

    public boolean lowerCropUsed(){return lowerCropUsed;}
    public float confidence(){return lastConfidence;}
    public String label(){return lastLabel;}
    public int candidates(){return detections;}

    /**
     * Retain only the central glass area; phone detector includes bezels.
     * 3%/6% inward bounds are a conservative approximation, not exact geometry.
     */
    public static RectF estimatedGlass(RectF phone) {
        if(phone==null)return null;
        float dx=phone.width()*.035f,dy=phone.height()*.055f;
        return new RectF(phone.left+dx,phone.top+dy,
            phone.right-dx,phone.bottom-dy);
    }
    @Override public void close(){detector.close();}
}
