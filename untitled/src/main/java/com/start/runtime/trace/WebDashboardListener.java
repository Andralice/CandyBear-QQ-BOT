package com.start.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.runtime.RuntimeEvent;
import com.start.runtime.RuntimeListener;
import com.start.service.GenerationMetadata;
import com.start.service.GenerationResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Web 可观测性面板 — RuntimeListener，提供实时决策链路、群聊指标和系统健康。
 * 内嵌 HTTP 服务器，不依赖外部容器。不改 Runtime 一行代码。
 */
public class WebDashboardListener implements RuntimeListener {

    private static final Logger logger = LoggerFactory.getLogger(WebDashboardListener.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final int DEFAULT_PORT = 8765;
    private static final int MAX_DECISIONS = 300;

    // —— 内部数据记录 ——
    static final class DecisionEntry {
        final long timestamp;
        final String groupId;
        final String userId;
        final String eventType;
        final String decision;   // REPLY / SILENT / ERROR
        final String reason;
        final int toolCalls;
        final int tokensUsed;
        final long latencyMs;
        final long generation;
        final long revision;

        DecisionEntry(long timestamp, String groupId, String userId, String eventType,
                      String decision, String reason, int toolCalls, int tokensUsed,
                      long latencyMs, long generation, long revision) {
            this.timestamp = timestamp;
            this.groupId = groupId;
            this.userId = userId;
            this.eventType = eventType;
            this.decision = decision;
            this.reason = reason;
            this.toolCalls = toolCalls;
            this.tokensUsed = tokensUsed;
            this.latencyMs = latencyMs;
            this.generation = generation;
            this.revision = revision;
        }
    }

    static final class GroupSummary {
        final String groupId;
        final AtomicLong messages = new AtomicLong();
        final AtomicLong replies = new AtomicLong();
        final AtomicLong silent = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final AtomicLong totalTokens = new AtomicLong();
        final AtomicLong totalLatencyMs = new AtomicLong();
        volatile long lastActive;

        GroupSummary(String groupId) { this.groupId = groupId; }
    }

    private static volatile WebDashboardListener instance;

    private final ConcurrentLinkedDeque<DecisionEntry> decisions = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, AtomicLong> toolCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GroupSummary> groups = new ConcurrentHashMap<>();
    private final AtomicLong totalMessages = new AtomicLong();
    private final AtomicLong totalReplies = new AtomicLong();
    private final AtomicLong totalSilent = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final long startTime = System.currentTimeMillis();

    private HttpServer server;
    private final int port;
    private final String host;
    private final String token;   // null = 不需要鉴权

    public WebDashboardListener() {
        this.host = System.getProperty("dashboard.host",
                System.getenv().getOrDefault("DASHBOARD_HOST", "0.0.0.0"));
        this.port = Integer.parseInt(System.getProperty("dashboard.port",
                System.getenv().getOrDefault("DASHBOARD_PORT", String.valueOf(DEFAULT_PORT))));
        String t = System.getenv("DASHBOARD_TOKEN");
        this.token = (t != null && !t.isBlank()) ? t : null;
    }

    // ===== 公开 API =====

    /** 启动内嵌 HTTP 服务器（守护线程，不阻止 JVM 退出）。 */
    public void start() {
        if (server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "dashboard-http");
                t.setDaemon(true);
                return t;
            }));
            server.createContext("/", this::serveHtml);
            server.createContext("/api/decisions", this::serveDecisions);
            server.createContext("/api/groups", this::serveGroups);
            server.createContext("/api/system", this::serveSystem);
            server.start();
            instance = this;
            logger.info("WebDashboard 已启动: http://{}:{}{}", host, port,
                    token != null ? " (Token 鉴权已启用)" : "");
        } catch (IOException e) {
            logger.error("WebDashboard 启动失败", e);
        }
    }

    /** 停止 HTTP 服务器。 */
    public void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
            instance = null;
        }
    }

    /** 静态记录决策（供 AIHandler 等非 Runtime 路径直接调用）。 */
    public static void recordDecision(String groupId, String userId, String eventType,
                                       String decision, String reason, int toolCalls,
                                       int tokensUsed, long latencyMs) {
        WebDashboardListener inst = instance;
        if (inst == null) return;
        inst.addDecision(new DecisionEntry(System.currentTimeMillis(), groupId, userId,
                eventType, decision, reason, toolCalls, tokensUsed, latencyMs, 0, 0));
        GroupSummary gs = inst.groups.computeIfAbsent(groupId, k -> new GroupSummary(k));
        switch (decision) {
            case "REPLY" -> { inst.totalReplies.incrementAndGet(); gs.replies.incrementAndGet(); }
            case "SILENT" -> { inst.totalSilent.incrementAndGet(); gs.silent.incrementAndGet(); }
            case "ERROR"  -> { inst.totalErrors.incrementAndGet(); gs.errors.incrementAndGet(); }
        }
        if (tokensUsed > 0) gs.totalTokens.addAndGet(tokensUsed);
        if (latencyMs > 0) gs.totalLatencyMs.addAndGet(latencyMs);
    }

    /** 静态记录消息（供 AIHandler 调用）。 */
    public static void recordMessage(String groupId, String userId) {
        WebDashboardListener inst = instance;
        if (inst == null) return;
        inst.totalMessages.incrementAndGet();
        GroupSummary gs = inst.groups.computeIfAbsent(groupId, k -> new GroupSummary(k));
        gs.messages.incrementAndGet();
        gs.lastActive = System.currentTimeMillis();
    }

    /** 静态记录工具调用次数。 */
    public static void recordToolCall(String toolName) {
        WebDashboardListener inst = instance;
        if (inst == null) return;
        inst.toolCallCounts.computeIfAbsent(toolName, k -> new AtomicLong()).incrementAndGet();
    }

    // ===== RuntimeListener 实现 =====

    @Override
    public void onEvent(RuntimeEvent e) {
        if (e instanceof RuntimeEvent.MessageReceived m) {
            totalMessages.incrementAndGet();
            GroupSummary gs = groups.computeIfAbsent(m.groupId(), k -> new GroupSummary(k));
            gs.messages.incrementAndGet();
            gs.lastActive = System.currentTimeMillis();
        } else if (e instanceof RuntimeEvent.CommitFinished f) {
            GenerationResult r = f.result();
            GenerationMetadata m = r != null ? r.metadata() : null;
            String dec = r != null && r.isSilent() ? "SILENT"
                    : r != null && r.isError() ? "ERROR" : "REPLY";
            String reason = r != null && r.isSilent() ? "model_no_reply" : "ok";
            int tools = m != null ? m.toolCalls() : 0;
            int tokens = m != null ? m.tokensUsed() : 0;

            addDecision(new DecisionEntry(System.currentTimeMillis(), f.groupId(), f.userId(),
                    "COMMIT", dec, reason, tools, tokens, 0,
                    m != null ? m.generation() : 0, m != null ? m.revision() : 0));

            GroupSummary gs = groups.computeIfAbsent(f.groupId(), k -> new GroupSummary(k));
            switch (dec) {
                case "REPLY" -> { totalReplies.incrementAndGet(); gs.replies.incrementAndGet(); }
                case "SILENT" -> { totalSilent.incrementAndGet(); gs.silent.incrementAndGet(); }
                case "ERROR"  -> { totalErrors.incrementAndGet(); gs.errors.incrementAndGet(); }
            }
            if (tokens > 0) gs.totalTokens.addAndGet(tokens);
        }
    }

    // ===== 内部方法 =====

    private void addDecision(DecisionEntry e) {
        decisions.addLast(e);
        while (decisions.size() > MAX_DECISIONS) {
            decisions.pollFirst();
        }
    }

    // ===== HTTP 处理器 =====

    /** 鉴权检查。未配置 token 时直接放行。 */
    private boolean checkAuth(HttpExchange ex) throws IOException {
        if (token == null) return true;
        String qt = parseQuery(ex, "token");
        if (token.equals(qt)) return true;
        byte[] body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(401, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
        return false;
    }

    private void serveHtml(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        byte[] bytes = HTML.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void serveDecisions(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        int limit = parseQueryInt(ex, "limit", 100);
        ArrayNode arr = mapper.createArrayNode();
        List<DecisionEntry> snapshot = new ArrayList<>(decisions);
        int skip = Math.max(0, snapshot.size() - limit);
        int idx = 0;
        for (DecisionEntry d : snapshot) {
            if (idx++ < skip) continue;
            ObjectNode o = mapper.createObjectNode();
            o.put("time", Instant.ofEpochMilli(d.timestamp)
                    .atZone(ZoneId.of("Asia/Shanghai"))
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss")));
            o.put("groupId", d.groupId);
            o.put("userId", d.userId != null ? d.userId : "");
            o.put("event", d.eventType);
            o.put("decision", d.decision);
            o.put("reason", d.reason);
            o.put("toolCalls", d.toolCalls);
            o.put("tokensUsed", d.tokensUsed);
            o.put("latencyMs", d.latencyMs);
            o.put("generation", d.generation);
            o.put("revision", d.revision);
            arr.add(o);
        }
        sendJson(ex, arr.toString());
    }

    private void serveGroups(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        ArrayNode arr = mapper.createArrayNode();
        List<GroupSummary> sorted = new ArrayList<>(groups.values());
        sorted.sort(Comparator.comparingLong(g -> -g.lastActive));
        for (GroupSummary g : sorted) {
            ObjectNode o = mapper.createObjectNode();
            o.put("groupId", g.groupId);
            o.put("messages", g.messages.get());
            o.put("replies", g.replies.get());
            o.put("silent", g.silent.get());
            o.put("errors", g.errors.get());
            o.put("totalTokens", g.totalTokens.get());
            o.put("avgLatencyMs", g.replies.get() > 0
                    ? g.totalLatencyMs.get() / g.replies.get() : 0);
            o.put("lastActive", g.lastActive > 0
                    ? Instant.ofEpochMilli(g.lastActive)
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    : "-");
            arr.add(o);
        }
        sendJson(ex, arr.toString());
    }

    private void serveSystem(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        Runtime rt = Runtime.getRuntime();
        long uptimeMs = System.currentTimeMillis() - startTime;
        ObjectNode o = mapper.createObjectNode();
        o.put("uptime", formatDuration(uptimeMs));
        o.put("uptimeMs", uptimeMs);
        o.put("totalMessages", totalMessages.get());
        o.put("totalReplies", totalReplies.get());
        o.put("totalSilent", totalSilent.get());
        o.put("totalErrors", totalErrors.get());
        o.put("heapUsedMB", (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
        o.put("heapMaxMB", rt.maxMemory() / 1024 / 1024);
        o.put("threadCount", ManagementFactory.getThreadMXBean().getThreadCount());
        o.put("activeGroups", groups.size());

        // 工具调用排行 Top 15
        ArrayNode tools = mapper.createArrayNode();
        toolCallCounts.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicLong>comparingByValue(
                        Comparator.comparingLong(AtomicLong::get)).reversed())
                .limit(15)
                .forEach(e -> {
                    ObjectNode t = mapper.createObjectNode();
                    t.put("name", e.getKey());
                    t.put("count", e.getValue().get());
                    tools.add(t);
                });
        o.set("topTools", tools);

        sendJson(ex, o.toString());
    }

    // ===== 工具方法 =====

    private static String parseQuery(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) return kv[1];
        }
        return null;
    }

    private static int parseQueryInt(HttpExchange ex, String key, int def) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return def;
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                try { return Integer.parseInt(kv[1]); } catch (NumberFormatException ignored) {}
            }
        }
        return def;
    }

    private static void sendJson(HttpExchange ex, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String formatDuration(long ms) {
        Duration d = Duration.ofMillis(ms);
        long days = d.toDays();
        long hours = d.toHours() % 24;
        long mins = d.toMinutes() % 60;
        if (days > 0) return String.format("%dd %dh %dm", days, hours, mins);
        if (hours > 0) return String.format("%dh %dm", hours, mins);
        return String.format("%dm %ds", mins, d.toSeconds() % 60);
    }

    // ===== 内嵌 HTML 面板 =====

    private static final String HTML = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>糖果熊 Dashboard</title>
            <style>
            :root {
                --bg: #0f0f1a; --card: #1a1a2e; --border: #2a2a4a;
                --text: #cdd6f4; --muted: #6c7086; --accent: #f5c2e7;
                --green: #a6e3a1; --yellow: #f9e2af; --red: #f38ba8; --blue: #89b4fa;
            }
            * { margin:0; padding:0; box-sizing:border-box; }
            body { background:var(--bg); color:var(--text); font-family:"Segoe UI",system-ui,sans-serif;
                   padding:20px; min-height:100vh; }
            .header { display:flex; justify-content:space-between; align-items:center;
                      background:var(--card); padding:16px 24px; border-radius:12px;
                      margin-bottom:16px; border:1px solid var(--border); }
            .header h1 { font-size:1.4rem; color:var(--accent); }
            .header .stats { display:flex; gap:24px; font-size:0.85rem; color:var(--muted); }
            .header .stats span { color:var(--text); font-weight:600; }
            .grid { display:grid; grid-template-columns:1.5fr 1fr; gap:16px; }
            .panel { background:var(--card); border:1px solid var(--border);
                     border-radius:12px; padding:16px; }
            .panel h2 { font-size:0.95rem; color:var(--blue); margin-bottom:12px;
                        text-transform:uppercase; letter-spacing:0.05em; }
            .trail-table { width:100%; font-size:0.78rem; border-collapse:collapse; }
            .trail-table th { text-align:left; color:var(--muted); padding:4px 6px;
                              border-bottom:1px solid var(--border); position:sticky; top:0;
                              background:var(--card); }
            .trail-table td { padding:3px 6px; border-bottom:1px solid #1e1e36; }
            .trail-table .REPLY { color:var(--green); }
            .trail-table .SILENT { color:var(--muted); }
            .trail-table .ERROR { color:var(--red); }
            .scroll { max-height:65vh; overflow-y:auto; }
            .group-card { background:#16162a; border:1px solid var(--border); border-radius:8px;
                          padding:10px 14px; margin-bottom:8px; font-size:0.82rem; }
            .group-card .gid { color:var(--blue); font-weight:600; margin-bottom:4px; }
            .group-card .row { display:flex; gap:16px; color:var(--muted); }
            .group-card .row span { color:var(--text); }
            .tool-row { display:flex; justify-content:space-between; font-size:0.8rem;
                        padding:3px 0; border-bottom:1px solid #1e1e36; }
            .tool-row .name { color:var(--text); }
            .tool-row .count { color:var(--accent); font-weight:600; }
            .footer { margin-top:16px; text-align:center; font-size:0.75rem; color:var(--muted); }
            .pulse { animation:pulse 2s infinite; }
            @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:0.5} }
            </style>
            </head>
            <body>
            <div class="header">
                <h1>糖果熊 Dashboard</h1>
                <div class="stats">
                    <div>运行 <span id="uptime">-</span></div>
                    <div>消息 <span id="totalMsg">0</span></div>
                    <div>回复 <span id="totalReply">0</span></div>
                    <div>静默 <span id="totalSilent">0</span></div>
                    <div>错误 <span id="totalError">0</span></div>
                    <div>堆内存 <span id="heap">-</span></div>
                    <div class="pulse" style="color:var(--green);" id="liveDot">● LIVE</div>
                </div>
            </div>
            <div class="grid">
                <div class="panel">
                    <h2>Decision Trail</h2>
                    <div class="scroll">
                    <table class="trail-table">
                    <thead><tr>
                        <th>时间</th><th>群</th><th>用户</th><th>事件</th><th>决策</th>
                        <th>原因</th><th>工具</th><th>Token</th><th>延迟</th>
                    </tr></thead>
                    <tbody id="trailBody"></tbody>
                    </table>
                    </div>
                </div>
                <div>
                    <div class="panel" style="margin-bottom:16px;">
                        <h2>Group Metrics</h2>
                        <div id="groupCards" style="max-height:35vh;overflow-y:auto;">
                            <span style="color:var(--muted);">等待数据...</span>
                        </div>
                    </div>
                    <div class="panel">
                        <h2>Tool Call Stats</h2>
                        <div id="toolStats"><span style="color:var(--muted);">等待数据...</span></div>
                    </div>
                </div>
            </div>
            <div class="footer">refresh: 3s | threads: <span id="threadCount">-</span></div>
            <script>
            const params = new URLSearchParams(location.search);
            const token = params.get('token');
            if (token) sessionStorage.setItem('dash_token', token);
            const auth = (u) => { const t = sessionStorage.getItem('dash_token'); return t ? u + (u.includes('?')?'&':'?') + 'token=' + t : u; };
            async function refresh() {
                try {
                    let [sysRes, decRes, grpRes] = await Promise.all([
                        fetch(auth('/api/system')), fetch(auth('/api/decisions?limit=50')), fetch(auth('/api/groups'))
                    ]);
                    let sys = await sysRes.json();
                    document.getElementById('uptime').textContent = sys.uptime;
                    document.getElementById('totalMsg').textContent = sys.totalMessages;
                    document.getElementById('totalReply').textContent = sys.totalReplies;
                    document.getElementById('totalSilent').textContent = sys.totalSilent;
                    document.getElementById('totalError').textContent = sys.totalErrors;
                    document.getElementById('heap').textContent = sys.heapUsedMB + '/' + sys.heapMaxMB + ' MB';
                    document.getElementById('threadCount').textContent = sys.threadCount + ' threads | active groups: ' + sys.activeGroups;

                    let decs = await decRes.json();
                    let tb = document.getElementById('trailBody');
                    tb.innerHTML = decs.reverse().map(d =>
                        `<tr>
                            <td>${d.time}</td>
                            <td>${d.groupId}</td>
                            <td>${d.userId}</td>
                            <td>${d.event}</td>
                            <td class="${d.decision}">${d.decision}</td>
                            <td style="color:var(--muted)">${d.reason}</td>
                            <td>${d.toolCalls||0}</td>
                            <td>${d.tokensUsed||0}</td>
                            <td>${d.latencyMs}ms</td>
                        </tr>`
                    ).join('');

                    let grps = await grpRes.json();
                    let gc = document.getElementById('groupCards');
                    gc.innerHTML = grps.length === 0 ? '<span style="color:var(--muted)">等待数据...</span>'
                        : grps.map(g =>
                        `<div class="group-card">
                            <div class="gid">群 ${g.groupId}</div>
                            <div class="row">
                                <span>消息 <span style="color:var(--text)">${g.messages}</span></span>
                                <span>回复 <span style="color:var(--green)">${g.replies}</span></span>
                                <span>静默 <span style="color:var(--muted)">${g.silent}</span></span>
                                <span>错误 <span style="color:var(--red)">${g.errors}</span></span>
                                <span>Token <span style="color:var(--text)">${g.totalTokens}</span></span>
                                <span>平均延迟 <span style="color:var(--yellow)">${g.avgLatencyMs}ms</span></span>
                            </div>
                            <div style="font-size:0.7rem;color:var(--muted);margin-top:2px;">最近活跃 ${g.lastActive}</div>
                        </div>`
                    ).join('');

                    let ts = document.getElementById('toolStats');
                    ts.innerHTML = sys.topTools.map(t =>
                        `<div class="tool-row"><span class="name">${t.name}</span><span class="count">${t.count}</span></div>`
                    ).join('') || '<span style="color:var(--muted)">暂无</span>';

                } catch(e) { console.error(e); }
            }
            refresh();
            setInterval(refresh, 3000);
            // 如果 API 返回 401，提示需要 token
            fetch(auth('/api/system')).then(r => { if(r.status===401) document.body.innerHTML='<div style="text-align:center;padding:60px;color:var(--muted);"><h2>需要鉴权</h2><p>请在 URL 后添加 <code>?token=你的Token</code></p></div>'; });
            </script>
            </body>
            </html>
            """;
}
