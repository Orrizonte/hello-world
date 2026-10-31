package fr.externalscreen.beastfingerdiagnostic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finger silhouette over the phone screen, computed in RECTIFIED screen space.
 *
 * Every camera frame is resampled onto a fixed grid aligned with the phone's
 * display (through the camera->screen homography), so head motion does not move
 * anything in this space. Three cues are fused per cell:
 *  - per-cell background model of what the screen normally shows (learned
 *    online, exposure-compensated): an occluding finger differs from it;
 *  - adaptive skin colour model (Cb/Cr Gaussian learned from pixels sampled
 *    along MediaPipe finger bones anywhere in the camera image);
 *  - spatial prior: capsules around MediaPipe hand bones mapped onto the screen.
 * Components must either be supported by the hand prior or enter the screen
 * from its border (a real finger always does). The field is smoothed in time
 * and space and its 0.5 iso-line gives sub-cell contours. No calibration.
 */
public final class FingerSegmenter {
    public static final int PAD = 2;

    // Hand bones (MediaPipe 21-landmark topology).
    static final int[][] BONES = {
        {0, 1}, {1, 2}, {2, 3}, {3, 4},
        {0, 5}, {5, 6}, {6, 7}, {7, 8},
        {5, 9}, {9, 10}, {10, 11}, {11, 12},
        {9, 13}, {13, 14}, {14, 15}, {15, 16},
        {13, 17}, {0, 17}, {17, 18}, {18, 19}, {19, 20}};
    // Bones whose centre line is reliably on skin (used to learn skin colour).
    static final int[][] SKIN_BONES = {{1, 2}, {2, 3}, {5, 6}, {6, 7}, {9, 10}, {10, 11},
        {13, 14}, {14, 15}, {17, 18}, {18, 19}, {0, 9}, {5, 17}};

    private int gw, gh, n;
    private float[] bgY, bgCb, bgCr, varY, varC;
    private boolean[] bgValid;
    private float[] cy, ccb, ccr, prior, skin, raw, field, blur;
    private int[] labels, queue;
    private boolean[] rejected;
    private final Contours contours = new Contours();

    // Skin model (Cb, Cr) mean and covariance.
    private float sCb = 112, sCr = 150, sVbb = 120, sVrr = 160, sVbr = -30;
    private int skinUpdates;
    private float gain = 1f;

    // Diagnostics
    public int lastFgCells, lastComponents, lastRejected, lastValidPct;
    public float lastGain;

    public void reset() { gw = 0; gh = 0; }

    public int gridW() { return gw; }
    public int gridH() { return gh; }
    public float[] field() { return blur; }
    public boolean skinTrained() { return skinUpdates > 0; }

    private void allocate(int w, int h) {
        gw = w; gh = h; n = w * h;
        bgY = new float[n]; bgCb = new float[n]; bgCr = new float[n];
        varY = new float[n]; varC = new float[n]; bgValid = new boolean[n];
        cy = new float[n]; ccb = new float[n]; ccr = new float[n];
        prior = new float[n]; skin = new float[n]; raw = new float[n];
        field = new float[n]; blur = new float[n];
        labels = new int[n]; queue = new int[n]; rejected = new boolean[n];
    }

    /**
     * @param argb camera frame, w*h
     * @param screenToCam homography screen px -> camera px
     * @param camToScreen homography camera px -> screen px
     * @param hands landmark arrays (42 floats, camera px), may be empty
     * @param sw,sh screen size (px) in its current orientation
     * @return contours in screen pixels
     */
    public List<float[]> process(int[] argb, int w, int h, Homography screenToCam, Homography camToScreen,
                                 List<float[]> hands, int sw, int sh) {
        // Fixed cell count, aspect follows the display orientation.
        int longSide = 260, shortSide = 118;
        int tgw = sw <= sh ? shortSide : longSide;
        int tgh = sw <= sh ? Math.round(shortSide * (float) sh / sw) : Math.round(longSide * (float) sh / sw);
        tgh = Math.max(40, Math.min(tgh, 420));
        if (tgw != gw || tgh != gh) allocate(tgw, tgh);
        float cellW = sw / (float) gw, cellH = sh / (float) gh;

        sample(argb, w, h, screenToCam, cellW, cellH);
        learnSkin(argb, w, h, hands);
        boolean hasHand = buildPrior(hands, camToScreen, cellW, cellH);
        exposure();
        fuse(hasHand);
        smoothAndFilter(hasHand);
        updateBackground();

        // iso-contours on the padded field (replicated border so fingers
        // entering from the edge close outside the visible screen).
        int pw = gw + 2 * PAD, ph = gh + 2 * PAD;
        float[] padded = new float[pw * ph];
        for (int y = 0; y < ph; y++) {
            int yy = Math.max(0, Math.min(gh - 1, y - PAD));
            for (int x = 0; x < pw; x++) {
                int xx = Math.max(0, Math.min(gw - 1, x - PAD));
                padded[y * pw + x] = blur[yy * gw + xx];
            }
        }
        List<float[]> loops = contours.extract(padded, pw, ph, 0.5f, 6f);
        ArrayList<float[]> out = new ArrayList<>(loops.size());
        for (float[] l : loops) {
            float[] s = Contours.chaikin(l);
            for (int i = 0; i < s.length; i += 2) {
                s[i] = (s[i] - PAD + 0.5f) * cellW;
                s[i + 1] = (s[i + 1] - PAD + 0.5f) * cellH;
            }
            out.add(Contours.decimate(s, 2.5f));
        }
        return out;
    }

