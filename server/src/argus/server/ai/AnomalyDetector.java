package argus.server.ai;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-VM anomaly detector. Learns what is "normal" for THIS VM (each VM has its
 * own model), then flags snapshots that do not look like it.
 *
 * Hybrid detector, two complementary models:
 *  - a z-score per metric (statistical, univariate): says WHICH metric is abnormal;
 *    a minimum standard deviation keeps tiny noise on an idle VM from alerting;
 *  - an Isolation Forest on the whole feature vector (ML, multivariate): measures
 *    how "isolated" the snapshot is compared with the learned normal behaviour.
 *
 * Decision rules (the reason says which one fired):
 *  STRONG       |z| >= 5                         one metric is far outside its normal range
 *  CONFIRMED    |z| >= 3  and score > threshold  moderate deviation confirmed by the forest
 *  COMBINATION  score > threshold + 0.08         no single metric is extreme, but several
 *               and >= 2 metrics with |z| >= 2   drift together into a combination never seen
 *
 * Auto-learning: anomalous points are not learned, unless the new behaviour lasts
 * long enough (3 min) to be considered the new normal (e.g. a service was started).
 */
public class AnomalyDetector {

    public static final String[] FEATURES = { "CPU", "RAM", "Disk", "Network", "Processes" };
    private static final String[] UNITS    = { "%", "%", "%", "", "" };
    /**
     * Minimum std per feature: a change smaller than ~3x this is never an anomaly.
     * Network is log(1 + KB/s): a floor of 1.5 means traffic must grow ~90x to alert.
     */
    private static final double[] STD_FLOOR = { 5.0, 2.0, 1.0, 1.5, 6.0 };

    public static final int WARMUP = 30;          // samples before detection starts (1 min at 2 s)
    private static final int MAX_TRAIN = 300;     // sliding training window (10 min)
    private static final int RETRAIN_EVERY = 30;  // retrain the forest every N new samples
    /**
     * The score threshold is not fixed: after each retraining it is set to the 98th
     * percentile of the scores of the training points (the 2% "most isolated" normal
     * points), never below 0.5. Each VM therefore gets its own adaptive threshold.
     */
    private static final double QUANTILE = 0.98, MIN_THRESHOLD = 0.50;
    private static final double Z_THRESHOLD = 3.0, Z_STRONG = 5.0, COMBO_MARGIN = 0.08;
    private static final int ADAPT_AFTER = 90;    // consecutive anomalies before accepting as new normal

    public static final class Result {
        public boolean warmingUp;
        public int trainingSize;
        public double score;          // Isolation Forest score (0..1)
        public boolean anomalous;
        public String reason = "";
        public String feature = "";   // most abnormal feature
        public double maxZ;
        public double threshold;      // adaptive Isolation Forest threshold of this VM
        public String rule = "";      // which decision rule fired
    }

    private final Deque<double[]> training = new ArrayDeque<>();
    private final IsolationForest forest;
    private int sinceTrain = 0;
    private int consecutive = 0;
    private static final int SKIP_FIRST = 3;
    private int skipped = 0;
    private double[] mean = new double[FEATURES.length], std = new double[FEATURES.length];
    private double threshold = 0.6;

    public AnomalyDetector(long seed) {
        this.forest = new IsolationForest(100, 128, seed);
    }

    public synchronized Result analyze(double[] x) {
        Result r = new Result();
        if (skipped < SKIP_FIRST) {   // agent JVM start-up noise: not representative
            skipped++;
            r.warmingUp = true;
            return r;
        }
        if (training.size() < WARMUP) {
            learn(x);
            r.warmingUp = true;
            r.trainingSize = training.size();
            return r;
        }
        if (!forest.isTrained() || sinceTrain >= RETRAIN_EVERY) retrain();

        r.score = forest.score(x);
        r.trainingSize = training.size();

        int worst = 0, moderate = 0;
        double worstZ = 0;
        for (int i = 0; i < x.length; i++) {
            double z = (x[i] - mean[i]) / Math.max(std[i], STD_FLOOR[i]);
            if (Math.abs(z) > Math.abs(worstZ)) { worstZ = z; worst = i; }
            if (Math.abs(z) >= 2) moderate++;
        }
        r.maxZ = worstZ;
        r.feature = FEATURES[worst];
        r.threshold = threshold;
        double az = Math.abs(worstZ);
        String rule = null;
        if (az >= Z_STRONG) rule = "strong deviation";
        else if (az >= Z_THRESHOLD && r.score > threshold) rule = "confirmed by Isolation Forest";
        else if (r.score > threshold + COMBO_MARGIN && moderate >= 2) rule = "unusual combination of metrics";
        r.anomalous = rule != null;
        r.rule = rule == null ? "" : rule;

        if (r.anomalous) {
            String what;
            if (worst == 3) { // network is learned in log space, but explained in KB/s
                what = String.format("Network = %.0f KB/s (usual ~%.0f KB/s, z = %+.1f)",
                        Math.expm1(x[3]), Math.expm1(mean[3]), worstZ);
            } else {
                what = String.format("%s = %.1f%s (usual %.1f ± %.1f, z = %+.1f)",
                        FEATURES[worst], x[worst], UNITS[worst], mean[worst], std[worst], worstZ);
            }
            r.reason = String.format("%s [%s, isolation score %.2f / threshold %.2f]", what, rule, r.score, threshold);
            consecutive++;
            if (consecutive >= ADAPT_AFTER) learn(x); // concept drift: slowly accept the new normal
        } else {
            consecutive = 0;
            learn(x);
        }
        return r;
    }

    private void learn(double[] x) {
        training.addLast(x.clone());
        if (training.size() > MAX_TRAIN) training.removeFirst();
        sinceTrain++;
    }

    private void retrain() {
        double[][] data = training.toArray(new double[0][]);
        forest.fit(data);
        double[] scores = new double[data.length];
        for (int i = 0; i < data.length; i++) scores[i] = forest.score(data[i]);
        java.util.Arrays.sort(scores);
        threshold = Math.max(MIN_THRESHOLD, scores[(int) Math.min(scores.length - 1, Math.floor(QUANTILE * scores.length))]);
        int d = FEATURES.length;
        mean = new double[d];
        std = new double[d];
        for (double[] row : data) for (int i = 0; i < d; i++) mean[i] += row[i];
        for (int i = 0; i < d; i++) mean[i] /= data.length;
        for (double[] row : data) for (int i = 0; i < d; i++) std[i] += (row[i] - mean[i]) * (row[i] - mean[i]);
        for (int i = 0; i < d; i++) std[i] = Math.sqrt(std[i] / data.length);
        sinceTrain = 0;
    }
}
