package fr.externalscreen.beastfingerdiagnostic;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Finds the phone's screen as a perspective quadrilateral in the Beast camera
 * image. Pure Java (no Android types) so it can be unit-tested off-device.
 *
 * Pipeline, per frame:
 *  1. half-resolution luminance, Otsu threshold -> "emissive" mask (skin removed);
 *  2. connected components; enclosed holes (text, dark keys) are counted as
 *     part of the component, so a lit border alone is enough in dark themes;
 *  3. convex hull -> largest inscribed quadrilateral (robust to fingers that
 *     cut into the screen edges);
 *  4. sub-pixel refinement: edge points searched along each side's normal at
 *     full resolution, total-least-squares line fit with outlier rejection,
 *     corners = line intersections;
 *  5. corner order TL,TR,BR,BL (screen "up" = away from the wearer, or the
 *     ordering closest to the previous frame).
 */
public final class ScreenTracker {
    static boolean DEBUG = false;

    /** Corner coordinates TL,TR,BR,BL in full-resolution image pixels. */
    public static final class Quad {
        public final float[] pts = new float[8];
        public float score;
        public boolean refined;
        Quad copy() { Quad q = new Quad(); System.arraycopy(pts, 0, q.pts, 0, 8); q.score = score; q.refined = refined; return q; }
    }

    // Reused buffers.
    private int[] small, labels, queue;
    private boolean[] mask;
    private int lastThreshold;
    private int lastComponents;

    public int lastThreshold() { return lastThreshold; }
    public int lastComponents() { return lastComponents; }

    /**
     * @param luma   full-resolution luminance, w*h
     * @param skin   optional full-resolution skin mask (excluded from the screen)
     * @param prior  previous quad (same convention) or null
     * @param expectedAspect screen height/width in its current orientation (e.g. 2.2 portrait)
     */
    /**
     * Frame mode: the app draws a reference frame on the display border (outer
     * white line, inner black line). In the camera this is a thin bright RIDGE
     * (dark on both sides) forming a closed quadrilateral, whatever the room
     * brightness or the UI theme. It is used for acquisition and to validate
     * tracking.
     */
    public boolean frameMode = true;
    private boolean[] ridge;      // full resolution
    private boolean[] ridgeSmall; // half resolution (OR of 2x2)
    private int[] rlabels, rqueue;
    private float lastRing;
    public float lastRingSupport() { return lastRing; }

    public Quad detect(int[] luma, boolean[] skin, int w, int h, Quad prior, float expectedAspect, boolean mirrored) {
        lastMode = 0;
        lastRing = 0;
        if (frameMode) computeRidge(luma, w, h);
        if (prior != null) {
            Quad t = track(luma, w, h, prior, expectedAspect);
            if (t != null && frameMode) {
                lastRing = ringSupport(t.pts, w, h);
                if (lastRing < 0.45f) t = null;
            }
            if (t != null) { lastMode = 1; return t; }
        }
        if (frameMode) {
            Quad r = ringAcquire(luma, w, h, prior, expectedAspect, mirrored);
            if (r != null) { lastMode = 3; return r; }
            return null; // with the frame on, never trust brightness blobs (windows, walls, hands)
        }
        int sw = w / 2, sh = h / 2, n = sw * sh;
        if (sw < 32 || sh < 32) return null;
        if (small == null || small.length != n) {
            small = new int[n]; labels = new int[n]; queue = new int[n]; mask = new boolean[n];
        }
        int[] hist = new int[256];
        for (int y = 0; y < sh; y++) {
            int r0 = (2 * y) * w, r1 = r0 + w;
            for (int x = 0; x < sw; x++) {
                int i = 2 * x;
                int v = (luma[r0 + i] + luma[r0 + i + 1] + luma[r1 + i] + luma[r1 + i + 1] + 2) >> 2;
                small[y * sw + x] = v;
                hist[v]++;
            }
        }
        // Several global thresholds: the screen is not always the brightest
        // thing in view (window, lamp, dimmed screen).
        int t1 = Math.max(48, otsu(hist, n));
        int below = 0;
        int[] h2 = new int[256];
        for (int i = 0; i < t1; i++) { h2[i] = hist[i]; below += hist[i]; }
        int t2 = Math.max(40, otsu(h2, below));
        int[] ts = t2 < t1 - 12 ? new int[]{t1, t2, (t1 + t2) / 2} : new int[]{t1};
        Quad best = null;
        for (int t : ts) {
            Quad q = acquire(luma, h, t, skin, w, sw, sh, prior, expectedAspect, mirrored);
            if (q != null && (best == null || q.score > best.score)) { best = q; lastThreshold = t; }
        }
        if (best == null) { lastThreshold = t1; return null; }
        lastMode = 2;
        refine(best, luma, w, h);
        return best;
    }

    private int lastMode;
    /** 0 = none, 1 = edge tracking, 2 = acquisition. */
    public int lastMode() { return lastMode; }

