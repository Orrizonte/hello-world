package fr.externalscreen.beastfingerdiagnostic;

/**
 * 1€ filter (Casiez, Roussel, Vogel, CHI 2012): strong smoothing when still,
 * low lag when moving. Same family of filter MediaPipe uses for landmarks.
 * Also exposes the filtered velocity, used to extrapolate between camera frames.
 */
public final class OneEuroFilter {
    private final float minCutoff, beta, dCutoff;
    private boolean initialized;
    private float x, dx;
    private long lastNs;

    public OneEuroFilter(float minCutoff, float beta, float dCutoff) {
        this.minCutoff = minCutoff;
        this.beta = beta;
        this.dCutoff = dCutoff;
    }

    private static float alpha(float cutoff, float dt) {
        float tau = 1f / (2f * (float) Math.PI * cutoff);
        return 1f / (1f + tau / dt);
    }

    public float filter(float value, long timeNs) {
        if (!initialized) {
            initialized = true;
            x = value; dx = 0; lastNs = timeNs;
            return x;
        }
        float dt = Math.max(1e-3f, (timeNs - lastNs) / 1e9f);
        lastNs = timeNs;
        float rawDx = (value - x) / dt;
        dx += alpha(dCutoff, dt) * (rawDx - dx);
        float cutoff = minCutoff + beta * Math.abs(dx);
        x += alpha(cutoff, dt) * (value - x);
        return x;
    }

    public float value() { return x; }
    /** Units per second. */
    public float velocity() { return dx; }
    public boolean ready() { return initialized; }
    public void reset() { initialized = false; dx = 0; }
}
