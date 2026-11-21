package com.xalgocapital.claude.s03;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * s03: 带权限闸门的 Agent 循环。
 *
 * 和 s02 的循环逐行对照，只在一处加了几行——工具真正执行之前：
 *   s02: String out = registry.dispatch(name, args);   // 照单全收，直接执行
 *   s03: if (!guard.checkAndApprove(name, args)) {      // ← 新增：三道闸门
 *            results.add("Permission denied.");
 *            continue;                                  // 被拒就跳过执行
 *        }
 *        String out = registry.dispatch(name, args);    // s02 原有
 *
 * while(true) 那段——调用模型、追加消息、扫 tool_calls、逐个执行、喂回——结构没动。
 * 权限是「挂在执行前的一道门」，不是改循环。这就是这一篇要证明的事。
 */
public class AgentLoop {
    private final OpenAICompatClient client;
    private final ObjectMapper mapper;
    private final ToolRegistry registry;
    private final PermissionGuard guard;

    public AgentLoop(OpenAICompatClient client) {
        this.client = client;
        this.mapper = client.mapper();
        this.registry = new ToolRegistry(mapper);
        BuiltinTools.install(registry);   // 5 个工具，一次注册（同 s02）
        this.guard = new PermissionGuard();
    }

    public void run(String query) {
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode system = mapper.createObjectNode();
        system.put("role", "system");
        system.put("content", SYSTEM);
        messages.add(system);

        ObjectNode first = mapper.createObjectNode();
        first.put("role", "user");
        first.put("content", query);
        messages.add(first);

        ArrayNode tools = registry.schemas();

        while (true) {
            JsonNode response = client.createChatCompletion(messages, tools);
            JsonNode choice = response.path("choices").path(0);
            JsonNode message = choice.path("message");

            messages.add(message);   // 整条回传，带 tool_calls

            // 不靠 finish_reason，扫 tool_calls（同 s01/s02）
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isMissingNode() || !toolCalls.isArray() || toolCalls.isEmpty()) {
                printText(message);
                return;
            }

            for (JsonNode call : toolCalls) {
                String id = call.path("id").asText();
                String name = call.path("function").path("name").asText();

                JsonNode argsNode = call.path("function").path("arguments");
                JsonNode args = parseArgs(argsNode);

                System.out.println("\n→ " + name + "(" + args + ")");

                // ===== s03 新增：执行前的权限闸门 =====
                if (!guard.checkAndApprove(name, args)) {
                    // 被拒也要喂一条结果回去，否则模型不知道工具没跑、会卡在等待
                    ObjectNode denied = mapper.createObjectNode();
                    denied.put("role", "tool");
                    denied.put("tool_call_id", id);
                    denied.put("content", "Permission denied by permission guard.");
                    messages.add(denied);
                    continue;
                }
                // ====================================

                String output = registry.dispatch(name, args);
                System.out.println(output);

                ObjectNode toolResult = mapper.createObjectNode();
                toolResult.put("role", "tool");
                toolResult.put("tool_call_id", id);
                toolResult.put("content", output);
                messages.add(toolResult);
            }
        }
    }

    private JsonNode parseArgs(JsonNode node) {
        if (node.isTextual()) {
            try {
                return mapper.readTree(node.asText());
            } catch (Exception e) {
                return mapper.createObjectNode();
            }
        }
        return node.isObject() ? node : mapper.createObjectNode();
    }

    private void printText(JsonNode message) {
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            System.out.println("\n" + content.asText());
        }
    }

    public static void main(String[] args) {
        String baseUrl = System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.deepseek.com/v1");
        String apiKey = System.getenv("OPENAI_API_KEY");
        String model = System.getenv().getOrDefault("MODEL_ID", "deepseek-chat");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("请先设置环境变量 OPENAI_API_KEY");
            System.exit(1);
        }
        String query = args.length > 0
                ? String.join(" ", args)
                : "Read README.md, then try to clean up /tmp with rm.";
        new AgentLoop(new OpenAICompatClient(baseUrl, apiKey, model)).run(query);
    }

    private static final String SYSTEM = """
            You are a helpful coding agent. Use the provided tools to inspect and modify files.
            Prefer read_file / write_file / edit_file / glob over shell commands when possible.
            When the task is done, answer in plain text without calling a tool.
            """;
}
