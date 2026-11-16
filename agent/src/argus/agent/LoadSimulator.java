package argus.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates artificial load on the VM so the anomaly detection can be demonstrated
 * live, without installing any stress tool.
 */
public class LoadSimulator {

    private volatile long cpuStressUntil = 0;
    private final List<byte[]> leaked = new ArrayList<>();
    private volatile boolean leaking = false;

    /** Busy-loops on every core for the given number of seconds. */
    public synchronized String stressCpu(int seconds) {
        seconds = Math.max(5, Math.min(seconds, 300));
        long until = System.currentTimeMillis() + seconds * 1000L;
        boolean alreadyRunning = cpuStressUntil > System.currentTimeMillis();
        cpuStressUntil = until;
        if (!alreadyRunning) {
            int threads = Runtime.getRuntime().availableProcessors();
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(() -> {
                    double x = 0;
                    while (System.currentTimeMillis() < cpuStressUntil) {
                        x += Math.sqrt(x + 1) * Math.sin(x); // pure CPU work
                    }
                    if (x == 42) System.out.print(""); // keep the JIT from removing the loop
                }, "cpu-stress-" + i);
                t.setDaemon(true);
                t.start();
            }
            return "CPU stress started on " + threads + " core(s) for " + seconds + " s";
        }
        return "CPU stress extended to " + seconds + " s";
    }

    /**
     * Simulates a memory leak: allocates 16 MB every 2 seconds until targetMb is reached.
     * The slow, steady growth is what the RAM forecaster is designed to catch.
     */
    public synchronized String leakMemory(int targetMb) {
        if (leaking) return "A memory leak is already running (" + leakedMb() + " MB so far)";
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        int target = Math.max(32, Math.min(targetMb, (int) (maxHeapMb * 0.85)));
        leaking = true;
        Thread t = new Thread(() -> {
            try {
                while (leaking && leakedMb() < target) {
                    byte[] chunk = new byte[16 * 1024 * 1024];
                    for (int i = 0; i < chunk.length; i += 4096) chunk[i] = 1; // touch pages so the OS really commits them
                    synchronized (leaked) { leaked.add(chunk); }
                    Thread.sleep(2000);
                }
            } catch (InterruptedException | OutOfMemoryError ignored) {
            } finally {
                leaking = false;
            }
        }, "memory-leak");
        t.setDaemon(true);
        t.start();
        return "Memory leak started: +16 MB every 2 s up to " + target + " MB (JVM max heap " + maxHeapMb + " MB)";
    }

    public String releaseMemory() {
        leaking = false;
        int mb = leakedMb();
        synchronized (leaked) { leaked.clear(); }
        System.gc();
        return "Released " + mb + " MB";
    }

    public String stopAll() {
        cpuStressUntil = 0;
        return "CPU stress stopped. " + releaseMemory();
    }

    private int leakedMb() {
        synchronized (leaked) { return leaked.size() * 16; }
    }
}
