package dev.lcc.s19;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * s19：把远端的 MCP 工具搬进本地的 ToolRegistry。
 *
 * 名字加三段前缀 mcp__<server>__<tool>，例如 mcp__demo__echo：
 * 两个 server 都有 echo 时不互相覆盖；模型看得出这不是本机工具；日志里能定位进程。
 *
 * schema 原样透传——server 给的 inputSchema 直接当 OpenAI 的 parameters，
 * 中间不翻译就不会丢掉 required、enum、嵌套对象。
 * 结果只拼 content[] 里的 text；isError 转成 "(error: ...)"，
 * 交给 AgentLoop 包成 <tool-error>（s11 的约定）。
 */
public class McpTools {
    public static final String PREFIX = "mcp__";

    private final McpClient client;
    private final String server;

    private McpTools(McpClient client, String server) {
        this.client = client;
        this.server = server;
    }

    /**
     * 握手 → 列工具 → 每个工具往 registry 里注册一条。返回 null 表示没配 MCP_SERVER。
     */
    public static McpTools install(ToolRegistry registry, ObjectMapper mapper) {
        String command = System.getenv().getOrDefault("MCP_SERVER", "").trim();
        if (command.isEmpty()) {
            System.out.println("   [mcp] 没设 MCP_SERVER，只用本地工具");
            return null;
        }
        String server = System.getenv().getOrDefault("MCP_SERVER_NAME", "demo");

        McpClient client = new McpClient(command, mapper);
        client.start();
        client.selfCheck();

        JsonNode listed = client.request("tools/list", mapper.createObjectNode());
        McpTools bridge = new McpTools(client, server);
        List<String> names = new ArrayList<>();
        for (JsonNode tool : listed.path("tools")) {
            String remote = tool.path("name").asText("");
            if (remote.isEmpty()) {
                continue;
            }
            String exposed = PREFIX + server + "__" + remote;
            ObjectNode schema = tool.path("inputSchema").isObject()
                    ? (ObjectNode) tool.path("inputSchema")
                    : emptySchema(mapper);
            registry.register(exposed,
                    tool.path("description").asText("") + " (MCP server=" + server + ", tool=" + remote + ")",
                    schema,
                    args -> bridge.call(remote, args));
            names.add(exposed);
        }
        System.out.println("   [mcp] 已接入 " + server + "：" + names.size()
                + " 个工具 " + String.join(", ", names));
        return bridge;
    }

    /**
     * 一次调用。失败分三种，处理方式不同：
     *   isError=true   对面说这次调用失败 → 原样回灌给模型，它自己会换办法
     *   超时           对面慢/卡住 → 不重试（重试只是再等一个超时）
     *   进程死了       连接没了 → 重连一次再发，只重连一次
     */
    private String call(String remote, JsonNode args) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return render(remote, client.callTool(remote, args));
            } catch (McpClient.McpTimeout t) {
                return "(error: mcp timeout " + t.ms() + "ms calling " + remote + ")";
            } catch (McpClient.McpDead d) {
                if (attempt == 2) {
                    return "(error: mcp server is down: " + d.getMessage() + ")";
                }
                try {
                    client.restart();
                } catch (RuntimeException e) {
                    return "(error: mcp reconnect failed: " + e.getMessage() + ")";
                }
            } catch (McpClient.McpError e) {
                return "(error: mcp " + remote + ": " + e.getMessage() + ")";
            }
        }
        return "(error: mcp " + remote + ": gave up)";
    }

    private static String render(String remote, JsonNode result) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            if ("text".equals(block.path("type").asText(""))) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(block.path("text").asText(""));
            }
        }
        if (sb.length() == 0) {
            return "(error: mcp " + remote + " returned no text content)";
        }
        String text = sb.toString();
        return result.path("isError").asBoolean(false) ? "(error: " + text + ")" : text;
    }

    private static ObjectNode emptySchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", mapper.createObjectNode());
        schema.set("required", mapper.createArrayNode());
        return schema;
    }

    public String report() {
        return "server=" + server + "，重连 " + client.restarts() + " 次，超时 "
                + client.timeouts() + " 次，单次上限 " + client.timeoutMs() + "ms";
    }
}
