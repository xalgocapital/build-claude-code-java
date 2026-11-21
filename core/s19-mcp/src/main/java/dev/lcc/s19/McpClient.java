package dev.lcc.s19;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * s19：stdio 上的 MCP 客户端。不依赖任何 MCP SDK，就三件事。
 *
 * 1. 起进程、按行读写。一条消息一行 JSON，这是 stdio 传输的全部约定。
 * 2. 发出去的请求先在 pending 里挂一个 future，读线程按响应里的 id 唤醒它。
 *    不能假设「发一条、收一条」——server 每个请求一个线程，慢的那个后回。
 * 3. 每次等待都有上限，且超时和「进程死了」分开：前者是慢，后者要重连。
 *
 * 重连也在这里。进程死了就重新起一个、重做握手；工具表不重新拉。
 * id 不归零，日志里能分出重连前后，乱序断言也不会被重复 id 骗过去。
 */
public class McpClient {
    /** 握手时带的协议版本。2024-11-05 是第一批 MCP server 普遍实现的版本 */
    public static final String PROTOCOL_VERSION = "2024-11-05";
    public static final String CLIENT_NAME = "lcc-s19";
    public static final String CLIENT_VERSION = "1.0.0";

    /** JSON-RPC 的 error 字段：对面明确说这次调用不行 */
    public static class McpError extends RuntimeException {
        public McpError(String message) {
            super(message);
        }
    }

    /** 等超时了。区别于「进程死了」：对面可能只是慢 */
    public static class McpTimeout extends McpError {
        private final long ms;

        public McpTimeout(String message, long ms) {
            super(message);
            this.ms = ms;
        }

        public long ms() {
            return ms;
        }
    }

    /** 管道断了 / 进程没了 / 写不进去。这一类可以靠重连救 */
    public static class McpDead extends McpError {
        public McpDead(String message) {
            super(message);
        }
    }

    private static final class Pending {
        final int id;
        final String method;
        final CompletableFuture<JsonNode> future = new CompletableFuture<>();

        Pending(int id, String method) {
            this.id = id;
            this.method = method;
        }
    }

    private final String command;
    private final long timeoutMs;
    private final ObjectMapper mapper;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    private volatile Process process;
    private volatile BufferedWriter stdin;
    private volatile boolean alive;
    /** 每次 launch 加一。旧读线程结束时不能把新连接的 alive 改回 false */
    private volatile int generation;
    private int restarts;
    private int timeouts;

    public McpClient(String command, ObjectMapper mapper) {
        this.command = command;
        this.mapper = mapper;
        this.timeoutMs = Long.parseLong(System.getenv().getOrDefault("MCP_TIMEOUT_MS", "2000"));
    }

    public void start() {
        launch();
        handshake();
    }

    private void launch() {
        generation++;
        int gen = generation;
        ProcessBuilder pb = new ProcessBuilder(Arrays.asList(command.trim().split("\\s+")));
        pb.redirectErrorStream(false);   // stderr 是日志，不能混进 JSON 的 stdout
        try {
            Process p = pb.start();
            process = p;
            stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            alive = true;
            System.out.println("   [mcp] 启动 " + command + "（pid " + p.pid() + "）");
        } catch (IOException e) {
            throw new McpDead("起不了进程：" + e.getMessage());
        }
        daemon("mcp-stdout-" + gen, () -> readLoop(gen, process));
        daemon("mcp-stderr-" + gen, () -> errLoop(process));
    }

    private static void daemon(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        t.start();
    }

