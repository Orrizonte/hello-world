package fr.externalscreen.beastfingerdiagnostic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sub-pixel iso-contours of a scalar field (marching squares, oriented so that
 * every edge crossing has exactly one successor: loops are always closed).
 * Values outside the grid are treated as 0. Pure Java.
 */
public final class Contours {
    private int[] next;
    private float[] ex, ey;
    private boolean[] used;

    /**
     * @return closed loops {x0,y0,x1,y1,...} in grid coordinates (sample centres at integers).
     */
    public List<float[]> extract(float[] f, int w, int h, float iso, float minArea) {
        // Work on a virtual grid with one ring of zeros around it.
        int W = w + 2, H = h + 2;
        int edges = 2 * W * H;
        if (next == null || next.length < edges) {
            next = new int[edges]; ex = new float[edges]; ey = new float[edges]; used = new boolean[edges];
        }
        Arrays.fill(next, 0, edges, -1);
        Arrays.fill(used, 0, edges, false);
        int vOff = W * H;
        for (int y = 0; y < H - 1; y++) {
            for (int x = 0; x < W - 1; x++) {
                float v0 = val(f, w, h, x, y), v1 = val(f, w, h, x + 1, y);
                float v2 = val(f, w, h, x + 1, y + 1), v3 = val(f, w, h, x, y + 1);
                boolean b0 = v0 > iso, b1 = v1 > iso, b2 = v2 > iso, b3 = v3 > iso;
                if (b0 == b1 && b1 == b2 && b2 == b3) continue;
                // clockwise boundary: v0 -e0- v1 -e1- v2 -e2- v3 -e3- v0
                int e0 = y * W + x, e1 = vOff + y * W + (x + 1), e2 = (y + 1) * W + x, e3 = vOff + y * W + x;
                // crossing points
                if (b0 != b1) { ex[e0] = x + t(v0, v1, iso); ey[e0] = y; }
                if (b1 != b2) { ex[e1] = x + 1; ey[e1] = y + t(v1, v2, iso); }
                if (b3 != b2) { ex[e2] = x + t(v3, v2, iso); ey[e2] = y + 1; }
                if (b0 != b3) { ex[e3] = x; ey[e3] = y + t(v0, v3, iso); }
                // ordered crossings with type: exit = in->out along clockwise traversal
                int[] ce = new int[4]; boolean[] exit = new boolean[4]; int n = 0;
                if (b0 != b1) { ce[n] = e0; exit[n++] = b0; }
                if (b1 != b2) { ce[n] = e1; exit[n++] = b1; }
                if (b2 != b3) { ce[n] = e2; exit[n++] = b2; }
                if (b3 != b0) { ce[n] = e3; exit[n++] = b3; }
                if (n == 2) {
                    if (exit[0]) next[ce[1]] = ce[0]; else next[ce[0]] = ce[1];
                } else if (n == 4) {
                    boolean centreIn = (v0 + v1 + v2 + v3) * 0.25f > iso;
                    for (int i = 0; i < 4; i++) {
                        if (exit[i]) continue;
                        int j = centreIn ? (i + 3) % 4 : (i + 1) % 4; // previous / next exit
                        next[ce[i]] = ce[j];
                    }
                }
            }
        }
        ArrayList<float[]> loops = new ArrayList<>();
        float[] buf = new float[512];
        for (int s = 0; s < edges; s++) {
            if (next[s] < 0 || used[s]) continue;
            int n = 0, e = s, guard = 0;
            boolean closed = false;
            while (e >= 0 && !used[e] && guard++ < edges) {
                used[e] = true;
                if (2 * n + 2 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                buf[2 * n] = ex[e] - 1; buf[2 * n + 1] = ey[e] - 1; n++;
                e = next[e];
                if (e == s) { closed = true; break; }
            }
            if (!closed || n < 4) continue;
            float[] loop = Arrays.copyOf(buf, 2 * n);
            if (Math.abs(area(loop)) >= minArea) loops.add(loop);
        }
        return loops;
    }

    private static float val(float[] f, int w, int h, int x, int y) {
        int gx = x - 1, gy = y - 1;
        if (gx < 0 || gy < 0 || gx >= w || gy >= h) return 0f;
        return f[gy * w + gx];
    }

    private static float t(float a, float b, float iso) {
        float d = b - a;
        if (Math.abs(d) < 1e-6f) return 0.5f;
        return Math.max(0f, Math.min(1f, (iso - a) / d));
    }

    public static float area(float[] p) {
        double s = 0;
        int n = p.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            s += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1];
        }
        return (float) (s / 2);
    }

    /** One Chaikin corner-cutting pass on a closed loop. */
    public static float[] chaikin(float[] p) {
        int n = p.length / 2;
        float[] o = new float[n * 4];
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float ax = p[2 * i], ay = p[2 * i + 1], bx = p[2 * j], by = p[2 * j + 1];
            o[4 * i] = 0.75f * ax + 0.25f * bx; o[4 * i + 1] = 0.75f * ay + 0.25f * by;
            o[4 * i + 2] = 0.25f * ax + 0.75f * bx; o[4 * i + 3] = 0.25f * ay + 0.75f * by;
        }
        return o;
    }

    /** Drops vertices closer than minDist to the previously kept one. */
    public static float[] decimate(float[] p, float minDist) {
        int n = p.length / 2;
        float[] o = new float[p.length];
        int m = 0;
        float lx = Float.NaN, ly = Float.NaN, d2 = minDist * minDist;
        for (int i = 0; i < n; i++) {
            float x = p[2 * i], y = p[2 * i + 1];
            if (m > 0) { float dx = x - lx, dy = y - ly; if (dx * dx + dy * dy < d2) continue; }
            o[2 * m] = x; o[2 * m + 1] = y; m++; lx = x; ly = y;
        }
        return m >= 3 ? Arrays.copyOf(o, 2 * m) : p;
    }
}
