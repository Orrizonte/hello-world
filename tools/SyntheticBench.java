import fr.externalscreen.beastfingerdiagnostic.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/**
 * Off-device bench: renders synthetic Beast-camera frames (room, phone in
 * perspective with a keyboard UI, thumbs over the screen, head motion) and
 * scores FrameProcessor against ground truth. Host JDK only.
 *   java -cp out SyntheticBench [scenario] [dumpDir]
 */
public class SyntheticBench {
    static final int W = 1280, H = 720, SW = 1080, SH = 2412;
    static Random rnd = new Random(7);

    static boolean FRAME = false, SMALL = false, BRIGHT = false;
    static int[] content(boolean dark) {
        int[] c = new int[SW * SH];
        int bg = dark ? 0xff202124 : 0xffffffff, kb = dark ? 0xff303134 : 0xffe8eaed,
            key = dark ? 0xff4a4b4f : 0xffffffff, ink = dark ? 0xffe8eaed : 0xff202124;
        for (int y = 0; y < SH; y++) for (int x = 0; x < SW; x++) {
            int v = y < SH * 0.58 ? bg : kb;
            if (y < 140) v = dark ? 0xff2b2c30 : 0xfff1f3f4; // status/app bar
            // chat bubbles
            if (y > 300 && y < 1200 && ((y / 160) % 2 == 0) && x > 80 && x < 700 && y % 160 > 30) v = dark ? 0xff3c4043 : 0xffe1f5fe;
            if (y >= SH * 0.6) {
                int ky = (int) ((y - SH * 0.6) / ((SH * 0.4) / 5)), kx = x / (SW / 10);
                float fx = (x % (SW / 10)) / (float) (SW / 10), fy = ((y - (float) (SH * 0.6)) % ((SH * 0.4f) / 5)) / ((SH * 0.4f) / 5);
                if (ky < 4 && fx > 0.08 && fx < 0.92 && fy > 0.1 && fy < 0.9) {
                    v = key;
                    if (fx > 0.38 && fx < 0.62 && fy > 0.35 && fy < 0.65 && ((x + y) % 7 < 4)) v = ink;
                }
            }
            if (FRAME && (x < 54 || y < 54 || x >= SW - 54 || y >= SH - 54)) v = 0xff000000;
            if (FRAME && (x < 27 || y < 27 || x >= SW - 27 || y >= SH - 27)) v = 0xffffffff;
            c[y * SW + x] = v;
        }
        return c;
    }

    static double SPEED = 1;
    static float[] quadAt(int f) {
        double t = f / 30.0 * SPEED;
        double cx = 640 + 60 * Math.sin(t * 1.3), cy = 400 + 35 * Math.sin(t * 0.9 + 1);
        double s = 1 + 0.08 * Math.sin(t * 0.7);
        double rot = Math.toRadians(6 * Math.sin(t * 1.1));
        double hw = (SMALL ? 80 : 118) * s, hh = (SMALL ? 150 : 215) * s, topShrink = 0.85; // perspective: top farther
        double[][] p = {{-hw * topShrink, -hh}, {hw * topShrink, -hh}, {hw, hh}, {-hw, hh}};
        float[] q = new float[8];
        for (int i = 0; i < 4; i++) {
            double x = p[i][0] * Math.cos(rot) - p[i][1] * Math.sin(rot);
            double y = p[i][0] * Math.sin(rot) + p[i][1] * Math.cos(rot);
            q[2 * i] = (float) (cx + x); q[2 * i + 1] = (float) (cy + y);
        }
        return q;
    }

    // thumbs in screen coords: {baseX, baseY, tipX, tipY, radius}
    static float[][] thumbs(int f) {
        double t = f / 30.0;
        float tap = (float) Math.max(0, Math.sin(t * 9));
        return new float[][]{
            {-200, SH * 0.97f, (float) (SW * (0.33 + 0.08 * Math.sin(t * 2.1))), (float) (SH * (0.73 + 0.03 * Math.sin(t * 3))) + 30 * tap, SW * 0.07f},
            {SW + 200, SH * 1.0f, (float) (SW * (0.66 + 0.07 * Math.sin(t * 1.7 + 1))), (float) (SH * (0.78 + 0.03 * Math.cos(t * 2.5))), SW * 0.072f}};
    }

