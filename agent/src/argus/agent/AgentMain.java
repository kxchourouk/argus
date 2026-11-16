package argus.agent;

import argus.common.AgentInfo;
import argus.common.AgentRemote;
import argus.common.Metrics;
import argus.common.MonitorServer;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Entry point of the agent that runs on each VM.
 *
 *   java -jar argus-agent.jar --server 192.168.56.1 --name vm1-ubuntu
 *
 * Steps:
 *  1. export its own remote object (AgentRemote) on a fixed port, so the host can call it;
 *  2. look up MonitorServer in the host's RMI registry and register (sending its stub);
 *  3. push metrics every N seconds; if the host is unreachable, retry until it comes back.
 */
public class AgentMain {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        if (opt.containsKey("help")) { usage(); return; }

        String serverHost = opt.getOrDefault("server", "127.0.0.1");
        int registryPort  = Integer.parseInt(opt.getOrDefault("port", "1099"));
        int agentPort     = Integer.parseInt(opt.getOrDefault("agent-port", "1101"));
        int intervalSec   = Integer.parseInt(opt.getOrDefault("interval", "2"));
        String token      = opt.getOrDefault("token", "sid-2026");
        boolean allowShell = opt.containsKey("allow-shell");
        String name       = opt.getOrDefault("name", InetAddress.getLocalHost().getHostName());
        // IP the host will use to call us back: by default, the local address of the
        // interface that routes to the server (avoids picking the NAT interface 10.0.2.x)
        String myIp       = opt.getOrDefault("ip", detectLocalIp(serverHost));

        // MUST be set before exporting: the IP written inside our stub
        System.setProperty("java.rmi.server.hostname", myIp);

        MetricsCollector collector = new MetricsCollector(name);
        AgentRemoteImpl impl = new AgentRemoteImpl(name, token, allowShell, collector.isLinux());
        AgentRemote stub = (AgentRemote) UnicastRemoteObject.exportObject(impl, agentPort);

        AgentInfo info = new AgentInfo();
        info.name = name;
        info.address = myIp;
        info.os = System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")";
        info.cores = Runtime.getRuntime().availableProcessors();
        info.totalRamMb = collector.collect().ramTotalMb;
        info.javaVersion = System.getProperty("java.version");

        log("Agent '" + name + "' exported on " + myIp + ":" + agentPort + (allowShell ? " (shell ENABLED)" : ""));
        log("Host: rmi://" + serverHost + ":" + registryPort + "/" + MonitorServer.BINDING_NAME);

        final MonitorServer[] server = { null };
        final boolean[] accepted = { false };
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (server[0] == null || !accepted[0]) return;
                server[0].unregister(name, token);
                log("Unregistered cleanly");
            } catch (Exception ignored) { }
        }));

        boolean registered = false;
        int failures = 0;
        while (true) {
            try {
                if (!registered) {
                    Registry reg = LocateRegistry.getRegistry(serverHost, registryPort);
                    server[0] = (MonitorServer) reg.lookup(MonitorServer.BINDING_NAME);
                    if (!server[0].register(info, stub, token)) {
                        log("Registration refused by host (check the token). Exiting.");
                        System.exit(1);
                    }
                    registered = true;
                    accepted[0] = true;
                    failures = 0;
                    log("Registered with host " + serverHost);
                }
                Metrics m = collector.collect();
                if (!server[0].pushMetrics(m, token)) {
                    log("Host does not know us anymore (restarted?) -> registering again");
                    registered = false;
                    continue;
                }
                if (opt.containsKey("verbose")) log(m.toString());
            } catch (Exception e) {
                registered = false;
                if (failures++ % 5 == 0) log("Host unreachable (" + e.getClass().getSimpleName() + ": "
                        + rootMessage(e) + ") - retrying...");
            }
            Thread.sleep(registered ? intervalSec * 1000L : 3000L);
        }
    }

    /** Finds the local IP used to reach the server (no packet is actually sent). */
    static String detectLocalIp(String serverHost) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName(serverHost), 9);
            String ip = s.getLocalAddress().getHostAddress();
            if (!ip.equals("0.0.0.0")) return ip;
        } catch (Exception ignored) { }
        try { return InetAddress.getLocalHost().getHostAddress(); } catch (Exception e) { return "127.0.0.1"; }
    }

    static String rootMessage(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.getMessage();
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String k = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) m.put(k, args[++i]);
            else m.put(k, "true");
        }
        return m;
    }

    static void usage() {
        System.out.println("Usage: java -jar argus-agent.jar [options]\n"
                + "  --server <ip>       host IP (default 127.0.0.1)\n"
                + "  --port <n>          RMI registry port on the host (default 1099)\n"
                + "  --name <name>       VM name shown on the dashboard (default hostname)\n"
                + "  --ip <ip>           this VM's IP seen by the host (auto-detected)\n"
                + "  --agent-port <n>    port of this agent's remote object (default 1101)\n"
                + "  --interval <s>      seconds between two metrics (default 2)\n"
                + "  --token <secret>    shared secret (default sid-2026)\n"
                + "  --allow-shell       allow free shell commands from the host (off by default)\n"
                + "  --verbose           print every metrics snapshot");
    }

    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("HH:mm:ss");
    static void log(String s) {
        System.out.println("[" + LocalTime.now().format(TF) + "] " + s);
    }
}
