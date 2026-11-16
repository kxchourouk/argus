package argus.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects alerts from every VM. Thread-safe: alerts arrive from several RMI
 * threads (one per agent call) and from the failure detector at the same time.
 * A cooldown per (VM, type) avoids flooding the dashboard with the same alert.
 */
public class AlertManager {

    private static final int MAX_KEPT = 500;
    private static final DateTimeFormatter TF =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Deque<Alert> alerts = new ArrayDeque<>();
    private final Map<String, Long> lastByKey = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final Path logFile;

    public AlertManager(Path dataDir) {
        this.logFile = dataDir.resolve("alerts.log");
    }

    /** Adds an alert unless the same (vm, type) was raised less than cooldownSec ago. */
    public boolean raise(String vm, Alert.Level level, String type, String message, int cooldownSec) {
        return raise(vm, level, type, message, cooldownSec, "");
    }

    /** Same, with a finer cooldown key (e.g. one cooldown per abnormal metric). */
    public boolean raise(String vm, Alert.Level level, String type, String message, int cooldownSec, String subKey) {
        long now = System.currentTimeMillis();
        String key = vm + "|" + type + "|" + subKey;
        Long last = lastByKey.get(key);
        if (cooldownSec > 0 && last != null && now - last < cooldownSec * 1000L) return false;
        lastByKey.put(key, now);

        Alert a = new Alert(ids.incrementAndGet(), now, vm, level, type, message);
        synchronized (alerts) {
            alerts.addFirst(a);
            if (alerts.size() > MAX_KEPT) alerts.removeLast();
        }
        String line = TF.format(Instant.ofEpochMilli(now)) + " [" + level + "] " + vm + " " + type + ": " + message;
        ServerMain.log(line);
        try {
            Files.write(logFile, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
        return true;
    }

    public List<Alert> latest(int n) {
        synchronized (alerts) {
            List<Alert> out = new ArrayList<>();
            for (Alert a : alerts) { if (out.size() >= n) break; out.add(a); }
            return out;
        }
    }

    public int countSince(long since) {
        synchronized (alerts) {
            int c = 0;
            for (Alert a : alerts) { if (a.time < since) break; if (a.level != Alert.Level.INFO) c++; }
            return c;
        }
    }

    public void clear() {
        synchronized (alerts) { alerts.clear(); }
        lastByKey.clear();
    }
}
