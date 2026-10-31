package fr.externalscreen.beastfingerdiagnostic;

/** 3x3 planar homography from 4 point correspondences (direct linear transform). */
public final class Homography {
    final double[] h = new double[9];

    private Homography() { }

    /**
     * src/dst are {x0,y0,x1,y1,x2,y2,x3,y3}. Returns null if degenerate.
     */
    public static Homography fromQuads(float[] src, float[] dst) {
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = src[2 * i], y = src[2 * i + 1];
            double u = dst[2 * i], v = dst[2 * i + 1];
            a[2 * i] = new double[]{x, y, 1, 0, 0, 0, -u * x, -u * y, u};
            a[2 * i + 1] = new double[]{0, 0, 0, x, y, 1, -v * x, -v * y, v};
        }
        // Gaussian elimination with partial pivoting on the 8x8 system.
        for (int col = 0; col < 8; col++) {
            int pivot = col;
            for (int r = col + 1; r < 8; r++)
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) pivot = r;
            if (Math.abs(a[pivot][col]) < 1e-12) return null;
            double[] tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp;
            for (int r = 0; r < 8; r++) {
                if (r == col) continue;
                double f = a[r][col] / a[col][col];
                if (f == 0) continue;
                for (int c = col; c < 9; c++) a[r][c] -= f * a[col][c];
            }
        }
        Homography out = new Homography();
        for (int i = 0; i < 8; i++) out.h[i] = a[i][8] / a[i][i];
        out.h[8] = 1;
        for (double v : out.h) if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return out;
    }

    /** Maps (x,y); writes into out[off], out[off+1]. Returns false behind the plane. */
    public boolean map(float x, float y, float[] out, int off) {
        double w = h[6] * x + h[7] * y + h[8];
        if (Math.abs(w) < 1e-9) return false;
        out[off] = (float) ((h[0] * x + h[1] * y + h[2]) / w);
        out[off + 1] = (float) ((h[3] * x + h[4] * y + h[5]) / w);
        return w > 0;
    }
}
