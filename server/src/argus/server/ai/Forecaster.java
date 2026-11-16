package argus.server.ai;

/**
 * Simple trend forecaster (least-squares linear regression on a sliding window).
 * Used for "pro-action": warn BEFORE a resource is exhausted, e.g.
 *   "RAM will reach 90% in about 3 min".
 */
public final class Forecaster {

    public static final class Trend {
        public double slopePerSec;   // % per second
        public double r2;            // goodness of fit (0..1)
        public double current;       // fitted value now
        public double in5min;        // predicted value in 5 minutes (clamped 0..100)
        public double secondsTo(double threshold) {
            if (slopePerSec <= 0 || current >= threshold) return -1;
            return (threshold - current) / slopePerSec;
        }
    }

    private Forecaster() { }

    /**
     * @param t      timestamps in milliseconds
     * @param v      values (percentages)
     * @param from   first index of the window (inclusive)
     * @param to     last index (exclusive)
     */
    public static Trend fit(long[] t, double[] v, int from, int to) {
        int n = to - from;
        Trend tr = new Trend();
        if (n < 5) return tr;
        double t0 = t[from];
        double sx = 0, sy = 0, sxx = 0, sxy = 0, syy = 0;
        for (int i = from; i < to; i++) {
            double x = (t[i] - t0) / 1000.0, y = v[i];
            sx += x; sy += y; sxx += x * x; sxy += x * y; syy += y * y;
        }
        double den = n * sxx - sx * sx;
        if (den == 0) return tr;
        double slope = (n * sxy - sx * sy) / den;
        double intercept = (sy - slope * sx) / n;
        double ssTot = syy - sy * sy / n;
        double ssRes = 0;
        for (int i = from; i < to; i++) {
            double x = (t[i] - t0) / 1000.0;
            double e = v[i] - (intercept + slope * x);
            ssRes += e * e;
        }
        double xNow = (t[to - 1] - t0) / 1000.0;
        tr.slopePerSec = slope;
        tr.r2 = ssTot <= 1e-9 ? 0 : Math.max(0, 1 - ssRes / ssTot);
        tr.current = intercept + slope * xNow;
        tr.in5min = Math.max(0, Math.min(100, tr.current + slope * 300));
        return tr;
    }
}