    // ------------------------------------------------------------ sampling
    private void sample(int[] argb, int w, int h, Homography H, float cellW, float cellH) {
        double[] m = H.h;
        float[] qx = {-0.25f, 0.25f, -0.25f, 0.25f}, qy = {-0.25f, -0.25f, 0.25f, 0.25f};
        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                float ar = 0, ag = 0, ab = 0; int cnt = 0;
                for (int k = 0; k < 4; k++) {
                    double sx = (gx + 0.5 + qx[k]) * cellW, sy = (gy + 0.5 + qy[k]) * cellH;
                    double ww = m[6] * sx + m[7] * sy + m[8];
                    float x = (float) ((m[0] * sx + m[1] * sy + m[2]) / ww);
                    float y = (float) ((m[3] * sx + m[4] * sy + m[5]) / ww);
                    if (x < 0 || y < 0 || x > w - 2 || y > h - 2) continue;
                    int x0 = (int) x, y0 = (int) y;
                    float fx = x - x0, fy = y - y0;
                    int i = y0 * w + x0;
                    int p00 = argb[i], p10 = argb[i + 1], p01 = argb[i + w], p11 = argb[i + w + 1];
                    float w00 = (1 - fx) * (1 - fy), w10 = fx * (1 - fy), w01 = (1 - fx) * fy, w11 = fx * fy;
                    ar += w00 * ((p00 >> 16) & 255) + w10 * ((p10 >> 16) & 255) + w01 * ((p01 >> 16) & 255) + w11 * ((p11 >> 16) & 255);
                    ag += w00 * ((p00 >> 8) & 255) + w10 * ((p10 >> 8) & 255) + w01 * ((p01 >> 8) & 255) + w11 * ((p11 >> 8) & 255);
                    ab += w00 * (p00 & 255) + w10 * (p10 & 255) + w01 * (p01 & 255) + w11 * (p11 & 255);
                    cnt++;
                }
                int c = gy * gw + gx;
                if (cnt == 0) { cy[c] = -1; continue; }
                float r = ar / cnt, g = ag / cnt, b = ab / cnt;
                cy[c] = 0.299f * r + 0.587f * g + 0.114f * b;
                ccb[c] = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b;
                ccr[c] = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b;
            }
        }
    }

    // ------------------------------------------------------------ skin model
    private void learnSkin(int[] argb, int w, int h, List<float[]> hands) {
        if (hands == null || hands.isEmpty()) return;
        float[] cb = new float[256], cr = new float[256];
        int m = 0;
        for (float[] lm : hands) {
            for (int[] b : SKIN_BONES) {
                for (int k = 1; k <= 3 && m < 256; k++) {
                    float t = k / 4f;
                    float x = lm[2 * b[0]] + t * (lm[2 * b[1]] - lm[2 * b[0]]);
                    float y = lm[2 * b[0] + 1] + t * (lm[2 * b[1] + 1] - lm[2 * b[0] + 1]);
                    int xi = Math.round(x), yi = Math.round(y);
                    if (xi < 1 || yi < 1 || xi >= w - 1 || yi >= h - 1) continue;
                    float r = 0, g = 0, bl = 0;
                    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                        int p = argb[(yi + dy) * w + xi + dx];
                        r += (p >> 16) & 255; g += (p >> 8) & 255; bl += p & 255;
                    }
                    r /= 9; g /= 9; bl /= 9;
                    float Y = 0.299f * r + 0.587f * g + 0.114f * bl;
                    if (Y < 35 || Y > 240) continue;
                    float pcb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * bl;
                    float pcr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * bl;
                    // broad generic skin gamut: rejects the phone body, sleeves,
                    // table... where hidden landmarks (fingers behind the phone) land
                    if (pcr < 135 || pcr > 185 || pcb < 78 || pcb > 126 || (pcr - 128) < 0.5f * (128 - pcb)) continue;
                    cb[m] = pcb; cr[m] = pcr; m++;
                }
            }
        }
        if (m < 8) return;
        float mb = median(cb, m), mr = median(cr, m);
        // robust spread
        float[] d = new float[m];
        for (int i = 0; i < m; i++) d[i] = Math.abs(cb[i] - mb) + Math.abs(cr[i] - mr);
        float mad = median(d, m);
        double sb = 0, sr = 0, vbb = 0, vrr = 0, vbr = 0; int k = 0;
        for (int i = 0; i < m; i++) {
            if (d[i] > 3f * mad + 4f) continue;
            sb += cb[i]; sr += cr[i]; k++;
        }
        if (k < 6) return;
        sb /= k; sr /= k;
        for (int i = 0; i < m; i++) {
            if (d[i] > 3f * mad + 4f) continue;
            double db = cb[i] - sb, dr = cr[i] - sr;
            vbb += db * db; vrr += dr * dr; vbr += db * dr;
        }
        vbb /= k; vrr /= k; vbr /= k;
        float a = skinUpdates == 0 ? 1f : 0.15f;
        sCb += a * ((float) sb - sCb);
        sCr += a * ((float) sr - sCr);
        // inflate: fingers over the screen are lit differently than the palm
        sVbb += a * ((float) Math.max(vbb, 9) * 2.2f + 20 - sVbb);
        sVrr += a * ((float) Math.max(vrr, 9) * 2.2f + 20 - sVrr);
        sVbr += a * ((float) vbr * 2.2f - sVbr);
        skinUpdates++;
    }

    private static float median(float[] a, int n) {
        float[] c = Arrays.copyOf(a, n);
        Arrays.sort(c);
        return c[n / 2];
    }

    private float skinMaha(float cb, float cr) {
        float db = cb - sCb, dr = cr - sCr;
        float det = sVbb * sVrr - sVbr * sVbr;
        if (det < 1e-3f) return 99;
        float q = (sVrr * db * db - 2 * sVbr * db * dr + sVbb * dr * dr) / det;
        return (float) Math.sqrt(Math.max(0, q));
    }

    // ------------------------------------------------------------ hand prior
    private boolean buildPrior(List<float[]> hands, Homography camToScreen, float cellW, float cellH) {
        Arrays.fill(prior, 0f);
        if (hands == null || hands.isEmpty()) return false;
        float[] g = new float[42];
        boolean any = false;
        for (float[] lm : hands) {
            for (int i = 0; i < 21; i++) {
                camToScreen.map(lm[2 * i], lm[2 * i + 1], g, 2 * i);
                g[2 * i] = g[2 * i] / cellW - 0.5f;
                g[2 * i + 1] = g[2 * i + 1] / cellH - 0.5f;
            }
            // finger half-width ~ half the index-middle knuckle distance
            float knuckle = (float) Math.hypot(g[10] - g[18], g[11] - g[19]);
            float span = (float) Math.hypot(g[0] - g[18], g[1] - g[19]); // wrist->middle MCP
            float r = Math.max(2.5f, Math.max(0.5f * knuckle, 0.11f * span));
            if (!(r < 200)) continue;
            for (int[] b : BONES) {
                float ax = g[2 * b[0]], ay = g[2 * b[0] + 1], bx = g[2 * b[1]], by = g[2 * b[1] + 1];
                float rr = (b[0] == 0 || b[0] == 5 && b[1] == 9 || b[0] == 9 && b[1] == 13 || b[0] == 13 && b[1] == 17) ? r * 1.6f : r;
                float inner = rr * 1.25f, outer = rr * 2.8f;
                int x0 = (int) Math.floor(Math.min(ax, bx) - outer), x1 = (int) Math.ceil(Math.max(ax, bx) + outer);
                int y0 = (int) Math.floor(Math.min(ay, by) - outer), y1 = (int) Math.ceil(Math.max(ay, by) + outer);
                if (x1 < 0 || y1 < 0 || x0 >= gw || y0 >= gh) continue;
                x0 = Math.max(0, x0); y0 = Math.max(0, y0); x1 = Math.min(gw - 1, x1); y1 = Math.min(gh - 1, y1);
                float dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
                for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                    float t = len2 < 1e-6f ? 0 : ((x - ax) * dx + (y - ay) * dy) / len2;
                    t = Math.max(0, Math.min(1, t));
                    float px = ax + t * dx - x, py = ay + t * dy - y;
                    float d = (float) Math.sqrt(px * px + py * py);
                    float p = d <= inner ? 1f : d >= outer ? 0f : (outer - d) / (outer - inner);
                    int c = y * gw + x;
                    if (p > prior[c]) prior[c] = p;
                }
                any = true;
            }
        }
        return any;
    }

    // ------------------------------------------------------------ exposure
    private void exposure() {
        float[] ratios = new float[Math.min(n, 4096)];
        int m = 0, valid = 0;
        int step = Math.max(1, n / 4096);
        for (int c = 0; c < n; c++) if (bgValid[c]) valid++;
        for (int c = 0; c < n && m < ratios.length; c += step) {
            if (!bgValid[c] || prior[c] > 0.05f || cy[c] < 0 || bgY[c] < 25) continue;
            ratios[m++] = cy[c] / bgY[c];
        }
        lastValidPct = 100 * valid / Math.max(1, n);
        if (m > 50) {
            float g = median(ratios, m);
            g = Math.max(0.4f, Math.min(2.5f, g));
            gain += 0.5f * (g - gain);
        }
        lastGain = gain;
    }

    // ------------------------------------------------------------ fusion
    private void fuse(boolean hasHand) {
        boolean trained = skinUpdates > 0;
        for (int c = 0; c < n; c++) {
            if (cy[c] < 0) { raw[c] = 0; skin[c] = 0; continue; }
            float Y = cy[c] / gain;
            // skin evidence
            float s = 0;
            boolean chromaOk = cy[c] > 38 && cy[c] < 238;
            if (chromaOk) {
                float mh = skinMaha(ccb[c], ccr[c]);
                s = Math.max(-1f, Math.min(1f, (2.6f - mh) / 2.0f));
                if (bgValid[c]) {
                    // if the screen itself looks like skin here, skin colour says nothing
                    float mb = skinMaha(bgCb[c], bgCr[c]);
                    s *= Math.max(0f, Math.min(1f, (mb - 1.2f) / 2f));
                }
                if (!trained) s *= 0.6f;
            } else if (cy[c] >= 238 && Math.abs(ccb[c] - 128) + Math.abs(ccr[c] - 128) < 12) {
                s = -0.6f; // clipped neutral white: screen, not skin
            }
            skin[c] = s;
            float L;
            if (bgValid[c]) {
                float sy = Math.max(9f, (float) Math.sqrt(varY[c]));
                float sc = Math.max(5f, (float) Math.sqrt(varC[c]));
                float a = (Y - bgY[c]) / sy, b = (ccb[c] - bgCb[c]) / sc, r = (ccr[c] - bgCr[c]) / sc;
                float dist = Math.min(6f, (float) Math.sqrt(a * a + b * b + r * r));
                float dTerm = 1.5f * (dist - 2.2f);
                // A clearly non-skin change (key pop-up, text, our own outline)
                // is not a finger: gate the difference by skin when chroma is usable.
                if (dTerm > 0 && chromaOk && s < 0.3f) dTerm *= Math.max(0f, (s + 1f) / 1.3f);
                L = dTerm;
                L += 2.0f * s;
            } else {
                L = 3.0f * s; // no background yet: colour decides inside the prior
            }
            L += hasHand ? 4.2f * (prior[c] - 0.5f) : -0.9f;
            raw[c] = (float) (1.0 / (1.0 + Math.exp(-L)));
        }
    }

    // ------------------------------------------------------------ smoothing + components
    private void smoothAndFilter(boolean hasHand) {
        // temporal (fast attack, slightly slower release keeps outlines steady)
        for (int c = 0; c < n; c++) {
            float v = raw[c], f = field[c];
            float a = v > f ? 0.75f : 0.55f;
            field[c] = f + a * (v - f);
        }
        // 3x3 binomial blur
        for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) {
            float s = 0, wsum = 0;
            for (int dy = -1; dy <= 1; dy++) {
                int yy = y + dy; if (yy < 0 || yy >= gh) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx; if (xx < 0 || xx >= gw) continue;
                    float wt = (dx == 0 ? 2 : 1) * (dy == 0 ? 2 : 1);
                    s += wt * field[yy * gw + xx]; wsum += wt;
                }
            }
            blur[y * gw + x] = s / wsum;
        }
        // components of blur > 0.5
        Arrays.fill(labels, 0);
        Arrays.fill(rejected, false);
        int comps = 0, rej = 0, fg = 0;
        for (int st = 0; st < n; st++) {
            if (blur[st] <= 0.5f || labels[st] != 0) continue;
            int id = ++comps, front = 0, back = 0;
            queue[back++] = st; labels[st] = id;
            float sumP = 0, sumS = 0; boolean border = false;
            while (front < back) {
                int p = queue[front++];
                int px = p % gw, py = p / gw;
                sumP += prior[p]; sumS += skin[p];
                if (px == 0 || py == 0 || px == gw - 1 || py == gh - 1) border = true;
                if (px > 0 && blur[p - 1] > 0.5f && labels[p - 1] == 0) { labels[p - 1] = id; queue[back++] = p - 1; }
                if (px < gw - 1 && blur[p + 1] > 0.5f && labels[p + 1] == 0) { labels[p + 1] = id; queue[back++] = p + 1; }
                if (py > 0 && blur[p - gw] > 0.5f && labels[p - gw] == 0) { labels[p - gw] = id; queue[back++] = p - gw; }
                if (py < gh - 1 && blur[p + gw] > 0.5f && labels[p + gw] == 0) { labels[p + gw] = id; queue[back++] = p + gw; }
            }
            int area = back;
            float mp = sumP / area, ms = sumS / area;
            boolean keep = area >= 8 && ((hasHand && mp > 0.3f) || (border && ms > 0.12f));
            if (!keep) {
                rej++;
                for (int i = 0; i < back; i++) rejected[queue[i]] = true;
            } else fg += area;
        }
        // suppress rejected regions in both the displayed and remembered field
        for (int c = 0; c < n; c++) {
            if (rejected[c]) { blur[c] = Math.min(blur[c], 0.3f); field[c] *= 0.5f; }
        }
        lastFgCells = fg; lastComponents = comps; lastRejected = rej;
    }

    /** field = temporal memory, blur = what is contoured and displayed. */
    private float[] displayed() { return blur; }

    // ------------------------------------------------------------ background
    private void updateBackground() {
        float[] shown = displayed();
        // global change (app switched, scrolling): many confident differences
        int changed = 0, considered = 0;
        for (int c = 0; c < n; c += 3) {
            if (!bgValid[c] || prior[c] > 0.05f || cy[c] < 0) continue;
            considered++;
            float dy = Math.abs(cy[c] / gain - bgY[c]);
            if (dy > 3 * Math.max(9f, (float) Math.sqrt(varY[c]))) changed++;
        }
        boolean global = considered > 100 && changed > considered * 0.3f;
        for (int c = 0; c < n; c++) {
            if (cy[c] < 0) continue;
            float Y = cy[c] / gain;
            float a;
            if (!bgValid[c]) {
                // only learn cells that are clearly not hand
                // only learn cells that are clearly not hand: outside the hand
                // prior, or inside it but with clearly non-skin colour
                boolean clearlyNotSkin = skin[c] < -0.45f;
                if (shown[c] > 0.3f || skin[c] > 0.2f || (prior[c] > 0.1f && !clearlyNotSkin)) continue;
                bgY[c] = Y; bgCb[c] = ccb[c]; bgCr[c] = ccr[c];
                varY[c] = 100; varC[c] = 36; bgValid[c] = true;
                continue;
            }
            if (rejected[c]) a = 0.35f;
            else if (shown[c] > 0.35f || prior[c] > 0.5f) continue;
            else a = global ? 0.4f : (prior[c] > 0.05f ? 0.02f : 0.05f);
            float dy = Y - bgY[c], db = ccb[c] - bgCb[c], dr = ccr[c] - bgCr[c];
            bgY[c] += a * dy; bgCb[c] += a * db; bgCr[c] += a * dr;
            varY[c] += a * (dy * dy - varY[c]);
            varC[c] += a * (0.5f * (db * db + dr * dr) - varC[c]);
            varY[c] = Math.min(varY[c], 900); varC[c] = Math.min(varC[c], 400);
        }
    }

    /** The smoothed field that was contoured (grid, 0..1). */
    public float[] displayedField() { return displayed(); }
}
