package fr.externalscreen.beastfingerdiagnostic;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local, camera-only fingertip-foreground fallback.
 *
 * Google's HandLandmarker requires a palm. When it reports zero hands, identify
 * moving skin-coloured pixels INSIDE the dynamically detected phone screen.
 * Foreground is compared against a slowly learned screen background in
 * normalized phone coordinates; static keyboard keys should not be rendered.
 *
 * This is a conservative, imperfect 2D vision heuristic: it cannot prove a
 * finger touches the glass, and works only when the RGB camera sees the skin.
 * No template/manual calibration or camera video overlay is required.
 */
public final class PhoneFingerSegmenter {
    private static final int SAMPLE_WIDTH=170;
    private int sampleHeight;
    private int[] background;
    private int[] current;
    private boolean[] candidate;
    private boolean[] mask;
    private boolean[] visited;
    private int[] queue;
    private volatile int lastSkinPixels;
    private volatile int lastMovingPixels;
    private volatile int lastRegions;
    private volatile int lastSamples;
    private volatile boolean backgroundReady;
    private int elapsed;
    private final float[] lastPhone = new float[4];

    public int skinPixels(){return lastSkinPixels;}
    public int movingPixels(){return lastMovingPixels;}
    public int regions(){return lastRegions;}
    public int sampledPixels(){return lastSamples;}
    public boolean ready(){return backgroundReady;}

    private static int clamp(int v,int a,int b){
        return Math.max(a,Math.min(b,v));
    }

    private static boolean isSkin(int pixel){
        int r=(pixel>>16)&255;
        int g=(pixel>>8)&255;
        int b=pixel&255;
        if(r<32 || g<18 || b<10 || r<g-16 || r<b-10)return false;
        int mx=Math.max(r,Math.max(g,b)), mn=Math.min(r,Math.min(g,b));
        if(mx-mn<17)return false; // exclude white/grey keyboard pixels
        // Broad chrominance region covers different skin tones and lighting.
        float cr=128f+0.5f*r-0.418688f*g-0.081312f*b;
        float cb=128f-0.168736f*r-0.331264f*g+0.5f*b;
        return cr>129f && cr<189f && cb>73f && cb<141f
            && r>g*.87f && g>b*.70f;
    }

    private static int difference(int x,int y){
        int dr=Math.abs(((x>>16)&255)-((y>>16)&255));
        int dg=Math.abs(((x>>8)&255)-((y>>8)&255));
        int db=Math.abs((x&255)-(y&255));
        return Math.max(dr,Math.max(dg,db));
    }

