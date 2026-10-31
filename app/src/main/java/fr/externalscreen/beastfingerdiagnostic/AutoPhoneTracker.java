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

    public AutoPhoneTracker(Context context) {
        BaseOptions model=BaseOptions.builder()
            .setModelAssetPath("efficientdet_lite0_int8.tflite")
            .build();
        ObjectDetector.ObjectDetectorOptions opts=
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(model)
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(15)
                .setScoreThreshold(0.16f)
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
        ObjectDetectorResult result=detector.detect(new BitmapImageBuilder(downscaled).build());
        detections=result.detections().size();
        for(Detection detection:result.detections()) {
            if(detection.categories().isEmpty())continue;
            Category category=detection.categories().get(0);
            String name=category.categoryName();
            if(name==null)continue;
            String clean=name.toLowerCase(Locale.ROOT).replace('_',' ');
            if(!(clean.equals("cell phone") || clean.equals("mobile phone") ||
                clean.equals("cellphone") || clean.equals("phone") ||
                clean.equals("smartphone")))continue;
            RectF b=detection.boundingBox();
            if(b==null)continue;
            float left=b.left/downscaled.getWidth();
            float top=b.top/downscaled.getHeight();
            float right=b.right/downscaled.getWidth();
            float bottom=b.bottom/downscaled.getHeight();
            if(right-left<.025f || bottom-top<.035f ||
                right-left>.93f || bottom-top>.93f)continue;
            if(category.score()>score) {
                score=category.score();
                found=new RectF(
                    Math.max(0f,left),Math.max(0f,top),
                    Math.min(1f,right),Math.min(1f,bottom));
                label=name;
            }
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
        if(current!=null && now-lastSeenMs<=220L)return new RectF(current);
        current=null;
        lastConfidence=0f;
        lastLabel="aucun";
        return null;
    }
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
