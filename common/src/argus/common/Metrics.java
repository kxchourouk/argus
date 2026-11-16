package argus.common;

import java.io.Serializable;

/**
 * One snapshot of a VM's resources, sent by the agent to the host every few seconds.
 * Serializable: RMI marshals it (stub side) and unmarshals it (skeleton side).
 */
public class Metrics implements Serializable {
    private static final long serialVersionUID = 1L;

    public String vmName;
    public long timestamp;        // epoch millis, set by the agent
    public double cpuPercent;     // 0..100, whole system
    public double ramPercent;     // 0..100
    public long ramUsedMb;
    public long ramTotalMb;
    public double diskPercent;    // 0..100, root partition
    public double netRxKBs;       // KB/s received
    public double netTxKBs;       // KB/s sent
    public int processCount;
    public double loadAvg;        // 1-minute load average (-1 if unavailable)
    public long uptimeSec;

    /** Feature vector used by the anomaly detector (Isolation Forest). */
    public double[] features() {
        return new double[] { cpuPercent, ramPercent, diskPercent,
                Math.log1p(netRxKBs + netTxKBs), processCount };
    }

    @Override
    public String toString() {
        return String.format("%s cpu=%.1f%% ram=%.1f%% disk=%.1f%% net=%.1f/%.1fKB/s procs=%d",
                vmName, cpuPercent, ramPercent, diskPercent, netRxKBs, netTxKBs, processCount);
    }
}
