package argus.server;

import argus.common.MonitorServer;
import argus.server.web.WebServer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point on the HOST (your machine).
 *
 *   java -jar argus-server.jar --ip 192.168.56.1
 *
 *  1. creates the RMI registry (port 1099) and binds MonitorServer (port 1100);
 *  2. starts the failure detector (checks heartbeats every 2 s);
 *  3. starts the web dashboard on http://localhost:8080
 */
public class ServerMain {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        if (opt.containsKey("help")) { usage(); return; }

        int registryPort = Integer.parseInt(opt.getOrDefault("port", "1099"));
        int objectPort   = Integer.parseInt(opt.getOrDefault("object-port", "1100"));
        int httpPort     = Integer.parseInt(opt.getOrDefault("http", "8080"));
        String httpBind  = opt.getOrDefault("http-bind", "127.0.0.1");
        String token     = opt.getOrDefault("token", "sid-2026");
        long timeoutMs   = Long.parseLong(opt.getOrDefault("heartbeat-timeout", "7")) * 1000;
        Path dataDir     = Paths.get(opt.getOrDefault("data", "data"));
        String ip        = opt.containsKey("ip") ? opt.get("ip") : guessHostOnlyIp();

        // RMI settings: the IP written in our stubs, and short timeouts so a dead VM
        // is detected in seconds instead of minutes
        System.setProperty("java.rmi.server.hostname", ip);
        System.setProperty("sun.rmi.transport.tcp.responseTimeout", "4000");
        System.setProperty("sun.rmi.transport.connectionTimeout", "3000");
        System.setProperty("sun.rmi.transport.proxy.connectTimeout", "3000");
        System.setProperty("sun.rmi.transport.tcp.handshakeTimeout", "3000");

        Files.createDirectories(dataDir);
        AlertManager alerts = new AlertManager(dataDir);
        MetricsStore store = new MetricsStore(dataDir);
        MonitorServerImpl impl = new MonitorServerImpl(token, alerts, store);

        Registry registry = LocateRegistry.createRegistry(registryPort);
        MonitorServer stub = (MonitorServer) UnicastRemoteObject.exportObject(impl, objectPort);
        registry.rebind(MonitorServer.BINDING_NAME, stub);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "failure-detector"); t.setDaemon(true); return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try { impl.checkLiveness(timeoutMs); } catch (Throwable e) { log("detector error: " + e); }
        }, 2, 2, TimeUnit.SECONDS);

        CommandService commands = new CommandService(impl, token, alerts);
        new WebServer(impl, alerts, commands).start(httpBind, httpPort);

        log("==============================================================");
        log(" Argus - host server");
        log(" RMI registry : " + ip + ":" + registryPort + "  (object on port " + objectPort + ")");
        log(" Binding      : rmi://" + ip + ":" + registryPort + "/" + MonitorServer.BINDING_NAME);
        log(" Dashboard    : http://" + ("0.0.0.0".equals(httpBind) ? "localhost" : httpBind) + ":" + httpPort);
        log(" Data folder  : " + dataDir.toAbsolutePath());
        log(" Start agents on the VMs with:  --server " + ip);
        log("==============================================================");

        Thread.currentThread().join(); // keep the JVM alive
    }

    /**
     * Picks the VirtualBox host-only address (192.168.56.x) if present, otherwise the
     * first private IPv4. Prints every candidate so the user can force one with --ip.
     */
    static String guessHostOnlyIp() throws Exception {
        List<String> candidates = new ArrayList<>();
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                    candidates.add(a.getHostAddress());
                    log("Network interface: " + ni.getDisplayName() + " -> " + a.getHostAddress());
                }
            }
        }
        for (String c : candidates) if (c.startsWith("192.168.56.")) return c;
        if (!candidates.isEmpty()) {
            log("WARNING: no VirtualBox host-only address found, using " + candidates.get(0) + " (force it with --ip)");
            return candidates.get(0);
        }
        return "127.0.0.1";
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
        System.out.println("Usage: java -jar argus-server.jar [options]\n"
                + "  --ip <ip>               host IP the VMs can reach (default: auto, prefers 192.168.56.x)\n"
                + "  --port <n>              RMI registry port (default 1099)\n"
                + "  --object-port <n>       port of the MonitorServer object (default 1100)\n"
                + "  --http <n>              dashboard port (default 8080)\n"
                + "  --http-bind <ip>        dashboard bind address (default 127.0.0.1 = this machine only)\n"
                + "  --token <secret>        shared secret with the agents (default sid-2026)\n"
                + "  --heartbeat-timeout <s> seconds without metrics before suspecting a VM (default 7)\n"
                + "  --data <dir>            folder for CSV history and alerts.log (default ./data)");
    }

    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("HH:mm:ss");
    public static void log(String s) {
        System.out.println("[" + LocalTime.now().format(TF) + "] " + s);
    }
}
