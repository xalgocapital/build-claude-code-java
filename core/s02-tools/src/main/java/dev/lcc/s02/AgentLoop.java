package dev.lcc.s02;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * s02: 带工具分发表的 Agent 循环。
 *
 * 和 s01 的循环逐行对照，只有两处变化，都在「执行」那个位置：
 *   s01: ArrayNode tools = buildTools();              // 写死一个 bash
 *        String out = runBash(command);                // 写死调用 bash
 *   s02: ArrayNode tools = registry.schemas();         // 从分发表拿工具清单
 *        String out = registry.dispatch(name, args);   // 按名字查表调用
 *
 * while(true) 那一段——调用模型、追加消息、扫 tool_calls、逐个执行、喂回——一行没动。
 * 这就是这一篇要证明的事：能力靠注册扩展，循环不动。
 */
public class AgentLoop {
    private final OpenAICompatClient client;
    private final ObjectMapper mapper;
    private final ToolRegistry registry;

    public AgentLoop(OpenAICompatClient client) {
        this.client = client;
        this.mapper = client.mapper();
        this.registry = new ToolRegistry(mapper);
        BuiltinTools.install(registry);   // 5 个工具，一次注册
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

        // 【变化 1】工具清单从分发表来，不再写死
        ArrayNode tools = registry.schemas();

        while (true) {
            JsonNode response = client.createChatCompletion(messages, tools);
            JsonNode choice = response.path("choices").path(0);
            JsonNode message = choice.path("message");

            messages.add(message);   // 整条回传，带 tool_calls

            // 和 s01 一样：不靠 finish_reason，扫 tool_calls
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isMissingNode() || !toolCalls.isArray() || toolCalls.isEmpty()) {
                printText(message);
                return;
            }

            // 一个 prompt 模型可能一次返回多个 tool_call，这里按原始顺序逐个执行
            for (JsonNode call : toolCalls) {
                String id = call.path("id").asText();
                String name = call.path("function").path("name").asText();

                // arguments 两种形态都吃（同 s01）
                JsonNode argsNode = call.path("function").path("arguments");
                JsonNode args = parseArgs(argsNode);

                System.out.println("\n→ " + name + "(" + args + ")");

                // 【变化 2】查表执行，替代写死的 runBash
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
                : "Read the file README.md and tell me what this project is about.";
        new AgentLoop(new OpenAICompatClient(baseUrl, apiKey, model)).run(query);
    }

    private static final String SYSTEM = """
            You are a helpful coding agent. Use the provided tools to inspect and modify files.
            Prefer read_file / write_file / edit_file / glob over shell commands when possible.
            When the task is done, answer in plain text without calling a tool.
            """;
}