    /**
     * 读 stdout，一行一条消息。唯一允许的同步假设是「响应里带 id」。
     * 通知、server 发起的请求、不是 JSON 的脏数据，都不能让读线程死掉。
     */
    private void readLoop(int gen, Process p) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                JsonNode msg;
                try {
                    msg = mapper.readTree(line);
                } catch (Exception e) {
                    System.out.println("   [mcp] 非 JSON 输出，忽略：" + clip(line));
                    continue;
                }
                String method = msg.path("method").asText("");
                if (msg.has("id") && !method.isEmpty()) {
                    // server 反过来请求客户端。这一篇不需要 client 侧能力
                    ObjectNode err = mapper.createObjectNode();
                    err.put("jsonrpc", "2.0");
                    err.set("id", msg.get("id"));
                    ObjectNode body = mapper.createObjectNode();
                    body.put("code", -32601);
                    body.put("message", "client does not implement " + method);
                    err.set("error", body);
                    writeLine(err);
                    continue;
                }
                if (!msg.has("id")) {
                    continue;   // 通知，没有东西要配对
                }
                Pending p2 = pending.remove(msg.get("id").asInt());
                if (p2 != null) {
                    p2.future.complete(msg);
                } else {
                    // 迟到的响应：超时那一条已经从表里删了，不能当成新请求
                    System.out.println("   [mcp] 丢弃迟到的响应 id=" + msg.get("id").asInt());
                }
            }
        } catch (IOException ignored) {
            // 流断了，finally 统一处理
        } finally {
            if (gen == generation) {
                alive = false;
                failAll("server 进程退出（pid " + p.pid() + "）");
            }
        }
    }

    private void errLoop(Process p) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                System.out.println("   [mcp:server] " + line);
            }
        } catch (IOException ignored) {
            // 进程结束了，正常
        }
    }

    private void handshake() {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", mapper.createObjectNode());
        ObjectNode info = mapper.createObjectNode();
        info.put("name", CLIENT_NAME);
        info.put("version", CLIENT_VERSION);
        params.set("clientInfo", info);

        JsonNode res = request("initialize", params);
        System.out.println("   [mcp] initialize → protocolVersion=" + res.path("protocolVersion").asText()
                + "，server=" + res.path("serverInfo").path("name").asText()
                + "/" + res.path("serverInfo").path("version").asText());
        notify("notifications/initialized");
    }

    /**
     * 同时发两条、让先发的那条后回。这不是演示，是启动自检：
     * 谁把 pending 换成「发一条等一条」，这里会打印失败，而不是偶发地配错结果。
     */
    public void selfCheck() {
        ObjectNode slowParams = mapper.createObjectNode();
        slowParams.put("name", "echo");
        slowParams.set("arguments", mapper.createObjectNode().put("text", "SLOW"));
        ObjectNode fastParams = mapper.createObjectNode();
        fastParams.put("name", "add");
        ObjectNode fastArgs = mapper.createObjectNode();
        fastArgs.put("a", 20);
        fastArgs.put("b", 22);
        fastParams.set("arguments", fastArgs);

        Pending slow = send("tools/call", slowParams);
        Pending fast = send("tools/call", fastParams);
        String fastText = firstText(await(fast));
        String slowText = firstText(await(slow));
        if ("42".equals(fastText) && "slow-ok".equals(slowText)) {
            System.out.println("   [mcp] 乱序自检通过：id " + fast.id + " 先回 42，id "
                    + slow.id + " 后回 slow-ok");
        } else {
            System.out.println("   [mcp] 乱序自检失败：id " + fast.id + "=" + fastText
                    + "，id " + slow.id + "=" + slowText);
        }
    }

    public JsonNode request(String method, JsonNode params) {
        return await(send(method, params));
    }

    public JsonNode callTool(String name, JsonNode arguments) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        return request("tools/call", params);
    }

    public void notify(String method) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        writeLine(msg);
    }

    private Pending send(String method, JsonNode params) {
        if (!alive) {
            throw new McpDead("server 不在运行");
        }
        int id = ids.incrementAndGet();
        Pending p = new Pending(id, method);
        pending.put(id, p);
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (params != null && !params.isMissingNode()) {
            msg.set("params", params);
        }
        try {
            writeLine(msg);
        } catch (McpDead e) {
            pending.remove(id);
            throw e;
        }
        return p;
    }

    private void writeLine(JsonNode msg) {
        try {
            String line = mapper.writeValueAsString(msg);
            synchronized (writeLock) {
                stdin.write(line);
                stdin.newLine();
                stdin.flush();
            }
        } catch (IOException e) {
            alive = false;
            throw new McpDead("写 stdin 失败：" + e.getMessage());
        }
    }

    private JsonNode await(Pending p) {
        try {
            JsonNode msg = p.future.get(timeoutMs, TimeUnit.MILLISECONDS);
            JsonNode err = msg.path("error");
            if (!err.isMissingNode()) {
                throw new McpError("JSON-RPC error " + err.path("code").asInt()
                        + "：" + err.path("message").asText());
            }
            return msg.path("result");
        } catch (TimeoutException e) {
            pending.remove(p.id);
            timeouts++;
            // 超时的两种原因要分开：进程已经死了，重试同一个连接是白等
            if (!alive) {
                throw new McpDead("等了 " + timeoutMs + "ms 没有响应，进程已退出");
            }
            throw new McpTimeout("等 " + p.method + " 的响应超时（" + timeoutMs + "ms）", timeoutMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof McpError me) {
                throw me;
            }
            throw new McpDead(cause == null ? String.valueOf(e.getMessage()) : cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpDead("被中断");
        }
    }

    public synchronized void restart() {
        restarts++;
        alive = false;
        closeProcess();
        failAll("重建连接");
        pending.clear();
        System.out.println("   [mcp] 重连第 " + restarts + " 次（旧进程已不在）");
        launch();
        handshake();
    }

    private void failAll(String reason) {
        for (Integer id : pending.keySet()) {
            Pending p = pending.remove(id);
            if (p != null) {
                p.future.completeExceptionally(new McpDead(reason));
            }
        }
    }

    private void closeProcess() {
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
            // 已经断了
        }
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
    }

    private static String firstText(JsonNode result) {
        for (JsonNode block : result.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                return block.path("text").asText();
            }
        }
        return "";
    }

    private static String clip(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }

    public int restarts() {
        return restarts;
    }

    public int timeouts() {
        return timeouts;
    }

    public long timeoutMs() {
        return timeoutMs;
    }
}