    public List<FingertipDetector.Patch> extract(Bitmap frame,RectF glass){
        lastRegions=0;
        lastSkinPixels=0;
        lastMovingPixels=0;
        if(frame==null || glass==null)return Collections.emptyList();
        int W=frame.getWidth(),H=frame.getHeight();
        int left=clamp((int)Math.floor(glass.left*W),0,W-2);
        int top=clamp((int)Math.floor(glass.top*H),0,H-2);
        int right=clamp((int)Math.ceil(glass.right*W),left+2,W);
        int bottom=clamp((int)Math.ceil(glass.bottom*H),top+2,H);
        int pw=right-left,ph=bottom-top;
        if(pw<18 || ph<25)return Collections.emptyList();
        int outW=Math.min(SAMPLE_WIDTH,pw);
        int outH=clamp((int)Math.round(ph*outW/(float)pw),25,420);
        int n=outW*outH;
        if(n>110000)return Collections.emptyList();

        Bitmap roi=Bitmap.createBitmap(frame,left,top,pw,ph);
        Bitmap resized=(pw==outW && ph==outH)?roi:
            Bitmap.createScaledBitmap(roi,outW,outH,true);
        int[] pixels=new int[n];
        try{
            resized.getPixels(pixels,0,outW,0,0,outW,outH);
        }finally{
            if(resized!=roi)resized.recycle();
            roi.recycle();
        }
        if(background==null || background.length!=n ||
            sampleHeight!=outH || current==null || current.length!=n){
            sampleHeight=outH;
            background=pixels.clone();
            current=pixels;
            candidate=new boolean[n];
            mask=new boolean[n];
            visited=new boolean[n];
            queue=new int[n];
            elapsed=0;
            lastSamples=n;
            backgroundReady=true;
            return Collections.emptyList();
        }

        current=pixels;
        elapsed++;
        lastSamples=n;
        // Screen position is tracked in real time; the normalized phone ROI
        // keeps the background approximately aligned across head motion.
        for(int i=0;i<n;i++){
            int now=pixels[i], ref=background[i];
            boolean skin=isSkin(now);
            if(skin) lastSkinPixels++;
            int delta=difference(now,ref);
            boolean changed=delta>24;
            boolean foreground=skin && changed;
            candidate[i]=foreground;
            if(foreground) lastMovingPixels++;
            else {
                // Slowly learn illumination and keyboard changes; never learn
                // away an active moving finger from the background.
                int ar=(ref>>16)&255,ag=(ref>>8)&255,ab=ref&255;
                int br=(now>>16)&255,bg=(now>>8)&255,bb=now&255;
                int rate=(delta>55)?3:16; // /256
                ar+=(br-ar)*rate/256;
                ag+=(bg-ag)*rate/256;
                ab+=(bb-ab)*rate/256;
                background[i]=0xff000000|(ar<<16)|(ag<<8)|ab;
            }
        }

        // Closing small skin holes (nails, highlights) and rejecting isolated
        // compression artifacts. No whole-hand image can leak through the mask.
        for(int y=1;y<outH-1;y++){
            int row=y*outW;
            for(int x=1;x<outW-1;x++){
                int i=row+x;
                int count=0;
                for(int dY=-1;dY<=1;dY++)
                    for(int dX=-1;dX<=1;dX++)
                        if(candidate[i+dY*outW+dX])count++;
                mask[i]=(candidate[i]&&count>=3)||(!candidate[i]&&count>=7);
            }
        }
        java.util.Arrays.fill(visited,false);
        ArrayList<FingertipDetector.Patch> result=new ArrayList<>();
        int minComponent=Math.max(12,n/2200);
        int maxComponent=(int)(n*.18f);
        for(int y=1;y<outH-1;y++){
            for(int x=1;x<outW-1;x++){
                int start=y*outW+x;
                if(!mask[start] || visited[start])continue;
                int front=0,back=0,area=0;
                queue[back++]=start;
                visited[start]=true;
                int minX=x,maxX=x,minY=y,maxY=y;
                while(front<back){
                    int i=queue[front++];
                    int cy=i/outW,cx=i%outW;
                    area++;
                    minX=Math.min(minX,cx);maxX=Math.max(maxX,cx);
                    minY=Math.min(minY,cy);maxY=Math.max(maxY,cy);
                    int[] neighbors={i-1,i+1,i-outW,i+outW};
                    for(int next:neighbors){
                        int nx=next%outW,ny=next/outW;
                        if(nx<1||nx>=outW-1||ny<1||ny>=outH-1)continue;
                        if(mask[next]&&!visited[next]){
                            visited[next]=true;
                            queue[back++]=next;
                        }
                    }
                }
                if(area<minComponent || area>maxComponent ||
                    maxX-minX<2 || maxY-minY<2)continue;
                minX=Math.max(0,minX-2);maxX=Math.min(outW-1,maxX+2);
                minY=Math.max(0,minY-2);maxY=Math.min(outH-1,maxY+2);
                int patchW=maxX-minX+1,patchH=maxY-minY+1;
                int[] rgba=new int[patchW*patchH];
                int filled=0;
                for(int yy=minY;yy<=maxY;yy++){
                    for(int xx=minX;xx<=maxX;xx++){
                        int src=yy*outW+xx;
                        if(!mask[src] || !visited[src])continue;
                        int close=0;
                        for(int dy=-1;dy<=1;dy++){
                            for(int dx=-1;dx<=1;dx++){
                                int ax=xx+dx,ay=yy+dy;
                                if(ax>=0&&ax<outW&&ay>=0&&ay<outH&&mask[ay*outW+ax])
                                    close++;
                            }
                        }
                        int alpha=close>=7?230:close>=4?175:110;
                        rgba[(yy-minY)*patchW+(xx-minX)]=(alpha<<24)|
                            (pixels[src]&0x00ffffff);
                        filled++;
                    }
                }
                if(filled<minComponent)continue;
                Bitmap skin=Bitmap.createBitmap(rgba,patchW,patchH,Bitmap.Config.ARGB_8888);
                float centerX=(minX+maxX+1f)/(2f*outW);
                float centerY=(minY+maxY+1f)/(2f*outH);
                float radiusX=patchW/(2f*outW);
                float radiusY=patchH/(2f*outH);
                result.add(new FingertipDetector.Patch(
                    skin,centerX,centerY,radiusX,radiusY));
                if(result.size()>=12)break;
            }
            if(result.size()>=12)break;
        }
        lastRegions=result.size();
        return result;
    }
}
