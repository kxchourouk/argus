package argus.server.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import argus.common.CommandResult;
import argus.common.Metrics;
import argus.server.Alert;
import argus.server.AlertManager;
import argus.server.CommandService;
import argus.server.MonitorServerImpl;
import argus.server.VmState;
import argus.server.ai.AnomalyDetector;
import argus.server.ai.Forecaster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Web dashboard served by the host (JDK built-in HTTP server, no dependency).
 *   GET  /               the dashboard page
 *   GET  /api/state      all VMs, histories, AI results, alerts (JSON, polled every 2 s)
 *   POST /api/command    run a command on one VM or on all (target=*)
 *   POST /api/alerts/clear
 * The browser never talks to the VMs: the host is the only RMI client of the agents.
 */
public class WebServer {

    private final MonitorServerImpl server;
    private final AlertManager alerts;
    private final CommandService commands;
    private final long startedAt = System.currentTimeMillis();
    private HttpServer http;

    public WebServer(MonitorServerImpl server, AlertManager alerts, CommandService commands) {
        this.server = server;
        this.alerts = alerts;
        this.commands = commands;
    }

    public void start(String bind, int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        http.setExecutor(Executors.newFixedThreadPool(4));
        http.createContext("/", this::page);
        http.createContext("/api/state", ex -> send(ex, 200, "application/json", state()));
        http.createContext("/api/command", this::command);
        http.createContext("/api/alerts/clear", ex -> { alerts.clear(); send(ex, 200, "application/json", "{\"ok\":true}"); });
        http.start();
    }

    // ------------------------------------------------------------------ handlers

    private void page(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) { send(ex, 404, "text/plain", "Not found"); return; }
        try (InputStream in = WebServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) { send(ex, 500, "text/plain", "index.html missing from the classpath"); return; }
            send(ex, 200, "text/html; charset=utf-8", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void command(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "POST only"); return; }
        Map<String, String> f = form(ex);
        CommandService.Record rec = commands.run(f.getOrDefault("target", "*"),
                f.getOrDefault("action", ""), f.getOrDefault("arg", ""));
        Json j = new Json().obj().put("target", rec.target).put("action", rec.action).key("results").arr();
        for (CommandResult r : rec.results) {
            j.obj().put("vm", r.vmName).put("success", r.success).put("output", r.output).put("ms", r.durationMs).end();
        }
        send(ex, 200, "application/json", j.endArr().end().toString());
    }

    private String state() {
        long now = System.currentTimeMillis();
        List<VmState> list = new ArrayList<>(server.vms());
        list.sort(Comparator.comparing(v -> v.info.name));

        Json j = new Json().obj().put("now", now).put("uptime", (now - startedAt) / 1000);
        int online = 0, down = 0, anomalous = 0;
        j.key("vms").arr();
        for (VmState vm : list) {
            if (vm.status == VmState.Status.ONLINE || vm.status == VmState.Status.SUSPECT) online++;
            if (vm.status == VmState.Status.DOWN) down++;
            AnomalyDetector.Result r = vm.lastResult;
            if (r != null && r.anomalous && vm.isReachable()) anomalous++;

            j.obj().put("name", vm.info.name).put("address", vm.info.address).put("os", vm.info.os)
                    .put("cores", vm.info.cores).put("java", vm.info.javaVersion)
                    .put("status", vm.status.name()).put("lastSeen", vm.lastSeen)
                    .put("registeredAt", vm.registeredAt).put("anomalyCount", vm.anomalyCount);

            Metrics m = vm.latest();
            if (m != null) {
                j.key("latest").obj().put("cpu", m.cpuPercent).put("ram", m.ramPercent).put("ramUsed", m.ramUsedMb)
                        .put("ramTotal", m.ramTotalMb).put("disk", m.diskPercent).put("rx", m.netRxKBs).put("tx", m.netTxKBs)
                        .put("procs", m.processCount).put("load", m.loadAvg).put("uptime", m.uptimeSec).end();
            }
            if (r != null) {
                j.key("ai").obj().put("warmingUp", r.warmingUp).put("training", r.trainingSize)
                        .put("warmup", AnomalyDetector.WARMUP).put("score", r.score).put("anomalous", r.anomalous)
                        .put("feature", r.feature).put("z", r.maxZ).put("threshold", r.threshold).put("reason", r.reason).end();
            }
            Forecaster.Trend tr = vm.ramTrend;
            if (tr != null) {
                j.key("forecast").obj().put("slopePerMin", tr.slopePerSec * 60).put("r2", tr.r2)
                        .put("in5min", tr.in5min).put("etaTo90", tr.r2 >= 0.8 ? tr.secondsTo(90) : -1).end();
            }
            // history for the charts (last 5 minutes)
            List<VmState.Sample> h = vm.history(150);
            Json t = new Json().arr(), cpu = new Json().arr(), ram = new Json().arr(), disk = new Json().arr(),
                    net = new Json().arr(), score = new Json().arr(), an = new Json().arr();
            for (VmState.Sample s : h) {
                t.val(s.m.timestamp); cpu.val(s.m.cpuPercent); ram.val(s.m.ramPercent); disk.val(s.m.diskPercent);
                net.val(s.m.netRxKBs + s.m.netTxKBs); score.val(s.score); an.val(s.anomaly ? 1 : 0);
            }
            j.key("history").obj();
            j.key("t");     raw(j, t);
            j.key("cpu");   raw(j, cpu);
            j.key("ram");   raw(j, ram);
            j.key("disk");  raw(j, disk);
            j.key("net");   raw(j, net);
            j.key("score"); raw(j, score);
            j.key("anom");  raw(j, an);
            j.end();
            j.end();
        }
        j.endArr();

        j.key("stats").obj().put("total", list.size()).put("online", online).put("down", down)
                .put("anomalous", anomalous).put("alerts10min", alerts.countSince(now - 600_000)).end();

        j.key("alerts").arr();
        for (Alert a : alerts.latest(60)) {
            j.obj().put("id", a.id).put("time", a.time).put("vm", a.vm).put("level", a.level.name())
                    .put("type", a.type).put("message", a.message).end();
        }
        j.endArr();
        return j.end().toString();
    }

    /** Inserts an already-built JSON array as a value. */
    private static void raw(Json parent, Json array) {
        parent.rawValue(array.endArr().toString());
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, String> form(HttpExchange ex) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ex.getRequestBody().transferTo(bo);
        Map<String, String> m = new HashMap<>();
        for (String pair : bo.toString(StandardCharsets.UTF_8).split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            m.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                  URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
