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
 * Automatically follows the phone as the wearer's head moves.
 *
 * A low-confidence COCO "cell phone" detection must be seen consistently
 * before it may create an overlay. Positions are smoothed for small motion,
 * refreshed promptly for large coherent motion, and discarded on loss.
 * No user X/Y offset or static calibration is involved.
 */
public final class AutoPhoneTracker implements AutoCloseable {
    private final ObjectDetector detector;
    private RectF confirmed;
    private RectF proposed;
    private int proposedFrames;
    private int frameNumber;
    private long lastConfirmedAt;
    private float lastConfidence;
    private String lastLabel="aucun";
    private int detectedObjects;
    private int consecutiveMisses;
    private volatile boolean tracking;
    private volatile boolean searchedLower;

    public AutoPhoneTracker(Context context) {
        BaseOptions model=BaseOptions.builder()
            .setModelAssetPath("efficientdet_lite0_int8.tflite").build();
        ObjectDetector.ObjectDetectorOptions options=
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(model)
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(80)
                .setScoreThreshold(0.14f)
                .build();
        detector=ObjectDetector.createFromOptions(context.getApplicationContext(),options);
    }

    public RectF locate(Bitmap camera) {
        if(camera==null)return null;
        frameNumber++;
        long now=SystemClock.elapsedRealtime();
        searchedLower=false;
        ObjectDetectorResult full=detector.detect(new BitmapImageBuilder(camera).build());
        detectedObjects=full.detections().size();
        PhoneCandidate candidate=selectBest(full,camera.getWidth(),camera.getHeight(),0,confirmed);

        // Small, partially occluded phones are often missed in the full image.
        // Try the lower image, but only when the full-frame result is weak.
        if((candidate==null || candidate.confidence<.33f) && (frameNumber%2==0)
            && camera.getHeight()>130) {
            int cropTop=camera.getHeight()/3;
            Bitmap crop=Bitmap.createBitmap(camera,0,cropTop,camera.getWidth(),
                camera.getHeight()-cropTop);
            try {
                ObjectDetectorResult lower=detector.detect(
                    new BitmapImageBuilder(crop).build());
                detectedObjects+=lower.detections().size();
                PhoneCandidate second=selectBest(lower,camera.getWidth(),
                    camera.getHeight(),cropTop,confirmed);
                if(second!=null && (candidate==null ||
                    score(second,confirmed)>score(candidate,confirmed)))
                    candidate=second;
                searchedLower=true;
            }finally{crop.recycle();}
        }

        boolean accepted=false;
        if(candidate!=null && candidate.confidence>=.24f){
            RectF box=candidate.box;
            if(confirmed!=null) {
                float similarity=iou(confirmed,box);
                float displacement=(float)Math.hypot(
                    centerX(confirmed)-centerX(box),centerY(confirmed)-centerY(box));
                // Reject isolated high-scoring boxes elsewhere in the image.
                boolean consistent=similarity>=.14f || displacement<.13f;
                if(consistent || candidate.confidence>=.66f) {
                    float alpha=displacement<.032f?.48f:.80f;
                    confirmed=blend(confirmed,box,alpha);
                    accepted=true;
                }
            } else {
                if(proposed!=null && (iou(proposed,box)>.22f ||
                    Math.hypot(centerX(proposed)-centerX(box),
                        centerY(proposed)-centerY(box))<.08f)){
                    proposed=blend(proposed,box,.65f);
                    proposedFrames++;
                }else {
                    proposed=new RectF(box);
                    proposedFrames=1;
                }
                // An exceptionally clear phone can be accepted immediately;
                // weak detections require two spatially consistent frames.
                if(candidate.confidence>=.56f || proposedFrames>=2) {
                    confirmed=new RectF(proposed);
                    accepted=true;
                    proposed=null;
                    proposedFrames=0;
                }
            }
            if(accepted){
                consecutiveMisses=0;
                lastConfirmedAt=now;
                lastConfidence=candidate.confidence;
                lastLabel=candidate.label;
                tracking=true;
                return new RectF(confirmed);
            }
        }

        consecutiveMisses++;
        if(confirmed!=null && now-lastConfirmedAt<=430L &&
            consecutiveMisses<=3){
            tracking=true; // short occlusion by the user's own fingers
            return new RectF(confirmed);
        }
        confirmed=null;
        tracking=false;
        lastConfidence=0f;
        lastLabel="aucun";
        if(proposedFrames>2){proposed=null;proposedFrames=0;}
        return null;
    }

