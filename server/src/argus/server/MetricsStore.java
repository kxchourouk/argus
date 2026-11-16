package argus.server;

import argus.common.Metrics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Persistence: every snapshot is appended to data/metrics-&lt;vm&gt;.csv.
 * The files can be opened in Excel / pandas for the report (history, plots).
 */
public class MetricsStore {

    private static final String HEADER =
            "timestamp,cpu_percent,ram_percent,ram_used_mb,disk_percent,net_rx_kbs,net_tx_kbs,processes,load_avg,anomaly_score,anomaly";

    private final Path dir;
    private final Map<String, BufferedWriter> writers = new HashMap<>();

    public MetricsStore(Path dir) {
        this.dir = dir;
    }

    public synchronized void append(Metrics m, double score, boolean anomaly) {
        try {
            BufferedWriter w = writers.get(m.vmName);
            if (w == null) {
                Path f = dir.resolve("metrics-" + m.vmName.replaceAll("[^A-Za-z0-9._-]", "_") + ".csv");
                boolean isNew = !Files.exists(f);
                w = Files.newBufferedWriter(f, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (isNew) { w.write(HEADER); w.newLine(); }
                writers.put(m.vmName, w);
            }
            w.write(String.format(java.util.Locale.ROOT, "%d,%.1f,%.1f,%d,%.1f,%.1f,%.1f,%d,%.2f,%.3f,%d",
                    m.timestamp, m.cpuPercent, m.ramPercent, m.ramUsedMb, m.diskPercent,
                    m.netRxKBs, m.netTxKBs, m.processCount, m.loadAvg, score, anomaly ? 1 : 0));
            w.newLine();
            w.flush();
        } catch (IOException e) {
            ServerMain.log("CSV write failed: " + e.getMessage());
        }
    }
}
