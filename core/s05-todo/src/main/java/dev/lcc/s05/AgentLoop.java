package dev.lcc.s05;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * s05: 给 Agent 一层显式计划。
 *
 * 相对 s04 只有两处新增，循环的执行主干（调模型→扫 tool_calls→dispatch→回灌）没动：
 *   1) 工具分发表注册第 6 个工具 todo_write —— 它不碰文件、不跑命令，只记计划
 *   2) roundsSinceTodo 计数器：连续 3 轮没调 todo_write，下一轮前注入一条提醒
 *
 * s04 的 hook 结构原样保留——这几篇是层层叠加的：循环 → 分发表 → 权限 → hook → 计划。
 */
public class AgentLoop {
    private static final int NAG_AFTER = 3;   // 连续几轮没更新就提醒

    private final OpenAICompatClient client;
    private final ObjectMapper mapper;
    private final ToolRegistry registry;
    private final HookRegistry hooks;
    private final TodoStore todos;

    public AgentLoop(OpenAICompatClient client) {
        this.client = client;
        this.mapper = client.mapper();
        this.registry = new ToolRegistry(mapper);
        BuiltinTools.install(registry);   // 5 个老工具

        // s05 新增：第 6 个工具注册进同一张分发表，循环只认 dispatch
        this.todos = new TodoStore(mapper);
        registry.register("todo_write", TodoStore.DESCRIPTION, todos.schema(), todos::update);

        this.hooks = new HookRegistry();
        // s04 的 hook 原样挂着，本篇不动
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

        hooks.trigger(Event.userPrompt(query));

        ObjectNode first = mapper.createObjectNode();
        first.put("role", "user");
        first.put("content", query);
        messages.add(first);

        ArrayNode tools = registry.schemas();
        int toolCount = 0;
        int roundsSinceTodo = 0;

        while (true) {
            // s05 新增的第二个东西：nag reminder。
            // 连续 NAG_AFTER 轮没调 todo_write，就在下一次 LLM 调用前塞一条 user 提醒，
            // 让「更新计划」重新进入模型注意力。注入后计数归零，避免每轮都提醒。
            if (roundsSinceTodo >= NAG_AFTER) {
                ObjectNode reminder = mapper.createObjectNode();
                reminder.put("role", "user");
                reminder.put("content",
                        "<reminder>Update your todos: mark finished items completed and pick the next pending one.</reminder>");
                messages.add(reminder);
                System.out.println("\n   [NAG] 连续 " + roundsSinceTodo + " 轮没更新 todo，注入提醒");
                roundsSinceTodo = 0;
            }

            JsonNode response = client.createChatCompletion(messages, tools);
            JsonNode choice = response.path("choices").path(0);
            JsonNode message = choice.path("message");
            messages.add(message);   // 整条回传，带 tool_calls（同前几篇）

            JsonNode toolCalls = message.path("tool_calls");
            boolean hasTools = toolCalls.isArray() && !toolCalls.isEmpty();

            // 本轮有没有调 todo_write：调了就归零，没调就 +1
            if (hasTools && usedTodo(toolCalls)) {
                roundsSinceTodo = 0;
            } else if (hasTools) {
                roundsSinceTodo++;
            }
            // 无 tool_calls 的一轮（准备退出）不计入 —— 不然收尾的文字回复也会触发 nag

            if (!hasTools) {
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

                System.out.println("\n→ " + name + "(" + (name.equals("todo_write") ? brief(args) : args) + ")");

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

    /** 本轮 tool_calls 里有没有 todo_write */
    private boolean usedTodo(JsonNode toolCalls) {
        for (JsonNode call : toolCalls) {
            if ("todo_write".equals(call.path("function").path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    /** todo_write 参数太长，打印时压成一行摘要 */
    private String brief(JsonNode args) {
        JsonNode arr = args.path("todos");
        return arr.isArray() ? "[" + arr.size() + " tasks]" : args.toString();
    }

    /** arguments 兼容两种形态：JSON 字符串 或 已解析对象（同前几篇） */
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
                : "Refactor 3 files: add type hints, write tests, then run them.";
        new AgentLoop(new OpenAICompatClient(baseUrl, apiKey, model)).run(query);
    }

    private static final String SYSTEM = """
            You are a helpful coding agent. Use the provided tools to inspect and modify files.
            For any multi-step task, call todo_write FIRST to lay out a plan, then mark each item
            in_progress / completed as you make progress. Keep the plan up to date.
            Prefer read_file / write_file / edit_file / glob over shell commands when possible.
            When the task is done, answer in plain text without calling a tool.
            """;
}