    private Quad acquire(int[] luma, int h, int t, boolean[] skin, int w, int sw, int sh, Quad prior, float expectedAspect, boolean mirrored) {
        int n = sw * sh;
        for (int y = 0; y < sh; y++) {
            int r0 = (2 * y) * w;
            for (int x = 0; x < sw; x++) {
                int k = y * sw + x;
                boolean bright = small[k] > t;
                if (bright && skin != null) {
                    int i = r0 + 2 * x;
                    int s = (skin[i] ? 1 : 0) + (skin[i + 1] ? 1 : 0) + (skin[i + w] ? 1 : 0) + (skin[i + w + 1] ? 1 : 0);
                    if (s >= 3) bright = false;
                }
                mask[k] = bright;
            }
        }

        // ---- label foreground components (4-connectivity)
        Arrays.fill(labels, 0);
        int count = 0;
        int[] areaTmp = new int[64];
        for (int start = 0; start < n; start++) {
            if (!mask[start] || labels[start] != 0) continue;
            int id = ++count;
            if (id >= areaTmp.length) areaTmp = Arrays.copyOf(areaTmp, areaTmp.length * 2);
            areaTmp[id] = flood(start, id, true, sw, sh);
        }
        lastComponents = count;
        if (count == 0) return null;
        int[] area = areaTmp;
        long[] filled = new long[count + 1];
        for (int i = 1; i <= count; i++) filled[i] = area[i];

        // ---- holes: background components not touching the border belong to
        // the foreground component that surrounds them.
        int holeBase = count;
        int hole = holeBase;
        for (int start = 0; start < n; start++) {
            if (mask[start] || labels[start] != 0) continue;
            int id = ++hole;
            int front = 0, back = 0, a = 0, owner = -1;
            boolean border = false;
            queue[back++] = start; labels[start] = -id;
            while (front < back) {
                int p = queue[front++]; a++;
                int px = p % sw, py = p / sw;
                if (px == 0 || py == 0 || px == sw - 1 || py == sh - 1) border = true;
                for (int d = 0; d < 4; d++) {
                    int qx = px + (d == 0 ? -1 : d == 1 ? 1 : 0), qy = py + (d == 2 ? -1 : d == 3 ? 1 : 0);
                    if (qx < 0 || qy < 0 || qx >= sw || qy >= sh) continue;
                    int q = qy * sw + qx;
                    if (mask[q]) { int l = labels[q]; if (owner < 0 || area[l] > area[owner]) owner = l; continue; } // islands (text) inside a hole don't own it
                    if (labels[q] == 0) { labels[q] = -id; queue[back++] = q; }
                }
            }
            if (!border && owner > 0) filled[owner] += a;
        }

        // ---- candidates (pieces down to minArea/6 can be merged into a bigger one:
        // a bright screen border cut by fingers falls apart into several pieces)
        int minArea = Math.max(60, n / 90);
        ArrayList<Integer> cand = new ArrayList<>();
        for (int i = 1; i <= count; i++) if (filled[i] >= Math.max(24, minArea / 40) && filled[i] < n * 0.85) cand.add(i);
        if (cand.isEmpty()) return null;
        int[] slot = new int[count + 1];
        Arrays.fill(slot, -1);
        for (int c = 0; c < cand.size(); c++) slot[cand.get(c)] = c;
        int nc = cand.size();
        int[][] rowMin = new int[nc][sh], rowMax = new int[nc][sh];
        for (int[] r : rowMin) Arrays.fill(r, Integer.MAX_VALUE);
        for (int[] r : rowMax) Arrays.fill(r, Integer.MIN_VALUE);
        int[] bx0 = new int[nc], bx1 = new int[nc], by0 = new int[nc], by1 = new int[nc];
        Arrays.fill(bx0, Integer.MAX_VALUE); Arrays.fill(by0, Integer.MAX_VALUE);
        Arrays.fill(bx1, -1); Arrays.fill(by1, -1);
        for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++) {
            int l = labels[y * sw + x];
            if (l <= 0) continue;
            int s = slot[l];
            if (s < 0) continue;
            if (x < rowMin[s][y]) rowMin[s][y] = x;
            if (x > rowMax[s][y]) rowMax[s][y] = x;
            if (x < bx0[s]) bx0[s] = x; if (x > bx1[s]) bx1[s] = x;
            if (y < by0[s]) by0[s] = y; if (y > by1[s]) by1[s] = y;
        }
        // groups to evaluate: every big component alone, plus each big component
        // merged with the pieces lying around it
        ArrayList<int[]> groups = new ArrayList<>();
        ArrayList<Long> groupArea = new ArrayList<>();
        for (int c = 0; c < nc; c++) {
            long boxArea = (long) (bx1[c] - bx0[c] + 1) * (by1[c] - by0[c] + 1);
            if (filled[cand.get(c)] < minArea && boxArea < 2L * minArea) continue; // thin ring pieces count by extent
            groups.add(new int[]{c});
            groupArea.add(filled[cand.get(c)]);
            int ex = (int) ((bx1[c] - bx0[c]) * 0.35f) + 2, ey = (int) ((by1[c] - by0[c]) * 0.35f) + 2;
            ArrayList<Integer> members = new ArrayList<>();
            members.add(c);
            long a = filled[cand.get(c)];
            for (int o = 0; o < nc; o++) {
                if (o == c) continue;
                if (bx1[o] < bx0[c] - ex || bx0[o] > bx1[c] + ex || by1[o] < by0[c] - ey || by0[o] > by1[c] + ey) continue;
                if (filled[cand.get(o)] > filled[cand.get(c)] * 1.5f) continue;
                members.add(o); a += filled[cand.get(o)];
            }
            if (members.size() > 1) {
                int[] g = new int[members.size()];
                for (int i = 0; i < g.length; i++) g[i] = members.get(i);
                groups.add(g); groupArea.add(a);
            }
        }

