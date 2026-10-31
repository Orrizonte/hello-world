package fr.externalscreen.beastfingerdiagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Temporally stable finger tracks in the AUTO-DETECTED PHONE coordinate system.
 * Avoid the "ghosts" caused by one-frame colour detections jumping between
 * keyboard pixels. At least two consistent frames are required to draw a new
 * outline; lost tracks are hidden immediately instead of floating in place.
 */
public final class TipTemporalFilter {
    private static final float MAX_MATCH_DIST=.18f;
    private final ArrayList<Track> tracks=new ArrayList<>();
    private int sequence;
    private static final class Track {
        float x,y,rx,ry;
        int hits,missed,frame;
        boolean matched;
        Track(FingertipDetector.Patch p,int sequence){
            x=p.x;y=p.y;rx=p.rx;ry=p.ry;
            hits=1;frame=sequence;matched=true;
        }
    }

    public void reset(){
        tracks.clear();
        sequence=0;
    }

    public List<FingertipDetector.Patch> apply(
            List<FingertipDetector.Patch> detected){
        sequence++;
        if(detected==null)detected=Collections.emptyList();
        for(Track t:tracks)t.matched=false;
        ArrayList<FingertipDetector.Patch> stable=new ArrayList<>();
        // The ML fingertip patches are first, then independent skin contours.
        for(FingertipDetector.Patch p:detected){
            Track nearest=null;
            float best=MAX_MATCH_DIST;
            for(Track t:tracks){
                if(t.matched)continue;
                float dx=p.x-t.x,dy=p.y-t.y;
                // More tolerant of vertical typing motions than unrelated
                // horizontal jumps to a different key.
                float d=(float)Math.hypot(dx,dy*.8f);
                if(d<best){best=d;nearest=t;}
            }
            if(nearest==null){
                if(tracks.size()<10)tracks.add(new Track(p,sequence));
                p.release(); // never show an unconfirmed one-frame detection
                continue;
            }
            nearest.matched=true;
            nearest.frame=sequence;
            nearest.missed=0;
            nearest.hits=Math.min(12,nearest.hits+1);
            float movement=(float)Math.hypot(p.x-nearest.x,p.y-nearest.y);
            // Smooth stationary digits strongly, but follow faster typing.
            float gain=movement>.06f?.72f:.44f;
            nearest.x+=gain*(p.x-nearest.x);
            nearest.y+=gain*(p.y-nearest.y);
            nearest.rx+=.32f*(p.rx-nearest.rx);
            nearest.ry+=.32f*(p.ry-nearest.ry);
            p.x=nearest.x;
            p.y=nearest.y;
            p.rx=nearest.rx;
            p.ry=nearest.ry;
            if(nearest.hits>=2 && stable.size()<5)stable.add(p);
            else p.release();
        }
        for(int i=tracks.size()-1;i>=0;i--){
            Track t=tracks.get(i);
            if(!t.matched && ++t.missed>2)tracks.remove(i);
        }
        return stable;
    }
}
