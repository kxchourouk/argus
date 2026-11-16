package argus.server;

import argus.common.AgentInfo;
import argus.common.AgentRemote;
import argus.common.Metrics;
import argus.common.MonitorServer;
import argus.server.ai.AnomalyDetector;
import argus.server.ai.Forecaster;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The host's remote object. Agents on the VMs call it through RMI.
 * Several agents call it at the same time (one RMI thread per call), so all the
 * shared state lives in thread-safe structures.
 */
public class MonitorServerImpl implements MonitorServer {

    private final String token;
    private final AlertManager alerts;
    private final MetricsStore store;
    private final ConcurrentHashMap<String, VmState> vms = new ConcurrentHashMap<>();

    public MonitorServerImpl(String token, AlertManager alerts, MetricsStore store) {
        this.token = token;
        this.alerts = alerts;
        this.store = store;
    }

    public Collection<VmState> vms() { return vms.values(); }
    public VmState vm(String name) { return vms.get(name); }

    // ======================================================== remote methods

    @Override
    public boolean register(AgentInfo info, AgentRemote callback, String tok) {
        if (!token.equals(tok)) {
            alerts.raise(info.name, Alert.Level.WARNING, "AUTH", "Registration refused: wrong token (from " + info.address + ")", 10);
            return false;
        }
        VmState existing = vms.get(info.name);
        if (existing == null) {
            vms.put(info.name, new VmState(info, callback));
            alerts.raise(info.name, Alert.Level.INFO, "VM_JOIN", "New VM registered: " + info, 0);
        } else {
            boolean wasDown = !existing.isReachable();
            existing.info = info;
            existing.stub = callback;          // new stub: the agent may have a new port/IP
            existing.status = VmState.Status.ONLINE;
            existing.lastSeen = System.currentTimeMillis();
            existing.registeredAt = existing.lastSeen;
            alerts.raise(info.name, Alert.Level.INFO, wasDown ? "VM_UP" : "VM_JOIN",
                    wasDown ? "VM is back online after " + downFor(existing) : "Agent re-registered", 0);
            existing.downSince = 0;
        }
        return true;
    }

    @Override
    public boolean pushMetrics(Metrics m, String tok) {
        if (!token.equals(tok)) return false;
        VmState vm = vms.get(m.vmName);
        if (vm == null) return false; // unknown -> the agent will register again
        vm.lastSeen = System.currentTimeMillis();
        if (vm.status != VmState.Status.ONLINE) {
            if (vm.status == VmState.Status.DOWN)
                alerts.raise(vm.info.name, Alert.Level.INFO, "VM_UP", "VM is back online after " + downFor(vm), 0);
            vm.status = VmState.Status.ONLINE;
            vm.downSince = 0;
        }
        analyze(vm, m);
        return true;
    }

    @Override
    public void unregister(String vmName, String tok) {
        if (!token.equals(tok)) return;
        VmState vm = vms.get(vmName);
        if (vm == null) return;
        vm.status = VmState.Status.STOPPED;
        alerts.raise(vmName, Alert.Level.INFO, "VM_STOP", "Agent stopped cleanly (planned shutdown, not a failure)", 0);
    }

    // ======================================================== analysis

    private void analyze(VmState vm, Metrics m) {
        String name = vm.info.name;

        // 1) AI: Isolation Forest + z-score, learned per VM
        AnomalyDetector.Result r = vm.detector.analyze(m.features());
        vm.lastResult = r;
        vm.add(m, r);
        store.append(m, r.score, r.anomalous);
        if (r.anomalous) {
            vm.anomalyCount++;
            alerts.raise(name, Alert.Level.WARNING, "ANOMALY", "Unusual behaviour: " + r.reason, 30, r.feature);
        }

        // 2) Classic static rules (what a basic monitoring tool would do)
        vm.highCpuStreak = m.cpuPercent > 90 ? vm.highCpuStreak + 1 : 0;
        if (vm.highCpuStreak == 3)
            alerts.raise(name, Alert.Level.WARNING, "CPU_HIGH", String.format("CPU above 90%% for 6 s (%.0f%%)", m.cpuPercent), 60);
        if (m.ramPercent > 90)
            alerts.raise(name, Alert.Level.CRITICAL, "RAM_HIGH", String.format("RAM at %.0f%% (%d / %d MB)", m.ramPercent, m.ramUsedMb, m.ramTotalMb), 60);
        if (m.diskPercent > 90)
            alerts.raise(name, Alert.Level.WARNING, "DISK_HIGH", String.format("Disk at %.0f%%", m.diskPercent), 300);

        // 3) Pro-action: forecast RAM over the last 90 s and warn before saturation
        List<VmState.Sample> h = vm.history(45);
        if (h.size() >= 20) {
            long[] t = new long[h.size()];
            double[] v = new double[h.size()];
            for (int i = 0; i < h.size(); i++) { t[i] = h.get(i).m.timestamp; v[i] = h.get(i).m.ramPercent; }
            Forecaster.Trend tr = Forecaster.fit(t, v, 0, h.size());
            vm.ramTrend = tr;
            double eta = tr.secondsTo(90);
            if (tr.r2 >= 0.8 && tr.slopePerSec > 0.02 && eta > 0 && eta <= 600) {
                alerts.raise(name, Alert.Level.WARNING, "FORECAST", String.format(
                        "RAM rising steadily (+%.1f%%/min, R²=%.2f): predicted to reach 90%% in ~%s. Possible memory leak.",
                        tr.slopePerSec * 60, tr.r2, human(eta)), 120);
            }
        }
    }

    // ======================================================== failure detection

    /** Called every 2 s by the failure detector thread. */
    void checkLiveness(long heartbeatTimeoutMs) {
        long now = System.currentTimeMillis();
        for (VmState vm : vms.values()) {
            if (!vm.isReachable()) continue;
            long silent = now - vm.lastSeen;
            if (silent < heartbeatTimeoutMs) continue;
            vm.status = VmState.Status.SUSPECT;
            // the heartbeat is late: try to reach the VM ourselves before declaring it dead
            try {
                vm.stub.ping();
                alerts.raise(vm.info.name, Alert.Level.WARNING, "SLOW",
                        "No metrics for " + silent / 1000 + " s but the agent answers ping (overloaded VM?)", 60);
            } catch (Exception e) {
                vm.status = VmState.Status.DOWN;
                vm.downSince = now;
                alerts.raise(vm.info.name, Alert.Level.CRITICAL, "VM_DOWN",
                        "VM unreachable: no heartbeat for " + silent / 1000 + " s and ping failed ("
                                + e.getClass().getSimpleName() + ")", 0);
            }
        }
    }

    private static String downFor(VmState vm) {
        return vm.downSince == 0 ? "a short time" : human((System.currentTimeMillis() - vm.downSince) / 1000.0);
    }

    static String human(double sec) {
        if (sec < 90) return Math.round(sec) + " s";
        return String.format("%.1f min", sec / 60);
    }
}
