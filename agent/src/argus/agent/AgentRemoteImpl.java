package argus.agent;

import argus.common.AgentRemote;
import argus.common.CommandResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The agent's remote object: the host calls these methods on the VM.
 * Security: every command needs the shared token, and only whitelisted actions
 * are accepted. Free shell commands are disabled unless the agent was started
 * with --allow-shell.
 */
public class AgentRemoteImpl implements AgentRemote {

    private final String vmName;
    private final String token;
    private final boolean allowShell;
    private final LoadSimulator load = new LoadSimulator();
    private final boolean linux;

    public AgentRemoteImpl(String vmName, String token, boolean allowShell, boolean linux) {
        this.vmName = vmName;
        this.token = token;
        this.allowShell = allowShell;
        this.linux = linux;
    }

    @Override
    public String ping() {
        return vmName;
    }

    @Override
    public List<String> availableActions() {
        List<String> a = new ArrayList<>(Arrays.asList(
                "sysinfo", "uptime", "top", "disk", "network", "kill",
                "stress-cpu", "leak-memory", "release-memory", "stop-load"));
        if (allowShell) a.add("shell");
        return a;
    }

    @Override
    public CommandResult execute(String action, String arg, String tok) {
        long t0 = System.currentTimeMillis();
        String a = action == null ? "" : action.trim();
        arg = arg == null ? "" : arg.trim();
        if (!token.equals(tok)) {
            AgentMain.log("REFUSED command '" + a + "' (bad token)");
            return CommandResult.of(vmName, a, false, "Access denied: invalid token", 0);
        }
        AgentMain.log("Command received: " + a + (arg.isEmpty() ? "" : " " + arg));
        try {
            String out;
            switch (a) {
                case "sysinfo":        out = sysinfo(); break;
                case "uptime":         out = linux ? run("uptime") : "JVM uptime: " + java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000 + " s"; break;
                case "top":            out = linux ? run("ps", "-eo", "pid,user,comm,%cpu,%mem", "--sort=-%cpu").lines().limit(12).reduce("", (x, y) -> x + y + "\n")
                                                   : run("tasklist"); break;
                case "disk":           out = disk(); break;
                case "network":        out = network(); break;
                case "kill":           out = kill(arg); break;
                case "stress-cpu":     out = load.stressCpu(parseInt(arg, 30)); break;
                case "leak-memory":    out = load.leakMemory(parseInt(arg, 300)); break;
                case "release-memory": out = load.releaseMemory(); break;
                case "stop-load":      out = load.stopAll(); break;
                case "shell":
                    if (!allowShell) throw new SecurityException("shell is disabled on this agent");
                    out = linux ? run("bash", "-c", arg) : run("cmd", "/c", arg);
                    break;
                default:
                    return CommandResult.of(vmName, a, false, "Unknown action. Allowed: " + availableActions(), 0);
            }
            return CommandResult.of(vmName, a, true, out, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return CommandResult.of(vmName, a, false, "Error: " + e.getMessage(), System.currentTimeMillis() - t0);
        }
    }

    // ------------------------------------------------------------------ actions

    private String sysinfo() throws Exception {
        Runtime rt = Runtime.getRuntime();
        return "Hostname : " + InetAddress.getLocalHost().getHostName() + "\n"
                + "OS       : " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")\n"
                + "Cores    : " + rt.availableProcessors() + "\n"
                + "Java     : " + System.getProperty("java.version") + "\n"
                + "Agent JVM: " + (rt.totalMemory() - rt.freeMemory()) / 1048576 + " MB used / "
                + rt.maxMemory() / 1048576 + " MB max\n"
                + "Agent PID: " + ProcessHandle.current().pid();
    }

    private String disk() {
        StringBuilder sb = new StringBuilder(String.format("%-12s %10s %10s %6s%n", "Mount", "Size(GB)", "Free(GB)", "Used"));
        File[] roots = linux ? new File[]{ new File("/"), new File("/home"), new File("/var") } : File.listRoots();
        for (File r : roots) {
            if (!r.exists() || r.getTotalSpace() == 0) continue;
            double total = r.getTotalSpace() / 1e9, free = r.getUsableSpace() / 1e9;
            sb.append(String.format("%-12s %10.1f %10.1f %5.0f%%%n", r.getPath(), total, free, 100 * (total - free) / total));
        }
        return sb.toString();
    }

    private String network() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp()) continue;
            sb.append(ni.getName()).append(ni.isLoopback() ? " (loopback)" : "").append(": ");
            for (InetAddress ad : Collections.list(ni.getInetAddresses())) sb.append(ad.getHostAddress()).append("  ");
            sb.append("\n");
        }
        return sb.toString();
    }

    private String kill(String arg) {
        long pid = Long.parseLong(arg);
        if (pid <= 1) throw new IllegalArgumentException("refusing to kill PID " + pid);
        if (pid == ProcessHandle.current().pid()) throw new IllegalArgumentException("refusing to kill the agent itself");
        ProcessHandle ph = ProcessHandle.of(pid).orElseThrow(() -> new IllegalArgumentException("no process " + pid));
        String name = ph.info().command().orElse("?");
        boolean ok = ph.destroy();
        return (ok ? "Sent SIGTERM to " : "Could not stop ") + pid + " (" + name + ")";
    }

    /** Runs a program with fixed arguments (no shell, so no command injection). */
    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null && sb.length() < 20000) sb.append(line).append('\n');
        }
        if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroyForcibly(); sb.append("[timeout]"); }
        return sb.toString();
    }

    private static int parseInt(String s, int def) {
        try { return s.isEmpty() ? def : Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