        Quad best = null;
        for (int gi = 0; gi < groups.size(); gi++) {
            ArrayList<float[]> pts = new ArrayList<>();
            for (int c : groups.get(gi)) {
                for (int y = 0; y < sh; y++) {
                    if (rowMin[c][y] == Integer.MAX_VALUE) continue;
                    pts.add(new float[]{rowMin[c][y], y});
                    pts.add(new float[]{rowMax[c][y] + 1, y});
                    pts.add(new float[]{rowMin[c][y], y + 1});
                    pts.add(new float[]{rowMax[c][y] + 1, y + 1});
                }
            }
            float[][] hull = simplify(convexHull(pts), 16);
            if (hull.length < 4) continue;
            float hullArea = Math.abs(polyArea(hull));
            float[] quad = bestQuad(hull);
            if (quad == null) continue;
            float quadArea = Math.abs(polyArea8(quad));
            if (quadArea < minArea * 0.8f) continue;
            float fill = Math.min(1f, groupArea.get(gi) / quadArea);
            float hullFit = quadArea / Math.max(1f, hullArea);
            if (hullFit < 0.8f) continue;
            if (!anglesOk(quad)) continue;
            for (int i = 0; i < 8; i++) quad[i] *= 2f; // to full resolution
            float support = edgeSupport(quad, luma, w, h);
            if (DEBUG) System.err.printf("  t=%d grp=%d qa=%.0f fill=%.2f sup=%.2f%n", t, groups.get(gi).length, quadArea, fill, support);
            if (fill < 0.5f && support < 0.6f) continue;
            float quality = Math.max(fill, support);
            orderClockwise(quad, mirrored);
            orient(quad, prior);
            float aspect = aspect(quad);
            float aspectTerm = (float) Math.exp(-0.8 * Math.abs(Math.log(aspect / Math.max(1f, expectedAspect * 0.8f))));
            float priorTerm = 1f;
            if (prior != null) {
                float d = meanCornerDistance(quad, prior.pts);
                float diag = (float) Math.hypot(prior.pts[4] - prior.pts[0], prior.pts[5] - prior.pts[1]);
                priorTerm = 1f + 2f * (float) Math.exp(-d / Math.max(4f, 0.12f * diag));
            }
            // a real screen is fully in view: touching the image border is suspicious
            float qx0 = Math.min(Math.min(quad[0], quad[2]), Math.min(quad[4], quad[6]));
            float qx1 = Math.max(Math.max(quad[0], quad[2]), Math.max(quad[4], quad[6]));
            float qy0 = Math.min(Math.min(quad[1], quad[3]), Math.min(quad[5], quad[7]));
            float qy1 = Math.max(Math.max(quad[1], quad[3]), Math.max(quad[5], quad[7]));
            float borderTerm = (qx0 < 6 || qy0 < 6 || qx1 > 2 * sw - 6 || qy1 > 2 * sh - 6) ? 0.1f : 1f;
            if (quadArea > n * 0.6f) continue;
            float score = (float) Math.sqrt(quadArea / n) * quality * Math.min(1f, support / 0.6f) * hullFit
                * aspectTerm * priorTerm * borderTerm;
            if (DEBUG) System.err.printf("t=%d grp=%d area=%.0f fill=%.2f sup=%.2f hull=%.2f asp=%.2f border=%.1f score=%.3f%n", t, groups.get(gi).length, quadArea, fill, support, hullFit, aspectTerm, borderTerm, score);
            if (best == null || score > best.score) {
                best = new Quad();
                System.arraycopy(quad, 0, best.pts, 0, 8);
                best.score = score;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ helpers
    private int flood(int start, int id, boolean fg, int sw, int sh) {
        int front = 0, back = 0, a = 0;
        queue[back++] = start;
        labels[start] = id;
        while (front < back) {
            int p = queue[front++]; a++;
            int px = p % sw, py = p / sw;
            if (px > 0) { int q = p - 1; if (mask[q] == fg && labels[q] == 0) { labels[q] = id; queue[back++] = q; } }
            if (px < sw - 1) { int q = p + 1; if (mask[q] == fg && labels[q] == 0) { labels[q] = id; queue[back++] = q; } }
            if (py > 0) { int q = p - sw; if (mask[q] == fg && labels[q] == 0) { labels[q] = id; queue[back++] = q; } }
            if (py < sh - 1) { int q = p + sw; if (mask[q] == fg && labels[q] == 0) { labels[q] = id; queue[back++] = q; } }
        }
        return a;
    }

    static int otsu(int[] hist, int total) {
        double sum = 0;
        for (int i = 0; i < 256; i++) sum += i * (double) hist[i];
        double sumB = 0, best = -1; int wB = 0, t = 128;
        for (int i = 0; i < 256; i++) {
            wB += hist[i];
            if (wB == 0) continue;
            int wF = total - wB;
            if (wF == 0) break;
            sumB += i * (double) hist[i];
            double mB = sumB / wB, mF = (sum - sumB) / wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > best) { best = between; t = i; }
        }
        return t;
    }

    static float[][] convexHull(ArrayList<float[]> pts) {
        pts.sort((a, b) -> a[0] != b[0] ? Float.compare(a[0], b[0]) : Float.compare(a[1], b[1]));
        int n = pts.size();
        if (n < 3) return new float[0][];
        float[][] hull = new float[2 * n][];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], pts.get(i)) <= 0) k--;
            hull[k++] = pts.get(i);
        }
        for (int i = n - 2, lo = k + 1; i >= 0; i--) {
            while (k >= lo && cross(hull[k - 2], hull[k - 1], pts.get(i)) <= 0) k--;
            hull[k++] = pts.get(i);
        }
        return Arrays.copyOf(hull, Math.max(0, k - 1));
    }

    private static float cross(float[] o, float[] a, float[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** Visvalingam simplification down to at most maxPts vertices. */
    static float[][] simplify(float[][] poly, int maxPts) {
        ArrayList<float[]> p = new ArrayList<>(Arrays.asList(poly));
        while (p.size() > maxPts) {
            int m = p.size(), worst = -1; float least = Float.MAX_VALUE;
            for (int i = 0; i < m; i++) {
                float a = Math.abs(cross(p.get((i + m - 1) % m), p.get(i), p.get((i + 1) % m)));
                if (a < least) { least = a; worst = i; }
            }
            p.remove(worst);
        }
        return p.toArray(new float[0][]);
    }

    static float polyArea(float[][] p) {
        double s = 0;
        for (int i = 0; i < p.length; i++) {
            float[] a = p[i], b = p[(i + 1) % p.length];
            s += a[0] * b[1] - b[0] * a[1];
        }
        return (float) (s / 2);
    }

    static float polyArea8(float[] q) {
        double s = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            s += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1];
        }
        return (float) (s / 2);
    }

    /** Largest-area quadrilateral using hull vertices (hull order kept). */
    static float[] bestQuad(float[][] hull) {
        int n = hull.length;
        float bestA = -1; int[] idx = null;
        for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++)
            for (int c = b + 1; c < n; c++) for (int d = c + 1; d < n; d++) {
                float[] q = {hull[a][0], hull[a][1], hull[b][0], hull[b][1], hull[c][0], hull[c][1], hull[d][0], hull[d][1]};
                float ar = Math.abs(polyArea8(q));
                if (ar > bestA) { bestA = ar; idx = new int[]{a, b, c, d}; }
            }
        if (idx == null) return null;
        float[] q = new float[8];
        for (int i = 0; i < 4; i++) { q[2 * i] = hull[idx[i]][0]; q[2 * i + 1] = hull[idx[i]][1]; }
        return q;
    }

    static boolean anglesOk(float[] q) {
        for (int i = 0; i < 4; i++) {
            int p = (i + 3) % 4, nx = (i + 1) % 4;
            float ax = q[2 * p] - q[2 * i], ay = q[2 * p + 1] - q[2 * i + 1];
            float bx = q[2 * nx] - q[2 * i], by = q[2 * nx + 1] - q[2 * i + 1];
            double cos = (ax * bx + ay * by) / (Math.hypot(ax, ay) * Math.hypot(bx, by) + 1e-6);
            double deg = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
            if (deg < 40 || deg > 140) return false;
        }
        return true;
    }

    /** Visual clockwise in image coordinates (y down), or anticlockwise if the camera mirrors. */
    static void orderClockwise(float[] q, boolean mirrored) {
        boolean cw = polyArea8(q) > 0; // y-down: positive shoelace = clockwise on screen
        if (cw == mirrored) {
            // reverse order: 0,3,2,1
            float x1 = q[2], y1 = q[3];
            q[2] = q[6]; q[3] = q[7];
            q[6] = x1; q[7] = y1;
        }
    }

    /** Rotate the cyclic order so that pts are TL,TR,BR,BL. */
    static void orient(float[] q, Quad prior) {
        int bestK = 0; float bestCost = Float.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            float cost;
            if (prior != null) {
                cost = 0;
                for (int i = 0; i < 4; i++) {
                    int j = (i + k) % 4;
                    cost += (float) Math.hypot(q[2 * j] - prior.pts[2 * i], q[2 * j + 1] - prior.pts[2 * i + 1]);
                }
            } else {
                // top edge = corners k,k+1; prefer it up in the image (away from the wearer)
                float tx = (q[2 * k] + q[2 * ((k + 1) % 4)]) / 2, ty = (q[2 * k + 1] + q[2 * ((k + 1) % 4) + 1]) / 2;
                float bx = (q[2 * ((k + 2) % 4)] + q[2 * ((k + 3) % 4)]) / 2, by = (q[2 * ((k + 2) % 4) + 1] + q[2 * ((k + 3) % 4) + 1]) / 2;
                cost = (float) ((ty - by) / (Math.hypot(tx - bx, ty - by) + 1e-6)); // -1 = straight up
            }
            if (cost < bestCost) { bestCost = cost; bestK = k; }
        }
        if (bestK == 0) return;
        float[] tmp = q.clone();
        for (int i = 0; i < 4; i++) {
            int j = (i + bestK) % 4;
            q[2 * i] = tmp[2 * j]; q[2 * i + 1] = tmp[2 * j + 1];
        }
    }

    /** Apparent height/width (left+right sides vs top+bottom). */
    static float aspect(float[] q) {
        float top = dist(q, 0, 1), right = dist(q, 1, 2), bottom = dist(q, 2, 3), left = dist(q, 3, 0);
        return (left + right) / Math.max(1f, top + bottom);
    }

    private static float dist(float[] q, int a, int b) {
        return (float) Math.hypot(q[2 * a] - q[2 * b], q[2 * a + 1] - q[2 * b + 1]);
    }

    static float meanCornerDistance(float[] a, float[] b) {
        float s = 0;
        for (int i = 0; i < 4; i++) s += (float) Math.hypot(a[2 * i] - b[2 * i], a[2 * i + 1] - b[2 * i + 1]);
        return s / 4;
    }

    // ----------------------------------------------------- sub-pixel edges
    private static float sample(int[] y, int w, int h, float x, float yy) {
        if (x < 0 || yy < 0 || x > w - 2 || yy > h - 2) return -1;
        int x0 = (int) x, y0 = (int) yy;
        float fx = x - x0, fy = yy - y0;
        int i = y0 * w + x0;
        float a = y[i] + fx * (y[i + 1] - y[i]);
        float b = y[i + w] + fx * (y[i + w + 1] - y[i + w]);
        return a + fy * (b - a);
    }

    // ------------------------------------------------------------ frame (ridge) mode
    private void computeRidge(int[] luma, int w, int h) {
        int n = w * h;
        if (ridge == null || ridge.length != n) ridge = new boolean[n];
        int k = 4;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int i = row + x, v = luma[i];
                boolean r = false;
                if (v >= 70) {
                    int t = Math.max(22, v / 6);
                    if (y >= k && y < h - k) {
                        int a = luma[i - k * w], b = luma[i + k * w];
                        if (v - Math.max(a, b) > t) r = true;
                    }
                    if (!r && x >= k && x < w - k) {
                        int a = luma[i - k], b = luma[i + k];
                        if (v - Math.max(a, b) > t) r = true;
                    }
                }
                ridge[i] = r;
            }
        }
        int sw = w / 2, sh = h / 2;
        if (ridgeSmall == null || ridgeSmall.length != sw * sh) {
            ridgeSmall = new boolean[sw * sh]; rlabels = new int[sw * sh]; rqueue = new int[sw * sh];
        }
        for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++) {
            int i = 2 * y * w + 2 * x;
            ridgeSmall[y * sw + x] = ridge[i] || ridge[i + 1] || ridge[i + w] || ridge[i + w + 1];
        }
    }

    /** Fraction of perimeter samples with a ridge pixel within 4 px inside the side. */
    float ringSupport(float[] q, int w, int h) {
        if (ridge == null) return 0;
        float cx = (q[0] + q[2] + q[4] + q[6]) / 4, cy = (q[1] + q[3] + q[5] + q[7]) / 4;
        int ok = 0, total = 0; float minSide = 1;
        for (int s = 0; s < 4; s++) {
            int a = s, b = (s + 1) % 4;
            float ax = q[2 * a], ay = q[2 * a + 1], bx = q[2 * b], by = q[2 * b + 1];
            float len = (float) Math.hypot(bx - ax, by - ay);
            if (len < 8) return 0;
            float nx = (by - ay) / len, ny = -(bx - ax) / len;
            if (nx * ((ax + bx) / 2 - cx) + ny * ((ay + by) / 2 - cy) < 0) { nx = -nx; ny = -ny; }
            int sideOk = 0, sideN = 24;
            for (int k = 0; k < sideN; k++) {
                float t = 0.06f + 0.88f * k / (sideN - 1);
                float sx = ax + (bx - ax) * t, sy = ay + (by - ay) * t;
                for (float o = -1.5f; o <= 5.5f; o += 1f) {
                    int px = Math.round(sx - nx * o), py = Math.round(sy - ny * o);
                    if (px < 0 || py < 0 || px >= w || py >= h) continue;
                    if (ridge[py * w + px]) { sideOk++; break; }
                }
            }
            ok += sideOk; total += sideN;
            minSide = Math.min(minSide, sideOk / (float) sideN);
        }
        // every side must show some of the frame (fingers hide parts of it)
        if (minSide < 0.2f) return Math.min(0.3f, ok / (float) total);
        return ok / (float) total;
    }

    private Quad ringAcquire(int[] luma, int w, int h, Quad prior, float expectedAspect, boolean mirrored) {
        int sw = w / 2, sh = h / 2, n = sw * sh;
        Arrays.fill(rlabels, 0);
        int count = 0;
        ArrayList<int[]> boxes = new ArrayList<>(); // minX,minY,maxX,maxY,area
        boxes.add(null);
        for (int st = 0; st < n; st++) {
            if (!ridgeSmall[st] || rlabels[st] != 0) continue;
            int id = ++count, front = 0, back = 0;
            rqueue[back++] = st; rlabels[st] = id;
            int x0 = sw, y0 = sh, x1 = -1, y1 = -1;
            while (front < back) {
                int p = rqueue[front++];
                int px = p % sw, py = p / sw;
                if (px < x0) x0 = px; if (px > x1) x1 = px; if (py < y0) y0 = py; if (py > y1) y1 = py;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int qx = px + dx, qy = py + dy;
                    if (qx < 0 || qy < 0 || qx >= sw || qy >= sh) continue;
                    int q = qy * sw + qx;
                    if (ridgeSmall[q] && rlabels[q] == 0) { rlabels[q] = id; rqueue[back++] = q; }
                }
            }
            boxes.add(new int[]{x0, y0, x1, y1, back});
        }
        int minSpan = Math.max(16, Math.min(sw, sh) / 10);
        Quad best = null;
        for (int c = 1; c <= count; c++) {
            int[] b = boxes.get(c);
            if (b[2] - b[0] < minSpan && b[3] - b[1] < minSpan) continue;
            if (b[4] < 20) continue;
            // group: this piece + pieces around it (the frame is cut by fingers)
            int ex = (b[2] - b[0]) / 2 + 4, ey = (b[3] - b[1]) / 2 + 4;
            ArrayList<Integer> members = new ArrayList<>();
            members.add(c);
            for (int o = 1; o <= count; o++) {
                if (o == c) continue;
                int[] bo = boxes.get(o);
                if (bo[4] < 6) continue;
                if (bo[2] < b[0] - ex || bo[0] > b[2] + ex || bo[3] < b[1] - ey || bo[1] > b[3] + ey) continue;
                members.add(o);
            }
            for (int variant = 0; variant < 2; variant++) {
                java.util.HashSet<Integer> set = new java.util.HashSet<>();
                if (variant == 0) set.add(c); else { if (members.size() == 1) break; set.addAll(members); }
                ArrayList<float[]> pts = new ArrayList<>();
                int gx0 = sw, gy0 = sh, gx1 = 0, gy1 = 0;
                for (int i : set) { int[] bb = boxes.get(i); gx0 = Math.min(gx0, bb[0]); gy0 = Math.min(gy0, bb[1]); gx1 = Math.max(gx1, bb[2]); gy1 = Math.max(gy1, bb[3]); }
                for (int y = gy0; y <= gy1; y++) {
                    int mn = -1, mx = -1;
                    for (int x = gx0; x <= gx1; x++) {
                        int l = rlabels[y * sw + x];
                        if (l != 0 && set.contains(l)) { if (mn < 0) mn = x; mx = x; }
                    }
                    if (mn >= 0) { pts.add(new float[]{mn, y}); pts.add(new float[]{mx + 1, y + 1}); pts.add(new float[]{mn, y + 1}); pts.add(new float[]{mx + 1, y}); }
                }
                float[][] hull = simplify(convexHull(pts), 16);
                if (hull.length < 4) continue;
                float[] quad = bestQuad(hull);
                if (quad == null || !anglesOk(quad)) continue;
                float hullFit = Math.abs(polyArea8(quad)) / Math.max(1f, Math.abs(polyArea(hull)));
                if (hullFit < 0.85f) continue;
                for (int i = 0; i < 8; i++) quad[i] *= 2f;
                float area = Math.abs(polyArea8(quad));
                if (area < (w * h) / 120f || area > w * h * 0.7f) continue;
                float sup = ringSupport(quad, w, h);
                if (sup < 0.55f) continue;
                orderClockwise(quad, mirrored);
                orient(quad, prior);
                float aspect = aspect(quad);
                float aspectTerm = (float) Math.exp(-0.6 * Math.abs(Math.log(aspect / Math.max(0.2f, expectedAspect * 0.8f))));
                float priorTerm = 1f;
                if (prior != null) {
                    float d = meanCornerDistance(quad, prior.pts);
                    float diag = (float) Math.hypot(prior.pts[4] - prior.pts[0], prior.pts[5] - prior.pts[1]);
                    priorTerm = 1f + (float) Math.exp(-d / Math.max(4f, 0.12f * diag));
                }
                float score = (float) Math.sqrt(area / (w * h)) * sup * sup * aspectTerm * priorTerm;
                if (DEBUG) System.err.printf("ring c=%d var=%d area=%.0f sup=%.2f asp=%.2f score=%.3f%n", c, variant, area, sup, aspectTerm, score);
                if (best == null || score > best.score) {
                    best = new Quad();
                    System.arraycopy(quad, 0, best.pts, 0, 8);
                    best.score = score;
                }
            }
        }
        if (best != null) {
            Quad before = best.copy();
            refine(best, luma, w, h);
            if (ringSupport(best.pts, w, h) < 0.5f) best = before;
            lastRing = ringSupport(best.pts, w, h);
        }
        return best;
    }

    /** Fraction of side samples with a clear bright-inside / dark-outside edge (within 3 px). */
    static float edgeSupport(float[] q, int[] luma, int w, int h) {
        float cx = (q[0] + q[2] + q[4] + q[6]) / 4, cy = (q[1] + q[3] + q[5] + q[7]) / 4;
        int ok = 0, total = 0;
        for (int s = 0; s < 4; s++) {
            int a = s, b = (s + 1) % 4;
            float ax = q[2 * a], ay = q[2 * a + 1], bx = q[2 * b], by = q[2 * b + 1];
            float len = (float) Math.hypot(bx - ax, by - ay);
            if (len < 4) return 0;
            float nx = (by - ay) / len, ny = -(bx - ax) / len;
            if (nx * ((ax + bx) / 2 - cx) + ny * ((ay + by) / 2 - cy) < 0) { nx = -nx; ny = -ny; }
            for (int k = 0; k < 16; k++) {
                float t = 0.08f + 0.84f * k / 15f;
                float sx = ax + (bx - ax) * t, sy = ay + (by - ay) * t;
                total++;
                for (int o = -3; o <= 3; o++) {
                    float vin = sample(luma, w, h, sx + nx * (o - 2), sy + ny * (o - 2));
                    float vout = sample(luma, w, h, sx + nx * (o + 2), sy + ny * (o + 2));
                    if (vin >= 0 && vout >= 0 && vin - vout >= 20) { ok++; break; }
                }
            }
        }
        return total == 0 ? 0 : ok / (float) total;
    }

    /**
     * Frame-to-frame tracking: search the bright-inside / dark-outside edge of
     * each side around the previous quad, robust line fit, corners from line
     * intersections. Tolerates fingers crossing the edges (outliers) and works
     * with a dark UI as long as the screen border is brighter than the bezel.
     */
    private Quad track(int[] luma, int w, int h, Quad prior, float expectedAspect) {
        float[] q = prior.pts;
        float cx = (q[0] + q[2] + q[4] + q[6]) / 4, cy = (q[1] + q[3] + q[5] + q[7]) / 4;
        float diag = (float) Math.hypot(q[4] - q[0], q[5] - q[1]);
        float range = Math.max(10f, Math.min(42f, diag * 0.09f));
        float[][] lines = new float[4][];
        for (int s = 0; s < 4; s++) {
            int a = s, b = (s + 1) % 4;
            float ax = q[2 * a], ay = q[2 * a + 1], bx = q[2 * b], by = q[2 * b + 1];
            float len = (float) Math.hypot(bx - ax, by - ay);
            if (len < 12) return null;
            float dx = (bx - ax) / len, dy = (by - ay) / len;
            float nx = dy, ny = -dx;
            if (nx * ((ax + bx) / 2 - cx) + ny * ((ay + by) / 2 - cy) < 0) { nx = -nx; ny = -ny; }
            int samples = Math.max(12, Math.min(60, (int) (len / 5)));
            float[] px = new float[samples], py = new float[samples];
            int m = 0;
            int steps = (int) range;
            float[] g = new float[2 * steps + 1];
            for (int k = 0; k < samples; k++) {
                float t = 0.06f + 0.88f * k / (samples - 1);
                float sx = ax + (bx - ax) * t, sy = ay + (by - ay) * t;
                int bi = -1; float bestScore = 0;
                for (int o = -steps; o <= steps; o++) {
                    float v1 = sample(luma, w, h, sx + nx * (o + 1.5f), sy + ny * (o + 1.5f));
                    float v0 = sample(luma, w, h, sx + nx * (o - 1.5f), sy + ny * (o - 1.5f));
                    float gg = (v1 < 0 || v0 < 0) ? 0 : v1 - v0;
                    g[o + steps] = gg;
                    float score = -gg - 0.25f * Math.abs(o);
                    if (-gg >= 14f && score > bestScore) { bestScore = score; bi = o + steps; }
                }
                if (bi < 0) continue;
                bi = outermost(g, bi, 2 * steps + 1);
                float off = bi - steps;
                if (bi > 0 && bi < 2 * steps) {
                    float l = g[bi - 1], c = g[bi], r = g[bi + 1];
                    float den = l - 2 * c + r;
                    if (Math.abs(den) > 1e-3) off += Math.max(-0.5f, Math.min(0.5f, 0.5f * (l - r) / den));
                }
                px[m] = sx + nx * off; py[m] = sy + ny * off; m++;
            }
            if (m < Math.max(6, samples * 0.35f)) return null;
            float[] line = fitLine(px, py, m);
            if (line == null) return null;
            lines[s] = line;
        }
        Quad out = new Quad();
        for (int c = 0; c < 4; c++) {
            float[] p = intersect(lines[(c + 3) % 4], lines[c]);
            if (p == null) return null;
            out.pts[2 * c] = p[0]; out.pts[2 * c + 1] = p[1];
        }
        if (meanCornerDistance(out.pts, q) > 0.22f * diag) return null;
        float a0 = Math.abs(polyArea8(q)), a1 = Math.abs(polyArea8(out.pts));
        if (a1 < 0.6f * a0 || a1 > 1.6f * a0 || !anglesOk(out.pts)) return null;
        for (int i = 0; i < 8; i++) if (out.pts[i] < -2 || out.pts[i] > (i % 2 == 0 ? w + 2 : h + 2)) return null;
        out.score = prior.score;
        float asp = aspect(out.pts);
        if (asp < expectedAspect * 0.25f || asp > expectedAspect * 2.5f) return null;
        refine(out, luma, w, h);
        if (edgeSupport(out.pts, luma, w, h) < 0.45f) return null;
        return out;
    }

    /**
     * The display edge is the OUTERMOST bright->dark transition (frame line or
     * screen content -> bezel); inner ones (content -> black frame line) are
     * just as strong, so take the last local minimum with >= 60% of the best.
     */
    private static int outermost(float[] g, int bi, int len) {
        float limit = 0.6f * g[bi];
        int pick = bi;
        for (int o = bi + 1; o < len; o++) {
            boolean localMin = (o == len - 1 || g[o] <= g[o + 1]) && g[o] <= g[o - 1];
            if (g[o] <= limit && g[o] <= -14f && localMin) pick = o;
        }
        return pick;
    }

    private void refine(Quad quad, int[] luma, int w, int h) {
        float[] q = quad.pts;
        float cx = (q[0] + q[2] + q[4] + q[6]) / 4, cy = (q[1] + q[3] + q[5] + q[7]) / 4;
        float[][] lines = new float[4][];
        int good = 0;
        for (int s = 0; s < 4; s++) {
            int a = s, b = (s + 1) % 4;
            float ax = q[2 * a], ay = q[2 * a + 1], bx = q[2 * b], by = q[2 * b + 1];
            float len = (float) Math.hypot(bx - ax, by - ay);
            if (len < 8) return;
            float dx = (bx - ax) / len, dy = (by - ay) / len;
            float nx = dy, ny = -dx;
            if (nx * ((ax + bx) / 2 - cx) + ny * ((ay + by) / 2 - cy) < 0) { nx = -nx; ny = -ny; }
            int samples = Math.max(8, Math.min(40, (int) (len / 4)));
            float[] px = new float[samples], py = new float[samples];
            int m = 0;
            for (int k = 0; k < samples; k++) {
                float t = 0.12f + 0.76f * k / (samples - 1);
                float sx = ax + (bx - ax) * t, sy = ay + (by - ay) * t;
                float bestG = -14f, bestS = Float.NaN;
                float[] g = new float[13];
                for (int o = -6; o <= 6; o++) {
                    float v1 = sample(luma, w, h, sx + nx * (o + 1), sy + ny * (o + 1));
                    float v0 = sample(luma, w, h, sx + nx * (o - 1), sy + ny * (o - 1));
                    g[o + 6] = (v1 < 0 || v0 < 0) ? 0 : v1 - v0; // bright inside -> dark outside: negative
                }
                int bi = -1;
                for (int o = 0; o < 13; o++) if (g[o] < bestG) { bestG = g[o]; bi = o; }
                if (bi < 0) continue;
                bi = outermost(g, bi, 13);
                float off = bi - 6;
                if (bi > 0 && bi < 12) {
                    float l = g[bi - 1], c = g[bi], r = g[bi + 1];
                    float den = l - 2 * c + r;
                    if (Math.abs(den) > 1e-3) off += Math.max(-0.5f, Math.min(0.5f, 0.5f * (l - r) / den));
                }
                bestS = off;
                px[m] = sx + nx * bestS; py[m] = sy + ny * bestS; m++;
            }
            float[] line = fitLine(px, py, m);
            if (line != null) { lines[s] = line; good++; }
        }
        if (good < 4) return;
        float[] out = new float[8];
        for (int c = 0; c < 4; c++) {
            float[] l1 = lines[(c + 3) % 4], l2 = lines[c]; // corner c is between side c-1 and side c
            float[] p = intersect(l1, l2);
            if (p == null) return;
            if (Math.hypot(p[0] - q[2 * c], p[1] - q[2 * c + 1]) > 10) return; // implausible jump
            out[2 * c] = p[0]; out[2 * c + 1] = p[1];
        }
        System.arraycopy(out, 0, q, 0, 8);
        quad.refined = true;
    }

    /** Total least squares line: returns {px,py,dx,dy} or null. */
    static float[] fitLine(float[] x, float[] y, int n) {
        boolean[] keep = new boolean[n];
        Arrays.fill(keep, true);
        float[] line = null;
        for (int pass = 0; pass < 3; pass++) {
            int k = 0; double mx = 0, my = 0;
            for (int i = 0; i < n; i++) if (keep[i]) { mx += x[i]; my += y[i]; k++; }
            if (k < 6) return null;
            mx /= k; my /= k;
            double sxx = 0, syy = 0, sxy = 0;
            for (int i = 0; i < n; i++) if (keep[i]) {
                double dx = x[i] - mx, dy = y[i] - my;
                sxx += dx * dx; syy += dy * dy; sxy += dx * dy;
            }
            double theta = 0.5 * Math.atan2(2 * sxy, sxx - syy);
            float dx = (float) Math.cos(theta), dy = (float) Math.sin(theta);
            line = new float[]{(float) mx, (float) my, dx, dy};
            float[] res = new float[k]; int r = 0;
            for (int i = 0; i < n; i++) if (keep[i]) res[r++] = Math.abs((x[i] - line[0]) * dy - (y[i] - line[1]) * dx);
            float[] sorted = res.clone(); Arrays.sort(sorted);
            float limit = Math.max(0.8f, 2.5f * sorted[sorted.length / 2]);
            boolean changed = false;
            for (int i = 0; i < n; i++) if (keep[i]) {
                float d = Math.abs((x[i] - line[0]) * dy - (y[i] - line[1]) * dx);
                if (d > limit) { keep[i] = false; changed = true; }
            }
            if (!changed) break;
        }
        return line;
    }

    static float[] intersect(float[] a, float[] b) {
        float den = a[2] * b[3] - a[3] * b[2];
        if (Math.abs(den) < 1e-4) return null;
        float t = ((b[0] - a[0]) * b[3] - (b[1] - a[1]) * b[2]) / den;
        return new float[]{a[0] + a[2] * t, a[1] + a[3] * t};
    }
}
