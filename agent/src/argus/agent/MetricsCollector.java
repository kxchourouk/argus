package argus.agent;

import argus.common.Metrics;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Reads the VM's resources.
 * On Linux it reads /proc directly (precise, no external tool needed).
 * On other systems (e.g. testing an agent on Windows) it falls back to the JVM's OS MXBean.
 */
public final class MetricsCollector {

    private final String vmName;
    private final boolean linux = Files.isReadable(Paths.get("/proc/stat"));
    private final com.sun.management.OperatingSystemMXBean os =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    // previous samples, used to compute deltas (CPU %, network KB/s)
    private long prevCpuTotal = -1, prevCpuIdle = -1;
    private long prevRx = -1, prevTx = -1, prevNetTime = -1;

    public MetricsCollector(String vmName) {
        this.vmName = vmName;
        collect(); // prime the deltas
    }

    public synchronized Metrics collect() {
        Metrics m = new Metrics();
        m.vmName = vmName;
        m.timestamp = System.currentTimeMillis();
        m.cpuPercent = round(cpu());
        readRam(m);
        m.diskPercent = round(disk());
        readNetwork(m);
        m.processCount = processCount();
        m.loadAvg = round(os.getSystemLoadAverage());
        m.uptimeSec = uptime();
        return m;
    }

    // ---------------------------------------------------------------- CPU
    private double cpu() {
        if (linux) {
            try {
                // first line: cpu user nice system idle iowait irq softirq steal ...
                String[] f = Files.readAllLines(Paths.get("/proc/stat")).get(0).trim().split("\\s+");
                long total = 0;
                for (int i = 1; i < Math.min(f.length, 9); i++) total += Long.parseLong(f[i]);
                long idle = Long.parseLong(f[4]) + Long.parseLong(f[5]);
                double pct = 0;
                if (prevCpuTotal >= 0 && total > prevCpuTotal) {
                    pct = 100.0 * (1.0 - (double) (idle - prevCpuIdle) / (total - prevCpuTotal));
                }
                prevCpuTotal = total; prevCpuIdle = idle;
                return clamp(pct);
            } catch (Exception ignored) { /* fall through */ }
        }
        @SuppressWarnings("deprecation")
        double load = os.getSystemCpuLoad();
        return load < 0 ? 0 : clamp(load * 100);
    }

    // ---------------------------------------------------------------- RAM
    @SuppressWarnings("deprecation")
    private void readRam(Metrics m) {
        long totalKb = -1, availKb = -1;
        if (linux) {
            try {
                for (String line : Files.readAllLines(Paths.get("/proc/meminfo"))) {
                    if (line.startsWith("MemTotal:")) totalKb = kb(line);
                    else if (line.startsWith("MemAvailable:")) availKb = kb(line);
                }
            } catch (IOException ignored) { }
        }
        if (totalKb <= 0 || availKb < 0) {
            totalKb = os.getTotalPhysicalMemorySize() / 1024;
            availKb = os.getFreePhysicalMemorySize() / 1024;
        }
        m.ramTotalMb = totalKb / 1024;
        m.ramUsedMb = (totalKb - availKb) / 1024;
        m.ramPercent = round(100.0 * (totalKb - availKb) / Math.max(1, totalKb));
    }

    private static long kb(String line) {
        return Long.parseLong(line.replaceAll("[^0-9]", ""));
    }

    // ---------------------------------------------------------------- Disk
    private double disk() {
        File root = linux ? new File("/") : File.listRoots()[0];
        long total = root.getTotalSpace();
        if (total == 0) return 0;
        return 100.0 * (total - root.getUsableSpace()) / total;
    }

    // ---------------------------------------------------------------- Network
    private void readNetwork(Metrics m) {
        if (!linux) return; // not available through the JVM
        try {
            long rx = 0, tx = 0;
            List<String> lines = Files.readAllLines(Paths.get("/proc/net/dev"));
            for (String line : lines.subList(2, lines.size())) {
                String[] parts = line.trim().split("[:\\s]+");
                if (parts[0].equals("lo")) continue;
                rx += Long.parseLong(parts[1]);
                tx += Long.parseLong(parts[9]);
            }
            long now = System.nanoTime();
            if (prevNetTime > 0) {
                double sec = (now - prevNetTime) / 1e9;
                m.netRxKBs = round(Math.max(0, rx - prevRx) / 1024.0 / sec);
                m.netTxKBs = round(Math.max(0, tx - prevTx) / 1024.0 / sec);
            }
            prevRx = rx; prevTx = tx; prevNetTime = now;
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- Processes / uptime
    private int processCount() {
        if (linux) {
            File[] dirs = new File("/proc").listFiles((d, n) -> n.chars().allMatch(Character::isDigit));
            if (dirs != null) return dirs.length;
        }
        return (int) ProcessHandle.allProcesses().count();
    }

    private long uptime() {
        if (linux) {
            try {
                Path p = Paths.get("/proc/uptime");
                return (long) Double.parseDouble(Files.readAllLines(p).get(0).split("\\s+")[0]);
            } catch (Exception ignored) { }
        }
        return ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
    }

    public boolean isLinux() { return linux; }

    private static double clamp(double v) { return Math.max(0, Math.min(100, v)); }
    private static double round(double v) { return Math.round(v * 10) / 10.0; }
}
