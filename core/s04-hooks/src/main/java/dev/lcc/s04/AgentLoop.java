package dev.lcc.s04;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * s04: 把扩展挂到循环上，而不是写进循环里。
 *
 * 和 s03 对照，循环体的变化只有几处——每个变化都是一行 trigger(...)：
 *   s03: if (!guard.checkAndApprove(name, args)) { ... }   // 硬编码调检查函数
 *   s04: blocked = hooks.trigger(Event.preTool(name, args)); // ← 只调 trigger
 *        if (blocked != null) { ... }                         // 非 null = 拦截
 *
 * 另外三个挂点是 s03 完全没有的：
 *   进 LLM 前   trigger(Event.userPrompt(query))
 *   工具执行后  trigger(Event.postTool(name, args, output))
 *   退出前      trigger(Event.stop(toolCount))  → 非 null 强制续跑
 *
 * while(true) 那段——调用模型、追加消息、扫 tool_calls、逐个执行、喂回——结构没动。
 * 这就是这一篇要证明的：行为可以无限加，循环保持稳定。
 */
public class AgentLoop {
    private final OpenAICompatClient client;
    private final ObjectMapper mapper;
    private final ToolRegistry registry;
    private final HookRegistry hooks;

    public AgentLoop(OpenAICompatClient client) {
        this.client = client;
        this.mapper = client.mapper();
        this.registry = new ToolRegistry(mapper);
        BuiltinTools.install(registry);   // 5 个工具（同 s02/s03）
        this.hooks = new HookRegistry();

        // 所有扩展在这里一次性注册——循环里再也看不到它们的名字
        hooks.register("UserPromptSubmit", BuiltinHooks.contextInjectHook());
        hooks.register("PreToolUse", BuiltinHooks.permissionHook());
        hooks.register("PreToolUse", BuiltinHooks.logHook());
        hooks.register("PostToolUse", BuiltinHooks.largeOutputHook());
        hooks.register("Stop", BuiltinHooks.summaryHook());
    }

    public void run(String query) {
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode system = mapper.createObjectNode();
        system.put("role", "system");
        system.put("content", SYSTEM);
        messages.add(system);

        // 挂点1：用户输入后、进 LLM 前
        hooks.trigger(Event.userPrompt(query));

        ObjectNode first = mapper.createObjectNode();
        first.put("role", "user");
        first.put("content", query);
        messages.add(first);

        ArrayNode tools = registry.schemas();
        int toolCount = 0;

        while (true) {
            JsonNode response = client.createChatCompletion(messages, tools);
            JsonNode choice = response.path("choices").path(0);
            JsonNode message = choice.path("message");

            messages.add(message);   // 整条回传，带 tool_calls（同 s01-s03）

            // 不靠 finish_reason，扫 tool_calls（同 s01-s03）
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isMissingNode() || !toolCalls.isArray() || toolCalls.isEmpty()) {
                // 挂点4：退出前。返回非 null = 强制续跑
                String force = hooks.trigger(Event.stop(toolCount));
                if (force != null) {
                    ObjectNode injection = mapper.createObjectNode();
                    injection.put("role", "user");
                    injection.put("content", force);
                    messages.add(injection);
                    continue;
                }
                printText(message);
                return;
            }

            for (JsonNode call : toolCalls) {
                String id = call.path("id").asText();
                String name = call.path("function").path("name").asText();
                JsonNode args = parseArgs(call.path("function").path("arguments"));

                System.out.println("\n→ " + name + "(" + args + ")");

                // 挂点2：工具执行前。非 null = 拦截，字符串当拒绝原因喂回模型
                // s03 这里是 guard.checkAndApprove(...)；s04 只认 trigger
                String blocked = hooks.trigger(Event.preTool(name, args));
                if (blocked != null) {
                    ObjectNode denied = mapper.createObjectNode();
                    denied.put("role", "tool");
                    denied.put("tool_call_id", id);
                    denied.put("content", blocked);
                    messages.add(denied);
                    continue;
                }

                String output = registry.dispatch(name, args);
                toolCount++;

                // 挂点3：工具执行后
                hooks.trigger(Event.postTool(name, args, output));

                System.out.println(output);
                ObjectNode toolResult = mapper.createObjectNode();
                toolResult.put("role", "tool");
                toolResult.put("tool_call_id", id);
                toolResult.put("content", output);
                messages.add(toolResult);
            }
        }
    }

    /** arguments 兼容两种形态：JSON 字符串 或 已解析对象（同 s01-s03） */
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
                : "Read README.md, then create a test file.";
        new AgentLoop(new OpenAICompatClient(baseUrl, apiKey, model)).run(query);
    }

    private static final String SYSTEM = """
            You are a helpful coding agent. Use the provided tools to inspect and modify files.
            Prefer read_file / write_file / edit_file / glob over shell commands when possible.
            When the task is done, answer in plain text without calling a tool.
            """;
}
