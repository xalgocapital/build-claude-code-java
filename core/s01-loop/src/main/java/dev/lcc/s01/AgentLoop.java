package dev.lcc.s01;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * s01: 最小 Agent 循环。OpenAI 兼容方言版。
 *
 * 全部机制就一个 while(true)：
 *   1. 把 messages + tools 发给模型
 *   2. 把模型回答追加进 messages
 *   3. 回答里没有 tool_calls -> 退出
 *   4. 有 tool_calls -> 执行，结果作为新消息追加，回到第 1 步
 *
 * 关键决策：不看 finish_reason，扫 tool_calls 数组。
 *   Claude Code 判断要不要继续时不看 stop_reason，而是扫消息里有没有 tool_use 块
 *   （流式响应里 stop_reason 可能还没更新）。
 *   OpenAI 兼容层里这个坑更大——finish_reason 的字符串各家不统一
 *   （OpenAI 是 "tool_calls"，别家可能不同）。所以更该扫消息里的 tool_calls 本身。
 *
 * 这一版只有 bash 一个工具，也没有权限检查（s03 才加）。
 * 请在临时目录运行——模型生成的命令会真实执行。
 */
public class AgentLoop {
    private final OpenAICompatClient client;
    private final ObjectMapper mapper;

    public AgentLoop(OpenAICompatClient client) {
        this.client = client;
        this.mapper = client.mapper();
    }

    public void run(String query) {
        // 第 1 步：system + 用户的问题
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode system = mapper.createObjectNode();
        system.put("role", "system");
        system.put("content", SYSTEM);
        messages.add(system);

        ObjectNode first = mapper.createObjectNode();
        first.put("role", "user");
        first.put("content", query);
        messages.add(first);

        ArrayNode tools = buildTools();

        while (true) {
            // 第 2 步：发给模型
            JsonNode response = client.createChatCompletion(messages, tools);
            JsonNode choice = response.path("choices").path(0);
            JsonNode message = choice.path("message");

            // 第 3 步：把模型回答整条追加进 messages
            // 注意要带 tool_calls 字段原样回传，否则下一轮协议校验会失败
            messages.add(message);

            // 第 4 步：不靠 finish_reason，直接扫 tool_calls 判断要不要继续
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isMissingNode() || !toolCalls.isArray() || toolCalls.isEmpty()) {
                printText(message);
                return;
            }

            // 第 5 步：逐个执行工具，收集结果
            // OpenAI 格式里结果用 role="tool" 的消息，带 tool_call_id
            for (JsonNode call : toolCalls) {
                String id = call.path("id").asText();
                String name = call.path("function").path("name").asText();

                // 坑 1：arguments 类型不统一——OpenAI 返回 JSON 字符串，
                // 有些兼容后端返回已解析的对象。两种都要能吃。
                JsonNode argsNode = call.path("function").path("arguments");
                JsonNode args = parseArgs(argsNode);
                String command = args.path("command").asText("");

                System.out.println("\n$ " + command);
                String output = runBash(command);
                System.out.println(output);

                ObjectNode toolResult = mapper.createObjectNode();
                toolResult.put("role", "tool");
                toolResult.put("tool_call_id", id);
                toolResult.put("content", output);
                messages.add(toolResult);
            }
            // 回到第 1 步
        }
    }

    /** 兼容 arguments 的两种形态：JSON 字符串 / 已解析对象 */
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

    private ArrayNode buildTools() {
        // OpenAI 格式：tools[].function.parameters（不是 Anthropic 的 input_schema）
        ArrayNode tools = mapper.createArrayNode();
        ObjectNode entry = mapper.createObjectNode();
        entry.put("type", "function");
        ObjectNode fn = mapper.createObjectNode();
        fn.put("name", "bash");
        fn.put("description", "Run a shell command in the current working directory.");
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        ObjectNode command = mapper.createObjectNode();
        command.put("type", "string");
        command.put("description", "The shell command to execute");
        props.set("command", command);
        schema.set("properties", props);
        schema.putArray("required").add("command");
        fn.set("parameters", schema);
        entry.set("function", fn);
        tools.add(entry);
        return tools;
    }

    /** 真实执行 shell 命令。当前工作目录就是进程启动目录。 */
    private String runBash(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(Path.of(".").toFile());
            pb.redirectErrorStream(true);  // stderr 也回传给模型，否则报错信息看不见
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "(command timed out)";
            }
            return out.toString().strip();
        } catch (Exception e) {
            return "(error: " + e.getMessage() + ")";
        }
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
                : "List the files in the current directory, then tell me how many there are.";
        new AgentLoop(new OpenAICompatClient(baseUrl, apiKey, model)).run(query);
    }

    private static final String SYSTEM = """
            You are a helpful coding agent. Use the Bash tool to inspect and modify files.
            When the task is done, answer in plain text without calling a tool.
            """;
}