    private static float centerX(RectF r){return (r.left+r.right)*.5f;}
    private static float centerY(RectF r){return (r.top+r.bottom)*.5f;}
    private static RectF blend(RectF a,RectF b,float alpha){
        return new RectF(a.left+(b.left-a.left)*alpha,
            a.top+(b.top-a.top)*alpha,
            a.right+(b.right-a.right)*alpha,
            a.bottom+(b.bottom-a.bottom)*alpha);
    }
    private static float iou(RectF a,RectF b){
        if(a==null||b==null)return 0f;
        float l=Math.max(a.left,b.left),r=Math.min(a.right,b.right);
        float t=Math.max(a.top,b.top),bottom=Math.min(a.bottom,b.bottom);
        if(r<=l||bottom<=t)return 0f;
        float intersect=(r-l)*(bottom-t);
        return intersect/Math.max(.0001f,a.width()*a.height()+b.width()*b.height()-intersect);
    }
    private static float score(PhoneCandidate candidate,RectF previous){
        if(candidate==null)return -1f;
        return candidate.confidence+(previous==null?0f:.30f*iou(candidate.box,previous));
    }
    private static final class PhoneCandidate {
        final RectF box;
        final float confidence;
        final String label;
        PhoneCandidate(RectF b,float confidence,String label){
            box=b;this.confidence=confidence;this.label=label;
        }
    }

    private static PhoneCandidate selectBest(ObjectDetectorResult detections,
        int imageW,int imageH,int cropTop,RectF previous) {
        PhoneCandidate best=null;
        for(Detection detection:detections.detections()) {
            if(detection.categories().isEmpty())continue;
            Category cat=detection.categories().get(0);
            String label=cat.categoryName();
            if(label==null)continue;
            String key=label.toLowerCase(Locale.ROOT).replace('_',' ');
            if(!(key.equals("cell phone")||key.equals("mobile phone")||
                key.equals("cellphone")||key.equals("phone")||
                key.equals("smartphone")))continue;
            if(cat.score()<.18f)continue;
            RectF b=detection.boundingBox();
            if(b==null)continue;
            float l=Math.max(0f,b.left/imageW);
            float t=Math.max(0f,(b.top+cropTop)/imageH);
            float r=Math.min(1f,b.right/imageW);
            float bottom=Math.min(1f,(b.bottom+cropTop)/imageH);
            if(r-l<.02f||bottom-t<.035f||r-l>.94f||bottom-t>.95f)
                continue;
            float aspect=(r-l)*imageW/Math.max(1f,(bottom-t)*imageH);
            if(aspect<.17f||aspect>2.3f)continue;
            PhoneCandidate item=new PhoneCandidate(new RectF(l,t,r,bottom),
                cat.score(),label);
            if(best==null||score(item,previous)>score(best,previous))
                best=item;
        }
        return best;
    }

    public boolean tracking(){return tracking;}
    public boolean lowerCropUsed(){return searchedLower;}
    public float confidence(){return lastConfidence;}
    public String label(){return lastLabel;}
    public int candidates(){return detectedObjects;}

    public static RectF estimatedGlass(RectF phone){
        if(phone==null)return null;
        float dx=phone.width()*.045f,dy=phone.height()*.062f;
        return new RectF(phone.left+dx,phone.top+dy,
            phone.right-dx,phone.bottom-dy);
    }

    @Override public void close(){detector.close();}
}
