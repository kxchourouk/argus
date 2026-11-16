package argus.server;

import argus.common.AgentInfo;
import argus.common.AgentRemote;
import argus.common.Metrics;
import argus.server.ai.AnomalyDetector;
import argus.server.ai.Forecaster;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Everything the host knows about one VM: identity, callback stub, history and AI state. */
public class VmState {

    public enum Status { ONLINE, SUSPECT, DOWN, STOPPED }

    public static final int HISTORY = 300; // 10 minutes at one sample every 2 s

    public static final class Sample {
        public final Metrics m;
        public final double score;
        public final boolean anomaly;
        Sample(Metrics m, double score, boolean anomaly) { this.m = m; this.score = score; this.anomaly = anomaly; }
    }

    public volatile AgentInfo info;
    public volatile AgentRemote stub;
    public volatile Status status = Status.ONLINE;
    public volatile long lastSeen = System.currentTimeMillis();
    public volatile long registeredAt = System.currentTimeMillis();
    public volatile long downSince = 0;

    // AI state survives an agent restart: the VM keeps its learned "normal"
    public final AnomalyDetector detector;
    public volatile AnomalyDetector.Result lastResult;
    public volatile Forecaster.Trend ramTrend;
    public volatile long anomalyCount = 0;
    int highCpuStreak = 0;

    private final Deque<Sample> history = new ArrayDeque<>();

    public VmState(AgentInfo info, AgentRemote stub) {
        this.info = info;
        this.stub = stub;
        this.detector = new AnomalyDetector(info.name.hashCode());
    }

    public synchronized void add(Metrics m, AnomalyDetector.Result r) {
        history.addLast(new Sample(m, r.score, r.anomalous));
        if (history.size() > HISTORY) history.removeFirst();
    }

    public synchronized List<Sample> history(int lastN) {
        List<Sample> all = new ArrayList<>(history);
        return all.subList(Math.max(0, all.size() - lastN), all.size());
    }

    public synchronized Metrics latest() {
        return history.isEmpty() ? null : history.peekLast().m;
    }

    public boolean isReachable() {
        return status == Status.ONLINE || status == Status.SUSPECT;
    }
}
