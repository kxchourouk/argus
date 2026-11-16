package argus.server;

import argus.common.CommandResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Sends administration commands to one VM or to every VM at once.
 * "All VMs" runs in parallel (one thread per VM): a slow or dead VM does not
 * block the others, and each VM gets its own success/failure (partial failures).
 */
public class CommandService {

    public static final class Record {
        public final long time;
        public final String target, action, arg;
        public final List<CommandResult> results;
        Record(String target, String action, String arg, List<CommandResult> results) {
            this.time = System.currentTimeMillis();
            this.target = target; this.action = action; this.arg = arg; this.results = results;
        }
    }

    private final MonitorServerImpl server;
    private final String token;
    private final AlertManager alerts;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "command"); t.setDaemon(true); return t;
    });
    private final Deque<Record> history = new ArrayDeque<>();

    public CommandService(MonitorServerImpl server, String token, AlertManager alerts) {
        this.server = server;
        this.token = token;
        this.alerts = alerts;
    }

    /** @param target a VM name, or "*" for every reachable VM */
    public Record run(String target, String action, String arg) {
        List<VmState> targets = new ArrayList<>();
        if ("*".equals(target)) {
            for (VmState vm : server.vms()) if (vm.isReachable()) targets.add(vm);
        } else {
            VmState vm = server.vm(target);
            if (vm != null) targets.add(vm);
        }

        List<Future<CommandResult>> futures = new ArrayList<>();
        for (VmState vm : targets) {
            futures.add(pool.submit(() -> vm.stub.execute(action, arg, token)));
        }
        List<CommandResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            String vmName = targets.get(i).info.name;
            try {
                results.add(futures.get(i).get(30, TimeUnit.SECONDS));
            } catch (Exception e) {
                futures.get(i).cancel(true);
                Throwable c = e.getCause() != null ? e.getCause() : e;
                results.add(CommandResult.of(vmName, action, false,
                        "Remote call failed: " + c.getClass().getSimpleName() + " " + (c.getMessage() == null ? "" : c.getMessage()), 0));
            }
        }
        if (targets.isEmpty())
            results.add(CommandResult.of(target, action, false, "No reachable VM matches '" + target + "'", 0));

        long ok = results.stream().filter(r -> r.success).count();
        alerts.raise("*".equals(target) ? "ALL" : target, Alert.Level.INFO, "COMMAND",
                "Command '" + action + (arg.isEmpty() ? "" : " " + arg) + "' -> " + ok + "/" + results.size() + " succeeded", 0);

        Record rec = new Record(target, action, arg, results);
        synchronized (history) {
            history.addFirst(rec);
            if (history.size() > 30) history.removeLast();
        }
        return rec;
    }

    public List<Record> history() {
        synchronized (history) { return new ArrayList<>(history); }
    }
}
