package argus.server.ai;

import java.util.Random;

/**
 * Isolation Forest (Liu, Ting &amp; Zhou, 2008), written from scratch.
 *
 * Idea: anomalies are "few and different", so random cuts isolate them in very
 * few steps. We build many random binary trees on a sample of normal data; a
 * point whose average path length is short is probably an anomaly.
 *
 * score(x) = 2^( -E[h(x)] / c(n) )   in (0, 1)
 *   ~0.5 or less -> normal,   close to 1 -> anomaly
 */
public class IsolationForest {

    private final int nTrees;
    private final int sampleSize;
    private final Random rnd;
    private Node[] trees;
    private double cn; // average path length of an unsuccessful BST search for sampleSize points

    public IsolationForest(int nTrees, int sampleSize, long seed) {
        this.nTrees = nTrees;
        this.sampleSize = sampleSize;
        this.rnd = new Random(seed);
    }

    /** Trains the forest on rows of features (all rows must have the same length). */
    public void fit(double[][] data) {
        int psi = Math.min(sampleSize, data.length);
        int heightLimit = (int) Math.ceil(Math.log(psi) / Math.log(2));
        trees = new Node[nTrees];
        for (int t = 0; t < nTrees; t++) {
            double[][] sample = new double[psi][];
            for (int i = 0; i < psi; i++) sample[i] = data[rnd.nextInt(data.length)];
            trees[t] = build(sample, 0, heightLimit);
        }
        cn = c(psi);
    }

    public boolean isTrained() { return trees != null; }

    /** Anomaly score in (0,1). */
    public double score(double[] x) {
        if (trees == null) return 0;
        double sum = 0;
        for (Node tree : trees) sum += pathLength(x, tree, 0);
        double avg = sum / trees.length;
        return Math.pow(2, -avg / cn);
    }

    // ------------------------------------------------------------------ tree

    private static final class Node {
        int feature;
        double split;
        Node left, right;
        int size; // > 0 only for leaves
    }

    private Node build(double[][] x, int depth, int limit) {
        Node n = new Node();
        if (depth >= limit || x.length <= 1) {
            n.size = x.length;
            return n;
        }
        int dims = x[0].length;
        // pick a random feature that actually varies in this subset
        int f = -1;
        double min = 0, max = 0;
        for (int tries = 0; tries < dims * 2; tries++) {
            int cand = rnd.nextInt(dims);
            min = Double.MAX_VALUE; max = -Double.MAX_VALUE;
            for (double[] row : x) { min = Math.min(min, row[cand]); max = Math.max(max, row[cand]); }
            if (max > min) { f = cand; break; }
        }
        if (f < 0) { // all points identical: leaf
            n.size = x.length;
            return n;
        }
        double split = min + rnd.nextDouble() * (max - min);
        int leftCount = 0;
        for (double[] row : x) if (row[f] < split) leftCount++;
        double[][] l = new double[leftCount][], r = new double[x.length - leftCount][];
        int li = 0, ri = 0;
        for (double[] row : x) { if (row[f] < split) l[li++] = row; else r[ri++] = row; }
        n.feature = f;
        n.split = split;
        n.left = build(l, depth + 1, limit);
        n.right = build(r, depth + 1, limit);
        return n;
    }

    private static double pathLength(double[] x, Node n, int depth) {
        if (n.left == null) return depth + c(n.size); // leaf: add the expected remaining depth
        return pathLength(x, x[n.feature] < n.split ? n.left : n.right, depth + 1);
    }

    /** Average path length of an unsuccessful search in a BST of n nodes. */
    static double c(int n) {
        if (n <= 1) return 0;
        if (n == 2) return 1;
        double h = Math.log(n - 1) + 0.5772156649; // harmonic number approximation
        return 2 * h - 2.0 * (n - 1) / n;
    }
}