    static float segDist(float px, float py, float[] th) {
        float ax = th[0], ay = th[1], bx = th[2], by = th[3];
        float dx = bx - ax, dy = by - ay;
        float tt = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)));
        return (float) Math.hypot(ax + tt * dx - px, ay + tt * dy - py);
    }

    public static void main(String[] a) throws Exception {
        String sc = a.length > 0 ? a[0] : "late";
        String dump = a.length > 1 ? a[1] : null;
        boolean dark = sc.contains("dark");
        boolean noLm = sc.contains("nolm");
        boolean early = sc.contains("early");
        FRAME = sc.contains("frame"); SMALL = sc.contains("small"); BRIGHT = sc.contains("bright"); SPEED = sc.contains("fast") ? 3.5 : 1;
        float dim = sc.contains("dim") ? 0.55f : 0.92f;
        int[] skinRgb = sc.contains("tone") ? new int[]{125, 85, 62} : new int[]{200, 145, 120};
        int[] scr = content(dark);
        FrameProcessor fp = new FrameProcessor();
        fp.setFrameMode(FRAME);
        int[] img = new int[W * H];
        double iouSum = 0; int iouN = 0; double cornerErr = 0; int found = 0;
        long ms = 0;
        int frames = 90;
        for (int f = 0; f < frames; f++) {
            float[] q = quadAt(f);
            float[] scrQ = {0, 0, SW, 0, SW, SH, 0, SH};
            Homography c2s = Homography.fromQuads(q, scrQ), s2c = Homography.fromQuads(scrQ, q);
            boolean handsOn = early || f >= 25;
            float[][] th = thumbs(f);
            // render
            float[] o = new float[2];
            float[] bez = new float[8];
            for (int i = 0; i < 4; i++) { // bezel 4% bigger
                float cx = (q[0] + q[2] + q[4] + q[6]) / 4, cy = (q[1] + q[3] + q[5] + q[7]) / 4;
                bez[2 * i] = cx + (q[2 * i] - cx) * 1.07f; bez[2 * i + 1] = cy + (q[2 * i + 1] - cy) * 1.05f;
            }
            Homography c2b = Homography.fromQuads(bez, scrQ);
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                int r, g, b;
                // room
                int base = (BRIGHT ? 150 : 70) + (int) (50.0 * y / H) + ((x / 64 + y / 64) % 2) * 8;
                if (BRIGHT && x > 900 && y < 420) base = 245; // big window
                r = base + 10; g = base; b = base - 12;
                if (x < 260 && y < 200) { r = g = b = 250; } // window / lamp
                c2b.map(x, y, o, 0);
                if (o[0] >= 0 && o[0] < SW && o[1] >= 0 && o[1] < SH) { r = 18; g = 18; b = 20; } // phone body
                c2s.map(x, y, o, 0);
                boolean onScreen = o[0] >= 0 && o[0] < SW && o[1] >= 0 && o[1] < SH;
                if (onScreen) {
                    int p = scr[(int) o[1] * SW + (int) o[0]];
                    r = (int) (((p >> 16) & 255) * dim + 18); g = (int) (((p >> 8) & 255) * dim + 18); b = (int) ((p & 255) * dim + 15);
                }
                if (handsOn) {
                    for (float[] t : th) {
                        float d = segDist(o[0], o[1], t);
                        if (d < t[4]) {
                            float sh = 0.75f + 0.25f * (1 - d / t[4]);
                            r = (int) (skinRgb[0] * sh); g = (int) (skinRgb[1] * sh); b = (int) (skinRgb[2] * sh);
                        }
                    }
                }
                int nz = (int) (rnd.nextGaussian() * 4);
                r = Math.max(0, Math.min(255, r + nz)); g = Math.max(0, Math.min(255, g + nz)); b = Math.max(0, Math.min(255, b + nz));
                img[y * W + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
            // synthetic landmarks (camera px)
            List<float[]> hands = new ArrayList<>();
            if (handsOn && !noLm) {
                for (int k = 0; k < 2; k++) {
                    float[] t = th[k], lm = new float[42];
                    float[][] sp = new float[21][];
                    float side = k == 0 ? -1 : 1;
                    sp[0] = new float[]{t[0] - side * 60, SH * 1.25f};
                    for (int j = 1; j <= 4; j++) { float u = 0.25f + 0.25f * (j - 1) * 0.97f + 0.02f; sp[j] = new float[]{t[0] + (t[2] - t[0]) * u, t[1] + (t[3] - t[1]) * u}; }
                    sp[4] = new float[]{t[2], t[3]};
                    for (int j = 5; j <= 20; j++) { // fingers behind the phone
                        int fi = (j - 5) / 4, ji = (j - 5) % 4;
                        sp[j] = new float[]{t[0] + side * (-40 + 10 * ji), SH * (0.98f + 0.05f * fi) + 30 * ji};
                    }
                    for (int j = 0; j < 21; j++) {
                        s2c.map(sp[j][0], sp[j][1], o, 0);
                        lm[2 * j] = o[0] + (float) rnd.nextGaussian() * 2.5f;
                        lm[2 * j + 1] = o[1] + (float) rnd.nextGaussian() * 2.5f;
                    }
                    hands.add(lm);
                }
            }
            long t0 = System.nanoTime();
            FrameProcessor.Result res = fp.process(img, W, H, hands, f * 33_333_333L, SW, SH, f * 33_333_333L);
            long dt = (System.nanoTime() - t0) / 1_000_000;
            if (f > 5) ms += dt;
            if (res.quad != null) {
                found++;
                double e = 0; for (int i = 0; i < 8; i++) e = Math.max(e, Math.abs(res.quad[i] - q[i]));
                cornerErr += e;
            }
            // IoU at grid resolution
            FingerSegmenter s = fp.segmenter();
            int gw = s.gridW(), gh = s.gridH();
            double inter = 0, uni = 0;
            if (gw > 0 && res.quad != null) {
                float[] fld = s.field();
                for (int gy = 0; gy < gh; gy++) for (int gx = 0; gx < gw; gx++) {
                    float sx = (gx + 0.5f) * SW / gw, sy = (gy + 0.5f) * SH / gh;
                    boolean gt = false;
                    if (handsOn) for (float[] t : th) if (segDist(sx, sy, t) < t[4]) gt = true;
                    boolean pr = fld[gy * gw + gx] > 0.5f;
                    if (gt && pr) inter++;
                    if (gt || pr) uni++;
                }
            }
            double iou = uni == 0 ? 1 : inter / uni;
            if (handsOn && f >= (early ? 15 : 35)) { iouSum += iou; iouN++; }
            if (f % 10 == 0 || f == frames - 1)
                System.out.printf("f%02d %3dms hands=%b iou=%.3f fp=%.0f | %s%n", f, dt, handsOn, iou, uni - inter, res.diag);
            if (dump != null && (f == 2 || f == 40 || f == 80)) { dumpFrame(img, res, s, dump + "/" + sc + "_" + f); try { Class.forName("Dbg").getMethod("dump", FingerSegmenter.class, String.class).invoke(null, s, dump + "/" + sc + "_" + f + "_fields.png"); } catch (Throwable e) { } }
        }
        System.out.printf("SCENARIO %s: screen %d/%d, mean max-corner err %.2f px, mean IoU %.3f, %.1f ms/frame%n",
            sc, found, frames, cornerErr / Math.max(1, found), iouSum / Math.max(1, iouN), ms / (double) (frames - 6));
    }

    static void dumpFrame(int[] img, FrameProcessor.Result res, FingerSegmenter s, String path) throws Exception {
        BufferedImage bi = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        bi.setRGB(0, 0, W, H, img, 0, W);
        java.awt.Graphics2D g = bi.createGraphics();
        if (res.quad != null) {
            g.setColor(java.awt.Color.GREEN);
            for (int i = 0; i < 4; i++) { int j = (i + 1) % 4; g.drawLine((int) res.quad[2 * i], (int) res.quad[2 * i + 1], (int) res.quad[2 * j], (int) res.quad[2 * j + 1]); }
        }
        ImageIO.write(bi, "png", new File(path + "_cam.png"));
        // screen-space render of contours
        BufferedImage sv = new BufferedImage(SW / 3, SH / 3, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2 = sv.createGraphics();
        int[] scr = content(path.contains("dark"));
        for (int y = 0; y < SH / 3; y++) for (int x = 0; x < SW / 3; x++) sv.setRGB(x, y, scr[(y * 3) * SW + x * 3]);
        g2.setColor(java.awt.Color.MAGENTA);
        g2.setStroke(new java.awt.BasicStroke(2f));
        for (float[] c : res.contours) {
            int n = c.length / 2;
            for (int i = 0; i < n; i++) { int j = (i + 1) % n; g2.drawLine((int) (c[2 * i] / 3), (int) (c[2 * i + 1] / 3), (int) (c[2 * j] / 3), (int) (c[2 * j + 1] / 3)); }
        }
        ImageIO.write(sv, "png", new File(path + "_screen.png"));
    }
}
