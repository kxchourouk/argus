package argus.server;

/** One alert shown on the dashboard and written to data/alerts.log. */
public class Alert {
    public enum Level { INFO, WARNING, CRITICAL }

    public final long id;
    public final long time;
    public final String vm;
    public final Level level;
    public final String type;     // ANOMALY, FORECAST, CPU_HIGH, RAM_HIGH, DISK_HIGH, VM_DOWN, VM_UP, ...
    public final String message;

    public Alert(long id, long time, String vm, Level level, String type, String message) {
        this.id = id; this.time = time; this.vm = vm; this.level = level; this.type = type; this.message = message;
    }
}
